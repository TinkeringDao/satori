#include <android/log.h>
#include <jni.h>
#include <iomanip>
#include <cerrno>
#include <cmath>
#include <cstdio>
#include <fcntl.h>
#include <malloc.h>
#include <string>
#include <sys/stat.h>
#include <sys/system_properties.h>
#include <unistd.h>
#include <cstdlib>
#include <cstring>
#include <vector>
#include <sampling.h>

#include "logging.h"
#include "chat.h"
#include "common.h"
#include "drop_pages.h"
#include "grammar_sampler.h"
#include "llama.h"

template<class T>
static std::string join(const std::vector<T> &values, const std::string &delim) {
    std::ostringstream str;
    for (size_t i = 0; i < values.size(); i++) {
        str << values[i];
        if (i < values.size() - 1) { str << delim; }
    }
    return str.str();
}

/**
 * LLama resources: context, model, batch and sampler
 */
constexpr int   N_THREADS_MIN           = 2;
constexpr int   N_THREADS_MAX           = 4;
constexpr int   N_THREADS_HEADROOM      = 2;

// 4096 covers all use cases (worst case ~3968 tokens for LONG_MEETING:
//   system ~150 + context prefix ~150 + analyze 12000 chars ~2900 + output 768).
// Halves KV cache memory vs the previous 8192. Increasing n_ctx only grows
// pre-allocated RAM (KV-cache), not CPU/battery — inference cost tracks actual
// tokens processed, not context capacity. 4096 is the correct ceiling here.
constexpr int   DEFAULT_CONTEXT_SIZE    = 4096;
constexpr int   OVERFLOW_HEADROOM       = 4;
constexpr int   BATCH_SIZE              = 512;
constexpr float DEFAULT_SAMPLER_TEMP    = 0.3f;

static llama_model                      * g_model;
static llama_context                    * g_context;

// The context's cells as llama.cpp made it (llama_n_ctx, a multiple of 256): DEFAULT_CONTEXT_SIZE,
// unless a debug build asked for another n_ctx (LoadSettings.kt). Every check against the end of the
// context uses it.
static int                                g_n_ctx = DEFAULT_CONTEXT_SIZE;

// Whether the KV cache of the sliding-window layers spans the whole context (swa_full, the default).
// A windowed one reuses the cells that fall out of the window, so after a reply longer than the
// window the system prompt's cells may be gone from it, and the KV cache can't be rewound to the end
// of the system prompt (see processSystemPrompt).
static bool                               g_swa_full = true;
static llama_batch                        g_batch;
static common_chat_templates_ptr          g_chat_templates;
static common_sampler                   * g_sampler;

// Settings of g_sampler. The grammar sampler of a reply reuses them without the repeat penalties
// (see grammar_params).
static common_params_sampling             g_sampler_params;

// The grammar of the reply being generated, if it has one. processUserPrompt begins it for that
// reply only and the reply's end frees it, so later replies are free text again. g_sampler stays
// as it is meanwhile.
static grammar_reply                      g_grammar;

// The text of a ByteArray holding UTF-8 (String.toByteArray(Charsets.UTF_8) in InferenceEngineImpl).
// Text comes as bytes because GetStringUTFChars gives Modified UTF-8, which writes a character
// outside the BMP, such as an emoji, as two surrogates: a JSON schema holding one fails to parse
// and the model would read it as noise.
static std::string utf8_from_bytes(JNIEnv *env, jbyteArray bytes) {
    std::string text;
    if (bytes == nullptr) { return text; }
    const jsize size = env->GetArrayLength(bytes);
    if (size > 0) {
        text.resize(static_cast<size_t>(size));
        env->GetByteArrayRegion(bytes, 0, size, reinterpret_cast<jbyte *>(text.data()));
    }
    return text;
}

// True for hybrid SSM+attention models (e.g. Qwen3.5) that use M-RoPE and
// cannot rewind the recurrent memory state to an earlier position.
static bool                               g_model_has_mrope = false;

// Whether the loaded model's chat template should emit extended thinking tokens.
// Set to false for models whose template supports enable_thinking (currently Gemma 4)
// so the Jinja template omits <|think|> from the system turn and automatically
// appends the thinking-suppression prefix in the generation prompt.
// For all other models the parameter is a no-op in their template.
static bool                               g_model_enable_thinking = true;

extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_init(JNIEnv *env, jobject /*unused*/, jstring nativeLibDir) {
    // Set llama log handler to Android
    llama_log_set(aichat_android_log_callback, nullptr);

    // Disable KleidiAI SME/SME2 kernels.
    // Some Android devices advertise HWCAP2_SME2 but crash with SIGILL (ILL_ILLOPC)
    // when the sme2_mopa instruction actually executes (llama.cpp issue #15973).
    // Setting this env var before ggml_backend_load_all_from_path() makes KleidiAI
    // fall back to DOTPROD/I8MM kernels on all devices, which is the correct behaviour
    // for the vast majority of Android hardware in use today.
    // Remove once llama.cpp/KleidiAI upstream adds a proper HWCAP2_SME2 runtime probe.
    setenv("GGML_KLEIDIAI_SME", "0", 1);

    // Loading all CPU backend variants
    const auto *path_to_backend = env->GetStringUTFChars(nativeLibDir, 0);
    LOGi("Loading backends from %s", path_to_backend);
    ggml_backend_load_all_from_path(path_to_backend);
    env->ReleaseStringUTFChars(nativeLibDir, path_to_backend);

    // Initialize backends
    llama_backend_init();
    LOGi("Backend initiated; Log handler set.");
}

#ifndef M_PURGE_ALL
#define M_PURGE_ALL (-104)
#endif

// The process's native heap (bionic's allocator, which llama.cpp, ONNX Runtime and sherpa-onnx all
// allocate from) gives the pages of the memory freed so far back to the kernel now, as Zygote does
// before it starts each app's process: 2 with M_PURGE_ALL (API 34), 1 with M_PURGE (API 28), 0 when
// neither takes (mallopt answers 0 for an option it doesn't know). A speech model's release calls it
// (NativeHeap.kt).
extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_NativeHeap_purge(JNIEnv * /*env*/, jclass /*unused*/) {
    if (mallopt(M_PURGE_ALL, 0) == 1) {
        return 2;
    }
    if (mallopt(M_PURGE, 0) == 1) {
        return 1;
    }
    return 0;
}

// The system property `name`, "" when it isn't set (LoadSettings.kt reads its debug switches so).
extern "C"
JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_systemProperty(JNIEnv *env, jobject /*unused*/, jstring jname) {
    const char *name = env->GetStringUTFChars(jname, nullptr);
    std::string value;
    if (name != nullptr) {
        const prop_info *info = __system_property_find(name);
        env->ReleaseStringUTFChars(jname, name);
        if (info != nullptr) {
            __system_property_read_callback(info, [](void *cookie, const char *, const char *text, uint32_t) {
                *static_cast<std::string *>(cookie) = text;
            }, &value);
        }
    } else {
        env->ExceptionClear();
    }
    return env->NewStringUTF(value.c_str());
}

// Whether the file at `jpath` opens with O_DIRECT and has a block size to align reads to: the test
// llama.cpp's own direct IO makes (llama_file::impl::init_fd and has_direct_io) before it turns
// mmap off for it.
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_directIoApplies(JNIEnv *env, jobject /*unused*/, jstring jpath) {
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    if (path == nullptr) {
        env->ExceptionClear();
        return JNI_FALSE;
    }
    const int fd = open(path, O_RDONLY | O_DIRECT | O_CLOEXEC);
    env->ReleaseStringUTFChars(jpath, path);
    if (fd < 0) {
        return JNI_FALSE;
    }
    struct stat st {};
    const bool aligned = fstat(fd, &st) == 0 && st.st_blksize > 1;
    close(fd);
    return aligned ? JNI_TRUE : JNI_FALSE;
}

