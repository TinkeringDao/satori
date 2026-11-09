// The reader's JNI: the external functions of com.satori.reader.ReaderNative (ReaderNative.kt),
// each one call of satori::reader::Reader (reader_engine.h). A handle is a Reader allocated by
// create() and freed by release(); ReaderEngine.kt and ReaderTokenizer.kt guarantee that no call
// on a handle runs during or after its release.
//
// Every function returns a status (reader_status.h) or, for pieces(), writes one, and none lets a
// C++ exception out. Text crosses as UTF-8 byte arrays in both directions, never as jstring:
// GetStringUTFChars and NewStringUTF speak modified UTF-8, and CheckJNI (on in debug builds)
// aborts on a 4-byte character in NewStringUTF. Arrays are copied with Get/Set*ArrayRegion, which
// the VM bounds-checks, and no JNI call follows one that left an exception pending.

#include <jni.h>

#include <cstdint>
#include <new>
#include <string>
#include <vector>

#include "reader_engine.h"
#include "reader_status.h"

using satori::reader::Dims;
using satori::reader::Options;
using satori::reader::Reader;
using satori::reader::Status;
using satori::reader::ToInt;
using satori::reader::Translation;

namespace {

// The layout of load()'s options array, ReaderOptions.toIntArray() in ReaderEngine.kt.
enum OptionIndex : int {
    kThreads = 0,
    kPrepackEncoder,
    kPrepackDecoder,
    kPrepackDecoderWithPast,
    kCpuArena,
    kShrinkArena,
    kMaxSourcePositions,
    kMaxTargetPositions,
    kSourceVocab,
    kDecoderStartId,
    kEosId,
    kPadId,
    kOptionCount,
};

Reader* From(jlong handle) { return reinterpret_cast<Reader*>(static_cast<intptr_t>(handle)); }

// The bytes of a Java byte[]; false for null or a failed copy.
bool Bytes(JNIEnv* env, jbyteArray array, std::string* out) {
    if (array == nullptr) {
        return false;
    }
    const jsize size = env->GetArrayLength(array);
    out->assign(static_cast<size_t>(size), '\0');
    if (size > 0) {
        env->GetByteArrayRegion(array, 0, size, reinterpret_cast<jbyte*>(&(*out)[0]));
    }
    return !env->ExceptionCheck();
}

// A new byte[] holding `bytes`, or null with an OutOfMemoryError pending.
jbyteArray NewBytes(JNIEnv* env, const std::string& bytes) {
    const auto size = static_cast<jsize>(bytes.size());
    jbyteArray array = env->NewByteArray(size);
    if (array != nullptr && size > 0) {
        env->SetByteArrayRegion(array, 0, size, reinterpret_cast<const jbyte*>(bytes.data()));
    }
    return array;
}

template <typename Call>
jint Guarded(Call&& call) {
    try {
        return call();
    } catch (const std::bad_alloc&) {
        return ToInt(Status::kOutOfMemory);
    } catch (...) {
        return ToInt(Status::kInternal);
    }
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_com_satori_reader_ReaderNative_create(JNIEnv* /*env*/, jclass /*clazz*/) {
    try {
        return static_cast<jlong>(reinterpret_cast<intptr_t>(new Reader()));
    } catch (...) {
        return 0;
    }
}

JNIEXPORT jint JNICALL Java_com_satori_reader_ReaderNative_load(JNIEnv* env, jclass /*clazz*/, jlong handle,
                                                                jbyteArray encoder, jbyteArray decoder,
                                                                jbyteArray decoder_with_past, jintArray options) {
    return Guarded([&]() -> jint {
        Reader* reader = From(handle);
        if (reader == nullptr) {
            return ToInt(Status::kNotLoaded);
        }
        std::string encoder_path;
        std::string decoder_path;
        std::string past_path;
        if (!Bytes(env, encoder, &encoder_path) || !Bytes(env, decoder, &decoder_path) ||
            !Bytes(env, decoder_with_past, &past_path) || options == nullptr ||
            env->GetArrayLength(options) != kOptionCount) {
            return ToInt(Status::kBadInput);
        }
        jint values[kOptionCount] = {};
        env->GetIntArrayRegion(options, 0, kOptionCount, values);
        if (env->ExceptionCheck()) {
            return ToInt(Status::kBadInput);
        }
        Options parsed;
        parsed.threads = values[kThreads];
        parsed.prepack_encoder = values[kPrepackEncoder] != 0;
        parsed.prepack_decoder = values[kPrepackDecoder] != 0;
        parsed.prepack_decoder_with_past = values[kPrepackDecoderWithPast] != 0;
        parsed.cpu_arena = values[kCpuArena] != 0;
        parsed.shrink_arena = values[kShrinkArena] != 0;
        parsed.max_source_positions = values[kMaxSourcePositions];
        parsed.max_target_positions = values[kMaxTargetPositions];
        parsed.source_vocab = values[kSourceVocab];
        parsed.decoder_start_id = values[kDecoderStartId];
        parsed.eos_id = values[kEosId];
        parsed.pad_id = values[kPadId];
        return ToInt(reader->Load(encoder_path, decoder_path, past_path, parsed));
    });
}

JNIEXPORT jint JNICALL Java_com_satori_reader_ReaderNative_loadTokenizer(JNIEnv* env, jclass /*clazz*/, jlong handle,
                                                                         jbyteArray model) {
    return Guarded([&]() -> jint {
        Reader* reader = From(handle);
        if (reader == nullptr) {
            return ToInt(Status::kNotLoaded);
        }
        std::string path;
        if (!Bytes(env, model, &path)) {
            return ToInt(Status::kBadInput);
        }
        return ToInt(reader->LoadTokenizer(path));
    });
}

JNIEXPORT jobjectArray JNICALL Java_com_satori_reader_ReaderNative_pieces(JNIEnv* env, jclass /*clazz*/, jlong handle,
                                                                          jbyteArray text, jintArray status_out) {
    if (status_out == nullptr || env->GetArrayLength(status_out) < 1) {
        return nullptr;
    }
    const auto report = [&](Status status) {
        const jint value = ToInt(status);
        env->SetIntArrayRegion(status_out, 0, 1, &value);
    };
    try {
        Reader* reader = From(handle);
        if (reader == nullptr) {
            report(Status::kNotLoaded);
            return nullptr;
        }
        std::string utf8;
        if (!Bytes(env, text, &utf8)) {
            if (!env->ExceptionCheck()) {
                report(Status::kBadInput);
            }
            return nullptr;
        }
        std::vector<std::string> pieces;
        const Status status = reader->Pieces(utf8, &pieces);
        if (status != Status::kOk) {
            report(status);
            return nullptr;
        }
        // Reported before the arrays exist: a failure to allocate them leaves an
        // OutOfMemoryError pending, which Kotlin sees instead of any status.
        report(Status::kOk);
        jclass bytes_class = env->FindClass("[B");
        if (bytes_class == nullptr) {
            return nullptr;
        }
        jobjectArray array = env->NewObjectArray(static_cast<jsize>(pieces.size()), bytes_class, nullptr);
        env->DeleteLocalRef(bytes_class);
        if (array == nullptr) {
            return nullptr;
        }
        for (size_t i = 0; i < pieces.size(); ++i) {
            jbyteArray piece = NewBytes(env, pieces[i]);
            if (piece == nullptr) {
                return nullptr;
            }
            env->SetObjectArrayElement(array, static_cast<jsize>(i), piece);
            env->DeleteLocalRef(piece);
        }
        return array;
    } catch (const std::bad_alloc&) {
        if (!env->ExceptionCheck()) {
            report(Status::kOutOfMemory);
        }
        return nullptr;
    } catch (...) {
        if (!env->ExceptionCheck()) {
            report(Status::kInternal);
        }
        return nullptr;
    }
}

JNIEXPORT jint JNICALL Java_com_satori_reader_ReaderNative_translate(JNIEnv* env, jclass /*clazz*/, jlong handle,
                                                                     jintArray source_ids, jint beam,
                                                                     jint max_new_tokens, jint min_tokens,
                                                                     jintArray out_ids, jintArray out_counts,
                                                                     jdoubleArray out_score) {
    return Guarded([&]() -> jint {
        Reader* reader = From(handle);
        if (reader == nullptr) {
            return ToInt(Status::kNotLoaded);
        }
        if (source_ids == nullptr || out_ids == nullptr || out_counts == nullptr || out_score == nullptr ||
            env->GetArrayLength(out_counts) < 3 || env->GetArrayLength(out_score) < 1) {
            return ToInt(Status::kBadInput);
        }
        const jsize count = env->GetArrayLength(source_ids);
        std::vector<jint> raw(static_cast<size_t>(count));
        if (count > 0) {
            env->GetIntArrayRegion(source_ids, 0, count, raw.data());
            if (env->ExceptionCheck()) {
                return ToInt(Status::kBadInput);
            }
        }
        const std::vector<int32_t> ids(raw.begin(), raw.end());
        Translation translation;
        const Status status = reader->Translate(ids.data(), ids.size(), static_cast<int>(beam),
                                                static_cast<int>(max_new_tokens), static_cast<int>(min_tokens), &translation);
        if (status != Status::kOk) {
            return ToInt(status);
        }
        if (translation.ids.size() > static_cast<size_t>(env->GetArrayLength(out_ids))) {
            return ToInt(Status::kInternal);
        }
        const std::vector<jint> result(translation.ids.begin(), translation.ids.end());
        if (!result.empty()) {
            env->SetIntArrayRegion(out_ids, 0, static_cast<jsize>(result.size()), result.data());
        }
        const jint counts[3] = {static_cast<jint>(result.size()), translation.capped ? 1 : 0, translation.steps};
        env->SetIntArrayRegion(out_counts, 0, 3, counts);
        const jdouble score = translation.mean_log_prob;
        env->SetDoubleArrayRegion(out_score, 0, 1, &score);
        return env->ExceptionCheck() ? ToInt(Status::kInternal) : ToInt(Status::kOk);
    });
}

JNIEXPORT jintArray JNICALL Java_com_satori_reader_ReaderNative_dims(JNIEnv* env, jclass /*clazz*/, jlong handle) {
    Reader* reader = From(handle);
    if (reader == nullptr) {
        return nullptr;
    }
    const Dims dims = reader->dims();
    const jint values[3] = {dims.layers, dims.heads, dims.head_dim};
    jintArray array = env->NewIntArray(3);
    if (array != nullptr) {
        env->SetIntArrayRegion(array, 0, 3, values);
    }
    return array;
}

JNIEXPORT void JNICALL Java_com_satori_reader_ReaderNative_cancel(JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
    Reader* reader = From(handle);
    if (reader != nullptr) {
        reader->Cancel();
    }
}

JNIEXPORT jbyteArray JNICALL Java_com_satori_reader_ReaderNative_lastError(JNIEnv* env, jclass /*clazz*/,
                                                                           jlong handle) {
    Reader* reader = From(handle);
    if (reader == nullptr) {
        return nullptr;
    }
    try {
        return NewBytes(env, reader->last_error());
    } catch (...) {
        return nullptr;
    }
}

JNIEXPORT void JNICALL Java_com_satori_reader_ReaderNative_release(JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
    delete From(handle);
}

}  // extern "C"
