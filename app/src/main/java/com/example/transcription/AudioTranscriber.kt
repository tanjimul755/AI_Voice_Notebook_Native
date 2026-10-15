package com.example.transcription

import java.io.File

/**
 * Model status information for offline Whisper speech-to-text.
 */
data class WhisperModelStatus(
    val isAvailable: Boolean,
    val modelFile: File?,
    val modelName: String = "ggml-tiny.bin",
    val modelSizeBytes: Long = 77700000L, // ~75 MB
    val statusDescription: String
)

/**
 * Interface defining offline speech-to-text operations.
 * Operates purely locally on device without network/cloud calls during transcription.
 */
interface AudioTranscriber {
    /**
     * Checks if the required offline Whisper model file is already present on the device.
     */
    fun getModelStatus(): WhisperModelStatus

    /**
     * Downloads the lightweight multilingual Whisper model (~75 MB) to app internal storage.
     * Only invoked upon explicit user confirmation from the UI.
     */
    suspend fun downloadModel(onProgress: (Float) -> Unit): Result<File>

    /**
     * Transcribes a recorded .m4a audio file locally using the offline Whisper model.
     * Supports "auto" (auto-detect language), "en" (English), and "bn" (Bangla).
     */
    suspend fun transcribe(audioFile: File, language: String = "auto"): Result<String>

    /**
     * Releases active Whisper model native handles and caches.
     */
    fun release()
}
