package com.buddyv2.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent

/**
 * Manages Android's SpeechRecognizer with proper lifecycle handling.
 * Provides callbacks for recognition events and partial results.
 * Includes comprehensive diagnostic logging for troubleshooting.
 */
class SpeechRecognizerManager(
    private val context: Context,
    private val listener: RecognitionCallback,
    private val diagnosticLogger: DiagnosticLogger
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
        // DIAGNOSTIC: Check RECORD_AUDIO permission at runtime
        val hasRecordPermission = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        diagnosticLogger.log("DIAGNOSTIC: RECORD_AUDIO permission granted = $hasRecordPermission")
        Log.d(TAG, "DIAGNOSTIC: RECORD_AUDIO permission granted = $hasRecordPermission")

        // DIAGNOSTIC: Check if SpeechRecognizer is available
        val isAvailable = SpeechRecognizer.isRecognitionAvailable(context)
        diagnosticLogger.log("DIAGNOSTIC: SpeechRecognizer.isRecognitionAvailable() = $isAvailable")
        Log.d(TAG, "DIAGNOSTIC: SpeechRecognizer.isRecognitionAvailable() = $isAvailable")

        // DIAGNOSTIC: Check recognition service info
        if (isAvailable) {
            val service = SpeechRecognizer.createSpeechRecognizer(context)
            diagnosticLogger.log("DIAGNOSTIC: SpeechRecognizer created successfully")
            Log.d(TAG, "DIAGNOSTIC: SpeechRecognizer created successfully")
            service.destroy()
        }

        if (isAvailable) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            speechRecognizer?.setRecognitionListener(recognitionListener)
            diagnosticLogger.log("SpeechRecognizer initialized")
            Log.d(TAG, "SpeechRecognizer initialized")
        } else {
            diagnosticLogger.log("SpeechRecognizer not available on this device")
            Log.w(TAG, "SpeechRecognizer not available on this device")
            listener.onError(SpeechRecognizer.ERROR_RECOGNIZER_BUSY, "Speech recognition not available")
        }
    }

    fun startListening() {
        if (isListening) {
            diagnosticLogger.log("Already listening")
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

            // DIAGNOSTIC: Log recognition language configuration
            val language = java.util.Locale.getDefault().toLanguageTag()
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, true)
            diagnosticLogger.log("DIAGNOSTIC: Recognition language set to: $language")
            Log.d(TAG, "DIAGNOSTIC: Recognition language set to: $language")
        }

        hasStartedSpeaking = false
        isListening = true

        // DIAGNOSTIC: Log intent extras
        diagnosticLogger.log("DIAGNOSTIC: Starting recognition with extras:")
        diagnosticLogger.log("  EXTRA_LANGUAGE_MODEL = ${RecognizerIntent.LANGUAGE_MODEL_FREE_FORM}")
        diagnosticLogger.log("  EXTRA_PARTIAL_RESULTS = true")
        diagnosticLogger.log("  EXTRA_MAX_RESULTS = 1")
        diagnosticLogger.log("  EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS = 1000")
        diagnosticLogger.log("  EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS = 1500")
        diagnosticLogger.log("  EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS = 500")
        diagnosticLogger.log("  EXTRA_LANGUAGE = ${java.util.Locale.getDefault().toLanguageTag()}")
        diagnosticLogger.log("  EXTRA_CALLING_PACKAGE = ${context.packageName}")

        Log.d(TAG, "DIAGNOSTIC: Starting recognition with extras:")
        Log.d(TAG, "  EXTRA_LANGUAGE_MODEL = ${RecognizerIntent.LANGUAGE_MODEL_FREE_FORM}")
        Log.d(TAG, "  EXTRA_PARTIAL_RESULTS = true")
        Log.d(TAG, "  EXTRA_MAX_RESULTS = 1")
        Log.d(TAG, "  EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS = 1000")
        Log.d(TAG, "  EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS = 1500")
        Log.d(TAG, "  EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS = 500")
        Log.d(TAG, "  EXTRA_LANGUAGE = ${java.util.Locale.getDefault().toLanguageTag()}")
        Log.d(TAG, "  EXTRA_CALLING_PACKAGE = ${context.packageName}")

        try {
            recognizer.startListening(intent)
            diagnosticLogger.log("Started listening - recognizer.startListening() called")
            Log.d(TAG, "Started listening - recognizer.startListening() called")
        } catch (e: Exception) {
            diagnosticLogger.log("Failed to start listening: ${e.message}")
            Log.e(TAG, "Failed to start listening", e)
            isListening = false
            listener.onError(SpeechRecognizer.ERROR_RECOGNIZER_BUSY, e.message ?: "Failed to start recognition")
        }
    }

    fun stopListening() {
        if (!isListening) return

        diagnosticLogger.log("DIAGNOSTIC: stopListening() called")
        Log.d(TAG, "DIAGNOSTIC: stopListening() called")
        speechRecognizer?.stopListening()
        isListening = false
        diagnosticLogger.log("Stopped listening")
        Log.d(TAG, "Stopped listening")
    }

    fun cancel() {
        if (!isListening) return

        diagnosticLogger.log("DIAGNOSTIC: cancel() called - recognizer.cancel()")
        Log.d(TAG, "DIAGNOSTIC: cancel() called - recognizer.cancel()")
        speechRecognizer?.cancel()
        isListening = false
        diagnosticLogger.log("Cancelled recognition")
        Log.d(TAG, "Cancelled recognition")
    }

    fun destroy() {
        diagnosticLogger.log("DIAGNOSTIC: destroy() called")
        Log.d(TAG, "DIAGNOSTIC: destroy() called")
        cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
        diagnosticLogger.log("SpeechRecognizer destroyed")
        Log.d(TAG, "SpeechRecognizer destroyed")
    }

    val isCurrentlyListening: Boolean
        get() = isListening

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            diagnosticLogger.log("DIAGNOSTIC: onReadyForSpeech() called")
            if (params != null) {
                diagnosticLogger.log("  params = $params")
            }
            Log.d(TAG, "DIAGNOSTIC: onReadyForSpeech() called")
            if (params != null) {
                Log.d(TAG, "  params = $params")
            }
            hasStartedSpeaking = false
            listener.onReadyForSpeech()
        }

        override fun onBeginningOfSpeech() {
            diagnosticLogger.log("DIAGNOSTIC: onBeginningOfSpeech() called - USER STARTED SPEAKING")
            Log.d(TAG, "DIAGNOSTIC: onBeginningOfSpeech() called - USER STARTED SPEAKING")
            hasStartedSpeaking = true
            listener.onBeginningOfSpeech()
        }

        override fun onRmsChanged(rmsdB: Float) {
            // DIAGNOSTIC: Log RMS (audio level) changes - shows microphone is capturing audio
            diagnosticLogger.log("DIAGNOSTIC: onRmsChanged() rmsdB = $rmsdB")
            Log.d(TAG, "DIAGNOSTIC: onRmsChanged() rmsdB = $rmsdB")
            // Optional: could expose volume level for UI visualization
        }

        override fun onBufferReceived(buffer: ByteArray?) {
            diagnosticLogger.log("DIAGNOSTIC: onBufferReceived() buffer size = ${buffer?.size ?: 0}")
            Log.d(TAG, "DIAGNOSTIC: onBufferReceived() buffer size = ${buffer?.size ?: 0}")
        }

        override fun onEndOfSpeech() {
            diagnosticLogger.log("DIAGNOSTIC: onEndOfSpeech() called - SPEECH ENDED, waiting for results")
            Log.d(TAG, "DIAGNOSTIC: onEndOfSpeech() called - SPEECH ENDED, waiting for results")
            listener.onEndOfSpeech()
        }

        override fun onError(errorCode: Int) {
            val errorString = errorCodeToString(errorCode)
            diagnosticLogger.log("DIAGNOSTIC: onError() errorCode=$errorCode ($errorString)")
            Log.w(TAG, "DIAGNOSTIC: onError() errorCode=$errorCode ($errorString)")
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
            diagnosticLogger.log("DIAGNOSTIC: onResults() called")
            Log.d(TAG, "DIAGNOSTIC: onResults() called")
            if (results != null) {
                diagnosticLogger.log("  results keys = ${results.keySet()}")
                Log.d(TAG, "  results keys = ${results.keySet()}")
            }
            isListening = false
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            diagnosticLogger.log("DIAGNOSTIC: onResults() matches = $matches")
            Log.d(TAG, "DIAGNOSTIC: onResults() matches = $matches")
            matches?.firstOrNull()?.let { text ->
                if (text.isNotBlank()) {
                    diagnosticLogger.log("DIAGNOSTIC: onResults() recognized text = '$text'")
                    Log.d(TAG, "DIAGNOSTIC: onResults() recognized text = '$text'")
                    listener.onResults(text)
                } else {
                    diagnosticLogger.log("DIAGNOSTIC: onResults() empty text, sending NO_MATCH")
                    Log.w(TAG, "DIAGNOSTIC: onResults() empty text, sending NO_MATCH")
                    listener.onError(SpeechRecognizer.ERROR_NO_MATCH, "Empty recognition result")
                }
            } ?: run {
                diagnosticLogger.log("DIAGNOSTIC: onResults() no matches, sending NO_MATCH")
                Log.w(TAG, "DIAGNOSTIC: onResults() no matches, sending NO_MATCH")
                listener.onError(SpeechRecognizer.ERROR_NO_MATCH, "No recognition results")
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            diagnosticLogger.log("DIAGNOSTIC: onPartialResults() called")
            Log.d(TAG, "DIAGNOSTIC: onPartialResults() called")
            if (partialResults != null) {
                diagnosticLogger.log("  partialResults keys = ${partialResults.keySet()}")
                Log.d(TAG, "  partialResults keys = ${partialResults.keySet()}")
            }
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            diagnosticLogger.log("DIAGNOSTIC: onPartialResults() matches = $matches")
            Log.d(TAG, "DIAGNOSTIC: onPartialResults() matches = $matches")
            matches?.firstOrNull()?.let { text ->
                if (text.isNotBlank()) {
                    diagnosticLogger.log("DIAGNOSTIC: onPartialResults() partial text = '$text'")
                    Log.d(TAG, "DIAGNOSTIC: onPartialResults() partial text = '$text'")
                    listener.onPartialResults(text)
                }
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {
            diagnosticLogger.log("DIAGNOSTIC: onEvent() eventType=$eventType, params=$params")
            Log.d(TAG, "DIAGNOSTIC: onEvent() eventType=$eventType, params=$params")
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