package com.satori.data.monitor

import android.app.ActivityManager
import android.content.Context
import com.satori.domain.monitor.DeviceMemory
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** [DeviceMemory] from Android's own count of the memory free for apps. */
class AndroidDeviceMemory @Inject constructor(
    @ApplicationContext private val context: Context
) : DeviceMemory {

    override fun availableBytes(): Long {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        return ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo).availMem
    }
}
