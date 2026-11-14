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
jmethodID g_emit_audio_method = nullptr;

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

void emit_audio_pcm16(JNIEnv * env, jobject target, const float * samples, size_t count) {
    if (target == nullptr || samples == nullptr || count == 0) return;
    jmethodID method = g_emit_audio_method;
    if (method == nullptr) {
        jclass cls = env->GetObjectClass(target);
        if (cls == nullptr) return;
        method = env->GetMethodID(cls, "emitAudioPcm16", "([B)V");
        env->DeleteLocalRef(cls);
        if (method == nullptr) {
            env->ExceptionDescribe();
            env->ExceptionClear();
            return;
        }
        g_emit_audio_method = method;
    }
    std::vector<int16_t> pcm(count);
    for (size_t i = 0; i < count; ++i) {
        const float v = std::max(-1.0f, std::min(1.0f, samples[i]));
        pcm[i] = static_cast<int16_t>(v * 32767.0f);
    }
    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(pcm.size() * sizeof(int16_t)));
    if (bytes == nullptr) {
        env->ExceptionClear();
        return;
    }
    env->SetByteArrayRegion(
        bytes, 0, static_cast<jsize>(pcm.size() * sizeof(int16_t)),
        reinterpret_cast<const jbyte *>(pcm.data()));
    env->CallVoidMethod(target, method, bytes);
    env->DeleteLocalRef(bytes);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
}

std::string raw_token_piece(const llama_vocab * vocab, llama_token token, bool special) {
    char buffer[256];
    int32_t written = llama_token_to_piece(vocab, token, buffer, sizeof(buffer), 0, special);
    if (written > 0 && static_cast<size_t>(written) <= sizeof(buffer)) {
        return std::string(buffer, static_cast<size_t>(written));
    }
    if (written < 0) {
        std::vector<char> larger(static_cast<size_t>(-written));
        written = llama_token_to_piece(vocab, token, larger.data(), static_cast<int32_t>(larger.size()), 0, special);
        if (written > 0) return std::string(larger.data(), static_cast<size_t>(written));
    }
    return {};
}

bool is_turn_boundary_special(const std::string & piece) {
    return piece == "<|im_end|>" ||
           piece == "<|eot_id|>" ||
           piece == "<end_of_turn>" ||
           piece == "<turn|>" ||
           piece == "<|end|>" ||
           piece == "</s>" ||
           piece == "<|endoftext|>" ||
           piece == "<|end_of_text|>" ||
           piece == "<|im_start|>" ||
           piece == "<start_of_turn>" ||
           piece == "<|turn>";
}

bool is_semantic_special(const std::string & piece) {
    return piece == "<tool_call>" ||
           piece == "</tool_call>" ||
           piece == "<|python_tag|>" ||
           piece == "[TOOL_CALLS]" ||
           piece == "<|tool_call>" ||
           piece == "<tool_call|>" ||
           piece == "<|\"|>" ||
           piece == "<|tool_call_start|>" ||
           piece == "<|tool_call_end|>" ||
           piece == "<think>" ||
           piece == "</think>" ||
           piece == "<|channel>" ||
           piece == "<channel|>" ||
           piece == "<|thought|>" ||
           piece == "<thought>" ||
           piece == "</thought>";
}