// Drops the file's pages from the page cache (a debug build's cold load, LoadSettings.kt, and its
// pre-read probe), so the read that follows comes from flash. Since 0.7.2 with MADV_PAGEOUT on the
// file's cached pages (drop_pages.h): posix_fadvise alone, 0.7.1's drop, drops nothing of a file
// under Android/data, so cold memtests on the phone started partly warm. Only pages no other mapping
// holds go, and the engine has unloaded its model before any load. Returns whether the drop ran
// without an error; its line says what was cached before and after.
static bool drop_cached_pages(const char *path) {
    const satori::file_drop_result drop = satori::drop_file_pages(path);
    constexpr double MB = 1048576.0;
    if (drop.error != 0) {
        LOGw("%s: the file's cached pages not all dropped: %s", __func__, strerror(drop.error));
    }
    if (!drop.seen) {
        LOGi("%s: posix_fadvise only: the file is neither the app's nor writable by it, so its cached pages can't be seen or paged out",
             __func__);
    } else {
        LOGi("%s: %.0f of the file's %.0f MB were cached, %.0f MB still are (MADV_PAGEOUT, then posix_fadvise), in %.1f ms",
             __func__, drop.cached_before_bytes < 0 ? -1.0 : drop.cached_before_bytes / MB, drop.size_bytes / MB,
             drop.cached_after_bytes < 0 ? -1.0 : drop.cached_after_bytes / MB, drop.us / 1000.0);
    }
    return drop.error == 0;
}

// The pages of the copied tensors of the last load, given back by on_mapped (LoadSettings.kt's
// dropcopied): summed over the model's files, `ran` false when the load copied nothing or had no
// hook. Loads come one at a time, on the engine's thread.
struct copied_drop {
    bool ran = false;
    satori::drop_result total;
};
static copied_drop g_copied_drop;

// llama.cpp's loader calls this (patches/0003-mapped-hook.patch) once per file of a load with mmap,
// after its last tensor and before it unmaps the head and tail, with the byte ranges, as pairs, of
// the tensors it copied and of those it uses in place. The copied ones' whole pages go back to the
// kernel (drop_pages.h); every page an in-place tensor has a byte on stays mapped and cached.
static void on_mapped(void *addr, size_t size, int fd, const size_t *copied, size_t n_copied,
                      const size_t *in_place, size_t n_in_place, void * /*user_data*/) {
    if (n_copied == 0) {
        return;
    }
    const satori::drop_result drop = satori::drop_copied_pairs(addr, size, fd, copied, n_copied, in_place, n_in_place);
    if (!g_copied_drop.ran) {
        g_copied_drop.total = drop;
        g_copied_drop.ran = true;
    } else {
        satori::add_drop(g_copied_drop.total, drop);
    }
}

// The last load's drop of its copied tensors' pages, for InferenceEngineImpl's line: copied bytes,
// bytes given back, bytes still cached (-1 unknown), ranges, the first errno, whether MADV_PAGEOUT
// may act (1 or 0), RssFile before and after in kB, and the microseconds it took; null when the
// load gave nothing back (nothing copied, the switch off, or no load yet).
extern "C"
JNIEXPORT jlongArray JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_lastDrop(JNIEnv *env, jobject /*unused*/) {
    if (!g_copied_drop.ran) {
        return nullptr;
    }
    const satori::drop_result &d = g_copied_drop.total;
    const jlong values[] = {
            d.copied_bytes, d.dropped_bytes, d.cached_bytes, d.ranges, d.error, d.pageout ? 1 : 0,
            d.rss_file_before_kb, d.rss_file_after_kb, d.us
    };
    const jsize count = (jsize) (sizeof values / sizeof values[0]);
    jlongArray array = env->NewLongArray(count);
    if (array != nullptr) {
        env->SetLongArrayRegion(array, 0, count, values);
    }
    return array;
}

// InferenceEngineImpl calls it before a cold load's pre-read and native load, so what the pre-read
// reads in stays cached for llama.cpp.
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_dropFileCache(JNIEnv *env, jobject /*unused*/, jstring jpath) {
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    if (path == nullptr) {
        env->ExceptionClear();
        return JNI_FALSE;
    }
    const bool dropped = drop_cached_pages(path);
    env->ReleaseStringUTFChars(jpath, path);
    return dropped ? JNI_TRUE : JNI_FALSE;
}

// The CPU backend's extra buffer type called `name` (CPU_KLEIDIAI, CPU_REPACK), as llama.cpp finds
// them (make_cpu_buft_list), or nullptr when there is none of that name.
static ggml_backend_buffer_type_t cpu_extra_buffer_type(const char *name) {
    ggml_backend_dev_t cpu = ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU);
    if (cpu == nullptr) { return nullptr; }
    auto get_extra_bufts = (ggml_backend_dev_get_extra_bufts_t)
            ggml_backend_reg_get_proc_address(ggml_backend_dev_backend_reg(cpu), "ggml_backend_dev_get_extra_bufts");
    if (get_extra_bufts == nullptr) { return nullptr; }
    for (ggml_backend_buffer_type_t *buft = get_extra_bufts(cpu); buft != nullptr && *buft != nullptr; ++buft) {
        if (strcmp(ggml_backend_buft_name(*buft), name) == 0) { return *buft; }
    }
    return nullptr;
}

// The tied output left mapped (LoadSettings.kt, tiedout=mapped: Gemma 3 1B's and Gemma 4 E2B's loads
// since 0.6.1): the tensors whose names match this go to KleidiAI's buffer type, every other one
// stays in the mapping. llama.cpp reads the list only while it loads; loads come one at a time, on
// the engine's thread.
static std::string g_tied_output_layers;
static llama_model_tensor_buft_override g_layer_overrides[2];

// The app's pacer of the weight copy (InferenceEngine.WeightPacer: every model's copy pace since 0.6.1,
// which a debug build's debug.satori.llm.copypace can change), which llama.cpp's loader calls with its
// progress before each tensor, on the loading thread, inside load().
// The progress is a share of the loader's own count of bytes (patches/0001-load-progress-bytes.patch:
// a duplicated tensor counts twice, the header not at all), which the pacer gets in bytes. The pacer
// answers whether the load goes on: false, once the load waited 10 s in all for memory, stops it, and
// the loader then frees what it had and returns no model. A pacer that throws is called no more, and
// the copy goes on unpaced.
struct weight_pacer {
    JNIEnv *env;
    jobject pacer;
    jmethodID before_tensor;
    bool failed;
};

static bool pace_weight_copy(float progress, void *user_data) {
    auto *pacer = static_cast<weight_pacer *>(user_data);
    if (pacer->failed) {
        return true;
    }
    const double bytes = (double) progress * (double) llama_satori_load_progress_bytes();
    const jboolean go_on = pacer->env->CallBooleanMethod(pacer->pacer, pacer->before_tensor, (jlong) bytes);
    if (pacer->env->ExceptionCheck()) {
        pacer->env->ExceptionClear();
        pacer->failed = true;
        LOGw("%s: the weight pacer failed; the copy goes on unpaced", __func__);
        return true;
    }
    if (go_on != JNI_TRUE) {
        LOGw("%s: the weight pacer stopped the load: the phone stayed short of memory", __func__);
        return false;
    }
    return true;
}

