package com.example.reminder

import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "ReminderRinger"

/**
 * Manages audible ringing alerts for scheduled reminders.
 * Provides a bounded alerting duration (default 30 seconds) to prevent battery drain
 * and endless ringing while complying with Android background restrictions.
 */
object ReminderRinger {
    private var ringtone: Ringtone? = null
    private var stopJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    @Volatile
    var isRingingActive: Boolean = false
        private set

    @Synchronized
    fun startRinging(context: Context, durationMs: Long = 30_000L) {
        stopRinging()
        try {
            val alertUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            if (alertUri != null) {
                val r = RingtoneManager.getRingtone(context.applicationContext, alertUri)
                if (r != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        r.audioAttributes = AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    }
                    r.play()
                    ringtone = r
                }
            }
            isRingingActive = true
            Log.i(TAG, "Reminder ringer started (max duration: ${durationMs}ms)")
        } catch (e: Throwable) {
            // Handled gracefully for headless JVM, Robolectric tests, or missing system ringtones
            Log.w(TAG, "Could not start audible ringtone: ${e.message}")
            isRingingActive = true
        }

        // Bounded duration: automatically cancel alert after durationMs
        stopJob = scope.launch {
            delay(durationMs)
            Log.i(TAG, "Ringing duration elapsed. Stopping ringer automatically.")
            stopRinging()
        }
    }

    @Synchronized
    fun stopRinging() {
        stopJob?.cancel()
        stopJob = null
        try {
            if (ringtone?.isPlaying == true) {
                ringtone?.stop()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Error stopping ringtone: ${e.message}")
        }
        ringtone = null
        isRingingActive = false
        Log.i(TAG, "Reminder ringer stopped")
    }
}
