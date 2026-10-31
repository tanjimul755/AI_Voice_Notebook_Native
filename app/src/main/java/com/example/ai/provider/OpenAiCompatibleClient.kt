package com.example.ai.provider

import android.util.Log
import com.example.ai.model.AiProviderType
import com.example.ai.model.AiTask
import com.example.ai.usage.AiUsageTracker
import com.example.reminder.DetectedReminder
import com.example.reminder.ReminderAction
import com.example.transcription.GeminiTranscriptionFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

private const val TAG = "OpenAiCompatibleClient"
private const val DEFAULT_BASE_URL = "https://api.openai.com/v1"

/**
 * Reusable client for standard OpenAI-compatible REST APIs.
 * Supports audio transcription (/audio/transcriptions) and text/chat completions (/chat/completions).
 */
class OpenAiCompatibleClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build(),
    private val usageTracker: AiUsageTracker? = null
) {

    /**
     * Transcribes an audio file via the standard OpenAI audio transcriptions endpoint.
     */
    suspend fun transcribeAudio(
        audioFile: File,
        apiKey: String,
        modelName: String = "whisper-1",
        customEndpoint: String = ""
    ): Result<String> = withContext(Dispatchers.IO) {
        val model = modelName.ifBlank { "whisper-1" }

        if (apiKey.isBlank()) {
            return@withContext Result.failure(
                IllegalStateException("API key is not configured for OpenAI-compatible provider.")
            )
        }

        if (!audioFile.exists() || audioFile.length() == 0L) {
            return@withContext Result.failure(
                IllegalArgumentException("Audio file is missing or empty.")
            )
        }

        val baseUrl = getBaseUrl(customEndpoint)
        val url = "$baseUrl/audio/transcriptions"

        try {
            val mediaType = if (audioFile.name.endsWith(".wav", ignoreCase = true)) {
                "audio/wav".toMediaType()
            } else {
                "audio/mp4".toMediaType()
            }

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", audioFile.name, audioFile.asRequestBody(mediaType))
                .addFormDataPart("model", model)
                .addFormDataPart("response_format", "json")
                .build()

            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $apiKey")
                .post(requestBody)
                .build()

            val response = httpClient.newCall(request).execute()
            val code = response.code
            val body = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                val errMsg = parseErrorMessage(code, body)
                val isQuota = code == 429
                usageTracker?.recordFailure(AiTask.TRANSCRIPTION, AiProviderType.OPENAI_COMPATIBLE, isQuota)
                return@withContext Result.failure(IOException(errMsg))
            }

            val json = JSONObject(body)
            val text = json.optString("text").trim()

            // OpenAI whisper does not return token counts, track successful request count
            usageTracker?.recordSuccess(
                task = AiTask.TRANSCRIPTION,
                provider = AiProviderType.OPENAI_COMPATIBLE,
                promptTokens = 0L,
                completionTokens = 0L,
                totalTokens = 0L
            )

            val result = if (text.isEmpty()) "[No speech detected in audio]" else text
            Result.success(result)
        } catch (e: Exception) {
            Log.e(TAG, "OpenAI transcription error", e)
            usageTracker?.recordFailure(AiTask.TRANSCRIPTION, AiProviderType.OPENAI_COMPATIBLE)
            Result.failure(e)
        }
    }

    /**
     * Interprets a transcript using chat completions with structured JSON output.
     */
    suspend fun interpretReminder(
        transcript: String,
        apiKey: String,
        modelName: String = "gpt-4o-mini",
        customEndpoint: String = ""
    ): Result<DetectedReminder> = withContext(Dispatchers.IO) {
        val model = modelName.ifBlank { "gpt-4o-mini" }

        if (apiKey.isBlank()) {
            return@withContext Result.failure(
                IllegalStateException("API key is not configured for OpenAI-compatible provider.")
            )
        }

        if (transcript.isBlank()) {
            return@withContext Result.success(
                DetectedReminder(action = ReminderAction.IGNORE, taskSummary = "")
            )
        }

        val baseUrl = getBaseUrl(customEndpoint)
        val url = "$baseUrl/chat/completions"

        try {
            val now = System.currentTimeMillis()
            val nowFormatted = SimpleDateFormat("yyyy-MM-dd HH:mm:ss (EEEE)", Locale.US).format(Date(now))

            val systemMessage = """
                You are a smart reminder assistant for a voice notebook.
                Current Reference Time: $nowFormatted (Epoch ms: $now).

                Analyze this transcript and extract any reminder, alert, alarm, or task schedule instructions.

                TASK SUMMARY RULES (CRITICAL):
                - The "taskSummary" MUST be a short, natural, humanized action title in familiar 2nd-person imperative (আদেশ/অনুরোধসূচক ক্রিয়াপদ).
                - In Bengali: NEVER use verbal nouns like 'চেক করা', 'খাওয়া', 'দেখা' (e.g. NEVER 'App চেক করা' or 'এই অ্যাপ টা চেক করা').
                  ALWAYS use natural imperative forms like 'App টি চেক করো', 'ওষুধ খাও', 'ডাক্তারকে ফোন করো'.
                - In English: Use standard imperative verb + object (e.g. 'Check this app', 'Take medicine', 'Call doctor').
                - NEVER include reminder filler words ('please', 'remind me', 'মনে করিয়ে দিও'), pronouns ('আমাকে'), or time phrases ('৫ সেকেন্ড পর', 'in 10 seconds').

                Supported Actions:
                - "CREATE": user wants to set a new reminder or alarm.
                - "UPDATE": user wants to change time or reschedule an existing reminder.
                - "CANCEL": user wants to cancel, delete, or dismiss a reminder.
                - "IGNORE": transcript is a general note or thought, with no reminder/alarm intention.

                Respond with ONLY a valid, raw JSON object:
                {
                  "action": "CREATE" | "UPDATE" | "CANCEL" | "IGNORE",
                  "taskSummary": "natural imperative action title (e.g. 'App টি চেক করো', 'ওষুধ খাও', 'Buy milk')",
                  "relativeSeconds": <integer seconds from now if a relative duration is mentioned, e.g. 5 for '৫ সেকেন্ড পর', 10 for '10 seconds', 60 for '1 minute', or null>,
                  "targetTimestampMs": <calculated epoch timestamp in milliseconds for when the reminder should fire, or null if no explicit time>,
                  "isExplicitDateTime": <true if explicit time/date specified, false if relative or none>,
                  "targetQuery": <search keyword to find existing note for UPDATE or CANCEL, or null>
                }
            """.trimIndent()

            val requestJson = JSONObject().apply {
                put("model", model)
                val messages = JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", systemMessage)
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", transcript)
                    })
                }
                put("messages", messages)
                put("temperature", 0.0)
                put("response_format", JSONObject().apply {
                    put("type", "json_object")
                })
            }

            val result = executeChatCompletion(url, apiKey, requestJson, AiTask.REMINDER_INTERPRETATION)
            result.fold(
                onSuccess = { content ->
                    val reminder = parseReminderJson(content, transcript)
                    Result.success(reminder)
                },
                onFailure = { error ->
                    Result.failure(error)
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "OpenAI reminder interpretation error", e)
            usageTracker?.recordFailure(AiTask.REMINDER_INTERPRETATION, AiProviderType.OPENAI_COMPATIBLE)
            Result.failure(e)
        }
    }

    /**
     * Formats an existing transcript using chat completions.
     */
    suspend fun formatTranscript(
        text: String,
        targetFormat: GeminiTranscriptionFormat,
        apiKey: String,
        modelName: String = "gpt-4o-mini",
        customEndpoint: String = ""
    ): Result<String> = withContext(Dispatchers.IO) {
        val model = modelName.ifBlank { "gpt-4o-mini" }

        if (apiKey.isBlank()) {
            return@withContext Result.failure(
                IllegalStateException("API key is not configured for OpenAI-compatible provider.")
            )
        }

        if (text.isBlank()) {
            return@withContext Result.success(text)
        }

        val baseUrl = getBaseUrl(customEndpoint)
        val url = "$baseUrl/chat/completions"

        try {
            val systemMessage = """
                You are an expert multilingual language assistant.
                
                Task:
                Convert and format the following voice transcript according to these instructions:
                ${targetFormat.instruction}

                Strict Rules:
                1. Retain the exact meaning and nuances of the original text.
                2. Do not omit or add new facts.
                3. Do not include markdown codeblocks or quotes.
                4. Output ONLY the converted text.
            """.trimIndent()

            val requestJson = JSONObject().apply {
                put("model", model)
                val messages = JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", systemMessage)
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", text)
                    })
                }
                put("messages", messages)
                put("temperature", 0.2)
            }

            val result = executeChatCompletion(url, apiKey, requestJson, AiTask.TRANSCRIPT_FORMATTING)
            result.fold(
                onSuccess = { content ->
                    Result.success(content.ifBlank { text })
                },
                onFailure = { error ->
                    Result.failure(error)
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "OpenAI formatting error", e)
            usageTracker?.recordFailure(AiTask.TRANSCRIPT_FORMATTING, AiProviderType.OPENAI_COMPATIBLE)
            Result.failure(e)
        }
    }

    /**
     * Lightweight test connection to verify API key, endpoint, and model availability.
     */
    suspend fun testConnection(
        task: AiTask,
        apiKey: String,
        modelName: String,
        customEndpoint: String = ""
    ): Result<String> = withContext(Dispatchers.IO) {
        val baseUrl = getBaseUrl(customEndpoint)
        val model = modelName.ifBlank {
            if (task == AiTask.TRANSCRIPTION) "whisper-1" else "gpt-4o-mini"
        }

        if (apiKey.isBlank()) {
            return@withContext Result.failure(
                IllegalStateException("API key is empty.")
            )
        }

        try {
            val url = "$baseUrl/chat/completions"
            val requestJson = JSONObject().apply {
                put("model", if (task == AiTask.TRANSCRIPTION) "gpt-4o-mini" else model)
                val messages = JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", "ping")
                    })
                }
                put("messages", messages)
                put("max_tokens", 5)
            }

            val result = executeChatCompletion(url, apiKey, requestJson, task)
            result.fold(
                onSuccess = {
                    Result.success("Connected successfully to OpenAI endpoint ($baseUrl)!")
                },
                onFailure = { error ->
                    Result.failure(error)
                }
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun executeChatCompletion(
        url: String,
        apiKey: String,
        bodyJson: JSONObject,
        task: AiTask
    ): Result<String> {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = bodyJson.toString().toRequestBody(mediaType)

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        return try {
            val response = httpClient.newCall(request).execute()
            val code = response.code
            val body = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                val errMsg = parseErrorMessage(code, body)
                val isQuota = code == 429
                usageTracker?.recordFailure(task, AiProviderType.OPENAI_COMPATIBLE, isQuota)
                return Result.failure(IOException(errMsg))
            }

            val json = JSONObject(body)
            recordUsageTokens(task, json)

            val choices = json.optJSONArray("choices")
            if (choices == null || choices.length() == 0) {
                return Result.failure(IOException("No response choices returned by OpenAI."))
            }

            val firstChoice = choices.getJSONObject(0)
            val message = firstChoice.optJSONObject("message")
            val content = message?.optString("content", "")?.trim() ?: ""

            Result.success(content)
        } catch (e: Exception) {
            usageTracker?.recordFailure(task, AiProviderType.OPENAI_COMPATIBLE)
            Result.failure(e)
        }
    }

    private fun recordUsageTokens(task: AiTask, responseJson: JSONObject) {
        val usage = responseJson.optJSONObject("usage") ?: return
        val promptTokens = usage.optLong("prompt_tokens", 0L)
        val completionTokens = usage.optLong("completion_tokens", 0L)
        val totalTokens = usage.optLong("total_tokens", promptTokens + completionTokens)

        usageTracker?.recordSuccess(
            task = task,
            provider = AiProviderType.OPENAI_COMPATIBLE,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            totalTokens = totalTokens
        )
    }

    private fun getBaseUrl(customEndpoint: String): String {
        return if (customEndpoint.isNotBlank()) {
            customEndpoint.trimEnd('/')
        } else {
            DEFAULT_BASE_URL
        }
    }

    private fun parseReminderJson(rawJson: String, originalTranscript: String): DetectedReminder {
        return try {
            val clean = rawJson.replace("```json", "").replace("```", "").trim()
            val json = JSONObject(clean)
            val actionStr = json.optString("action", "CREATE").uppercase(Locale.ROOT)
            val action = try {
                ReminderAction.valueOf(actionStr)
            } catch (_: Exception) {
                ReminderAction.CREATE
            }
            val rawSummary = json.optString("taskSummary").ifBlank { originalTranscript.take(50) }
            val taskSummary = com.example.util.TitleGenerator.humanizeActionTitle(rawSummary)
            val jsonRelativeSec = if (json.has("relativeSeconds") && !json.isNull("relativeSeconds")) {
                json.optLong("relativeSeconds", -1L).takeIf { it > 0 }
            } else {
                null
            }
            val relativeSec = jsonRelativeSec ?: com.example.reminder.ReminderManager.extractRelativeSeconds(originalTranscript)
            val timestamp = when {
                relativeSec != null -> System.currentTimeMillis() + (relativeSec * 1000L)
                json.has("targetTimestampMs") && !json.isNull("targetTimestampMs") -> {
                    val raw = json.getLong("targetTimestampMs")
                    if (raw in 1_000_000_000L..9_999_999_999L) raw * 1000L else raw
                }
                else -> null
            }
            val isExplicit = json.optBoolean("isExplicitDateTime", false) || relativeSec != null
            val targetQuery = json.optString("targetQuery").takeIf { it.isNotBlank() }

            DetectedReminder(
                action = action,
                taskSummary = taskSummary,
                targetTimestampMs = timestamp,
                isExplicitDateTime = isExplicit,
                targetQuery = targetQuery,
                relativeSeconds = relativeSec
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse OpenAI structured reminder JSON: $rawJson", e)
            DetectedReminder(
                action = ReminderAction.CREATE,
                taskSummary = originalTranscript.take(50),
                targetTimestampMs = null
            )
        }
    }

    private fun parseErrorMessage(code: Int, responseBody: String): String {
        val baseMsg = try {
            val json = JSONObject(responseBody)
            json.optJSONObject("error")?.optString("message") ?: ""
        } catch (_: Exception) {
            ""
        }

        return when (code) {
            400 -> "Bad request (400): ${baseMsg.ifBlank { "Invalid parameters." }}"
            401 -> "Authentication failed (401): Invalid OpenAI API key. Please check your key in Settings."
            403 -> "Forbidden (403): Access denied. Please verify your OpenAI account permissions."
            404 -> "Not found (404): Endpoint or model not found. Please verify endpoint URL and model name."
            429 -> "Quota exceeded (429): Rate limit reached or insufficient credits in your account."
            500, 502, 503 -> "Server error ($code): OpenAI service temporarily unavailable. Please retry later."
            else -> "OpenAI error ($code): ${baseMsg.ifBlank { responseBody.take(120) }}"
        }
    }
}
