package com.example.data

import android.content.Context
import android.content.SharedPreferences
import com.example.transcription.GeminiTranscriptionFormat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

import com.example.ai.model.AiProviderType
import com.example.ai.model.AiTask
import com.example.ai.model.ApiProfile
import com.example.ai.model.ProfileQuotaConfig
import com.example.ai.model.QuotaResetPeriod
import com.example.ai.model.QuotaUnit
import com.example.ai.model.TaskAiConfig
import org.json.JSONArray
import org.json.JSONObject

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
    val autoReminderScheduling: Boolean = true,
    val fullAiMode: Boolean = true,
    val apiProfiles: List<ApiProfile> = emptyList(),
    val transcriptionAiConfig: TaskAiConfig = TaskAiConfig(task = AiTask.TRANSCRIPTION),
    val reminderAiConfig: TaskAiConfig = TaskAiConfig(task = AiTask.REMINDER_INTERPRETATION),
    val formattingAiConfig: TaskAiConfig = TaskAiConfig(task = AiTask.TRANSCRIPT_FORMATTING)
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

        val fullAi = prefs.getBoolean(KEY_FULL_AI_MODE, true)
        val profiles = getAllProfiles()

        return NotebookSettings(
            autoTitleGeneration = prefs.getBoolean(KEY_AUTO_TITLE, true),
            autoTranscription = prefs.getBoolean(KEY_AUTO_TRANSCRIBE, true),
            transcriptionMode = mode,
            autoGeminiFallback = prefs.getBoolean(KEY_AUTO_FALLBACK, false),
            cloudAiEnabled = prefs.getBoolean(KEY_CLOUD_AI_ENABLED, true),
            defaultTranscriptionFormat = format,
            autoReminderDetection = prefs.getBoolean(KEY_AUTO_REMINDER_DETECTION, true),
            autoReminderScheduling = autoScheduling,
            fullAiMode = fullAi,
            apiProfiles = profiles,
            transcriptionAiConfig = loadTaskConfig(AiTask.TRANSCRIPTION, profiles),
            reminderAiConfig = loadTaskConfig(AiTask.REMINDER_INTERPRETATION, profiles),
            formattingAiConfig = loadTaskConfig(AiTask.TRANSCRIPT_FORMATTING, profiles)
        )
    }

    fun getAllProfiles(): List<ApiProfile> {
        val jsonString = prefs.getString(KEY_API_PROFILES, null)
        if (jsonString.isNullOrBlank()) {
            val defaults = createDefaultProfiles()
            saveProfilesInternal(defaults)
            return defaults
        }
        return try {
            val array = JSONArray(jsonString)
            val list = mutableListOf<ApiProfile>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val p = parseProfileJson(obj)
                list.add(p)
            }
            if (list.isEmpty()) {
                val defaults = createDefaultProfiles()
                saveProfilesInternal(defaults)
                defaults
            } else {
                list
            }
        } catch (e: Exception) {
            val defaults = createDefaultProfiles()
            saveProfilesInternal(defaults)
            defaults
        }
    }

    fun getProfile(profileId: String): ApiProfile? {
        return getAllProfiles().find { it.id == profileId }
    }

    fun saveProfile(profile: ApiProfile) {
        val current = getAllProfiles().toMutableList()
        val index = current.indexOfFirst { it.id == profile.id }
        if (index >= 0) {
            current[index] = profile
        } else {
            current.add(profile)
        }
        saveProfilesInternal(current)
        _settings.value = _settings.value.copy(apiProfiles = current)
    }

    fun deleteProfile(profileId: String) {
        val current = getAllProfiles().filter { it.id != profileId }
        saveProfilesInternal(current)
        _settings.value = _settings.value.copy(apiProfiles = current)
    }

    private fun saveProfilesInternal(profiles: List<ApiProfile>) {
        val array = JSONArray()
        for (p in profiles) {
            val obj = JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("provider", p.provider.id)
                put("model", p.model)
                put("customEndpoint", p.customEndpoint)
                put("isEnabled", p.isEnabled)
                val quotaObj = JSONObject().apply {
                    put("hasManualLimit", p.quotaConfig.hasManualLimit)
                    put("limit", p.quotaConfig.limit)
                    put("unit", p.quotaConfig.unit.id)
                    put("resetPeriod", p.quotaConfig.resetPeriod.id)
                    put("periodStartTimestampMs", p.quotaConfig.periodStartTimestampMs)
                }
                put("quotaConfig", quotaObj)
            }
            array.put(obj)
        }
        prefs.edit().putString(KEY_API_PROFILES, array.toString()).apply()
    }

    private fun parseProfileJson(obj: JSONObject): ApiProfile {
        val id = obj.optString("id", java.util.UUID.randomUUID().toString())
        val name = obj.optString("name", "Unnamed Profile")
        val providerId = obj.optString("provider", AiProviderType.GEMINI.id)
        val provider = AiProviderType.values().find { it.id == providerId } ?: AiProviderType.GEMINI
        val model = obj.optString("model", "gemini-3.5-flash")
        val customEndpoint = obj.optString("customEndpoint", "")
        val isEnabled = obj.optBoolean("isEnabled", true)

        val quotaObj = obj.optJSONObject("quotaConfig")
        val quotaConfig = if (quotaObj != null) {
            val unitId = quotaObj.optString("unit", QuotaUnit.REQUESTS.id)
            val unit = QuotaUnit.values().find { it.id == unitId } ?: QuotaUnit.REQUESTS
            val periodId = quotaObj.optString("resetPeriod", QuotaResetPeriod.DAILY.id)
            val period = QuotaResetPeriod.values().find { it.id == periodId } ?: QuotaResetPeriod.DAILY
            ProfileQuotaConfig(
                hasManualLimit = quotaObj.optBoolean("hasManualLimit", false),
                limit = quotaObj.optLong("limit", 0L),
                unit = unit,
                resetPeriod = period,
                periodStartTimestampMs = quotaObj.optLong("periodStartTimestampMs", System.currentTimeMillis())
            )
        } else {
            ProfileQuotaConfig()
        }

        return ApiProfile(
            id = id,
            name = name,
            provider = provider,
            model = model,
            customEndpoint = customEndpoint,
            isEnabled = isEnabled,
            quotaConfig = quotaConfig
        )
    }

    private fun createDefaultProfiles(): List<ApiProfile> {
        return listOf(
            ApiProfile(
                id = "default_gemini",
                name = "Google Gemini Primary",
                provider = AiProviderType.GEMINI,
                model = "gemini-3.5-flash",
                customEndpoint = "",
                isEnabled = true,
                quotaConfig = ProfileQuotaConfig(
                    hasManualLimit = true,
                    limit = 1500L,
                    unit = QuotaUnit.REQUESTS,
                    resetPeriod = QuotaResetPeriod.DAILY
                )
            ),
            ApiProfile(
                id = "default_openai",
                name = "OpenAI / Compatible",
                provider = AiProviderType.OPENAI_COMPATIBLE,
                model = "gpt-4o-mini",
                customEndpoint = "",
                isEnabled = true,
                quotaConfig = ProfileQuotaConfig(
                    hasManualLimit = false,
                    limit = 0L,
                    unit = QuotaUnit.TOKENS,
                    resetPeriod = QuotaResetPeriod.MONTHLY
                )
            )
        )
    }

    private fun loadTaskConfig(task: AiTask, profiles: List<ApiProfile> = getAllProfiles()): TaskAiConfig {
        val prefix = "task_${task.id}"
        val enabled = prefs.getBoolean("${prefix}_enabled", true)
        val providerId = prefs.getString("${prefix}_provider", AiProviderType.GEMINI.id) ?: AiProviderType.GEMINI.id
        val provider = AiProviderType.values().find { it.id == providerId } ?: AiProviderType.GEMINI
        val rawModel = prefs.getString("${prefix}_model", null)
        val model = if (provider == AiProviderType.GEMINI && (rawModel == "gemini-3.8-flash" || rawModel == "gemini-2.5-flash" || rawModel.isNullOrBlank())) {
            TaskAiConfig.getDefaultModel(task, provider)
        } else {
            rawModel ?: TaskAiConfig.getDefaultModel(task, provider)
        }
        val endpoint = prefs.getString("${prefix}_endpoint", "") ?: ""

        val savedPreferredProfile = prefs.getString("${prefix}_preferred_profile", null)
        val preferredProfileId = savedPreferredProfile ?: profiles.firstOrNull { it.provider == provider }?.id ?: "default_${provider.id}"

        val fallbacksStr = prefs.getString("${prefix}_fallback_profiles", "") ?: ""
        val fallbackProfileIds = if (fallbacksStr.isNotBlank()) {
            fallbacksStr.split(",").filter { it.isNotBlank() }
        } else {
            profiles.filter { it.id != preferredProfileId }.map { it.id }
        }

        return TaskAiConfig(
            task = task,
            enabled = enabled,
            provider = provider,
            model = model,
            customEndpoint = endpoint,
            preferredProfileId = preferredProfileId,
            fallbackProfileIds = fallbackProfileIds
        )
    }

    fun setTaskConfig(config: TaskAiConfig) {
        val prefix = "task_${config.task.id}"
        prefs.edit()
            .putBoolean("${prefix}_enabled", config.enabled)
            .putString("${prefix}_provider", config.provider.id)
            .putString("${prefix}_model", config.model)
            .putString("${prefix}_endpoint", config.customEndpoint)
            .putString("${prefix}_preferred_profile", config.preferredProfileId ?: "")
            .putString("${prefix}_fallback_profiles", config.fallbackProfileIds.joinToString(","))
            .apply()

        _settings.value = when (config.task) {
            AiTask.TRANSCRIPTION -> _settings.value.copy(transcriptionAiConfig = config)
            AiTask.REMINDER_INTERPRETATION -> _settings.value.copy(reminderAiConfig = config)
            AiTask.TRANSCRIPT_FORMATTING -> _settings.value.copy(formattingAiConfig = config)
        }
    }

    fun setFullAiMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_FULL_AI_MODE, enabled).apply()
        _settings.value = _settings.value.copy(fullAiMode = enabled)
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
        private const val KEY_FULL_AI_MODE = "pref_full_ai_mode"
        private const val KEY_API_PROFILES = "pref_api_profiles_json"
    }
}
