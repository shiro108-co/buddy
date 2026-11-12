package com.buddyv2.app

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Manages Android's TextToSpeech with queue management to prevent overlapping speech.
 */
class TextToSpeechManager(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var isSpeaking = false
    private val utteranceQueue = ConcurrentLinkedQueue<Utterance>()
    private var currentUtteranceId = 0
    private var activeUtterance: Utterance? = null

    private var onInitListener: ((Boolean) -> Unit)? = null

    companion object {
        private const val TAG = "TextToSpeechManager"
    }

    interface SpeakCallback {
        fun onStart()
        fun onDone()
        fun onError(errorMessage: String)
    }

    data class Utterance(
        val id: Int,
        val text: String,
        val callback: SpeakCallback?
    )

    fun initialize(onInit: (Boolean) -> Unit) {
        onInitListener = onInit
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val langResult = tts?.setLanguage(Locale.getDefault())
                if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "Language not supported")
                    isInitialized = false
                    onInitListener?.invoke(false)
                } else {
                    isInitialized = true
                    tts?.setOnUtteranceProgressListener(utteranceProgressListener)
                    // Set speech rate slightly slower for clarity
                    tts?.setSpeechRate(1.0f)
                    tts?.setPitch(1.0f)
                    Log.d(TAG, "TTS initialized successfully")
                    onInitListener?.invoke(true)
                    processQueue()
                }
            } else {
                Log.e(TAG, "TTS initialization failed")
                isInitialized = false
                onInitListener?.invoke(false)
            }
        }
    }

    fun speak(text: String, callback: SpeakCallback? = null) {
        if (text.isBlank()) {
            callback?.onError("Empty text")
            return
        }

        if (!isInitialized) {
            callback?.onError("TTS not initialized")
            return
        }

        val utterance = Utterance(currentUtteranceId++, text, callback)
        utteranceQueue.add(utterance)
        processQueue()
    }

    fun stop() {
        tts?.stop()
        activeUtterance?.callback?.onError("Speech stopped")
        activeUtterance = null
        utteranceQueue.clear()
        isSpeaking = false
        Log.d(TAG, "TTS stopped and queue cleared")
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
        Log.d(TAG, "TTS shutdown")
    }

    val isTtsSpeaking: Boolean
        get() = isSpeaking

    val isTtsInitialized: Boolean
        get() = isInitialized

    private fun processQueue() {
        if (isSpeaking || utteranceQueue.isEmpty() || !isInitialized) return

        val utterance = utteranceQueue.poll() ?: return
        isSpeaking = true

        activeUtterance = utterance
        val utteranceId = "buddy_${utterance.id}"
        val params: Bundle? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId) }
        } else {
            null
        }

        utterance.callback?.onStart()

        val result = tts?.speak(utterance.text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        if (result != TextToSpeech.SUCCESS) {
            Log.e(TAG, "TTS speak failed: $result")
            isSpeaking = false
            activeUtterance = null
            utterance.callback?.onError("TTS speak failed: $result")
            processQueue()
        }
    }

    private fun finishUtterance(utteranceId: String, error: String?) {
        val finished = activeUtterance ?: return
        if (utteranceId != "buddy_${finished.id}") return
        activeUtterance = null
        isSpeaking = false
        if (error == null) finished.callback?.onDone() else finished.callback?.onError(error)
        processQueue()
    }

    private val utteranceProgressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) {
            Log.d(TAG, "Utterance started: $utteranceId")
        }

        override fun onDone(utteranceId: String) {
            Log.d(TAG, "Utterance done: $utteranceId")
            finishUtterance(utteranceId, null)
        }

        override fun onError(utteranceId: String) {
            Log.e(TAG, "Utterance error: $utteranceId")
            finishUtterance(utteranceId, "Text-to-speech failed")
        }

        override fun onError(utteranceId: String, errorCode: Int) {
            Log.e(TAG, "Utterance error: $utteranceId code=$errorCode")
            finishUtterance(utteranceId, "Text-to-speech failed ($errorCode)")
        }
    }
}