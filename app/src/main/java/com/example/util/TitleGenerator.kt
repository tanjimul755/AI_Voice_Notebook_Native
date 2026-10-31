package com.example.util

import java.util.Locale
import java.util.regex.Pattern

/**
 * Utility for intelligently deriving short, natural titles based on speech meaning and intent.
 * Handles reminders (Bengali, English, Banglish) extracting core action/topic,
 * as well as ordinary voice notes.
 */
object TitleGenerator {

    private val FILLER_WORDS = setOf(
        "um", "uh", "like", "basically", "actually", "literally", "so", "well",
        "হুম", "মানে", "আরকি", "তো", "আচ্ছা", "just", "really"
    )

    private val BENGALI_VERB_CONVERSIONS = listOf(
        "খাওয়ার কথা" to "খাও", "খাওয়ার কথা" to "খাও", "খাবার কথা" to "খাও",
        "খাওয়ার" to "খাও", "খাওয়ার" to "খাও", "খাবার" to "খাও", "খেতে" to "খাও",
        "যাওয়ার কথা" to "যাও", "যাওয়ার কথা" to "যাও", "যাওয়ার" to "যাও", "যাওয়ার" to "যাও", "যেতে" to "যাও",
        "করার কথা" to "করো", "করার" to "করো", "করতে" to "করো",
        "নেওয়ার কথা" to "নাও", "নেওয়ার কথা" to "নাও", "নেওয়ার" to "নাও", "নেওয়ার" to "নাও", "নিতে" to "নাও",
        "দেওয়ার কথা" to "দাও", "দেওয়ার কথা" to "দাও", "দেওয়ার" to "দাও", "দেওয়ার" to "দাও", "দিতে" to "দাও",
        "কেনার কথা" to "কেনো", "কেনার" to "কেনো", "কিনতে" to "কেনো",
        "পড়ার কথা" to "পড়ো", "পড়ার কথা" to "পড়ো", "পড়ার" to "পড়ো", "পড়ার" to "পড়ো", "পড়তে" to "পড়ো", "পড়াশোনা করার কথা" to "পড়াশোনা করো",
        "বলার কথা" to "বলো", "বলার" to "বলো", "বলতে" to "বলো",
        "পাঠানোর কথা" to "পাঠাও", "পাঠানোর" to "পাঠাও", "পাঠাতে" to "পাঠাও",
        "ঘুমানোর কথা" to "ঘুমাও", "ঘুমানোর" to "ঘুমাও", "ঘুমাতে" to "ঘুমাও",
        "আসার কথা" to "এসো", "আসার" to "এসো", "আসতে" to "এসো",
        "রাখার কথা" to "রাখো", "রাখার" to "রাখো", "রাখতে" to "রাখো",
        "শুনার কথা" to "শোনো", "শোনার কথা" to "শোনো", "শুনতে" to "শোনো",
        "লেখার কথা" to "লেখো", "লেখার" to "লেখো", "লিখতে" to "লেখো"
    )

    private val BANGLISH_VERB_CONVERSIONS = listOf(
        "khaowar kotha" to "khao", "khaowar" to "khao", "kheye nite" to "khao",
        "jaowar kotha" to "jao", "jaowar" to "jao",
        "korar kotha" to "koro", "korar" to "koro",
        "kenar kotha" to "keno", "kenar" to "keno",
        "deowar kotha" to "dao", "deowar" to "dao",
        "neowar kotha" to "nao", "neowar" to "nao",
        "porar kotha" to "poro", "porar" to "poro"
    )

    /**
     * Derives a short, natural title from the transcription text.
     * Extracts the core action (e.g. "ওষুধ খাও", "App টি চেক করো") for reminders or key subject for notes.
     */
    fun generateTitle(transcript: String?): String? {
        if (transcript.isNullOrBlank()) return null
        val trimmed = transcript.trim()
        if (trimmed == "[No speech detected in audio]" || trimmed.startsWith("[")) {
            return null
        }

        // 1. Try Reminder / Action Intent Extraction First
        val actionTitle = extractActionTitle(trimmed)
        if (!actionTitle.isNullOrBlank()) {
            return humanizeActionTitle(actionTitle)
        }

        // 2. Regular Note Contextual Title Extraction
        return extractNoteTitle(trimmed)
    }

