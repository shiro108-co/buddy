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
 * Coordinates SpeechRecognizer, TTS, IntentParser, and UI.
 * Supports WhatsApp-style press-and-hold voice input.
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

    private var isMicPermissionGranted = false
    private var pendingIntent: IntentParser.Intent? = null
    private var voiceActionPending = false

    // Track if a voice input session is active (for press-and-hold)
    private var isVoiceInputActive = false

    companion object {
        private const val TAG = "VoiceController"
        private const val MIC_PERMISSION_REQUEST_CODE = 200
    }

    init {
        speechRecognizerManager = SpeechRecognizerManager(activity, this, diagnosticLogger)
        textToSpeechManager = TextToSpeechManager(activity)
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_CREATE)
    fun onCreate() {
        diagnosticLogger.log("DIAGNOSTIC: VoiceController.onCreate() called")
        Log.d(TAG, "DIAGNOSTIC: VoiceController.onCreate() called")
        checkMicPermission()

        textToSpeechManager.initialize { success ->
            diagnosticLogger.log("DIAGNOSTIC: TTS initialization result = $success")
            Log.d(TAG, "DIAGNOSTIC: TTS initialization result = $success")
            if (!success) {
                diagnosticLogger.log("TTS initialization failed")
                Log.w(TAG, "TTS initialization failed")
            }
        }
        speechRecognizerManager.initialize()
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    fun onDestroy() {
        diagnosticLogger.log("DIAGNOSTIC: VoiceController.onDestroy() called")
        Log.d(TAG, "DIAGNOSTIC: VoiceController.onDestroy() called")
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

    /** Called when user presses and holds the mic button - start voice recognition */
    fun startVoiceInput() {
        diagnosticLogger.log("DIAGNOSTIC: startVoiceInput() called, current state = ${uiController.getState()}, isVoiceInputActive = $isVoiceInputActive")
        Log.d(TAG, "DIAGNOSTIC: startVoiceInput() called, current state = ${uiController.getState()}, isVoiceInputActive = $isVoiceInputActive")

        // Only start if idle and not already in a voice input session
        if (uiController.getState() != VoiceUIController.VoiceState.IDLE || isVoiceInputActive) {
            diagnosticLogger.log("DIAGNOSTIC: startVoiceInput() - ignoring, not in IDLE or already active")
            Log.d(TAG, "DIAGNOSTIC: startVoiceInput() - ignoring, not in IDLE or already active")
            return
        }

        isVoiceInputActive = true
        startListening()
    }

    /** Called when user releases the mic button - stop voice recognition and process */
    fun stopVoiceInput() {
        diagnosticLogger.log("DIAGNOSTIC: stopVoiceInput() called, isVoiceInputActive = $isVoiceInputActive")
        Log.d(TAG, "DIAGNOSTIC: stopVoiceInput() called, isVoiceInputActive = $isVoiceInputActive")

        if (!isVoiceInputActive) {
            diagnosticLogger.log("DIAGNOSTIC: stopVoiceInput() - ignoring, no active voice input")
            Log.d(TAG, "DIAGNOSTIC: stopVoiceInput() - ignoring, no active voice input")
            return
        }

        isVoiceInputActive = false
        stopListening()
    }

    /** Called when user drags outside button or interaction is cancelled - cancel without processing */
    fun cancelVoiceInput() {
        diagnosticLogger.log("DIAGNOSTIC: cancelVoiceInput() called, isVoiceInputActive = $isVoiceInputActive")
        Log.d(TAG, "DIAGNOSTIC: cancelVoiceInput() called, isVoiceInputActive = $isVoiceInputActive")

        if (!isVoiceInputActive) {
            diagnosticLogger.log("DIAGNOSTIC: cancelVoiceInput() - ignoring, no active voice input")
            Log.d(TAG, "DIAGNOSTIC: cancelVoiceInput() - ignoring, no active voice input")
            return
        }

        isVoiceInputActive = false
        cancelListening()
    }

    private fun startListening() {
        diagnosticLogger.log("DIAGNOSTIC: startListening() called")
        Log.d(TAG, "DIAGNOSTIC: startListening() called")
        if (!isMicPermissionGranted) {
            diagnosticLogger.log("DIAGNOSTIC: startListening() - no mic permission, requesting")
            Log.w(TAG, "DIAGNOSTIC: startListening() - no mic permission, requesting")
            checkMicPermission()
            onError("Microphone permission required")
            isVoiceInputActive = false
            return
        }

        if (speechRecognizerManager.isCurrentlyListening) {
            diagnosticLogger.log("DIAGNOSTIC: startListening() - already listening")
            Log.w(TAG, "DIAGNOSTIC: startListening() - already listening")
            return
        }

        // Stop any ongoing TTS
        if (textToSpeechManager.isTtsSpeaking) {
            diagnosticLogger.log("DIAGNOSTIC: startListening() - stopping TTS before listening")
            textToSpeechManager.stop()
        }

        diagnosticLogger.log("DIAGNOSTIC: startListening() - setting UI state to LISTENING")
        Log.d(TAG, "DIAGNOSTIC: startListening() - setting UI state to LISTENING")
        uiController.setState(VoiceUIController.VoiceState.LISTENING)
        speechRecognizerManager.startListening()
    }

    private fun stopListening() {
        diagnosticLogger.log("DIAGNOSTIC: stopListening() called")
        Log.d(TAG, "DIAGNOSTIC: stopListening() called")
        speechRecognizerManager.stopListening()
        uiController.setState(VoiceUIController.VoiceState.PROCESSING)
    }

    private fun cancelListening() {
        diagnosticLogger.log("DIAGNOSTIC: cancelListening() called")
        Log.d(TAG, "DIAGNOSTIC: cancelListening() called")
        speechRecognizerManager.cancel()
        uiController.setState(VoiceUIController.VoiceState.IDLE)
        voiceActionPending = false
    }

    fun stopSpeaking() {
        diagnosticLogger.log("DIAGNOSTIC: stopSpeaking() called")
        Log.d(TAG, "DIAGNOSTIC: stopSpeaking() called")
        textToSpeechManager.stop()
        uiController.setState(VoiceUIController.VoiceState.IDLE)
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

        uiController.setState(VoiceUIController.VoiceState.IDLE)
        voiceActionPending = false
        isVoiceInputActive = false
        when (errorCode) {
            SpeechRecognizer.ERROR_NO_MATCH -> onError("No speech detected. Try again.")
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> onError("Speech timed out. Try again.")
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                isMicPermissionGranted = false
                onError("Microphone permission denied")
            }
            else -> onError(errorMessage)
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
                // After TTS finishes, return to IDLE
                uiController.setState(VoiceUIController.VoiceState.IDLE)
            }

            override fun onError(errorMessage: String) {
                diagnosticLogger.log("DIAGNOSTIC: TTS onError() = $errorMessage")
                Log.e(TAG, "DIAGNOSTIC: TTS onError() = $errorMessage")
                uiController.setState(VoiceUIController.VoiceState.IDLE)
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
        speechRecognizerManager.cancel()
        textToSpeechManager.stop()
        voiceActionPending = false
        isVoiceInputActive = false
        uiController.setState(VoiceUIController.VoiceState.IDLE)
    }
}