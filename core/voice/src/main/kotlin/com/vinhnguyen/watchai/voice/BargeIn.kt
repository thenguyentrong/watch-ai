package com.vinhnguyen.watchai.voice

/**
 * Talking over the answer should silence it at once. GPT-Live stops an answer only when its own
 * detector is sure the user is talking (1-5 s in the 27.09 test), so the phone mutes the answer
 * itself after [talkMs] of speech over it, and plays again when the answer has stopped (the
 * server took the interruption) or when the user went quiet without it stopping (a cough, "mm-hm").
 *
 * The mic is echo-cancelled, but some of the answer can still leak in. The first [learnMs] of every
 * answer only measure that leak (nobody interrupts that fast), and the talk threshold sits well
 * above it, so an answer can't silence itself. A mute never lasts longer than [maxMuteMs].
 * Pure logic, unit-tested.
 */
class BargeIn(
    private val talkLevel: Double = 0.06,
    private val quietLevel: Double = 0.03,
    private val talkMs: Long = 200,
    /** Speech dips between words; a dip shorter than this doesn't restart the count. */
    private val gapMs: Long = 150,
    private val releaseMs: Long = 1_000,
    private val learnMs: Long = 400,
    private val maxMuteMs: Long = 3_000,
) {
    enum class Action { MUTE, UNMUTE }

    var muted: Boolean = false
        private set

    /** How long the user had been talking over the answer when it was muted. */
    var lastMuteAfterMs: Long = 0
        private set

    private var echo = 0.0
    private var answerSince: Long? = null
    private var talkSince: Long? = null
    private var quietSince: Long? = null
    private var mutedSince = 0L
    private var gaveUp = false
    private var belowSince: Long? = null
    private var loudSamples = 0

    fun sample(
        nowMs: Long,
        userLevel: Double,
        assistantSpeaking: Boolean,
    ): Action? {
        if (!assistantSpeaking) {
            answerSince = null
            talkSince = null
            belowSince = null
            quietSince = null
            gaveUp = false
            if (!muted) return null
            muted = false
            return Action.UNMUTE
        }
        val since =
            answerSince ?: nowMs.also {
                answerSince = it
                echo *= ECHO_DECAY
            }
        if (nowMs - since < learnMs) {
            echo = maxOf(echo, userLevel)
            return null
        }
        return if (muted) whileMuted(nowMs, userLevel) else whilePlaying(nowMs, userLevel)
    }

    private fun whilePlaying(
        nowMs: Long,
        userLevel: Double,
    ): Action? {
        if (gaveUp) return null
        val talk = maxOf(talkLevel, minOf(MAX_TALK_LEVEL, echo * ECHO_MARGIN))
        val since = talkSince
        if (since == null) {
            if (userLevel >= talk) {
                talkSince = nowMs
                loudSamples = 1
                belowSince = null
            }
            return null
        }
        if (userLevel >= talk * HOLD_RATIO) {
            belowSince = null
            if (userLevel >= talk) loudSamples++
        } else {
            val quietFrom = belowSince ?: nowMs.also { belowSince = it }
            if (nowMs - quietFrom >= gapMs) {
                talkSince = null
                belowSince = null
            }
            return null
        }
        // Mute only while the user is audible right now, after enough loud moments.
        if (nowMs - since < talkMs || loudSamples < MIN_LOUD_SAMPLES) return null
        muted = true
        mutedSince = nowMs
        lastMuteAfterMs = nowMs - since
        talkSince = null
        quietSince = null
        return Action.MUTE
    }

    private fun whileMuted(
        nowMs: Long,
        userLevel: Double,
    ): Action? {
        if (nowMs - mutedSince >= maxMuteMs) {
            // Still "talking" after this long with the answer going on: most likely leak, not the user.
            // Leave this answer alone; the next one starts with the higher leak level.
            echo = maxOf(echo, userLevel)
            gaveUp = true
            return unmute()
        }
        if (userLevel >= maxOf(quietLevel, echo * 1.5)) {
            quietSince = null
            return null
        }
        val since = quietSince ?: nowMs.also { quietSince = it }
        return if (nowMs - since >= releaseMs) unmute() else null
    }

    private fun unmute(): Action {
        muted = false
        quietSince = null
        return Action.UNMUTE
    }

    private companion object {
        const val ECHO_DECAY = 0.8
        const val ECHO_MARGIN = 2.5
        const val MAX_TALK_LEVEL = 0.3
        const val HOLD_RATIO = 0.5
        const val MIN_LOUD_SAMPLES = 2
    }
}
