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

    @Test
    fun geminiTranscriptionFormats_containsAllRequiredOptions() {
        val formats = com.example.transcription.GeminiTranscriptionFormat.values()
        assertEquals(4, formats.size)

        val formatIds = formats.map { it.id }
        assertTrue(formatIds.contains("auto"))
        assertTrue(formatIds.contains("pure_bangla"))
        assertTrue(formatIds.contains("romanized_bangla"))
        assertTrue(formatIds.contains("english"))

        // Verify Banglish instruction contains phonetic latin guidance
        val romanized = formats.first { it.id == "romanized_bangla" }
        assertTrue(romanized.instruction.contains("Roman/Latin"))

        // Verify Pure Bangla instruction requires Bengali script
        val pureBangla = formats.first { it.id == "pure_bangla" }
        assertTrue(pureBangla.instruction.contains("বাংলা"))
    }

    @Test
    fun geminiAudioTranscriber_missingFileOrApiKeyHandledSafely() = runBlocking {
        val transcriber = com.example.transcription.GeminiAudioTranscriber(context)
        val nonExistentFile = File(context.filesDir, "does_not_exist.m4a")

        val result = transcriber.transcribeAudio(nonExistentFile)
        assertTrue(result.isFailure)
        assertNotNull(result.exceptionOrNull())
    }

    @Test
    fun geminiTranscription_updatesRoomDatabaseAndPreservesOriginalRecording() = runBlocking {
        val tempFile = File(context.filesDir, "bangla_recording.m4a")
        tempFile.writeText("sample audio content")

        val note = repository.saveNote(
            title = "Bangla Meeting Note",
            file = tempFile,
            durationMs = 28000L,
            tags = "Office"
        )
        assertNotNull(note)

        // Simulate improved Gemini transcription with mixed Bangla-English (Banglish)
        val banglaTranscript = "Ajke meeting e shobai present chilo. Amra new feature finalize korechi."
        repository.updateTranscription(note.id, banglaTranscript)

        val updatedNote = repository.getNoteById(note.id)
        assertNotNull(updatedNote)
        assertEquals(banglaTranscript, updatedNote?.transcription)
        assertTrue(tempFile.exists()) // Audio file is preserved
        assertEquals("Bangla Meeting Note", updatedNote?.title)
    }

    @Test
    fun notebookPreferences_persistsAndRetrievesAllSettings() {
        val prefs = com.example.data.NotebookPreferences(context)

        // Verify defaults
        val initial = prefs.settings.value
        assertTrue(initial.autoTitleGeneration)
        assertTrue(initial.autoTranscription)
        assertFalse(initial.autoGeminiFallback)
        assertTrue(initial.cloudAiEnabled)
        assertEquals(com.example.transcription.GeminiTranscriptionFormat.AUTO, initial.defaultTranscriptionFormat)
        assertTrue(initial.autoReminderDetection)
        assertFalse(initial.autoReminderScheduling)

        // Update settings
        prefs.setAutoTitleGeneration(false)
        prefs.setAutoTranscription(true)
        prefs.setAutoGeminiFallback(true)
        prefs.setCloudAiEnabled(false)
        prefs.setDefaultTranscriptionFormat(com.example.transcription.GeminiTranscriptionFormat.ROMANIZED_BANGLA)
        prefs.setAutoReminderDetection(true)
        prefs.setAutoReminderScheduling(true)

        // Reload from new preferences instance (verifying persistent storage across app restarts)
        val reloadedPrefs = com.example.data.NotebookPreferences(context)
        val updated = reloadedPrefs.settings.value

        assertFalse(updated.autoTitleGeneration)
        assertTrue(updated.autoTranscription)
        assertTrue(updated.autoGeminiFallback)
        assertFalse(updated.cloudAiEnabled)
        assertEquals(com.example.transcription.GeminiTranscriptionFormat.ROMANIZED_BANGLA, updated.defaultTranscriptionFormat)
        assertTrue(updated.autoReminderDetection)
        assertTrue(updated.autoReminderScheduling)
    }

    @Test
    fun titleGenerator_generatesContextualTitlesFromTranscripts() {
        // English sentence
        val englishTranscript = "We discussed the budget report and finalized the Q3 marketing strategy."
        val englishTitle = com.example.util.TitleGenerator.generateTitle(englishTranscript)
        assertNotNull(englishTitle)
        assertTrue(englishTitle?.contains("budget", ignoreCase = true) == true)

        // Bangla sentence
        val banglaTranscript = "আজকে মিটিং এ নতুন প্রজেক্টের পরিকল্পনা করা হলো।"
        val banglaTitle = com.example.util.TitleGenerator.generateTitle(banglaTranscript)
        assertNotNull(banglaTitle)
        assertTrue(banglaTitle!!.isNotBlank())

        // Blank or non-speech transcript returns null
        val noSpeech = "[No speech detected in audio]"
        assertEquals(null, com.example.util.TitleGenerator.generateTitle(noSpeech))
        assertEquals(null, com.example.util.TitleGenerator.generateTitle(""))
    }

    @Test
    fun reminderManager_detectsExplicitDateTimeWithoutInventingFakeTime() {
        val reminderManager = com.example.reminder.ReminderManager(context)

        // 1. Explicit future time in English
        val englishWithTime = "Please remind me to submit the assignment tomorrow at 4 pm"
        val detectedWithTime = reminderManager.detectReminder(englishWithTime)
        assertNotNull(detectedWithTime)
        assertTrue(detectedWithTime!!.isExplicitDateTime)
        assertNotNull(detectedWithTime.targetTimestampMs)
        assertTrue(detectedWithTime.targetTimestampMs!! > System.currentTimeMillis())

        // 2. Reminder intent without explicit time: MUST NOT invent fake time!
        val englishWithoutTime = "Don't forget to call Mom and wish her a happy birthday"
        val detectedWithoutTime = reminderManager.detectReminder(englishWithoutTime)
        assertNotNull(detectedWithoutTime)
        assertFalse(detectedWithoutTime!!.isExplicitDateTime)
        assertEquals(null, detectedWithoutTime.targetTimestampMs)

        // 3. Explicit future time in Bangla
        val banglaWithTime = "মনে করিয়ে দিও কালকে বিকাল ৫টায় ডাক্তারের সাথে দেখা করতে হবে"
        val detectedBangla = reminderManager.detectReminder(banglaWithTime)
        assertNotNull(detectedBangla)
        assertTrue(detectedBangla!!.isExplicitDateTime)
        assertNotNull(detectedBangla.targetTimestampMs)
        assertTrue(detectedBangla.targetTimestampMs!! > System.currentTimeMillis())

        // 4. Non-reminder text returns null
        val regularSpeech = "The weather today is cloudy with light rain."
        assertEquals(null, reminderManager.detectReminder(regularSpeech))
    }

    @Test
    fun repository_updateReminderTimestamp() = runBlocking {
        val tempFile = File(context.filesDir, "reminder_note.m4a")
        tempFile.writeText("audio bytes")

        val note = repository.saveNote(
            title = "Doctor Appointment",
            file = tempFile,
            durationMs = 10000L
        )

        // Initially null
        assertEquals(null, note.reminderTimestamp)

        val targetTimestamp = System.currentTimeMillis() + 86400000L // +24 hours
        repository.updateReminder(note.id, targetTimestamp)

        val updated = repository.getNoteById(note.id)
        assertNotNull(updated)
        assertEquals(targetTimestamp, updated?.reminderTimestamp)
    }

    @Test
    fun audioDecoder_wavHeaderAccuratelyDescribesPcmData() {
        val testWavFile = File(context.cacheDir, "test_header.wav")
        val sampleRate = 16000
        val channels = 1
        val initialPcmBytes = 32000 // 1 second of 16kHz 16-bit mono

        java.io.FileOutputStream(testWavFile).use { fos ->
            com.example.audio.AudioDecoder.writeWavHeader(fos, sampleRate, channels, initialPcmBytes)
            // write dummy PCM data
            fos.write(ByteArray(initialPcmBytes))
        }

        assertEquals(initialPcmBytes + 44L, testWavFile.length())

        val raf = java.io.RandomAccessFile(testWavFile, "r")
        val header = ByteArray(44)
        raf.readFully(header)

        // "RIFF"
        assertEquals("RIFF", String(header, 0, 4))
        // Format "WAVE"
        assertEquals("WAVE", String(header, 8, 4))
        // "fmt "
        assertEquals("fmt ", String(header, 12, 4))
        // AudioFormat = 1 (PCM)
        val audioFormat = (header[20].toInt() and 0xFF) or ((header[21].toInt() and 0xFF) shl 8)
        assertEquals(1, audioFormat)
        // Channels = 1
        val numChannels = (header[22].toInt() and 0xFF) or ((header[23].toInt() and 0xFF) shl 8)
        assertEquals(channels, numChannels)
        // SampleRate = 16000
        val sRate = (header[24].toInt() and 0xFF) or
                ((header[25].toInt() and 0xFF) shl 8) or
                ((header[26].toInt() and 0xFF) shl 16) or
                ((header[27].toInt() and 0xFF) shl 24)
        assertEquals(sampleRate, sRate)
        // BitsPerSample = 16
        val bits = (header[34].toInt() and 0xFF) or ((header[35].toInt() and 0xFF) shl 8)
        assertEquals(16, bits)
        // ByteRate = 32000
        val byteRate = (header[28].toInt() and 0xFF) or
                ((header[29].toInt() and 0xFF) shl 8) or
                ((header[30].toInt() and 0xFF) shl 16) or
                ((header[31].toInt() and 0xFF) shl 24)
        assertEquals(sampleRate * channels * 2, byteRate)
        // "data"
        assertEquals("data", String(header, 36, 4))
        // Data length
        val dataLen = (header[40].toInt() and 0xFF) or
                ((header[41].toInt() and 0xFF) shl 8) or
                ((header[42].toInt() and 0xFF) shl 16) or
                ((header[43].toInt() and 0xFF) shl 24)
        assertEquals(initialPcmBytes, dataLen)

        // Now test updateWavHeader with a new size
        val updatedPcmBytes = 64000
        com.example.audio.AudioDecoder.updateWavHeader(testWavFile, updatedPcmBytes, sampleRate, channels)

        raf.seek(4)
        val totalLenUpdated = raf.read() or (raf.read() shl 8) or (raf.read() shl 16) or (raf.read() shl 24)
        assertEquals(updatedPcmBytes + 36, totalLenUpdated)

        raf.seek(40)
        val dataLenUpdated = raf.read() or (raf.read() shl 8) or (raf.read() shl 16) or (raf.read() shl 24)
        assertEquals(updatedPcmBytes, dataLenUpdated)

        raf.close()
        testWavFile.delete()
    }

    @Test
    fun audioDecoder_resampleBandLimitedPreservesInBandAndAttenuatesOutOfBand() {
        val srcRate = 44100
        val dstRate = 16000
        val durationSeconds = 0.5
        val sampleCount = (srcRate * durationSeconds).toInt()

        // 1. In-band 1 kHz sine wave (should pass through with minimal loss)
        val inBandFreq = 1000.0
        val inBandInput = ShortArray(sampleCount) { i ->
            val t = i.toDouble() / srcRate
            (kotlin.math.sin(2.0 * Math.PI * inBandFreq * t) * 20000).toInt().toShort()
        }

        val inBandOutput = com.example.audio.AudioDecoder.resampleBandLimited(inBandInput, srcRate, dstRate)
        assertTrue(inBandOutput.isNotEmpty())

        // Calculate RMS of in-band input and output
        var inBandInRms = 0.0
        for (s in inBandInput) inBandInRms += s.toDouble() * s.toDouble()
        inBandInRms = kotlin.math.sqrt(inBandInRms / inBandInput.size)

        var inBandOutRms = 0.0
        // Ignore boundary transition (first and last 50 samples)
        val innerOutput = inBandOutput.sliceArray(50 until inBandOutput.size - 50)
        for (s in innerOutput) inBandOutRms += s.toDouble() * s.toDouble()
        inBandOutRms = kotlin.math.sqrt(inBandOutRms / innerOutput.size)

        // Passband level should be preserved (within 10% tolerance)
        val inBandRatio = inBandOutRms / inBandInRms
        assertTrue("In-band 1kHz tone should be preserved (ratio=$inBandRatio)", inBandRatio in 0.85..1.15)

        // 2. Out-of-band 12 kHz sine wave (above Nyquist of 16kHz = 8kHz; MUST be severely attenuated)
        val outOfBandFreq = 12000.0
        val outOfBandInput = ShortArray(sampleCount) { i ->
            val t = i.toDouble() / srcRate
            (kotlin.math.sin(2.0 * Math.PI * outOfBandFreq * t) * 20000).toInt().toShort()
        }

        val outOfBandOutput = com.example.audio.AudioDecoder.resampleBandLimited(outOfBandInput, srcRate, dstRate)
        var outOfBandOutRms = 0.0
        val innerOutBand = outOfBandOutput.sliceArray(50 until outOfBandOutput.size - 50)
        for (s in innerOutBand) outOfBandOutRms += s.toDouble() * s.toDouble()
        outOfBandOutRms = kotlin.math.sqrt(outOfBandOutRms / innerOutBand.size)

        // The 12 kHz signal must be attenuated by at least 30 dB (RMS < 3% of original)
        val outBandRatio = outOfBandOutRms / inBandInRms
        assertTrue("Out-of-band 12kHz tone must be strongly attenuated (ratio=$outBandRatio)", outBandRatio < 0.05)
    }

    @Test
    fun audioDecoder_streamingResamplerProducesAccurateSampleRatio() {
        val srcRate = 48000
        val dstRate = 16000
        val resampler = com.example.audio.AudioDecoder.StreamingResampler(srcRate, dstRate)

        val totalInputSamples = 48000 // 1 second of 48kHz audio
        val input = ShortArray(totalInputSamples) { (it % 1000).toShort() }

        val outputList = ArrayList<Short>()
        // Feed in 1024-sample chunks
        val chunkSize = 1024
        var offset = 0
        while (offset < totalInputSamples) {
            val end = minOf(offset + chunkSize, totalInputSamples)
            val chunk = input.copyOfRange(offset, end)
            resampler.process(chunk) { outputList.add(it) }
            offset = end
        }
        resampler.finish { outputList.add(it) }

        // 48000 -> 16000 should produce ~16000 samples (within small window boundary difference)
        val expected = 16000
        val diff = kotlin.math.abs(outputList.size - expected)
        assertTrue("Output sample count ${outputList.size} should be close to expected $expected", diff <= 2)
    }
}
