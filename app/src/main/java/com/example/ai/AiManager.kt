package com.example.ai

import android.content.Context
import com.example.ai.model.AiProviderType
import com.example.ai.model.AiTask
import com.example.ai.model.TaskAiConfig
import com.example.ai.provider.GeminiAiClient
import com.example.ai.provider.OpenAiCompatibleClient
import com.example.ai.security.SecureKeyStorage
import com.example.ai.usage.AiUsageTracker
import com.example.data.NotebookPreferences
import com.example.reminder.DetectedReminder
import com.example.transcription.GeminiTranscriptionFormat
import java.io.File

/**
 * Central coordinator for task-specific multi-provider AI operations,
 * credential management, usage tracking, and Full AI Mode policies.
 */
class AiManager(
    private val context: Context,
    val secureKeyStorage: SecureKeyStorage = SecureKeyStorage(context),
    val usageTracker: AiUsageTracker = AiUsageTracker(context),
    private val geminiClient: GeminiAiClient = GeminiAiClient(usageTracker = usageTracker),
    private val openAiClient: OpenAiCompatibleClient = OpenAiCompatibleClient(usageTracker = usageTracker)
) {

    /**
     * Transcribes audio using the configured provider for AiTask.TRANSCRIPTION.
     *
     * @param audioFile The local recording file (.m4a/.wav)
     * @param format Desired formatting (Auto, Pure Bangla, Banglish, English)
     * @param config The current task configuration
     * @param isFullAiMode Whether global Full AI Mode is enabled
     * @param isExplicitUserAction True if user tapped a button, false if triggered automatically
     */
    suspend fun transcribeAudio(
        audioFile: File,
        format: GeminiTranscriptionFormat,
        config: TaskAiConfig,
        isFullAiMode: Boolean,
        isExplicitUserAction: Boolean
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

        val apiKey = secureKeyStorage.getApiKey(config.provider)
        if (apiKey.isNullOrBlank()) {
            return Result.failure(
                IllegalStateException(
                    "Missing API key for ${config.provider.displayName}. Please configure the API key in Settings."
                )
            )
        }

        return when (config.provider) {
            AiProviderType.GEMINI -> {
                geminiClient.transcribeAudio(
                    audioFile = audioFile,
                    format = format,
                    apiKey = apiKey,
                    modelName = config.model,
                    customEndpoint = config.customEndpoint
                )
            }
            AiProviderType.OPENAI_COMPATIBLE -> {
                openAiClient.transcribeAudio(
                    audioFile = audioFile,
                    apiKey = apiKey,
                    modelName = config.model,
                    customEndpoint = config.customEndpoint
                )
            }
        }
    }

    /**
     * Interprets transcript to extract reminder action and schedule.
     */
    suspend fun interpretReminder(
        transcript: String,
        config: TaskAiConfig,
        isFullAiMode: Boolean,
        isExplicitUserAction: Boolean
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

        val apiKey = secureKeyStorage.getApiKey(config.provider)
        if (apiKey.isNullOrBlank()) {
            return Result.failure(
                IllegalStateException(
                    "Missing API key for ${config.provider.displayName}. Please configure the API key in Settings."
                )
            )
        }

        return when (config.provider) {
            AiProviderType.GEMINI -> {
                geminiClient.interpretReminder(
                    transcript = transcript,
                    apiKey = apiKey,
                    modelName = config.model,
                    customEndpoint = config.customEndpoint
                )
            }
            AiProviderType.OPENAI_COMPATIBLE -> {
                openAiClient.interpretReminder(
                    transcript = transcript,
                    apiKey = apiKey,
                    modelName = config.model,
                    customEndpoint = config.customEndpoint
                )
            }
        }
    }

    /**
     * Converts or formats an existing transcript into English, Pure Bangla, Banglish, or Auto.
     */
    suspend fun formatTranscript(
        text: String,
        targetFormat: GeminiTranscriptionFormat,
        config: TaskAiConfig,
        isFullAiMode: Boolean,
        isExplicitUserAction: Boolean
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

        val apiKey = secureKeyStorage.getApiKey(config.provider)
        if (apiKey.isNullOrBlank()) {
            return Result.failure(
                IllegalStateException(
                    "Missing API key for ${config.provider.displayName}. Please configure the API key in Settings."
                )
            )
        }

        return when (config.provider) {
            AiProviderType.GEMINI -> {
                geminiClient.formatTranscript(
                    text = text,
                    targetFormat = targetFormat,
                    apiKey = apiKey,
                    modelName = config.model,
                    customEndpoint = config.customEndpoint
                )
            }
            AiProviderType.OPENAI_COMPATIBLE -> {
                openAiClient.formatTranscript(
                    text = text,
                    targetFormat = targetFormat,
                    apiKey = apiKey,
                    modelName = config.model,
                    customEndpoint = config.customEndpoint
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
