package com.vinhnguyen.watchai.wear

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

/**
 * "Hey Buddy", heard on the watch itself: sherpa-onnx keyword spotting with a small English model
 * (assets/kws, the phrase in keywords.txt). Loading takes about 20 s on a Galaxy Watch5 and
 * listening keeps about 0.7 of a core busy, so it's loaded once and fed only in short windows.
 * The native side isn't thread-safe (two threads decoding at once crashed the app, 27.09): every
 * call takes the lock.
 */
internal class WakeWord(
    context: Context,
) {
    private val spotter = KeywordSpotter(context.assets, CONFIG)
    private val stream = spotter.createStream()

    /** Feeds [n] samples of 16 kHz mono audio; true once the phrase was heard (it then starts over). */
    @Synchronized
    fun heard(
        pcm: ShortArray,
        n: Int,
    ): Boolean {
        stream.acceptWaveform(FloatArray(n) { pcm[it] / 32_768f }, SAMPLE_RATE)
        while (spotter.isReady(stream)) {
            spotter.decode(stream)
            if (spotter.getResult(stream).keyword.isNotEmpty()) {
                spotter.reset(stream)
                return true
            }
        }
        return false
    }

    /** Forgets what it heard so far, before a new listening window. */
    @Synchronized
    fun reset() = spotter.reset(stream)

    @Synchronized
    fun release() {
        stream.release()
        spotter.release()
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val MODEL = "epoch-12-avg-2-chunk-16-left-64.int8.onnx"

        val CONFIG =
            KeywordSpotterConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig =
                OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(encoder = "kws/encoder-$MODEL", decoder = "kws/decoder-$MODEL", joiner = "kws/joiner-$MODEL"),
                    tokens = "kws/tokens.txt",
                    numThreads = 1,
                    modelType = "zipformer2",
                ),
                // Must exist and be valid: the native side ends the process otherwise.
                keywordsFile = "kws/keywords.txt",
            )
    }
}
