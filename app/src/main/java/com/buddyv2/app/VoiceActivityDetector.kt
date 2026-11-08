package com.buddyv2.app

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Voice Activity Detector for barge-in support.
 * Monitors microphone audio levels during TTS playback to detect user speech.
 * Uses simple energy-based detection with adaptive threshold.
 */
class VoiceActivityDetector(
    private val diagnosticLogger: DiagnosticLogger
) {

    companion object {
        private const val TAG = "VoiceActivityDetector"
        // Audio configuration for VAD
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        // Buffer size for ~100ms chunks at 16kHz
        private const val BUFFER_SIZE_MS = 100
        private const val BUFFER_SIZE = SAMPLE_RATE * BUFFER_SIZE_MS / 1000 * 2 // 2 bytes per sample

        // VAD thresholds (will be adapted based on ambient noise)
        private const val INITIAL_ENERGY_THRESHOLD = 1000.0
        private const val SPEECH_ENERGY_MULTIPLIER = 3.0 // Speech must be 3x ambient
        private const val MIN_SPEECH_DURATION_MS = 300 // Minimum speech duration to trigger
        private const val MAX_SILENCE_DURATION_MS = 800 // Max silence before ending speech segment
    }

    private var audioRecord: AudioRecord? = null
    private var isRunning = false
    private var executor: ExecutorService? = null

    // VAD state
    private var ambientEnergy = INITIAL_ENERGY_THRESHOLD
    private var speechEnergyThreshold = INITIAL_ENERGY_THRESHOLD * SPEECH_ENERGY_MULTIPLIER
    private var speechStartTime = 0L
    private var lastSpeechTime = 0L
    private var isInSpeechSegment = false
    private var framesSinceSpeechStart = 0

    // Callbacks
    private var onVoiceDetected: (() -> Unit)? = null
    private var onVoiceEnded: (() -> Unit)? = null

    interface VoiceDetectionCallback {
        fun onVoiceDetected()
        fun onVoiceEnded()
    }

    fun setCallback(callback: VoiceDetectionCallback) {
        onVoiceDetected = { callback.onVoiceDetected() }
        onVoiceEnded = { callback.onVoiceEnded() }
    }

    fun start() {
        if (isRunning) {
            diagnosticLogger.log("VAD: Already running")
            return
        }

        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT
        )
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            diagnosticLogger.log("VAD: Failed to get min buffer size")
            Log.e(TAG, "Failed to get min buffer size")
            return
        }

        val bufferSize = maxOf(minBufferSize, BUFFER_SIZE)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            diagnosticLogger.log("VAD: AudioRecord failed to initialize")
            Log.e(TAG, "AudioRecord failed to initialize")
            audioRecord?.release()
            audioRecord = null
            return
        }

        isRunning = true
        executor = Executors.newSingleThreadExecutor()

        // Reset VAD state
        ambientEnergy = INITIAL_ENERGY_THRESHOLD
        speechEnergyThreshold = INITIAL_ENERGY_THRESHOLD * SPEECH_ENERGY_MULTIPLIER
        isInSpeechSegment = false
        framesSinceSpeechStart = 0

        audioRecord?.startRecording()
        diagnosticLogger.log("VAD: Started voice activity detection")
        Log.d(TAG, "Started voice activity detection")

        executor?.execute { recordingLoop() }
    }

    fun stop() {
        if (!isRunning) return

        isRunning = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        executor?.shutdown()
        executor = null

        diagnosticLogger.log("VAD: Stopped voice activity detection")
        Log.d(TAG, "Stopped voice activity detection")
    }

    private fun recordingLoop() {
        val buffer = ShortArray(BUFFER_SIZE / 2)

        while (isRunning && audioRecord != null) {
            val readResult = audioRecord?.read(buffer, 0, buffer.size)

            if (readResult == null || readResult <= 0) {
                if (readResult == AudioRecord.ERROR_INVALID_OPERATION) {
                    // Recording stopped
                    break
                }
                Thread.sleep(10)
                continue
            }

            // Calculate RMS energy for this frame
            var sumSquares = 0L
            for (i in 0 until readResult) {
                val sample = buffer[i].toLong()
                sumSquares += sample * sample
            }
            val rms = Math.sqrt(sumSquares.toDouble() / readResult)

            // Adaptive threshold: slowly track ambient noise
            ambientEnergy = ambientEnergy * 0.99 + rms * 0.01
            speechEnergyThreshold = ambientEnergy * SPEECH_ENERGY_MULTIPLIER

            // Minimum threshold to avoid false positives from very quiet environments
            if (speechEnergyThreshold < INITIAL_ENERGY_THRESHOLD * SPEECH_ENERGY_MULTIPLIER) {
                speechEnergyThreshold = INITIAL_ENERGY_THRESHOLD * SPEECH_ENERGY_MULTIPLIER
            }

            val now = System.currentTimeMillis()
            val isSpeechFrame = rms > speechEnergyThreshold

            if (isSpeechFrame) {
                lastSpeechTime = now
                framesSinceSpeechStart++

                if (!isInSpeechSegment && framesSinceSpeechStart >= 3) { // ~300ms of speech
                    isInSpeechSegment = true
                    speechStartTime = now
                    diagnosticLogger.log("VAD: Voice detected (energy=$rms, threshold=$speechEnergyThreshold)")
                    Log.d(TAG, "Voice detected: energy=$rms, threshold=$speechEnergyThreshold")
                    onVoiceDetected?.invoke()
                }
            } else if (isInSpeechSegment) {
                // Check if silence duration exceeds max allowed
                val silenceDuration = now - lastSpeechTime
                if (silenceDuration > MAX_SILENCE_DURATION_MS) {
                    // Only trigger end if we had a minimum speech duration
                    val speechDuration = now - speechStartTime
                    if (speechDuration >= MIN_SPEECH_DURATION_MS) {
                        isInSpeechSegment = false
                        framesSinceSpeechStart = 0
                        diagnosticLogger.log("VAD: Voice segment ended (duration=${speechDuration}ms)")
                        Log.d(TAG, "Voice segment ended: duration=${speechDuration}ms")
                        onVoiceEnded?.invoke()
                    } else {
                        // Too short, treat as noise
                        isInSpeechSegment = false
                        framesSinceSpeechStart = 0
                    }
                }
            } else {
                // Not in speech, reset frame counter
                framesSinceSpeechStart = 0
            }
        }
    }

    fun isActive(): Boolean = isRunning
}