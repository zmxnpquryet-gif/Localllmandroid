// JNI bridge between the app's Kotlin LlamaContext wrapper and upstream llama.cpp.
//
// Design goals:
//  - one registry entry per loaded model, addressed by an opaque 64-bit id, so a
//    native call can never dereference a session that another thread is freeing;
//  - every generation returns an explicit status instead of failing silently;
//  - tokens cross the bridge as complete UTF-8 byte sequences, preserving 4-byte
//    code points and partial multi-byte token pieces.
#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#define TAG "LlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

// Mirrored by LlamaContext.CompletionStatus. Keep both sides in sync.
enum CompletionStatus : jint {
    STATUS_OK = 0,
    STATUS_NOT_READY = 1,
    STATUS_BUSY = 2,
    STATUS_PROMPT_FAILED = 3,
    STATUS_DECODE_FAILED = 4,
    STATUS_CONTEXT_OVERFLOW = 5,
    STATUS_MULTIMODAL_FAILED = 6,
    STATUS_OUT_OF_MEMORY = 7,
};

struct LlamaSession {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    mtmd_context * mtmd = nullptr;
    const llama_vocab * vocab = nullptr;
    int n_ctx = 0;
    int n_batch = 0;
    std::atomic<bool> stop{false};
    std::atomic<bool> predicting{false};
    std::atomic<bool> closing{false};
    std::mutex mutex;

    void release() {
        if (mtmd != nullptr) {
            mtmd_free(mtmd);
            mtmd = nullptr;
        }
        if (ctx != nullptr) {
            llama_free(ctx);
            ctx = nullptr;
        }
        if (model != nullptr) {
            llama_model_free(model);
            model = nullptr;
        }
        vocab = nullptr;
    }

    ~LlamaSession() { release(); }
};

std::mutex g_registry_mutex;
std::unordered_map<jlong, std::shared_ptr<LlamaSession>> g_sessions;
std::atomic<jlong> g_next_session_id{1};

// Cached JNI identifier for LlamaContext.emitTokenBytes([B)V.
jmethodID g_emit_token_method = nullptr;

jlong register_session(std::unique_ptr<LlamaSession> session) {
    const jlong id = g_next_session_id.fetch_add(1);
    auto shared = std::shared_ptr<LlamaSession>(std::move(session));
    std::lock_guard<std::mutex> lock(g_registry_mutex);
    g_sessions.emplace(id, std::move(shared));
    return id;
}

std::shared_ptr<LlamaSession> acquire_session(jlong id) {
    std::lock_guard<std::mutex> lock(g_registry_mutex);
    const auto it = g_sessions.find(id);
    return it == g_sessions.end() ? nullptr : it->second;
}

void unregister_session(jlong id) {
    std::shared_ptr<LlamaSession> session;
    {
        std::lock_guard<std::mutex> lock(g_registry_mutex);
        const auto it = g_sessions.find(id);
        if (it == g_sessions.end()) return;
        session = std::move(it->second);
        g_sessions.erase(it);
    }

    // Ask a running generation to leave its decode loop, then wait for it to
    // drop the session mutex before the shared_ptr releases native resources.
    session->closing.store(true);
    session->stop.store(true);
    std::lock_guard<std::mutex> lock(session->mutex);
}

void log_callback(enum ggml_log_level level, const char * text, void * /* user_data */) {
    if (text == nullptr) return;
    int priority = ANDROID_LOG_DEBUG;
    if (level == GGML_LOG_LEVEL_ERROR) priority = ANDROID_LOG_ERROR;
    else if (level == GGML_LOG_LEVEL_WARN) priority = ANDROID_LOG_WARN;
    else if (level == GGML_LOG_LEVEL_INFO) priority = ANDROID_LOG_INFO;
    __android_log_write(priority, "llama.cpp", text);
}

