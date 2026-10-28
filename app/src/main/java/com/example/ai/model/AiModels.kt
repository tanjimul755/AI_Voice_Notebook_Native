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

/**
 * Task-specific AI configuration.
 * Each task has its own provider, model, endpoint, and enabled status.
 */
data class TaskAiConfig(
    val task: AiTask,
    val enabled: Boolean = true,
    val provider: AiProviderType = AiProviderType.GEMINI,
    val model: String = getDefaultModel(task, AiProviderType.GEMINI),
    val customEndpoint: String = ""
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
