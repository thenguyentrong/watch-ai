package com.vinhnguyen.watchai.watchlink

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException

/**
 * The voice link between the watch and the phone: one Wear Data Layer channel carrying the
 * watch's microphone to the phone and the answer's audio back, plus small control messages.
 * The phone runs the conversation (ChatGPT voice, hand-offs, phone actions); the watch is the
 * microphone, the speaker and the face.
 *
 * Audio is 16 kHz mono in 40 ms frames, IMA ADPCM (64 kbit/s, see [Adpcm]), and only while
 * someone is talking: silence isn't sent. Raw 16-bit PCM frames are still understood.
 */
public object WatchLink {
    public const val VOICE_PATH: String = "/watchai/voice/v1"
    public const val SAMPLE_RATE: Int = 16_000
    public const val FRAME_SAMPLES: Int = SAMPLE_RATE / 25
    public const val FRAME_BYTES: Int = FRAME_SAMPLES * 2
    public const val MAX_PAYLOAD: Int = 16 * 1024
}

public sealed interface Frame {
    /** Raw PCM as described in [WatchLink]. */
    public class Audio(
        public val pcm: ByteArray,
    ) : Frame

    /** One [Adpcm] packet: a 40 ms frame at 16 kHz mono. */
    public class Adpcm(
        public val packet: ByteArray,
    ) : Frame

    public data class Message(
        val control: Control,
    ) : Frame

    /** Phone to watch: drop the answer audio still queued (the user talked over it). */
    public data object Flush : Frame
}

/**
 * [type]: "status" (phone to watch: the conversation's [phase] and the talker's [level] 0..1),
 * "ping" / "pong" (either way, [at] echoed, to measure the link's round trip), "bye" (either side ends),
 * "route" (phone to watch: [mic] true = the watch is the microphone, [speaker] true = the watch
 * plays the answer; with a headset on the phone both are false and the watch only shows the face),
 * "timer" / "alarm" (phone to watch: set one on the watch's own clock, [seconds] or [hour]:[minute],
 * with an optional [label]) and "battery" (phone to watch: its battery), each answered by "done"
 * (watch to phone: the same [id], the outcome in [text]), and "mascot" (phone to watch: Buddy's
 * reaction as a mood name in [mood] with its intensity in [level], the outfit's item names in
 * [outfit], and the [seed] that makes this user's Buddy; any of them may be missing).
 */
@Serializable
public data class Control(
    val type: String,
    val phase: String? = null,
    val level: Float? = null,
    val at: Long? = null,
    val speaker: Boolean? = null,
    val mic: Boolean? = null,
    val id: Long? = null,
    val seconds: Int? = null,
    val hour: Int? = null,
    val minute: Int? = null,
    val label: String? = null,
    val text: String? = null,
    val mood: String? = null,
    val outfit: String? = null,
    val seed: Long? = null,
)

/** Frames on the channel: kind (1 byte), payload length (2 bytes, big-endian), payload. */
public object FrameCodec {
    private const val AUDIO = 1
    private const val MESSAGE = 2
    private const val FLUSH = 3
    private const val ADPCM = 5
    private val json = Json { ignoreUnknownKeys = true }

    public fun write(
        out: DataOutputStream,
        frame: Frame,
    ): Unit = writeAll(out, listOf(frame))

    /**
     * Writes [frames] and flushes once. On the watch every flush waits for the Bluetooth link
     * (0.2-1.4 s in the 27.09 test), so sending what has piled up in one go is what keeps up.
     */
    public fun writeAll(
        out: DataOutputStream,
        frames: List<Frame>,
    ) {
        if (frames.isEmpty()) return
        synchronized(out) {
            for (frame in frames) {
                val (kind, payload) =
                    when (frame) {
                        is Frame.Audio -> AUDIO to frame.pcm
                        is Frame.Adpcm -> ADPCM to frame.packet
                        is Frame.Message -> MESSAGE to json.encodeToString(Control.serializer(), frame.control).encodeToByteArray()
                        Frame.Flush -> FLUSH to ByteArray(0)
                    }
                require(payload.size <= WatchLink.MAX_PAYLOAD) { "frame too big" }
                out.writeByte(kind)
                out.writeShort(payload.size)
                out.write(payload)
            }
            out.flush()
        }
    }

    /** The next frame, or null when the other side closed the channel. Unknown kinds are skipped. */
    public fun read(input: DataInputStream): Frame? {
        while (true) {
            val kind = input.read()
            if (kind < 0) return null
            val size =
                try {
                    input.readUnsignedShort()
                } catch (e: EOFException) {
                    return null
                }
            if (size > WatchLink.MAX_PAYLOAD) throw IOException("frame too big")
            val payload = ByteArray(size)
            try {
                input.readFully(payload)
            } catch (e: EOFException) {
                return null
            }
            when (kind) {
                AUDIO -> return Frame.Audio(payload)
                MESSAGE -> runCatching { json.decodeFromString(Control.serializer(), payload.decodeToString()) }.getOrNull()?.let { return Frame.Message(it) }
                FLUSH -> return Frame.Flush
                ADPCM -> return Frame.Adpcm(payload)
            }
        }
    }
}
