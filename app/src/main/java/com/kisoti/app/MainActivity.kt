
package com.kisoti.app

import android.app.Activity
import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private val microphoneRequestCode = 100
    private val sampleRate = 16000
    private val chunkDurationSeconds = 5
    private val chunkSize = sampleRate * chunkDurationSeconds

    private lateinit var whisperBridge: WhisperBridge
    private lateinit var transcriptText: TextView
    private lateinit var audioEngine: AudioEngine
    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    @Volatile
    private var modelReady = false

    private val transcriptionExecutor =
        Executors.newSingleThreadExecutor()

    private val audioLock = Any()
    private val audioBuffer = ShortArray(chunkSize)

    private var audioBufferSize = 0
    private var hasTranscript = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        whisperBridge = WhisperBridge()

        audioEngine = AudioEngine(
            this,
            object : AudioEngine.Listener {

                override fun onAudioData(
                    audioData: ShortArray,
                    length: Int
                ) {
                    collectAudio(audioData, length)
                }

                override fun onError(message: String) {
                    runOnUiThread {
                        statusText.text = message
                    }
                }
            }
        )

        createScreen()
        prepareWhisperModel()
    }

    private fun prepareWhisperModel() {

        statusText.text =
            "Kisoti-Hear inapakua Whisper Model..."

        WhisperModelManager.download(
            this,
            object : WhisperModelManager.Listener {

                override fun onProgress(percent: Int) {
                    statusText.text =
                        "Inapakua Whisper Model: $percent%"
                }

                override fun onComplete(modelPath: String) {

                    statusText.text =
                        "Inaandaa Whisper Model..."

                    Thread {

                        val loaded =
                            whisperBridge.loadModel(modelPath)

                        runOnUiThread {

                            if (loaded) {
                                modelReady = true
                                statusText.text =
                                    "Whisper Model iko tayari. Karibu!"
                            } else {
                                statusText.text =
                                    "Imeshindwa kuandaa Whisper Model."
                            }
                        }

                    }.start()
                }

                override fun onError(message: String) {
                    statusText.text =
                        "Tatizo la Whisper Model: $message"
                }
            }
        )
    }

    private fun createScreen() {

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(30, 30, 30, 30)
        }

        val title = TextView(this).apply {
            text = "Kisoti-Hear"
            textSize = 28f
            gravity = Gravity.CENTER
        }

        statusText = TextView(this).apply {
            text = "Karibu kwenye Kisoti-Hear"
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(10, 40, 10, 40)
        }

        startButton = Button(this).apply {
            text = "ANZA KUSIKILIZA"
            setOnClickListener {
                checkMicrophonePermission()
            }
        }

        stopButton = Button(this).apply {
            text = "SIMAMISHA"
            isEnabled = false
            setOnClickListener {
                stopListening()
            }
        }

        transcriptText = TextView(this).apply {
            text = "Matokeo ya sauti yataonekana hapa."
            textSize = 17f
            gravity = Gravity.CENTER
            setPadding(10, 40, 10, 10)
        }

        layout.addView(title)
        layout.addView(statusText)
        layout.addView(startButton)
        layout.addView(stopButton)
        layout.addView(transcriptText)

        setContentView(layout)
    }

    private fun checkMicrophonePermission() {

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {

            requestPermissions(
                arrayOf(Manifest.permission.RECORD_AUDIO),
                microphoneRequestCode
            )

        } else {
            startListening()
        }
    }

    private fun startListening() {

        if (!modelReady) {
            statusText.text =
                "Subiri Whisper Model iandaliwe kwanza."
            return
        }

        val started = audioEngine.start()

        if (started) {
            statusText.text = "Kisoti-Hear inasikiliza..."
            startButton.isEnabled = false
            stopButton.isEnabled = true
        }
    }

    private fun collectAudio(
        audioData: ShortArray,
        length: Int
    ) {

        if (length <= 0) return

        val completedChunks = mutableListOf<ShortArray>()

        synchronized(audioLock) {

            var sourcePosition = 0

            while (sourcePosition < length) {

                val available =
                    chunkSize - audioBufferSize

                val remaining =
                    length - sourcePosition

                val copyLength =
                    minOf(available, remaining)

                System.arraycopy(
                    audioData,
                    sourcePosition,
                    audioBuffer,
                    audioBufferSize,
                    copyLength
                )

                audioBufferSize += copyLength
                sourcePosition += copyLength

                if (audioBufferSize == chunkSize) {

                    completedChunks.add(
                        audioBuffer.copyOf()
                    )

                    audioBufferSize = 0
                }
            }
        }

        for (chunk in completedChunks) {
            sendToWhisper(chunk)
        }
    }

    private fun sendToWhisper(audioData: ShortArray) {

        transcriptionExecutor.execute {

            try {

                val result =
                    whisperBridge.transcribe(audioData).trim()

                if (result.isNotEmpty()) {

                    runOnUiThread {

                        if (!hasTranscript) {
                            transcriptText.text = ""
                            hasTranscript = true
                        }

                        transcriptText.append(result + "\n")
                    }
                }

            } catch (e: Exception) {

                runOnUiThread {
                    statusText.text =
                        "Tatizo la STT: ${e.message}"
                }
            }
        }
    }

    private fun stopListening() {

        audioEngine.stop()

        var remainingAudio: ShortArray? = null

        synchronized(audioLock) {

            if (audioBufferSize >= sampleRate) {

                remainingAudio =
                    audioBuffer.copyOfRange(
                        0,
                        audioBufferSize
                    )
            }

            audioBufferSize = 0
        }

        remainingAudio?.let {
            sendToWhisper(it)
        }

        statusText.text = "Kusikiliza kumesimamishwa."
        startButton.isEnabled = true
        stopButton.isEnabled = false
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (requestCode == microphoneRequestCode) {

            if (grantResults.isNotEmpty() &&
                grantResults[0] ==
                PackageManager.PERMISSION_GRANTED) {

                startListening()

            } else {
                statusText.text =
                    "Ruhusa ya microphone imekataliwa."
            }
        }
    }

    override fun onDestroy() {

        audioEngine.stop()

        synchronized(audioLock) {
            audioBufferSize = 0
        }

        transcriptionExecutor.execute {
            whisperBridge.close()
        }

        transcriptionExecutor.shutdown()

        super.onDestroy()
    }
}
