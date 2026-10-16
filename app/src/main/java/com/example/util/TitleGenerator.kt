package com.example.util

import java.util.Locale

/**
 * Utility for intelligently deriving a concise contextual title from speech transcriptions.
 * Works with English, Bengali (বাংলা), and Romanized Bangla (Banglish).
 */
object TitleGenerator {

    private val FILLER_WORDS = setOf(
        "um", "uh", "like", "basically", "actually", "literally", "so", "well",
        "হুম", "মানে", "আরকি", "তো", "আচ্ছা"
    )

    /**
     * Generates a 3 to 7 word concise contextual title from the transcription.
     * Returns null if the transcript is blank or non-speech.
     */
    fun generateTitle(transcript: String?): String? {
        if (transcript.isNullOrBlank()) return null
        val trimmed = transcript.trim()
        if (trimmed == "[No speech detected in audio]" || trimmed.startsWith("[")) {
            return null
        }

        // Take the first line or first sentence
        val firstSentence = trimmed
            .split("\n", ".", "?", "!", "|", "।")
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() } ?: return null

        val words = firstSentence.split("\\s+".toRegex())
            .filter { word ->
                val clean = word.lowercase(Locale.ROOT).replace(Regex("[^a-zA-Z0-9\u0980-\u09FF]"), "")
                clean !in FILLER_WORDS && clean.length > 1
            }

        if (words.isEmpty()) {
            return firstSentence.take(35).trim()
        }

        val selectedWords = words.take(6)
        val candidate = selectedWords.joinToString(" ")
        val finalTitle = if (candidate.length > 40) {
            candidate.take(37) + "..."
        } else {
            candidate
        }

        // Capitalize first character
        return finalTitle.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
        }
    }
}
