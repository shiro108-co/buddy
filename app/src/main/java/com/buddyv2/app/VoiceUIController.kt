package com.buddyv2.app

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat

class VoiceUIController(
    private val context: Context,
    private val micButton: ImageButton,
    private val statusText: TextView,
    private val commandText: TextView,
    private val commandInput: android.widget.EditText
) {
    enum class VoiceState { IDLE, LISTENING, PROCESSING, SPEAKING }
    private var state = VoiceState.IDLE
    private var animator: ObjectAnimator? = null
    fun getState() = state
    fun setState(next: VoiceState) {
        state = next
        animator?.cancel()
        micButton.scaleX = 1f
        micButton.scaleY = 1f
        micButton.rotation = 0f
        when (next) {
            VoiceState.IDLE -> {
                micButton.setImageResource(android.R.drawable.ic_btn_speak_now)
                micButton.setColorFilter(ContextCompat.getColor(context, R.color.primary))
                micButton.contentDescription = "Hold to speak"
                statusText.text = "Hold to speak"
                commandInput.hint = context.getString(R.string.type_command_hint)
            }
            VoiceState.LISTENING -> {
                micButton.setImageResource(android.R.drawable.ic_media_pause)
                micButton.setColorFilter(ContextCompat.getColor(context, R.color.listening_red))
                statusText.text = "Listening…"
                commandInput.hint = "Release to process"
                animator = ObjectAnimator.ofFloat(micButton, "scaleX", 1f, 1.15f).apply {
                    duration = 650; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE; start()
                }
            }
            VoiceState.PROCESSING -> {
                micButton.setImageResource(android.R.drawable.ic_menu_rotate)
                micButton.setColorFilter(ContextCompat.getColor(context, R.color.processing_blue))
                statusText.text = "Processing…"
                animator = ObjectAnimator.ofFloat(micButton, "rotation", 0f, 360f).apply {
                    duration = 900; repeatCount = ValueAnimator.INFINITE; start()
                }
            }
            VoiceState.SPEAKING -> {
                micButton.setImageResource(android.R.drawable.ic_media_play)
                micButton.setColorFilter(ContextCompat.getColor(context, R.color.speaking_green))
                statusText.text = "Speaking…"
            }
        }
    }
    fun setRecognizedText(text: String) {
        commandText.text = text
        commandText.visibility = View.VISIBLE
    }
    fun cleanup() { animator?.cancel(); animator = null }
}