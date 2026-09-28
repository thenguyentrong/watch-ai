package com.vinhnguyen.watchai.actions

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CardHubTest {
    @Test
    fun `a no says nothing was sent`() {
        assertThat(CardHub.decision("ok: cancelled, nothing was sent")).isEqualTo(BuddyCard.Done(Symbol.CANCEL, "Cancelled", "Nothing was sent"))
    }

    @Test
    fun `a sent text shows who it went to`() {
        assertThat(CardHub.decision("ok: sent to Alex")).isEqualTo(BuddyCard.Done(Symbol.CHECK, "Sent to Alex"))
    }

    @Test
    fun `only the first sentence is shown, not what the voice is told to say`() {
        val card = CardHub.decision("ok: calling Alex now. Say goodbye in two or three words: the call takes over.")
        assertThat(card).isEqualTo(BuddyCard.Done(Symbol.CHECK, "Calling Alex now"))
    }

    @Test
    fun `a failure says why, without the advice for the model`() {
        val card = CardHub.decision("error: the phone couldn't send it (code 2); maybe no signal")
        assertThat(card).isEqualTo(BuddyCard.Done(Symbol.CANCEL, "Didn't go through", "The phone couldn't send it (code 2)"))
    }

    @Test
    fun `a tap with nothing waiting doesn't pretend it worked`() {
        val card = CardHub.decision("error: nothing is waiting for a yes") as BuddyCard.Done
        assertThat(card.symbol).isEqualTo(Symbol.CANCEL)
        assertThat(card.title).isEqualTo("Didn't go through")
    }

    @Test
    fun `cards only reach someone who is listening`() {
        val hub = CardHub()
        hub.show(BuddyCard.Place("Aachen Hauptbahnhof"))
        assertThat(hub.cards.replayCache).isEmpty()
    }
}
