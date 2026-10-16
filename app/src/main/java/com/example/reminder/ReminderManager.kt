package com.example.reminder

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.model.VoiceNote
import java.util.Calendar
import java.util.Locale
import java.util.regex.Pattern

/**
 * Result of analyzing a transcript for actionable reminders.
 */
data class DetectedReminder(
    val taskSummary: String,
    val targetTimestampMs: Long?,
    val isExplicitDateTime: Boolean
)

/**
 * BroadcastReceiver for firing scheduled voice note reminder notifications.
 */
class ReminderBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val noteId = intent.getLongExtra(EXTRA_NOTE_ID, -1L)
        val noteTitle = intent.getStringExtra(EXTRA_NOTE_TITLE) ?: "Voice Note Reminder"
        val reminderText = intent.getStringExtra(EXTRA_REMINDER_TEXT) ?: "You have a scheduled voice note reminder."

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Create channel on Android 8.0+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Voice Note Reminders",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for scheduled voice note reminders"
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            noteId.toInt(),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(noteTitle)
            .setContentText(reminderText)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(noteId.toInt().coerceAtLeast(1001), notification)
    }

    companion object {
        const val CHANNEL_ID = "voice_note_reminders"
        const val EXTRA_NOTE_ID = "extra_note_id"
        const val EXTRA_NOTE_TITLE = "extra_note_title"
        const val EXTRA_REMINDER_TEXT = "extra_reminder_text"
    }
}

/**
 * Handles reminder detection from text and scheduling system alarms.
 */
class ReminderManager(private val context: Context) {

    /**
     * Inspects transcription text for reminder keywords, intent, and temporal expressions.
     * Accurately supports English, Bengali (বাংলা), and Romanized Bangla (Banglish).
     *
     * Never invents fake dates or times: if no explicit date/time is mentioned, targetTimestampMs is null.
     */
    fun detectReminder(transcript: String): DetectedReminder? {
        if (transcript.isBlank()) return null
        val lower = transcript.lowercase(Locale.ROOT)

        // Reminder trigger indicators
        val hasReminderTrigger = lower.contains("remind me") ||
                lower.contains("remember to") ||
                lower.contains("don't forget") ||
                lower.contains("dont forget") ||
                lower.contains("meeting at") ||
                lower.contains("appointment at") ||
                lower.contains("call at") ||
                lower.contains("deadline") ||
                lower.contains("alarm at") ||
                lower.contains("মনে করিয়ে দিও") ||
                lower.contains("মনে রেখো") ||
                lower.contains("ভুলে যেও না") ||
                lower.contains("মিটিং") ||
                lower.contains("remind koro") ||
                lower.contains("mone koriye dio")

        if (!hasReminderTrigger) {
            return null
        }

        // Clean task summary
        val taskSummary = transcript.take(60).trim()

        // Attempt to parse explicit time/date
        val targetTimestamp = parseExplicitTimestamp(lower)

        return DetectedReminder(
            taskSummary = taskSummary,
            targetTimestampMs = targetTimestamp,
            isExplicitDateTime = targetTimestamp != null
        )
    }

