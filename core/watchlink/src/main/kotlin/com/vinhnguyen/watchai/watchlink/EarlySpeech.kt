package com.vinhnguyen.watchai.watchlink

/**
 * What the user says between "Hey Buddy" (or opening the app) and the phone picking up, about 2 s,
 * kept so that "Hey Buddy, what time is it?" works in one breath instead of losing the question.
 * Keeps the last [maxFrames] frames; [drain] hands them over starting one frame before the first
 * that is at least [voiceLevel] loud (so soft first sounds aren't clipped), and empties the buffer.
 */
public class EarlySpeech(
    private val maxFrames: Int,
    private val voiceLevel: Float,
) {
    private val frames = ArrayDeque<ShortArray>()
    private val levels = ArrayDeque<Float>()

    @Synchronized
    public fun add(
        pcm: ShortArray,
        level: Float,
    ) {
        if (frames.size == maxFrames) {
            frames.removeFirst()
            levels.removeFirst()
        }
        frames.addLast(pcm)
        levels.addLast(level)
    }

    /** Everything from just before the first voiced frame; empty if nobody spoke. */
    @Synchronized
    public fun drain(): List<ShortArray> {
        val first = levels.indexOfFirst { it >= voiceLevel }
        val out = if (first < 0) emptyList() else frames.toList().drop(maxOf(0, first - 1))
        frames.clear()
        levels.clear()
        return out
    }
}
