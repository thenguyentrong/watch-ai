package com.vinhnguyen.watchai.brain

/** Answers are read aloud on a watch, so every brain gets the same short-answer rules. */
public object PromptStyle {
    public const val WATCH_SYSTEM: String =
        "You are a helpful assistant that answers on a smartwatch. " +
            "Answer in one or two short sentences, under 40 words, in plain text without markdown, " +
            "lists or links. If a question needs a long answer, give the key point and offer to go on. " +
            "Answer in the language the user writes in."

    /** For answers read aloud: written for the ear, and the user can simply talk again. */
    public const val SPOKEN_SYSTEM: String =
        "You are a friendly companion that lives on the user's smartwatch and talks with them out loud. " +
            "Reply the way people talk: one or two short sentences, under 35 words. " +
            "Plain words only - no markdown, lists, emoji, links or symbols; say numbers, units and times as they are spoken. " +
            "Don't end every reply with a question or an offer to help. " +
            "If the words you heard look garbled, say so briefly and ask the user to repeat. " +
            "Answer in the language the user speaks."

    public fun system(style: ReplyStyle): String = if (style == ReplyStyle.SPOKEN) SPOKEN_SYSTEM else WATCH_SYSTEM

    /** For brains that can't use tools when the app offers actions, so they don't pretend. */
    public const val NO_ACTIONS: String =
        "You can't take actions on the phone in this mode (no notes, calendar, reminders, timers, alarms, music, volume or other phone controls). " +
            "If asked, say briefly that this needs ChatGPT."

    /** The system prompt for a brain that can't use the request's tools. */
    public fun systemWithoutTools(request: ChatRequest): String = if (request.tools == null) system(request) else system(request) + "\n" + NO_ACTIONS

    /** The system prompt for [request]: its style, plus the device facts it carries. */
    public fun system(request: ChatRequest): String = listOfNotNull(system(request.style), request.context?.trim()?.ifEmpty { null }).joinToString("\n")

    /** Keeps the last [maxTurns] turns so on-device models stay fast and cloud requests stay small. */
    public fun trim(
        history: List<ChatTurn>,
        maxTurns: Int = 4,
    ): List<ChatTurn> = if (history.size <= maxTurns) history else history.takeLast(maxTurns)
}
