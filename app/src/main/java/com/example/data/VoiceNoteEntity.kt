package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.model.VoiceNote

/**
 * Room Database entity representing the persistent metadata of a voice note.
 * Audio files are stored separately in the device's private internal storage directory.
 */
@Entity(tableName = "voice_notes")
data class VoiceNoteEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val filePath: String,
    val durationMs: Long,
    val fileSizeBytes: Long,
    val createdAtTimestamp: Long,
    val transcription: String? = null,
    val tags: String = "",
    val isStarred: Boolean = false,
    val reminderTimestamp: Long? = null,
    val reminderTask: String? = null,
    val isReminderEnabled: Boolean = false,
    val reminderStatus: String = "NONE"
) {
    fun toDomain(): VoiceNote = VoiceNote(
        id = id,
        title = title,
        filePath = filePath,
        durationMs = durationMs,
        fileSizeBytes = fileSizeBytes,
        createdAtTimestamp = createdAtTimestamp,
        transcription = transcription,
        tags = tags,
        isStarred = isStarred,
        reminderTimestamp = reminderTimestamp,
        reminderTask = reminderTask,
        isReminderEnabled = isReminderEnabled,
        reminderStatus = reminderStatus
    )

    companion object {
        fun fromDomain(note: VoiceNote): VoiceNoteEntity = VoiceNoteEntity(
            id = note.id,
            title = note.title,
            filePath = note.filePath,
            durationMs = note.durationMs,
            fileSizeBytes = note.fileSizeBytes,
            createdAtTimestamp = note.createdAtTimestamp,
            transcription = note.transcription,
            tags = note.tags,
            isStarred = note.isStarred,
            reminderTimestamp = note.reminderTimestamp,
            reminderTask = note.reminderTask,
            isReminderEnabled = note.isReminderEnabled,
            reminderStatus = note.reminderStatus
        )
    }
}