std::string jstring_to_utf8(JNIEnv * env, jstring value) {
    if (value == nullptr) return {};
    const jsize length = env->GetStringLength(value);
    const jchar * chars = env->GetStringChars(value, nullptr);
    if (chars == nullptr) return {};

    std::string out;
    out.reserve(static_cast<size_t>(length) * 3);
    for (jsize i = 0; i < length; ++i) {
        uint32_t cp = chars[i];
        if (cp >= 0xD800 && cp <= 0xDBFF) {
            if (i + 1 < length) {
                const uint32_t low = chars[i + 1];
                if (low >= 0xDC00 && low <= 0xDFFF) {
                    cp = 0x10000 + ((cp - 0xD800) << 10) + (low - 0xDC00);
                    ++i;
                } else {
                    cp = 0xFFFD;
                }
            } else {
                cp = 0xFFFD;
            }
        } else if (cp >= 0xDC00 && cp <= 0xDFFF) {
            cp = 0xFFFD;
        }

        if (cp < 0x80) {
            out.push_back(static_cast<char>(cp));
        } else if (cp < 0x800) {
            out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else if (cp < 0x10000) {
            out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else {
            out.push_back(static_cast<char>(0xF0 | (cp >> 18)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        }
    }
    env->ReleaseStringChars(value, chars);
    return out;
}

size_t utf8_sequence_length(unsigned char c) {
    if (c >= 0xC2 && c <= 0xDF) return 2;
    if (c >= 0xE0 && c <= 0xEF) return 3;
    if (c >= 0xF0 && c <= 0xF4) return 4;
    return 0;
}

// Number of bytes at the end of `text` that form an incomplete UTF-8 sequence.
size_t utf8_incomplete_suffix(const std::string & text) {
    const size_t n = text.size();
    for (size_t back = 1; back <= 3 && back <= n; ++back) {
        const unsigned char c = static_cast<unsigned char>(text[n - back]);
        if ((c & 0xC0) == 0x80) continue;
        const size_t seq = utf8_sequence_length(c);
        if (seq == 0 || back >= seq) return 0;
        return back;
    }
    return 0;
}

void emit_bytes(JNIEnv * env, jobject target, const std::string & chunk) {
    if (target == nullptr || chunk.empty()) return;
    jmethodID method = g_emit_token_method;
    if (method == nullptr) {
        jclass cls = env->GetObjectClass(target);
        if (cls == nullptr) return;
        method = env->GetMethodID(cls, "emitTokenBytes", "([B)V");
        env->DeleteLocalRef(cls);
        if (method == nullptr) {
            env->ExceptionDescribe();
            env->ExceptionClear();
            return;
        }
        g_emit_token_method = method;
    }

    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(chunk.size()));
    if (bytes == nullptr) {
        env->ExceptionClear();
        return;
    }
    env->SetByteArrayRegion(
        bytes, 0, static_cast<jsize>(chunk.size()),
        reinterpret_cast<const jbyte *>(chunk.data()));
    env->CallVoidMethod(target, method, bytes);
    env->DeleteLocalRef(bytes);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
}

std::string token_to_piece(const llama_vocab * vocab, llama_token token) {
    char buffer[256];
    int32_t written = llama_token_to_piece(vocab, token, buffer, sizeof(buffer), 0, false);
    if (written > 0 && static_cast<size_t>(written) <= sizeof(buffer)) {
        return std::string(buffer, static_cast<size_t>(written));
    }
    if (written < 0) {
        std::vector<char> larger(static_cast<size_t>(-written));
        written = llama_token_to_piece(vocab, token, larger.data(), static_cast<int32_t>(larger.size()), 0, false);
        if (written > 0) return std::string(larger.data(), static_cast<size_t>(written));
    }
    return {};
}

std::vector<llama_token> tokenize(const llama_vocab * vocab, const std::string & text, bool add_special) {
    int32_t needed = llama_tokenize(
        vocab, text.data(), static_cast<int32_t>(text.size()),
        nullptr, 0, add_special, true);
    if (needed < 0) needed = -needed;
    if (needed == 0) return {};

    std::vector<llama_token> tokens(static_cast<size_t>(needed));
    const int32_t count = llama_tokenize(
        vocab, text.data(), static_cast<int32_t>(text.size()),
        tokens.data(), needed, add_special, true);
    if (count <= 0) return {};
    tokens.resize(static_cast<size_t>(count));
    return tokens;
}

bool decode_tokens(
        llama_context * ctx,
        int n_batch,
        const std::vector<llama_token> & tokens,
        int n_past) {
    if (tokens.empty()) return true;
    llama_batch batch = llama_batch_init(n_batch, 0, 1);
    if (batch.token == nullptr) return false;

    bool ok = true;
    for (size_t offset = 0; offset < tokens.size(); offset += static_cast<size_t>(n_batch)) {
        const int count = static_cast<int>(std::min<size_t>(n_batch, tokens.size() - offset));
        batch.n_tokens = count;
        for (int i = 0; i < count; ++i) {
            batch.token[i] = tokens[offset + i];
            batch.pos[i] = n_past + static_cast<int>(offset) + i;
            batch.n_seq_id[i] = 1;
            batch.seq_id[i][0] = 0;
            batch.logits[i] = (offset + i + 1 == tokens.size()) ? 1 : 0;
        }
        if (llama_decode(ctx, batch) != 0) {
            ok = false;
            break;
        }
    }
    llama_batch_free(batch);
    return ok;
}

bool eval_multimodal(
        LlamaSession * session,
        const std::string & prompt,
        const std::string & image_path,
        int & n_past) {
    if (session->mtmd == nullptr) {
        LOGE("multimodal prompt requested without an mmproj context");
        return false;
    }

    std::string full_prompt = prompt;
    const char * marker = mtmd_default_marker();
    if (marker != nullptr && full_prompt.find(marker) == std::string::npos) {
        // Match mtmd-cli: most models need the media marker before the prompt.
        full_prompt = std::string(marker) + full_prompt;
    }

    const mtmd_helper_init_opt opt = mtmd_helper_init_opt_default();
    mtmd_helper_bitmap_wrapper loaded = mtmd_helper_bitmap_init_from_file(
        session->mtmd, image_path.c_str(), false, opt);
    if (loaded.video_ctx != nullptr) {
        mtmd_helper_video_free(loaded.video_ctx);
        loaded.video_ctx = nullptr;
    }
    if (loaded.bitmap == nullptr) {
        LOGE("failed to load image: %s", image_path.c_str());
        return false;
    }

    mtmd_input_chunks * chunks = mtmd_input_chunks_init();
    if (chunks == nullptr) {
        mtmd_bitmap_free(loaded.bitmap);
        return false;
    }

    mtmd_input_text input{};
    input.text = full_prompt.c_str();
    input.text_len = full_prompt.size();
    input.add_special = true;
    input.parse_special = true;
    const mtmd_bitmap * bitmaps[1] = { loaded.bitmap };

    int result = mtmd_tokenize(session->mtmd, chunks, &input, bitmaps, 1);
    if (result == 0) {
        llama_pos new_n_past = 0;
        result = mtmd_helper_eval_chunks(
            session->mtmd, session->ctx, chunks,
            0, 0, session->n_batch, true, &new_n_past);
        if (result == 0) n_past = static_cast<int>(new_n_past);
    }

    mtmd_input_chunks_free(chunks);
    mtmd_bitmap_free(loaded.bitmap);
    if (result != 0) {
        LOGE("multimodal chunk evaluation failed: %d", result);
        return false;
    }
    return true;
}

} // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM * vm, void * /* reserved */) {
    llama_log_set(log_callback, nullptr);
    llama_backend_init();

    JNIEnv * env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_OK && env != nullptr) {
        jclass cls = env->FindClass("com/localllm/android/engine/llama/LlamaContext");
        if (cls != nullptr) {
            g_emit_token_method = env->GetMethodID(cls, "emitTokenBytes", "([B)V");
            env->DeleteLocalRef(cls);
            if (env->ExceptionCheck()) env->ExceptionClear();
        }
    }

    LOGI("llama.cpp backend initialized (%s)", llama_print_system_info());
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL JNI_OnUnload(JavaVM * /* vm */, void * /* reserved */) {
    std::unordered_map<jlong, std::shared_ptr<LlamaSession>> drained;
    {
        std::lock_guard<std::mutex> lock(g_registry_mutex);
        drained.swap(g_sessions);
    }
    drained.clear();
    llama_backend_free();
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_localllm_android_engine_llama_LlamaContext_nativeCreate(
        JNIEnv * env, jobject /* thiz */,
        jstring j_model_path,
        jstring j_mmproj_path,
        jint n_ctx,
        jint n_batch,
        jint n_threads,
        jint n_gpu_layers,
        jboolean use_mmap,
        jboolean use_mlock,
        jboolean vocab_only) {
    const std::string model_path = jstring_to_utf8(env, j_model_path);
    const std::string mmproj_path = jstring_to_utf8(env, j_mmproj_path);
    if (model_path.empty()) {
        LOGE("model path is empty");
        return 0;
    }

    auto session = std::make_unique<LlamaSession>();

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = n_gpu_layers;
    model_params.vocab_only = vocab_only != JNI_FALSE;
    if (use_mmap != JNI_FALSE && use_mlock != JNI_FALSE) {
        model_params.load_mode = LLAMA_LOAD_MODE_MMAP_MLOCK;
    } else if (use_mmap != JNI_FALSE) {
        model_params.load_mode = LLAMA_LOAD_MODE_MMAP;
    } else if (use_mlock != JNI_FALSE) {
        model_params.load_mode = LLAMA_LOAD_MODE_MLOCK;
    } else {
        model_params.load_mode = LLAMA_LOAD_MODE_NONE;
    }

    LOGI("loading model: %s (n_ctx=%d, n_batch=%d, threads=%d, gpu_layers=%d)",
         model_path.c_str(), n_ctx, n_batch, n_threads, n_gpu_layers);
    session->model = llama_model_load_from_file(model_path.c_str(), model_params);
    if (session->model == nullptr) {
        LOGE("failed to load model: %s", model_path.c_str());
        return 0;
    }
    session->vocab = llama_model_get_vocab(session->model);

    llama_context_params context_params = llama_context_default_params();
    const uint32_t requested_ctx = static_cast<uint32_t>(std::max(0, n_ctx));
    const uint32_t requested_batch = static_cast<uint32_t>(std::max(1, n_batch));
    context_params.n_ctx = requested_ctx;
    context_params.n_batch = requested_batch;
    context_params.n_ubatch = std::min<uint32_t>(requested_batch, context_params.n_ubatch);
    context_params.n_threads = std::max(1, n_threads);
    context_params.n_threads_batch = std::max(1, n_threads);
    context_params.embeddings = false;

    session->ctx = llama_init_from_model(session->model, context_params);
    if (session->ctx == nullptr) {
        LOGE("failed to create llama context");
        return 0;
    }
    session->n_ctx = static_cast<int>(llama_n_ctx(session->ctx));
    session->n_batch = static_cast<int>(llama_n_batch(session->ctx));

    if (!mmproj_path.empty()) {
        mtmd_context_params mtmd_params = mtmd_context_params_default();
        mtmd_params.use_gpu = false;
        mtmd_params.n_threads = std::max(1, n_threads);
        mtmd_params.print_timings = false;
        session->mtmd = mtmd_init_from_file(mmproj_path.c_str(), session->model, mtmd_params);
        if (session->mtmd == nullptr) {
            LOGE("failed to initialize mmproj: %s", mmproj_path.c_str());
            return 0;
        }
        LOGI("multimodal projector loaded: %s", mmproj_path.c_str());
    }

    LOGI("model ready: n_ctx=%d, n_batch=%d", session->n_ctx, session->n_batch);
    return register_session(std::move(session));
}

extern "C" JNIEXPORT void JNICALL
Java_com_localllm_android_engine_llama_LlamaContext_nativeFree(
        JNIEnv * /* env */, jobject /* thiz */, jlong handle) {
    unregister_session(handle);
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_localllm_android_engine_llama_LlamaContext_nativeTokenize(
        JNIEnv * env, jobject /* thiz */, jlong handle, jstring j_text) {
    const auto session = acquire_session(handle);
    if (session == nullptr || session->vocab == nullptr) {
        return env->NewIntArray(0);
    }
    const std::vector<llama_token> tokens = tokenize(session->vocab, jstring_to_utf8(env, j_text), false);
    jintArray result = env->NewIntArray(static_cast<jsize>(tokens.size()));
    if (result != nullptr && !tokens.empty()) {
        env->SetIntArrayRegion(
            result, 0, static_cast<jsize>(tokens.size()),
            reinterpret_cast<const jint *>(tokens.data()));
    }
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_localllm_android_engine_llama_LlamaContext_nativeCompletion(
        JNIEnv * env, jobject /* thiz */,
        jlong handle,
        jstring j_prompt,
        jstring j_image_path,
        jfloat temperature,
        jint top_k,
        jfloat top_p,
        jfloat repetition_penalty,
        jint n_predict,
        jobject callback_target) {
    const auto session = acquire_session(handle);
    if (session == nullptr || session->ctx == nullptr || session->vocab == nullptr) {
        LOGE("completion requested without a loaded context");
        return STATUS_NOT_READY;
    }
    if (session->closing.load()) return STATUS_NOT_READY;

    std::unique_lock<std::mutex> lock(session->mutex, std::try_to_lock);
    if (!lock.owns_lock()) {
        LOGE("completion requested while another completion owns the context");
        return STATUS_BUSY;
    }
    if (session->closing.load()) return STATUS_NOT_READY;

    session->stop.store(false);
    session->predicting.store(true);
    struct PredictingGuard {
        LlamaSession * session;
        ~PredictingGuard() { session->predicting.store(false); }
    } guard{session.get()};

    const std::string prompt = jstring_to_utf8(env, j_prompt);
    const std::string image_path = jstring_to_utf8(env, j_image_path);
    if (prompt.empty()) return STATUS_PROMPT_FAILED;

    llama_memory_clear(llama_get_memory(session->ctx), true);

    llama_sampler_chain_params sampler_params = llama_sampler_chain_default_params();
    sampler_params.no_perf = true;
    llama_sampler * sampler = llama_sampler_chain_init(sampler_params);
    if (sampler == nullptr) return STATUS_OUT_OF_MEMORY;

    const int32_t n_vocab = llama_vocab_n_tokens(session->vocab);
    if (repetition_penalty > 0.0f && repetition_penalty != 1.0f) {
        llama_sampler_chain_add(sampler, llama_sampler_init_penalties(
            n_vocab, 64, repetition_penalty, 0.0f, 0.0f));
    }
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(std::max(1, top_k)));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(
        top_p > 0.0f ? top_p : 0.95f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(
        temperature > 0.0f ? temperature : 0.8f));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    int n_past = 0;
    jint status = STATUS_OK;
    if (!image_path.empty()) {
        if (!eval_multimodal(session.get(), prompt, image_path, n_past)) {
            status = STATUS_MULTIMODAL_FAILED;
        }
    } else {
        const std::vector<llama_token> tokens = tokenize(session->vocab, prompt, true);
        if (tokens.empty()) {
            status = STATUS_PROMPT_FAILED;
        } else if (static_cast<int>(tokens.size()) >= session->n_ctx) {
            status = STATUS_CONTEXT_OVERFLOW;
        } else if (!decode_tokens(session->ctx, session->n_batch, tokens, 0)) {
            status = STATUS_DECODE_FAILED;
        } else {
            n_past = static_cast<int>(tokens.size());
        }
    }

    if (status == STATUS_OK && n_past >= session->n_ctx) {
        status = STATUS_CONTEXT_OVERFLOW;
    }

    std::string pending;
    size_t generated = 0;
    if (status == STATUS_OK) {
        const int available = session->n_ctx - n_past;
        const int max_predict = n_predict > 0 ? std::min(n_predict, available) : available;
        const size_t max_new = static_cast<size_t>(std::max(0, max_predict));

        llama_batch batch = llama_batch_init(1, 0, 1);
        if (batch.token == nullptr) {
            status = STATUS_OUT_OF_MEMORY;
        } else {
            while (generated < max_new) {
                if (session->stop.load() || session->closing.load()) break;

                const llama_token token = llama_sampler_sample(sampler, session->ctx, -1);
                if (token < 0) {
                    status = STATUS_DECODE_FAILED;
                    break;
                }
                if (llama_vocab_is_eog(session->vocab, token)) break;

                pending += token_to_piece(session->vocab, token);
                const size_t hold = utf8_incomplete_suffix(pending);
                const size_t ready = pending.size() - hold;
                if (ready > 0) {
                    emit_bytes(env, callback_target, pending.substr(0, ready));
                    pending.erase(0, ready);
                }

                batch.n_tokens = 1;
                batch.token[0] = token;
                batch.pos[0] = n_past;
                batch.n_seq_id[0] = 1;
                batch.seq_id[0][0] = 0;
                batch.logits[0] = 1;
                if (llama_decode(session->ctx, batch) != 0) {
                    LOGE("decode failed while generating token %zu", generated);
                    status = STATUS_DECODE_FAILED;
                    break;
                }
                ++n_past;
                ++generated;
            }
            llama_batch_free(batch);
        }
    }

    if (!pending.empty()) {
        emit_bytes(env, callback_target, pending);
    }

    llama_sampler_free(sampler);
    LOGI("completion finished: status=%d, %zu token(s), n_past=%d, stopped=%s",
         status, generated, n_past, session->stop.load() ? "true" : "false");
    return status;
}

extern "C" JNIEXPORT void JNICALL
Java_com_localllm_android_engine_llama_LlamaContext_nativeStop(
        JNIEnv * /* env */, jobject /* thiz */, jlong handle) {
    const auto session = acquire_session(handle);
    if (session != nullptr) session->stop.store(true);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_localllm_android_engine_llama_LlamaContext_nativeIsPredicting(
        JNIEnv * /* env */, jobject /* thiz */, jlong handle) {
    const auto session = acquire_session(handle);
    return (session != nullptr && session->predicting.load()) ? JNI_TRUE : JNI_FALSE;
}
