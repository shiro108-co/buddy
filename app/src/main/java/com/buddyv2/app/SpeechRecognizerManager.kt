package com.buddyv2.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent

/**
 * Manages Android's SpeechRecognizer with proper lifecycle handling.
 * Provides callbacks for recognition events and partial results.
 */
class SpeechRecognizerManager(
    private val context: Context,
    private val listener: RecognitionCallback
) : LifecycleObserver {

    interface RecognitionCallback {
        fun onReadyForSpeech()
        fun onBeginningOfSpeech()
        fun onPartialResults(partialText: String)
        fun onResults(finalText: String)
        fun onError(errorCode: Int, errorMessage: String)
        fun onEndOfSpeech()
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var hasStartedSpeaking = false

    companion object {
        private const val TAG = "SpeechRecognizerManager"
    }

    fun initialize() {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            speechRecognizer?.setRecognitionListener(recognitionListener)
            Log.d(TAG, "SpeechRecognizer initialized")
        } else {
            Log.w(TAG, "SpeechRecognizer not available on this device")
            listener.onError(SpeechRecognizer.ERROR_RECOGNIZER_BUSY, "Speech recognition not available")
        }
    }

    fun startListening() {
        if (isListening) {
            Log.w(TAG, "Already listening")
            return
        }

        val recognizer = speechRecognizer ?: return

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Reduced from 10000ms to 1000ms - allows short commands like "Open WhatsApp" to complete
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1000)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 500)
        }

        hasStartedSpeaking = false
        isListening = true

        try {
            recognizer.startListening(intent)
            Log.d(TAG, "Started listening")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start listening", e)
            isListening = false
            listener.onError(SpeechRecognizer.ERROR_RECOGNIZER_BUSY, e.message ?: "Failed to start recognition")
        }
    }

    fun stopListening() {
        if (!isListening) return

        speechRecognizer?.stopListening()
        isListening = false
        Log.d(TAG, "Stopped listening")
    }

    fun cancel() {
        if (!isListening) return

        speechRecognizer?.cancel()
        isListening = false
        Log.d(TAG, "Cancelled recognition")
    }

    fun destroy() {
        cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
        Log.d(TAG, "SpeechRecognizer destroyed")
    }

    val isCurrentlyListening: Boolean
        get() = isListening

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "onReadyForSpeech")
            hasStartedSpeaking = false
            listener.onReadyForSpeech()
        }

        override fun onBeginningOfSpeech() {
            Log.d(TAG, "onBeginningOfSpeech")
            hasStartedSpeaking = true
            listener.onBeginningOfSpeech()
        }

        override fun onRmsChanged(rmsdB: Float) {
            // Optional: could expose volume level for UI visualization
        }

        override fun onBufferReceived(buffer: ByteArray?) {
            // Not used
        }

        override fun onEndOfSpeech() {
            Log.d(TAG, "onEndOfSpeech")
            listener.onEndOfSpeech()
        }

        override fun onError(errorCode: Int) {
            Log.w(TAG, "onError: $errorCode - ${errorCodeToString(errorCode)}")
            isListening = false
            val errorMessage = when (errorCode) {
                SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                SpeechRecognizer.ERROR_CLIENT -> "Client error"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission denied"
                SpeechRecognizer.ERROR_NETWORK -> "Network error"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognition service busy"
                SpeechRecognizer.ERROR_SERVER -> "Server error"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
                else -> "Unknown error ($errorCode)"
            }
            listener.onError(errorCode, errorMessage)
        }

        override fun onResults(results: Bundle?) {
            Log.d(TAG, "onResults")
            isListening = false
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            matches?.firstOrNull()?.let { text ->
                if (text.isNotBlank()) {
                    listener.onResults(text)
                } else {
                    listener.onError(SpeechRecognizer.ERROR_NO_MATCH, "Empty recognition result")
                }
            } ?: run {
                listener.onError(SpeechRecognizer.ERROR_NO_MATCH, "No recognition results")
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            matches?.firstOrNull()?.let { text ->
                if (text.isNotBlank()) {
                    listener.onPartialResults(text)
                }
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {
            // Not used
        }
    }

    private fun errorCodeToString(code: Int): String {
        return when (code) {
            SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
            SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
            SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
            SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
            SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
            else -> "ERROR_UNKNOWN($code)"
        }
    }
}