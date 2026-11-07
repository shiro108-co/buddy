package com.buddyv2.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent

/**
 * Main orchestrator for voice interaction.
 * Coordinates SpeechRecognizer, TTS, IntentParser, and UI.
 */
class VoiceController(
    private val activity: Activity,
    private val uiController: VoiceUIController,
    private val intentParser: IntentParser,
    private val onIntentParsed: (IntentParser.Intent) -> Unit,
    private val onTextRecognized: (String) -> Unit,
    private val onResultReady: (String) -> Unit,
    private val onError: (String) -> Unit
) : LifecycleObserver {

    private val speechRecognizerManager: SpeechRecognizerManager
    private val textToSpeechManager: TextToSpeechManager

    private var isMicPermissionGranted = false
    private var pendingIntent: IntentParser.Intent? = null

    companion object {
        private const val TAG = "VoiceController"
        private const val MIC_PERMISSION_REQUEST_CODE = 200
    }

    init {
        speechRecognizerManager = SpeechRecognizerManager(activity) { callback ->
            handleSpeechCallback(callback)
        }
        textToSpeechManager = TextToSpeechManager(activity)
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_CREATE)
    fun onCreate() {
        checkMicPermission()
        textToSpeechManager.initialize { success ->
            if (!success) {
                Log.w(TAG, "TTS initialization failed")
            }
        }
        speechRecognizerManager.initialize()
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    fun onDestroy() {
        speechRecognizerManager.destroy()
        textToSpeechManager.shutdown()
    }

    private fun checkMicPermission() {
        isMicPermissionGranted = ContextCompat.checkSelfPermission(
            activity, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

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
            if (isMicPermissionGranted) {
                Log.d(TAG, "Microphone permission granted")
            } else {
                Log.w(TAG, "Microphone permission denied")
                onError("Microphone permission is required for voice input")
            }
        }
    }

    fun onMicButtonClick() {
        when (uiController.getState()) {
            VoiceUIController.VoiceState.IDLE -> startListening()
            VoiceUIController.VoiceState.LISTENING -> stopListening()
            VoiceUIController.VoiceState.PROCESSING -> {
                // Ignore clicks while processing
            }
            VoiceUIController.VoiceState.SPEAKING -> stopSpeaking()
        }
    }

    private fun startListening() {
        if (!isMicPermissionGranted) {
            checkMicPermission()
            onError("Microphone permission required")
            return
        }

        if (speechRecognizerManager.isCurrentlyListening) {
            Log.w(TAG, "Already listening")
            return
        }

        // Stop any ongoing TTS
        textToSpeechManager.stop()

        uiController.setState(VoiceUIController.VoiceState.LISTENING)
        speechRecognizerManager.startListening()
    }

    private fun stopListening() {
        speechRecognizerManager.stopListening()
        uiController.setState(VoiceUIController.VoiceState.PROCESSING)
    }

    fun stopSpeaking() {
        textToSpeechManager.stop()
        uiController.setState(VoiceUIController.VoiceState.IDLE)
    }

    private fun handleSpeechCallback(callback: SpeechRecognizerManager.RecognitionCallback) {
        when (callback) {
            is SpeechRecognizerManager.RecognitionCallback.OnReadyForSpeech -> {
                // Already handled by UI state change
            }
            is SpeechRecognizerManager.RecognitionCallback.OnBeginningOfSpeech -> {
                // User started speaking
            }
            is SpeechRecognizerManager.RecognitionCallback.OnPartialResults -> {
                uiController.setRecognizedText(callback.partialText)
                onTextRecognized(callback.partialText)
            }
            is SpeechRecognizerManager.RecognitionCallback.OnResults -> {
                uiController.setState(VoiceUIController.VoiceState.PROCESSING)
                val intent = intentParser.parse(callback.finalText)
                pendingIntent = intent
                onIntentParsed(intent)
            }
            is SpeechRecognizerManager.RecognitionCallback.OnEndOfSpeech -> {
                // Speech ended, waiting for results
            }
            is SpeechRecognizerManager.RecognitionCallback.OnError -> {
                Log.w(TAG, "Speech error: ${callback.errorMessage}")
                uiController.setState(VoiceUIController.VoiceState.IDLE)
                when (callback.errorCode) {
                    SpeechRecognizerManager.ERROR_NO_MATCH -> onError("No speech detected. Try again.")
                    SpeechRecognizerManager.ERROR_SPEECH_TIMEOUT -> onError("Speech timed out. Try again.")
                    SpeechRecognizerManager.ERROR_INSUFFICIENT_PERMISSIONS -> {
                        isMicPermissionGranted = false
                        onError("Microphone permission denied")
                    }
                    else -> onError(callback.errorMessage)
                }
            }
        }
    }

    fun speakResponse(text: String) {
        if (text.isBlank()) {
            uiController.setState(VoiceUIController.VoiceState.IDLE)
            return
        }

        uiController.setState(VoiceUIController.VoiceState.SPEAKING)
        textToSpeechManager.speak(text, object : TextToSpeechManager.SpeakCallback {
            override fun onStart() {
                // Speaking started
            }

            override fun onDone() {
                uiController.setState(VoiceUIController.VoiceState.IDLE)
            }

            override fun onError(errorMessage: String) {
                Log.e(TAG, "TTS error: $errorMessage")
                uiController.setState(VoiceUIController.VoiceState.IDLE)
            }
        })
    }

    fun executePendingIntent(resultMessage: String) {
        pendingIntent?.let { intent ->
            onIntentParsed(intent) // Re-deliver for execution
        }
        onResultReady(resultMessage)
        speakResponse(resultMessage)
    }

    fun cancelAll() {
        speechRecognizerManager.cancel()
        textToSpeechManager.stop()
        uiController.setState(VoiceUIController.VoiceState.IDLE)
    }
}