// The model's parameters come from LoadSettings.kt, the model's own (mmap, no direct IO, the extra
// buffer types that repack the weights) unless a debug build asked for others. With
// jtied_output_layers (and the extra buffer types on), only the tensors it matches are repacked, by
// KleidiAI; with jpacer, the loader's progress paces the copy, and may stop the load; without
// populate the file is mapped without MAP_POPULATE (patches/0002-map-populate-switch.patch), set
// for each load; with drop_copied the pages of the tensors copied out of the mapping go back to the
// kernel once the last tensor is loaded (patches/0003-mapped-hook.patch, on_mapped), which
// lastDrop then tells.
extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_load(JNIEnv *env, jobject, jstring jmodel_path,
                                                      jboolean use_mmap, jboolean use_direct_io,
                                                      jboolean use_extra_bufts, jstring jtied_output_layers,
                                                      jobject jpacer, jboolean populate, jboolean drop_copied) {
    llama_model_params model_params = llama_model_default_params();
    model_params.use_mmap = use_mmap == JNI_TRUE;
    model_params.use_direct_io = use_direct_io == JNI_TRUE;
    model_params.use_extra_bufts = use_extra_bufts == JNI_TRUE;
    // use_mlock intentionally left at default (false): Android apps lack
    // CAP_IPC_LOCK, so mlock() silently fails with EPERM. Enabling it
    // gives false confidence that model weights are pinned in RAM when
    // they are not — kswapd can and will page them out under pressure.

    if (jtied_output_layers != nullptr && model_params.use_extra_bufts) {
        ggml_backend_buffer_type_t kleidiai = cpu_extra_buffer_type("CPU_KLEIDIAI");
        const char *layers = env->GetStringUTFChars(jtied_output_layers, nullptr);
        if (layers == nullptr) {
            env->ExceptionClear();
        }
        if (kleidiai == nullptr || layers == nullptr) {
            LOGw("%s: tied output left in the repack buffers: %s", __func__,
                 kleidiai == nullptr ? "no CPU_KLEIDIAI buffer type" : "the layers' pattern wasn't read");
        } else {
            g_tied_output_layers = layers;
            g_layer_overrides[0] = {g_tied_output_layers.c_str(), kleidiai};
            g_layer_overrides[1] = {nullptr, nullptr};
            model_params.use_extra_bufts = false;
            model_params.tensor_buft_overrides = g_layer_overrides;
            LOGi("%s: tied output left mapped; only the layers' matrices go to CPU_KLEIDIAI", __func__);
        }
        if (layers != nullptr) {
            env->ReleaseStringUTFChars(jtied_output_layers, layers);
        }
    }

    weight_pacer pacer{};
    if (jpacer != nullptr) {
        jclass pacer_class = env->GetObjectClass(jpacer);
        jmethodID before_tensor = pacer_class != nullptr ? env->GetMethodID(pacer_class, "beforeTensor", "(J)Z") : nullptr;
        if (before_tensor == nullptr) {
            env->ExceptionClear();
            LOGw("%s: no weight pacer to call; the copy goes on unpaced", __func__);
        } else {
            pacer = weight_pacer{env, jpacer, before_tensor, false};
            model_params.progress_callback = pace_weight_copy;
            model_params.progress_callback_user_data = &pacer;
        }
        if (pacer_class != nullptr) {
            env->DeleteLocalRef(pacer_class);
        }
    }

    llama_satori_set_map_populate(populate == JNI_TRUE);
    if (populate != JNI_TRUE) {
        LOGi("%s: no MAP_POPULATE; the file's pages come in as they are used", __func__);
    }

    g_copied_drop = copied_drop{};
    llama_satori_set_mapped_hook(drop_copied == JNI_TRUE ? on_mapped : nullptr, nullptr);

    const auto *model_path = env->GetStringUTFChars(jmodel_path, 0);
    LOGd("%s: Loading model from: \n%s\n", __func__, model_path);

    auto *model = llama_model_load_from_file(model_path, model_params);
    env->ReleaseStringUTFChars(jmodel_path, model_path);
    llama_satori_set_mapped_hook(nullptr, nullptr);
    if (!model) {
        return 1;
    }
    g_model = model;
    return 0;
}

// swa_full, kv_q8_0 and n_outputs_max come from LoadSettings.kt, the model's own (since 0.6.1 a
// windowed SWA cache, Q8_0 keys and values and one output for Gemma 3 1B and Gemma 4 E2B; the
// defaults here are llama.cpp's: a full-size SWA cache, F16 keys and values, as many outputs as the
// batch). A Q8_0 V cache needs flash attention, so it is asked for outright then; with F16 llama.cpp
// decides (auto, which turns it on for the CPU). n_outputs_max below what a batch asks for ends the
// process (GGML_ASSERT), and every batch here asks for one logit at most.
static llama_context *init_context(llama_model *model, const int n_ctx = DEFAULT_CONTEXT_SIZE, const int nThreadsHint = -1,
                                   const bool swa_full = true, const bool kv_q8_0 = false,
                                   const int n_outputs_max = 0) {
    if (!model) {
        LOGe("%s: model cannot be null", __func__);
        return nullptr;
    }

    // Multi-threading setup.
    // nThreadsHint > 0 lets callers override the thread count (e.g. conservative mode on
    // RAM-constrained devices, applied from the very first model load). The hint is still
    // clamped to [N_THREADS_MIN, N_THREADS_MAX] so the engine never goes below 2 or above 4 threads.
    // Note: reducing threads only noticeably affects throughput for very long input contexts
    // (prefill phase); on short prompts the latency difference is negligible.
    const int n_threads = (nThreadsHint > 0)
        ? std::max(N_THREADS_MIN, std::min(nThreadsHint, N_THREADS_MAX))
        : std::max(N_THREADS_MIN, std::min(N_THREADS_MAX,
                                           (int) sysconf(_SC_NPROCESSORS_ONLN) -
                                           N_THREADS_HEADROOM));
    LOGi("%s: Using %d threads (hint=%d)", __func__, n_threads, nThreadsHint);

    // Context parameters setup
    llama_context_params ctx_params = llama_context_default_params();
    const int trained_context_size = llama_model_n_ctx_train(model);
    if (n_ctx > trained_context_size) {
        LOGw("%s: Model was trained with only %d context size! Enforcing %d context size...",
             __func__, trained_context_size, n_ctx);
    }
    ctx_params.n_ctx = n_ctx;
    ctx_params.n_batch = BATCH_SIZE;
    ctx_params.n_ubatch = BATCH_SIZE;
    ctx_params.n_threads = n_threads;
    ctx_params.n_threads_batch = n_threads;
    ctx_params.swa_full = swa_full;
    ctx_params.n_outputs_max = (uint32_t) std::max(n_outputs_max, 0);
    if (kv_q8_0) {
        ctx_params.type_k = GGML_TYPE_Q8_0;
        ctx_params.type_v = GGML_TYPE_Q8_0;
        ctx_params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;
    }
    auto *context = llama_init_from_model(g_model, ctx_params);
    if (context == nullptr) {
        LOGe("%s: llama_new_context_with_model() returned null)", __func__);
    }
    return context;
}

static common_params_sampling sampler_params(
    float temp,
    int top_k             = 40,
    float top_p           = 0.95f,
    float min_p           = 0.05f,
    float repeat_penalty  = 1.0f
) {
    common_params_sampling sparams;
    sparams.temp           = temp;
    sparams.top_k          = top_k;
    sparams.top_p          = top_p;
    sparams.min_p          = min_p;
    sparams.penalty_repeat = repeat_penalty;
    return sparams;
}

