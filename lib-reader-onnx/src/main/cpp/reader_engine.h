// The reader: IndicTrans2's three ONNX graphs on the ONNX Runtime that sherpa-onnx ships, through
// its C API (ORT_API_VERSION 28). One Reader per load; JNI (reader_jni.cpp) wraps it, and the host
// tests (src/test/cpp) drive it directly against a toy model with the export's inputs and outputs.
//
// The graphs (the export's src/01_export_encoder_decoder.py):
//   encoder_model:           input_ids, attention_mask -> last_hidden_state
//   decoder_model:           input_ids, encoder_attention_mask, encoder_hidden_states
//                            -> logits, present.N.{decoder,encoder}.{key,value}
//   decoder_with_past_model: input_ids, encoder_attention_mask, past_key_values.N.{decoder,encoder}.{key,value}
//                            -> logits, present.N.{decoder,encoder}.{key,value}
// Each loads from its file, so ONNX Runtime finds the external data files beside it.
//
// One segment (Translate): the encoder once, decoder_model once with the start token (batch 1),
// then decoder_with_past per token with the beams as its batch. The cross-attention cache of the
// first step is fed to every step. Every output of decoder_with_past goes into the reader's own
// buffers, so its arena holds only a step's small intermediates: the logits, present.N.decoder.*
// into the self-attention cache, and present.N.encoder.* (past_key_values.N.encoder.* passed
// through unchanged, an Identity in the toy graphs) into the very cross buffer fed as that input,
// where the pass-through finds its input and copies nothing (unbound, ONNX Runtime would allocate
// and fill a copy of the whole cross cache per step). Layer and head counts come from the
// sessions' inputs and outputs, and every shape a Run returns is checked before it is read.
//
// Threads: Load, LoadTokenizer, Pieces and Translate run one at a time (a second call meanwhile is
// kBusy, never a wait; ReaderEngine.kt serializes its calls, so only a direct ReaderNative caller
// sees kBusy). Cancel may come from any thread at any time before the Reader is
// destroyed: it sets the terminate flag of the run options (a running Run stops at its next node),
// the load-cancellation flag of a session being loaded (ONNX Runtime checks it per initializer,
// per node while prepacking and between optimizer passes), and a flag every call checks first.
// Cancel is final: the caller releases this Reader and makes a new one (D14).

#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <mutex>
#include <string>
#include <string_view>
#include <vector>

#include "onnxruntime_c_api.h"
#include "reader_status.h"
#include "spm_tokenizer.h"

namespace satori::reader {

// The sessions' settings (D3, D4) and the model's limits (its config.json). The defaults are the
// design's; V1 compares the other settings without a rebuild.
struct Options {
    int threads = 4;                        // D4: intra-op threads per session
    bool prepack_encoder = true;            // D3: prepacking on for the encoder ...
    bool prepack_decoder = false;           // ... off for decoder_model, run once per segment ...
    bool prepack_decoder_with_past = true;  // ... on for the per-token loop
    bool cpu_arena = true;                  // D3: the CPU arena on ...
    bool shrink_arena = true;               // ... shrunk after each segment's encoder and first step
    int max_source_positions = 256;         // max_source_positions: tags, pieces and </s>
    int max_target_positions = 256;         // max_target_positions: the start token and the output
    int source_vocab = 122706;              // encoder_vocab_size: every source id is below it
    int decoder_start_id = 2;
    int eos_id = 2;
    int pad_id = 1;
};

struct Translation {
    std::vector<int32_t> ids;  // the target ids, without the closing end-of-sentence
    double mean_log_prob = 0.0;
    bool capped = false;  // reached the token cap, or ended without an end-of-sentence
    int steps = 0;        // decoder runs: decoder_model once, decoder_with_past for the rest
};

struct Dims {
    int layers = 0;
    int heads = 0;     // 0 until known: from decoder_with_past's inputs, else the first step
    int head_dim = 0;
};

class Reader {
public:
    Reader();
    ~Reader();
    Reader(const Reader&) = delete;
    Reader& operator=(const Reader&) = delete;