    /**
     * Extracts core action title for reminders, e.g. "ওষুধ খাও", "App টি চেক করো", or "Take medicine".
     */
    fun extractActionTitle(text: String): String? {
        var clean = text.trim()
        val lower = clean.lowercase(Locale.ROOT)

        // Check for reminder intent markers
        val hasReminderMarker = lower.contains("মনে করিয়ে দিও") ||
                lower.contains("মনে করিয়ে দিও") ||
                lower.contains("মনে করিয়ে দেবে") ||
                lower.contains("মনে করিয়ে দেবে") ||
                lower.contains("মনে রেখো") ||
                lower.contains("ভুলতে দিও না") ||
                lower.contains("ভুলে যেও না") ||
                lower.contains("রিমাইন্ডার") ||
                lower.contains("mone koriye dio") ||
                lower.contains("mone rekho") ||
                lower.contains("remind me") ||
                lower.contains("don't forget") ||
                lower.contains("dont forget") ||
                lower.contains("remember to") ||
                lower.contains("set a reminder") ||
                lower.contains("set reminder")

        if (!hasReminderMarker) {
            return null
        }

        // Fast-path: Check for App check intention (e.g. "৫ সেকেন্ড পর এই অ্যাপ টা চেক করার কথা মনে করিয়ে দিও")
        if ((lower.contains("অ্যাপ") || lower.contains("app")) &&
            (lower.contains("চেক") || lower.contains("check"))
        ) {
            return "App টি চেক করো"
        }

        // Strip leading pronouns and polite phrases
        clean = clean
            .replace(Regex("(?i)^(?:আমাকে|আমায়|দয়া করে|দয়া করে|প্লিজ|please|amake)\\s+"), "")
            .replace(Regex("(?i)^(?:please\\s+)?remind me (?:that |to |about )?"), "")
            .replace(Regex("(?i)^(?:please\\s+)?set (?:a |the )?reminder (?:that |to |about )?"), "")
            .replace(Regex("(?i)^(?:please\\s+)?don't forget (?:to )?"), "")
            .replace(Regex("(?i)^(?:please\\s+)?dont forget (?:to )?"), "")
            .replace(Regex("(?i)^(?:please\\s+)?remember (?:to )?"), "")
            .replace(Regex("(?i)^রিমাইন্ডার\\s*(?:সেট\\s*করুন|সেট\\s*কর|দাও)?\\s*"), "")
            .trim()

        // Strip temporal phrases at start (e.g. "১০ সেকেন্ড পর", "in 20 seconds to")
        clean = clean
            .replace(Regex("(?i)^(?:[\\d\u09E6-\u09EF]+|দশ|বিশ|পনের|পাঁচ|এক)\\s*(?:সেকেন্ড|মিনিট|ঘণ্টা|ঘন্টা)\\s*(?:পর|পরে|বাদে)\\s+"), "")
            .replace(Regex("(?i)^(?:in|after)\\s+[\\d\u09E6-\u09EF]+\\s*(?:seconds?|minutes?|secs?|mins?|hours?)\\s*(?:to\\s+)?"), "")
            .replace(Regex("(?i)^tomorrow\\s+(?:at\\s+[\\d\u09E6-\u09EF]{1,2}(?::[\\d\u09E6-\u09EF]{2})?\\s*(?:am|pm)?\\s+)?(?:to\\s+)?"), "")
            .replace(Regex("(?i)^কাল(?:কে)?\\s*(?:সকালে|বিকেলে|রাতে)?\\s*"), "")
            .trim()

        // Strip temporal phrases at end (e.g. "after 10 seconds", "১০ সেকেন্ড পর")
        clean = clean
            .replace(Regex("(?i)\\s+(?:after|in)\\s+[\\d\u09E6-\u09EF]+\\s*(?:seconds?|minutes?|secs?|mins?|hours?)\\.?$"), "")
            .replace(Regex("(?i)\\s+[\\d\u09E6-\u09EF]+\\s*(?:সেকেন্ড|মিনিট|ঘণ্টা|ঘন্টা)\\s*(?:পর|পরে|বাদে)\\.?$"), "")
            .replace(Regex("(?i)\\s+[\\d\u09E6-\u09EF]+\\s*(?:seconds?|minutes?)\\s*(?:later|after)\\.?$"), "")
            .trim()

        // Strip trailer reminder phrases
        clean = clean
            .replace(Regex("(?i)\\s*(?:এর\\s+)?(?:কথা\\s+)?(?:মনে\\s*করিয়ে\\s*দিও|মনে\\s*করিয়ে\\s*দিও|মনে\\s*করিয়ে\\s*দেবে|মনে\\s*করিয়ে\\s*দেবে|মনে\\s*রেখো|ভুলতে\\s*দিও\\s*না|ভুলে\\s*যেও\\s*না|স্মরণ\\s*করিয়ে\\s*দিও|mone\\s*koriye\\s*dio|mone\\s*rekho)\\.?$"), "")
            .trim()

        // Strip trailing temporal phrase that might have preceded the reminder trailer
        clean = clean
            .replace(Regex("(?i)\\s+(?:after|in)\\s+[\\d\u09E6-\u09EF]+\\s*(?:seconds?|minutes?|secs?|mins?|hours?)\\.?$"), "")
            .replace(Regex("(?i)\\s+[\\d\u09E6-\u09EF]+\\s*(?:সেকেন্ড|মিনিট|ঘণ্টা|ঘন্টা)\\s*(?:পর|পরে|বাদে)\\.?$"), "")
            .trim()

        // Apply Bengali verb conversions (e.g. "ওষুধ খাওয়ার" -> "ওষুধ খাও")
        for ((verbForm, imperative) in BENGALI_VERB_CONVERSIONS) {
            if (clean.endsWith(verbForm)) {
                val prefix = clean.removeSuffix(verbForm).trim()
                val candidate = if (prefix.isNotBlank()) "$prefix $imperative" else imperative
                return humanizeActionTitle(candidate)
            }
        }

        // Apply Banglish conversions
        for ((verbForm, imperative) in BANGLISH_VERB_CONVERSIONS) {
            if (clean.endsWith(verbForm, ignoreCase = true)) {
                val prefix = clean.substring(0, clean.length - verbForm.length).trim()
                val candidate = if (prefix.isNotBlank()) "$prefix $imperative" else imperative
                return candidate.replaceFirstChar { it.uppercase() }
            }
        }

        // If English "to [action]" is embedded, extract
        val toIdx = clean.indexOf(" to ")
        if (toIdx != -1 && toIdx + 4 < clean.length) {
            clean = clean.substring(toIdx + 4).trim()
        }

        clean = clean.trimEnd('.', '!', '?', ',', '।')
        if (clean.length in 2..35) {
            return humanizeActionTitle(clean)
        }

        return null
    }

