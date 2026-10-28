package com.example.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import java.io.File

private const val TAG = "AndroidAudioPlayer"

/**
 * Android-native implementation of AudioPlayer using MediaPlayer.
 * Handles playback, seeking, pause/resume, and proper resource release.
 */
class AndroidAudioPlayer(
    private val context: Context
) : AudioPlayer {

    private var player: MediaPlayer? = null
    private var onCompletionCallback: (() -> Unit)? = null

    override val isPlaying: Boolean
        get() = try {
            player?.isPlaying == true
        } catch (e: Exception) {
            false
        }

    override val currentPosition: Int
        get() = try {
            player?.currentPosition ?: 0
        } catch (e: Exception) {
            0
        }

    override val duration: Int
        get() = try {
            player?.duration ?: 0
        } catch (e: Exception) {
            0
        }

    override fun play(
        file: File,
        onCompletion: () -> Unit,
        onError: (String) -> Unit
    ): Result<Unit> {
        return try {
            stop()

            if (!file.exists() || file.length() == 0L) {
                onError("Audio file does not exist or is empty")
                return Result.failure(IllegalArgumentException("File does not exist or is empty"))
            }

            onCompletionCallback = onCompletion

            // Ensure system media volume is audible
            try {
                val audioManager = context.getSystemService(android.media.AudioManager::class.java)
                    ?: (context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager)
                audioManager?.let { am ->
                    val currentVol = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                    val maxVol = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                    if (currentVol == 0 && maxVol > 0) {
                        am.setStreamVolume(
                            android.media.AudioManager.STREAM_MUSIC,
                            (maxVol * 0.8).toInt().coerceAtLeast(1),
                            0
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not check or adjust media volume", e)
            }

            player = MediaPlayer().apply {
                val attributes = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setLegacyStreamType(android.media.AudioManager.STREAM_MUSIC)
                    .build()
                setAudioAttributes(attributes)
                setDataSource(file.absolutePath)
                setVolume(1.0f, 1.0f)
                setOnCompletionListener {
                    stop()
                    onCompletionCallback?.invoke()
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error: what=$what, extra=$extra")
                    stop()
                    onError("Playback error ($what, $extra)")
                    true
                }
                prepare()
                start()
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start playback", e)
            onError(e.localizedMessage ?: "Failed to play audio")
            stop()
            Result.failure(e)
        }
    }

    override fun pause() {
        try {
            if (player?.isPlaying == true) {
                player?.pause()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pause playback", e)
        }
    }

    override fun resume() {
        try {
            player?.start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resume playback", e)
        }
    }

    override fun stop() {
        try {
            player?.let { p ->
                try {
                    if (p.isPlaying) {
                        p.stop()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Exception during player.stop()", e)
                }
                try {
                    p.reset()
                } catch (e: Exception) {
                    Log.w(TAG, "Exception during player.reset()", e)
                }
                try {
                    p.release()
                } catch (e: Exception) {
                    Log.w(TAG, "Exception during player.release()", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop/release MediaPlayer", e)
        } finally {
            player = null
            onCompletionCallback = null
        }
    }

    override fun seekTo(positionMs: Int) {
        try {
            player?.seekTo(positionMs)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to seek MediaPlayer", e)
        }
    }

    override fun release() {
        stop()
    }
}
