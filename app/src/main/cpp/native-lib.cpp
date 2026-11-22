
#include <jni.h>
#include <string>
#include <vector>
#include <cstdint>
#include "whisper.h"

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
        return 0;
    }

    const char* path =
            env->GetStringUTFChars(modelPath, nullptr);

    if (path == nullptr) {
        return 0;
    }

    whisper_context_params params =
            whisper_context_default_params();

    params.use_gpu = false;

    whisper_context* context =
            whisper_init_from_file_with_params(path, params);

    env->ReleaseStringUTFChars(modelPath, path);

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
        return env->NewStringUTF("");
    }

    jsize length = env->GetArrayLength(audioData);

    if (length == 0) {
        return env->NewStringUTF("");
    }

    jshort* audio =
            env->GetShortArrayElements(audioData, nullptr);

    if (audio == nullptr) {
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

    int result = whisper_full(
            context,
            params,
            samples.data(),
            static_cast<int>(samples.size())
    );

    if (result != 0) {
        return env->NewStringUTF("");
    }

    std::string transcript;

    int segments = whisper_full_n_segments(context);

    for (int i = 0; i < segments; i++) {

        const char* text =
                whisper_full_get_segment_text(context, i);

        if (text != nullptr) {
            transcript += text;
        }
    }

    return env->NewStringUTF(transcript.c_str());
}

extern "C"
JNIEXPORT void JNICALL
Java_com_kisoti_app_WhisperBridge_nativeFree(
        JNIEnv* env,
        jobject thiz,
        jlong contextPointer) {

    auto* context =
            reinterpret_cast<whisper_context*>(contextPointer);

    if (context != nullptr) {
        whisper_free(context);
    }
}