    /**
     * Converts a raw or AI-generated action title into a natural, contextual,
     * humanized title in 2nd-person imperative (আদেশ/অনুরোধসূচক ক্রিয়াপদ).
     * e.g.:
     *   "App চেক করা" -> "App টি চেক করো"
     *   "এই অ্যাপ টা চেক করা" -> "App টি চেক করো"
     *   "এই অ্যাপ টা চেক করো" -> "App টি চেক করো"
     *   "ওষুধ খাওয়া" -> "ওষুধ খাও"
     *   "ওষুধ খাওয়ার কথা" -> "ওষুধ খাও"
     */
    fun humanizeActionTitle(rawTitle: String?): String {
        if (rawTitle.isNullOrBlank()) return "Voice Note"
        var title = rawTitle.trim()

        // Remove wrapping quotes if any
        title = title.removeSurrounding("\"", "\"").removeSurrounding("'", "'").trim()

        // 1. App check special pattern: "App চেক করা" / "এই অ্যাপ টা চেক করা" / "App চেক করো" -> "App টি চেক করো"
        val lower = title.lowercase(Locale.ROOT)
        if ((lower.contains("অ্যাপ") || lower.contains("app")) &&
            (lower.contains("চেক") || lower.contains("check"))
        ) {
            return "App টি চেক করো"
        }

        // 2. Strip conversational / demonstrative prefixes
        title = title
            .replace(Regex("(?i)^এই\\s+([a-zA-Z\u0980-\u09FF]+)\\s+টা\\s+"), "$1 টি ")
            .replace(Regex("(?i)^এই\\s+([a-zA-Z\u0980-\u09FF]+)টা\\s+"), "$1 টি ")
            .replace(Regex("(?i)\\b([a-zA-Z\u0980-\u09FF]+)\\s+টা\\b"), "$1 টি")
            .replace(Regex("(?i)^এই\\s+"), "")
            .trim()

        // 3. Convert verbal nouns (কাজের নাম) into 2nd person imperative (অনুরোধ/আদেশ)
        val verbalNounConversions = listOf(
            "চেক করার" to "চেক করো",
            "চেক করা" to "চেক করো",
            "করার কথা" to "করো",
            "করার" to "করো",
            "করতে" to "করো",
            "করা" to "করো",
            "খাওয়ার কথা" to "খাও",
            "খাওয়ার কথা" to "খাও",
            "খাওয়ার" to "খাও",
            "খাওয়ার" to "খাও",
            "খাওয়া" to "খাও",
            "খাওয়া" to "খাও",
            "খাবার" to "খাও",
            "খেতে" to "খাও",
            "যাওয়ার" to "যাও",
            "যাওয়ার" to "যাও",
            "যাওয়া" to "যাও",
            "যাওয়া" to "যাও",
            "যেতে" to "যাও",
            "দেখা" to "দেখো",
            "দেখার" to "দেখো",
            "দেখতে" to "দেখো",
            "পড়ার" to "পড়ো",
            "পড়ার" to "পড়ো",
            "পড়া" to "পড়ো",
            "পড়া" to "পড়ো",
            "পড়তে" to "পড়ো",
            "শোনা" to "শোনো",
            "নেওয়ার" to "নাও",
            "নেওয়ার" to "নাও",
            "নেওয়া" to "নাও",
            "নেওয়া" to "নাও",
            "নিতে" to "নাও",
            "দেওয়ার" to "দাও",
            "দেওয়ার" to "দাও",
            "দেওয়া" to "দাও",
            "দেওয়া" to "দাও",
            "দিতে" to "দাও",
            "বলা" to "বলো",
            "বলতে" to "বলো",
            "কেনা" to "কেনো",
            "কিনতে" to "কেনো",
            "খোলা" to "খুলো",
            "খুলতে" to "খুলো",
            "বন্ধ করা" to "বন্ধ করো",
            "পাঠানো" to "পাঠাও",
            "পাঠাতে" to "পাঠাও",
            "লেখা" to "লেখো",
            "লিখতে" to "লেখো",
            "ঘুমানো" to "ঘুমাও",
            "ঘুমাতে" to "ঘুমাও",
            "রাখা" to "রাখো",
            "রাখতে" to "রাখো"
        )

        for ((verb, imperative) in verbalNounConversions) {
            if (title.endsWith(verb)) {
                val prefix = title.removeSuffix(verb).trim()
                title = if (prefix.isNotBlank()) "$prefix $imperative" else imperative
                break
            }
        }

        title = title.trimEnd('.', '!', '?', ',', '।').trim()
        return title.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
    }

