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
import androidx.core.app.NotificationManagerCompat
import com.example.MainActivity
import com.example.data.AppDatabase
import com.example.model.VoiceNote
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale
import java.util.regex.Pattern

private const val TAG = "ReminderManager"

enum class ReminderAction {
    CREATE,
    UPDATE,
    CANCEL,
    IGNORE
}

/**
 * Result of analyzing a transcript for actionable reminders.
 */
data class DetectedReminder(
    val action: ReminderAction = ReminderAction.CREATE,
    val taskSummary: String,
    val targetTimestampMs: Long? = null,
    val isExplicitDateTime: Boolean = false,
    val targetQuery: String? = null
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

        // Create notification channel on Android 8.0+
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
            putExtra("target_note_id", noteId)
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

        // Mark reminder as completed in Room database
        if (noteId > 0) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val db = AppDatabase.getDatabase(context)
                    val note = db.voiceNoteDao().getVoiceNoteById(noteId)
                    if (note != null) {
                        db.voiceNoteDao().updateReminderDetails(
                            id = noteId,
                            timestamp = note.reminderTimestamp,
                            task = note.reminderTask,
                            isEnabled = false,
                            status = "COMPLETED"
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to mark reminder completed in DB", e)
                }
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "voice_note_reminders"
        const val EXTRA_NOTE_ID = "extra_note_id"
        const val EXTRA_NOTE_TITLE = "extra_note_title"
        const val EXTRA_REMINDER_TEXT = "extra_reminder_text"
    }
}

/**
 * BroadcastReceiver for restoring scheduled reminders after device reboot.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            Log.i(TAG, "Boot completed event received ($action). Restoring active alarms...")
            val reminderManager = ReminderManager(context)
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val db = AppDatabase.getDatabase(context)
                    val futureNotes = db.voiceNoteDao().getActiveFutureReminders(System.currentTimeMillis())
                    for (note in futureNotes) {
                        note.reminderTimestamp?.let { timestamp ->
                            reminderManager.scheduleAlarm(
                                noteId = note.id,
                                title = note.reminderTask ?: note.title,
                                text = note.transcription ?: "Voice note reminder",
                                targetTimestampMs = timestamp
                            )
                        }
                    }
                    Log.i(TAG, "Restored ${futureNotes.size} reminders after reboot")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to restore reminders after reboot", e)
                }
            }
        }
    }
}

/**
 * Handles reminder intent detection from text, temporal expression parsing, and Android AlarmManager scheduling.
 */
class ReminderManager(private val context: Context) {