    private fun normalizeDigits(input: String): String {
        val banglaDigits = "০১২৩৪৫৬৭৮৯"
        val sb = StringBuilder()
        for (ch in input) {
            val idx = banglaDigits.indexOf(ch)
            if (idx >= 0) {
                sb.append(idx)
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun parseExplicitTimestamp(rawText: String): Long? {
        val text = normalizeDigits(rawText)
        val now = Calendar.getInstance()

        // Check relative day offset
        var dayOffset = 0
        if (text.contains("tomorrow") || text.contains("কালকে") || text.contains("আগামীকাল") || text.contains("kaalke") || text.contains("kal")) {
            dayOffset = 1
        } else if (text.contains("day after tomorrow") || text.contains("পরশু") || text.contains("porshu")) {
            dayOffset = 2
        }

        // Regex for time: e.g. "at 5 pm", "5:30 pm", "10 am", "বিকাল ৫টায়", "রাত ৮টায়", "সকাল ৯টায়"
        // Pattern 1: standard 12-hour English "5 pm", "5:30 pm", "10:00 am"
        val timePattern12 = Pattern.compile("(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)", Pattern.CASE_INSENSITIVE)
        val matcher12 = timePattern12.matcher(text)

        if (matcher12.find()) {
            val hourStr = matcher12.group(1) ?: return null
            val minStr = matcher12.group(2) ?: "00"
            val amPm = matcher12.group(3)?.lowercase(Locale.ROOT) ?: "am"

            var hour = hourStr.toIntOrNull() ?: return null
            val min = minStr.toIntOrNull() ?: 0

            if (amPm == "pm" && hour < 12) hour += 12
            if (amPm == "am" && hour == 12) hour = 0

            val cal = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, dayOffset)
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, min)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }

            // If time is today and has already passed, bump to tomorrow
            if (dayOffset == 0 && cal.timeInMillis <= now.timeInMillis) {
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }
            return cal.timeInMillis
        }

        // Pattern 2: Bengali numerals or Bengali speech (e.g. বিকাল ৫টায়, সকাল ১০টায়, রাত ৮টায়)
        val isPmBangla = text.contains("বিকাল") || text.contains("সন্ধ্যা") || text.contains("রাত") ||
                text.contains("bikal") || text.contains("shondha") || text.contains("raat")
        val isAmBangla = text.contains("সকাল") || text.contains("ভোর") || text.contains("shokal")

        val numMatcher = Pattern.compile("(\\d{1,2})\\s*(?:টায়|টায়|টা|tay|ta)").matcher(text)
        if (numMatcher.find()) {
            val hourStr = numMatcher.group(1) ?: return null
            var hour = hourStr.toIntOrNull() ?: return null
            if (isPmBangla && hour in 1..11) hour += 12
            if (isAmBangla && hour == 12) hour = 0

            val cal = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, dayOffset)
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (dayOffset == 0 && cal.timeInMillis <= now.timeInMillis) {
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }
            return cal.timeInMillis
        }

        // Relative hours: e.g. "in 2 hours", "in 30 minutes"
        val inHoursMatcher = Pattern.compile("in\\s+(\\d+)\\s+hours?").matcher(text)
        if (inHoursMatcher.find()) {
            val hours = inHoursMatcher.group(1)?.toIntOrNull() ?: 1
            return now.timeInMillis + (hours * 3600 * 1000L)
        }

        val inMinsMatcher = Pattern.compile("in\\s+(\\d+)\\s+minutes?").matcher(text)
        if (inMinsMatcher.find()) {
            val mins = inMinsMatcher.group(1)?.toIntOrNull() ?: 30
            return now.timeInMillis + (mins * 60 * 1000L)
        }

        // No explicit time found; do NOT invent a fake time
        return null
    }

    /**
     * Schedules a system alarm for the note at targetTimestampMs.
     * Returns true if scheduled, false if failed or timestamp is in the past.
     */
    fun scheduleAlarm(note: VoiceNote, targetTimestampMs: Long): Boolean {
        if (targetTimestampMs <= System.currentTimeMillis()) {
            return false
        }

        return try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, ReminderBroadcastReceiver::class.java).apply {
                putExtra(ReminderBroadcastReceiver.EXTRA_NOTE_ID, note.id)
                putExtra(ReminderBroadcastReceiver.EXTRA_NOTE_TITLE, note.title)
                putExtra(
                    ReminderBroadcastReceiver.EXTRA_REMINDER_TEXT,
                    note.transcription?.take(100) ?: "Voice note reminder"
                )
            }

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                note.id.toInt(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    targetTimestampMs,
                    pendingIntent
                )
            } else {
                alarmManager.set(
                    AlarmManager.RTC_WAKEUP,
                    targetTimestampMs,
                    pendingIntent
                )
            }
            Log.i("ReminderManager", "Alarm scheduled successfully for note ${note.id} at $targetTimestampMs")
            true
        } catch (e: Exception) {
            Log.e("ReminderManager", "Failed to schedule alarm", e)
            false
        }
    }
}
