package com.example.audio

import java.io.File

/**
 * Interface defining audio recording capabilities.
 * Supports start, pause, resume, stop, cancellation, and live amplitude sampling.
 */
interface AudioRecorder {
    fun start(outputFile: File): Result<Unit>
    fun pause(): Result<Unit>
    fun resume(): Result<Unit>
    fun stop(): Result<File>
    fun cancel()
    fun getMaxAmplitude(): Int
    val isRecording: Boolean
    val isPaused: Boolean
}