    /**
     * Inspects transcription text for explicit reminder intent, cancellations, or updates.
     * Supports English, Bengali (বাংলা), Romanized Bangla (Banglish), and mixed speech.
     *
     * Rules:
     * 1. Returns null for ordinary non-reminder statements (e.g. "I need to buy milk tomorrow").
     * 2. Returns IGNORE action for negative requests (e.g. "Don't set a reminder", "Don't remind me").
     * 3. Returns CANCEL action for cancellation requests (e.g. "Cancel my medicine reminder").
     * 4. Returns UPDATE action for reschedule requests (e.g. "Change my bill reminder to 7 PM").
     * 5. For positive reminder intent: parses explicit dates, clock times, and relative delays without inventing fake times.
     */
    fun detectReminder(transcript: String): DetectedReminder? {
        if (transcript.isBlank()) return null
        val lower = transcript.lowercase(Locale.ROOT).trim()

        // 1. Negative request check - MUST NEVER create a reminder
        val isNegative = lower.contains("don't set a reminder") ||
                lower.contains("dont set a reminder") ||
                lower.contains("do not set a reminder") ||
                lower.contains("don't set any reminder") ||
                lower.contains("don't remind me") ||
                lower.contains("dont remind me") ||
                lower.contains("do not remind me") ||
                lower.contains("never remind me") ||
                lower.contains("please do not remind") ||
                lower.contains("রিমাইন্ডার সেট করো না") ||
                lower.contains("রিমাইন্ডার সেট কর না") ||
                lower.contains("রিমাইন্ডার দিও না") ||
                lower.contains("মনে করিয়ে দিও না") ||
                lower.contains("মনে করিয়ে দিও না") ||
                lower.contains("মনে করাবে না") ||
                lower.contains("মনে করানোর দরকার নেই") ||
                lower.contains("reminder set koro na") ||
                lower.contains("remind koro na") ||
                lower.contains("remind korio na") ||
                lower.contains("remind korbe na") ||
                lower.contains("mone koriye dio na") ||
                lower.contains("mone korio na") ||
                lower.contains("bhule jete paro")

        if (isNegative) {
            return DetectedReminder(
                action = ReminderAction.IGNORE,
                taskSummary = "",
                isExplicitDateTime = false
            )
        }

        // 2. Cancellation check
        val isCancel = lower.contains("cancel my") ||
                lower.contains("cancel the") ||
                lower.contains("cancel reminder") ||
                lower.contains("delete my") ||
                lower.contains("delete reminder") ||
                lower.contains("remove reminder") ||
                lower.contains("reminder cancel") ||
                lower.contains("reminder delete") ||
                lower.contains("রিমাইন্ডার বাতিল") ||
                lower.contains("রিমাইন্ডার ডিলিট") ||
                lower.contains("রিমাইন্ডার বন্ধ") ||
                lower.contains("রিমাইন্ডার ক্যানসেল")
        if (isCancel) {
            val query = extractQueryFromCancelOrChange(transcript)
            return DetectedReminder(
                action = ReminderAction.CANCEL,
                taskSummary = transcript.take(60).trim(),
                targetQuery = query,
                isExplicitDateTime = false
            )
        }

        // 3. Update / Change check
        val isChange = lower.contains("change my") ||
                lower.contains("change the") ||
                lower.contains("update my") ||
                lower.contains("update reminder") ||
                lower.contains("reschedule") ||
                lower.contains("reminder change") ||
                lower.contains("রিমাইন্ডার পরিবর্তন") ||
                lower.contains("রিমাইন্ডার বদলে")
        if (isChange) {
            val query = extractQueryFromCancelOrChange(transcript)
            val newTimestamp = parseExplicitTimestamp(lower)
            return DetectedReminder(
                action = ReminderAction.UPDATE,
                taskSummary = transcript.take(60).trim(),
                targetTimestampMs = newTimestamp,
                isExplicitDateTime = newTimestamp != null,
                targetQuery = query
            )
        }

        // 4. Positive explicit reminder intent check
        val hasExplicitIntent = lower.contains("remind me") ||
                lower.contains("set a reminder") ||
                lower.contains("set the reminder") ||
                lower.contains("remember to") ||
                lower.contains("don't let me forget") ||
                lower.contains("dont let me forget") ||
                lower.contains("do not let me forget") ||
                lower.contains("don't forget") ||
                lower.contains("dont forget") ||
                lower.contains("do not forget") ||
                lower.contains("please remind") ||
                lower.contains("remind koro") ||
                lower.contains("remind korio") ||
                lower.contains("reminder set koro") ||
                lower.contains("reminder dao") ||
                lower.contains("amake mone koriye dio") ||
                lower.contains("mone koriye dio") ||
                lower.contains("mone korie dio") ||
                lower.contains("mone rekho") ||
                lower.contains("bhulte dio na") ||
                lower.contains("bhule jeo na") ||
                lower.contains("bhule jeyo na") ||
                lower.contains("রিমাইন্ডার সেট কর") ||
                lower.contains("রিমাইন্ডার সেট করুন") ||
                lower.contains("রিমাইন্ডার দাও") ||
                lower.contains("রিমাইন্ডার দিন") ||
                lower.contains("মনে করিয়ে দিও") ||
                lower.contains("মনে করিয়ে দিও") ||
                lower.contains("মনে করিয়ে দেবে") ||
                lower.contains("মনে করিয়ে দেবে") ||
                lower.contains("মনে রেখো") ||
                lower.contains("ভুলতে দিও না") ||
                lower.contains("ভুলে যেও না") ||
                lower.contains("ভুলে যেওনা") ||
                lower.contains("স্মরণ করিয়ে দিও") ||
                lower.contains("স্মরণ করিয়ে দিও")

        // Ordinary statements like "I need to buy milk tomorrow" or "Meeting at 5" without reminder trigger return null
        if (!hasExplicitIntent) {
            return null
        }

        // Extract clean task summary
        val taskSummary = extractTaskSummary(transcript)

        // Attempt to parse explicit time/date (including relative delays)
        val targetTimestamp = parseExplicitTimestamp(lower)

        return DetectedReminder(
            action = ReminderAction.CREATE,
            taskSummary = taskSummary,
            targetTimestampMs = targetTimestamp,
            isExplicitDateTime = targetTimestamp != null
        )
    }

