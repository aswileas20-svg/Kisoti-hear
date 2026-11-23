
#include <jni.h>
#include <string>
#include <vector>
#include <cstdint>
#include <atomic>
#include <android/log.h>
#include "whisper.h"

#define LOG_TAG "KISOTI_WHISPER"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static std::atomic<int> g_inference_stage{0};
static std::atomic<int> g_last_result{-999};
static std::atomic<int> g_last_samples{0};
static std::atomic<int> g_last_segments{0};

extern "C"
JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM* vm, void* reserved) {
    return JNI_VERSION_1_6;
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_kisoti_app_WhisperBridge_nativeInit(
        JNIEnv* env,
        jobject thiz,
        jstring modelPath) {

    if (modelPath == nullptr) {
        LOGE("Model path ni tupu");
        return 0;
    }

    const char* path =
            env->GetStringUTFChars(modelPath, nullptr);

    if (path == nullptr) {
        LOGE("Imeshindwa kusoma model path");
        return 0;
    }

    whisper_context_params params =
            whisper_context_default_params();

    params.use_gpu = false;

    LOGI("Model inaanza kupakiwa");

    whisper_context* context =
            whisper_init_from_file_with_params(path, params);

    env->ReleaseStringUTFChars(modelPath, path);

    if (context == nullptr) {
        LOGE("Model imeshindwa kupakiwa");
    } else {
        LOGI("Model imepakiwa");
    }

    return reinterpret_cast<jlong>(context);
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_kisoti_app_WhisperBridge_nativeTranscribe(
        JNIEnv* env,
        jobject thiz,
        jlong contextPointer,
        jshortArray audioData) {

    auto* context =
            reinterpret_cast<whisper_context*>(contextPointer);

    if (context == nullptr || audioData == nullptr) {
        g_inference_stage = 0;
        return env->NewStringUTF("");
    }

    jsize length = env->GetArrayLength(audioData);

    if (length == 0) {
        g_inference_stage = 0;
        return env->NewStringUTF("");
    }

    jshort* audio =
            env->GetShortArrayElements(audioData, nullptr);

    if (audio == nullptr) {
        g_inference_stage = 0;
        return env->NewStringUTF("");
    }

    std::vector<float> samples(length);

    for (jsize i = 0; i < length; i++) {
        samples[i] =
                static_cast<float>(audio[i]) / 32768.0f;
    }

    env->ReleaseShortArrayElements(
            audioData,
            audio,
            JNI_ABORT
    );

    g_last_samples = static_cast<int>(length);
    g_last_segments = 0;
    g_last_result = -999;

    whisper_full_params params =
            whisper_full_default_params(
                    WHISPER_SAMPLING_GREEDY
            );

    params.n_threads = 4;
    params.language = "auto";
    params.detect_language = false;

    params.no_context = true;
    params.no_timestamps = true;
    params.single_segment = true;
    params.max_tokens = 0;

    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;

    LOGI("Hatua 1: maandalizi yamekamilika. Sampuli: %d",
         static_cast<int>(length));

    g_inference_stage = 2;

    LOGI("Hatua 2: whisper_full imeanza");

    int result = whisper_full(
            context,
            params,
            samples.data(),
            static_cast<int>(samples.size())
    );

    g_last_result = result;

    LOGI("Hatua 3: whisper_full imerudi. Result: %d", result);

    if (result != 0) {
        g_inference_stage = 3;
        return env->NewStringUTF("");
    }

    std::string transcript;

    int segments = whisper_full_n_segments(context);
    g_last_segments = segments;

    for (int i = 0; i < segments; i++) {

        const char* text =
                whisper_full_get_segment_text(context, i);

        if (text != nullptr) {
            transcript += text;
        }
    }

    LOGI("Uchambuzi umekamilika. Segments: %d, herufi: %d",
         segments,
         static_cast<int>(transcript.size()));

    g_inference_stage = 3;

    return env->NewStringUTF(transcript.c_str());
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_kisoti_app_WhisperBridge_nativeGetStage(
        JNIEnv* env,
        jobject thiz) {
    return g_inference_stage.load();
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_kisoti_app_WhisperBridge_nativeGetLastResult(
        JNIEnv* env,
        jobject thiz) {
    return g_last_result.load();
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_kisoti_app_WhisperBridge_nativeGetLastSamples(
        JNIEnv* env,
        jobject thiz) {
    return g_last_samples.load();
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_kisoti_app_WhisperBridge_nativeGetLastSegments(
        JNIEnv* env,
        jobject thiz) {
    return g_last_segments.load();
}
