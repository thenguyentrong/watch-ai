package com.vinhnguyen.watchai.buddy

import java.util.Locale

/**
 * How Buddy reacts. The phone picks the mood from what the actions did (done, failed, music); the
 * user sets none of this.
 */
public enum class Mood {
    NEUTRAL,
    HAPPY,
    GREET,
    EXCITED,
    LAUGH,
    LOVE,
    CURIOUS,
    THINKING,
    SURPRISED,
    SAD,
    SLEEPY,
    CONFUSED,
    PROUD,
    OOPS,

    /** Music or a video starts: the play triangle. */
    PLAY,
    ;

    public val wire: String get() = name.lowercase(Locale.ROOT)

    public companion object {
        public fun of(wire: String?): Mood? = entries.firstOrNull { it.wire == wire }
    }
}

public data class Reaction(
    val mood: Mood,
    /** 0..1: a small smile at 0.3, jumping around at 1. */
    val intensity: Float,
)

