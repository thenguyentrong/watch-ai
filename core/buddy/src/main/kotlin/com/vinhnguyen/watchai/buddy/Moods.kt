package com.vinhnguyen.watchai.buddy

import java.util.Locale

/**
 * How Buddy reacts. The phone picks the mood from what is said and what the actions did; the
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

/**
 * Picks Buddy's reaction from words, when they make one obvious; otherwise null and Buddy keeps
 * the face the conversation gives it (listening, thinking, talking). Plain rules, no model: it
 * runs on every sentence, instantly and for free. English first, with a few German and
 * Vietnamese words since the user speaks those too.
 */
public object MoodReader {
    private data class Rule(
        val mood: Mood,
        val intensity: Float,
        val words: List<String>,
    )

    // First match wins, so the stronger or more specific feelings come first. Whole words and
    // phrases; a trailing * also matches longer words ("congrat*" for congratulations).
    private val ASSISTANT =
        listOf(
            Rule(Mood.OOPS, 0.7f, listOf("couldn't", "could not", "didn't work", "went wrong", "error", "fail*", "konnte nicht", "không thể")),
            Rule(Mood.CONFUSED, 0.5f, listOf("not sure", "didn't catch", "could you repeat", "say that again", "i don't know", "weiß nicht")),
            Rule(Mood.SAD, 0.6f, listOf("sorry", "unfortunately", "sad to", "leider", "tut mir leid", "xin lỗi", "tiếc")),
            Rule(Mood.LOVE, 0.7f, listOf("you're welcome", "my pleasure", "love", "aww", "gern geschehen", "không có gì")),
            Rule(Mood.LAUGH, 0.8f, listOf("haha*", "hehe*", "lol", "joke*", "funny", "witz*", "hài")),
            Rule(Mood.SURPRISED, 0.7f, listOf("wow*", "whoa", "no way", "surpris*", "incredibl*", "krass", "trời")),
            Rule(Mood.EXCITED, 0.8f, listOf("congrat*", "awesome", "amazing", "fantastic", "woohoo", "yay", "glückwunsch", "tuyệt vời")),
            Rule(Mood.PROUD, 0.7f, listOf("done", "all set", "it's set", "is set", "added", "saved", "set for", "erledigt", "gespeichert", "xong")),
            Rule(Mood.SLEEPY, 0.6f, listOf("good night", "sleep well", "sweet dreams", "gute nacht", "chúc ngủ ngon")),
            Rule(Mood.GREET, 0.6f, listOf("hello", "hi there", "hey there", "good morning", "hallo", "guten morgen", "xin chào")),
            Rule(Mood.HAPPY, 0.5f, listOf("great", "glad", "nice", "sure", "of course", "happy", "super", "gerne", "vui")),
        )

    private val USER =
        listOf(
            Rule(Mood.LOVE, 0.8f, listOf("thank*", "love you", "danke", "cảm ơn")),
            Rule(Mood.LAUGH, 0.8f, listOf("haha*", "hehe*", "lol")),
            Rule(Mood.SAD, 0.5f, listOf("i'm sad", "bad day", "i feel bad", "traurig", "buồn")),
            Rule(Mood.SLEEPY, 0.6f, listOf("good night", "i'm tired", "going to bed", "gute nacht", "ngủ")),
            Rule(Mood.GREET, 0.7f, listOf("hello", "hi buddy", "hey buddy", "good morning", "hallo", "xin chào", "chào")),
            Rule(Mood.SURPRISED, 0.6f, listOf("wow*", "whoa", "no way")),
        )

    /** For what Buddy says. A question at the end makes Buddy curious when nothing else fits. */
    public fun assistant(text: String): Reaction? {
        val t = text.lowercase(Locale.ROOT)
        return match(ASSISTANT, t) ?: if (t.trimEnd().endsWith("?")) Reaction(Mood.CURIOUS, 0.5f) else null
    }

    /** For what the user says, e.g. thanks and greetings. */
    public fun user(text: String): Reaction? = match(USER, text.lowercase(Locale.ROOT))

    private fun match(
        rules: List<Rule>,
        text: String,
    ): Reaction? = rules.firstOrNull { rule -> rule.words.any { contains(text, it) } }?.let { Reaction(it.mood, it.intensity) }

    /** [word] as a whole word ("set" isn't in "upset", "lol" isn't in "lollipop"), or as a word start if it ends in *. */
    private fun contains(
        text: String,
        word: String,
    ): Boolean {
        val prefix = word.endsWith("*")
        val needle = word.removeSuffix("*")
        var from = 0
        while (true) {
            val at = text.indexOf(needle, from)
            if (at < 0) return false
            val end = at + needle.length
            val startsWord = at == 0 || !text[at - 1].isLetterOrDigit()
            val endsWord = prefix || end == text.length || !text[end].isLetterOrDigit()
            if (startsWord && endsWord) return true
            from = at + 1
        }
    }
}
