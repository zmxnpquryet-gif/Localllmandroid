// SDengine native core — TEST BUILD.
// Portable C++ reference kernels. Contract: every op here must match the
// JVM ReferenceKernels semantics; the instrumented test pins dot() bit-parity.
// ARM NEON ports go in arch/arm64/ next to this file (not yet written).
#include <jni.h>
#include <string>
#include <android/log.h>

#define LOG_TAG "SDengine"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jstring JNICALL
Java_com_localllm_engine_SDEngineNative_version(JNIEnv* env, jobject /*thiz*/) {
    return env->NewStringUTF("sdengine-native 0.1.0-test (portable C++, no NEON yet)");
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_localllm_engine_SDEngineNative_dot(
        JNIEnv* env, jobject /*thiz*/, jfloatArray a, jfloatArray b) {
    if (a == nullptr || b == nullptr) return 0.0;
    jsize n = env->GetArrayLength(a);
    if (n <= 0 || n != env->GetArrayLength(b)) return 0.0;
    jfloat* pa = env->GetFloatArrayElements(a, nullptr);
    jfloat* pb = env->GetFloatArrayElements(b, nullptr);
    if (pa == nullptr || pb == nullptr) {
        if (pa != nullptr) env->ReleaseFloatArrayElements(a, pa, JNI_ABORT);
        if (pb != nullptr) env->ReleaseFloatArrayElements(b, pb, JNI_ABORT);
        return 0.0;
    }
    double acc = 0.0;
    for (jsize i = 0; i < n; ++i) {
        acc += (double) pa[i] * (double) pb[i];
    }
    env->ReleaseFloatArrayElements(a, pa, JNI_ABORT);
    env->ReleaseFloatArrayElements(b, pb, JNI_ABORT);
    return acc;
}
