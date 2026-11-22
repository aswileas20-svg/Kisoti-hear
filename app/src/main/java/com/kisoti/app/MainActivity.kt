
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
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {

    private val microphoneRequestCode = 100
    private val sampleRate = 16000

    // Dirisha la sauti la sekunde 5
    private val windowSize = sampleRate * 5

    // Sasisha dirisha kila baada ya sekunde 1
    private val updateInterval = sampleRate

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

    private val inferenceRunning = AtomicBoolean(false)

    private val audioLock = Any()

    private val audioRingBuffer = ShortArray(windowSize)

    private var ringWritePosition = 0
    private var ringSamplesAvailable = 0
    private var samplesSinceLastInference = 0

    private var previousWindowWords: List<String> = emptyList()

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
                    runOnUiThread {
                        statusText.text =
                            "Inapakua Whisper Model: $percent%"
                    }
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
                    runOnUiThread {
                        statusText.text =
                            "Tatizo la Whisper Model: $message"
                    }
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

        synchronized(audioLock) {
            ringWritePosition = 0
            ringSamplesAvailable = 0
            samplesSinceLastInference = 0
        }

        transcriptionExecutor.execute {
            previousWindowWords = emptyList()
        }

        val started = audioEngine.start()

        if (started) {
            statusText.text =
                "Kisoti-Hear inasikiliza moja kwa moja..."

            startButton.isEnabled = false
            stopButton.isEnabled = true
        }
    }

    private fun collectAudio(
        audioData: ShortArray,
        length: Int
    ) {

        if (length <= 0) return

        var snapshot: ShortArray? = null

        synchronized(audioLock) {

            val safeLength = minOf(length, audioData.size)

            for (i in 0 until safeLength) {

                audioRingBuffer[ringWritePosition] =
                    audioData[i]

                ringWritePosition =
                    (ringWritePosition + 1) % windowSize

                if (ringSamplesAvailable < windowSize) {
                    ringSamplesAvailable++
                }
            }

            samplesSinceLastInference += safeLength

            if (ringSamplesAvailable >= windowSize &&
                samplesSinceLastInference >= updateInterval) {

                samplesSinceLastInference %= updateInterval

                snapshot = ShortArray(windowSize) { index ->

                    audioRingBuffer[
                        (ringWritePosition + index) % windowSize
                    ]
                }
            }
        }

        snapshot?.let {
            sendToWhisper(it)
        }
    }

    private fun sendToWhisper(audioData: ShortArray) {

        // Usiruhusu kazi za Whisper kujikusanya.
        if (!inferenceRunning.compareAndSet(false, true)) {
            return
        }

        transcriptionExecutor.execute {

            try {

                val result =
                    whisperBridge.transcribe(audioData).trim()

                if (result.isNotEmpty()) {

                    val currentWords =
                        result.split(Regex("\\s+"))
                            .filter { it.isNotBlank() }

                    val newWords =
                        extractNewWords(currentWords)

                    if (newWords.isNotEmpty()) {

                        runOnUiThread {

                            if (!hasTranscript) {
                                transcriptText.text = ""
                                hasTranscript = true
                            }

                            transcriptText.append(
                                newWords.joinToString(" ") + " "
                            )
                        }
                    }
                }

            } catch (e: Exception) {

                runOnUiThread {
                    statusText.text =
                        "Tatizo la STT: ${e.message}"
                }

            } finally {
                inferenceRunning.set(false)
            }
        }
    }

    private fun extractNewWords(
        currentWords: List<String>
    ): List<String> {

        var overlap = 0

        val maxOverlap =
            minOf(
                previousWindowWords.size,
                currentWords.size
            )

        for (size in maxOverlap downTo 1) {

            var matches = true

            for (i in 0 until size) {

                val oldWord =
                    normalizeWord(
                        previousWindowWords[
                            previousWindowWords.size - size + i
                        ]
                    )

                val newWord =
                    normalizeWord(currentWords[i])

                if (oldWord != newWord) {
                    matches = false
                    break
                }
            }

            if (matches) {
                overlap = size
                break
            }
        }

        previousWindowWords = currentWords

        return currentWords.drop(overlap)
    }

    private fun normalizeWord(word: String): String {

        return word.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]"), "")
    }

    private fun stopListening() {

        audioEngine.stop()

        var remainingAudio: ShortArray? = null

        synchronized(audioLock) {

            val available =
                minOf(ringSamplesAvailable, windowSize)

            if (available >= sampleRate) {

                val startPosition =
                    (ringWritePosition - available + windowSize) %
                            windowSize

                remainingAudio = ShortArray(available) { index ->

                    audioRingBuffer[
                        (startPosition + index) % windowSize
                    ]
                }
            }
        }

        remainingAudio?.let {
            sendToWhisper(it)
        }

        statusText.text =
            "Kusikiliza kumesimamishwa."

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
            ringSamplesAvailable = 0
        }

        transcriptionExecutor.execute {
            whisperBridge.close()
        }

        transcriptionExecutor.shutdown()

        super.onDestroy()
    }
}
