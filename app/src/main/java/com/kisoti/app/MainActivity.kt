
package com.kisoti.app

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.Manifest
import android.content.pm.PackageManager
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private companion object {
        const val MICROPHONE_REQUEST_CODE = 100
        const val SAMPLE_RATE = 16000

        // Keep the existing 5-second sliding window for initial testing.
        const val WINDOW_SECONDS = 5
        const val UPDATE_SECONDS = 1
    }

    private val windowSize = SAMPLE_RATE * WINDOW_SECONDS
    private val updateInterval = SAMPLE_RATE * UPDATE_SECONDS

    private lateinit var whisperBridge: WhisperBridge
    private lateinit var transcriptText: TextView
    private lateinit var audioEngine: AudioEngine
    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    @Volatile
    private var modelReady = false

    @Volatile
    private var currentSession = 0

    private val transcriptionExecutor =
        Executors.newSingleThreadExecutor()

    private data class PendingAudio(
        val samples: ShortArray,
        val sessionId: Int,
        val capturedAtMs: Long
    )

    private val pendingLock = Any()

    private var pendingAudio: PendingAudio? = null

    private var workerRunning = false

    private var processingSession = -1

    // Audio sliding window.
    private val audioLock = Any()

    private val audioRingBuffer = ShortArray(windowSize)

    private var ringWritePosition = 0
    private var ringSamplesAvailable = 0
    private var samplesSinceLastInference = 0

    // Transcript overlap management.
    private var previousWindowWords: List<String> = emptyList()
    private var inferenceResultCount = 0

    private var hasTranscript = false

    // Diagnostic information.
    @Volatile
    private var lastInferenceDurationMs = 0L

    @Volatile
    private var lastQueueDelayMs = 0L

    @Volatile
    private var droppedWindowCount = 0

    @Volatile
    private var firstAudioToastShown = false

    @Volatile
    private var firstWindowToastShown = false

    @Volatile
    private var firstInferenceStarted = false

    @Volatile
    private var busyToastShown = false

    private val mainHandler = Handler(Looper.getMainLooper())

    private val diagnosticRunnable = object : Runnable {

        override fun run() {

            if (!modelReady) return

            val active = synchronized(pendingLock) {
                workerRunning
            }

            if (!active) return

            try {

                val stage = whisperBridge.getNativeStage()
                val result = whisperBridge.getLastResult()
                val samples = whisperBridge.getLastSamples()
                val segments = whisperBridge.getLastSegments()

                val message = when (stage) {

                    2 -> {
                        "WHISPER: Inachakata sauti...\n"
                    }

                    3 -> {
                        "WHISPER: Uchakataji umekamilika.\n"
                    }

                    else -> {
                        "Whisper inajiandaa..."
                    }
                }

                statusText.text =
                    message +
                    "Muda wa uchakataji: ${lastInferenceDurationMs} ms\n" +
                    "Muda wa kusubiri: ${lastQueueDelayMs} ms\n" +
                    "Madirisha yaliyopitwa: $droppedWindowCount\n" +
                    "Sampuli: $samples\n" +
                    "Segments: $segments\n" +
                    "Matokeo: $result"

            } catch (e: Exception) {

                statusText.text =
                    "Tatizo la vipimo vya Whisper: ${e.message}"
            }

            mainHandler.postDelayed(this, 1000)
        }
    }

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
                        showToast(message)
                    }
                }
            }
        )

        createScreen()
        prepareWhisperModel()
    }

    private fun showToast(message: String) {

        runOnUiThread {

            Toast.makeText(
                applicationContext,
                message,
                Toast.LENGTH_SHORT
            ).show()
        }
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

                    runOnUiThread {
                        statusText.text =
                            "Inaandaa Whisper Model..."
                    }

                    Thread {

                        val loaded =
                            whisperBridge.loadModel(modelPath)

                        runOnUiThread {

                            if (loaded) {

                                modelReady = true

                                statusText.text =
                                    "Whisper Model iko tayari. Karibu!"

                                showToast(
                                    "Whisper Model imepakiwa vizuri."
                                )

                            } else {

                                statusText.text =
                                    "Imeshindwa kuandaa Whisper Model."

                                showToast(
                                    "Tatizo: Whisper Model haijapakiwa."
                                )
                            }
                        }

                    }.apply {
                        name = "Kisoti-ModelLoader"
                        start()
                    }
                }

                override fun onError(message: String) {

                    runOnUiThread {

                        statusText.text =
                            "Tatizo la Whisper Model: $message"

                        showToast("Model error: $message")
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
            != PackageManager.PERMISSION_GRANTED
        ) {

            requestPermissions(
                arrayOf(Manifest.permission.RECORD_AUDIO),
                MICROPHONE_REQUEST_CODE
            )

        } else {

            startListening()
        }
    }

    private fun startListening() {

        if (!modelReady) {

            statusText.text =
                "Subiri Whisper Model iandaliwe kwanza."

            showToast("Whisper Model bado haijawa tayari.")
            return
        }

        if (audioEngine.isRunning()) {
            return
        }

        currentSession++

        synchronized(pendingLock) {
            pendingAudio = null
        }

        synchronized(audioLock) {

            ringWritePosition = 0
            ringSamplesAvailable = 0
            samplesSinceLastInference = 0
        }

        firstAudioToastShown = false
        firstWindowToastShown = false
        firstInferenceStarted = false
        busyToastShown = false

        lastInferenceDurationMs = 0L
        lastQueueDelayMs = 0L
        droppedWindowCount = 0

        hasTranscript = false

        transcriptText.text = "Nasubiri sauti..."

        val started = audioEngine.start()

        if (started) {

            statusText.text =
                "Kisoti-Hear inasikiliza moja kwa moja..."

            startButton.isEnabled = false
            stopButton.isEnabled = true

            showToast("Microphone imeanza. Ongea sasa.")

        } else {

            showToast("Microphone haikuweza kuanza.")
        }
    }

    private fun collectAudio(
        audioData: ShortArray,
        length: Int
    ) {

        if (length <= 0) return

        if (!firstAudioToastShown) {

            firstAudioToastShown = true

            showToast(
                "Hatua 1: Microphone inapokea sauti."
            )
        }

        var snapshot: ShortArray? = null

        synchronized(audioLock) {

            val safeLength =
                minOf(length, audioData.size)

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

            if (
                ringSamplesAvailable >= windowSize &&
                samplesSinceLastInference >= updateInterval
            ) {

                samplesSinceLastInference %= updateInterval

                snapshot = ShortArray(windowSize) { index ->

                    audioRingBuffer[
                        (ringWritePosition + index) % windowSize
                    ]
                }

                if (!firstWindowToastShown) {

                    firstWindowToastShown = true

                    showToast(
                        "Hatua 2: Sauti ya sekunde 5 imekusanywa."
                    )
                }
            }
        }

        snapshot?.let {
            sendToWhisper(it)
        }
    }

    private fun sendToWhisper(audioData: ShortArray) {

        synchronized(pendingLock) {

            // Count a window only when it replaces another
            // window that has not yet started processing.
            if (pendingAudio != null) {
                droppedWindowCount++
            }

            pendingAudio = PendingAudio(
                samples = audioData.copyOf(),
                sessionId = currentSession,
                capturedAtMs = SystemClock.elapsedRealtime()
            )

            if (!workerRunning) {

                workerRunning = true

                try {

                    transcriptionExecutor.execute {
                        processPendingAudio()
                    }

                    mainHandler.removeCallbacks(
                        diagnosticRunnable
                    )

                    mainHandler.post(
                        diagnosticRunnable
                    )

                } catch (e: Exception) {

                    workerRunning = false
                    pendingAudio = null

                    showToast(
                        "Imeshindwa kupanga kazi ya Whisper: ${e.message}"
                    )
                }

            } else {

                if (!busyToastShown) {

                    busyToastShown = true

                    showToast(
                        "Whisper inachakata sauti mpya."
                    )
                }

                // Muhimu: synchronized block inarudisha Unit,
                // badala ya kutumia if isiyo na else kama expression.
                Unit
            }
        }
    }

    private fun processPendingAudio() {

        while (true) {

            val job: PendingAudio? = synchronized(pendingLock) {

                val nextAudio = pendingAudio

                if (nextAudio == null) {

                    workerRunning = false
                    busyToastShown = false

                    null

                } else {

                    pendingAudio = null

                    nextAudio
                }
            }

            if (job == null) {
                return
            }

            val audioData = job.samples
            val jobSession = job.sessionId

            // Do not process obsolete sessions.
            if (jobSession != currentSession) {
                continue
            }

            try {

                if (processingSession != jobSession) {

                    processingSession = jobSession

                    previousWindowWords = emptyList()
                    inferenceResultCount = 0
                }

                val queueDelay =
                    (
                        SystemClock.elapsedRealtime() -
                        job.capturedAtMs
                    ).coerceAtLeast(0L)

                if (jobSession == currentSession) {
                    lastQueueDelayMs = queueDelay
                }

                if (!firstInferenceStarted) {

                    firstInferenceStarted = true

                    showToast(
                        "Hatua 3: Whisper imeanza kuchakata sauti."
                    )
                }

                val inferenceStart =
                    SystemClock.elapsedRealtime()

                val result =
                    whisperBridge.transcribe(audioData).trim()

                val inferenceDuration =
                    SystemClock.elapsedRealtime() - inferenceStart

                if (jobSession != currentSession) {
                    continue
                }

                lastInferenceDurationMs = inferenceDuration

                inferenceResultCount++

                if (result.isNotEmpty()) {

                    if (inferenceResultCount <= 3) {

                        showToast(
                            "Whisper imerudisha maandishi: ${result.length} herufi."
                        )
                    }

                    val currentWords =
                        result.split(Regex("\\s+"))
                            .filter { it.isNotBlank() }

                    val newWords =
                        extractNewWords(currentWords)

                    if (newWords.isNotEmpty()) {

                        runOnUiThread {

                            if (jobSession != currentSession) {
                                return@runOnUiThread
                            }

                            if (!hasTranscript) {

                                transcriptText.text = ""
                                hasTranscript = true
                            }

                            transcriptText.append(
                                newWords.joinToString(" ") + " "
                            )
                        }
                    }

                } else {

                    if (inferenceResultCount < 3) {

                        showToast(
                            "Whisper imerudisha matokeo tupu."
                        )
                    }
                }

            } catch (e: Exception) {

                if (jobSession == currentSession) {

                    runOnUiThread {

                        statusText.text =
                            "Tatizo la STT: ${e.message}"

                        showToast(
                            "STT error: ${e.message}"
                        )
                    }
                }
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

        val remainingAudio: ShortArray? =
            synchronized(audioLock) {

                val available =
                    minOf(ringSamplesAvailable, windowSize)

                if (available >= SAMPLE_RATE) {

                    val startPosition =
                        (
                            ringWritePosition - available + windowSize
                        ) % windowSize

                    ShortArray(available) { index ->

                        audioRingBuffer[
                            (startPosition + index) % windowSize
                        ]
                    }

                } else {

                    null
                }
            }

        if (remainingAudio != null) {
            sendToWhisper(remainingAudio)
        }

        statusText.text =
            "Kusikiliza kumesimamishwa. Whisper inaweza kuendelea kuchakata."

        startButton.isEnabled = true
        stopButton.isEnabled = false

        showToast("Kusikiliza kumesimamishwa.")
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

        if (requestCode == MICROPHONE_REQUEST_CODE) {

            if (
                grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {

                startListening()

            } else {

                statusText.text =
                    "Ruhusa ya microphone imekataliwa."

                showToast(
                    "Ruhusa ya microphone imekataliwa."
                )
            }
        }
    }

    override fun onDestroy() {

        mainHandler.removeCallbacks(diagnosticRunnable)

        audioEngine.stop()

        try {

            transcriptionExecutor.execute {
                whisperBridge.close()
            }

            transcriptionExecutor.shutdown()

        } catch (_: Exception) {

            transcriptionExecutor.shutdownNow()
        }

        super.onDestroy()
    }
}
