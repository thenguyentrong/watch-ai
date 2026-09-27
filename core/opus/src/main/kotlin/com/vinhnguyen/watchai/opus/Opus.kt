package com.vinhnguyen.watchai.opus

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import com.vinhnguyen.watchai.watchlink.Pcm
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Opus through the platform's own software codec (MediaCodec, part of Android 10 and later):
 * speech at about 24 kbit/s instead of 256 kbit/s of raw 16 kHz PCM, so the watch's Bluetooth
 * link doesn't queue up. Each encoder or decoder is used from one thread only.
 */
object Opus {
    fun available(): Boolean {
        val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        fun has(encoder: Boolean) = codecs.any { it.isEncoder == encoder && it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_AUDIO_OPUS, true) } }
        return has(encoder = true) && has(encoder = false)
    }

    internal const val TIMEOUT_US = 5_000L

    /** Opus decodes at 48 kHz internally; headers describe a mono stream recorded at [inputRate]. */
    internal fun decoderFormat(inputRate: Int): MediaFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, 48_000, 1).apply {
        setByteBuffer("csd-0", opusHead(inputRate))
        setByteBuffer("csd-1", nanos(PRE_SKIP_SAMPLES * 1_000_000_000L / 48_000))
        setByteBuffer("csd-2", nanos(SEEK_PRE_ROLL_NS))
    }

    /** The 19-byte "OpusHead" identification header (RFC 7845), mono, mapping family 0. */
    private fun opusHead(inputRate: Int): ByteBuffer = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("OpusHead".toByteArray(Charsets.US_ASCII))
        put(1) // version
        put(1) // channels
        putShort(PRE_SKIP_SAMPLES.toShort())
        putInt(inputRate)
        putShort(0) // output gain
        put(0) // channel mapping family
        flip()
    }

    private fun nanos(value: Long): ByteBuffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).apply { flip() }

    private const val PRE_SKIP_SAMPLES = 312
    private const val SEEK_PRE_ROLL_NS = 80_000_000L
}

class OpusEncoder(
    private val sampleRate: Int,
    bitRate: Int = 24_000,
) : Closeable {
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
    private val info = MediaCodec.BufferInfo()
    private var presentationUs = 0L

    init {
        val format =
            MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, sampleRate, 1).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, sampleRate / 50 * 2 * 4)
            }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    /** Feeds 16-bit mono [pcm] and returns the Opus packets that are ready (often one per 20 ms). */
    fun encode(pcm: ShortArray): List<ByteArray> {
        val index = codec.dequeueInputBuffer(Opus.TIMEOUT_US)
        if (index >= 0) {
            val buffer = codec.getInputBuffer(index) ?: return drain()
            buffer.clear()
            val bytes = Pcm.toBytes(pcm)
            val size = minOf(bytes.size, buffer.remaining())
            buffer.put(bytes, 0, size)
            codec.queueInputBuffer(index, 0, size, presentationUs, 0)
            presentationUs += size / 2 * 1_000_000L / sampleRate
        }
        return drain()
    }

    private fun drain(): List<ByteArray> {
        val packets = mutableListOf<ByteArray>()
        while (true) {
            val index = codec.dequeueOutputBuffer(info, 0)
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
            if (index < 0) break
            val buffer = codec.getOutputBuffer(index)
            if (buffer != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                val packet = ByteArray(info.size)
                buffer.position(info.offset)
                buffer.get(packet)
                packets += packet
            }
            codec.releaseOutputBuffer(index, false)
        }
        return packets
    }

    override fun close() {
        runCatching { codec.stop() }
        codec.release()
    }
}

/** Decodes Opus packets from [OpusEncoder] back to 16-bit mono PCM at [outputRate]. */
class OpusDecoder(
    private val outputRate: Int,
) : Closeable {
    private val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
    private val info = MediaCodec.BufferInfo()
    private var presentationUs = 0L
    private var codecRate = 48_000
    private var codecChannels = 1

    init {
        codec.configure(Opus.decoderFormat(outputRate), null, null, 0)
        codec.start()
    }

    fun decode(packet: ByteArray): ShortArray {
        val index = codec.dequeueInputBuffer(Opus.TIMEOUT_US)
        if (index >= 0) {
            val buffer = codec.getInputBuffer(index)
            if (buffer != null) {
                buffer.clear()
                buffer.put(packet, 0, minOf(packet.size, buffer.remaining()))
                codec.queueInputBuffer(index, 0, packet.size, presentationUs, 0)
                presentationUs += FRAME_US
            }
        }
        return drain()
    }

    private fun drain(): ShortArray {
        var pcm = ShortArray(0)
        while (true) {
            val index = codec.dequeueOutputBuffer(info, 0)
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                codec.outputFormat.let { f ->
                    codecRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    codecChannels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
                continue
            }
            if (index < 0) break
            val buffer = codec.getOutputBuffer(index)
            if (buffer != null && info.size > 0) {
                val bytes = ByteArray(info.size)
                buffer.position(info.offset)
                buffer.get(bytes)
                val all = Pcm.toShorts(bytes)
                val mono = if (codecChannels <= 1) all else ShortArray(all.size / codecChannels) { all[it * codecChannels] }
                pcm += Pcm.resample(mono, codecRate, outputRate)
            }
            codec.releaseOutputBuffer(index, false)
        }
        return pcm
    }

    override fun close() {
        runCatching { codec.stop() }
        codec.release()
    }

    private companion object {
        const val FRAME_US = 20_000L
    }
}
