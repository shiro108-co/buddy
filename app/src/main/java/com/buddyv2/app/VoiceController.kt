package com.buddyv2.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent

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
    private val speechRecognizerManager = SpeechRecognizerManager(activity, this, diagnosticLogger)
    private val textToSpeechManager = TextToSpeechManager(activity)
    private var isMicPermissionGranted = false
    private var voiceActionPending = false
    private var isVoiceInputActive = false

    @OnLifecycleEvent(Lifecycle.Event.ON_CREATE)
    fun onCreate() {
        checkMicPermission()
        textToSpeechManager.initialize { }
        speechRecognizerManager.initialize()
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    fun onDestroy() {
        speechRecognizerManager.destroy()
        textToSpeechManager.shutdown()
    }

    private fun checkMicPermission() {
        isMicPermissionGranted = ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!isMicPermissionGranted) ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.RECORD_AUDIO), 200)
    }

    fun onRequestPermissionsResult(requestCode: Int, grantResults: IntArray) {
        if (requestCode == 200) {
            isMicPermissionGranted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
            if (!isMicPermissionGranted) onError("Microphone permission is required for voice input")
        }
    }

    fun startVoiceInput() {
        if (uiController.getState() != VoiceUIController.VoiceState.IDLE || isVoiceInputActive) return
        isVoiceInputActive = true
        if (!isMicPermissionGranted) {
            checkMicPermission()
            isVoiceInputActive = false
            return
        }
        if (textToSpeechManager.isTtsSpeaking) textToSpeechManager.stop()
        uiController.setState(VoiceUIController.VoiceState.LISTENING)
        speechRecognizerManager.startListening()
    }

    fun stopVoiceInput() {
        if (!isVoiceInputActive) return
        isVoiceInputActive = false
        speechRecognizerManager.stopListening()
        uiController.setState(VoiceUIController.VoiceState.PROCESSING)
    }

    fun cancelVoiceInput() {
        if (!isVoiceInputActive) return
        isVoiceInputActive = false
        speechRecognizerManager.cancel()
        uiController.setState(VoiceUIController.VoiceState.IDLE)
        voiceActionPending = false
    }

    fun reportVoiceResult(message: String) {
        if (!voiceActionPending) return
        voiceActionPending = false
        onResultReady(message)
        speakResponse(message)
    }

    override fun onReadyForSpeech() {}
    override fun onRecognitionStarted() {}
    override fun onBeginningOfSpeech() {}
    override fun onPartialResults(partialText: String) {
        uiController.setRecognizedText(partialText)
        onTextRecognized(partialText)
    }
    override fun onResults(finalText: String) {
        uiController.setState(VoiceUIController.VoiceState.PROCESSING)
        voiceActionPending = true
        onTextRecognized(finalText)
        onIntentParsed(intentParser.parse(finalText))
    }
    override fun onEndOfSpeech() {}
    override fun onError(errorCode: Int, errorMessage: String) {
        isVoiceInputActive = false
        voiceActionPending = false
        uiController.setState(VoiceUIController.VoiceState.IDLE)
        onError(when (errorCode) {
            SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected. Try again."
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timed out. Try again."
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission denied"
            else -> errorMessage
        })
    }

    fun stopSpeaking() {
        textToSpeechManager.stop()
        uiController.setState(VoiceUIController.VoiceState.IDLE)
    }

    private fun speakResponse(text: String) {
        if (text.isBlank()) {
            uiController.setState(VoiceUIController.VoiceState.IDLE)
            return
        }
        uiController.setState(VoiceUIController.VoiceState.SPEAKING)
        textToSpeechManager.speak(text, object : TextToSpeechManager.SpeakCallback {
            override fun onStart() {}
            override fun onDone() { uiController.setState(VoiceUIController.VoiceState.IDLE) }
            override fun onError(errorMessage: String) { uiController.setState(VoiceUIController.VoiceState.IDLE) }
        })
    }

    fun cancelAll() {
        speechRecognizerManager.cancel()
        textToSpeechManager.stop()
        isVoiceInputActive = false
        voiceActionPending = false
        uiController.setState(VoiceUIController.VoiceState.IDLE)
    }
}