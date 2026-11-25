
package com.kisoti.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

class AudioEngine(
    private val context: Context,
    private val listener: Listener
) {

    interface Listener {
        fun onAudioData(audioData: ShortArray, length: Int)
        fun onError(message: String)
    }

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        // 1024 samples = 64 milliseconds at 16 kHz.
        private const val READ_BUFFER_SIZE = 1024
    }

    @Volatile
    private var isRecording = false

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null

    @Synchronized
    fun start(): Boolean {

        if (isRecording) return true

        // Clean up any previous recording session.
        if (audioRecord != null || recordingThread != null) {
            stop()
        }

        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            reportError("Ruhusa ya microphone haijatolewa")
            return false
        }

        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT
        )

        if (minBufferSize <= 0) {
            reportError("Imeshindwa kuandaa microphone: $minBufferSize")
            return false
        }

        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                maxOf(minBufferSize * 2, 4096)
            )
        } catch (e: Exception) {
            reportError("Tatizo la microphone: ${e.message}")
            return false
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            reportError("Microphone haikuweza kuanzishwa")
            return false
        }

        try {
            recorder.startRecording()

            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                recorder.release()
                reportError("Microphone haikuanza kurekodi")
                return false
            }

        } catch (e: Exception) {
            try {
                recorder.release()
            } catch (_: Exception) {
            }

            reportError("Imeshindwa kuanza kurekodi: ${e.message}")
            return false
        }

        audioRecord = recorder
        isRecording = true

        try {
            recordingThread = Thread {
                captureAudio(recorder)
            }.apply {
                name = "Kisoti-AudioEngine"
                start()
            }

        } catch (e: Exception) {
            isRecording = false

            try {
                recorder.stop()
            } catch (_: Exception) {
            }

            recorder.release()
            audioRecord = null
            recordingThread = null

            reportError("Imeshindwa kuanzisha recording thread: ${e.message}")
            return false
        }

        return true
    }

    private fun captureAudio(recorder: AudioRecord) {

        val buffer = ShortArray(READ_BUFFER_SIZE)

        try {

            while (isRecording) {

                val length = recorder.read(
                    buffer,
                    0,
                    buffer.size
                )

                when {
                    length > 0 -> {

                        if (!isRecording) break

                        // Send an independent copy to the listener.
                        // The listener must return quickly so audio
                        // capture is not blocked by Whisper processing.
                        val audioData = buffer.copyOf(length)

                        try {
                            listener.onAudioData(audioData, length)
                        } catch (e: Exception) {
                            reportError(
                                "Hitilafu wakati wa kupokea sauti: ${e.message}"
                            )
                            break
                        }
                    }

                    length == 0 -> {
                        Thread.yield()
                    }

                    else -> {
                        if (isRecording) {
                            reportError(
                                "Hitilafu ya kusoma sauti: $length"
                            )
                        }
                        break
                    }
                }
            }

        } catch (e: Exception) {
            if (isRecording) {
                reportError("Audio capture error: ${e.message}")
            }

        } finally {
            isRecording = false
        }
    }

    @Synchronized
    fun stop() {

        isRecording = false

        val recorder = audioRecord
        val thread = recordingThread

        // Stop AudioRecord first to unblock a pending read().
        try {
            if (recorder != null &&
                recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING
            ) {
                recorder.stop()
            }
        } catch (_: Exception) {
        }

        // Wait for the recording thread to finish.
        if (thread != null && thread !== Thread.currentThread()) {
            try {
                thread.join(1000)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        try {
            recorder?.release()
        } catch (_: Exception) {
        }

        audioRecord = null
        recordingThread = null
    }

    fun isRunning(): Boolean {
        return isRecording
    }

    private fun reportError(message: String) {
        try {
            listener.onError(message)
        } catch (_: Exception) {
            // Prevent listener errors from crashing audio capture.
        }
    }
}