// Converts a sampled token into UTF-8 text while preserving special tool-call and
// reasoning delimiters (which llama_token_to_piece(..., special=false) drops).
std::string token_to_piece(const llama_vocab * vocab, llama_token token, bool & stop_turn) {
    stop_turn = false;
    std::string normal = raw_token_piece(vocab, token, false);
    if (!normal.empty()) {
        return normal;
    }
    std::string special = raw_token_piece(vocab, token, true);
    if (special.empty()) {
        return {};
    }
    if (is_turn_boundary_special(special)) {
        stop_turn = true;
        return {};
    }
    if (is_semantic_special(special)) {
        return special;
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
        int n_past,
        std::atomic<bool> * stop_flag = nullptr) {
    if (tokens.empty()) return true;
    llama_batch batch = llama_batch_init(n_batch, 0, 1);
    if (batch.token == nullptr) return false;

    bool ok = true;
    for (size_t offset = 0; offset < tokens.size(); offset += static_cast<size_t>(n_batch)) {
        // Prompt evaluation used to be uninterruptible, so a Stop request
        // during a long prefill kept the context busy for the whole decode
        // and a follow-up message immediately failed with BUSY_INFERENCE.
        // Check the stop flag between batches so Stop takes effect quickly.
        if (stop_flag != nullptr && stop_flag->load()) break;
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

bool eval_multimodal_audio(
        LlamaSession * session,
        const std::string & prompt,
        const jshort * pcm16,
        size_t sample_count,
        int & n_past) {
    if (session->mtmd == nullptr || pcm16 == nullptr || sample_count == 0) {
        LOGE("native Omni audio requested without an audio projector or samples");
        return false;
    }
    if (!mtmd_support_audio(session->mtmd)) {
        LOGE("loaded projector has no audio input encoder");
        return false;
    }

    std::string full_prompt = prompt;
    const char * marker = mtmd_default_marker();
    if (marker != nullptr && full_prompt.find(marker) == std::string::npos) {
        full_prompt = std::string(marker) + full_prompt;
    }

    std::vector<float> samples(sample_count);
    for (size_t i = 0; i < sample_count; ++i) {
        samples[i] = static_cast<float>(pcm16[i]) / 32768.0f;
    }
    LOGI("native Omni audio: %zu samples (%.1fs @16kHz)",
         samples.size(), (double) samples.size() / 16000.0);
    mtmd_bitmap * bitmap = mtmd_bitmap_init_from_audio(samples.size(), samples.data());
    if (bitmap == nullptr) return false;
    mtmd_input_chunks * chunks = mtmd_input_chunks_init();
    if (chunks == nullptr) {
        mtmd_bitmap_free(bitmap);
        return false;
    }
    mtmd_input_text input{};
    input.text = full_prompt.c_str();
    input.text_len = full_prompt.size();
    input.add_special = true;
    input.parse_special = true;
    const mtmd_bitmap * bitmaps[1] = { bitmap };
    int result = mtmd_tokenize(session->mtmd, chunks, &input, bitmaps, 1);
    if (result == 0) {
        llama_pos new_n_past = 0;
        LOGI("native Omni audio prompt tokenized; evaluating");
        result = mtmd_helper_eval_chunks(
            session->mtmd, session->ctx, chunks,
            0, 0, session->n_batch, true, &new_n_past);
        if (result == 0) {
            n_past = static_cast<int>(new_n_past);
            LOGI("native Omni audio prompt evaluated at position %d", n_past);
        }
    }
    mtmd_input_chunks_free(chunks);
    mtmd_bitmap_free(bitmap);
    if (result != 0) {
        LOGE("native Omni audio evaluation failed: %d", result);
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

extern "C" JNIEXPORT jboolean JNICALL
Java_com_localllm_android_engine_llama_LlamaRuntime_nativeGpuOffloadAvailable(
        JNIEnv * /* env */, jobject /* thiz */) {
    return llama_supports_gpu_offload() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_localllm_android_engine_llama_LlamaRuntime_nativeSystemInfo(
        JNIEnv * env, jobject /* thiz */) {
    const char * info = llama_print_system_info();
    return env->NewStringUTF(info != nullptr ? info : "unknown");
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
        jboolean vocab_only,
        jint type_k,
        jint type_v) {
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

    // KV-cache precision chosen in Settings: 1 = F16, 8 = Q8_0, 2 = Q4_0 (ggml_type
    // ids). Any other value keeps llama.cpp's default instead of degrading the load.
    const auto is_supported_kv_type = [](jint t) { return t == 1 || t == 8 || t == 2; };
    if (is_supported_kv_type(type_k)) {
        context_params.type_k = static_cast<decltype(context_params.type_k)>(type_k);
    }
    if (is_supported_kv_type(type_v)) {
        context_params.type_v = static_cast<decltype(context_params.type_v)>(type_v);
    }
    LOGI("kv cache types: k=%d v=%d", static_cast<int>(context_params.type_k),
         static_cast<int>(context_params.type_v));

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
    LOGI("native completion: evaluating prompt (%d chars, image=%s)",
         static_cast<int>(prompt.size()), !image_path.empty() ? "yes" : "no");
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
        } else if (!decode_tokens(session->ctx, session->n_batch, tokens, 0, &session->stop)) {
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

                bool stop_turn = false;
                pending += token_to_piece(session->vocab, token, stop_turn);
                if (stop_turn) break;
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

extern "C" JNIEXPORT jint JNICALL
Java_com_localllm_android_engine_llama_LlamaContext_nativeOmniCompletion(
        JNIEnv * env, jobject /* thiz */,
        jlong handle,
        jstring j_prompt_prefix,
        jstring j_prompt_continuation,
        jshortArray j_pcm16,
        jfloat temperature,
        jint max_steps,
        jint expected_output_sample_rate,
        jobject callback_target) {
    const auto session = acquire_session(handle);
    if (session == nullptr || session->ctx == nullptr || session->vocab == nullptr ||
        session->mtmd == nullptr) {
        return STATUS_NOT_READY;
    }
    if (session->closing.load()) return STATUS_NOT_READY;
    std::unique_lock<std::mutex> lock(session->mutex, std::try_to_lock);
    if (!lock.owns_lock()) return STATUS_BUSY;

    const std::string prefix = jstring_to_utf8(env, j_prompt_prefix);
    const std::string continuation = jstring_to_utf8(env, j_prompt_continuation);
    if (prefix.empty() || j_pcm16 == nullptr) return STATUS_PROMPT_FAILED;
    const jsize sample_count = env->GetArrayLength(j_pcm16);
    if (sample_count <= 0) return STATUS_PROMPT_FAILED;
    std::vector<jshort> pcm16(static_cast<size_t>(sample_count));
    env->GetShortArrayRegion(j_pcm16, 0, sample_count, pcm16.data());

    session->stop.store(false);
    session->predicting.store(true);
    struct PredictingGuard {
        LlamaSession * session;
        ~PredictingGuard() { session->predicting.store(false); }
    } guard{session.get()};

    llama_memory_clear(llama_get_memory(session->ctx), true);
    llama_set_embeddings(session->ctx, true);

    int n_past = 0;
    if (!eval_multimodal_audio(
            session.get(), prefix, pcm16.data(), pcm16.size(), n_past)) {
        return STATUS_MULTIMODAL_FAILED;
    }
    if (n_past >= session->n_ctx) return STATUS_CONTEXT_OVERFLOW;

    mtmd_helper_gen_audio * gen = mtmd_helper_gen_audio_init(session->ctx, session->mtmd);
    if (gen == nullptr) return STATUS_NOT_READY;
    LOGI("native Omni completion: generation pipeline created");
    struct GenGuard {
        mtmd_helper_gen_audio * gen;
        ~GenGuard() { mtmd_helper_gen_audio_free(gen); }
    } gen_guard{gen};

    mtmd_helper_gen_audio_inp input = mtmd_helper_gen_audio_inp_default();
    input.stream = true;
    input.seq_id = 0;
    input.prompt = continuation.c_str();
    input.prompt_len = continuation.size();
    input.n_past = n_past;
    input.out_mode = MTMD_HELPER_GEN_AUDIO_OUT_MIXED;
    input.out_type = MTMD_HELPER_GEN_AUDIO_OUTTYPE_PCM;
    input.seed = LLAMA_DEFAULT_SEED;
    if (mtmd_helper_gen_audio_set_input(gen, &input) != 0) {
        return STATUS_MULTIMODAL_FAILED;
    }
    LOGI("native Omni completion: generation input ready");
    int32_t prompt_remaining;
    while ((prompt_remaining = mtmd_helper_gen_audio_step_prompt(
                gen, session->n_batch)) > 0) {
        if (session->stop.load() || session->closing.load()) break;
    }
    if (prompt_remaining < 0) return STATUS_DECODE_FAILED;
    LOGI("native Omni completion: continuation prompt decoded");

    const float * initial_h = llama_get_embeddings_ith(session->ctx, -1);
    const int32_t n_embd = llama_model_n_embd(session->model);
    if (initial_h == nullptr) return STATUS_DECODE_FAILED;
    std::vector<float> h_state(initial_h, initial_h + n_embd);

    llama_sampler_chain_params sampler_params = llama_sampler_chain_default_params();
    sampler_params.no_perf = true;
    llama_sampler * sampler = llama_sampler_chain_init(sampler_params);
    if (sampler == nullptr) return STATUS_OUT_OF_MEMORY;
    struct SamplerGuard {
        llama_sampler * sampler;
        ~SamplerGuard() { llama_sampler_free(sampler); }
    } sampler_guard{sampler};
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    const int32_t capped_steps = max_steps > 0 ? max_steps : 8192;
    int32_t steps = 0;
    bool stopped = false;
    jint status = STATUS_OK;
    std::string pending_text;
    while (!stopped && steps < capped_steps) {
        if (session->stop.load() || session->closing.load()) break;
        llama_token sampled = LLAMA_TOKEN_NULL;
        if (mtmd_helper_gen_audio_next_is_text(gen)) {
            sampled = llama_sampler_sample(sampler, session->ctx, -1);
            if (sampled < 0) {
                status = STATUS_DECODE_FAILED;
                break;
            }
            llama_sampler_accept(sampler, sampled);
        }

        const float * next_h = nullptr;
        bool frame_stop = false;
        if (mtmd_helper_gen_audio_step_gen(
                gen, sampled, h_state.data(), &next_h, &frame_stop) != 0) {
            status = STATUS_DECODE_FAILED;
            break;
        }
        if (next_h != nullptr) h_state.assign(next_h, next_h + n_embd);

        if (sampled != LLAMA_TOKEN_NULL && !frame_stop &&
            !llama_vocab_is_control(session->vocab, sampled) &&
            !llama_vocab_is_eog(session->vocab, sampled)) {
            pending_text += raw_token_piece(session->vocab, sampled, false);
            const size_t hold = utf8_incomplete_suffix(pending_text);
            const size_t ready = pending_text.size() - hold;
            if (ready > 0) {
                emit_bytes(env, callback_target, pending_text.substr(0, ready));
                pending_text.erase(0, ready);
            }
        }

        int32_t sample_rate = 0;
        const char * audio_data = nullptr;
        size_t audio_len = 0;
        if (mtmd_helper_gen_audio_get_output(
                gen, &sample_rate, &audio_data, &audio_len, nullptr) == 0 &&
            audio_data != nullptr && audio_len >= sizeof(float)) {
            if (expected_output_sample_rate > 0 &&
                sample_rate != expected_output_sample_rate) {
                LOGE("native Omni output rate %d != negotiated %d",
                     sample_rate, expected_output_sample_rate);
                status = STATUS_DECODE_FAILED;
                stopped = true;
                break;
            }
            emit_audio_pcm16(
                env, callback_target,
                reinterpret_cast<const float *>(audio_data),
                audio_len / sizeof(float));
        }

        stopped = frame_stop;
        steps++;
        if (steps == 1 || steps % 32 == 0) {
            LOGI("native Omni completion: step %d, text_mode=%d",
                 steps, mtmd_helper_gen_audio_next_is_text(gen) ? 1 : 0);
        }
        const llama_pos max_pos =
            llama_memory_seq_pos_max(llama_get_memory(session->ctx), 0);
        if (max_pos + 2 >= session->n_ctx) stopped = true;
    }

    if (!stopped) {
        mtmd_helper_gen_audio_flush(gen);
        int32_t sample_rate = 0;
        const char * audio_data = nullptr;
        size_t audio_len = 0;
        if (mtmd_helper_gen_audio_get_output(
                gen, &sample_rate, &audio_data, &audio_len, nullptr) == 0 &&
            audio_data != nullptr && audio_len >= sizeof(float)) {
            if (expected_output_sample_rate <= 0 ||
                sample_rate == expected_output_sample_rate) {
                emit_audio_pcm16(
                    env, callback_target,
                    reinterpret_cast<const float *>(audio_data),
                    audio_len / sizeof(float));
            } else {
                status = STATUS_DECODE_FAILED;
            }
        }
    }
    if (!pending_text.empty()) {
        emit_bytes(env, callback_target, pending_text);
    }
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
