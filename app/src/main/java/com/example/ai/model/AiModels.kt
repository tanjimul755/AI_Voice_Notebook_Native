package com.example.ai.model

import com.example.transcription.GeminiTranscriptionFormat

/**
 * Supported tasks that can be independently configured with AI providers.
 */
enum class AiTask(
    val id: String,
    val displayName: String,
    val description: String
) {
    TRANSCRIPTION(
        id = "transcription",
        displayName = "Audio Transcription",
        description = "Converts recorded audio speech into text."
    ),
    REMINDER_INTERPRETATION(
        id = "reminder_interpretation",
        displayName = "Reminder Interpretation",
        description = "Interprets transcript to extract task intent, alarm time, and updates."
    ),
    TRANSCRIPT_FORMATTING(
        id = "transcript_formatting",
        displayName = "Transcript Formatting",
        description = "Formats transcript into English, Pure Bangla, Banglish, or Auto."
    )
}

/**
 * Supported AI provider types.
 */
enum class AiProviderType(
    val id: String,
    val displayName: String,
    val defaultBaseUrl: String
) {
    GEMINI(
        id = "gemini",
        displayName = "Google Gemini",
        defaultBaseUrl = "https://generativelanguage.googleapis.com"
    ),
    OPENAI_COMPATIBLE(
        id = "openai_compatible",
        displayName = "OpenAI-Compatible",
        defaultBaseUrl = "https://api.openai.com/v1"
    )
}

enum class QuotaUnit(
    val id: String,
    val displayName: String,
    val shortSuffix: String
) {
    REQUESTS(id = "requests", displayName = "Requests", shortSuffix = "req"),
    TOKENS(id = "tokens", displayName = "Tokens", shortSuffix = "tokens"),
    AUDIO_MINUTES(id = "audio_minutes", displayName = "Audio Minutes", shortSuffix = "min"),
    CREDITS(id = "credits", displayName = "Credits", shortSuffix = "credits")
}

enum class QuotaResetPeriod(
    val id: String,
    val displayName: String
) {
    DAILY(id = "daily", displayName = "Daily"),
    MONTHLY(id = "monthly", displayName = "Monthly"),
    TOTAL(id = "total", displayName = "Total / Never")
}

data class ProfileQuotaConfig(
    val hasManualLimit: Boolean = false,
    val limit: Long = 0L,
    val unit: QuotaUnit = QuotaUnit.REQUESTS,
    val resetPeriod: QuotaResetPeriod = QuotaResetPeriod.DAILY,
    val periodStartTimestampMs: Long = System.currentTimeMillis()
)

data class ApiProfile(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val provider: AiProviderType = AiProviderType.GEMINI,
    val model: String = "gemini-3.5-flash",
    val customEndpoint: String = "",
    val isEnabled: Boolean = true,
    val quotaConfig: ProfileQuotaConfig = ProfileQuotaConfig()
)

data class ProfileQuotaStatus(
    val profileId: String,
    val profileName: String,
    val provider: AiProviderType,
    val consumed: Long,
    val limit: Long?,
    val remaining: Long?,
    val unit: QuotaUnit,
    val resetInfo: String,
    val isEstimated: Boolean,
    val isQuotaUnavailable: Boolean,
    val isQuotaExhausted: Boolean,
    val progress: Float = 0f,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val quotaBlockedCount: Int = 0
)

/**
 * Task-specific AI configuration.
 * Each task has its own provider, model, endpoint, preferred profile, and fallback profiles.
 */
data class TaskAiConfig(
    val task: AiTask,
    val enabled: Boolean = true,
    val provider: AiProviderType = AiProviderType.GEMINI,
    val model: String = getDefaultModel(task, AiProviderType.GEMINI),
    val customEndpoint: String = "",
    val preferredProfileId: String? = null,
    val fallbackProfileIds: List<String> = emptyList()
) {
    companion object {
        fun getDefaultModel(task: AiTask, provider: AiProviderType): String {
            return when (provider) {
                AiProviderType.GEMINI -> "gemini-3.5-flash"
                AiProviderType.OPENAI_COMPATIBLE -> when (task) {
                    AiTask.TRANSCRIPTION -> "whisper-1"
                    AiTask.REMINDER_INTERPRETATION -> "gpt-4o-mini"
                    AiTask.TRANSCRIPT_FORMATTING -> "gpt-4o-mini"
                }
            }
        }
    }
}

/**
 * Usage metrics record for tracking local requests and token counts.
 */
data class AiUsageRecord(
    val task: AiTask,
    val provider: AiProviderType,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val quotaBlockedCount: Int = 0,
    val promptTokens: Long = 0L,
    val completionTokens: Long = 0L,
    val totalTokens: Long = 0L
) {
    val totalRequests: Int get() = successCount + failureCount + quotaBlockedCount
}