    private fun extractQueryFromCancelOrChange(transcript: String): String? {
        val lower = transcript.lowercase(Locale.ROOT)
        val patterns = listOf(
            Pattern.compile("(?:cancel|delete|remove|change|update|reschedule)\\s+(?:my|the)?\\s*([a-zA-Z0-9\\s]+?)(?:\\s+reminder|\\s+to\\b|$)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("([a-zA-Z0-9\\s]+?)\\s+(?:reminder\\s+cancel|reminder\\s+delete|রিমাইন্ডার)", Pattern.CASE_INSENSITIVE)
        )
        for (p in patterns) {
            val m = p.matcher(lower)
            if (m.find()) {
                val q = m.group(1)?.trim()
                if (!q.isNullOrBlank() && q != "my" && q != "the") {
                    return q
                }
            }
        }
        return null
    }

    private fun extractTaskSummary(rawText: String): String {
        var text = rawText.trim()
        val lower = text.lowercase(Locale.ROOT)

        // Pattern A: "Remind me that [task] after X" -> e.g. "Remind me that this app works after 10 seconds."
        val thatPattern = Pattern.compile("(?i)(?:remind me that|set a reminder that)\\s+(.+?)(?:\\s+(?:after|in)\\s+\\d+\\s*(?:seconds?|secs?|minutes?|mins?|hours?|hrs?)|\\s+at\\s+\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)?|\\s+tomorrow|\\s*$)")
        val thatMatcher = thatPattern.matcher(text)
        if (thatMatcher.find()) {
            val cand = thatMatcher.group(1)?.trim()?.trimEnd('.', '!', '?')
            if (!cand.isNullOrBlank() && cand.length >= 2) {
                return cand
            }
        }

        // Pattern B: English "to [task]" pattern: e.g. "Remind me tomorrow at 10 AM to renew my data pack."
        val toIndex = lower.lastIndexOf(" to ")
        if (toIndex != -1 && toIndex + 4 < text.length) {
            var candidate = text.substring(toIndex + 4).trim()
            candidate = stripTrailingTimePhrases(candidate)
            if (candidate.length >= 3) return candidate.replaceFirstChar { it.uppercase() }
        }

        // Pattern C: "don't forget to [task]" / "remember to [task]"
        val forgetIdx = when {
            lower.contains("forget to ") -> lower.indexOf("forget to ") + 10
            lower.contains("remember to ") -> lower.indexOf("remember to ") + 12
            else -> -1
        }
        if (forgetIdx != -1 && forgetIdx < text.length) {
            var candidate = text.substring(forgetIdx).trim()
            candidate = stripTrailingTimePhrases(candidate)
            if (candidate.length >= 3) return candidate.replaceFirstChar { it.uppercase() }
        }

        // Pattern D: Bangla "ওষুধ খাওয়ার কথা মনে করিয়ে দিও" -> extract before "মনে করিয়ে দিও" / "ভুলে যেও না"
        val banglaEndings = listOf("মনে করিয়ে দিও", "মনে করিয়ে দিও", "মনে করিয়ে দেবে", "মনে করিয়ে দেবে", "ভুলতে দিও না", "ভুলে যেও না", "mone koriye dio", "bhulte dio na", "bhule jeo na")
        for (ending in banglaEndings) {
            val idx = text.indexOf(ending)
            if (idx > 0) {
                var candidate = text.substring(0, idx).trim()
                val prefixes = listOf("আমাকে", "amake", "কাল", "কালকে", "kal", "kaalke", "shokal", "bikal", "please", "day after tomorrow", "tomorrow")
                for (prefix in prefixes) {
                    if (candidate.startsWith(prefix, ignoreCase = true)) {
                        candidate = candidate.substring(prefix.length).trim()
                    }
                }
                candidate = stripTrailingTimePhrases(candidate)
                if (candidate.length >= 3) {
                    return candidate.trimEnd('.', '!', '?').replaceFirstChar { it.uppercase() }
                }
            }
        }

        // Default: clean leading boilerplate and strip trailing temporal phrases
        var clean = text
            .replace("(?i)^remind me (?:that |to |about )?".toRegex(), "")
            .replace("(?i)^set (?:a |the )?reminder (?:that |to |about )?".toRegex(), "")
            .replace("(?i)^don't let me forget (?:to )?".toRegex(), "")
            .replace("(?i)^dont let me forget (?:to )?".toRegex(), "")
            .replace("(?i)^don't forget (?:to )?".toRegex(), "")
            .replace("(?i)^dont forget (?:to )?".toRegex(), "")
            .replace("(?i)^please remind (?:me )?(?:to |that )?".toRegex(), "")
            .replace("(?i)^রিমাইন্ডার সেট কর(?:ুন)?\\s*".toRegex(), "")
            .replace("(?i)^রিমাইন্ডার দাও\\s*".toRegex(), "")
            .replace("(?i)^reminder set koro\\s*".toRegex(), "")
            .replace("(?i)^amake mone koriye dio\\s*".toRegex(), "")
            .trim()

        clean = stripTrailingTimePhrases(clean)

        return clean.ifBlank { "Voice Note Reminder" }
    }

    private fun stripTrailingTimePhrases(input: String): String {
        return input
            .replace("(?i)\\s+(?:after|in)\\s+\\d+\\s*(?:seconds?|secs?|minutes?|mins?|hours?|hrs?)\\.?$".toRegex(), "")
            .replace("(?i)\\s+\\d+\\s*(?:seconds?|secs?|minutes?|mins?|hours?|hrs?)\\s*(?:later|after|pore|por)\\.?$".toRegex(), "")
            .replace("(?i)\\s+\\d+\\s*(?:সেকেন্ড|মিনিট|ঘণ্টা|ঘন্টা)\\s*(?:পর|পরে)\\.?$".toRegex(), "")
            .replace("(?i)\\s+(?:tomorrow|yesterday|today|কাল|কালকে|পরশু)(?:\\s+at\\s+\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)?)?\\.?$".toRegex(), "")
            .replace("(?i)\\s+at\\s+\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)?\\.?$".toRegex(), "")
            .trim()
            .trimEnd('.', '!', '?')
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

    /**
     * Parses explicit date, clock time, and relative delay expressions.
     * Returns null if missing or ambiguous without inventing a fake time.
     */
    fun parseExplicitTimestamp(rawText: String): Long? {
        val text = normalizeDigits(rawText)
        val now = Calendar.getInstance()

        // 1. Relative seconds: e.g. "after 10 seconds", "in 10 seconds", "10 seconds later", "10 second pore", "১০ সেকেন্ড পর"
        val secMatcher = Pattern.compile(
            "(?:after|in)\\s+(\\d+)\\s*(?:seconds?|secs?|second|sec)\\b|(\\d+)\\s*(?:seconds?|secs?|second|sec)\\s*(?:later|after|pore|por)\\b|(\\d+)\\s*(?:সেকেন্ড|সেঃ)\\s*(?:পর|পরে)",
            Pattern.CASE_INSENSITIVE
        ).matcher(text)
        if (secMatcher.find()) {
            val secs = secMatcher.group(1)?.toLongOrNull()
                ?: secMatcher.group(2)?.toLongOrNull()
                ?: secMatcher.group(3)?.toLongOrNull()
                ?: 10L
            return now.timeInMillis + (secs * 1000L)
        }

        // 2. Relative minutes: e.g. "after 5 minutes", "in 5 minutes", "5 minutes later", "5 minute pore", "৫ মিনিট পর"
        val minMatcher = Pattern.compile(
            "(?:after|in)\\s+(\\d+)\\s*(?:minutes?|mins?|minute|min)\\b|(\\d+)\\s*(?:minutes?|mins?|minute|min)\\s*(?:later|after|pore|por)\\b|(\\d+)\\s*(?:মিনিট|মিঃ)\\s*(?:পর|পরে)",
            Pattern.CASE_INSENSITIVE
        ).matcher(text)
        if (minMatcher.find()) {
            val mins = minMatcher.group(1)?.toLongOrNull()
                ?: minMatcher.group(2)?.toLongOrNull()
                ?: minMatcher.group(3)?.toLongOrNull()
                ?: 5L
            return now.timeInMillis + (mins * 60 * 1000L)
        }

        // 3. Relative hours: e.g. "after 2 hours", "in 2 hours", "2 hours later", "২ ঘণ্টা পর"
        val hrMatcher = Pattern.compile(
            "(?:after|in)\\s+(\\d+)\\s*(?:hours?|hrs?|hour|hr)\\b|(\\d+)\\s*(?:hours?|hrs?|hour|hr)\\s*(?:later|after|pore|por)\\b|(\\d+)\\s*(?:ঘণ্টা|ঘন্টা)\\s*(?:পর|পরে)",
            Pattern.CASE_INSENSITIVE
        ).matcher(text)
        if (hrMatcher.find()) {
            val hrs = hrMatcher.group(1)?.toLongOrNull()
                ?: hrMatcher.group(2)?.toLongOrNull()
                ?: hrMatcher.group(3)?.toLongOrNull()
                ?: 1L
            return now.timeInMillis + (hrs * 3600 * 1000L)
        }

        // Check relative day offset
        var dayOffset = 0
        if (text.contains("tomorrow") || text.contains("কালকে") || text.contains("আগামীকাল") || text.contains("kaalke") || text.contains("kal")) {
            dayOffset = 1
        } else if (text.contains("day after tomorrow") || text.contains("পরশু") || text.contains("porshu")) {
            dayOffset = 2
        }

        // 4. Standard 12-hour English "10 AM", "6 PM", "5:30 pm", "10:00 am"
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

            // If time is today and has already passed, advance to tomorrow
            if (dayOffset == 0 && cal.timeInMillis <= now.timeInMillis) {
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }
            return cal.timeInMillis
        }

        // 5. Bengali numerals or Bengali / Banglish speech (e.g. সকাল ১০টায়, বিকাল ৫টায়, রাত ৮টায়, 10tay)
        val isPmBangla = text.contains("বিকাল") || text.contains("দুপুর") || text.contains("সন্ধ্যা") || text.contains("রাত") ||
                text.contains("bikal") || text.contains("dupur") || text.contains("shondha") || text.contains("raat")
        val isAmBangla = text.contains("সকাল") || text.contains("ভোর") || text.contains("shokal") || text.contains("bhor")

        val numMatcher = Pattern.compile("(\\d{1,2})(?::(\\d{2}))?\\s*(?:টায়|টায়|টা|tay|ta)(?![a-zA-Z0-9])", Pattern.CASE_INSENSITIVE).matcher(text)
        if (numMatcher.find()) {
            val hourStr = numMatcher.group(1) ?: return null
            val minStr = numMatcher.group(2) ?: "00"
            var hour = hourStr.toIntOrNull() ?: return null
            val min = minStr.toIntOrNull() ?: 0

            if (isPmBangla && hour in 1..11) hour += 12
            if (isAmBangla && hour == 12) hour = 0

            val cal = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, dayOffset)
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, min)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (dayOffset == 0 && cal.timeInMillis <= now.timeInMillis) {
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }
            return cal.timeInMillis
        }

