package com.example.audio

import java.io.File

/**
 * Interface defining audio playback capabilities.
 */
interface AudioPlayer {
    fun play(
        file: File,
        onCompletion: () -> Unit = {},
        onError: (String) -> Unit = {}
    ): Result<Unit>
    fun pause()
    fun resume()
    fun stop()
    fun seekTo(positionMs: Int)
    val isPlaying: Boolean
    val currentPosition: Int
    val duration: Int
    fun release()
}
