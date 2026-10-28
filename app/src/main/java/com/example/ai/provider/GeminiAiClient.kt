package com.example.ai.provider

import android.util.Base64
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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

private const val TAG = "GeminiAiClient"
const val DEFAULT_GEMINI_MODEL = "gemini-3.5-flash"

/**
 * Direct client for Google Gemini API supporting multimodal audio transcription,
 * structured reminder interpretation, and transcript formatting.
 */
class GeminiAiClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build(),
    private val usageTracker: AiUsageTracker? = null
) {

    /**
     * Transcribes an audio file (.m4a / .wav) with the chosen language formatting.
     */
    suspend fun transcribeAudio(
        audioFile: File,
        format: GeminiTranscriptionFormat,
        apiKey: String,
        modelName: String = DEFAULT_GEMINI_MODEL,
        customEndpoint: String = ""
    ): Result<String> = withContext(Dispatchers.IO) {
        val effectiveModel = when (modelName.trim()) {
            "gemini-3.8-flash", "gemini-2.5-flash", "" -> DEFAULT_GEMINI_MODEL
            else -> modelName.trim()
        }

        if (apiKey.isBlank()) {
            return@withContext Result.failure(
                IllegalStateException("Gemini API key is not configured.")
            )
        }

        if (!audioFile.exists() || audioFile.length() == 0L) {
            return@withContext Result.failure(
                IllegalArgumentException("Audio file is missing or empty.")
            )
        }

        if (audioFile.length() > 20 * 1024 * 1024L) {
            return@withContext Result.failure(
                IllegalArgumentException("Audio file is too large for inline AI transcription (>20 MB).")
            )
        }

        try {
            val audioBytes = audioFile.readBytes()
            val base64Audio = Base64.encodeToString(audioBytes, Base64.NO_WRAP)
            val mimeType = when {
                audioFile.name.endsWith(".wav", ignoreCase = true) -> "audio/wav"
                audioFile.name.endsWith(".mp3", ignoreCase = true) -> "audio/mp3"
                audioFile.name.endsWith(".aac", ignoreCase = true) -> "audio/aac"
                audioFile.name.endsWith(".m4a", ignoreCase = true) -> "audio/m4a"
                audioFile.name.endsWith(".mp4", ignoreCase = true) -> "audio/mp4"
                else -> "audio/m4a"
            }

            val promptText = """
                You are an expert multilingual speech-to-text transcriber specializing in English, Bengali (Bangla), and conversational speech.

                Task:
                Listen to the attached audio recording and transcribe every spoken word accurately.

                Requested Output Format:
                ${format.instruction}

                Strict Output Rules:
                1. Capture words clearly in English or Bengali as spoken.
                2. Punctuate appropriately based on natural pauses and speech cadence.
                3. Do NOT summarize or omit anything.
                4. Do NOT add conversational filler, markdown formatting or quotes.
                5. Output ONLY the raw transcribed text.
            """.trimIndent()

            val requestJson = JSONObject().apply {
                val contentsArray = JSONArray().apply {
                    val contentObj = JSONObject().apply {
                        val partsArray = JSONArray().apply {
                            put(JSONObject().apply { put("text", promptText) })
                            put(JSONObject().apply {
                                put("inlineData", JSONObject().apply {
                                    put("mimeType", mimeType)
                                    put("data", base64Audio)
                                })
                            })
                        }
                        put("parts", partsArray)
                    }
                    put(contentObj)
                }
                put("contents", contentsArray)

                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.0)
                })

                val safetyArray = JSONArray().apply {
                    listOf(
                        "HARM_CATEGORY_HARASSMENT",
                        "HARM_CATEGORY_HATE_SPEECH",
                        "HARM_CATEGORY_SEXUALLY_EXPLICIT",
                        "HARM_CATEGORY_DANGEROUS_CONTENT"
                    ).forEach { cat ->
                        put(JSONObject().apply {
                            put("category", cat)
                            put("threshold", "BLOCK_NONE")
                        })
                    }
                }
                put("safetySettings", safetyArray)
            }

            val url = buildUrl(effectiveModel, apiKey, customEndpoint)
            var responseResult = executeRequest(url, requestJson, AiTask.TRANSCRIPTION)

            // If a non-default model failed (e.g. timeout or 404), fall back to DEFAULT_GEMINI_MODEL
            if (responseResult.isFailure && effectiveModel != DEFAULT_GEMINI_MODEL) {
                Log.w(TAG, "Transcription with $effectiveModel failed (${responseResult.exceptionOrNull()?.message}), retrying with $DEFAULT_GEMINI_MODEL")
                val fallbackUrl = buildUrl(DEFAULT_GEMINI_MODEL, apiKey, customEndpoint)
                responseResult = executeRequest(fallbackUrl, requestJson, AiTask.TRANSCRIPTION)
            }

            responseResult.fold(
                onSuccess = { responseJson ->
                    val transcript = extractTextFromCandidates(responseJson)
                    val result = if (transcript.isEmpty()) "[No speech detected in audio]" else transcript
                    Result.success(result)
                },
                onFailure = { error ->
                    Result.failure(error)
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Gemini transcription failed", e)
            usageTracker?.recordFailure(AiTask.TRANSCRIPTION, AiProviderType.GEMINI)
            Result.failure(e)
        }
    }

    /**
     * Interprets a transcript to extract reminder action, task summary, and schedule.
     */
    suspend fun interpretReminder(
        transcript: String,
        apiKey: String,
        modelName: String = DEFAULT_GEMINI_MODEL,
        customEndpoint: String = ""
    ): Result<DetectedReminder> = withContext(Dispatchers.IO) {
        val model = modelName.ifBlank { DEFAULT_GEMINI_MODEL }

        if (apiKey.isBlank()) {
            return@withContext Result.failure(
                IllegalStateException("Gemini API key is not configured.")
            )
        }

        if (transcript.isBlank()) {
            return@withContext Result.success(
                DetectedReminder(action = ReminderAction.IGNORE, taskSummary = "")
            )
        }

        try {
            val now = System.currentTimeMillis()
            val nowFormatted = SimpleDateFormat("yyyy-MM-dd HH:mm:ss (EEEE)", Locale.US).format(Date(now))

            val promptText = """
                You are a smart reminder and task scheduling assistant for a voice notebook.
                Current Reference Time: $nowFormatted (Epoch ms: $now).

                Analyze this transcript and extract any reminder, alert, alarm, or task schedule instructions.
                Transcript: "$transcript"

                Supported Actions:
                - "CREATE": user wants to set a new reminder or alarm.
                - "UPDATE": user wants to change time or reschedule an existing reminder.
                - "CANCEL": user wants to cancel, delete, or dismiss a reminder.
                - "IGNORE": transcript is a general note or thought, with no reminder/alarm intention.

                Respond with ONLY a valid, raw JSON object in this exact schema (no markdown, no backticks):
                {
                  "action": "CREATE" | "UPDATE" | "CANCEL" | "IGNORE",
                  "taskSummary": "concise description of the task to be reminded of",
                  "relativeSeconds": <integer seconds from now if a relative duration is mentioned, e.g. 10 for 'after 10 seconds', 20 for 'after 20 seconds', 60 for 'after 1 minute', or null>,
                  "targetTimestampMs": <calculated epoch timestamp in milliseconds for when the reminder should fire, or null if no explicit time is given>,
                  "isExplicitDateTime": <true if explicit time/date or relative seconds is specified, false if none>,
                  "targetQuery": <search keyword to find existing note for UPDATE or CANCEL, or null>
                }
            """.trimIndent()

            val requestJson = JSONObject().apply {
                val contentsArray = JSONArray().apply {
                    val contentObj = JSONObject().apply {
                        val partsArray = JSONArray().apply {
                            put(JSONObject().apply { put("text", promptText) })
                        }
                        put("parts", partsArray)
                    }
                    put(contentObj)
                }
                put("contents", contentsArray)

                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.0)
                    put("responseMimeType", "application/json")
                })
            }

            val url = buildUrl(model, apiKey, customEndpoint)
            val responseResult = executeRequest(url, requestJson, AiTask.REMINDER_INTERPRETATION)

            responseResult.fold(
                onSuccess = { responseJson ->
                    val rawText = extractTextFromCandidates(responseJson)
                    val reminder = parseReminderJson(rawText, transcript)
                    Result.success(reminder)
                },
                onFailure = { error ->
                    Result.failure(error)
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Gemini reminder interpretation failed", e)
            usageTracker?.recordFailure(AiTask.REMINDER_INTERPRETATION, AiProviderType.GEMINI)
            Result.failure(e)
        }
    }

    /**
     * Formats an existing transcript into English, Pure Bangla, Banglish, or Auto.
     */
    suspend fun formatTranscript(
        text: String,
        targetFormat: GeminiTranscriptionFormat,
        apiKey: String,
        modelName: String = DEFAULT_GEMINI_MODEL,
        customEndpoint: String = ""
    ): Result<String> = withContext(Dispatchers.IO) {
        val model = modelName.ifBlank { DEFAULT_GEMINI_MODEL }

        if (apiKey.isBlank()) {
            return@withContext Result.failure(
                IllegalStateException("Gemini API key is not configured.")
            )
        }

        if (text.isBlank()) {
            return@withContext Result.success(text)
        }

        try {
            val promptText = """
                You are an expert multilingual language and transliteration assistant.
                
                Task:
                Convert and format the following voice transcript according to these instructions:
                ${targetFormat.instruction}

                Strict Rules:
                1. Retain the exact meaning and nuances of the original text.
                2. Do not omit or add new facts.
                3. Do not include markdown codeblocks or quotes.
                4. Output ONLY the converted text.

                Input Text:
                $text
            """.trimIndent()

            val requestJson = JSONObject().apply {
                val contentsArray = JSONArray().apply {
                    val contentObj = JSONObject().apply {
                        val partsArray = JSONArray().apply {
                            put(JSONObject().apply { put("text", promptText) })
                        }
                        put("parts", partsArray)
                    }
                    put(contentObj)
                }
                put("contents", contentsArray)

                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.2)
                })
            }

            val url = buildUrl(model, apiKey, customEndpoint)
            val responseResult = executeRequest(url, requestJson, AiTask.TRANSCRIPT_FORMATTING)

            responseResult.fold(
                onSuccess = { responseJson ->
                    val result = extractTextFromCandidates(responseJson)
                    Result.success(result.ifBlank { text })
                },
                onFailure = { error ->
                    Result.failure(error)
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Gemini transcript formatting failed", e)
            usageTracker?.recordFailure(AiTask.TRANSCRIPT_FORMATTING, AiProviderType.GEMINI)
            Result.failure(e)
        }
    }

    /**
     * Performs a lightweight test call to verify API key and model availability.
     */
    suspend fun testConnection(
        task: AiTask,
        apiKey: String,
        modelName: String = DEFAULT_GEMINI_MODEL,
        customEndpoint: String = ""
    ): Result<String> = withContext(Dispatchers.IO) {
        val model = modelName.ifBlank { DEFAULT_GEMINI_MODEL }

        if (apiKey.isBlank()) {
            return@withContext Result.failure(
                IllegalStateException("Gemini API key is empty.")
            )
        }

        try {
            val requestJson = JSONObject().apply {
                val contentsArray = JSONArray().apply {
                    val contentObj = JSONObject().apply {
                        val partsArray = JSONArray().apply {
                            put(JSONObject().apply { put("text", "Ping test. Respond with 'OK'.") })
                        }
                        put("parts", partsArray)
                    }
                    put(contentObj)
                }
                put("contents", contentsArray)
                put("generationConfig", JSONObject().apply {
                    put("maxOutputTokens", 10)
                })
            }

            val url = buildUrl(model, apiKey, customEndpoint)
            val result = executeRequest(url, requestJson, task)

            result.fold(
                onSuccess = {
                    Result.success("Connected successfully to Gemini ($model)!")
                },
                onFailure = { error ->
                    Result.failure(error)
                }
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun buildUrl(model: String, apiKey: String, customEndpoint: String): String {
        val base = if (customEndpoint.isNotBlank()) {
            customEndpoint.trimEnd('/')
        } else {
            "https://generativelanguage.googleapis.com"
        }
        return "$base/v1beta/models/$model:generateContent?key=$apiKey"
    }

    private fun executeRequest(
        url: String,
        bodyJson: JSONObject,
        task: AiTask
    ): Result<JSONObject> {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = bodyJson.toString().toRequestBody(mediaType)

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        return try {
            val response = httpClient.newCall(request).execute()
            val code = response.code
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                val errorMessage = parseErrorMessage(code, responseBody)
                val isQuota = code == 429
                usageTracker?.recordFailure(task, AiProviderType.GEMINI, isQuotaBlocked = isQuota)
                return Result.failure(IOException(errorMessage))
            }

            val json = JSONObject(responseBody)
            recordUsageTokens(task, json)
            Result.success(json)
        } catch (e: java.net.SocketTimeoutException) {
            usageTracker?.recordFailure(task, AiProviderType.GEMINI)
            Result.failure(IOException("Gemini API connection timed out. Please check your network connection."))
        } catch (e: Exception) {
            usageTracker?.recordFailure(task, AiProviderType.GEMINI)
            Result.failure(e)
        }
    }

    private fun recordUsageTokens(task: AiTask, responseJson: JSONObject) {
        val usage = responseJson.optJSONObject("usageMetadata") ?: return
        val promptTokens = usage.optLong("promptTokenCount", 0L)
        val candidateTokens = usage.optLong("candidatesTokenCount", 0L)
        val totalTokens = usage.optLong("totalTokenCount", promptTokens + candidateTokens)

        usageTracker?.recordSuccess(
            task = task,
            provider = AiProviderType.GEMINI,
            promptTokens = promptTokens,
            completionTokens = candidateTokens,
            totalTokens = totalTokens
        )
    }

    private fun extractTextFromCandidates(responseJson: JSONObject): String {
        val candidates = responseJson.optJSONArray("candidates") ?: return ""
        if (candidates.length() == 0) return ""
        val first = candidates.getJSONObject(0)
        val content = first.optJSONObject("content") ?: return ""
        val parts = content.optJSONArray("parts") ?: return ""

        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val part = parts.getJSONObject(i)
            sb.append(part.optString("text", ""))
        }
        return sb.toString().trim()
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
            val taskSummary = json.optString("taskSummary").ifBlank { originalTranscript.take(50) }
            val relativeSec = if (json.has("relativeSeconds") && !json.isNull("relativeSeconds")) {
                json.optLong("relativeSeconds", -1L).takeIf { it > 0 }
            } else {
                null
            }
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
                targetQuery = targetQuery
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse structured reminder JSON: $rawJson", e)
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
            400 -> "Bad request (400): ${baseMsg.ifBlank { "Invalid request parameters" }}"
            401, 403 -> "Authentication failed ($code): Invalid or unauthorized Gemini API key. Please verify your API key in Settings."
            404 -> "Gemini model not found (404): ${baseMsg.ifBlank { "The requested model is not available. Please verify the model name (default: gemini-3.5-flash)." }}"
            429 -> "Gemini quota exceeded (429): Rate limit reached or quota exhausted. Please retry later or check billing."
            500, 503 -> "Gemini server error ($code): Google AI services temporarily unavailable. Please retry later."
            else -> "Gemini API error ($code): ${baseMsg.ifBlank { responseBody.take(120) }}"
        }
    }
}
