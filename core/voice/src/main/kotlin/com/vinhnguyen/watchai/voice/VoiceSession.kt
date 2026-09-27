package com.vinhnguyen.watchai.voice

import kotlinx.coroutines.flow.StateFlow

enum class VoicePhase { IDLE, CONNECTING, LISTENING, THINKING, SPEAKING, ERROR }

data class VoiceState(
    val phase: VoicePhase = VoicePhase.IDLE,
    val detail: String? = null,
    /** Last things heard/said, for the lab screen only (never logged). */
    val lastUserText: String? = null,
    val lastAssistantText: String? = null,
    val metrics: VoiceMetrics = VoiceMetrics(),
)

/**
 * Turn latency = from the moment the user stops talking to the first sound of the answer.
 * Interrupt latency = from the user starting to talk over the answer to the answer going quiet.
 */
data class VoiceMetrics(
    val turnLatenciesMs: List<Long> = emptyList(),
    val interruptLatenciesMs: List<Long> = emptyList(),
    val notes: List<String> = emptyList(),
) {
    fun withTurn(ms: Long) = copy(turnLatenciesMs = turnLatenciesMs + ms)

    fun withInterrupt(ms: Long) = copy(interruptLatenciesMs = interruptLatenciesMs + ms)

    fun withNote(note: String) = copy(notes = (notes + note).takeLast(40))
}

/** A hands-free conversation: start once, then listen → think → speak → listen until stopped. */
interface VoiceSession {
    val name: String
    val state: StateFlow<VoiceState>

    /** Loudness 0..1 of whoever is talking right now (the user, or the answer when known), for the talk animation. */
    val level: StateFlow<Float>

    /** Safe to call at any time, also while [start] is still connecting. */
    suspend fun start()

    /** Safe to call at any time and more than once; waits until everything is released. */
    suspend fun stop()
}
