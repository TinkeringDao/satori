#include "reader_engine.h"

#include <algorithm>
#include <limits>
#include <memory>
#include <tuple>
#include <utility>

#include "beam_search.h"
#include "kv_cache.h"
#include "onnxruntime_run_options_config_keys.h"
#include "onnxruntime_session_options_config_keys.h"

namespace satori::reader {
namespace {

constexpr size_t kMaxErrorBytes = 2000;
// A vocabulary far above IndicTrans2's 32,296 target tokens; it bounds the logits buffer.
constexpr int64_t kMaxVocab = int64_t{1} << 20;
constexpr int kMaxThreads = 16;
constexpr int kMaxPositions = 4096;
constexpr ONNXTensorElementDataType kInt64 = ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64;
constexpr ONNXTensorElementDataType kFloat = ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT;

// The C API of the ONNX Runtime the process loaded, resolved once; nullptr when it doesn't offer
// the headers' version (another ONNX Runtime than sherpa-onnx's 1.28.2).
const OrtApi* Api() {
    static const OrtApi* const api = [] {
        const OrtApiBase* base = OrtGetApiBase();
        return base == nullptr ? nullptr : base->GetApi(ORT_API_VERSION);
    }();
    return api;
}

struct ValueDeleter {
    void operator()(OrtValue* value) const {
        if (value != nullptr) {
            Api()->ReleaseValue(value);
        }
    }
};
using Value = std::unique_ptr<OrtValue, ValueDeleter>;

struct SessionOptionsDeleter {
    void operator()(OrtSessionOptions* options) const {
        if (options != nullptr) {
            Api()->ReleaseSessionOptions(options);
        }
    }
};

struct TypeInfoDeleter {
    void operator()(OrtTypeInfo* info) const {
        if (info != nullptr) {
            Api()->ReleaseTypeInfo(info);
        }
    }
};

std::string LayerName(const char* prefix, size_t layer, const char* part) {
    return std::string(prefix) + std::to_string(layer) + part;
}

}  // namespace

Reader::Reader() : api_(Api()) {}

Reader::~Reader() { Unload(); }

Status Reader::Fail(Status status, std::string message) {
    if (message.size() > kMaxErrorBytes) {
        message.resize(kMaxErrorBytes);
    }
    error_ = std::move(message);
    return status;
}

Status Reader::Check(OrtStatus* status, Status failure) {
    if (status == nullptr) {
        return Status::kOk;
    }
    const OrtErrorCode code = api_->GetErrorCode(status);
    std::string message = api_->GetErrorMessage(status);
    api_->ReleaseStatus(status);
    // A Run stopped by the terminate flag fails with ORT_FAIL ("Exiting due to terminate flag"),
    // a load stopped by the cancellation flag with ORT_MODEL_LOAD_CANCELED.
    if (code == ORT_MODEL_LOAD_CANCELED || cancelled_.load()) {
        return Fail(Status::kCancelled, std::move(message));
    }
    return Fail(failure, std::move(message));
}

void Reader::ReleaseQuietly(OrtStatus* status) const {
    if (status != nullptr) {
        api_->ReleaseStatus(status);
    }
}

void Reader::Unload() {
    loaded_ = false;
    first_outputs_.clear();
    step_inputs_.clear();
    step_outputs_.clear();
    layers_ = 0;
    heads_ = 0;
    head_dim_ = 0;
    vocab_ = 0;
    if (api_ == nullptr) {
        return;
    }
    for (OrtSession** session : {&decoder_with_past_, &decoder_, &encoder_}) {
        if (*session != nullptr) {
            api_->ReleaseSession(*session);
            *session = nullptr;
        }
    }
    if (memory_ != nullptr) {
        api_->ReleaseMemoryInfo(memory_);
        memory_ = nullptr;
    }
    {
        std::lock_guard<std::mutex> lock(flags_);
        for (OrtRunOptions** run : {&run_, &run_shrink_}) {
            if (*run != nullptr) {
                api_->ReleaseRunOptions(*run);
                *run = nullptr;
            }
        }
    }
    if (env_ != nullptr) {
        api_->ReleaseEnv(env_);
        env_ = nullptr;
    }
}

void Reader::Cancel() {
    cancelled_.store(true);
    if (api_ == nullptr) {
        return;
    }
    std::lock_guard<std::mutex> lock(flags_);
    for (OrtRunOptions* run : {run_, run_shrink_}) {
        if (run != nullptr) {
            ReleaseQuietly(api_->RunOptionsSetTerminate(run));
        }
    }
    if (loading_ != nullptr) {
        ReleaseQuietly(api_->SessionOptionsSetLoadCancellationFlag(loading_, true));
    }
}

Dims Reader::dims() const {
    std::unique_lock<std::mutex> call(call_, std::try_to_lock);
    if (!call.owns_lock()) {
        return Dims{};
    }
    return Dims{static_cast<int>(layers_), static_cast<int>(heads_), static_cast<int>(head_dim_)};
}

std::string Reader::last_error() const {
    std::unique_lock<std::mutex> call(call_, std::try_to_lock);
    return call.owns_lock() ? error_ : std::string("another call is running");
}

Status Reader::LoadTokenizer(const std::string& path) {
    std::unique_lock<std::mutex> call(call_, std::try_to_lock);
    if (!call.owns_lock()) {
        return Status::kBusy;
    }
    error_.clear();
    return tokenizer_.Load(path, &error_);
}

Status Reader::Pieces(std::string_view text, std::vector<std::string>* pieces) {
    std::unique_lock<std::mutex> call(call_, std::try_to_lock);
    if (!call.owns_lock()) {
        return Status::kBusy;
    }
    error_.clear();
    return tokenizer_.Pieces(text, pieces, &error_);
}

Status Reader::CreateSession(const std::string& path, bool prepack, OrtSession** session) {
    *session = nullptr;
    OrtSessionOptions* raw = nullptr;
    Status status = Check(api_->CreateSessionOptions(&raw), Status::kLoadFailed);
    std::unique_ptr<OrtSessionOptions, SessionOptionsDeleter> options(raw);
    if (status != Status::kOk) {
        return status;
    }
    OrtStatus* set = api_->SetIntraOpNumThreads(raw, options_.threads);
    if (set == nullptr) set = api_->SetInterOpNumThreads(raw, 1);
    if (set == nullptr) set = api_->SetSessionExecutionMode(raw, ORT_SEQUENTIAL);
    if (set == nullptr) set = api_->SetSessionGraphOptimizationLevel(raw, ORT_ENABLE_ALL);
    // With prepacking off, a memory pattern would reserve a block the size of all initializers
    // (MEMORY_RESEARCH.json part [1], finding 9).
    if (set == nullptr) set = api_->DisableMemPattern(raw);
    if (set == nullptr && !options_.cpu_arena) set = api_->DisableCpuMemArena(raw);
    if (set == nullptr && !prepack) set = api_->AddSessionConfigEntry(raw, kOrtSessionOptionsConfigDisablePrepacking, "1");
    status = Check(set, Status::kLoadFailed);
    if (status != Status::kOk) {
        return status;
    }
    {
        std::lock_guard<std::mutex> lock(flags_);
        loading_ = raw;
        if (cancelled_.load()) {
            ReleaseQuietly(api_->SessionOptionsSetLoadCancellationFlag(raw, true));
        }
    }
    OrtStatus* created = nullptr;
    if (!cancelled_.load()) {
        created = api_->CreateSession(env_, path.c_str(), raw, session);
    }
    {
        std::lock_guard<std::mutex> lock(flags_);
        loading_ = nullptr;
    }
    if (cancelled_.load()) {
        ReleaseQuietly(created);
        if (*session != nullptr) {
            api_->ReleaseSession(*session);
            *session = nullptr;
        }
        return Fail(Status::kCancelled, "cancelled while loading " + path);
    }
    return Check(created, Status::kLoadFailed);
}

Status Reader::ReadIo(OrtSession* session, bool inputs, std::vector<Io>* out) {
    out->clear();
    OrtAllocator* allocator = nullptr;
    Status status = Check(api_->GetAllocatorWithDefaultOptions(&allocator), Status::kBadModel);
    if (status != Status::kOk) {
        return status;
    }
    size_t count = 0;
    status = Check(inputs ? api_->SessionGetInputCount(session, &count) : api_->SessionGetOutputCount(session, &count),
                   Status::kBadModel);
    if (status != Status::kOk) {
        return status;
    }
    if (count > 4 * KvCache::kMaxLayers + 8) {
        return Fail(Status::kBadModel, "a graph with " + std::to_string(count) + (inputs ? " inputs" : " outputs"));
    }
    for (size_t i = 0; i < count; ++i) {
        char* name = nullptr;
        status = Check(inputs ? api_->SessionGetInputName(session, i, allocator, &name)
                              : api_->SessionGetOutputName(session, i, allocator, &name),
                       Status::kBadModel);
        if (status != Status::kOk) {
            return status;
        }
        Io io;
        io.name = name == nullptr ? "" : name;
        ReleaseQuietly(api_->AllocatorFree(allocator, name));
        OrtTypeInfo* raw = nullptr;
        status = Check(inputs ? api_->SessionGetInputTypeInfo(session, i, &raw)
                              : api_->SessionGetOutputTypeInfo(session, i, &raw),
                       Status::kBadModel);
        std::unique_ptr<OrtTypeInfo, TypeInfoDeleter> type_info(raw);
        if (status != Status::kOk) {
            return status;
        }
        const OrtTensorTypeAndShapeInfo* tensor = nullptr;
        OrtStatus* cast = api_->CastTypeInfoToTensorInfo(raw, &tensor);
        if (cast == nullptr && tensor != nullptr) {
            size_t rank = 0;
            OrtStatus* read = api_->GetTensorElementType(tensor, &io.type);
            if (read == nullptr) read = api_->GetDimensionsCount(tensor, &rank);
            if (read == nullptr && rank <= 8) {
                io.dims.resize(rank);
                read = rank == 0 ? nullptr : api_->GetDimensions(tensor, io.dims.data(), rank);
                io.tensor = read == nullptr;
            }
            ReleaseQuietly(read);
        }
        ReleaseQuietly(cast);
        out->push_back(std::move(io));
    }
    return Status::kOk;
}

Status Reader::ValidateSessions() {
    std::vector<Io> encoder_in, encoder_out, first_in, first_out, step_in, step_out;
    for (auto [session, inputs, list] : {std::make_tuple(encoder_, true, &encoder_in),
                                          std::make_tuple(encoder_, false, &encoder_out),
                                          std::make_tuple(decoder_, true, &first_in),
                                          std::make_tuple(decoder_, false, &first_out),
                                          std::make_tuple(decoder_with_past_, true, &step_in),
                                          std::make_tuple(decoder_with_past_, false, &step_out)}) {
        const Status status = ReadIo(session, inputs, list);
        if (status != Status::kOk) {
            return status;
        }
    }
    const auto find = [](const std::vector<Io>& list, const std::string& name) -> const Io* {
        for (const Io& io : list) {
            if (io.name == name) {
                return &io;
            }
        }
        return nullptr;
    };
    // An input must declare its rank; an output may leave its shape out.
    const auto is = [](const Io* io, ONNXTensorElementDataType type, size_t rank, bool output) {
        return io != nullptr && io->tensor && io->type == type &&
               (io->dims.size() == rank || (output && io->dims.empty()));
    };

    if (encoder_in.size() != 2 || !is(find(encoder_in, "input_ids"), kInt64, 2, false) ||
        !is(find(encoder_in, "attention_mask"), kInt64, 2, false)) {
        return Fail(Status::kBadModel, "encoder_model: the inputs are not input_ids and attention_mask (int64, 2-D)");
    }
    const Io* hidden = find(encoder_out, "last_hidden_state");
    if (!is(hidden, kFloat, 3, true)) {
        return Fail(Status::kBadModel, "encoder_model: no float last_hidden_state (3-D)");
    }

    const Io* hidden_in = find(first_in, "encoder_hidden_states");
    if (first_in.size() != 3 || !is(find(first_in, "input_ids"), kInt64, 2, false) ||
        !is(find(first_in, "encoder_attention_mask"), kInt64, 2, false) || !is(hidden_in, kFloat, 3, false)) {
        return Fail(Status::kBadModel,
                    "decoder_model: the inputs are not input_ids, encoder_attention_mask (int64, 2-D) and "
                    "encoder_hidden_states (float, 3-D)");
    }
    if (hidden->dims.size() == 3 && hidden->dims[2] > 0 && hidden_in->dims[2] > 0 &&
        hidden->dims[2] != hidden_in->dims[2]) {
        return Fail(Status::kBadModel, "the encoder's hidden size is not the decoder's");
    }
    const Io* first_logits = find(first_out, "logits");
    if (!is(first_logits, kFloat, 3, true)) {
        return Fail(Status::kBadModel, "decoder_model: no float logits (3-D)");
    }
    size_t layers = 0;
    while (layers <= KvCache::kMaxLayers && find(first_out, LayerName("present.", layers, ".decoder.key")) != nullptr) {
        ++layers;
    }
    if (layers == 0 || layers > KvCache::kMaxLayers) {
        return Fail(Status::kBadModel, "decoder_model: no present.0.decoder.key, or more than 64 layers");
    }
    static constexpr const char* kParts[4] = {".decoder.key", ".decoder.value", ".encoder.key", ".encoder.value"};
    for (size_t layer = 0; layer < layers; ++layer) {
        for (const char* part : kParts) {
            const std::string name = LayerName("present.", layer, part);
            if (!is(find(first_out, name), kFloat, 4, true)) {
                return Fail(Status::kBadModel, "decoder_model: no float output " + name + " (4-D)");
            }
        }
    }

    if (step_in.size() != 2 + 4 * layers || !is(find(step_in, "input_ids"), kInt64, 2, false) ||
        !is(find(step_in, "encoder_attention_mask"), kInt64, 2, false)) {
        return Fail(Status::kBadModel, "decoder_with_past_model: the inputs are not input_ids, encoder_attention_mask "
                                       "and four past_key_values per layer of decoder_model (" +
                                           std::to_string(layers) + ")");
    }
    int64_t heads = -1;
    int64_t head_dim = -1;
    for (size_t layer = 0; layer < layers; ++layer) {
        for (const char* part : kParts) {
            const std::string name = LayerName("past_key_values.", layer, part);
            const Io* past = find(step_in, name);
            if (!is(past, kFloat, 4, false)) {
                return Fail(Status::kBadModel, "decoder_with_past_model: no float input " + name + " (4-D)");
            }
            // The export fixes heads and head size (dims 1 and 3); only batch and length vary.
            for (auto [dim, known] : {std::make_pair(past->dims[1], &heads), std::make_pair(past->dims[3], &head_dim)}) {
                if (dim > 0 && *known > 0 && dim != *known) {
                    return Fail(Status::kBadModel, "decoder_with_past_model: " + name + " has another head shape");
                }
                if (dim > 0) {
                    *known = dim;
                }
            }
        }
    }
    if (heads > static_cast<int64_t>(KvCache::kMaxHeads) || head_dim > static_cast<int64_t>(KvCache::kMaxHeadDim)) {
        return Fail(Status::kBadModel, "decoder_with_past_model: more heads or a larger head than the reader takes");
    }
    const Io* step_logits = find(step_out, "logits");
    if (!is(step_logits, kFloat, 3, true)) {
        return Fail(Status::kBadModel, "decoder_with_past_model: no float logits (3-D)");
    }
    for (size_t layer = 0; layer < layers; ++layer) {
        for (const char* part : kParts) {
            const std::string name = LayerName("present.", layer, part);
            if (!is(find(step_out, name), kFloat, 4, true)) {
                return Fail(Status::kBadModel, "decoder_with_past_model: no float output " + name + " (4-D)");
            }
        }
    }
    const int64_t first_vocab = first_logits->dims.size() == 3 ? first_logits->dims[2] : -1;
    const int64_t step_vocab = step_logits->dims.size() == 3 ? step_logits->dims[2] : -1;
    if (first_vocab > 0 && step_vocab > 0 && first_vocab != step_vocab) {
        return Fail(Status::kBadModel, "the two decoders' vocabularies differ");
    }
    const int64_t vocab = first_vocab > 0 ? first_vocab : step_vocab;
    if (vocab > kMaxVocab) {
        return Fail(Status::kBadModel, "a vocabulary larger than the reader takes");
    }
    if (vocab > 0 && (options_.eos_id >= vocab || options_.pad_id >= vocab || options_.decoder_start_id >= vocab)) {
        return Fail(Status::kBadModel, "the start, end or padding id is outside the target vocabulary");
    }

    layers_ = layers;
    heads_ = heads > 0 ? static_cast<size_t>(heads) : 0;
    head_dim_ = head_dim > 0 ? static_cast<size_t>(head_dim) : 0;
    vocab_ = vocab > 0 ? vocab : 0;
    // Every output of decoder_with_past is fetched into the reader's buffers (RunSearch), so ONNX
    // Runtime allocates none of them: an output left unfetched is still computed, into its arena.
    first_outputs_.assign(1, "logits");
    step_inputs_ = {"input_ids", "encoder_attention_mask"};
    step_outputs_.assign(1, "logits");
    for (size_t layer = 0; layer < layers; ++layer) {
        for (const char* part : kParts) {
            first_outputs_.push_back(LayerName("present.", layer, part));
            step_inputs_.push_back(LayerName("past_key_values.", layer, part));
            step_outputs_.push_back(LayerName("present.", layer, part));
        }
    }
    return Status::kOk;
}

Status Reader::Load(const std::string& encoder, const std::string& decoder, const std::string& decoder_with_past,
                    const Options& options) {
    std::unique_lock<std::mutex> call(call_, std::try_to_lock);
    if (!call.owns_lock()) {
        return Status::kBusy;
    }
    error_.clear();
    if (cancelled_.load()) {
        return Fail(Status::kCancelled, "cancelled");
    }
    if (api_ == nullptr) {
        return Fail(Status::kLoadFailed, "this ONNX Runtime does not offer C API version " + std::to_string(ORT_API_VERSION));
    }
    if (loaded_ || env_ != nullptr) {
        return Fail(Status::kBadInput, "already loaded");
    }
    if (encoder.empty() || decoder.empty() || decoder_with_past.empty()) {
        return Fail(Status::kBadInput, "a graph path is empty");
    }
    if (options.threads < 1 || options.threads > kMaxThreads || options.max_source_positions < 4 ||
        options.max_source_positions > kMaxPositions || options.max_target_positions < 2 ||
        options.max_target_positions > kMaxPositions || options.source_vocab < 1 || options.decoder_start_id < 0 ||
        options.eos_id < 0 || options.pad_id < -1 || options.pad_id == options.eos_id) {
        return Fail(Status::kBadInput, "options out of range");
    }
    options_ = options;

    Status status = Check(api_->CreateEnv(ORT_LOGGING_LEVEL_WARNING, "satori-reader", &env_), Status::kLoadFailed);
    if (status == Status::kOk) {
        status = Check(api_->CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &memory_), Status::kLoadFailed);
    }
    if (status == Status::kOk) {
        std::lock_guard<std::mutex> lock(flags_);
        status = Check(api_->CreateRunOptions(&run_), Status::kLoadFailed);
        if (status == Status::kOk) {
            status = Check(api_->CreateRunOptions(&run_shrink_), Status::kLoadFailed);
        }
        if (status == Status::kOk && options_.cpu_arena && options_.shrink_arena) {
            status = Check(api_->AddRunConfigEntry(run_shrink_, kOrtRunOptionsConfigEnableMemoryArenaShrinkage, "cpu:0"),
                           Status::kLoadFailed);
        }
        if (status == Status::kOk && cancelled_.load()) {
            ReleaseQuietly(api_->RunOptionsSetTerminate(run_));
            ReleaseQuietly(api_->RunOptionsSetTerminate(run_shrink_));
        }
    }
    if (status == Status::kOk) {
        status = CreateSession(encoder, options_.prepack_encoder, &encoder_);
    }
    if (status == Status::kOk) {
        status = CreateSession(decoder, options_.prepack_decoder, &decoder_);
    }
    if (status == Status::kOk) {
        status = CreateSession(decoder_with_past, options_.prepack_decoder_with_past, &decoder_with_past_);
    }
    if (status == Status::kOk) {
        status = ValidateSessions();
    }
    if (status == Status::kOk && cancelled_.load()) {
        status = Fail(Status::kCancelled, "cancelled while loading");
    }
    if (status != Status::kOk) {
        Unload();
        return status;
    }
    loaded_ = true;
    return Status::kOk;
}

