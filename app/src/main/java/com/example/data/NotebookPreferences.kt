package com.example.data

import android.content.Context
import android.content.SharedPreferences
import com.example.transcription.GeminiTranscriptionFormat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Data holder for persistent user settings in AI Voice Notebook.
 */
data class NotebookSettings(
    val autoTitleGeneration: Boolean = true,
    val autoTranscription: Boolean = true,
    val autoGeminiFallback: Boolean = false,
    val cloudAiEnabled: Boolean = true,
    val defaultTranscriptionFormat: GeminiTranscriptionFormat = GeminiTranscriptionFormat.AUTO,
    val autoReminderDetection: Boolean = true,
    val autoReminderScheduling: Boolean = false
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

        return NotebookSettings(
            autoTitleGeneration = prefs.getBoolean(KEY_AUTO_TITLE, true),
            autoTranscription = prefs.getBoolean(KEY_AUTO_TRANSCRIBE, true),
            autoGeminiFallback = prefs.getBoolean(KEY_AUTO_FALLBACK, false),
            cloudAiEnabled = prefs.getBoolean(KEY_CLOUD_AI_ENABLED, true),
            defaultTranscriptionFormat = format,
            autoReminderDetection = prefs.getBoolean(KEY_AUTO_REMINDER_DETECTION, true),
            autoReminderScheduling = prefs.getBoolean(KEY_AUTO_REMINDER_SCHEDULING, false)
        )
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
        private const val KEY_AUTO_TITLE = "pref_auto_title"
        private const val KEY_AUTO_TRANSCRIBE = "pref_auto_transcribe"
        private const val KEY_AUTO_FALLBACK = "pref_auto_fallback"
        private const val KEY_CLOUD_AI_ENABLED = "pref_cloud_ai_enabled"
        private const val KEY_DEFAULT_FORMAT = "pref_default_format"
        private const val KEY_AUTO_REMINDER_DETECTION = "pref_auto_reminder_detection"
        private const val KEY_AUTO_REMINDER_SCHEDULING = "pref_auto_reminder_scheduling"
    }
}
