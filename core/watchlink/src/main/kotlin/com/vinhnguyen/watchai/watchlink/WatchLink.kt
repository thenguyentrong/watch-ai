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
 * Audio is 16 kHz mono 16-bit little-endian PCM both ways, in 20 ms frames.
 */
public object WatchLink {
    public const val VOICE_PATH: String = "/watchai/voice/v1"
    public const val SAMPLE_RATE: Int = 16_000
    public const val FRAME_SAMPLES: Int = SAMPLE_RATE / 50
    public const val FRAME_BYTES: Int = FRAME_SAMPLES * 2
    public const val MAX_PAYLOAD: Int = 16 * 1024
}

public sealed interface Frame {
    /** PCM as described in [WatchLink]. */
    public class Audio(
        public val pcm: ByteArray,
    ) : Frame

    public data class Message(
        val control: Control,
    ) : Frame

    /** Phone to watch: drop the answer audio still queued (the user talked over it). */
    public data object Flush : Frame
}

/**
 * [type]: "status" (phone to watch: the conversation's [phase] and the talker's [level] 0..1),
 * "ping" / "pong" (either way, [at] echoed, to measure the link's round trip), "bye" (either side ends).
 */
@Serializable
public data class Control(
    val type: String,
    val phase: String? = null,
    val level: Float? = null,
    val at: Long? = null,
)

/** Frames on the channel: kind (1 byte), payload length (2 bytes, big-endian), payload. */
public object FrameCodec {
    private const val AUDIO = 1
    private const val MESSAGE = 2
    private const val FLUSH = 3
    private val json = Json { ignoreUnknownKeys = true }

    public fun write(
        out: DataOutputStream,
        frame: Frame,
    ) {
        val (kind, payload) =
            when (frame) {
                is Frame.Audio -> AUDIO to frame.pcm
                is Frame.Message -> MESSAGE to json.encodeToString(Control.serializer(), frame.control).encodeToByteArray()
                Frame.Flush -> FLUSH to ByteArray(0)
            }
        require(payload.size <= WatchLink.MAX_PAYLOAD) { "frame too big" }
        synchronized(out) {
            out.writeByte(kind)
            out.writeShort(payload.size)
            out.write(payload)
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
            }
        }
    }
}
