package com.example.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File
import java.io.IOException

private const val TAG = "AndroidAudioRecorder"

/**
 * Android-native implementation of AudioRecorder using MediaRecorder.
 * Encodes audio using MPEG-4 container and AAC format at 44.1kHz / 128kbps,
 * compatible with standard media players.
 * Supports pause and resume on Android 7.0+ (API 24+).
 */
class AndroidAudioRecorder(
    private val context: Context
) : AudioRecorder {

    private var recorder: MediaRecorder? = null
    private var currentOutputFile: File? = null

    private var _isRecording = false
    override val isRecording: Boolean get() = _isRecording

    private var _isPaused = false
    override val isPaused: Boolean get() = _isPaused

    private fun createMediaRecorder(): MediaRecorder {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
    }

    override fun start(outputFile: File): Result<Unit> {
        return try {
            cancel() // Ensure any lingering session is cleared

            currentOutputFile = outputFile

            recorder = createMediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128000)
                setAudioSamplingRate(44100)
                setAudioChannels(1)
                setOutputFile(outputFile.absolutePath)
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaRecorder error: what=$what, extra=$extra")
                    cleanup()
                }
                prepare()
                start()
            }

            _isRecording = true
            _isPaused = false
            Result.success(Unit)
        } catch (e: IOException) {
            Log.e(TAG, "prepare() failed", e)
            cleanup()
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MediaRecorder", e)
            cleanup()
            Result.failure(e)
        }
    }

    override fun pause(): Result<Unit> {
        if (!_isRecording || _isPaused) {
            return Result.failure(IllegalStateException("Recorder is not currently recording"))
        }

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                recorder?.pause()
                _isPaused = true
                Result.success(Unit)
            } else {
                Result.failure(UnsupportedOperationException("Pause is not supported on this Android version"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pause recorder", e)
            Result.failure(e)
        }
    }

    override fun resume(): Result<Unit> {
        if (!_isRecording || !_isPaused) {
            return Result.failure(IllegalStateException("Recorder is not currently paused"))
        }

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                recorder?.resume()
                _isPaused = false
                Result.success(Unit)
            } else {
                Result.failure(UnsupportedOperationException("Resume is not supported on this Android version"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resume recorder", e)
            Result.failure(e)
        }
    }

    override fun stop(): Result<File> {
        val file = currentOutputFile
            ?: return Result.failure(IllegalStateException("No output file specified"))

        return try {
            recorder?.apply {
                stop()
                release()
            }
            recorder = null
            _isRecording = false
            _isPaused = false
            currentOutputFile = null

            if (file.exists() && file.length() > 0) {
                Result.success(file)
            } else {
                file.delete()
                Result.failure(IOException("Recorded audio file is empty or missing"))
            }
        } catch (e: RuntimeException) {
            // MediaRecorder.stop() can throw RuntimeException if called immediately without recording data
            Log.e(TAG, "stop() failed, file may be empty", e)
            cleanup()
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error stopping MediaRecorder", e)
            cleanup()
            Result.failure(e)
        }
    }

    override fun cancel() {
        cleanup()
    }

    override fun getMaxAmplitude(): Int {
        return if (_isRecording && !_isPaused) {
            try {
                recorder?.maxAmplitude ?: 0
            } catch (e: Exception) {
                0
            }
        } else {
            0
        }
    }

    private fun cleanup() {
        try {
            recorder?.let { r ->
                try {
                    r.stop()
                } catch (_: Exception) {}
                try {
                    r.reset()
                } catch (_: Exception) {}
                try {
                    r.release()
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up recorder", e)
        } finally {
            recorder = null
            _isRecording = false
            _isPaused = false
            currentOutputFile?.let {
                if (it.exists() && it.length() == 0L) {
                    it.delete()
                }
            }
            currentOutputFile = null
        }
    }
}
