package com.vinhnguyen.watchai.actions

import com.vinhnguyen.watchai.brain.guard.PrivateReply
import com.vinhnguyen.watchai.voice.ChatGptRealtimeSession
import com.vinhnguyen.watchai.voice.LocalSpeech
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * In a voice conversation, the phone says private answers (messages, notes, the calendar) in its
 * own voice, where the answers play, while the model hears silence ([ChatGptRealtimeSession.speakPrivately]).
 * The pop-up shows it too. [session] is the running conversation; [scope] ends with it.
 */
class VoiceReply(
    private val speech: LocalSpeech,
    private val cards: CardHub,
    private val scope: CoroutineScope,
    private val session: () -> ChatGptRealtimeSession?,
) : PrivateReply {
    override suspend fun tell(answer: suspend () -> String): Boolean {
        val s = session() ?: return false
        val text = answer()
        cards.show(BuddyCard.Done(Symbol.PRIVATE, "Read on this phone", text))
        val audio = speech.synthesize(text) ?: return false
        // Said once the model has finished its "Here you go"; the tool doesn't wait for it.
        scope.launch { s.speakPrivately(audio) }
        return true
    }
}

/** In the chat, a private answer is shown as its own message, made on the phone and never sent to ChatGPT. */
class ChatReply(
    private val show: (String) -> Unit,
) : PrivateReply {
    override suspend fun tell(answer: suspend () -> String): Boolean {
        show(answer())
        return true
    }
}
