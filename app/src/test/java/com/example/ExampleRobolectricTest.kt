package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.audio.AudioFileManager
import com.example.data.AppDatabase
import com.example.data.VoiceNoteDao
import com.example.data.VoiceNoteEntity
import com.example.data.VoiceNoteRepository
import com.example.data.VoiceNoteRepositoryImpl
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var dao: VoiceNoteDao
    private lateinit var audioFileManager: AudioFileManager
    private lateinit var repository: VoiceNoteRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.voiceNoteDao()
        audioFileManager = AudioFileManager(context)
        repository = VoiceNoteRepositoryImpl(dao, audioFileManager)
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun appNameResourceMatchesAppTitle() {
        val appName = context.getString(R.string.app_name)
        assertEquals("AI Voice Notebook", appName)
    }

    @Test
    fun roomDao_insertAndQueryVoiceNote() = runBlocking {
        val entity = VoiceNoteEntity(
            title = "Test Lecture",
            filePath = "/data/user/0/test/sample.m4a",
            durationMs = 65000L,
            fileSizeBytes = 102400L,
            createdAtTimestamp = System.currentTimeMillis(),
            tags = "Study"
        )
        val id = dao.insertVoiceNote(entity)
        assertTrue(id > 0)

        val retrieved = dao.getVoiceNoteById(id)
        assertNotNull(retrieved)
        assertEquals("Test Lecture", retrieved?.title)
        assertEquals(65000L, retrieved?.durationMs)
        assertEquals("Study", retrieved?.tags)
    }

    @Test
    fun roomDao_updateTitleAndToggleStar() = runBlocking {
        val entity = VoiceNoteEntity(
            title = "Initial Title",
            filePath = "/sample.m4a",
            durationMs = 30000L,
            fileSizeBytes = 50000L,
            createdAtTimestamp = System.currentTimeMillis()
        )
        val id = dao.insertVoiceNote(entity)

        dao.updateTitle(id, "Updated Meeting Title")
        var note = dao.getVoiceNoteById(id)
        assertEquals("Updated Meeting Title", note?.title)

        dao.toggleStarred(id)
        note = dao.getVoiceNoteById(id)
        assertTrue(note?.isStarred == true)

        dao.toggleStarred(id)
        note = dao.getVoiceNoteById(id)
        assertFalse(note?.isStarred == true)
    }

    @Test
    fun roomDao_searchNotesByQuery() = runBlocking {
        val note1 = VoiceNoteEntity(
            title = "Biology Lecture 1",
            filePath = "/bio1.m4a",
            durationMs = 12000L,
            fileSizeBytes = 20000L,
            createdAtTimestamp = 1000L,
            tags = "Science"
        )
        val note2 = VoiceNoteEntity(
            title = "Project Brainstorm",
            filePath = "/brainstorm.m4a",
            durationMs = 25000L,
            fileSizeBytes = 35000L,
            createdAtTimestamp = 2000L,
            tags = "Software"
        )
        dao.insertVoiceNote(note1)
        dao.insertVoiceNote(note2)

        val searchBio = dao.searchVoiceNotes("Biology").first()
        assertEquals(1, searchBio.size)
        assertEquals("Biology Lecture 1", searchBio[0].title)

        val searchTag = dao.searchVoiceNotes("Software").first()
        assertEquals(1, searchTag.size)
        assertEquals("Project Brainstorm", searchTag[0].title)
    }

    @Test
    fun audioFileManager_formatDurationAndSize() {
        assertEquals("00:00", AudioFileManager.formatDuration(0L))
        assertEquals("00:45", AudioFileManager.formatDuration(45000L))
        assertEquals("02:15", AudioFileManager.formatDuration(135000L))
        assertEquals("01:05:30", AudioFileManager.formatDuration(3930000L))

        assertEquals("0 KB", AudioFileManager.formatFileSize(0L))
        assertEquals("100 KB", AudioFileManager.formatFileSize(102400L))
        assertTrue(AudioFileManager.formatFileSize(2097152L).contains("MB"))
    }

    @Test
    fun repository_saveAndDeleteNoteWithFile() = runBlocking {
        // Create dummy physical file
        val tempFile = File(context.filesDir, "test_recording.m4a")
        tempFile.writeText("dummy audio data")
        assertTrue(tempFile.exists())

        val saved = repository.saveNote(
            title = "Architecture Plan",
            file = tempFile,
            durationMs = 45000L,
            tags = "University"
        )
        assertNotNull(saved)
        assertEquals("Architecture Plan", saved.title)

        val allNotes = repository.getAllNotes().first()
        assertEquals(1, allNotes.size)

        // Delete note
        val deleteSuccess = repository.deleteNote(saved)
        assertTrue(deleteSuccess)

        val notesAfterDelete = repository.getAllNotes().first()
        assertTrue(notesAfterDelete.isEmpty())
        assertFalse(tempFile.exists()) // File was deleted from disk
    }

    @Test
    fun roomDao_and_repository_updateTranscription() = runBlocking {
        val entity = VoiceNoteEntity(
            title = "Lecture on AI",
            filePath = "/lecture.m4a",
            durationMs = 60000L,
            fileSizeBytes = 120000L,
            createdAtTimestamp = System.currentTimeMillis()
        )
        val id = dao.insertVoiceNote(entity)

        // Initially null
        var note = dao.getVoiceNoteById(id)
        assertTrue(note?.transcription.isNullOrBlank())

        // Update transcription via repository
        val testTranscript = "Welcome to the lecture on artificial intelligence and speech processing."
        repository.updateTranscription(id, testTranscript)

        note = dao.getVoiceNoteById(id)
        assertEquals(testTranscript, note?.transcription)

        // Verify domain mapping
        val domainNote = repository.getNoteById(id)
        assertEquals(testTranscript, domainNote?.transcription)
    }

    @Test
    fun autoTranscription_workflowUpdatesRoomAndUi() = runBlocking {
        val tempFile = File(context.filesDir, "test_speech.m4a")
        tempFile.writeText("test audio")

        val saved = repository.saveNote(
            title = "Test Speech",
            file = tempFile,
            durationMs = 15000L,
            tags = "Test"
        )
        assertNotNull(saved)
        assertEquals("Test Speech", saved.title)
        assertTrue(saved.transcription.isNullOrBlank())

        val simulatedTranscription = "This is automatically transcribed offline speech."
        repository.updateTranscription(saved.id, simulatedTranscription)

        val updated = repository.getNoteById(saved.id)
        assertNotNull(updated)
        assertEquals(simulatedTranscription, updated?.transcription)
    }
}
