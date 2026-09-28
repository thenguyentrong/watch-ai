package com.vinhnguyen.watchai.actions

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * What the pop-up at the top of the phone shows while Buddy works: what it did, what it found,
 * and what waits for the user's yes. Actions post cards here as they run; nothing is stored.
 */
sealed interface BuddyCard {
    /** Something done: a timer, an alarm, a note, the flashlight… */
    data class Done(
        val symbol: Symbol,
        val title: String,
        val detail: String? = null,
    ) : BuddyCard

    /** An app on the phone, just opened. */
    data class App(
        val packageName: String,
        val title: String,
    ) : BuddyCard

    /** A text or a call read back, waiting for the user's yes (by voice or with the button). */
    data class Ask(
        val title: String,
        val detail: String,
        val confirm: String,
        /** Whose icon to show: the messages or phone app. */
        val packageName: String?,
    ) : BuddyCard

    /** Directions to a place: a small map. */
    data class Place(
        val name: String,
    ) : BuddyCard

    /** Messages read out, newest first. */
    data class Messages(
        val lines: List<Line>,
    ) : BuddyCard {
        data class Line(
            val packageName: String,
            val from: String,
            val text: String,
        )
    }
}

enum class Symbol { TIMER, ALARM, NOTE, CALENDAR, MUSIC, LIGHT, PHONE, CHECK, CANCEL }

class CardHub {
    private val _cards = MutableSharedFlow<BuddyCard>(extraBufferCapacity = 16)
    val cards: SharedFlow<BuddyCard> = _cards.asSharedFlow()

    private val _decidedOnScreen = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** A yes or no given with the pop-up's buttons, for the conversation to hear about. */
    val decidedOnScreen: SharedFlow<String> = _decidedOnScreen.asSharedFlow()

    fun show(card: BuddyCard) {
        _cards.tryEmit(card)
    }

    fun decidedOnScreen(result: String) {
        _decidedOnScreen.tryEmit(result)
    }

    companion object {
        /** The card for what came of a yes or no. */
        fun decision(result: String): BuddyCard = when {
            result.startsWith("ok: cancelled") -> BuddyCard.Done(Symbol.CANCEL, "Cancelled", "Nothing was sent")
            result.startsWith("ok") -> BuddyCard.Done(Symbol.CHECK, result.removePrefix("ok: ").substringBefore(". ").replaceFirstChar { it.uppercase() })
            else -> BuddyCard.Done(Symbol.CANCEL, "Didn't go through", result.removePrefix("error: ").substringBefore(";").replaceFirstChar { it.uppercase() })
        }
    }
}
