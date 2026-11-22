
package com.kisoti.app

import android.app.Activity
import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private val microphoneRequestCode = 100

    private lateinit var whisperBridge: WhisperBridge
    private lateinit var transcriptText: TextView
    private lateinit var audioEngine: AudioEngine
    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    @Volatile
    private var modelReady = false

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
                    // Sauti itaunganishwa na Whisper katika hatua inayofuata.
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

        statusText.text = "Kisoti-Hear inapakua Whisper Model..."

        WhisperModelManager.download(
            this,
            object : WhisperModelManager.Listener {

                override fun onProgress(percent: Int) {
                    statusText.text =
                        "Inapakua Whisper Model: $percent%"
                }

                override fun onComplete(modelPath: String) {

                    statusText.text = "Inaandaa Whisper Model..."

                    Thread {

                        val loaded = whisperBridge.loadModel(modelPath)

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

    private fun stopListening() {

        audioEngine.stop()

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
                grantResults[0] == PackageManager.PERMISSION_GRANTED) {

                startListening()

            } else {
                statusText.text =
                    "Ruhusa ya microphone imekataliwa."
            }
        }
    }

    override fun onDestroy() {
        audioEngine.stop()
        whisperBridge.close()
        super.onDestroy()
    }
}
