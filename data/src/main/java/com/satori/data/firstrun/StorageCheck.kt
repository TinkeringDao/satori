package com.satori.data.firstrun

import android.content.Context
import android.os.StatFs
import com.satori.domain.firstrun.FirstRunPlan
import com.satori.domain.firstrun.SpaceShortfall
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The free space where the models are kept, the app's external files, and whether a download
 * fits in it: its bytes and [FirstRunPlan.HEADROOM_BYTES] for its temporary file and the
 * recordings to come. Before 0.7.0 nothing looked, and a full disk failed the download halfway.
 */
@Singleton
class StorageCheck @Inject constructor(@ApplicationContext private val context: Context) {

    /** The bytes free for the models now; 0 when the folder can't be read. File access: not on the main thread. */
    fun freeBytes(): Long {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        return try {
            StatFs(dir.path).availableBytes
        } catch (e: IllegalArgumentException) {
            0L
        }
    }

    companion object {
        /** What a download of [payloadBytes] lacks with [freeBytes] free; null when it fits ([FirstRunPlan.fits]). */
        fun shortfall(freeBytes: Long, payloadBytes: Long): SpaceShortfall? =
            if (FirstRunPlan.fits(freeBytes, payloadBytes)) null else SpaceShortfall(FirstRunPlan.neededSpace(payloadBytes), freeBytes)
    }
}
