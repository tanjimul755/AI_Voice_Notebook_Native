package com.example.model

/**
 * Domain model representing a Voice Note in the application.
 * Designed to be clean and decouple business logic from Room database entities.
 */
data class VoiceNote(
    val id: Long = 0,
    val title: String,
    val filePath: String,
    val durationMs: Long,
    val fileSizeBytes: Long,
    val createdAtTimestamp: Long,
    val transcription: String? = null,
    val tags: String = "",
    val isStarred: Boolean = false,
    val reminderTimestamp: Long? = null
)
