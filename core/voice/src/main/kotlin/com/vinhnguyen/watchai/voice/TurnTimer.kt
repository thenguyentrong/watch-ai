package com.vinhnguyen.watchai.voice

/**
 * Works out turn and interrupt latency from two audio levels sampled over time: the microphone
 * (the user) and the answer's audio (the assistant). Pure logic so it can be unit-tested.
 */
class TurnTimer(
    private val speakOn: Double = 0.06,
    private val quietOff: Double = 0.02,
    private val userQuietMs: Long = 350,
    private val assistantQuietMs: Long = 250,
) {
    sealed interface Event {
        data class Turn(
            val latencyMs: Long,
        ) : Event

        data class Interrupt(
            val latencyMs: Long,
        ) : Event
    }

    private var userTalking = false
    private var userQuietSince: Long? = null
    private var userStoppedAt: Long? = null
    private var assistantTalking = false
    private var assistantQuietSince: Long? = null
    private var bargeInAt: Long? = null

    val assistantSpeaking: Boolean get() = assistantTalking
    val userSpeaking: Boolean get() = userTalking

    fun sample(
        nowMs: Long,
        userLevel: Double,
        assistantLevel: Double,
    ): Event? {
        var event: Event? = null

        // Assistant audio.
        if (assistantLevel >= quietOff) {
            assistantQuietSince = null
            if (!assistantTalking) {
                assistantTalking = true
                userStoppedAt?.let { event = Event.Turn(nowMs - it) }
                userStoppedAt = null
            }
        } else if (assistantTalking) {
            val since = assistantQuietSince ?: nowMs.also { assistantQuietSince = it }
            if (nowMs - since >= assistantQuietMs) {
                assistantTalking = false
                bargeInAt?.let { event = Event.Interrupt(since - it) }
                bargeInAt = null
            }
        }

        // User audio (echo-cancelled microphone).
        if (userLevel >= speakOn) {
            userQuietSince = null
            if (!userTalking) {
                userTalking = true
                userStoppedAt = null
                if (assistantTalking && bargeInAt == null) bargeInAt = nowMs
            }
        } else if (userTalking && userLevel < quietOff) {
            val since = userQuietSince ?: nowMs.also { userQuietSince = it }
            if (nowMs - since >= userQuietMs) {
                userTalking = false
                userStoppedAt = since
            }
        }
        return event
    }
}
