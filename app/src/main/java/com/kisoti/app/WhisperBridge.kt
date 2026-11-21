
package com.kisoti.app

class WhisperBridge {

    companion object {
        init {
            System.loadLibrary("kisoti_hear")
        }
    }

    private var nativeContext: Long = 0L

    private external fun nativeInit(modelPath: String): Long

    private external fun nativeTranscribe(
        contextPointer: Long,
        audioData: ShortArray
    ): String

    private external fun nativeFree(contextPointer: Long)

    @Synchronized
    fun loadModel(modelPath: String): Boolean {
        if (nativeContext != 0L) {
            return true
        }

        nativeContext = nativeInit(modelPath)
        return nativeContext != 0L
    }

    @Synchronized
    fun transcribe(audioData: ShortArray): String {
        if (nativeContext == 0L || audioData.isEmpty()) {
            return ""
        }

        return nativeTranscribe(nativeContext, audioData)
    }

    @Synchronized
    fun close() {
        if (nativeContext != 0L) {
            nativeFree(nativeContext)
            nativeContext = 0L
        }
    }

    fun isModelLoaded(): Boolean {
        return nativeContext != 0L
    }
}