static common_sampler *new_sampler(const common_params_sampling &base) {
    // common_sampler_init() may adjust the params it gets, so it gets a copy.
    common_params_sampling sparams = base;
    return common_sampler_init(g_model, sparams);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_prepare(JNIEnv * /*env*/, jobject /*unused*/, jint nThreadsHint,
                                                         jint n_ctx, jboolean swa_full, jboolean kv_q8_0,
                                                         jint n_outputs_max) {
    auto *context = init_context(g_model, n_ctx, nThreadsHint, swa_full == JNI_TRUE, kv_q8_0 == JNI_TRUE, n_outputs_max);
    if (!context) { return 1; }
    g_context = context;
    g_n_ctx = (int) llama_n_ctx(context);
    g_swa_full = swa_full == JNI_TRUE;
    g_batch = llama_batch_init(BATCH_SIZE, 0, 1);
    g_chat_templates = common_chat_templates_init(g_model, "");
    g_grammar.end();
    g_sampler_params = sampler_params(DEFAULT_SAMPLER_TEMP);
    g_sampler = new_sampler(g_sampler_params);

    // Detect models that use multi-dimensional / interleaved RoPE combined with recurrent
    // SSM layers (Mamba2/GDN). These models store a rolling recurrent state whose position
    // cannot be rewound to an earlier value: llama.cpp enforces "X < Y" for any new batch
    // starting at position Y when the last recurrent state is at position X.
    //
    // Two rope types require this treatment (both confirmed via upstream llama.cpp issues):
    //   LLAMA_ROPE_TYPE_MROPE  (8)  — standard multi-dimensional RoPE (e.g. Qwen2.5-VL)
    //   LLAMA_ROPE_TYPE_IMROPE (40) — interleaved M-RoPE, used by Qwen3.5 hybrid SSM+attn
    //     (llama.cpp issues #18497, #19858, #20133, #20225)
    //
    // Standard attention-only models (Gemma3 → NEOX=2, Llama → NORM=0) are unaffected
    // and continue to benefit from the KV-cache reuse optimisation.
    const auto rope_type = llama_model_rope_type(g_model);
    g_model_has_mrope = (rope_type == LLAMA_ROPE_TYPE_MROPE || rope_type == LLAMA_ROPE_TYPE_IMROPE);
    LOGi("prepare: rope_type=%d has_mrope=%s", (int) rope_type, g_model_has_mrope ? "true" : "false");

    // Detect models whose chat template supports enable_thinking (currently Gemma 4).
    // For these models we disable thinking via enable_thinking=false so the Jinja template
    // suppresses reasoning tokens automatically, keeping the full output budget for JSON.
    char arch_buf[32] = {};
    const int arch_len = llama_model_meta_val_str(g_model, "general.architecture", arch_buf, sizeof(arch_buf));
    const std::string arch_str = (arch_len > 0) ? std::string(arch_buf) : "";
    g_model_enable_thinking = (arch_str != "gemma4");
    LOGi("prepare: arch=%s enable_thinking=%s", arch_buf, g_model_enable_thinking ? "true" : "false");

    return 0;
}

static std::string get_backend() {
    std::vector<std::string> backends;
    for (size_t i = 0; i < ggml_backend_reg_count(); i++) {
        auto *reg = ggml_backend_reg_get(i);
        std::string name = ggml_backend_reg_name(reg);
        if (name != "CPU") {
            backends.push_back(ggml_backend_reg_name(reg));
        }
    }
    return backends.empty() ? "CPU" : join(backends, ",");
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_systemInfo(JNIEnv *env, jobject /*unused*/) {
    return env->NewStringUTF(llama_print_system_info());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_benchModel(JNIEnv *env, jobject /*unused*/, jint pp, jint tg,
                                                      jint pl, jint nr) {
    auto *context = init_context(g_model, pp);
    if (!context) {
        const auto *const err_msg = "Fail to init_context! Bench aborted.";
        LOGe(err_msg);
        return env->NewStringUTF(err_msg);
    }

    auto pp_avg = 0.0;
    auto tg_avg = 0.0;
    auto pp_std = 0.0;
    auto tg_std = 0.0;

    const uint32_t n_ctx = llama_n_ctx(context);
    LOGi("n_ctx = %d", n_ctx);

    int i, j;
    int nri;
    for (nri = 0; nri < nr; nri++) {
        LOGi("Benchmark prompt processing (pp = %d)", pp);

        common_batch_clear(g_batch);

        const int n_tokens = pp;
        for (i = 0; i < n_tokens; i++) {
            common_batch_add(g_batch, 0, i, {0}, false);
        }

        g_batch.logits[g_batch.n_tokens - 1] = true;
        llama_memory_clear(llama_get_memory(context), false);

        const auto t_pp_start = ggml_time_us();
        if (llama_decode(context, g_batch) != 0) {
            LOGe("llama_decode() failed during prompt processing");
        }
        const auto t_pp_end = ggml_time_us();

        // bench text generation

        LOGi("Benchmark text generation (tg = %d)", tg);

        llama_memory_clear(llama_get_memory(context), false);
        const auto t_tg_start = ggml_time_us();
        for (i = 0; i < tg; i++) {
            common_batch_clear(g_batch);
            for (j = 0; j < pl; j++) {
                common_batch_add(g_batch, 0, i, {j}, true);
            }

            if (llama_decode(context, g_batch) != 0) {
                LOGe("llama_decode() failed during text generation");
            }
        }
        const auto t_tg_end = ggml_time_us();

        llama_memory_clear(llama_get_memory(context), false);

        const auto t_pp = double(t_pp_end - t_pp_start) / 1000000.0;
        const auto t_tg = double(t_tg_end - t_tg_start) / 1000000.0;

        const auto speed_pp = double(pp) / t_pp;
        const auto speed_tg = double(pl * tg) / t_tg;

        pp_avg += speed_pp;
        tg_avg += speed_tg;

        pp_std += speed_pp * speed_pp;
        tg_std += speed_tg * speed_tg;

        LOGi("pp %f t/s, tg %f t/s", speed_pp, speed_tg);
    }

    llama_free(context);

    pp_avg /= double(nr);
    tg_avg /= double(nr);

    if (nr > 1) {
        pp_std = sqrt(pp_std / double(nr - 1) - pp_avg * pp_avg * double(nr) / double(nr - 1));
        tg_std = sqrt(tg_std / double(nr - 1) - tg_avg * tg_avg * double(nr) / double(nr - 1));
    } else {
        pp_std = 0;
        tg_std = 0;
    }

    char model_desc[128];
    llama_model_desc(g_model, model_desc, sizeof(model_desc));

    const auto model_size = double(llama_model_size(g_model)) / 1024.0 / 1024.0 / 1024.0;
    const auto model_n_params = double(llama_model_n_params(g_model)) / 1e9;

    const auto backend = get_backend();
    std::stringstream result;
    result << std::setprecision(3);
    result << "| model | size | params | backend | test | t/s |\n";
    result << "| --- | --- | --- | --- | --- | --- |\n";
    result << "| " << model_desc << " | " << model_size << "GiB | " << model_n_params << "B | "
           << backend << " | pp " << pp << " | " << pp_avg << " ± " << pp_std << " |\n";
    result << "| " << model_desc << " | " << model_size << "GiB | " << model_n_params << "B | "
           << backend << " | tg " << tg << " | " << tg_avg << " ± " << tg_std << " |\n";
    return env->NewStringUTF(result.str().c_str());
}


/**
 * Completion loop's long-term states:
 * - chat management
 * - position tracking
 */
constexpr const char *ROLE_SYSTEM       = "system";
constexpr const char *ROLE_USER         = "user";
constexpr const char *ROLE_ASSISTANT    = "assistant";

static std::vector<common_chat_msg> chat_msgs;
static llama_pos system_prompt_position;
static llama_pos current_position;
static std::string g_encoded_system_prompt;

static void reset_long_term_states(const bool clear_kv_cache = true) {
    chat_msgs.clear();
    system_prompt_position = 0;
    current_position = 0;
    g_encoded_system_prompt.clear();

    if (clear_kv_cache)
        llama_memory_clear(llama_get_memory(g_context), false);
}

/**
 * TODO-hyin: implement sliding-window version as a better alternative
 *
 * Context shifting by discarding the older half of the tokens appended after system prompt:
 * - take the [system_prompt_position] first tokens from the original prompt
 * - take half of the last (system_prompt_position - system_prompt_position) tokens
 * - recompute the logits in batches
 */
static void shift_context() {
    const int n_discard = (current_position - system_prompt_position) / 2;
    LOGi("%s: Discarding %d tokens", __func__, n_discard);
    llama_memory_seq_rm(llama_get_memory(g_context), 0, system_prompt_position, system_prompt_position + n_discard);
    llama_memory_seq_add(llama_get_memory(g_context), 0, system_prompt_position + n_discard, current_position, -n_discard);
    current_position -= n_discard;
    LOGi("%s: Context shifting done! Current position: %d", __func__, current_position);
}

// Render a message list through the model's Jinja chat template.
//
// Uses common_chat_templates_apply directly (rather than common_chat_format_single) so we can
// pass enable_thinking. For most models the parameter is a no-op in their template; for Gemma 4
// it makes the template omit <|think|> from the system turn and append the thinking-suppression
// prefix in the generation prompt.
//
// May throw std::exception: some templates (e.g. Qwen3.5) refuse to render a message list that
// does not contain a user turn ("No user query found in messages."). Callers must handle this.
static std::string apply_chat_template(const std::vector<common_chat_msg> &messages,
                                       bool add_generation_prompt) {
    common_chat_templates_inputs inputs;
    inputs.use_jinja             = true;
    inputs.enable_thinking       = g_model_enable_thinking;
    inputs.messages              = messages;
    inputs.add_generation_prompt = add_generation_prompt;
    return common_chat_templates_apply(g_chat_templates.get(), inputs).prompt;
}

static std::string chat_add_and_format(const std::string &role, const std::string &content) {
    common_chat_msg new_msg{role, content};

    // Render the conversation including the new message. The generation prompt is only appended
    // for USER turns (the model is expected to reply right after).
    std::vector<common_chat_msg> with_new = chat_msgs;
    with_new.push_back(new_msg);

    std::string fmt_new;
    try {
        fmt_new = apply_chat_template(with_new, role == ROLE_USER);
    } catch (const std::exception &e) {
        // The system turn is the only message we ever format before a user turn exists. Some
        // templates (e.g. Qwen3.5) cannot render a list without a user turn and raise here.
        // Defer the system turn: keep it in chat_msgs so it is folded into the next user turn,
        // but emit no tokens now. Re-throw for USER/ASSISTANT turns — those are always valid.
        if (role == ROLE_SYSTEM) {
            LOGw("%s: template cannot render the system turn alone (%s) — deferring it to the next user turn",
                 __func__, e.what());
            chat_msgs.push_back(new_msg);
            return "";
        }
        throw;
    }

    // Compute the already-formatted prefix to extract only the new message diff. If the prefix is
    // itself a list the template cannot render alone (a deferred system turn), treat fmt_new as
    // entirely new so the system turn is encoded together with this message.
    std::string fmt_past;
    if (!chat_msgs.empty()) {
        try {
            fmt_past = apply_chat_template(chat_msgs, false);
        } catch (const std::exception &) {
            fmt_past.clear();
        }
    }

    const auto diff = fmt_new.substr(fmt_past.size());
    // Preserve leading newline when past text ends with '\n' (mirrors common_chat_format_single).
    const auto formatted = (!fmt_past.empty() && fmt_past.back() == '\n') ? "\n" + diff : diff;

    chat_msgs.push_back(new_msg);
    LOGi("%s: Formatted and added %s message (%zu chars)", __func__, role.c_str(), formatted.size());
    return formatted;
}

/**
 * Completion loop's short-term states:
 * - stop generation position
 * - token chars caching
 * - current assistant message being generated
 */
static llama_pos stop_generation_position;
static std::string cached_token_chars;
static std::ostringstream assistant_ss;

static void reset_short_term_states() {
    stop_generation_position = 0;
    cached_token_chars.clear();
    assistant_ss.str("");
}

static int decode_tokens_in_batches(
        llama_context *context,
        llama_batch &batch,
        const llama_tokens &tokens,
        const llama_pos start_pos,
        const bool compute_last_logit = false) {
    // Process tokens in batches using the global batch
    LOGd("%s: Decode %d tokens starting at position %d", __func__, (int) tokens.size(), start_pos);
    for (int i = 0; i < (int) tokens.size(); i += BATCH_SIZE) {
        const int cur_batch_size = std::min((int) tokens.size() - i, BATCH_SIZE);
        common_batch_clear(batch);
        LOGv("%s: Preparing a batch size of %d starting at: %d", __func__, cur_batch_size, i);

        // Shift context if current batch cannot fit into the context
        if (start_pos + i + cur_batch_size >= g_n_ctx - OVERFLOW_HEADROOM) {
            LOGw("%s: Current batch won't fit into context! Shifting...", __func__);
            shift_context();
        }

        // Add tokens to the batch with proper positions
        for (int j = 0; j < cur_batch_size; j++) {
            const llama_token token_id = tokens[i + j];
            const llama_pos position = start_pos + i + j;
            const bool want_logit = compute_last_logit && (i + j == tokens.size() - 1);
            common_batch_add(batch, token_id, position, {0}, want_logit);
        }

        // Decode this batch
        const int decode_result = llama_decode(context, batch);
        if (decode_result) {
            LOGe("%s: llama_decode failed w/ %d", __func__, decode_result);
            return 1;
        }
    }
    return 0;
}

/**
 * System prompt cache: a system prompt's encoded state, kept in a file from one model load to the
 * next.
 *
 * On the phone's CPU Gemma 4 E2B takes about 3 s to encode a system prompt of about 1,000
 * characters, 1.5-5 s across the app's prompts. Every AI step after Stop has its own, and the model
 * is loaded for each finishing run and freed afterwards, while the in-memory reuse in
 * processSystemPrompt only helps consecutive calls with the same prompt. Loading the saved state
 * instead is a read of a few MB (18 KiB a token for Gemma 4 E2B). The file's name (PromptCache.kt,
 * beside InferenceEngineImpl) is a hash of the model file, the app's install and the prompt, so
 * only the setup that wrote a file reads it.
 *
 * The format is Satori's own, around llama.cpp's sequence state, in the phone's byte order:
 *   u32 magic, u32 format version
 *   u32 token count, then the tokens (i32 each)
 *   u64 state size, then the state (llama_state_seq_get_data of sequence 0)
 *   u64 FNV-1a 64 of all the bytes before it
 * The checksum is why the in-memory state functions are used and not llama_state_seq_save_file
 * and llama_state_seq_load_file: those pour the state from the file straight into the KV cache,
 * so a damaged file (a bad flash block, a write cut off) couldn't be told from a good one, and a
 * quietly damaged KV cache would spoil every answer that starts from it until the file is evicted.
 * Here such a file is a miss, and the encode that follows rewrites it.
 */
constexpr uint32_t PROMPT_CACHE_MAGIC    = 0x564b5053;  // "SPKV" in the file
constexpr uint32_t PROMPT_CACHE_VERSION  = 1;
constexpr size_t   PROMPT_CACHE_HEADER   = 3 * sizeof(uint32_t);
// A file without tokens or state: the header, the state size and the checksum
constexpr size_t   PROMPT_CACHE_MIN_SIZE = PROMPT_CACHE_HEADER + 2 * sizeof(uint64_t);
// The largest file written or read, PromptCache's cap (DEFAULT_MAX_BYTES): a bigger state would
// push every other one out of the directory at the next trim, and a bigger file can only be
// damage. Gemma 4 E2B's whole context is 72 MiB of state.
constexpr size_t   PROMPT_CACHE_MAX_SIZE = 100 * 1024 * 1024;

static uint64_t fnv1a_64(const uint8_t *data, const size_t size) {
    uint64_t hash = 0xcbf29ce484222325ULL;
    for (size_t i = 0; i < size; i++) {
        hash ^= data[i];
        hash *= 0x100000001b3ULL;
    }
    return hash;
}

// The length of a cache file of `n_tokens` tokens and `state_size` bytes of state
static size_t prompt_cache_size(const size_t n_tokens, const size_t state_size) {
    return PROMPT_CACHE_HEADER + n_tokens * sizeof(llama_token) + sizeof(uint64_t) + state_size + sizeof(uint64_t);
}

// Closes a file descriptor when it goes out of scope, also when an allocation throws first
struct fd_closer {
    const int fd;
    ~fd_closer() { close(fd); }
};

// Reads the cache file at `path` for the system prompt's `tokens`. Returns nullptr when it is a
// complete, intact cache file of these tokens, with the file in `file` and its state at
// [state_offset, state_offset + state_size). Else the miss reason for the log: "no file",
// "unreadable" (it can't be read, or isn't a complete and intact cache file) or "token mismatch".
// The whole file is read, its length from fstat and at most PROMPT_CACHE_MAX_SIZE, and the size
// fields must add up to that length, so no field decides an allocation.
static const char *read_prompt_cache(const std::string &path, const llama_tokens &tokens,
                                     std::vector<uint8_t> &file, size_t &state_offset, size_t &state_size) {
    const int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        return errno == ENOENT ? "no file" : "unreadable";
    }
    const fd_closer closer{fd};
    struct stat st {};
    if (fstat(fd, &st) != 0 || st.st_size < (off_t) PROMPT_CACHE_MIN_SIZE ||
        st.st_size > (off_t) PROMPT_CACHE_MAX_SIZE) {
        return "unreadable";
    }
    file.resize((size_t) st.st_size);
    size_t done = 0;
    while (done < file.size()) {
        const ssize_t n = read(fd, file.data() + done, file.size() - done);
        if (n > 0) {
            done += (size_t) n;
        } else if (n == 0 || errno != EINTR) {
            return "unreadable";
        }
    }

    uint32_t header[3];
    memcpy(header, file.data(), sizeof(header));
    const size_t n_tokens = header[2];
    if (header[0] != PROMPT_CACHE_MAGIC || header[1] != PROMPT_CACHE_VERSION ||
        n_tokens > (file.size() - PROMPT_CACHE_MIN_SIZE) / sizeof(llama_token)) {
        return "unreadable";
    }
    const size_t tokens_end = PROMPT_CACHE_HEADER + n_tokens * sizeof(llama_token);
    const size_t checksum_offset = file.size() - sizeof(uint64_t);
    uint64_t stored_state_size;
    uint64_t stored_checksum;
    memcpy(&stored_state_size, file.data() + tokens_end, sizeof(stored_state_size));
    memcpy(&stored_checksum, file.data() + checksum_offset, sizeof(stored_checksum));
    if (stored_state_size != checksum_offset - tokens_end - sizeof(uint64_t) ||
        stored_checksum != fnv1a_64(file.data(), checksum_offset)) {
        return "unreadable";
    }

    if (n_tokens != tokens.size() ||
        memcmp(file.data() + PROMPT_CACHE_HEADER, tokens.data(), n_tokens * sizeof(llama_token)) != 0) {
        return "token mismatch";
    }
    state_offset = tokens_end + sizeof(uint64_t);
    state_size = (size_t) stored_state_size;
    return nullptr;
}

// A cache file for the system prompt's `tokens`, with `state_size` bytes at `state_offset` left for
// the state; seal_prompt_cache adds the checksum once the state is in.
static std::vector<uint8_t> prompt_cache_layout(const llama_tokens &tokens, const size_t state_size,
                                                size_t &state_offset) {
    const uint32_t header[3] = {PROMPT_CACHE_MAGIC, PROMPT_CACHE_VERSION, (uint32_t) tokens.size()};
    const size_t tokens_end = PROMPT_CACHE_HEADER + tokens.size() * sizeof(llama_token);
    const uint64_t stored_state_size = state_size;
    std::vector<uint8_t> file(prompt_cache_size(tokens.size(), state_size));
    memcpy(file.data(), header, sizeof(header));
    memcpy(file.data() + PROMPT_CACHE_HEADER, tokens.data(), tokens.size() * sizeof(llama_token));
    memcpy(file.data() + tokens_end, &stored_state_size, sizeof(stored_state_size));
    state_offset = tokens_end + sizeof(uint64_t);
    return file;
}

static void seal_prompt_cache(std::vector<uint8_t> &file) {
    const size_t checksum_offset = file.size() - sizeof(uint64_t);
    const uint64_t checksum = fnv1a_64(file.data(), checksum_offset);
    memcpy(file.data() + checksum_offset, &checksum, sizeof(checksum));
}

// Writes `file` to <path>.tmp and renames that to `path`, so a reader never sees half a file, and
// removes the tmp file on any failure. Returns what failed, for the log, or an empty string. No
// fsync: a file the flash didn't finish fails the checksum, and is then written again.
static std::string write_prompt_cache(const std::string &path, const std::vector<uint8_t> &file) {
    const std::string tmp = path + ".tmp";
    const int fd = open(tmp.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (fd < 0) {
        return std::string("create: ") + strerror(errno);
    }
    std::string failure;
    size_t done = 0;
    while (failure.empty() && done < file.size()) {
        const ssize_t n = write(fd, file.data() + done, file.size() - done);
        if (n > 0) {
            done += (size_t) n;
        } else if (n == 0) {
            failure = "write: nothing written";
        } else if (errno != EINTR) {
            failure = std::string("write: ") + strerror(errno);
        }
    }
    if (close(fd) != 0 && failure.empty()) {
        failure = std::string("close: ") + strerror(errno);
    }
    if (failure.empty() && rename(tmp.c_str(), path.c_str()) != 0) {
        failure = std::string("rename: ") + strerror(errno);
    }
    if (!failure.empty()) {
        unlink(tmp.c_str());
    }
    return failure;
}

// Loads the system prompt's state from the cache file at `path` into sequence 0. Returns nullptr
// when it did, else the miss reason: read_prompt_cache's, "not restored" when llama.cpp didn't take
// the whole state or it doesn't end at the prompt's last position (where the user prompt goes on),
// or "out of memory". Never throws.
static const char *load_system_prompt(const std::string &path, const llama_tokens &tokens) {
    try {
        std::vector<uint8_t> file;
        size_t state_offset = 0;
        size_t state_size = 0;
        if (const char *miss = read_prompt_cache(path, tokens, file, state_offset, state_size)) {
            return miss;
        }
        if (llama_state_seq_set_data(g_context, file.data() + state_offset, state_size, 0) != state_size ||
            llama_memory_seq_pos_max(llama_get_memory(g_context), 0) != (llama_pos) tokens.size() - 1) {
            return "not restored";
        }
        return nullptr;
    } catch (const std::exception &) {
        return "out of memory";
    }
}

// Saves sequence 0's state, just encoded from the system prompt's `tokens`, to the cache file at
// `path`. A failure is logged and leaves no file behind. Never throws.
static void save_system_prompt(const std::string &path, const llama_tokens &tokens) {
    const int64_t t_start_us = ggml_time_us();
    std::string failure;
    size_t file_size = 0;
    try {
        const size_t state_size = llama_state_seq_get_size(g_context, 0);
        if (state_size == 0) {
            failure = "no state";
        } else if (prompt_cache_size(tokens.size(), state_size) > PROMPT_CACHE_MAX_SIZE) {
            failure = "too large";
        } else {
            size_t state_offset = 0;
            std::vector<uint8_t> file = prompt_cache_layout(tokens, state_size, state_offset);
            if (llama_state_seq_get_data(g_context, file.data() + state_offset, state_size, 0) != state_size) {
                failure = "state not copied";
            } else {
                seal_prompt_cache(file);
                failure = write_prompt_cache(path, file);
                file_size = file.size();
            }
        }
    } catch (const std::exception &) {
        failure = "out of memory";
    }
    if (!failure.empty()) {
        LOGw("%s: System prompt cache not saved (%s)", __func__, failure.c_str());
        return;
    }
    LOGi("%s: System prompt cached: %d tokens, %zu KB in %lld ms", __func__,
         (int) tokens.size(), file_size / 1024, (long long) ((ggml_time_us() - t_start_us) / 1000));
}

// The cache file's path from Kotlin, empty for none. JNI's Modified UTF-8 is plain UTF-8 for a path
// without NUL or characters outside the BMP, as the app's cache directory with a hex name is.
static std::string cache_path_from(JNIEnv *env, jstring jcache_path) {
    std::string path;
    if (jcache_path == nullptr) { return path; }
    const char *chars = env->GetStringUTFChars(jcache_path, nullptr);
    if (chars == nullptr) {
        // Out of memory: the prompt goes on without the cache, not with the pending error
        env->ExceptionClear();
        LOGw("%s: System prompt cache path not read; encoding without the cache", __func__);
        return path;
    }
    path = chars;
    env->ReleaseStringUTFChars(jcache_path, chars);
    return path;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_processSystemPrompt(
        JNIEnv *env,
        jobject /*unused*/,
        jbyteArray jsystem_prompt,
        jstring jcache_path
) {
    // The system prompt arrives as UTF-8 bytes (see utf8_from_bytes)
    const std::string incoming_system_prompt = utf8_from_bytes(env, jsystem_prompt);
    LOGd("%s: System prompt received (%zu bytes)", __func__, incoming_system_prompt.size());
    std::string formatted_system_prompt(incoming_system_prompt);

    // KV-cache reuse: if the system prompt is unchanged and already encoded,
    // discard only the previous user+assistant tokens (positions after
    // system_prompt_position) instead of wiping the entire KV cache.
    // This eliminates the redundant prefill cost on every inference call.
    //
    // Exception: M-RoPE models (e.g. Qwen3.5 hybrid SSM+attention) store recurrent
    // memory state at the last decoded position. Rewinding current_position to
    // system_prompt_position violates the M-RoPE constraint (requires X < Y),
    // causing llama_decode to fail. For these models we fall through to full reset.
    //
    // So does a windowed SWA cache (swa_full=off, Gemma 3 1B's and Gemma 4 E2B's since 0.6.1): it
    // reuses the cells that fall out of the window, so a reply longer than the window may have
    // overwritten the system prompt's, and the full reset then restores the prompt from its cache
    // file or encodes it again.
    const bool swa_windowed = !g_swa_full && llama_model_n_swa(g_model) > 0;
    if (system_prompt_position > 0 && incoming_system_prompt == g_encoded_system_prompt) {
        if (swa_windowed) {
            LOGi("%s: windowed SWA cache — full memory reset required (its cells may be reused)", __func__);
        } else if (!g_model_has_mrope) {
            LOGi("%s: System prompt unchanged — reusing KV cache, resetting to position %d",
                 __func__, system_prompt_position);
            if (current_position > system_prompt_position) {
                llama_memory_seq_rm(llama_get_memory(g_context), 0,
                                    system_prompt_position, current_position);
            }
            current_position = system_prompt_position;
            // Retain only the system message for correct chat template formatting
            if (!chat_msgs.empty() && chat_msgs.front().role == ROLE_SYSTEM) {
                chat_msgs.resize(1);
            }
            reset_short_term_states();
            return 0;
        } else {
            LOGi("%s: M-RoPE model — full memory reset required (cannot rewind recurrent state)", __func__);
        }
    }

    // System prompt changed (or first call): full KV cache reset + re-encode.
    reset_long_term_states();
    reset_short_term_states();
    g_encoded_system_prompt = incoming_system_prompt;

    // Format system prompt if applicable
    const bool has_chat_template = common_chat_templates_was_explicit(g_chat_templates.get());
    if (has_chat_template) {
        formatted_system_prompt = chat_add_and_format(ROLE_SYSTEM, incoming_system_prompt);
    }

    // The template may defer the system turn (empty result) when it cannot be rendered without a
    // user turn (e.g. Qwen3.5). In that case encode nothing now: the system turn is folded into
    // the first user prompt. Tokenizing an empty string with add_special would emit a stray BOS
    // at position 0, which the user turn would then duplicate.
    if (formatted_system_prompt.empty()) {
        LOGi("%s: system turn deferred to the first user prompt — nothing to encode", __func__);
        system_prompt_position = current_position = 0;
        return 0;
    }

    // Tokenize system prompt
    const auto system_tokens = common_tokenize(g_context, formatted_system_prompt,
                                               has_chat_template, has_chat_template);
    for (auto id: system_tokens) {
        LOGv("token -> %d", id);
    }

    // Handle context overflow
    const int max_batch_size = g_n_ctx - OVERFLOW_HEADROOM;
    if ((int) system_tokens.size() > max_batch_size) {
        LOGe("%s: System prompt too long for context! %d tokens, max: %d",
             __func__, (int) system_tokens.size(), max_batch_size);
        return 1;
    }

    // The system prompt cache (see read_prompt_cache); a deferred system turn returned above with
    // nothing to cache. Only for a memory that is a KV cache alone: M-RoPE models get a full reset
    // instead of the reuse above, and restoring a recurrent state, which recurrent and hybrid
    // models (Qwen3.5 among them) keep beside or instead of the KV cache, is untried. chat_msgs
    // holds the system message already, as after an encode.
    const bool cacheable = !g_model_has_mrope && !llama_model_is_recurrent(g_model) && !llama_model_is_hybrid(g_model);
    const std::string cache_path = cacheable ? cache_path_from(env, jcache_path) : std::string();
    if (!cache_path.empty()) {
        const int64_t t_start_us = ggml_time_us();
        const char *miss = load_system_prompt(cache_path, system_tokens);
        if (miss == nullptr) {
            system_prompt_position = current_position = (int) system_tokens.size();
            LOGi("%s: System prompt from cache: %d tokens in %lld ms", __func__,
                 (int) system_tokens.size(), (long long) ((ggml_time_us() - t_start_us) / 1000));
            return 0;
        }
        // Whatever a failed restore left in sequence 0 goes before the encode
        llama_memory_seq_rm(llama_get_memory(g_context), 0, -1, -1);
        LOGi("%s: System prompt cache miss (%s), encoding %d tokens", __func__, miss, (int) system_tokens.size());
    }

    // Decode system tokens in batches
    if (decode_tokens_in_batches(g_context, g_batch, system_tokens, current_position)) {
        LOGe("%s: llama_decode() failed!", __func__);
        return 2;
    }

    // Update position
    system_prompt_position = current_position = (int) system_tokens.size();

    if (!cache_path.empty()) {
        save_system_prompt(cache_path, system_tokens);
    }
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_updateSamplerConfig(
        JNIEnv * /*env*/,
        jobject /*unused*/,
        jfloat temperature,
        jint   top_k,
        jfloat top_p,
        jfloat min_p,
        jfloat repeat_penalty
) {
    // A grammar sampler of a reply in progress keeps its settings and its grammar state; the new
    // settings apply from the next reply.
    g_sampler_params = sampler_params(temperature, top_k, top_p, min_p, repeat_penalty);
    if (g_sampler) {
        common_sampler_free(g_sampler);
    }
    g_sampler = new_sampler(g_sampler_params);
    LOGi("Sampler updated: temp=%.2f topK=%d topP=%.2f minP=%.2f repeatPenalty=%.2f",
         temperature, top_k, top_p, min_p, repeat_penalty);
    return 0;
}

// Builds the grammar sampler for the coming reply. A grammar llama.cpp rejects is logged without its
// text (it may quote user content) and the reply is free text.
static void start_grammar(const std::string &grammar, const bool is_json_schema) {
    const char *kind = is_json_schema ? "JSON schema" : "GBNF";
    const grammar_status status = g_grammar.begin(g_model, g_sampler_params, grammar, is_json_schema);
    if (status == grammar_status::applied) {
        LOGi("%s: %s grammar applied (%zu bytes), without repeat penalties", __func__, kind, grammar.size());
    } else {
        LOGw("%s: %s grammar not applied (%s, %zu bytes); generating free text",
             __func__, kind, grammar_status_name(status), grammar.size());
    }
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_processUserPrompt(
        JNIEnv *env,
        jobject /*unused*/,
        jbyteArray juser_prompt,
        jint n_predict,
        jbyteArray jgrammar,
        jboolean jgrammar_is_json_schema
) {
    // Reset short-term states, and the grammar of a reply that was cancelled before it ended
    reset_short_term_states();
    g_grammar.end();

    // The user prompt and the grammar arrive as UTF-8 bytes (see utf8_from_bytes)
    const std::string user_prompt = utf8_from_bytes(env, juser_prompt);
    LOGd("%s: User prompt received (%zu bytes)", __func__, user_prompt.size());
    std::string formatted_user_prompt(user_prompt);

    // Grammar for this reply only; null means free text
    const bool has_grammar = jgrammar != nullptr;
    const std::string grammar = utf8_from_bytes(env, jgrammar);

    // Format user prompt if applicable
    const bool has_chat_template = common_chat_templates_was_explicit(g_chat_templates.get());
    if (has_chat_template) {
        formatted_user_prompt = chat_add_and_format(ROLE_USER, user_prompt);
    }

    // -------------------------------------------------------------------------
    // WORKAROUND: disable extended thinking for hybrid SSM+attention models
    // (currently Qwen3.5) that default to "thinking mode".
    //
    // Background: Qwen3.5 and similar hybrid models emit a <think>...</think>
    // reasoning block before every response by default. This block easily
    // consumes 400-800+ tokens of the output budget, leaving too few tokens
    // for the actual JSON payload and causing truncated or empty insights.
    //
    // How this works: by pre-filling the assistant turn with an *empty* think
    // block (<think>\n\n</think>\n\n), the model sees those tokens as already
    // generated and skips reasoning entirely, proceeding directly to the JSON
    // response. This is a token-level trick — the model genuinely does NOT
    // reason; it is not a UI filter.
    //
    // Why g_model_has_mrope is the right signal: only Qwen3.5-family models
    // use LLAMA_ROPE_TYPE_IMROPE (40), and those are exactly the models that
    // support / default to thinking mode. Standard attention-only models
    // (Gemma3 → NEOX=2, Llama → NORM=0) are unaffected.
    //
    // Note: Gemma 4 also has thinking mode but uses a different token format
    // (<|channel>thought ... <channel|>) and is handled in the block below.
    //
    // Upstream reference: llama.cpp --reasoning off / enable_thinking=false in
    // the Jinja chat template (models/templates/Qwen-Qwen3-0.6B.jinja:82-84).
    // -------------------------------------------------------------------------
    if (g_model_has_mrope && has_chat_template) {
        const std::string empty_think_prefix = "<think>\n\n</think>\n\n";
        formatted_user_prompt += empty_think_prefix;
        LOGi("%s: M-RoPE thinking model — pre-filled empty <think> block to suppress reasoning",
             __func__);
    }

    // For models with enable_thinking support (e.g. Gemma 4) the suppression is handled
    // transparently in chat_add_and_format via g_model_enable_thinking=false, which
    // makes the Jinja template append the thinking-suppression prefix automatically.
    // No additional step is needed here.

    // Decode formatted user prompts
    auto user_tokens = common_tokenize(g_context, formatted_user_prompt, has_chat_template, has_chat_template);
    for (auto id: user_tokens) {
        LOGv("token -> %d", id);
    }

    // Ensure user prompt doesn't exceed the context size by truncating if necessary.
    const int user_prompt_size = (int) user_tokens.size();
    const int max_batch_size = g_n_ctx - OVERFLOW_HEADROOM;
    if (user_prompt_size > max_batch_size) {
        const int skipped_tokens = user_prompt_size - max_batch_size;
        user_tokens.resize(max_batch_size);
        LOGw("%s: User prompt too long! Skipped %d tokens!", __func__, skipped_tokens);
    }

    // Decode user tokens in batches
    if (decode_tokens_in_batches(g_context, g_batch, user_tokens, current_position, true)) {
        LOGe("%s: llama_decode() failed!", __func__);
        return 2;
    }

    // Update position
    current_position += user_prompt_size;
    // stop_generation_position marks when token generation must stop.
    // It is current_position (end of user tokens) + the requested output budget.
    stop_generation_position = current_position + n_predict;

    if (has_grammar) {
        start_grammar(grammar, jgrammar_is_json_schema == JNI_TRUE);
    }
    return 0;
}

static bool is_valid_utf8(const char *string) {
    if (!string) { return true; }

    const auto *bytes = (const unsigned char *) string;
    int num;

    while (*bytes != 0x00) {
        if ((*bytes & 0x80) == 0x00) {
            // U+0000 to U+007F
            num = 1;
        } else if ((*bytes & 0xE0) == 0xC0) {
            // U+0080 to U+07FF
            num = 2;
        } else if ((*bytes & 0xF0) == 0xE0) {
            // U+0800 to U+FFFF
            num = 3;
        } else if ((*bytes & 0xF8) == 0xF0) {
            // U+10000 to U+10FFFF
            num = 4;
        } else {
            return false;
        }

        bytes += 1;
        for (int i = 1; i < num; ++i) {
            if ((*bytes & 0xC0) != 0x80) {
                return false;
            }
            bytes += 1;
        }
    }
    return true;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_generateNextToken(
        JNIEnv *env,
        jobject /*unused*/
) {
    // Infinite text generation via context shifting
    if (current_position >= g_n_ctx - OVERFLOW_HEADROOM) {
        LOGw("%s: Context full! Shifting...", __func__);
        shift_context();
    }

    // Stop if reaching the marked position
    if (current_position >= stop_generation_position) {
        // With a grammar this cuts the structured reply short: the output budget was too small.
        LOGw("%s: STOP: hitting stop position: %d%s", __func__, stop_generation_position,
             g_grammar.active() ? " (grammar unfinished)" : "");
        g_grammar.end();
        return nullptr;
    }

    // Sample next token. Under a grammar llama.cpp throws if a token breaks it; that message quotes
    // the output, so it isn't logged, and the reply ends instead of the process.
    common_sampler *sampler = g_grammar.sampler(g_sampler);
    llama_token new_token_id;
    try {
        new_token_id = common_sampler_sample(sampler, g_context, -1);
        common_sampler_accept(sampler, new_token_id, true);
    } catch (...) {
        LOGe("%s: sampling failed%s; ending the reply", __func__,
             g_grammar.active() ? " under the grammar" : "");
        g_grammar.end();
        return nullptr;
    }

    // Populate the batch with new token, then decode
    common_batch_clear(g_batch);
    common_batch_add(g_batch, new_token_id, current_position, {0}, true);
    if (llama_decode(g_context, g_batch) != 0) {
        LOGe("%s: llama_decode() failed for generated token", __func__);
        g_grammar.end();
        return nullptr;
    }

    // Update position
    current_position++;

    // Stop if next token is EOG. Under a grammar, EOG is only sampled once the grammar is complete.
    if (llama_vocab_is_eog(llama_model_get_vocab(g_model), new_token_id)) {
        LOGd("id: %d,\tIS EOG!\nSTOP.", new_token_id);
        g_grammar.end();
        chat_add_and_format(ROLE_ASSISTANT, assistant_ss.str());
        return nullptr;
    }

    // If not EOG, convert to text
    auto new_token_chars = common_token_to_piece(g_context, new_token_id);
    cached_token_chars += new_token_chars;

    // Create and return a valid UTF-8 Java string
    jstring result = nullptr;
    if (is_valid_utf8(cached_token_chars.c_str())) {
        result = env->NewStringUTF(cached_token_chars.c_str());
        LOGv("id: %d, emitted %zu bytes", new_token_id, cached_token_chars.size());

        assistant_ss << cached_token_chars;
        cached_token_chars.clear();
    } else {
        LOGv("id: %d,\tappend to cache", new_token_id);
        result = env->NewStringUTF("");
    }
    return result;
}


extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_unload(JNIEnv * /*unused*/, jobject /*unused*/) {
    // Reset long-term & short-term states
    reset_long_term_states();
    reset_short_term_states();

    // Free up resources
    g_grammar.end();
    common_sampler_free(g_sampler);
    g_sampler = nullptr;
    g_chat_templates.reset();
    llama_batch_free(g_batch);
    llama_free(g_context);
    llama_model_free(g_model);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_shutdown(JNIEnv *, jobject /*unused*/) {
    llama_backend_free();
}