    /**
     * Extracts a concise contextual title for an ordinary voice note.
     */
    fun extractNoteTitle(text: String): String {
        // Take first sentence
        val firstSentence = text
            .split("\n", ".", "?", "!", "|", "।")
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() } ?: text.take(30).trim()

        // Strip conversational lead-ins
        var clean = firstSentence
            .replace(Regex("(?i)^(?:today\\s+(?:we|i)\\s+(?:discussed|talked about|met about|worked on)\\s+)"), "")
            .replace(Regex("(?i)^(?:i\\s+was\\s+thinking\\s+(?:that|about)\\s+)"), "")
            .replace(Regex("(?i)^(?:note\\s+to\\s+self\\s*:?\\s*)"), "")
            .replace(Regex("(?i)^(?:quick\\s+thought\\s*:?\\s*)"), "")
            .replace(Regex("(?i)^(?:meeting\\s+notes\\s*:?\\s*)"), "")
            .replace(Regex("(?i)^(?:আজকে\\s+আমরা\\s+আলোচনা\\s+করেছি\\s+)"), "")
            .replace(Regex("(?i)^(?:আমি\\s+ভাবছিলাম\\s+যে\\s+)"), "")
            .replace(Regex("(?i)^(?:আমার\\s+মনে\\s+হয়\\s+যে\\s+)"), "")
            .trim()

        val words = clean.split("\\s+".toRegex())
            .filter { word ->
                val w = word.lowercase(Locale.ROOT).replace(Regex("[^a-zA-Z0-9\u0980-\u09FF]"), "")
                w !in FILLER_WORDS && w.length > 1
            }

        if (words.isEmpty()) {
            return firstSentence.take(28).trim().trimEnd('.', '!', '?')
        }

        // Take 3 to 5 words max
        val selected = words.take(4)
        val candidate = selected.joinToString(" ").trimEnd('.', '!', '?', ',', '।')

        val finalTitle = if (candidate.length > 32) {
            candidate.take(29).trimEnd() + "..."
        } else {
            candidate
        }

        return finalTitle.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
        }
    }
}

