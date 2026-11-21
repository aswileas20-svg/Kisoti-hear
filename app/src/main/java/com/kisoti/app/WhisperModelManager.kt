

package com.kisoti.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.net.URL
import java.security.MessageDigest

object WhisperModelManager {

    private const val MODEL_NAME = "ggml-tiny-q5_1.bin"

    private const val MODEL_URL =
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny-q5_1.bin"

    private const val EXPECTED_SHA256 =
        "818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7"

    interface Listener {
        fun onProgress(percent: Int)
        fun onComplete(modelPath: String)
        fun onError(message: String)
    }

    fun getModelPath(context: Context): String {
        return File(context.filesDir, MODEL_NAME).absolutePath
    }

    fun download(context: Context, listener: Listener) {

        Thread {
            val modelFile = File(context.filesDir, MODEL_NAME)
            val tempFile = File(context.filesDir, "$MODEL_NAME.tmp")

            try {
                if (modelFile.exists() &&
                    sha256(modelFile) == EXPECTED_SHA256) {

                    Handler(Looper.getMainLooper()).post {
                        listener.onComplete(modelFile.absolutePath)
                    }
                    return@Thread
                }

                modelFile.delete()

                val connection = URL(MODEL_URL)
                    .openConnection()

                connection.connectTimeout = 30000
                connection.readTimeout = 60000

                connection.getInputStream().use { input ->

                    tempFile.outputStream().use { output ->

                        val buffer = ByteArray(8192)
                        var downloaded = 0L
                        val total = connection.contentLengthLong

                        while (true) {
                            val count = input.read(buffer)

                            if (count == -1) break

                            output.write(buffer, 0, count)
                            downloaded += count

                            if (total > 0) {
                                val percent =
                                    (downloaded * 100 / total).toInt()

                                Handler(Looper.getMainLooper()).post {
                                    listener.onProgress(percent)
                                }
                            }
                        }
                    }
                }

                if (sha256(tempFile) != EXPECTED_SHA256) {
                    tempFile.delete()
                    throw Exception("Model imeharibika wakati wa kupakuliwa.")
                }

                if (!tempFile.renameTo(modelFile)) {
                    tempFile.delete()
                    throw Exception("Imeshindwa kuhifadhi model.")
                }

                Handler(Looper.getMainLooper()).post {
                    listener.onComplete(modelFile.absolutePath)
                }

            } catch (e: Exception) {

                tempFile.delete()

                Handler(Looper.getMainLooper()).post {
                    listener.onError(
                        e.message ?: "Tatizo la kupakua model."
                    )
                }
            }

        }.start()
    }

    private fun sha256(file: File): String {

        val digest = MessageDigest.getInstance("SHA-256")

        file.inputStream().use { input ->
            val buffer = ByteArray(8192)

            while (true) {
                val count = input.read(buffer)
                if (count == -1) break

                digest.update(buffer, 0, count)
            }
        }

        return digest.digest().joinToString("") {
            "%02x".format(it)
        }
    }
}
