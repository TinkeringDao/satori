package com.satori.domain.model

/**
 * Download progress information.
 *
 * @property bytesDownloaded Number of bytes downloaded so far
 * @property totalBytes Total size of the download in bytes
 * @property percentage Download completion percentage (0-100)
 */
data class DownloadProgress(
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val percentage: Int
)

/**
 * Download state sealed class for type-safe state management.
 *
 * Represents the various states of model downloading process.
 */
sealed class DownloadState {
    /**
     * Initial idle state - ready to start download.
     */
    object Idle : DownloadState()

    /**
     * Download in progress.
     *
     * @property progress Current download progress
     */
    data class Downloading(val progress: DownloadProgress) : DownloadState()

    /**
     * Every byte of a file is in and its SHA-256 is being checked, which holds the percent still
     * for some seconds on a large file: the screens say so instead of looking stuck.
     *
     * @property progress Where the download stands while the file is checked
     */
    data class Verifying(val progress: DownloadProgress) : DownloadState()

    /**
     * Held, not failed: the download goes on by itself once [reason] no longer holds, or with the
     * one tap the reason asks for. What it fetched so far stays on the phone.
     *
     * @property reason Why it is held; also the reason the field log gives
     * @property progress Where it stood when it was held; null when it was held before it began
     */
    data class Paused(
        val reason: PauseReason,
        val progress: DownloadProgress? = null
    ) : DownloadState()

    /**
     * Download completed successfully.
     */
    object Completed : DownloadState()

    /**
     * Download failed with error.
     *
     * @property message What went wrong, for the log. It is never shown to the user.
     * @property reason Why it failed in terms the user can act on; it picks the sentence shown.
     */
    data class Error(
        val message: String,
        val reason: DownloadFailure = DownloadFailure.OTHER
    ) : DownloadState()
}

/** Why a download is held ([DownloadState.Paused]), and so what lets it go on. */
enum class PauseReason {
    /** Pause was tapped: Continue goes on, also after the app was closed. */
    USER,

    /** A recorder is starting, recording or saving: it goes on once the recording is saved. */
    RECORDING,

    /** No network: it tries again by itself, and at once when Android reports one. */
    NO_NETWORK,

    /** Only mobile data, which this download may not use: Wi-Fi, or a tap on Use mobile data. */
    WIFI_ONLY,

    /**
     * The network works but Satori can't connect ([DownloadFailure.BLOCKED]): it tries again on
     * Try again and when the network changes.
     */
    BLOCKED,

    /** Not enough free space for the rest of it: it goes on once there is room. */
    NOT_ENOUGH_STORAGE,

    /** The server answered with an error: it tries again in a few minutes. */
    SERVER
}
