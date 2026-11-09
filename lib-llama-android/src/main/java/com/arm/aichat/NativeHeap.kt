package com.arm.aichat

/**
 * The process's native heap, bionic's malloc, which llama.cpp, ONNX Runtime and sherpa-onnx all
 * allocate from. One JNI call in libai-chat.so (ai_chat.cpp), which the inference engine loads at
 * the app's start anyway; not on the engine's thread, so it never waits behind an inference.
 */
object NativeHeap {
    init {
        System.loadLibrary("ai-chat")
    }

    /**
     * Gives the pages of the native memory freed so far back to the kernel now:
     * `mallopt(M_PURGE_ALL, 0)` where bionic knows it (API 34 and later), else `mallopt(M_PURGE, 0)`
     * (API 28 and later), as Zygote does before it starts each app's process. Returns 2 for
     * M_PURGE_ALL, 1 for M_PURGE, 0 when neither took. It holds the allocator's locks while it runs:
     * about 40-55 ms with 500 MB to give back on the emulator, so other threads' native allocations
     * wait that long.
     */
    @JvmStatic
    external fun purge(): Int
}
