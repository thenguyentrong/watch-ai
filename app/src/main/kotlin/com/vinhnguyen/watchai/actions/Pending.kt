package com.vinhnguyen.watchai.actions

import android.os.SystemClock

/**
 * One thing waiting for the user's yes. Messages and calls are never sent when asked, only read
 * back: one goes out when the user says yes in a later turn than the one it was proposed in (so
 * the assistant can't confirm its own proposal, and nothing in a message it read can), within
 * [TTL_MS]. A new proposal replaces the old one.
 */
class Pending(
    private val turns: UserTurns,
    private val now: () -> Long = SystemClock::elapsedRealtime,
) {
    private class Proposal(
        val summary: String,
        val at: Long,
        val go: suspend () -> String,
    )

    private var current: Proposal? = null

    /** Keeps [go] for later and returns what the assistant should ask. */
    @Synchronized
    fun propose(
        summary: String,
        go: suspend () -> String,
    ): String {
        current = Proposal(summary, now(), go)
        return "not done yet: $summary Read it back to the user and ask if you should go ahead; " +
            "only if they clearly say yes, call confirm_action with yes."
    }

    /** What the user decided about the proposal. */
    suspend fun decide(yes: Boolean): String {
        val p = synchronized(this) { current } ?: return "error: nothing is waiting for a yes"
        if (now() - p.at > TTL_MS) {
            clear(p)
            return "error: that was too long ago and was dropped; ask the user again if they still want it"
        }
        if (turns.lastAt() <= p.at) return "error: the user hasn't answered yet; ask them first and wait for their answer"
        clear(p)
        return if (yes) p.go() else "ok: cancelled, nothing was sent"
    }

    /** Drops whatever waits for a yes: the user said stop. */
    @Synchronized
    fun cancel() {
        current = null
    }

    @Synchronized
    private fun clear(p: Proposal) {
        if (current === p) current = null
    }

    companion object {
        const val TTL_MS = 120_000L
    }
}

/** When the user last said (or typed) something, to tell their yes from the assistant's own words. */
class UserTurns(
    private val now: () -> Long = SystemClock::elapsedRealtime,
) {
    @Volatile private var at = 0L

    fun heard() {
        at = now()
    }

    fun lastAt(): Long = at
}