Status Reader::Translate(const int32_t* ids, size_t count, int beam, int max_new_tokens, int min_tokens,
                         Translation* out) {
    std::unique_lock<std::mutex> call(call_, std::try_to_lock);
    if (!call.owns_lock()) {
        return Status::kBusy;
    }
    error_.clear();
    if (out == nullptr) {
        return Fail(Status::kInternal, "no result to fill");
    }
    *out = Translation{};
    if (cancelled_.load()) {
        return Fail(Status::kCancelled, "cancelled");
    }
    if (!loaded_) {
        return Fail(Status::kNotLoaded, "the reader is not loaded");
    }
    if (ids == nullptr || count == 0) {
        return Fail(Status::kBadInput, "no source ids");
    }
    if (count > static_cast<size_t>(options_.max_source_positions)) {
        return Fail(Status::kBadInput, std::to_string(count) + " source ids, the model takes at most " +
                                           std::to_string(options_.max_source_positions));
    }
    if (beam < 1 || beam > kMaxBeam) {
        return Fail(Status::kBadInput, "beam " + std::to_string(beam) + " outside 1.." + std::to_string(kMaxBeam));
    }
    if (max_new_tokens < 0) {
        return Fail(Status::kBadInput, "a negative token cap");
    }
    if (min_tokens < 0) {
        return Fail(Status::kBadInput, "a negative minimum length");
    }
    // The framing PieceIds gives a source: the two language tags, the pieces and one </s>, last
    // (eos_id; </s> is 2 in both dictionaries). A source cut or joined wrong before it gets here (no
    // </s>, or two segments' ids with their </s> between them) is refused rather than read worse.
    for (size_t i = 0; i < count; ++i) {
        if (ids[i] < 0 || ids[i] >= options_.source_vocab) {
            return Fail(Status::kBadInput, "source id " + std::to_string(ids[i]) + " at " + std::to_string(i) +
                                               " is outside the source vocabulary");
        }
        const bool end = i + 1 == count;
        if ((ids[i] == options_.eos_id) != end) {
            return Fail(Status::kBadInput, end ? "the source does not end with </s> (" + std::to_string(options_.eos_id) + ")"
                                               : "</s> at " + std::to_string(i) + ", before the source's end");
        }
    }
    // D15: at most min(2 x pieces + 10, max_target_positions - 1) tokens, the start token taking
    // the remaining position.
    const size_t pieces = count > 3 ? count - 3 : 0;
    size_t cap = std::min(2 * pieces + 10, static_cast<size_t>(options_.max_target_positions) - 1);
    if (max_new_tokens > 0) {
        cap = std::min(cap, static_cast<size_t>(max_new_tokens));
    }
    const Status status = RunSearch(ids, count, beam, cap, min_tokens, out);
    if (status != Status::kOk) {
        *out = Translation{};
    }
    return status;
}

