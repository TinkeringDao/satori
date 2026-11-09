package com.satori.domain.firstrun

/**
 * The first run's parts as they are on the phone, and the room there is for them. The first run
 * follows these facts, not what it remembers having done: files put there by hand count as
 * downloaded. Every call reads the disk, so none is for the main thread but [speech] without the
 * check, which only asks whether the files are there.
 */
interface FirstRunFiles {

    /** What [part] downloads, in bytes, from the registry: the AI model is the one that suits the phone. */
    fun bytes(part: FirstRunPart): Long

    /**
     * Speech recognition's files: [SpeechFiles.MISSING] or [SpeechFiles.PRESENT] without [check],
     * which only asks whether each is there and not empty; with it, [SpeechFiles.CHECKED] for
     * files that pass their check.
     */
    fun speech(check: Boolean): SpeechFiles

    /**
     * Whether [part]'s files are on the phone and pass their check. The reader's check is its
     * files' sizes and first bytes, and their SHA-256 read once ([verify]): until then it says no.
     */
    fun checked(part: FirstRunPart): Boolean

    /**
     * [checked], for a part whose check reads every byte of its files the first time: the reader's
     * SHA-256, against the pins, once for files that came another way than its download (the debug
     * tools', or by hand), whose own check reads each file as it arrives. That read stops whenever
     * [stop] says so, a recorder that starts, and is made at a later call ([FilesCheck.NOT_NOW]): it
     * reads 242 MB. The other parts have no such read.
     */
    fun verify(part: FirstRunPart, stop: () -> Boolean): FilesCheck =
        if (checked(part)) FilesCheck.PASSED else FilesCheck.FAILED

    /** The bytes of [part] on the phone: whole files, and what an unfinished download of it left. */
    fun bytesOnPhone(part: FirstRunPart): Long

    /** Whether an AI model that can write the notes is on the phone, whichever: then none is downloaded. */
    fun aiModelOnPhone(): Boolean

    /**
     * Deletes what is on the phone of [part], whole files and unfinished ones: a download that
     * finished with files that fail their check starts again from nothing, as a file left there
     * would count as downloaded.
     */
    fun discard(part: FirstRunPart)

    /** The free space where the models are kept. */
    fun freeBytes(): Long
}

/** What a check of a part's files that may read every byte of them came to ([FirstRunFiles.verify]). */
enum class FilesCheck {
    /** On the phone, and they pass their check. */
    PASSED,

    /** Not on the phone, or one of them fails its check. */
    FAILED,

    /** Not known now: the read of their bytes was held off, or stopped, for a recorder; a later call makes it. */
    NOT_NOW
}
