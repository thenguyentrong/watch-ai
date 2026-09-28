package com.vinhnguyen.watchai.actions

import java.text.Normalizer
import java.util.Locale

/**
 * Finds who or what the user named, the way people say names: any case, with or without accents
 * and other marks ("Jose" finds "José"), a first name alone, words in any order, an app name with
 * or without its space. The best kind of match wins and ties all come back, so the assistant can
 * ask which one is meant instead of guessing.
 */
object NameMatch {
    fun <T> best(
        query: String,
        candidates: List<T>,
        name: (T) -> String,
    ): List<T> {
        val said = words(query)
        if (said.isEmpty()) return emptyList()
        val scored = candidates.map { it to score(said, words(name(it))) }.filter { it.second > 0 }
        val top = scored.maxOfOrNull { it.second } ?: return emptyList()
        return scored.filter { it.second == top }.map { it.first }
    }

    private fun score(
        said: List<String>,
        name: List<String>,
    ): Int {
        if (name.isEmpty()) return 0
        val saidJoined = said.joinToString("")
        val nameJoined = name.joinToString("")
        return when {
            said == name || saidJoined == nameJoined -> EXACT
            said.all { it in name } -> WHOLE_WORDS
            said.all { s -> name.any { it.startsWith(s) } } -> WORD_STARTS
            saidJoined.length >= MIN_INSIDE && nameJoined.contains(saidJoined) -> INSIDE
            else -> 0
        }
    }

    /** Lower case, marks removed, split into words. Letters that don't come apart into letter and mark are mapped too. */
    fun words(text: String): List<String> {
        val plain =
            Normalizer
                .normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
                .replace(MARKS, "")
                .map { SPECIAL[it] ?: it.toString() }
                .joinToString("")
        return plain.split(SEPARATORS).filter { it.isNotEmpty() }
    }

    private const val EXACT = 4
    private const val WHOLE_WORDS = 3
    private const val WORD_STARTS = 2
    private const val INSIDE = 1
    private const val MIN_INSIDE = 3
    private val MARKS = Regex("\\p{Mn}+")
    private val SEPARATORS = Regex("[^\\p{L}\\p{N}]+")
    private val SPECIAL = mapOf('đ' to "d", 'ł' to "l", 'ø' to "o", 'æ' to "ae", 'œ' to "oe", 'ß' to "ss", 'ı' to "i", 'þ' to "th")
}
