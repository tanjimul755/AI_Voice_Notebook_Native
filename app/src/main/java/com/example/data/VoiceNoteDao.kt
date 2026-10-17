package com.example.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object (DAO) for interacting with the voice_notes table.
 * All write methods are suspended functions. Queries return reactive Flows.
 */
@Dao
interface VoiceNoteDao {

    @Query("SELECT * FROM voice_notes ORDER BY isStarred DESC, createdAtTimestamp DESC")
    fun getAllVoiceNotes(): Flow<List<VoiceNoteEntity>>

    @Query("SELECT * FROM voice_notes WHERE id = :id LIMIT 1")
    suspend fun getVoiceNoteById(id: Long): VoiceNoteEntity?

    @Query(
        "SELECT * FROM voice_notes " +
        "WHERE title LIKE '%' || :query || '%' OR tags LIKE '%' || :query || '%' " +
        "ORDER BY isStarred DESC, createdAtTimestamp DESC"
    )
    fun searchVoiceNotes(query: String): Flow<List<VoiceNoteEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVoiceNote(note: VoiceNoteEntity): Long

    @Update
    suspend fun updateVoiceNote(note: VoiceNoteEntity)

    @Delete
    suspend fun deleteVoiceNote(note: VoiceNoteEntity)

    @Query("DELETE FROM voice_notes WHERE id = :id")
    suspend fun deleteVoiceNoteById(id: Long)

    @Query("UPDATE voice_notes SET title = :newTitle WHERE id = :id")
    suspend fun updateTitle(id: Long, newTitle: String)

    @Query("UPDATE voice_notes SET transcription = :transcription WHERE id = :id")
    suspend fun updateTranscription(id: Long, transcription: String)

    @Query("UPDATE voice_notes SET isStarred = NOT isStarred WHERE id = :id")
    suspend fun toggleStarred(id: Long)

    @Query("UPDATE voice_notes SET reminderTimestamp = :reminderTimestamp WHERE id = :id")
    suspend fun updateReminder(id: Long, reminderTimestamp: Long?)

    @Query("UPDATE voice_notes SET reminderTimestamp = :timestamp, reminderTask = :task, isReminderEnabled = :isEnabled, reminderStatus = :status WHERE id = :id")
    suspend fun updateReminderDetails(
        id: Long,
        timestamp: Long?,
        task: String?,
        isEnabled: Boolean,
        status: String
    )

    @Query("SELECT * FROM voice_notes WHERE isReminderEnabled = 1 AND reminderTimestamp IS NOT NULL AND reminderTimestamp > :currentTime")
    suspend fun getActiveFutureReminders(currentTime: Long): List<VoiceNoteEntity>

    @Query("SELECT * FROM voice_notes WHERE (reminderTask LIKE '%' || :query || '%' OR title LIKE '%' || :query || '%') AND isReminderEnabled = 1 LIMIT 1")
    suspend fun findActiveReminderByQuery(query: String): VoiceNoteEntity?
}
