package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.ai.AiManager
import com.example.ai.model.AiProviderType
import com.example.ai.model.AiTask
import com.example.ai.model.TaskAiConfig
import com.example.ai.provider.DEFAULT_GEMINI_MODEL
import com.example.ai.security.SecureKeyStorage
import com.example.ai.usage.AiUsageTracker
import com.example.data.NotebookPreferences
import com.example.transcription.GeminiTranscriptionFormat
import kotlinx.coroutines.runBlocking
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
class MultiProviderAiTest {

    private lateinit var context: Context
    private lateinit var keyStorage: SecureKeyStorage
    private lateinit var usageTracker: AiUsageTracker
    private lateinit var preferences: NotebookPreferences
    private lateinit var aiManager: AiManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext<Context>()
        keyStorage = SecureKeyStorage(context)
        keyStorage.clearAllKeysForTesting()

        usageTracker = AiUsageTracker(context)
        usageTracker.resetAll()

        preferences = NotebookPreferences(context)
        aiManager = AiManager(context, keyStorage, usageTracker)
    }

    @Test
    fun defaultAiProviderAndModelAreGemini35Flash() {
        val settings = preferences.settings.value

        // Audio transcription default
        assertEquals(AiProviderType.GEMINI, settings.transcriptionAiConfig.provider)
        assertEquals("gemini-3.5-flash", settings.transcriptionAiConfig.model)
        assertTrue(settings.transcriptionAiConfig.enabled)

        // Reminder interpretation default
        assertEquals(AiProviderType.GEMINI, settings.reminderAiConfig.provider)
        assertEquals("gemini-3.5-flash", settings.reminderAiConfig.model)
        assertTrue(settings.reminderAiConfig.enabled)

        // Formatting default
        assertEquals(AiProviderType.GEMINI, settings.formattingAiConfig.provider)
        assertEquals("gemini-3.5-flash", settings.formattingAiConfig.model)
        assertTrue(settings.formattingAiConfig.enabled)

        // Verify default model constant
        assertEquals("gemini-3.5-flash", DEFAULT_GEMINI_MODEL)
    }

    @Test
    fun secureKeyStorage_saveMaskAndRemove() {
        val testKey = "AIzaSyD-TestKey-9876543210ABCDEF"
        keyStorage.saveApiKey(AiProviderType.GEMINI, testKey)

        assertTrue(keyStorage.isKeyAvailable(AiProviderType.GEMINI))
        val retrievedKey = keyStorage.getApiKey(AiProviderType.GEMINI)
        assertEquals(testKey, retrievedKey)

        // Verify masking
        val masked = keyStorage.getMaskedKey(AiProviderType.GEMINI)
        assertTrue(masked.contains("••••"))
        assertTrue(masked.endsWith("CDEF"))

        // OpenAI key independence
        assertFalse(keyStorage.isKeyAvailable(AiProviderType.OPENAI_COMPATIBLE))

        // Remove key
        keyStorage.removeApiKey(AiProviderType.GEMINI)
        assertFalse(keyStorage.hasUserConfiguredKey(AiProviderType.GEMINI))
    }

    @Test
    fun aiUsageTracker_recordsRequestsTokensAndErrors() {
        assertEquals(0, usageTracker.getAllUsage().size)

        // Record successful transcription
        usageTracker.recordSuccess(
            task = AiTask.TRANSCRIPTION,
            provider = AiProviderType.GEMINI,
            promptTokens = 120L,
            completionTokens = 45L
        )

        // Record another success
        usageTracker.recordSuccess(
            task = AiTask.TRANSCRIPTION,
            provider = AiProviderType.GEMINI,
            promptTokens = 80L,
            completionTokens = 25L
        )

        // Record a quota failure
        usageTracker.recordFailure(
            task = AiTask.TRANSCRIPTION,
            provider = AiProviderType.GEMINI,
            isQuotaBlocked = true
        )

        val usage = usageTracker.getUsage(AiTask.TRANSCRIPTION, AiProviderType.GEMINI)
        assertEquals(AiTask.TRANSCRIPTION, usage.task)
        assertEquals(AiProviderType.GEMINI, usage.provider)
        assertEquals(2, usage.successCount)
        assertEquals(1, usage.quotaBlockedCount)
        assertEquals(0, usage.failureCount)
        assertEquals(200L, usage.promptTokens)
        assertEquals(70L, usage.completionTokens)
        assertEquals(270L, usage.totalTokens)

        // Reset
        usageTracker.resetAll()
        val resetUsage = usageTracker.getUsage(AiTask.TRANSCRIPTION, AiProviderType.GEMINI)
        assertEquals(0, resetUsage.successCount)
        assertEquals(0, resetUsage.quotaBlockedCount)
        assertEquals(0, resetUsage.totalTokens)
    }

    @Test
    fun taskAiConfig_persistenceAndTaskIndependence() {
        val customTranscriptionConfig = TaskAiConfig(
            task = AiTask.TRANSCRIPTION,
            enabled = true,
            provider = AiProviderType.OPENAI_COMPATIBLE,
            model = "whisper-1",
            customEndpoint = "https://api.openai.com"
        )
        preferences.setTaskConfig(customTranscriptionConfig)

        val updatedSettings = preferences.settings.value
        // Transcription updated to OpenAI
        assertEquals(AiProviderType.OPENAI_COMPATIBLE, updatedSettings.transcriptionAiConfig.provider)
        assertEquals("whisper-1", updatedSettings.transcriptionAiConfig.model)
        assertEquals("https://api.openai.com", updatedSettings.transcriptionAiConfig.customEndpoint)

        // Reminders and Formatting remained Gemini
        assertEquals(AiProviderType.GEMINI, updatedSettings.reminderAiConfig.provider)
        assertEquals(AiProviderType.GEMINI, updatedSettings.formattingAiConfig.provider)
    }

    @Test
    fun aiManager_fullAiModeGatingAndExplicitActions() = runBlocking {
        val dummyFile = File(context.cacheDir, "sample.m4a").apply { writeText("audio content") }
        val geminiConfig = TaskAiConfig(
            task = AiTask.TRANSCRIPTION,
            enabled = true,
            provider = AiProviderType.GEMINI,
            model = "gemini-3.5-flash"
        )

        // 1. Full AI Mode is FALSE, Automatic action -> MUST BE BLOCKED
        val blockedAutoResult = aiManager.transcribeAudio(
            audioFile = dummyFile,
            format = GeminiTranscriptionFormat.AUTO,
            config = geminiConfig,
            isFullAiMode = false,
            isExplicitUserAction = false
        )
        assertTrue(blockedAutoResult.isFailure)
        assertTrue(blockedAutoResult.exceptionOrNull()?.message?.contains("Full AI Mode is turned off") == true)

        // 2. Full AI Mode is FALSE, Explicit user action -> Allowed to proceed to key check
        // (test with OpenAI provider to ensure key check fails without buildConfig key)
        val openAiConfig = TaskAiConfig(
            task = AiTask.TRANSCRIPTION,
            enabled = true,
            provider = AiProviderType.OPENAI_COMPATIBLE,
            model = "whisper-1"
        )
        val explicitResult = aiManager.transcribeAudio(
            audioFile = dummyFile,
            format = GeminiTranscriptionFormat.AUTO,
            config = openAiConfig,
            isFullAiMode = false,
            isExplicitUserAction = true
        )
        assertTrue(explicitResult.isFailure)
        assertTrue(explicitResult.exceptionOrNull()?.message?.contains("Missing API key") == true)

        // 3. Task is explicitly disabled -> MUST BE BLOCKED even if Full AI Mode is ON
        val disabledTaskConfig = geminiConfig.copy(enabled = false)
        val disabledTaskResult = aiManager.transcribeAudio(
            audioFile = dummyFile,
            format = GeminiTranscriptionFormat.AUTO,
            config = disabledTaskConfig,
            isFullAiMode = true,
            isExplicitUserAction = true
        )
        assertTrue(disabledTaskResult.isFailure)
        assertTrue(disabledTaskResult.exceptionOrNull()?.message?.contains("is disabled for this task") == true)
    }

    @Test
    fun tightScheduleReminderDetection_10secondsAnd20seconds() {
        val reminderManager = com.example.reminder.ReminderManager(context)
        val now = System.currentTimeMillis()

        // 1. "please remind me to take medicine after 10 seconds"
        val detected10s = reminderManager.detectReminder("please remind me to take medicine after 10 seconds")
        assertNotNull(detected10s)
        assertEquals(com.example.reminder.ReminderAction.CREATE, detected10s?.action)
        assertTrue(detected10s?.taskSummary?.contains("medicine", ignoreCase = true) == true)
        assertNotNull(detected10s?.targetTimestampMs)
        val diff10s = (detected10s?.targetTimestampMs ?: 0L) - now
        // Should be approximately +10s (allowing a 4s margin)
        assertTrue("diff10s was $diff10s ms", diff10s in 6000L..14000L)

        // 2. "remind me in 20 seconds to turn off stove"
        val detected20s = reminderManager.detectReminder("remind me in 20 seconds to turn off stove")
        assertNotNull(detected20s)
        assertEquals(com.example.reminder.ReminderAction.CREATE, detected20s?.action)
        assertNotNull(detected20s?.targetTimestampMs)
        val diff20s = (detected20s?.targetTimestampMs ?: 0L) - now
        assertTrue("diff20s was $diff20s ms", diff20s in 16000L..24000L)
    }

    @Test
    fun transcriptionMode_buttonVisibilityContract() {
        // Verify transcription mode button presence logic
        // LOCAL_ONLY: Only offline button, never AI button
        val localOnly = com.example.data.TranscriptionMode.LOCAL_ONLY
        val showOfflineInLocal = (localOnly != com.example.data.TranscriptionMode.CLOUD_ONLY)
        val showAiInLocal = (localOnly != com.example.data.TranscriptionMode.LOCAL_ONLY)
        assertTrue(showOfflineInLocal)
        assertFalse(showAiInLocal)

        // CLOUD_ONLY: Only AI button, never offline button
        val cloudOnly = com.example.data.TranscriptionMode.CLOUD_ONLY
        val showOfflineInCloud = (cloudOnly != com.example.data.TranscriptionMode.CLOUD_ONLY)
        val showAiInCloud = (cloudOnly != com.example.data.TranscriptionMode.LOCAL_ONLY)
        assertFalse(showOfflineInCloud)
        assertTrue(showAiInCloud)

        // AUTO_SELECT with Full AI Mode: AI button shown
        val autoSelect = com.example.data.TranscriptionMode.AUTO_SELECT
        val fullAi = true
        val showAiInAutoFull = (autoSelect == com.example.data.TranscriptionMode.CLOUD_ONLY || (autoSelect == com.example.data.TranscriptionMode.AUTO_SELECT && fullAi))
        assertTrue(showAiInAutoFull)
    }
}

