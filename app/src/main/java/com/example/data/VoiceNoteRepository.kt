package com.example.data

import com.example.audio.AudioFileManager
import com.example.model.VoiceNote
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File

/**
 * Repository interface defining data operations for Voice Notes.
 * Abstracts the Room persistence and audio file deletion.
 */
interface VoiceNoteRepository {
    fun getAllNotes(): Flow<List<VoiceNote>>
    fun searchNotes(query: String): Flow<List<VoiceNote>>
    suspend fun getNoteById(id: Long): VoiceNote?
    suspend fun saveNote(
        title: String,
        file: File,
        durationMs: Long,
        tags: String = "",
        transcription: String? = null
    ): VoiceNote
    suspend fun updateNoteTitle(id: Long, newTitle: String)
    suspend fun updateTranscription(id: Long, transcription: String)
    suspend fun updateReminder(id: Long, reminderTimestamp: Long?)
    suspend fun updateReminderDetails(
        id: Long,
        timestamp: Long?,
        task: String?,
        isEnabled: Boolean,
        status: String
    )
    suspend fun getActiveFutureReminders(currentTime: Long): List<VoiceNote>
    suspend fun findActiveReminderByQuery(query: String): VoiceNote?
    suspend fun toggleStarred(id: Long)
    suspend fun deleteNote(note: VoiceNote): Boolean
}

class VoiceNoteRepositoryImpl(
    private val dao: VoiceNoteDao,
    private val audioFileManager: AudioFileManager
) : VoiceNoteRepository {

    override fun getAllNotes(): Flow<List<VoiceNote>> {
        return dao.getAllVoiceNotes().map { list ->
            list.map { it.toDomain() }
        }
    }

    override fun searchNotes(query: String): Flow<List<VoiceNote>> {
        return dao.searchVoiceNotes(query).map { list ->
            list.map { it.toDomain() }
        }
    }

    override suspend fun getNoteById(id: Long): VoiceNote? {
        return dao.getVoiceNoteById(id)?.toDomain()
    }

    override suspend fun saveNote(
        title: String,
        file: File,
        durationMs: Long,
        tags: String,
        transcription: String?
    ): VoiceNote {
        val fileSize = file.length()
        val entity = VoiceNoteEntity(
            title = title.ifBlank { "Voice Note" },
            filePath = file.absolutePath,
            durationMs = durationMs,
            fileSizeBytes = fileSize,
            createdAtTimestamp = System.currentTimeMillis(),
            transcription = transcription,
            tags = tags,
            isStarred = false
        )
        val insertedId = dao.insertVoiceNote(entity)
        return entity.copy(id = insertedId).toDomain()
    }

    override suspend fun updateNoteTitle(id: Long, newTitle: String) {
        if (newTitle.isNotBlank()) {
            dao.updateTitle(id, newTitle.trim())
        }
    }

    override suspend fun updateTranscription(id: Long, transcription: String) {
        dao.updateTranscription(id, transcription.trim())
    }

    override suspend fun updateReminder(id: Long, reminderTimestamp: Long?) {
        dao.updateReminder(id, reminderTimestamp)
    }

    override suspend fun updateReminderDetails(
        id: Long,
        timestamp: Long?,
        task: String?,
        isEnabled: Boolean,
        status: String
    ) {
        dao.updateReminderDetails(id, timestamp, task, isEnabled, status)
    }

    override suspend fun getActiveFutureReminders(currentTime: Long): List<VoiceNote> {
        return dao.getActiveFutureReminders(currentTime).map { it.toDomain() }
    }

    override suspend fun findActiveReminderByQuery(query: String): VoiceNote? {
        return dao.findActiveReminderByQuery(query)?.toDomain()
    }

    override suspend fun toggleStarred(id: Long) {
        dao.toggleStarred(id)
    }

    override suspend fun deleteNote(note: VoiceNote): Boolean {
        // 1. Delete the physical audio file from device storage
        audioFileManager.deleteAudioFile(note.filePath)
        // 2. Delete the database record
        dao.deleteVoiceNoteById(note.id)
        return true
    }
}
