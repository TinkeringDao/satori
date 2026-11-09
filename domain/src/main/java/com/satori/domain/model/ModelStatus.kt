package com.satori.domain.model

/** Where a speech model ([SpeechModelId]) stands on this phone. */
sealed interface SpeechModelStatus {
    /** All its files are on the phone, whole. */
    data object Installed : SpeechModelStatus

    /** A download of it is running; [percent] is 0 while it starts. */
    data class Downloading(val percent: Int) : SpeechModelStatus

    /** Part of it is on the phone and nothing is downloading it: paused or interrupted. */
    data object Paused : SpeechModelStatus

    /** The download of it that was just running failed for [reason]. */
    data class Failed(val reason: DownloadFailure) : SpeechModelStatus

    /** Nothing of it is on the phone. */
    data object NotInstalled : SpeechModelStatus
}

/** Where an AI model stands on this phone. */
sealed interface AiModelStatus {
    /** The file is on the phone; [inUse] is true for the model that writes the notes. */
    data class Installed(val inUse: Boolean) : AiModelStatus

    /** A download is running; [percent] is 0 while it starts. */
    data class Downloading(val percent: Int) : AiModelStatus

    /** Part of the file is on the phone and nothing is downloading it: paused or interrupted. */
    data object Paused : AiModelStatus

    /** The download that was just running failed for [reason]. */
    data class Failed(val reason: DownloadFailure) : AiModelStatus

    /** Nothing of it is on the phone. */
    data object NotInstalled : AiModelStatus
}

/**
 * Works out where a model stands from what is on the phone and what the shared download state
 * says. The download state is about one model at a time, so progress and failures belong to the
 * row of that model only.
 */
object ModelStatusRules {

    /**
     * The status of the speech model [model]. The speech download state is about one model at a
     * time, [downloadModel]: a running download (its files checked too) or a failure of this model
     * is what the person needs to see first, also over files already there (Download again deletes and fetches them
     * anew). Otherwise its files settle it, and what is left of an unfinished download of it
     * ([downloadedBytes]) is a paused one.
     *
     * @param installed whether all its files are on the phone, whole.
     * @param downloadedBytes the bytes of its files on the phone, whole or partly downloaded.
     */
    fun speechModel(
        model: SpeechModelId,
        installed: Boolean,
        download: DownloadState,
        downloadModel: SpeechModelId?,
        downloadedBytes: Long
    ): SpeechModelStatus {
        val downloadIsAboutThis = model == downloadModel
        return when {
            downloadIsAboutThis && download is DownloadState.Downloading ->
                SpeechModelStatus.Downloading(download.progress.percentage)
            // Its files are in and being checked, for a few seconds: still its download.
            downloadIsAboutThis && download is DownloadState.Verifying ->
                SpeechModelStatus.Downloading(download.progress.percentage)
            downloadIsAboutThis && download is DownloadState.Error -> SpeechModelStatus.Failed(download.reason)
            installed -> SpeechModelStatus.Installed
            downloadedBytes > 0 -> SpeechModelStatus.Paused
            else -> SpeechModelStatus.NotInstalled
        }
    }

    /**
     * The status of the AI model [variant]. A file on the phone settles it, whatever an older
     * download said. Otherwise a running or failed download counts if it is [downloadVariant],
     * and what is left of an unfinished one ([partialBytes]) is a paused download. A failure
     * that names no model belongs to no row, so a partial file shows as paused then too.
     *
     * @param selected the model that writes the notes.
     * @param installed whether the whole file is on the phone.
     * @param download the shared state of AI model downloads, about [downloadVariant].
     */
    fun ai(
        variant: LlmModelVariant,
        selected: LlmModelVariant,
        installed: Boolean,
        download: DownloadState,
        downloadVariant: LlmModelVariant?,
        partialBytes: Long
    ): AiModelStatus {
        val downloadIsAboutThis = variant == downloadVariant
        return when {
            installed -> AiModelStatus.Installed(inUse = variant == selected)
            downloadIsAboutThis && download is DownloadState.Downloading ->
                AiModelStatus.Downloading(download.progress.percentage)
            downloadIsAboutThis && download is DownloadState.Verifying ->
                AiModelStatus.Downloading(download.progress.percentage)
            downloadIsAboutThis && download is DownloadState.Error -> AiModelStatus.Failed(download.reason)
            partialBytes > 0 -> AiModelStatus.Paused
            else -> AiModelStatus.NotInstalled
        }
    }
}
