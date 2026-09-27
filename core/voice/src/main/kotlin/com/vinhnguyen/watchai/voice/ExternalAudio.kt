package com.vinhnguyen.watchai.voice

import java.nio.ByteBuffer

/**
 * A microphone and speaker somewhere else, e.g. the watch: the conversation runs on the phone,
 * but what the user says comes from here and the answer plays there. The phone's own mic is
 * replaced sample for sample and its speaker stays silent.
 */
interface ExternalAudio {
    /** Human-readable, for the lab screen ("Galaxy Watch5"). */
    val name: String

    /**
     * True while the answer should play on the phone itself (headphones are connected to it), with
     * this device only the microphone. May change during a conversation.
     */
    val answerOnPhone: Boolean

    /**
     * True while this device is the microphone. False while a headset on the phone is both mic and
     * speaker (this device then only shows the conversation). May change during a conversation.
     */
    val micOnDevice: Boolean

    /**
     * Called every 10 ms on WebRTC's recording thread, before the audio is sent: overwrite the
     * first [bytes] of [buffer] (16-bit PCM, [sampleRate], [channels] interleaved) with the user's voice.
     */
    fun fillMicrophone(
        buffer: ByteBuffer,
        bytes: Int,
        sampleRate: Int,
        channels: Int,
    )

    /** The answer's audio as it arrives (16-bit PCM). Called on a WebRTC thread; must not block. */
    fun playAnswer(
        data: ByteBuffer,
        sampleRate: Int,
        channels: Int,
        frames: Int,
    )

    /** The user talked over the answer: drop what is still queued to play. */
    fun flushAnswer()

    /** The conversation's phase and the talker's loudness 0..1, for the face on the other device. */
    fun status(
        phase: VoicePhase,
        level: Float,
    )
}
