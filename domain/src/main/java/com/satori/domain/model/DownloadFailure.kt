package com.satori.domain.model

import java.io.EOFException
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Why a model download failed, in the terms a person can act on. It decides which sentence the
 * app shows; the exception behind it is only logged.
 */
enum class DownloadFailure {
    /** No usable internet connection, or it dropped on the way: airplane mode, no signal, a timeout. */
    NO_CONNECTION,

    /** The phone has no room left for the file. */
    NOT_ENOUGH_STORAGE,

    /** The download server answered with an error. */
    SERVER,

    /**
     * Android says the phone is online, yet Satori can't connect: a firewall or a data setting
     * blocks this app, which "Connect to Wi-Fi" would only send the wrong way. A socket Android
     * refuses to this app (EPERM) says so at once; a run of failed connects on a network Android
     * has validated says so too ([com.satori.domain.firstrun.NetworkPolicy.failure]).
     */
    BLOCKED,

    /** Anything else. */
    OTHER;

    companion object {
        /** The reason behind [error], read from it or from the exceptions it wraps. */
        fun of(error: Throwable): DownloadFailure {
            var current: Throwable? = error
            repeat(MAX_CAUSE_DEPTH) {
                val failure = current?.let(::classify)
                if (failure != null) return failure
                current = current?.cause
            }
            return OTHER
        }

        private fun classify(error: Throwable): DownloadFailure? = when {
            error is DownloadException -> error.failure
            // Android refuses the socket itself to an app whose network is denied: "socket
            // failed: EPERM (Operation not permitted)", or the same from connect.
            error is SocketException && error.message.orEmpty().contains("EPERM") -> BLOCKED
            // SocketException covers refused, unreachable and reset connections.
            error is UnknownHostException || error is SocketException ||
                error is SocketTimeoutException || error is EOFException -> NO_CONNECTION
            // A connection that ended before the whole file came: HttpURLConnection says
            // "unexpected end of stream" (a ProtocolException mid-file), not that a file is damaged.
            error is IOException && error.message.orEmpty().contains("unexpected end of stream") -> NO_CONNECTION
            error is IOException && error.mentionsFullStorage() -> NOT_ENOUGH_STORAGE
            else -> null
        }

        /** Android reports a full disk as "write failed: ENOSPC (No space left on device)". */
        private fun IOException.mentionsFullStorage(): Boolean {
            val text = message.orEmpty()
            return "ENOSPC" in text || "No space left" in text
        }

        /** Bounds the walk down a chain of causes, so a chain that loops back cannot hang it. */
        private const val MAX_CAUSE_DEPTH = 6
    }
}

/**
 * A model download that failed for a [failure] the person can act on. [message] is for the log
 * and never shown.
 */
class DownloadException(
    val failure: DownloadFailure,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
