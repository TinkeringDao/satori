package com.satori.data.monitor

import kotlinx.coroutines.delay
import java.util.concurrent.CancellationException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * One model load at a time, every load of the app ([ModelLoadPacing]): on 2026-10-13 at 14:21:40
 * the live Gemma 3 1B and Parakeet v3 loaded at once and 4 cached apps were killed.
 *
 * The recorder's own load, its speech model, never waits for the gate: the owner starts a
 * recording with the quick key at any moment, and its first words are kept only while the audio
 * queue holds them. It goes ahead of any other load instead ([recorder]), and the other loads give
 * way to it at their next piece, a read of 8 MiB or a tensor llama.cpp copies ([recorderLoading]
 * is their [Pace]'s mustYield); one that is about to start waits until it is done. Only a native
 * step already under way, which nothing can pause (ONNX Runtime making its sessions, llama.cpp
 * making its context), runs on beside it.
 *
 * Every other load waits for the gate, then for the recorder's load if one runs, in [acquire]
 * (blocking, a speech model's load on IO) or [acquireSuspending] (an AI model's, on the engine's
 * thread, which it doesn't block meanwhile), and gives it back with [release].
 *
 * @param pollMs How often a waiting load looks again
 */
internal class ModelLoadGate(private val pollMs: Long = Pace.POLL_MS) {

    private val permit = Semaphore(1, true)
    private val recorderLoads = AtomicInteger()

    /** The load holding the gate now, by its model, or null. */
    @Volatile
    var holder: String? = null
        private set

    /** Whether the recorder's own load runs now; other loads give way to it. */
    val recorderLoading: Boolean
        get() = recorderLoads.get() > 0

    /** Runs [block], the recorder's load, at once, while the other loads give way to it. */
    fun <T> recorder(block: () -> T): T {
        recorderLoads.incrementAndGet()
        try {
            return block()
        } finally {
            recorderLoads.decrementAndGet()
        }
    }

    /**
     * Waits for the gate for [name], then while the recorder's load runs; returns how long, in ms.
     *
     * @throws CancellationException once [cancelled] while it waits; the gate is not held then
     */
    fun acquire(name: String, cancelled: () -> Boolean): Long {
        val start = System.nanoTime()
        while (!permit.tryAcquire(pollMs, TimeUnit.MILLISECONDS)) {
            if (cancelled()) throw CancellationException("The load of $name was cancelled while it waited")
        }
        holder = name
        try {
            while (recorderLoading) {
                if (cancelled()) throw CancellationException("The load of $name was cancelled while it waited")
                Thread.sleep(pollMs)
            }
        } catch (e: Throwable) {
            release()
            throw e
        }
        return (System.nanoTime() - start) / 1_000_000
    }

    /** [acquire] for a coroutine, which is suspended, not blocked, while it waits; cancelled as it is. */
    suspend fun acquireSuspending(name: String): Long {
        val start = System.nanoTime()
        while (!permit.tryAcquire()) delay(pollMs)
        holder = name
        try {
            while (recorderLoading) delay(pollMs)
        } catch (e: Throwable) {
            release()
            throw e
        }
        return (System.nanoTime() - start) / 1_000_000
    }

    /** Gives the gate back after [acquire] or [acquireSuspending]. */
    fun release() {
        holder = null
        permit.release()
    }
}
