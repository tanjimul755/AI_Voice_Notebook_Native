package com.example.audio

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manages persistent audio file storage on the Android device's private internal storage.
 * Internal storage does not require runtime storage permissions, cannot be tampered with
 * by external apps, and persists across application restarts.
 */
class AudioFileManager(private val context: Context) {

    private val recordingsDir: File
        get() {
            val dir = File(context.filesDir, "voice_notes")
            if (!dir.exists()) {
                dir.mkdirs()
            }
            return dir
        }

    /**
     * Creates a new unique file handle in the voice_notes directory for a recording session.
     */
    fun createOutputFile(): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "VN_${timeStamp}.m4a"
        return File(recordingsDir, fileName)
    }

    /**
     * Checks if a file exists on disk.
     */
    fun fileExists(filePath: String): Boolean {
        val file = File(filePath)
        return file.exists() && file.length() > 0
    }

    /**
     * Returns the size of an audio file in bytes.
     */
    fun getFileSize(filePath: String): Long {
        val file = File(filePath)
        return if (file.exists()) file.length() else 0L
    }

    /**
     * Safely deletes an audio file from persistent storage.
     */
    fun deleteAudioFile(filePath: String): Boolean {
        return try {
            val file = File(filePath)
            if (file.exists()) {
                file.delete()
            } else {
                true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    companion object {
        /**
         * Formats milliseconds into a human-readable mm:ss or hh:mm:ss format.
         */
        fun formatDuration(durationMs: Long): String {
            val totalSeconds = (durationMs / 1000).coerceAtLeast(0)
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            val hours = minutes / 60

            return if (hours > 0) {
                String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes % 60, seconds)
            } else {
                String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
            }
        }

        /**
         * Formats byte size into readable KB or MB.
         */
        fun formatFileSize(bytes: Long): String {
            if (bytes <= 0) return "0 KB"
            val kb = bytes / 1024.0
            return if (kb >= 1024.0) {
                String.format(Locale.getDefault(), "%.1f MB", kb / 1024.0)
            } else {
                String.format(Locale.getDefault(), "%.0f KB", kb)
            }
        }

        /**
         * Formats epoch timestamp into a friendly date/time string.
         */
        fun formatTimestamp(timestampMs: Long): String {
            val sdf = SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.getDefault())
            return sdf.format(Date(timestampMs))
        }
    }
}
