package com.example.ai

import android.content.Context
import android.util.Log
import com.example.ai.model.AiProviderType
import com.example.ai.model.AiTask
import com.example.ai.model.ApiProfile
import com.example.ai.model.TaskAiConfig
import com.example.ai.provider.GeminiAiClient
import com.example.ai.provider.OpenAiCompatibleClient
import com.example.ai.security.SecureKeyStorage
import com.example.ai.usage.AiUsageTracker
import com.example.reminder.DetectedReminder
import com.example.transcription.GeminiTranscriptionFormat
import java.io.File

private const val TAG = "AiManager"

/**
 * Central coordinator for task-specific multi-provider AI operations,
 * profile management, automatic quota failover, and Full AI Mode policies.
 */
class AiManager(
    private val context: Context,
    val secureKeyStorage: SecureKeyStorage = SecureKeyStorage(context),
    val usageTracker: AiUsageTracker = AiUsageTracker(context),
    private val geminiClient: GeminiAiClient = GeminiAiClient(usageTracker = usageTracker),
    private val openAiClient: OpenAiCompatibleClient = OpenAiCompatibleClient(usageTracker = usageTracker)
) {

    /**
     * Resolves the ordered candidate profiles for a given task:
     * 1. Preferred profile (if enabled)
     * 2. Fallback profiles in explicit priority order (if enabled)
     * 3. Other enabled profiles matching provider or remaining enabled profiles
     */
    fun resolveCandidateProfiles(
        task: AiTask,
        config: TaskAiConfig,
        allProfiles: List<ApiProfile> = emptyList()
    ): List<ApiProfile> {
        val enabled = allProfiles.filter { it.isEnabled }
        if (enabled.isEmpty()) {
            return listOf(
                ApiProfile(
                    id = "default_${config.provider.id}",
                    name = "${config.provider.displayName} Default",
                    provider = config.provider,
                    model = config.model,
                    customEndpoint = config.customEndpoint
                )
            )
        }

        val candidates = mutableListOf<ApiProfile>()

        // 1. Preferred profile
        val preferred = config.preferredProfileId?.let { id -> enabled.find { it.id == id } }
            ?: enabled.firstOrNull { it.provider == config.provider }
            ?: enabled.first()
        candidates.add(preferred)

        // 2. Fallback profiles in order
        for (fallbackId in config.fallbackProfileIds) {
            val p = enabled.find { it.id == fallbackId }
            if (p != null && candidates.none { it.id == p.id }) {
                candidates.add(p)
            }
        }

        // 3. Other enabled profiles as safety net
        for (p in enabled) {
            if (candidates.none { it.id == p.id }) {
                candidates.add(p)
            }
        }

        return candidates
    }

    /**
     * Transcribes audio using candidate profiles with automatic failover on quota / rate limit.
     */
    suspend fun transcribeAudio(
        audioFile: File,
        format: GeminiTranscriptionFormat,
        config: TaskAiConfig,
        isFullAiMode: Boolean,
        isExplicitUserAction: Boolean,
        profiles: List<ApiProfile> = emptyList()
    ): Result<String> {
        if (!config.enabled) {
            return Result.failure(
                IllegalStateException("Cloud audio transcription is disabled for this task.")
            )
        }

        // Full AI Mode policy: automatic requests are disallowed if Full AI Mode is disabled
        if (!isFullAiMode && !isExplicitUserAction) {
            return Result.failure(
                IllegalStateException("Automatic cloud AI transcription is paused because Full AI Mode is turned off.")
            )
        }

        val candidates = resolveCandidateProfiles(AiTask.TRANSCRIPTION, config, profiles)
        var lastError: Throwable? = null

        for (profile in candidates) {
            val apiKey = secureKeyStorage.getProfileKey(profile.id, profile.provider)
            if (apiKey.isNullOrBlank()) {
                Log.w(TAG, "Profile '${profile.name}' has no API key configured. Trying next profile...")
                lastError = IllegalStateException("Missing API key for ${profile.name} (${profile.provider.displayName}).")
                continue
            }

            val quotaStatus = usageTracker.getProfileQuotaStatus(profile)
            if (quotaStatus.isQuotaExhausted) {
                Log.w(TAG, "Profile '${profile.name}' quota is exhausted (${quotaStatus.consumed}/${quotaStatus.limit ?: "429"}). Failing over...")
                lastError = IllegalStateException("Profile '${profile.name}' quota exhausted.")
                continue
            }

            Log.d(TAG, "Transcribing audio with profile '${profile.name}' (${profile.model})...")
            val callResult = when (profile.provider) {
                AiProviderType.GEMINI -> {
                    geminiClient.transcribeAudio(
                        audioFile = audioFile,
                        format = format,
                        apiKey = apiKey,
                        modelName = profile.model,
                        customEndpoint = profile.customEndpoint
                    )
                }
                AiProviderType.OPENAI_COMPATIBLE -> {
                    openAiClient.transcribeAudio(
                        audioFile = audioFile,
                        apiKey = apiKey,
                        modelName = profile.model,
                        customEndpoint = profile.customEndpoint
                    )
                }
            }

            if (callResult.isSuccess) {
                usageTracker.recordProfileSuccess(
                    profileId = profile.id,
                    task = AiTask.TRANSCRIPTION,
                    provider = profile.provider,
                    audioDurationMs = audioFile.length()
                )
                return callResult
            }

            val ex = callResult.exceptionOrNull()
            val msg = ex?.message ?: ""
            val isQuota = msg.contains("429") || msg.contains("quota", ignoreCase = true) || msg.contains("rate limit", ignoreCase = true)
            usageTracker.recordProfileFailure(profile.id, AiTask.TRANSCRIPTION, profile.provider, isQuotaBlocked = isQuota)
            lastError = ex
            Log.w(TAG, "Profile '${profile.name}' failed ($msg). Switching to next eligible profile...")
        }

        return Result.failure(lastError ?: IllegalStateException("All configured API profiles failed to transcribe audio."))
    }

    /**
     * Interprets transcript to extract reminder action and schedule with profile failover.
     */
    suspend fun interpretReminder(
        transcript: String,
        config: TaskAiConfig,
        isFullAiMode: Boolean,
        isExplicitUserAction: Boolean,
        profiles: List<ApiProfile> = emptyList()
    ): Result<DetectedReminder> {
        if (!config.enabled) {
            return Result.failure(
                IllegalStateException("Cloud reminder interpretation is disabled for this task.")
            )
        }

        if (!isFullAiMode && !isExplicitUserAction) {
            return Result.failure(
                IllegalStateException("Automatic cloud reminder interpretation is paused because Full AI Mode is turned off.")
            )
        }

        val candidates = resolveCandidateProfiles(AiTask.REMINDER_INTERPRETATION, config, profiles)
        var lastError: Throwable? = null

        for (profile in candidates) {
            val apiKey = secureKeyStorage.getProfileKey(profile.id, profile.provider)
            if (apiKey.isNullOrBlank()) {
                Log.w(TAG, "Profile '${profile.name}' has no API key configured. Trying next profile...")
                lastError = IllegalStateException("Missing API key for ${profile.name} (${profile.provider.displayName}).")
                continue
            }

            val quotaStatus = usageTracker.getProfileQuotaStatus(profile)
            if (quotaStatus.isQuotaExhausted) {
                Log.w(TAG, "Profile '${profile.name}' quota exhausted. Failing over...")
                lastError = IllegalStateException("Profile '${profile.name}' quota exhausted.")
                continue
            }

            Log.d(TAG, "Interpreting reminder with profile '${profile.name}' (${profile.model})...")
            val callResult = when (profile.provider) {
                AiProviderType.GEMINI -> {
                    geminiClient.interpretReminder(
                        transcript = transcript,
                        apiKey = apiKey,
                        modelName = profile.model,
                        customEndpoint = profile.customEndpoint
                    )
                }
                AiProviderType.OPENAI_COMPATIBLE -> {
                    openAiClient.interpretReminder(
                        transcript = transcript,
                        apiKey = apiKey,
                        modelName = profile.model,
                        customEndpoint = profile.customEndpoint
                    )
                }
            }

            if (callResult.isSuccess) {
                usageTracker.recordProfileSuccess(
                    profileId = profile.id,
                    task = AiTask.REMINDER_INTERPRETATION,
                    provider = profile.provider
                )
                return callResult
            }

            val ex = callResult.exceptionOrNull()
            val msg = ex?.message ?: ""
            val isQuota = msg.contains("429") || msg.contains("quota", ignoreCase = true) || msg.contains("rate limit", ignoreCase = true)
            usageTracker.recordProfileFailure(profile.id, AiTask.REMINDER_INTERPRETATION, profile.provider, isQuotaBlocked = isQuota)
            lastError = ex
            Log.w(TAG, "Profile '${profile.name}' reminder interpretation failed ($msg). Switching to next profile...")
        }

        return Result.failure(lastError ?: IllegalStateException("All configured API profiles failed to interpret reminder."))
    }

    /**
     * Converts or formats an existing transcript with profile failover.
     */
    suspend fun formatTranscript(
        text: String,
        targetFormat: GeminiTranscriptionFormat,
        config: TaskAiConfig,
        isFullAiMode: Boolean,
        isExplicitUserAction: Boolean,
        profiles: List<ApiProfile> = emptyList()
    ): Result<String> {
        if (!config.enabled) {
            return Result.failure(
                IllegalStateException("Cloud transcript formatting is disabled for this task.")
            )
        }

        if (!isFullAiMode && !isExplicitUserAction) {
            return Result.failure(
                IllegalStateException("Automatic cloud formatting is paused because Full AI Mode is turned off.")
            )
        }

        val candidates = resolveCandidateProfiles(AiTask.TRANSCRIPT_FORMATTING, config, profiles)
        var lastError: Throwable? = null

        for (profile in candidates) {
            val apiKey = secureKeyStorage.getProfileKey(profile.id, profile.provider)
            if (apiKey.isNullOrBlank()) {
                Log.w(TAG, "Profile '${profile.name}' has no API key configured. Trying next profile...")
                lastError = IllegalStateException("Missing API key for ${profile.name} (${profile.provider.displayName}).")
                continue
            }

            val quotaStatus = usageTracker.getProfileQuotaStatus(profile)
            if (quotaStatus.isQuotaExhausted) {
                Log.w(TAG, "Profile '${profile.name}' quota exhausted. Failing over...")
                lastError = IllegalStateException("Profile '${profile.name}' quota exhausted.")
                continue
            }

            Log.d(TAG, "Formatting transcript with profile '${profile.name}' (${profile.model})...")
            val callResult = when (profile.provider) {
                AiProviderType.GEMINI -> {
                    geminiClient.formatTranscript(
                        text = text,
                        targetFormat = targetFormat,
                        apiKey = apiKey,
                        modelName = profile.model,
                        customEndpoint = profile.customEndpoint
                    )
                }
                AiProviderType.OPENAI_COMPATIBLE -> {
                    openAiClient.formatTranscript(
                        text = text,
                        targetFormat = targetFormat,
                        apiKey = apiKey,
                        modelName = profile.model,
                        customEndpoint = profile.customEndpoint
                    )
                }
            }

            if (callResult.isSuccess) {
                usageTracker.recordProfileSuccess(
                    profileId = profile.id,
                    task = AiTask.TRANSCRIPT_FORMATTING,
                    provider = profile.provider
                )
                return callResult
            }

            val ex = callResult.exceptionOrNull()
            val msg = ex?.message ?: ""
            val isQuota = msg.contains("429") || msg.contains("quota", ignoreCase = true) || msg.contains("rate limit", ignoreCase = true)
            usageTracker.recordProfileFailure(profile.id, AiTask.TRANSCRIPT_FORMATTING, profile.provider, isQuotaBlocked = isQuota)
            lastError = ex
            Log.w(TAG, "Profile '${profile.name}' formatting failed ($msg). Switching to next profile...")
        }

        return Result.failure(lastError ?: IllegalStateException("All configured API profiles failed to format transcript."))
    }

    /**
     * Tests connectivity for a specific API profile.
     */
    suspend fun testProfile(profile: ApiProfile, task: AiTask = AiTask.TRANSCRIPTION): Result<String> {
        val apiKey = secureKeyStorage.getProfileKey(profile.id, profile.provider)
        if (apiKey.isNullOrBlank()) {
            return Result.failure(
                IllegalStateException("No API key configured for profile '${profile.name}' (${profile.provider.displayName}).")
            )
        }

        return when (profile.provider) {
            AiProviderType.GEMINI -> {
                geminiClient.testConnection(
                    task = task,
                    apiKey = apiKey,
                    modelName = profile.model,
                    customEndpoint = profile.customEndpoint
                )
            }
            AiProviderType.OPENAI_COMPATIBLE -> {
                openAiClient.testConnection(
                    task = task,
                    apiKey = apiKey,
                    modelName = profile.model,
                    customEndpoint = profile.customEndpoint
                )
            }
        }
    }

    /**
     * Tests connectivity for a given task configuration.
     */
    suspend fun testTaskConfig(config: TaskAiConfig): Result<String> {
        val apiKey = secureKeyStorage.getApiKey(config.provider)
        if (apiKey.isNullOrBlank()) {
            return Result.failure(
                IllegalStateException("No API key configured for ${config.provider.displayName}.")
            )
        }

        return when (config.provider) {
            AiProviderType.GEMINI -> {
                geminiClient.testConnection(
                    task = config.task,
                    apiKey = apiKey,
                    modelName = config.model,
                    customEndpoint = config.customEndpoint
                )
            }
            AiProviderType.OPENAI_COMPATIBLE -> {
                openAiClient.testConnection(
                    task = config.task,
                    apiKey = apiKey,
                    modelName = config.model,
                    customEndpoint = config.customEndpoint
                )
            }
        }
    }
}

