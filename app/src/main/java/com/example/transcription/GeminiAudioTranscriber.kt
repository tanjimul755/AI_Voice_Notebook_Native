package com.example.transcription

import android.content.Context
import android.util.Base64
import android.util.Log
import com.example.BuildConfig
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
import java.util.concurrent.TimeUnit

private const val TAG = "GeminiAudioTranscriber"
private const val MODEL_NAME = "gemini-3.5-flash"
private const val GEMINI_API_URL = "https://generativelanguage.googleapis.com/v1beta/models/$MODEL_NAME:generateContent"

/**
 * Output format choices for Gemini audio transcription.
 */
enum class GeminiTranscriptionFormat(
    val id: String,
    val displayName: String,
    val description: String,
    val instruction: String
) {
    AUTO(
        id = "auto",
        displayName = "Auto / As Spoken",
        description = "Bangla in বাংলা script, English in Latin script, preserving natural code-switching.",
        instruction = """
            Transcribe faithfully in the spoken languages:
            - Write Bengali (Bangla) speech in standard Bengali script (বাংলা হরফ).
            - Write English words in standard English (Latin) script.
            - If the speaker mixes Bengali and English (code-switching / Banglish colloquial speech such as 'ajke meeting ache', 'office theke call korbo'), transcribe each word faithfully in its respective script as spoken.
        """.trimIndent()
    ),
    PURE_BANGLA(
        id = "pure_bangla",
        displayName = "Pure Bangla (বাংলা)",
        description = "Transcribes the entire recording strictly in Bengali script (বাংলা হরফ).",
        instruction = """
            Transcribe the entire speech strictly in Bengali script (বাংলা হরফ):
            - Transcribe all Bengali words in Bengali script.
            - Even if English loan words, colloquial slang, or technical terms are used (e.g., 'meeting', 'phone', 'doctor'), transliterate and phonetically spell them in Bengali script (e.g., 'মিটিং', 'ফোন', 'ডাক্তার').
        """.trimIndent()
    ),
    ROMANIZED_BANGLA(
        id = "romanized_bangla",
        displayName = "Romanized Bangla (Banglish)",
        description = "Phonetic Latin script (e.g., 'Kemon acho? Ami bhalo achi').",
        instruction = """
            Transcribe the speech phonetically into the Roman/Latin alphabet (Romanized Bangla / Banglish):
            - Spell Bengali words phonetically using Latin letters representing colloquial, conversational pronunciation (e.g., 'Kemon achen? Ami bhalo achi. Ajke shokal theke kaaj korchi').
            - Keep English words in standard English spelling (e.g., 'office', 'project', 'schedule').
            - Capture colloquial nuances, abbreviations, and informal expressions accurately.
        """.trimIndent()
    ),
    ENGLISH(
        id = "english",
        displayName = "English Translation / English",
        description = "Transcribes English directly; translates any spoken Bengali to natural English.",
        instruction = """
            Transcribe the audio in English:
            - Any spoken English should be transcribed verbatim in English.
            - Any spoken Bengali or mixed Bangla-English speech should be accurately translated into fluent, natural English text.
        """.trimIndent()
    )
}

/**
 * Cloud-based audio transcription service powered by Google Gemini AI.
 * Transcribes original audio recordings (.m4a / .wav) with specialized support for
 * colloquial English, pure Bangla, code-switching, and Romanized Bangla (Banglish).
 */
