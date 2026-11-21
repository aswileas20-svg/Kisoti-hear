

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

    private val sampleRate = 16000

    @Volatile
    private var isRecording = false

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null

    @Synchronized
    fun start(): Boolean {

        if (isRecording) return true

        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            listener.onError("Ruhusa ya microphone haijatolewa")
            return false
        }

        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (minBufferSize <= 0) {
            listener.onError("Imeshindwa kuandaa microphone")
            return false
        }

        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBufferSize * 2
            )
        } catch (e: Exception) {
            listener.onError("Tatizo la microphone: ${e.message}")
            return false
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            listener.onError("Microphone haikuweza kuanzishwa")
            return false
        }

        audioRecord = recorder
        isRecording = true

        try {
            recorder.startRecording()
        } catch (e: Exception) {
            isRecording = false
            recorder.release()
            audioRecord = null
            listener.onError("Imeshindwa kuanza kurekodi: ${e.message}")
            return false
        }

        recordingThread = Thread {
            val buffer = ShortArray(
                maxOf(minBufferSize / 2, 2048)
            )

            while (isRecording) {
                val length = recorder.read(
                    buffer,
                    0,
                    buffer.size
                )

                if (length > 0) {
                    listener.onAudioData(
                        buffer.copyOf(length),
                        length
                    )
                } else if (length < 0) {
                    listener.onError("Hitilafu ya kusoma sauti: $length")
                    break
                }
            }
        }.apply {
            name = "Kisoti-AudioEngine"
            start()
        }

        return true
    }

    @Synchronized
    fun stop() {
        isRecording = false

        val recorder = audioRecord

        try {
            if (recorder != null &&
                recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                recorder.stop()
            }
        } catch (_: Exception) {
        }

        try {
            recordingThread?.join(500)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        recorder?.release()

        audioRecord = null
        recordingThread = null
    }

    fun isRunning(): Boolean {
        return isRecording
    }
}
