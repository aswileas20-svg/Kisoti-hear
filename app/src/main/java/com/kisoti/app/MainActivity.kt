
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

    private lateinit var audioEngine: AudioEngine
    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        audioEngine = AudioEngine(
            this,
            object : AudioEngine.Listener {

                override fun onAudioData(
                    audioData: ShortArray,
                    length: Int
                ) {
                    // Sauti itapelekwa kwa Whisper katika hatua inayofuata.
                }

                override fun onError(message: String) {
                    runOnUiThread {
                        statusText.text = message
                    }
                }
            }
        )

        createScreen()
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

        val transcriptText = TextView(this).apply {
            text = "Matokeo ya sauti yataonekana hapa baada ya kuunganisha Whisper."
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
                statusText.text = "Ruhusa ya microphone imekataliwa."
            }
        }
    }

    override fun onDestroy() {
        audioEngine.stop()
        super.onDestroy()
    }
}
