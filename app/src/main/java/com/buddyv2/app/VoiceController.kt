package com.buddyv2.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent

/**
 * Main orchestrator for voice interaction.
 * Coordinates SpeechRecognizer, TTS, VAD, IntentParser, and UI.
 * Supports continuous listening mode with true voice interruption (barge-in).
 * Includes comprehensive diagnostic logging for troubleshooting.
 */
class VoiceController(
    private val activity: Activity,
    private val uiController: VoiceUIController,
    private val intentParser: IntentParser,
    private val onIntentParsed: (IntentParser.Intent) -> Unit,
    private val onTextRecognized: (String) -> Unit,
    private val onResultReady: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val diagnosticLogger: DiagnosticLogger
) : LifecycleObserver, SpeechRecognizerManager.RecognitionCallback {

    private val speechRecognizerManager: SpeechRecognizerManager
    private val textToSpeechManager: TextToSpeechManager
    private val voiceActivityDetector: VoiceActivityDetector

    private var isMicPermissionGranted = false
    private var pendingIntent: IntentParser.Intent? = null
    private var voiceActionPending = false

    // Continuous listening mode state
    private var isContinuousListeningEnabled = false
    private var isProcessingInterruption = false
    private var wasContinuousBeforeInterruption = false

    // TTS state tracking for barge-in coordination
    private var isTtsActuallySpeaking = false
    private var ttsSpeechStartedTime = 0L

    companion object {
        private const val TAG = "VoiceController"
        private const val MIC_PERMISSION_REQUEST_CODE = 200
        // Minimum time TTS must be speaking before barge-in is allowed (avoid false triggers)
        private const val MIN_TTS_DURATION_BEFORE_BARGE_IN_MS = 500
    }

    init {
        speechRecognizerManager = SpeechRecognizerManager(activity, this, diagnosticLogger)
        textToSpeechManager = TextToSpeechManager(activity)
        voiceActivityDetector = VoiceActivityDetector(diagnosticLogger)
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_CREATE)
    fun onCreate() {
        diagnosticLogger.log("DIAGNOSTIC: VoiceController.onCreate() called")
        Log.d(TAG, "DIAGNOSTIC: VoiceController.onCreate() called")
        checkMicPermission()

        // Set up TTS speech callbacks for barge-in coordination
        textToSpeechManager.setTtsSpeechCallbacks(
            onStart = { onTtsSpeechActuallyStarted() },
            onEnd = { onTtsSpeechActuallyEnded() }
        )

        textToSpeechManager.initialize { success ->
            diagnosticLogger.log("DIAGNOSTIC: TTS initialization result = $success")
            Log.d(TAG, "DIAGNOSTIC: TTS initialization result = $success")
            if (!success) {
                diagnosticLogger.log("TTS initialization failed")
                Log.w(TAG, "TTS initialization failed")
            }
        }
        speechRecognizerManager.initialize()

        // Set up VAD callback for barge-in detection
        voiceActivityDetector.setCallback(object : VoiceActivityDetector.VoiceDetectionCallback {
            override fun onVoiceDetected() {
                onVadVoiceDetected()
            }

            override fun onVoiceEnded() {
                onVadVoiceEnded()
            }
        })
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    fun onDestroy() {
        diagnosticLogger.log("DIAGNOSTIC: VoiceController.onDestroy() called")
        Log.d(TAG, "DIAGNOSTIC: VoiceController.onDestroy() called")
        voiceActivityDetector.stop()
        speechRecognizerManager.destroy()
        textToSpeechManager.shutdown()
    }

    private fun checkMicPermission() {
        isMicPermissionGranted = ContextCompat.checkSelfPermission(
            activity, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        diagnosticLogger.log("DIAGNOSTIC: checkMicPermission() - isMicPermissionGranted = $isMicPermissionGranted")
        Log.d(TAG, "DIAGNOSTIC: checkMicPermission() - isMicPermissionGranted = $isMicPermissionGranted")

        if (!isMicPermissionGranted) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                MIC_PERMISSION_REQUEST_CODE
            )
        }
    }

    fun onRequestPermissionsResult(requestCode: Int, grantResults: IntArray) {
        if (requestCode == MIC_PERMISSION_REQUEST_CODE) {
            isMicPermissionGranted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            diagnosticLogger.log("DIAGNOSTIC: onRequestPermissionsResult() - isMicPermissionGranted = $isMicPermissionGranted")
            Log.d(TAG, "DIAGNOSTIC: onRequestPermissionsResult() - isMicPermissionGranted = $isMicPermissionGranted")
            if (isMicPermissionGranted) {
                diagnosticLogger.log("Microphone permission granted")
                Log.d(TAG, "Microphone permission granted")
            } else {
                diagnosticLogger.log("Microphone permission denied")
                Log.w(TAG, "Microphone permission denied")
                onError("Microphone permission is required for voice input")
            }
        }
    }

    fun onMicButtonClick() {
        diagnosticLogger.log("DIAGNOSTIC: onMicButtonClick() called, current state = ${uiController.getState()}")
        Log.d(TAG, "DIAGNOSTIC: onMicButtonClick() called, current state = ${uiController.getState()}")
        when (uiController.getState()) {
            VoiceUIController.VoiceState.IDLE -> startListening()
            VoiceUIController.VoiceState.LISTENING -> stopListening()
            VoiceUIController.VoiceState.PROCESSING -> {
                diagnosticLogger.log("DIAGNOSTIC: Ignoring click while PROCESSING")
                Log.d(TAG, "DIAGNOSTIC: Ignoring click while PROCESSING")
            }
            VoiceUIController.VoiceState.SPEAKING -> {
                // Barge-in via button: stop TTS and immediately start listening
                diagnosticLogger.log("DIAGNOSTIC: Barge-in via button during SPEAKING")
                Log.d(TAG, "DIAGNOSTIC: Barge-in via button during SPEAKING")
                handleBargeIn()
            }
        }
    }

    /** Enable continuous listening mode - after each response, automatically restart listening */
    fun enableContinuousListening() {
        diagnosticLogger.log("DIAGNOSTIC: enableContinuousListening() called")
        Log.d(TAG, "DIAGNOSTIC: enableContinuousListening() called")
        isContinuousListeningEnabled = true
        // If currently idle, start listening
        if (uiController.getState() == VoiceUIController.VoiceState.IDLE) {
            startListening()
        }
    }

    /** Disable continuous listening mode */
    fun disableContinuousListening() {
        diagnosticLogger.log("DIAGNOSTIC: disableContinuousListening() called")
        Log.d(TAG, "DIAGNOSTIC: disableContinuousListening() called")
        isContinuousListeningEnabled = false
        // If currently listening, stop
        if (uiController.getState() == VoiceUIController.VoiceState.LISTENING) {
            stopListening()
        }
    }

    /** Check if continuous listening mode is enabled */
    fun isContinuousListening(): Boolean = isContinuousListeningEnabled

    private fun startListening(isBargeIn: Boolean = false) {
        diagnosticLogger.log("DIAGNOSTIC: startListening() called, isBargeIn=$isBargeIn")
        Log.d(TAG, "DIAGNOSTIC: startListening() called, isBargeIn=$isBargeIn")
        if (!isMicPermissionGranted) {
            diagnosticLogger.log("DIAGNOSTIC: startListening() - no mic permission, requesting")
            Log.w(TAG, "DIAGNOSTIC: startListening() - no mic permission, requesting")
            checkMicPermission()
            onError("Microphone permission required")
            return
        }

        if (speechRecognizerManager.isCurrentlyListening) {
            diagnosticLogger.log("DIAGNOSTIC: startListening() - already listening")
            Log.w(TAG, "DIAGNOSTIC: startListening() - already listening")
            return
        }

        // Stop any ongoing TTS (if not already stopped for barge-in)
        if (textToSpeechManager.isTtsSpeaking && !isBargeIn) {
            diagnosticLogger.log("DIAGNOSTIC: startListening() - stopping TTS before listening")
            textToSpeechManager.stop()
        }

        // Stop VAD if running (we're about to start proper recognition)
        voiceActivityDetector.stop()

        diagnosticLogger.log("DIAGNOSTIC: startListening() - setting UI state to LISTENING")
        Log.d(TAG, "DIAGNOSTIC: startListening() - setting UI state to LISTENING")
        uiController.setState(VoiceUIController.VoiceState.LISTENING)
        speechRecognizerManager.startListening(isBargeIn)
    }

    private fun stopListening() {
        diagnosticLogger.log("DIAGNOSTIC: stopListening() called")
        Log.d(TAG, "DIAGNOSTIC: stopListening() called")
        speechRecognizerManager.stopListening()
        uiController.setState(VoiceUIController.VoiceState.PROCESSING)
    }

    fun stopSpeaking() {
        diagnosticLogger.log("DIAGNOSTIC: stopSpeaking() called")
        Log.d(TAG, "DIAGNOSTIC: stopSpeaking() called")
        textToSpeechManager.stop()
        voiceActivityDetector.stop()
        uiController.setState(VoiceUIController.VoiceState.IDLE)
    }

    /** Handle barge-in: stop TTS, stop VAD, start listening for new command */
    private fun handleBargeIn() {
        diagnosticLogger.log("DIAGNOSTIC: handleBargeIn() called")
        Log.d(TAG, "DIAGNOSTIC: handleBargeIn() called")

        // Prevent duplicate barge-in handling
        if (isProcessingInterruption) {
            diagnosticLogger.log("DIAGNOSTIC: handleBargeIn() - already processing interruption, ignoring")
            return
        }

        isProcessingInterruption = true
        wasContinuousBeforeInterruption = isContinuousListeningEnabled

        // Stop TTS immediately
        textToSpeechManager.stop()

        // Stop VAD
        voiceActivityDetector.stop()

        // Small delay to ensure audio system settles, then start listening
        // Use a short delay to avoid race conditions
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            startListening(isBargeIn = true)
            isProcessingInterruption = false
        }, 100)
    }

    /** Called when VAD detects voice during TTS */
    private fun onVadVoiceDetected() {
        diagnosticLogger.log("DIAGNOSTIC: onVadVoiceDetected() called, isTtsActuallySpeaking=$isTtsActuallySpeaking")
        Log.d(TAG, "DIAGNOSTIC: onVadVoiceDetected() called, isTtsActuallySpeaking=$isTtsActuallySpeaking")

        // Only trigger barge-in if TTS is actually speaking and has been for a minimum duration
        if (isTtsActuallySpeaking) {
            val ttsDuration = System.currentTimeMillis() - ttsSpeechStartedTime
            if (ttsDuration >= MIN_TTS_DURATION_BEFORE_BARGE_IN_MS) {
                diagnosticLogger.log("DIAGNOSTIC: Barge-in triggered by VAD (TTS duration=${ttsDuration}ms)")
                Log.d(TAG, "Barge-in triggered by VAD after ${ttsDuration}ms of TTS")
                handleBargeIn()
            } else {
                diagnosticLogger.log("DIAGNOSTIC: VAD detected voice but TTS too short (${ttsDuration}ms), ignoring")
            }
        }
    }

    /** Called when VAD voice segment ends */
    private fun onVadVoiceEnded() {
        diagnosticLogger.log("DIAGNOSTIC: onVadVoiceEnded() called")
        Log.d(TAG, "DIAGNOSTIC: onVadVoiceEnded() called")
        // VAD voice segment ended - if we didn't trigger barge-in, continue monitoring
        // The barge-in would have been triggered in onVadVoiceDetected
    }

    /** Called when TTS actually starts speaking (from UtteranceProgressListener.onStart) */
    private fun onTtsSpeechActuallyStarted() {
        diagnosticLogger.log("DIAGNOSTIC: onTtsSpeechActuallyStarted() called")
        Log.d(TAG, "DIAGNOSTIC: onTtsSpeechActuallyStarted() called")
        isTtsActuallySpeaking = true
        ttsSpeechStartedTime = System.currentTimeMillis()

        // Start VAD to monitor for user speech during TTS (for barge-in)
        // Only if continuous listening is enabled
        if (isContinuousListeningEnabled) {
            diagnosticLogger.log("DIAGNOSTIC: Starting VAD for barge-in detection during TTS")
            voiceActivityDetector.start()
        }
    }

    /** Called when TTS actually ends speaking (from UtteranceProgressListener.onDone/onError) */
    private fun onTtsSpeechActuallyEnded() {
        diagnosticLogger.log("DIAGNOSTIC: onTtsSpeechActuallyEnded() called, isContinuousListening=$isContinuousListeningEnabled")
        Log.d(TAG, "DIAGNOSTIC: onTtsSpeechActuallyEnded() called, isContinuousListening=$isContinuousListeningEnabled")
        isTtsActuallySpeaking = false

        // Stop VAD
        voiceActivityDetector.stop()

        // If continuous listening is enabled and we're not processing an interruption,
        // automatically restart listening
        if (isContinuousListeningEnabled && !isProcessingInterruption) {
            diagnosticLogger.log("DIAGNOSTIC: TTS ended, restarting listening for continuous mode")
            // Small delay to ensure clean transition
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                startListening()
            }, 200)
        } else if (!isContinuousListeningEnabled) {
            diagnosticLogger.log("DIAGNOSTIC: TTS ended, returning to IDLE (continuous mode off)")
            uiController.setState(VoiceUIController.VoiceState.IDLE)
        }
    }

    /** Called by MainActivity when a voice-triggered action completes with a result message. */
    fun reportVoiceResult(resultMessage: String) {
        diagnosticLogger.log("DIAGNOSTIC: reportVoiceResult() called, voiceActionPending = $voiceActionPending, message = '$resultMessage'")
        Log.d(TAG, "DIAGNOSTIC: reportVoiceResult() called, voiceActionPending = $voiceActionPending, message = '$resultMessage'")
        if (!voiceActionPending) {
            diagnosticLogger.log("DIAGNOSTIC: reportVoiceResult() - voiceActionPending is FALSE, ignoring")
            Log.w(TAG, "DIAGNOSTIC: reportVoiceResult() - voiceActionPending is FALSE, ignoring")
            return
        }
        voiceActionPending = false
        diagnosticLogger.log("DIAGNOSTIC: reportVoiceResult() - voice action completed, calling onResultReady and speakResponse")
        Log.d(TAG, "DIAGNOSTIC: reportVoiceResult() - voice action completed, calling onResultReady and speakResponse")
        onResultReady(resultMessage)
        speakResponse(resultMessage)
    }

    /** Called by MainActivity to indicate a voice action is starting. */
    fun markVoiceActionPending() {
        diagnosticLogger.log("DIAGNOSTIC: markVoiceActionPending() called")
        Log.d(TAG, "DIAGNOSTIC: markVoiceActionPending() called")
        voiceActionPending = true
    }

    // SpeechRecognizerManager.RecognitionCallback implementation
    override fun onReadyForSpeech() {
        diagnosticLogger.log("DIAGNOSTIC: onReadyForSpeech() called")
        Log.d(TAG, "DIAGNOSTIC: onReadyForSpeech() called")
        // Already handled by UI state change
    }

    override fun onRecognitionStarted() {
        diagnosticLogger.log("DIAGNOSTIC: onRecognitionStarted() called - recognition actually started")
        Log.d(TAG, "DIAGNOSTIC: onRecognitionStarted() called - recognition actually started")
        // Recognition has actually started - if this was a barge-in, VAD is already stopped
        // If VAD is still running for some reason, stop it now
        voiceActivityDetector.stop()
    }

    override fun onBeginningOfSpeech() {
        diagnosticLogger.log("DIAGNOSTIC: onBeginningOfSpeech() called - USER STARTED SPEAKING")
        Log.d(TAG, "DIAGNOSTIC: onBeginningOfSpeech() called - USER STARTED SPEAKING")
        // User started speaking during recognition
    }

    override fun onPartialResults(partialText: String) {
        diagnosticLogger.log("DIAGNOSTIC: onPartialResults() called with text = '$partialText'")
        Log.d(TAG, "DIAGNOSTIC: onPartialResults() called with text = '$partialText'")
        uiController.setRecognizedText(partialText)
        onTextRecognized(partialText)
    }

    override fun onResults(finalText: String) {
        diagnosticLogger.log("DIAGNOSTIC: onResults() called with finalText = '$finalText'")
        Log.d(TAG, "DIAGNOSTIC: onResults() called with finalText = '$finalText'")
        uiController.setState(VoiceUIController.VoiceState.PROCESSING)
        val intent = intentParser.parse(finalText)
        pendingIntent = intent
        // Mark that a voice action is pending so reportVoiceResult knows to complete it
        voiceActionPending = true
        diagnosticLogger.log("DIAGNOSTIC: onResults() - parsed intent = $intent, voiceActionPending = true")
        Log.d(TAG, "DIAGNOSTIC: onResults() - parsed intent = $intent, voiceActionPending = true")
        onIntentParsed(intent)
    }

    override fun onEndOfSpeech() {
        diagnosticLogger.log("DIAGNOSTIC: onEndOfSpeech() called - SPEECH ENDED, waiting for results")
        Log.d(TAG, "DIAGNOSTIC: onEndOfSpeech() called - SPEECH ENDED, waiting for results")
        // Speech ended, waiting for results
    }

    override fun onError(errorCode: Int, errorMessage: String) {
        val errorString = when (errorCode) {
            SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
            SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
            SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
            SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
            SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
            else -> "ERROR_UNKNOWN($errorCode)"
        }
        diagnosticLogger.log("DIAGNOSTIC: onError() errorCode=$errorCode ($errorString), message='$errorMessage'")
        Log.w(TAG, "DIAGNOSTIC: onError() errorCode=$errorCode ($errorString), message='$errorMessage'")

        // Stop VAD on any recognition error
        voiceActivityDetector.stop()

        // Handle barge-in specific errors
        if (errorCode == SpeechRecognizer.ERROR_RECOGNIZER_BUSY && isProcessingInterruption) {
            diagnosticLogger.log("DIAGNOSTIC: Recognizer busy during barge-in, will retry")
            // Could retry here, but for now just reset and let continuous mode handle it
            isProcessingInterruption = false
        }

        uiController.setState(VoiceUIController.VoiceState.IDLE)
        voiceActionPending = false
        when (errorCode) {
            SpeechRecognizer.ERROR_NO_MATCH -> onError("No speech detected. Try again.")
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> onError("Speech timed out. Try again.")
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                isMicPermissionGranted = false
                onError("Microphone permission denied")
            }
            else -> onError(errorMessage)
        }

        // If continuous listening and error wasn't during interruption, restart listening
        if (isContinuousListeningEnabled && !isProcessingInterruption) {
            diagnosticLogger.log("DIAGNOSTIC: Error in continuous mode, restarting listening")
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                startListening()
            }, 500)
        }
    }

    fun speakResponse(text: String) {
        diagnosticLogger.log("DIAGNOSTIC: speakResponse() called with text = '$text'")
        Log.d(TAG, "DIAGNOSTIC: speakResponse() called with text = '$text'")
        if (text.isBlank()) {
            diagnosticLogger.log("DIAGNOSTIC: speakResponse() - text is blank, returning to IDLE")
            Log.w(TAG, "DIAGNOSTIC: speakResponse() - text is blank, returning to IDLE")
            uiController.setState(VoiceUIController.VoiceState.IDLE)
            return
        }

        uiController.setState(VoiceUIController.VoiceState.SPEAKING)
        textToSpeechManager.speak(text, object : TextToSpeechManager.SpeakCallback {
            override fun onStart() {
                diagnosticLogger.log("DIAGNOSTIC: TTS onStart()")
                Log.d(TAG, "DIAGNOSTIC: TTS onStart()")
            }

            override fun onDone() {
                diagnosticLogger.log("DIAGNOSTIC: TTS onDone()")
                Log.d(TAG, "DIAGNOSTIC: TTS onDone()")
                // Actual speech end is handled by onTtsSpeechActuallyEnded()
            }

            override fun onError(errorMessage: String) {
                diagnosticLogger.log("DIAGNOSTIC: TTS onError() = $errorMessage")
                Log.e(TAG, "DIAGNOSTIC: TTS onError() = $errorMessage")
                // Actual speech end on error is handled by onTtsSpeechActuallyEnded()
            }
        })
    }

    fun executePendingIntent(resultMessage: String) {
        diagnosticLogger.log("DIAGNOSTIC: executePendingIntent() called with resultMessage = '$resultMessage'")
        Log.d(TAG, "DIAGNOSTIC: executePendingIntent() called with resultMessage = '$resultMessage'")
        pendingIntent?.let { intent ->
            diagnosticLogger.log("DIAGNOSTIC: executePendingIntent() - re-delivering pending intent = $intent")
            Log.d(TAG, "DIAGNOSTIC: executePendingIntent() - re-delivering pending intent = $intent")
            onIntentParsed(intent) // Re-deliver for execution
        }
        onResultReady(resultMessage)
        speakResponse(resultMessage)
    }

    fun cancelAll() {
        diagnosticLogger.log("DIAGNOSTIC: cancelAll() called")
        Log.d(TAG, "DIAGNOSTIC: cancelAll() called")
        voiceActivityDetector.stop()
        speechRecognizerManager.cancel()
        textToSpeechManager.stop()
        voiceActionPending = false
        isProcessingInterruption = false
        isTtsActuallySpeaking = false
        uiController.setState(VoiceUIController.VoiceState.IDLE)
    }
}