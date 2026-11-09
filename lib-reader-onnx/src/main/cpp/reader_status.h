// The statuses every native call of the reader returns. ReaderEngine.kt turns them into
// exceptions, so the numbers are part of the JNI contract: ReaderStatus there has the same ones.
// The reader runs inside Satori's only process, beside a recording's speech model, so a failure
// never aborts: whatever goes wrong comes back as one of these.

#pragma once

namespace satori::reader {

enum class Status : int {
    kOk = 0,
    // cancel() was called: ONNX Runtime's terminate flag stopped a Run, its load-cancellation flag
    // stopped a session load, or the call saw the flag before it started. Sticky until release.
    kCancelled = 1,
    // An argument out of range: no source ids, more than the model's positions, an id outside the
    // source vocabulary, a beam outside 1..kMaxBeam, an empty path, text that is not UTF-8.
    kBadInput = 2,
    // A graph whose inputs, outputs, element types or shapes are not the export's.
    kBadModel = 3,
    // A file that is missing, unreadable or not a model, or ONNX Runtime refusing to load it.
    kLoadFailed = 4,
    // ONNX Runtime failing a Run, or logits that are not finite.
    kRunFailed = 5,
    kOutOfMemory = 6,
    // translate before a successful load, pieces before a successful tokenizer load.
    kNotLoaded = 7,
    // A second call on a reader while one is running.
    kBusy = 8,
    // The reader's own bookkeeping failed a check: a bug, reported instead of acted on.
    kInternal = 9,
};

inline int ToInt(Status status) { return static_cast<int>(status); }

}  // namespace satori::reader
