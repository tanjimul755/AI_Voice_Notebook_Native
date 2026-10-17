package com.example.data

import android.content.Context
import android.content.SharedPreferences
import com.example.transcription.GeminiTranscriptionFormat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Supported automatic transcription modes for AI Voice Notebook.
 */
enum class TranscriptionMode(val id: String, val title: String, val description: String) {
    AUTO_SELECT(
        "auto_select",
        "Auto-select",
        "Uses local Whisper first; falls back to Gemini cloud AI if needed or unavailable."
    ),
    LOCAL_ONLY(
        "local_only",
        "Local AI only",
        "Strictly offline on-device transcription with Whisper. Never sends audio to the cloud."
    ),
    CLOUD_ONLY(
        "cloud_only",
        "Cloud AI only",
        "Transcribes directly with Google Gemini cloud AI for advanced multilingual accuracy."
    )
}

/**
 * Data holder for persistent user settings in AI Voice Notebook.
 */
data class NotebookSettings(
    val autoTitleGeneration: Boolean = true,
    val autoTranscription: Boolean = true,
    val transcriptionMode: TranscriptionMode = TranscriptionMode.AUTO_SELECT,
    val autoGeminiFallback: Boolean = false,
    val cloudAiEnabled: Boolean = true,
    val defaultTranscriptionFormat: GeminiTranscriptionFormat = GeminiTranscriptionFormat.AUTO,
    val autoReminderDetection: Boolean = true,
    val autoReminderScheduling: Boolean = true
)

/**
 * Persistent manager for user preferences, backed by Android SharedPreferences.
 * Provides synchronous atomic updates and reactive StateFlow observation.
 */
class NotebookPreferences(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<NotebookSettings> = _settings.asStateFlow()

    private fun loadSettings(): NotebookSettings {
        val formatId = prefs.getString(KEY_DEFAULT_FORMAT, GeminiTranscriptionFormat.AUTO.id)
            ?: GeminiTranscriptionFormat.AUTO.id
        val format = GeminiTranscriptionFormat.values().find { it.id == formatId }
            ?: GeminiTranscriptionFormat.AUTO

        val savedModeId = prefs.getString(KEY_TRANSCRIPTION_MODE, null)
        val mode = if (savedModeId != null) {
            TranscriptionMode.values().find { it.id == savedModeId } ?: TranscriptionMode.AUTO_SELECT
        } else {
            // Safe migration from older explicit settings
            if (!prefs.getBoolean(KEY_CLOUD_AI_ENABLED, true)) {
                TranscriptionMode.LOCAL_ONLY
            } else if (prefs.getBoolean(KEY_AUTO_FALLBACK, false)) {
                TranscriptionMode.AUTO_SELECT
            } else {
                TranscriptionMode.AUTO_SELECT
            }
        }

        // Enable automatic reminder scheduling by default for users who have not explicitly configured it
        val autoScheduling = if (prefs.contains(KEY_AUTO_REMINDER_SCHEDULING)) {
            prefs.getBoolean(KEY_AUTO_REMINDER_SCHEDULING, true)
        } else {
            true
        }

        return NotebookSettings(
            autoTitleGeneration = prefs.getBoolean(KEY_AUTO_TITLE, true),
            autoTranscription = prefs.getBoolean(KEY_AUTO_TRANSCRIBE, true),
            transcriptionMode = mode,
            autoGeminiFallback = prefs.getBoolean(KEY_AUTO_FALLBACK, false),
            cloudAiEnabled = prefs.getBoolean(KEY_CLOUD_AI_ENABLED, true),
            defaultTranscriptionFormat = format,
            autoReminderDetection = prefs.getBoolean(KEY_AUTO_REMINDER_DETECTION, true),
            autoReminderScheduling = autoScheduling
        )
    }

    fun setTranscriptionMode(mode: TranscriptionMode) {
        prefs.edit().putString(KEY_TRANSCRIPTION_MODE, mode.id).apply()
        _settings.value = _settings.value.copy(transcriptionMode = mode)
    }

    fun setAutoTitleGeneration(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_TITLE, enabled).apply()
        _settings.value = _settings.value.copy(autoTitleGeneration = enabled)
    }

    fun setAutoTranscription(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_TRANSCRIBE, enabled).apply()
        _settings.value = _settings.value.copy(autoTranscription = enabled)
    }

    fun setAutoGeminiFallback(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_FALLBACK, enabled).apply()
        _settings.value = _settings.value.copy(autoGeminiFallback = enabled)
    }

    fun setCloudAiEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CLOUD_AI_ENABLED, enabled).apply()
        _settings.value = _settings.value.copy(cloudAiEnabled = enabled)
    }

    fun setDefaultTranscriptionFormat(format: GeminiTranscriptionFormat) {
        prefs.edit().putString(KEY_DEFAULT_FORMAT, format.id).apply()
        _settings.value = _settings.value.copy(defaultTranscriptionFormat = format)
    }

    fun setAutoReminderDetection(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_REMINDER_DETECTION, enabled).apply()
        _settings.value = _settings.value.copy(autoReminderDetection = enabled)
    }

    fun setAutoReminderScheduling(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_REMINDER_SCHEDULING, enabled).apply()
        _settings.value = _settings.value.copy(autoReminderScheduling = enabled)
    }

    companion object {
        private const val PREFS_NAME = "ai_voice_notebook_prefs"
        private const val KEY_TRANSCRIPTION_MODE = "pref_transcription_mode"
        private const val KEY_AUTO_TITLE = "pref_auto_title"
        private const val KEY_AUTO_TRANSCRIBE = "pref_auto_transcribe"
        private const val KEY_AUTO_FALLBACK = "pref_auto_fallback"
        private const val KEY_CLOUD_AI_ENABLED = "pref_cloud_ai_enabled"
        private const val KEY_DEFAULT_FORMAT = "pref_default_format"
        private const val KEY_AUTO_REMINDER_DETECTION = "pref_auto_reminder_detection"
        private const val KEY_AUTO_REMINDER_SCHEDULING = "pref_auto_reminder_scheduling"
    }
}