class GeminiAudioTranscriber(
    private val context: Context
) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Checks whether a valid Gemini API key is configured in BuildConfig.
     */
    fun isApiKeyConfigured(): Boolean {
        val key = BuildConfig.GEMINI_API_KEY
        return !key.isNullOrBlank() && key != "MY_GEMINI_API_KEY"
    }

    /**
     * Sends the original audio file directly to Gemini API for transcription.
     *
     * @param audioFile The source audio file (e.g. .m4a or .wav).
     * @param format The desired transcription format (Auto, Pure Bangla, Romanized Bangla, English).
     * @return Result with the transcribed text, or error.
     */
    suspend fun transcribeAudio(
        audioFile: File,
        format: GeminiTranscriptionFormat = GeminiTranscriptionFormat.AUTO
    ): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isNullOrBlank() || apiKey == "MY_GEMINI_API_KEY") {
            return@withContext Result.failure(
                IllegalStateException(
                    "Gemini API key is not configured. Please set your GEMINI_API_KEY in the Secrets panel in AI Studio."
                )
            )
        }

        if (!audioFile.exists() || audioFile.length() == 0L) {
            return@withContext Result.failure(
                IllegalArgumentException("Audio file is missing or empty: ${audioFile.name}")
            )
        }

        if (audioFile.length() > 20 * 1024 * 1024L) {
            return@withContext Result.failure(
                IllegalArgumentException("Audio file is too large for inline AI transcription (>20 MB).")
            )
        }

        try {
            // Read audio file bytes and encode to Base64 (no line wrapping)
            val audioBytes = audioFile.readBytes()
            val base64Audio = Base64.encodeToString(audioBytes, Base64.NO_WRAP)

            val mimeType = if (audioFile.name.endsWith(".wav", ignoreCase = true)) {
                "audio/wav"
            } else {
                "audio/mp4"
            }

            val promptText = """
                You are an expert multilingual speech-to-text transcriber specializing in colloquial English, Bengali (Bangla), mixed Bangla-English speech (code-switching / Banglish), and Romanized Bangla.

                Task:
                Listen to the attached audio recording and transcribe every spoken word accurately.

                Requested Output Format:
                ${format.instruction}

                Strict Output Rules:
                1. Capture colloquial words, slang, regional accents, contractions, and technical jargon accurately.
                2. Punctuate appropriately (periods, question marks, commas) based on natural pauses and speech cadence.
                3. Do NOT summarize or omit anything.
                4. Do NOT add conversational filler, preamble, markdown formatting or quotes, or commentary (e.g., do NOT write "Here is the transcription:").
                5. Output ONLY the raw transcribed text.
            """.trimIndent()

            // Build request JSON for Gemini generateContent endpoint
            val requestJson = JSONObject().apply {
                val contentsArray = JSONArray().apply {
                    val contentObj = JSONObject().apply {
                        val partsArray = JSONArray().apply {
                            // Text prompt
                            put(JSONObject().apply {
                                put("text", promptText)
                            })
                            // Audio inlineData
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

                val generationConfig = JSONObject().apply {
                    put("temperature", 0.1)
                }
                put("generationConfig", generationConfig)
            }

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBody = requestJson.toString().toRequestBody(mediaType)

            val request = Request.Builder()
                .url("$GEMINI_API_URL?key=$apiKey")
                .post(requestBody)
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                val errorMsg = try {
                    val errJson = JSONObject(responseBody)
                    errJson.optJSONObject("error")?.optString("message") ?: response.message
                } catch (_: Exception) {
                    response.message
                }
                Log.e(TAG, "Gemini API call failed (${response.code}): $errorMsg")
                return@withContext Result.failure(
                    IOException("Gemini API error (${response.code}): $errorMsg")
                )
            }

            val jsonResponse = JSONObject(responseBody)
            val candidates = jsonResponse.optJSONArray("candidates")
            if (candidates == null || candidates.length() == 0) {
                return@withContext Result.failure(IOException("No transcription candidate returned by Gemini"))
            }

            val firstCandidate = candidates.getJSONObject(0)
            val content = firstCandidate.optJSONObject("content")
            val parts = content?.optJSONArray("parts")

            val transcriptBuilder = StringBuilder()
            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val partObj = parts.getJSONObject(i)
                    val text = partObj.optString("text")
                    transcriptBuilder.append(text)
                }
            }

            val rawTranscript = transcriptBuilder.toString().trim()
            if (rawTranscript.isEmpty()) {
                Result.success("[No speech detected in audio]")
            } else {
                Result.success(rawTranscript)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during Gemini transcription", e)
            Result.failure(e)
        }
    }
}
