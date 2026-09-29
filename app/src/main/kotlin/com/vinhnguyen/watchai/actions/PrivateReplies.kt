package com.vinhnguyen.watchai.actions

import com.vinhnguyen.watchai.brain.guard.PrivateReply
import com.vinhnguyen.watchai.voice.ChatGptRealtimeSession
import com.vinhnguyen.watchai.voice.LanguageId
import com.vinhnguyen.watchai.voice.LocalSpeech
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * In a voice conversation, the phone says private answers (messages, notes, the calendar) in its
 * own voice, where the answers play, while the model hears silence ([ChatGptRealtimeSession.speakPrivately]).
 * The pop-up shows it too. [session] is the running conversation; [scope] ends with it.
 */
class VoiceReply(
    private val speech: LocalSpeech,
    private val cards: CardHub,
    private val scope: CoroutineScope,
    private val languages: LanguageId,
    private val session: () -> ChatGptRealtimeSession?,
) : PrivateReply {
    override suspend fun tell(answer: suspend () -> String): Boolean = tell(null, answer)

    /**
     * Said in the voice for the language the answer is really in (the phone tells it from the text;
     * else the user's [language]). Without an offline voice for it, it's only shown, and the model
     * tells the user to look.
     */
    override suspend fun tell(
        language: String?,
        answer: suspend () -> String,
    ): Boolean {
        val s = session() ?: return false
        val text = answer()
        cards.show(BuddyCard.Done(Symbol.PRIVATE, "Read on this phone", text))
        val spoken = languages.of(text) ?: language
        val audio = speech.synthesize(text, spoken)
        if (audio == null) {
            val name = spoken?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.ENGLISH) }.orEmpty().ifEmpty { "this language" }
            cards.show(BuddyCard.Done(Symbol.PRIVATE, "No offline voice for $name: add one in Text-to-speech settings", text))
            return false
        }
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
