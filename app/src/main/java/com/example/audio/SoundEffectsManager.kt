package com.example.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.sin

/**
 * Manages tactile and acoustic feedback for voice recording interactions,
 * notably WhatsApp-style push-to-talk start, finish/save, and cancel sound cues.
 *
 * Sound cues are synthesized mathematically into PCM audio buffers, providing
 * zero-latency, asset-free sonification that runs reliably across all devices.
 */
class SoundEffectsManager(private val context: Context) {

    private val executor = Executors.newSingleThreadExecutor()

    private val vibrator: Vibrator? by lazy {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (e: Exception) {
            null
        }
    }

    // Pre-synthesized PCM buffers for zero-latency playback
    private val startPcmData: ShortArray by lazy {
        synthesizeDualTone(freq1 = 580.0, durationMs1 = 55, freq2 = 880.0, durationMs2 = 65)
    }

    private val stopPcmData: ShortArray by lazy {
        synthesizeDualTone(freq1 = 720.0, durationMs1 = 45, freq2 = 1120.0, durationMs2 = 75)
    }

    private val cancelPcmData: ShortArray by lazy {
        synthesizeSlideTone(startFreq = 850.0, endFreq = 420.0, durationMs = 80)
    }

    /**
     * Plays the push-to-talk start sound cue (ascending chirp) and fires subtle haptic feedback.
     */
    fun playStartSound() {
        vibrate(durationMs = 35, amplitude = 120)
        executor.execute {
            playPcmBuffer(startPcmData)
        }
    }

    /**
     * Plays the voice note sent/saved confirmation sound cue (cheerful pop chime) and haptic feedback.
     */
    fun playStopSound() {
        vibrate(durationMs = 30, amplitude = 140)
        executor.execute {
            playPcmBuffer(stopPcmData)
        }
    }

    /**
     * Plays the discard/cancel sound cue (descending soft tick).
     */
    fun playCancelSound() {
        vibrate(durationMs = 25, amplitude = 80)
        executor.execute {
            playPcmBuffer(cancelPcmData)
        }
    }

    private fun vibrate(durationMs: Long, amplitude: Int = 100) {
        try {
            val v = vibrator ?: return
            if (!v.hasVibrator()) return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val clampedAmp = amplitude.coerceIn(1, 255)
                val effect = VibrationEffect.createOneShot(durationMs, clampedAmp)
                v.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(durationMs)
            }
        } catch (e: Exception) {
            // Ignore vibration failures if permission or hardware is unavailable
        }
    }

    private fun playPcmBuffer(pcmData: ShortArray) {
        var track: AudioTrack? = null
        try {
            val sampleRate = 44100
            val bufferSize = pcmData.size * 2

            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()

            track = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            track.write(pcmData, 0, pcmData.size)
            track.play()

            // Wait until static buffer completes playback before releasing
            val playTimeMs = (pcmData.size.toDouble() / sampleRate * 1000).toLong() + 30
            Thread.sleep(playTimeMs)
        } catch (e: Exception) {
            Log.d("SoundEffectsManager", "Sonification playback skipped: ${e.message}")
        } finally {
            try {
                track?.stop()
                track?.release()
            } catch (e: Exception) {
                // Ignore cleanup errors
            }
        }
    }

    companion object {
        private const val SAMPLE_RATE = 44100
        private const val MAX_AMPLITUDE = 22000.0 // Keep volume pleasant, avoiding distortion

        private fun synthesizeDualTone(
            freq1: Double,
            durationMs1: Int,
            freq2: Double,
            durationMs2: Int
        ): ShortArray {
            val totalMs = durationMs1 + durationMs2
            val totalSamples = (SAMPLE_RATE * totalMs / 1000.0).toInt()
            val samplesPart1 = (SAMPLE_RATE * durationMs1 / 1000.0).toInt()
            val result = ShortArray(totalSamples)

            // Part 1
            for (i in 0 until samplesPart1) {
                val t = i.toDouble() / SAMPLE_RATE
                val envelope = envelope(i, samplesPart1)
                val sampleVal = (sin(2.0 * PI * freq1 * t) * MAX_AMPLITUDE * envelope).toInt()
                result[i] = sampleVal.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }

            // Part 2
            val samplesPart2 = totalSamples - samplesPart1
            for (i in 0 until samplesPart2) {
                val t = i.toDouble() / SAMPLE_RATE
                val envelope = envelope(i, samplesPart2)
                val sampleVal = (sin(2.0 * PI * freq2 * t) * MAX_AMPLITUDE * envelope).toInt()
                result[samplesPart1 + i] = sampleVal.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }

            return result
        }

        private fun synthesizeSlideTone(
            startFreq: Double,
            endFreq: Double,
            durationMs: Int
        ): ShortArray {
            val totalSamples = (SAMPLE_RATE * durationMs / 1000.0).toInt()
            val result = ShortArray(totalSamples)

            var phase = 0.0
            for (i in 0 until totalSamples) {
                val progress = i.toDouble() / totalSamples
                val currentFreq = startFreq + (endFreq - startFreq) * progress
                val phaseIncrement = 2.0 * PI * currentFreq / SAMPLE_RATE
                phase += phaseIncrement

                val envelope = envelope(i, totalSamples)
                val sampleVal = (sin(phase) * (MAX_AMPLITUDE * 0.8) * envelope).toInt()
                result[i] = sampleVal.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }

            return result
        }

        /**
         * Smooth attack and decay envelope to eliminate audible pops/clicks at audio boundaries.
         */
        private fun envelope(index: Int, total: Int): Double {
            val fadeSamples = (total * 0.15).toInt().coerceAtLeast(1)
            return when {
                index < fadeSamples -> index.toDouble() / fadeSamples
                index > total - fadeSamples -> (total - index).toDouble() / fadeSamples
                else -> 1.0
            }
        }
    }
}
