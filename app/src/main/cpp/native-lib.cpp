
#include <jni.h>
#include <string>
#include <vector>
#include <cstdint>
#include <atomic>
#include <chrono>
#include <cmath>
#include <android/log.h>
#include "whisper.h"

#define LOG_TAG "KISOTI_WHISPER"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static std::atomic<int> g_inference_stage{0};
static std::atomic<int> g_last_result{-999};
static std::atomic<int> g_last_samples{0};
static std::atomic<int> g_last_segments{0};


// =====================================================
// LOAD WHISPER MODEL
// =====================================================

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


// =====================================================
// TRANSCRIBE AUDIO WITH DIAGNOSTICS
// =====================================================

extern "C"
JNIEXPORT jstring JNICALL
Java_com_kisoti_app_WhisperBridge_nativeTranscribe(
        JNIEnv* env,
        jobject thiz,
        jlong contextPointer,
        jshortArray audioData) {

    whisper_context* context =
            reinterpret_cast<whisper_context*>(contextPointer);

    if (context == nullptr || audioData == nullptr) {
        LOGE("Context au audioData ni tupu");
        g_inference_stage = 0;
        return env->NewStringUTF("");
    }

    jsize length = env->GetArrayLength(audioData);

    if (length <= 0) {
        LOGE("Audio haina samples");
        g_inference_stage = 0;
        return env->NewStringUTF("");
    }

    jshort* audio =
            env->GetShortArrayElements(audioData, nullptr);

    if (audio == nullptr) {
        LOGE("Imeshindwa kupata audio samples");
        g_inference_stage = 0;
        return env->NewStringUTF("");
    }

    std::vector<float> samples(length);

    float max_amplitude = 0.0f;
    double sum_squares = 0.0;

    for (jsize i = 0; i < length; i++) {

        float value =
                static_cast<float>(audio[i]) / 32768.0f;

        samples[i] = value;

        float absolute_value =
                std::fabs(value);

        if (absolute_value > max_amplitude) {
            max_amplitude = absolute_value;
        }

        sum_squares +=
                static_cast<double>(value) * value;
    }

    env->ReleaseShortArrayElements(
            audioData,
            audio,
            JNI_ABORT
    );

    double rms = std::sqrt(
            sum_squares / static_cast<double>(length)
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

    LOGI("DIAG: Maandalizi yamekamilika");
    LOGI("DIAG: Audio samples = %d",
         static_cast<int>(length));

    LOGI("DIAG: Audio duration = %.2f seconds",
         static_cast<double>(length) / 16000.0);

    LOGI("DIAG: Maximum amplitude = %.6f",
         max_amplitude);

    LOGI("DIAG: RMS amplitude = %.6f",
         rms);

    LOGI("DIAG: Whisper threads = %d",
         params.n_threads);

    g_inference_stage = 2;

    LOGI("DIAG: WHISPER_FULL_STARTED");

    auto start_time =
            std::chrono::steady_clock::now();

    int result = whisper_full(
            context,
            params,
            samples.data(),
            static_cast<int>(samples.size())
    );

    auto end_time =
            std::chrono::steady_clock::now();

    double elapsed_seconds =
            std::chrono::duration<double>(
                    end_time - start_time
            ).count();

    g_last_result = result;

    LOGI("DIAG: WHISPER_FULL_RETURNED");

    LOGI("DIAG: Inference time = %.2f seconds",
         elapsed_seconds);

    LOGI("DIAG: Whisper result = %d",
         result);

    if (result != 0) {

        LOGE("Whisper inference imerudisha error: %d",
             result);

        g_inference_stage = 3;

        return env->NewStringUTF("");
    }

    std::string transcript;

    int segments =
            whisper_full_n_segments(context);

    g_last_segments = segments;

    for (int i = 0; i < segments; i++) {

        const char* text =
                whisper_full_get_segment_text(context, i);

        if (text != nullptr) {
            transcript += text;
        }
    }

    LOGI("DIAG: Segments = %d", segments);

    LOGI("DIAG: Transcript characters = %d",
         static_cast<int>(transcript.size()));

    LOGI("DIAG: Uchambuzi umekamilika");

    g_inference_stage = 3;

    return env->NewStringUTF(transcript.c_str());
}


// =====================================================
// GET INFERENCE STAGE
// =====================================================

extern "C"
JNIEXPORT jint JNICALL
Java_com_kisoti_app_WhisperBridge_nativeGetStage(
        JNIEnv* env,
        jobject thiz) {

    return g_inference_stage.load();
}


// =====================================================
// GET LAST RESULT
// =====================================================

extern "C"
JNIEXPORT jint JNICALL
Java_com_kisoti_app_WhisperBridge_nativeGetLastResult(
        JNIEnv* env,
        jobject thiz) {

    return g_last_result.load();
}


// =====================================================
// GET LAST SAMPLE COUNT
// =====================================================

extern "C"
JNIEXPORT jint JNICALL
Java_com_kisoti_app_WhisperBridge_nativeGetLastSamples(
        JNIEnv* env,
        jobject thiz) {

    return g_last_samples.load();
}


// =====================================================
// GET LAST SEGMENT COUNT
// =====================================================

extern "C"
JNIEXPORT jint JNICALL
Java_com_kisoti_app_WhisperBridge_nativeGetLastSegments(
        JNIEnv* env,
        jobject thiz) {

    return g_last_segments.load();
}


// =====================================================
// FREE WHISPER MODEL
// =====================================================

extern "C"
JNIEXPORT void JNICALL
Java_com_kisoti_app_WhisperBridge_nativeFree(
        JNIEnv* env,
        jobject thiz,
        jlong contextPointer) {

    whisper_context* context =
            reinterpret_cast<whisper_context*>(contextPointer);

    if (context != nullptr) {
        whisper_free(context);
        LOGI("Model imefungwa");
    }
}
