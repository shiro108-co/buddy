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
 * Includes comprehensive diagnostic logging for troubleshooting.
 */
class VoiceController(
    private val activity: Activity,
    private val uiController: VoiceUIController,
    private val intentParser: IntentParser,
    private val onIntentParsed: (IntentParser.Intent) -> Unit,
    private val onTextRecognized: (String) -> Unit,
    private val onResultReady: (String) -> Unit,
    private val onError: (String) -> Unit
) : LifecycleObserver, SpeechRecognizerManager.RecognitionCallback {

    private val speechRecognizerManager: SpeechRecognizerManager
    private val textToSpeechManager: TextToSpeechManager

    private var isMicPermissionGranted = false
    private var pendingIntent: IntentParser.Intent? = null
    private var voiceActionPending = false

    companion object {
        private const val TAG = "VoiceController"
        private const val MIC_PERMISSION_REQUEST_CODE = 200
    }

    init {
        speechRecognizerManager = SpeechRecognizerManager(activity, this)
        textToSpeechManager = TextToSpeechManager(activity)
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_CREATE)
    fun onCreate() {
        Log.d(TAG, "DIAGNOSTIC: VoiceController.onCreate() called")
        checkMicPermission()
        textToSpeechManager.initialize { success ->
            Log.d(TAG, "DIAGNOSTIC: TTS initialization result = $success")
            if (!success) {
                Log.w(TAG, "TTS initialization failed")
            }
        }
        speechRecognizerManager.initialize()
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    fun onDestroy() {
        Log.d(TAG, "DIAGNOSTIC: VoiceController.onDestroy() called")
        speechRecognizerManager.destroy()
        textToSpeechManager.shutdown()
    }

    private fun checkMicPermission() {
        isMicPermissionGranted = ContextCompat.checkSelfPermission(
            activity, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

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
            Log.d(TAG, "DIAGNOSTIC: onRequestPermissionsResult() - isMicPermissionGranted = $isMicPermissionGranted")
            if (isMicPermissionGranted) {
                Log.d(TAG, "Microphone permission granted")
            } else {
                Log.w(TAG, "Microphone permission denied")
                onError("Microphone permission is required for voice input")
            }
        }
    }

    fun onMicButtonClick() {
        Log.d(TAG, "DIAGNOSTIC: onMicButtonClick() called, current state = ${uiController.getState()}")
        when (uiController.getState()) {
            VoiceUIController.VoiceState.IDLE -> startListening()
            VoiceUIController.VoiceState.LISTENING -> stopListening()
            VoiceUIController.VoiceState.PROCESSING -> {
                Log.d(TAG, "DIAGNOSTIC: Ignoring click while PROCESSING")
            }
            VoiceUIController.VoiceState.SPEAKING -> stopSpeaking()
        }
    }

    private fun startListening() {
        Log.d(TAG, "DIAGNOSTIC: startListening() called")
        if (!isMicPermissionGranted) {
            Log.w(TAG, "DIAGNOSTIC: startListening() - no mic permission, requesting")
            checkMicPermission()
            onError("Microphone permission required")
            return
        }

        if (speechRecognizerManager.isCurrentlyListening) {
            Log.w(TAG, "DIAGNOSTIC: startListening() - already listening")
            return
        }

        // Stop any ongoing TTS
        textToSpeechManager.stop()

        Log.d(TAG, "DIAGNOSTIC: startListening() - setting UI state to LISTENING")
        uiController.setState(VoiceUIController.VoiceState.LISTENING)
        speechRecognizerManager.startListening()
    }

    private fun stopListening() {
        Log.d(TAG, "DIAGNOSTIC: stopListening() called")
        speechRecognizerManager.stopListening()
        uiController.setState(VoiceUIController.VoiceState.PROCESSING)
    }

    fun stopSpeaking() {
        Log.d(TAG, "DIAGNOSTIC: stopSpeaking() called")
        textToSpeechManager.stop()
        uiController.setState(VoiceUIController.VoiceState.IDLE)
    }

    /** Called by MainActivity when a voice-triggered action completes with a result message. */
    fun reportVoiceResult(resultMessage: String) {
        Log.d(TAG, "DIAGNOSTIC: reportVoiceResult() called, voiceActionPending = $voiceActionPending, message = '$resultMessage'")
        if (!voiceActionPending) {
            Log.w(TAG, "DIAGNOSTIC: reportVoiceResult() - voiceActionPending is FALSE, ignoring")
            return
        }
        voiceActionPending = false
        Log.d(TAG, "DIAGNOSTIC: reportVoiceResult() - voice action completed, calling onResultReady and speakResponse")
        onResultReady(resultMessage)
        speakResponse(resultMessage)
    }

    /** Called by MainActivity to indicate a voice action is starting. */
    fun markVoiceActionPending() {
        Log.d(TAG, "DIAGNOSTIC: markVoiceActionPending() called")
        voiceActionPending = true
    }

    // SpeechRecognizerManager.RecognitionCallback implementation
    override fun onReadyForSpeech() {
        Log.d(TAG, "DIAGNOSTIC: onReadyForSpeech() called")
        // Already handled by UI state change
    }

    override fun onBeginningOfSpeech() {
        Log.d(TAG, "DIAGNOSTIC: onBeginningOfSpeech() called - USER STARTED SPEAKING")
        // User started speaking
    }

    override fun onPartialResults(partialText: String) {
        Log.d(TAG, "DIAGNOSTIC: onPartialResults() called with text = '$partialText'")
        uiController.setRecognizedText(partialText)
        onTextRecognized(partialText)
    }

    override fun onResults(finalText: String) {
        Log.d(TAG, "DIAGNOSTIC: onResults() called with finalText = '$finalText'")
        uiController.setState(VoiceUIController.VoiceState.PROCESSING)
        val intent = intentParser.parse(finalText)
        pendingIntent = intent
        // Mark that a voice action is pending so reportVoiceResult knows to complete it
        voiceActionPending = true
        Log.d(TAG, "DIAGNOSTIC: onResults() - parsed intent = $intent, voiceActionPending = true")
        onIntentParsed(intent)
    }

    override fun onEndOfSpeech() {
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
        Log.w(TAG, "DIAGNOSTIC: onError() errorCode=$errorCode ($errorString), message='$errorMessage'")
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
    }

    fun speakResponse(text: String) {
        Log.d(TAG, "DIAGNOSTIC: speakResponse() called with text = '$text'")
        if (text.isBlank()) {
            Log.w(TAG, "DIAGNOSTIC: speakResponse() - text is blank, returning to IDLE")
            uiController.setState(VoiceUIController.VoiceState.IDLE)
            return
        }

        uiController.setState(VoiceUIController.VoiceState.SPEAKING)
        textToSpeechManager.speak(text, object : TextToSpeechManager.SpeakCallback {
            override fun onStart() {
                Log.d(TAG, "DIAGNOSTIC: TTS onStart()")
            }

            override fun onDone() {
                Log.d(TAG, "DIAGNOSTIC: TTS onDone() - returning to IDLE")
                uiController.setState(VoiceUIController.VoiceState.IDLE)
            }

            override fun onError(errorMessage: String) {
                Log.e(TAG, "DIAGNOSTIC: TTS onError() = $errorMessage")
                uiController.setState(VoiceUIController.VoiceState.IDLE)
            }
        })
    }

    fun executePendingIntent(resultMessage: String) {
        Log.d(TAG, "DIAGNOSTIC: executePendingIntent() called with resultMessage = '$resultMessage'")
        pendingIntent?.let { intent ->
            Log.d(TAG, "DIAGNOSTIC: executePendingIntent() - re-delivering pending intent = $intent")
            onIntentParsed(intent) // Re-deliver for execution
        }
        onResultReady(resultMessage)
        speakResponse(resultMessage)
    }

    fun cancelAll() {
        Log.d(TAG, "DIAGNOSTIC: cancelAll() called")
        speechRecognizerManager.cancel()
        textToSpeechManager.stop()
        voiceActionPending = false
        uiController.setState(VoiceUIController.VoiceState.IDLE)
    }
}