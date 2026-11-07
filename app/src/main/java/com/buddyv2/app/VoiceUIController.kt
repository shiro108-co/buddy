package com.buddyv2.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.drawable.Animatable
import android.util.Log
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * Manages voice UI states: idle, listening, processing, speaking.
 * Controls microphone button animation and status text updates.
 */
class VoiceUIController(
    private val context: Context,
    private val micButton: ImageButton,
    private val statusText: TextView,
    private val commandText: TextView,
    private val commandInput: android.widget.EditText
) {

    private var currentState = VoiceState.IDLE
    private var pulseAnimator: ObjectAnimator? = null
    private var waveAnimator: ValueAnimator? = null

    companion object {
        private const val TAG = "VoiceUIController"
    }

    enum class VoiceState {
        IDLE,        // Ready to start
        LISTENING,   // Actively recording speech
        PROCESSING,  // Speech ended, processing/understanding
        SPEAKING     // TTS is speaking response
    }

    fun setState(state: VoiceState) {
        if (currentState == state) return
        Log.d(TAG, "State change: $currentState -> $state")
        currentState = state
        applyState(state)
    }

    fun getState(): VoiceState = currentState

    private fun applyState(state: VoiceState) {
        when (state) {
            VoiceState.IDLE -> applyIdleState()
            VoiceState.LISTENING -> applyListeningState()
            VoiceState.PROCESSING -> applyProcessingState()
            VoiceState.SPEAKING -> applySpeakingState()
        }
    }

    private fun applyIdleState() {
        stopAnimations()
        micButton.setImageResource(android.R.drawable.ic_btn_speak_now)
        micButton.setColorFilter(ContextCompat.getColor(context, R.color.primary))
        micButton.contentDescription = context.getString(R.string.voice_start_listening)
        statusText.text = context.getString(R.string.tap_mic_to_speak)
        commandInput.hint = context.getString(R.string.type_or_speak_command)
    }

    private fun applyListeningState() {
        stopAnimations()
        micButton.setImageResource(android.R.drawable.ic_media_pause)
        micButton.setColorFilter(ContextCompat.getColor(context, R.color.listening_red))
        micButton.contentDescription = context.getString(R.string.voice_stop_listening)
        statusText.text = context.getString(R.string.listening_indicator)
        commandInput.hint = context.getString(R.string.speaking_hint)

        // Start pulse animation on mic button
        pulseAnimator = ObjectAnimator.ofFloat(micButton, "scaleX", 1f, 1.15f)
        pulseAnimator.duration = 800
        pulseAnimator.repeatCount = ValueAnimator.INFINITE
        pulseAnimator.repeatMode = ValueAnimator.REVERSE
        pulseAnimator.interpolator = AccelerateDecelerateInterpolator()
        pulseAnimator.addUpdateListener { animation ->
            val scale = animation.animatedValue as Float
            micButton.scaleY = scale
        }
        pulseAnimator.start()
    }

    private fun applyProcessingState() {
        stopAnimations()
        micButton.setImageResource(android.R.drawable.ic_menu_rotate)
        micButton.setColorFilter(ContextCompat.getColor(context, R.color.processing_blue))
        micButton.contentDescription = context.getString(R.string.voice_processing)
        statusText.text = context.getString(R.string.processing_indicator)

        // Rotation animation
        val rotateAnimator = ObjectAnimator.ofFloat(micButton, "rotation", 0f, 360f)
        rotateAnimator.duration = 1000
        rotateAnimator.repeatCount = ValueAnimator.INFINITE
        rotateAnimator.interpolator = AccelerateDecelerateInterpolator()
        rotateAnimator.start()
        pulseAnimator = rotateAnimator // reuse field for cleanup
    }

    private fun applySpeakingState() {
        stopAnimations()
        micButton.setImageResource(android.R.drawable.ic_media_play)
        micButton.setColorFilter(ContextCompat.getColor(context, R.color.speaking_green))
        micButton.contentDescription = context.getString(R.string.voice_stop_speaking)
        statusText.text = context.getString(R.string.speaking_indicator)

        // Gentle pulse while speaking
        pulseAnimator = ObjectAnimator.ofFloat(micButton, "alpha", 1f, 0.6f)
        pulseAnimator.duration = 1000
        pulseAnimator.repeatCount = ValueAnimator.INFINITE
        pulseAnimator.repeatMode = ValueAnimator.REVERSE
        pulseAnimator.interpolator = AccelerateDecelerateInterpolator()
        pulseAnimator.start()
    }

    fun setRecognizedText(text: String) {
        commandText.text = text
        commandText.visibility = View.VISIBLE
    }

    fun setResultText(text: String) {
        commandText.text = text
        commandText.visibility = View.VISIBLE
    }

    fun setErrorText(text: String) {
        commandText.text = text
        commandText.visibility = View.VISIBLE
    }

    private fun stopAnimations() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        waveAnimator?.cancel()
        waveAnimator = null
        micButton.clearAnimation()
        micButton.scaleX = 1f
        micButton.scaleY = 1f
        micButton.alpha = 1f
        micButton.rotation = 0f
    }

    fun cleanup() {
        stopAnimations()
    }
}