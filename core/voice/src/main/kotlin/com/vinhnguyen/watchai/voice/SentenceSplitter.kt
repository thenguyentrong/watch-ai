package com.vinhnguyen.watchai.voice

/**
 * Cuts a streamed answer into speakable pieces so speech can start after the first sentence
 * instead of after the whole answer. Very short pieces are held back to avoid choppy speech.
 * The first piece may also end at a comma once it is [firstPauseChars] long, so the voice starts
 * a little sooner on long opening sentences.
 */
class SentenceSplitter(
    private val minChars: Int = 12,
    private val maxChars: Int = 160,
    private val firstPauseChars: Int = 28,
) {
    private val buffer = StringBuilder()
    private var pieces = 0

    fun add(delta: String): List<String> {
        buffer.append(delta)
        val out = mutableListOf<String>()
        while (true) {
            val cut = nextCut() ?: break
            val piece = buffer.substring(0, cut).trim()
            buffer.delete(0, cut)
            if (piece.isNotEmpty()) {
                out += piece
                pieces++
            }
        }
        return out
    }

    /** Whatever is left when the answer ends. */
    fun flush(): String? = buffer.toString().trim().ifEmpty { null }.also { buffer.clear() }

    private fun nextCut(): Int? {
        for (i in buffer.indices) {
            val c = buffer[i]
            val followedBySpace = i + 1 < buffer.length && buffer[i + 1].isWhitespace()
            val sentenceEnd = (c in ENDERS && followedBySpace) || c == '\n'
            if (sentenceEnd && i + 1 >= minChars) return i + 1
            if (pieces == 0 && c in PAUSES && followedBySpace && i + 1 >= firstPauseChars) return i + 1
        }
        if (buffer.length >= maxChars) {
            val comma = buffer.lastIndexOfAny(charArrayOf(',', ';', ':'), maxChars)
            return if (comma >= minChars) comma + 1 else maxChars
        }
        return null
    }

    private companion object {
        val ENDERS = setOf('.', '!', '?', '…', '。', '！', '？')
        val PAUSES = setOf(',', ';', ':', '，', '、')
    }
}
