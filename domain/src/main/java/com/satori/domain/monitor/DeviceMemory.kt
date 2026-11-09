package com.satori.domain.monitor

/**
 * How much memory the phone has free now, as Android counts it for apps (ActivityManager's
 * MemoryInfo.availMem). A large speech model is only loaded when enough of it is free.
 */
fun interface DeviceMemory {

    /** The bytes free now; each call reads it again. */
    fun availableBytes(): Long
}
