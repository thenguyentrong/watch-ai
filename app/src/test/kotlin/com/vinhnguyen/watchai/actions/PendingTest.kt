package com.vinhnguyen.watchai.actions

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

class PendingTest {
    private var clock = 1_000L
    private val turns = UserTurns { clock }
    private val pending = Pending(turns) { clock }
    private var sent = 0

    private fun propose() = pending.propose("Send \"late\" to Anna by SMS.") {
        sent++
        "ok: sent"
    }

    @Test
    fun `nothing goes out when it's proposed`() {
        assertThat(propose()).startsWith("not done yet")
        assertThat(sent).isEqualTo(0)
    }

    @Test
    fun `a yes in a later turn sends it once`() = runBlocking {
        propose()
        clock += 3_000
        turns.heard()
        clock += 500
        assertThat(pending.decide(yes = true)).isEqualTo("ok: sent")
        assertThat(sent).isEqualTo(1)
        // It's gone after that: a second yes sends nothing.
        assertThat(pending.decide(yes = true)).startsWith("error")
        assertThat(sent).isEqualTo(1)
    }

    @Test
    fun `the assistant can't confirm its own proposal before the user answered`() = runBlocking {
        turns.heard()
        clock += 100
        propose()
        clock += 100
        assertThat(pending.decide(yes = true)).startsWith("error: the user hasn't answered")
        assertThat(sent).isEqualTo(0)
        // Still waiting for the real answer.
        clock += 1_000
        turns.heard()
        assertThat(pending.decide(yes = true)).isEqualTo("ok: sent")
    }

    @Test
    fun `a no cancels it`() = runBlocking {
        propose()
        clock += 1_000
        turns.heard()
        assertThat(pending.decide(yes = false)).startsWith("ok: cancelled")
        assertThat(pending.decide(yes = true)).startsWith("error")
        assertThat(sent).isEqualTo(0)
    }

    @Test
    fun `a yes that comes too late sends nothing`() = runBlocking {
        propose()
        clock += Pending.TTL_MS + 1
        turns.heard()
        assertThat(pending.decide(yes = true)).startsWith("error: that was too long ago")
        assertThat(sent).isEqualTo(0)
    }

    @Test
    fun `a new proposal replaces the old one`() = runBlocking {
        propose()
        var other = 0
        pending.propose("Call Jan.") {
            other++
            "ok: calling"
        }
        clock += 1_000
        turns.heard()
        assertThat(pending.decide(yes = true)).isEqualTo("ok: calling")
        assertThat(sent).isEqualTo(0)
        assertThat(other).isEqualTo(1)
    }
}
