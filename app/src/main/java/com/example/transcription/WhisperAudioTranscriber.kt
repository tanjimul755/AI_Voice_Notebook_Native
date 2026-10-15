package com.example.transcription

import android.content.Context
import android.os.Environment
import android.util.Log
import com.example.audio.AudioDecoder
import dev.ffmpegkit.whisper.Whisper
import dev.ffmpegkit.whisper.WhisperConfig
import dev.ffmpegkit.whisper.WhisperModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val TAG = "WhisperAudioTranscriber"
private const val MODEL_NAME = "ggml-tiny.bin"
// Official Whisper.cpp multilingual tiny model mirror on Hugging Face (supports English, Bangla, and 97 other languages)
private const val MODEL_DOWNLOAD_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin"

/**
 * Concrete offline implementation of AudioTranscriber using whisper.cpp (WhisperKit).
 *
 * All audio decoding and transcription executes 100% locally on the device CPU.
 * Audio is decoded from .m4a to 16kHz mono WAV via AudioDecoder, fed into whisper.cpp,
 * and the temporary WAV file is deleted immediately after transcription completes.
 */
class WhisperAudioTranscriber(
    private val context: Context
) : AudioTranscriber {

    private var activeModel: WhisperModel? = null
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val modelsDir: File
        get() = File(context.filesDir, "models").apply { if (!exists()) mkdirs() }

    override fun getModelStatus(): WhisperModelStatus {
        val modelFile = findModelFile()
        return if (modelFile != null && modelFile.exists() && modelFile.length() > 10_000_000L) {
            WhisperModelStatus(
                isAvailable = true,
                modelFile = modelFile,
                modelName = MODEL_NAME,
                modelSizeBytes = modelFile.length(),
                statusDescription = "Model ready (${modelFile.length() / (1024 * 1024)} MB). Fully offline."
            )
        } else {
            WhisperModelStatus(
                isAvailable = false,
                modelFile = null,
                modelName = MODEL_NAME,
                modelSizeBytes = 77_700_000L,
                statusDescription = "Model not found. Download ggml-tiny.bin (~75 MB) once to transcribe offline."
            )
        }
    }

    /**
     * Searches standard locations for the ggml-tiny.bin model:
     * 1. App internal filesDir/models/ggml-tiny.bin
     * 2. App external filesDir/models/ggml-tiny.bin
     * 3. Device Downloads directory /sdcard/Download/ggml-tiny.bin
     */
    private fun findModelFile(): File? {
        val internalFile = File(modelsDir, MODEL_NAME)
        if (internalFile.exists() && internalFile.length() > 10_000_000L) {
            return internalFile
        }

        val externalFilesDir = context.getExternalFilesDir(null)
        if (externalFilesDir != null) {
            val externalFile = File(externalFilesDir, "models/$MODEL_NAME")
            if (externalFile.exists() && externalFile.length() > 10_000_000L) {
                return externalFile
            }
        }

        try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val downloadFile = File(downloadsDir, MODEL_NAME)
            if (downloadFile.exists() && downloadFile.length() > 10_000_000L) {
                return downloadFile
            }
        } catch (_: Exception) {}

        return null
    }

    override suspend fun downloadModel(onProgress: (Float) -> Unit): Result<File> = withContext(Dispatchers.IO) {
        val targetFile = File(modelsDir, MODEL_NAME)
        val tempFile = File(modelsDir, "$MODEL_NAME.tmp")

        try {
            val request = Request.Builder()
                .url(MODEL_DOWNLOAD_URL)
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(IOException("Download failed with HTTP ${response.code}: ${response.message}"))
            }

            val body = response.body ?: return@withContext Result.failure(IOException("Empty response body from model repository"))
            val contentLength = body.contentLength()

            body.byteStream().use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalRead = 0L

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        if (contentLength > 0) {
                            val progress = (totalRead.toFloat() / contentLength.toFloat()).coerceIn(0f, 1f)
                            onProgress(progress)
                        }
                    }
                    output.flush()
                }
            }

            if (tempFile.length() < 10_000_000L) {
                tempFile.delete()
                return@withContext Result.failure(IOException("Downloaded model file is incomplete or corrupted"))
            }

            if (targetFile.exists()) {
                targetFile.delete()
            }
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }

            Log.i(TAG, "Whisper model downloaded successfully to ${targetFile.absolutePath}")
            onProgress(1.0f)
            Result.success(targetFile)
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading Whisper model", e)
            if (tempFile.exists()) tempFile.delete()
            Result.failure(e)
        }
    }

    override suspend fun transcribe(audioFile: File, language: String): Result<String> = withContext(Dispatchers.Default) {
        val modelFile = findModelFile()
            ?: return@withContext Result.failure(
                IllegalStateException("Whisper model ($MODEL_NAME) is not available. Please download the model first.")
            )

        // Step 1: Decode existing .m4a into 16kHz mono WAV in app cache directory
        val tempWavFile = File(context.cacheDir, "transcribe_${System.currentTimeMillis()}.wav")
        val decodeResult = AudioDecoder.decodeM4aTo16kWav(audioFile, tempWavFile)
        if (decodeResult.isFailure) {
            return@withContext Result.failure(
                decodeResult.exceptionOrNull() ?: Exception("Audio decoding failed")
            )
        }

        try {
            // Step 2: Load or reuse WhisperModel instance
            val model = activeModel ?: run {
                try {
                    val loaded = Whisper.loadModel(context, modelFile.absolutePath)
                    activeModel = loaded
                    loaded
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to load Whisper model: ${e.message}", e)
                    return@withContext Result.failure(
                        Exception("Failed to load Whisper model on this device: ${e.localizedMessage}")
                    )
                }
            }

            // Step 3: Run offline transcription using whisper.cpp
            val config = WhisperConfig(
                language = if (language.isNotBlank()) language else "auto",
                translate = false,
                threads = 4,
                maxSegmentLength = 0,
                printTimestamps = false
            )

            val startTime = System.currentTimeMillis()
            val result = Whisper.transcribe(model, tempWavFile.absolutePath, config)
            val durationMs = System.currentTimeMillis() - startTime
            Log.i(TAG, "Transcription finished in ${durationMs}ms: ${result.text}")

            val transcribedText = result.text.trim()
            if (transcribedText.isEmpty()) {
                Result.success("[No speech detected in audio]")
            } else {
                Result.success(transcribedText)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Transcription failed", e)
            Result.failure(Exception("Transcription error: ${e.localizedMessage}"))
        } finally {
            // Always clean up the temporary 16kHz WAV file
            if (tempWavFile.exists()) {
                tempWavFile.delete()
            }
        }
    }

    override fun release() {
        activeModel?.let {
            try {
                Whisper.releaseModel(it)
            } catch (e: Throwable) {
                Log.e(TAG, "Error releasing Whisper model", e)
            }
        }
        activeModel = null
    }
}