        // No explicit time found; do NOT invent a fake time
        return null
    }

    /**
     * Schedules a system alarm for the note at targetTimestampMs.
     * Prevents duplicate alarms by cancelling any prior alarm for this note first.
     */
    fun scheduleAlarm(
        noteId: Long,
        title: String,
        text: String,
        targetTimestampMs: Long
    ): Boolean {
        if (targetTimestampMs <= System.currentTimeMillis()) {
            Log.w(TAG, "Cannot schedule alarm in the past: $targetTimestampMs")
            return false
        }

        // Cancel previous alarm if any to prevent duplicates
        cancelAlarm(noteId)

        return try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, ReminderBroadcastReceiver::class.java).apply {
                putExtra(ReminderBroadcastReceiver.EXTRA_NOTE_ID, noteId)
                putExtra(ReminderBroadcastReceiver.EXTRA_NOTE_TITLE, title)
                putExtra(ReminderBroadcastReceiver.EXTRA_REMINDER_TEXT, text.take(120))
            }

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                noteId.toInt(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Android exact alarm check with safe fallback
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    if (alarmManager.canScheduleExactAlarms()) {
                        alarmManager.setExactAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            targetTimestampMs,
                            pendingIntent
                        )
                    } else {
                        alarmManager.setAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            targetTimestampMs,
                            pendingIntent
                        )
                    }
                } catch (se: SecurityException) {
                    Log.w(TAG, "Exact alarm permission denied, falling back to inexact idle alarm", se)
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        targetTimestampMs,
                        pendingIntent
                    )
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
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
            Log.i(TAG, "Alarm scheduled successfully for note $noteId at $targetTimestampMs")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule alarm for note $noteId", e)
            false
        }
    }

    fun scheduleAlarm(note: VoiceNote, targetTimestampMs: Long): Boolean {
        return scheduleAlarm(
            noteId = note.id,
            title = note.reminderTask ?: note.title,
            text = note.transcription ?: "Voice note reminder",
            targetTimestampMs = targetTimestampMs
        )
    }

    /**
     * Cancels an existing scheduled alarm for the given note ID.
     */
    fun cancelAlarm(noteId: Long): Boolean {
        return try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, ReminderBroadcastReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                noteId.toInt(),
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (pendingIntent != null) {
                alarmManager.cancel(pendingIntent)
                pendingIntent.cancel()
            }
            Log.i(TAG, "Alarm cancelled for note $noteId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cancel alarm for note $noteId", e)
            false
        }
    }

    fun areNotificationsEnabled(): Boolean {
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun canScheduleExactAlarms(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }
    }
}