Status Reader::RunSearch(const int32_t* ids, size_t count, int beam, size_t cap, int min_tokens, Translation* out) {
    const auto source_len = static_cast<int64_t>(count);
    const auto beams = static_cast<size_t>(beam);

    const auto tensor = [this](void* data, size_t bytes, const int64_t* shape, size_t rank,
                               ONNXTensorElementDataType type, Value* value) {
        OrtValue* raw = nullptr;
        const Status status =
            Check(api_->CreateTensorWithDataAsOrtValue(memory_, data, bytes, shape, rank, type, &raw), Status::kRunFailed);
        value->reset(raw);
        return status;
    };
    // The element type and shape of a tensor a Run returned.
    const auto shape_of = [this](const OrtValue* value, ONNXTensorElementDataType* type, std::vector<int64_t>* dims) {
        dims->clear();
        OrtTensorTypeAndShapeInfo* info = nullptr;
        Status status = Check(api_->GetTensorTypeAndShape(value, &info), Status::kRunFailed);
        if (status != Status::kOk) {
            return status;
        }
        size_t rank = 0;
        OrtStatus* read = api_->GetTensorElementType(info, type);
        if (read == nullptr) read = api_->GetDimensionsCount(info, &rank);
        if (read == nullptr && rank <= 8) {
            dims->resize(rank);
            read = rank == 0 ? nullptr : api_->GetDimensions(info, dims->data(), rank);
        }
        api_->ReleaseTensorTypeAndShapeInfo(info);
        return Check(read, Status::kRunFailed);
    };
    const auto floats_of = [this](OrtValue* value, float** data) {
        void* raw = nullptr;
        const Status status = Check(api_->GetTensorMutableData(value, &raw), Status::kRunFailed);
        *data = static_cast<float*>(raw);
        if (status == Status::kOk && raw == nullptr) {
            return Fail(Status::kRunFailed, "a Run returned a tensor without data");
        }
        return status;
    };

    // 1. The encoder.
    std::vector<int64_t> source(ids, ids + count);
    // Ones for every beam's row; decoder_model and the encoder take the first row.
    std::vector<int64_t> mask(beams * count, 1);
    const int64_t source_shape[2] = {1, source_len};
    Value source_value;
    Value mask_value;
    Status status = tensor(source.data(), count * sizeof(int64_t), source_shape, 2, kInt64, &source_value);
    if (status == Status::kOk) {
        status = tensor(mask.data(), count * sizeof(int64_t), source_shape, 2, kInt64, &mask_value);
    }
    if (status != Status::kOk) {
        return status;
    }
    Value hidden;
    {
        const char* in_names[] = {"input_ids", "attention_mask"};
        const OrtValue* in_values[] = {source_value.get(), mask_value.get()};
        const char* out_names[] = {"last_hidden_state"};
        OrtValue* out_values[] = {nullptr};
        OrtStatus* run = api_->Run(encoder_, run_shrink_, in_names, in_values, 2, out_names, 1, out_values);
        hidden.reset(out_values[0]);
        status = Check(run, Status::kRunFailed);
        if (status != Status::kOk) {
            return status;
        }
    }
    ONNXTensorElementDataType type = ONNX_TENSOR_ELEMENT_DATA_TYPE_UNDEFINED;
    std::vector<int64_t> dims;
    status = shape_of(hidden.get(), &type, &dims);
    if (status != Status::kOk) {
        return status;
    }
    if (type != kFloat || dims.size() != 3 || dims[0] != 1 || dims[1] != source_len || dims[2] < 1) {
        return Fail(Status::kBadModel, "encoder_model: last_hidden_state is not [1, source length, hidden]");
    }

    // 2. decoder_model: the start token, batch 1.
    int64_t start = options_.decoder_start_id;
    const int64_t one_shape[2] = {1, 1};
    Value start_value;
    status = tensor(&start, sizeof(start), one_shape, 2, kInt64, &start_value);
    if (status != Status::kOk) {
        return status;
    }
    std::vector<Value> first;
    {
        std::vector<const char*> out_names;
        out_names.reserve(first_outputs_.size());
        for (const std::string& name : first_outputs_) {
            out_names.push_back(name.c_str());
        }
        std::vector<OrtValue*> out_values(first_outputs_.size(), nullptr);
        const char* in_names[] = {"input_ids", "encoder_attention_mask", "encoder_hidden_states"};
        const OrtValue* in_values[] = {start_value.get(), mask_value.get(), hidden.get()};
        OrtStatus* run = api_->Run(decoder_, run_shrink_, in_names, in_values, 3, out_names.data(), out_names.size(),
                                   out_values.data());
        for (OrtValue* value : out_values) {
            first.emplace_back(value);
        }
        status = Check(run, Status::kRunFailed);
        if (status != Status::kOk) {
            return status;
        }
    }
    hidden.reset();
    for (const Value& value : first) {
        if (value == nullptr) {
            return Fail(Status::kRunFailed, "decoder_model returned no tensor for an output");
        }
    }
    status = shape_of(first[0].get(), &type, &dims);
    if (status != Status::kOk) {
        return status;
    }
    if (type != kFloat || dims.size() != 3 || dims[0] != 1 || dims[1] != 1 || dims[2] < 1 || dims[2] > kMaxVocab) {
        return Fail(Status::kBadModel, "decoder_model: logits are not [1, 1, vocabulary]");
    }
    const int64_t vocab = dims[2];
    if (vocab_ > 0 && vocab != vocab_) {
        return Fail(Status::kBadModel, "decoder_model: logits over another vocabulary than the graphs declare");
    }
    if (options_.eos_id >= vocab || options_.pad_id >= vocab || options_.decoder_start_id >= vocab) {
        return Fail(Status::kBadModel, "the start, end or padding id is outside the target vocabulary");
    }
    size_t heads = heads_;
    size_t head_dim = head_dim_;
    for (size_t layer = 0; layer < layers_; ++layer) {
        for (size_t part = 0; part < 4; ++part) {
            const size_t index = 1 + 4 * layer + part;
            status = shape_of(first[index].get(), &type, &dims);
            if (status != Status::kOk) {
                return status;
            }
            const int64_t length = part < 2 ? 1 : source_len;
            if (type != kFloat || dims.size() != 4 || dims[0] != 1 || dims[2] != length || dims[1] < 1 || dims[3] < 1 ||
                dims[1] > static_cast<int64_t>(KvCache::kMaxHeads) ||
                dims[3] > static_cast<int64_t>(KvCache::kMaxHeadDim)) {
                return Fail(Status::kBadModel, "decoder_model: " + first_outputs_[index] + " is not [1, heads, " +
                                                   (part < 2 ? "1" : "source length") + ", head size]");
            }
            if (heads == 0) {
                heads = static_cast<size_t>(dims[1]);
                head_dim = static_cast<size_t>(dims[3]);
            }
            if (static_cast<size_t>(dims[1]) != heads || static_cast<size_t>(dims[3]) != head_dim) {
                return Fail(Status::kBadModel, "decoder_model: " + first_outputs_[index] +
                                                   " has another head shape than decoder_with_past_model takes");
            }
        }
    }
    heads_ = heads;
    head_dim_ = head_dim;
    vocab_ = vocab;

    // 3. The caches.
    KvCache cache;
    status = cache.Allocate(KvCache::Shape{layers_, heads, head_dim, beams, cap, count});
    if (status != Status::kOk) {
        return Fail(status, "no room for the attention caches");
    }
    const size_t cross_floats = heads * count * head_dim;
    for (size_t layer = 0; layer < layers_; ++layer) {
        for (size_t kv = 0; kv < 2; ++kv) {
            float* data = nullptr;
            status = floats_of(first[1 + 4 * layer + 2 + kv].get(), &data);
            if (status == Status::kOk) {
                status = cache.FillCross(layer, kv, data, cross_floats);
            }
            if (status != Status::kOk) {
                return status == Status::kInternal ? Fail(status, "cross cache copy") : status;
            }
        }
    }

    // 4. The first step of the search, with the minimum length asked for (fairseq's 1 unless a
    // debug worst case holds the end back to the cap).
    BeamSearch search;
    status = search.Start(
        BeamSearch::Config{beam, static_cast<int>(cap), static_cast<int32_t>(options_.eos_id), static_cast<int32_t>(options_.pad_id),
                           min_tokens},
        static_cast<size_t>(vocab));
    if (status != Status::kOk) {
        return Fail(status, "the search's settings don't fit the vocabulary");
    }
    float* first_logits = nullptr;
    status = floats_of(first[0].get(), &first_logits);
    if (status != Status::kOk) {
        return status;
    }
    status = search.Step(first_logits, 1, static_cast<size_t>(vocab));
    if (status != Status::kOk) {
        return Fail(status, status == Status::kRunFailed ? "decoder_model: logits that are not finite" : "search step");
    }
    int steps = 1;
    if (!search.done()) {
        std::vector<const float*> self(2 * layers_, nullptr);
        for (size_t layer = 0; layer < layers_; ++layer) {
            for (size_t kv = 0; kv < 2; ++kv) {
                float* data = nullptr;
                status = floats_of(first[1 + 4 * layer + kv].get(), &data);
                if (status != Status::kOk) {
                    return status;
                }
                self[2 * layer + kv] = data;
            }
        }
        status = cache.Gather(self.data(), 1, 1, search.parents().data(), search.active());
        if (status != Status::kOk) {
            return Fail(status, "self cache copy after the first step");
        }
    }
    first.clear();

    // 5. decoder_with_past, one token per beam and step.
    std::vector<int64_t> step_ids(beams, 0);
    std::vector<float> logits(beams * static_cast<size_t>(vocab), 0.0f);
    std::vector<const char*> in_names;
    std::vector<const char*> out_names;
    for (const std::string& name : step_inputs_) {
        in_names.push_back(name.c_str());
    }
    for (const std::string& name : step_outputs_) {
        out_names.push_back(name.c_str());
    }
    std::vector<Value> inputs(in_names.size());
    std::vector<Value> outputs(out_names.size());
    std::vector<const OrtValue*> in_values(in_names.size(), nullptr);
    std::vector<OrtValue*> out_values(out_names.size(), nullptr);
    const auto rows_of = [](size_t rows, size_t a, size_t b, size_t c) { return rows * a * b * c * sizeof(float); };
    while (!search.done()) {
        if (cancelled_.load()) {
            return Fail(Status::kCancelled, "cancelled");
        }
        const size_t rows = search.active();
        const size_t past = search.generated();
        if (rows == 0 || rows > beams || past == 0 || past >= cap) {
            return Fail(Status::kInternal, "the search went on past its cap");
        }
        for (size_t row = 0; row < rows; ++row) {
            step_ids[row] = search.next_tokens()[row];
        }
        const auto n = static_cast<int64_t>(rows);
        const auto h = static_cast<int64_t>(heads);
        const auto d = static_cast<int64_t>(head_dim);
        const auto p = static_cast<int64_t>(past);
        const int64_t ids_shape[2] = {n, 1};
        const int64_t mask_shape[2] = {n, source_len};
        const int64_t past_shape[4] = {n, h, p, d};
        const int64_t cross_shape[4] = {n, h, source_len, d};
        const int64_t present_shape[4] = {n, h, p + 1, d};
        const int64_t logits_shape[3] = {n, 1, vocab};
        status = tensor(step_ids.data(), rows * sizeof(int64_t), ids_shape, 2, kInt64, &inputs[0]);
        if (status == Status::kOk) {
            status = tensor(mask.data(), rows * count * sizeof(int64_t), mask_shape, 2, kInt64, &inputs[1]);
        }
        for (size_t layer = 0; status == Status::kOk && layer < layers_; ++layer) {
            const size_t at = 2 + 4 * layer;
            status = tensor(cache.past(layer, 0), rows_of(rows, heads, past, head_dim), past_shape, 4, kFloat, &inputs[at]);
            if (status == Status::kOk) {
                status = tensor(cache.past(layer, 1), rows_of(rows, heads, past, head_dim), past_shape, 4, kFloat,
                                &inputs[at + 1]);
            }
            if (status == Status::kOk) {
                status = tensor(cache.cross(layer, 0), rows_of(rows, heads, count, head_dim), cross_shape, 4, kFloat,
                                &inputs[at + 2]);
            }
            if (status == Status::kOk) {
                status = tensor(cache.cross(layer, 1), rows_of(rows, heads, count, head_dim), cross_shape, 4, kFloat,
                                &inputs[at + 3]);
            }
        }
        if (status == Status::kOk) {
            status = tensor(logits.data(), rows * static_cast<size_t>(vocab) * sizeof(float), logits_shape, 3, kFloat,
                            &outputs[0]);
        }
        // The outputs in step_outputs_' order, per layer as kParts: present.N.decoder.* into the
        // self buffer that becomes the next past, and present.N.encoder.* into the cross buffer fed
        // as past_key_values.N.encoder.* above (same memory, same shape). The graph passes the
        // cross cache through unchanged (an Identity in the toy graphs), and ONNX Runtime's copy
        // skips a target that is its source; bound like this nothing is copied, and the arena
        // allocates nothing for it.
        for (size_t layer = 0; status == Status::kOk && layer < layers_; ++layer) {
            const size_t at = 1 + 4 * layer;
            status = tensor(cache.present(layer, 0), rows_of(rows, heads, past + 1, head_dim), present_shape, 4, kFloat,
                            &outputs[at]);
            if (status == Status::kOk) {
                status = tensor(cache.present(layer, 1), rows_of(rows, heads, past + 1, head_dim), present_shape, 4,
                                kFloat, &outputs[at + 1]);
            }
            if (status == Status::kOk) {
                status = tensor(cache.cross(layer, 0), rows_of(rows, heads, count, head_dim), cross_shape, 4, kFloat,
                                &outputs[at + 2]);
            }
            if (status == Status::kOk) {
                status = tensor(cache.cross(layer, 1), rows_of(rows, heads, count, head_dim), cross_shape, 4, kFloat,
                                &outputs[at + 3]);
            }
        }
        if (status != Status::kOk) {
            return status;
        }
        for (size_t k = 0; k < inputs.size(); ++k) {
            in_values[k] = inputs[k].get();
        }
        for (size_t k = 0; k < outputs.size(); ++k) {
            out_values[k] = outputs[k].get();
        }
        OrtStatus* run = api_->Run(decoder_with_past_, run_, in_names.data(), in_values.data(), in_values.size(),
                                   out_names.data(), out_values.size(), out_values.data());
        // ONNX Runtime writes into the tensors given; anything else it left in the array is freed.
        bool replaced = false;
        for (size_t k = 0; k < outputs.size(); ++k) {
            if (out_values[k] != outputs[k].get()) {
                replaced = true;
                if (out_values[k] != nullptr) {
                    api_->ReleaseValue(out_values[k]);
                }
            }
        }
        status = Check(run, Status::kRunFailed);
        if (status != Status::kOk) {
            return status;
        }
        if (replaced) {
            return Fail(Status::kInternal, "ONNX Runtime did not write into the reader's buffers");
        }
        ++steps;
        status = search.Step(logits.data(), rows, static_cast<size_t>(vocab));
        if (status != Status::kOk) {
            return Fail(status, status == Status::kRunFailed ? "decoder_with_past_model: logits that are not finite"
                                                             : "search step");
        }
        if (search.done()) {
            break;
        }
        status = cache.Gather(nullptr, rows, past + 1, search.parents().data(), search.active());
        if (status != Status::kOk) {
            return Fail(status, "self cache reorder");
        }
    }
    status = search.Result(&out->ids, &out->mean_log_prob, &out->capped);
    if (status != Status::kOk) {
        return Fail(status, "search result");
    }
    out->steps = steps;
    return Status::kOk;
}

}  // namespace satori::reader