    Status Load(const std::string& encoder, const std::string& decoder, const std::string& decoder_with_past,
                const Options& options);
    Status LoadTokenizer(const std::string& path);
    Status Pieces(std::string_view text, std::vector<std::string>* pieces);

    // `ids`: the source as the model takes it, the two language tags first and </s> last, the only
    // </s> (eos_id: </s> is 2 in both dictionaries); anything else is kBadInput.
    // The output is capped at min(2 x pieces + 10, max_target_positions - 1) tokens (D15), the
    // pieces being the ids but those three; `max_new_tokens` > 0 lowers that cap.
    // `min_tokens` is the search's minimum length (BeamSearch::Config): end-of-sentence is no
    // candidate before that many tokens, except at the cap's last token. fairseq's 1 by default; a
    // minimum at the cap or above holds the end back to the cap's last token, so the search runs
    // the cap's every step (the debug worst case, V1). Negative is kBadInput.
    Status Translate(const int32_t* ids, size_t count, int beam, int max_new_tokens, int min_tokens, Translation* out);
    Status Translate(const int32_t* ids, size_t count, int beam, int max_new_tokens, Translation* out) {
        return Translate(ids, count, beam, max_new_tokens, kDefaultMinTokens, out);
    }

    // fairseq's min_len default, which the reader keeps for every reading.
    static constexpr int kDefaultMinTokens = 1;

    void Cancel();

    Dims dims() const;
    // The message of the last failed call, ONNX Runtime's or SentencePiece's when they gave one.
    std::string last_error() const;

private:
    // The host tests (src/test/cpp) read the sessions' arena statistics through it; nothing else.
    friend struct ReaderTestPeer;

    struct Io {
        std::string name;
        bool tensor = false;
        ONNXTensorElementDataType type = ONNX_TENSOR_ELEMENT_DATA_TYPE_UNDEFINED;
        std::vector<int64_t> dims;
    };

    Status Fail(Status status, std::string message);
    Status Check(OrtStatus* status, Status failure);
    void ReleaseQuietly(OrtStatus* status) const;
    Status CreateSession(const std::string& path, bool prepack, OrtSession** session);
    Status ReadIo(OrtSession* session, bool inputs, std::vector<Io>* out);
    Status ValidateSessions();
    Status RunSearch(const int32_t* ids, size_t count, int beam, size_t cap, int min_tokens, Translation* out);
    void Unload();

    const OrtApi* api_ = nullptr;
    OrtEnv* env_ = nullptr;
    OrtMemoryInfo* memory_ = nullptr;
    OrtSession* encoder_ = nullptr;
    OrtSession* decoder_ = nullptr;
    OrtSession* decoder_with_past_ = nullptr;
    Options options_{};
    bool loaded_ = false;

    size_t layers_ = 0;
    size_t heads_ = 0;
    size_t head_dim_ = 0;
    int64_t vocab_ = 0;  // 0 until known: from a static logits shape, else the first step
    // Run names in feed order: decoder_model's outputs (logits, then per layer the decoder and
    // encoder key and value), decoder_with_past's inputs and its fetched outputs.
    std::vector<std::string> first_outputs_;
    std::vector<std::string> step_inputs_;
    std::vector<std::string> step_outputs_;

    Tokenizer tokenizer_;
    std::string error_;

    // One call at a time; try_lock, so a second call is kBusy.
    mutable std::mutex call_;
    // What Cancel touches from another thread: the run options and the session options of a
    // session being loaded.
    std::mutex flags_;
    OrtRunOptions* run_ = nullptr;         // decoder_with_past steps
    OrtRunOptions* run_shrink_ = nullptr;  // the encoder and decoder_model: shrink the arena after
    OrtSessionOptions* loading_ = nullptr;
    std::atomic<bool> cancelled_{false};
};

}  // namespace satori::reader
