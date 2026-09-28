package com.vinhnguyen.watchai.wear

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.vinhnguyen.watchai.watchlink.Leveller
import com.vinhnguyen.watchai.watchlink.Pcm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * "Learn my voice": the user reads out a few sentences that start with "Hey Buddy", and this learns
 * how the wake word model hears their voice saying it. The "Hey Buddy" of each take is decoded as
 * speech with the same model, and what it heard in at least two takes is listened for next to the
 * default phrase (once-only versions only if that isn't enough, and a little more loosely only
 * after that). The same for every voice, and nothing about the person is assumed. The takes stay
 * in memory and are dropped after; only what the model heard is kept.
 *
 * Levels are measured against the room and the take itself, not fixed numbers, so it works the
 * same on other watches, in other rooms, near and far.
 *
 * The recognizer takes about as long to load as the wake word: create this off the main thread.
 */
internal class VoiceTeacher(
    context: Context,
) {
    private val assets = context.applicationContext.assets
    private val recognizer = OnlineRecognizer(assets, CONFIG)
    private val tokens: Set<String> = assets.open("kws/tokens.txt").bufferedReader().useLines { lines -> lines.map { it.substringBefore(' ') }.toSet() }

    // What everyone starts with (keywords.txt): taught spellings come on top of these.
    private val defaults: List<List<String>> = assets.open("kws/keywords.txt").bufferedReader().useLines { lines -> lines.filter { it.isNotBlank() }.map { it.substringBefore(" :").split(' ') }.toList() }

    /** What a take gave. Anything but [Heard] asks for it again. */
    sealed interface Take {
        class Heard(
            val pcm: ShortArray,
        ) : Take

        /** Nothing said within 8 s (or only a click). */
        data object Nothing : Take

        /** Too quiet to learn from, even evened out. */
        data object Quiet : Take

        /** So loud it clipped. */
        data object Loud : Take

        /** Hardly louder than the room. */
        data object Noisy : Take
    }

    /** One take: waits up to 8 s for the user to start and ends after a pause. */
    @SuppressLint("MissingPermission") // the teach screen asks for the microphone first
    suspend fun take(): Take = withContext(Dispatchers.IO) {
        val minBuffer = AudioRecord.getMinBufferSize(WakeWord.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, WakeWord.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, WakeWord.SAMPLE_RATE * 2))
        try {
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) return@withContext Take.Nothing
            val before = ArrayDeque<ShortArray>()
            val said = mutableListOf<ShortArray>()
            var floor = START_FLOOR
            var waited = 0
            var quiet = 0
            var loud = 0
            var peak = 0f
            while (true) {
                ensureActive()
                val chunk = ShortArray(CHUNK)
                val n = record.read(chunk, 0, CHUNK)
                if (n < 0) return@withContext Take.Nothing
                if (n == 0) continue
                val level = Pcm.level(chunk, n)
                // Until the user starts, the room's noise (drops at once, rises slowly); "sound" is what stands out from it.
                if (said.isEmpty()) floor = if (level < floor) level else floor + (level - floor) * FLOOR_RISE
                val sound = level >= maxOf(MIN_SOUND, floor * ONSET)
                if (said.isEmpty()) {
                    if (!sound) {
                        // A little of the quiet before, so the first syllable is whole.
                        before.addLast(chunk.copyOf(n))
                        if (before.size > BEFORE_CHUNKS) before.removeFirst()
                        if (++waited >= WAIT_CHUNKS) return@withContext Take.Nothing
                        continue
                    }
                    said += before
                }
                said += chunk.copyOf(n)
                peak = maxOf(peak, level)
                if (sound) loud++
                quiet = if (sound) 0 else quiet + 1
                if (quiet >= END_CHUNKS || said.size >= MAX_CHUNKS) break
            }
            // A click or a cough isn't a take.
            if (loud < MIN_LOUD_CHUNKS) return@withContext Take.Nothing
            val pcm = ShortArray(said.sumOf { it.size })
            var at = 0
            for (c in said) {
                c.copyInto(pcm, at)
                at += c.size
            }
            when {
                pcm.count { it >= CLIP || it <= -CLIP } > pcm.size / CLIPPED_SHARE -> Take.Loud
                peak < MIN_PEAK -> Take.Quiet
                peak < floor * MIN_ABOVE_ROOM -> Take.Noisy
                else -> Take.Heard(pcm)
            }
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }

    /**
     * The "Hey Buddy" of a take: its first stretch of speech, up to the first pause (the comma
     * after it). The whole take if there's none. Quiet is measured against the take's own
     * loudest part.
     */
    fun phrase(pcm: ShortArray): ShortArray {
        val frames = pcm.size / FRAME
        val quietBelow = WakeWord.peakLevel(pcm) * PAUSE_SHARE
        var started = -1
        var quiet = 0
        for (f in 0 until frames) {
            val sound = Pcm.level(pcm.copyOfRange(f * FRAME, (f + 1) * FRAME)) >= quietBelow
            if (started < 0) {
                if (sound) started = f
                continue
            }
            quiet = if (sound) 0 else quiet + 1
            // A pause after at least 300 ms of speech; keep a little of it so the last sound is whole.
            if (quiet >= PAUSE_FRAMES && f - quiet - started >= MIN_PHRASE_FRAMES) return pcm.copyOf((f - quiet + TAIL_FRAMES) * FRAME)
        }
        return pcm
    }

    /** How the model hears [pcm], evened out: its tokens, word starts marked with ▁ as in keywords.txt. */
    fun spelling(pcm: ShortArray): List<String> {
        val gain = Leveller.gainFor(WakeWord.peakLevel(pcm))
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(WakeWord.QUIET, WakeWord.SAMPLE_RATE)
            stream.acceptWaveform(FloatArray(pcm.size) { (pcm[it] * gain / 32_768f).coerceIn(-1f, 1f) }, WakeWord.SAMPLE_RATE)
            stream.acceptWaveform(FloatArray(WakeWord.SAMPLE_RATE / 2), WakeWord.SAMPLE_RATE)
            stream.inputFinished()
            while (recognizer.isReady(stream)) recognizer.decode(stream)
            return recognizer.getResult(stream).tokens.map { if (it.startsWith(" ")) "▁" + it.drop(1) else it }.filter { it.isNotBlank() }
        } finally {
            stream.release()
        }
    }

    /**
     * Learns from [takes] (with their [spellings]) against the wake word [word]: what to listen
     * for, and how loosely, for all but one take to be heard. Tries, in order: what the model
     * heard in two or more takes; that plus what it heard once; each a step looser at a time.
     */
    fun learn(
        takes: List<ShortArray>,
        spellings: List<List<String>>,
        word: WakeWord,
    ): Lesson {
        val shaped = spellings.mapNotNull { shape(it) }.filter { s -> s !in defaults && s.all { it in tokens } }
        val counts = shaped.groupingBy { it }.eachCount()
        val repeated = counts.filter { it.value >= 2 }.keys.toList()
        val once = counts.filter { it.value == 1 }.keys.toList()
        var chosen = keywords(defaults, STEPS.first())
        val before = takes.count { word.firstKeyword(chosen, it) != null }
        var caught = before
        tries@ for (learned in listOf(repeated, repeated + once)) {
            val all = defaults + learned.take(MAX_SPELLINGS)
            for (step in STEPS) {
                val lines = keywords(all, step)
                val n = takes.count { word.firstKeyword(lines, it) != null }
                if (n > caught || lines == chosen) {
                    caught = n
                    chosen = lines
                }
                if (caught >= takes.size - 1) break@tries
            }
        }
        Log.i(TAG, "heard ${spellings.joinToString(" | ") { it.joinToString(" ") }}; before $before/${takes.size}, now $caught; $chosen")
        return Lesson(chosen, before, caught, takes.size)
    }

    fun release() = recognizer.release()

    /** What learning gave: the keyword lines to listen for, and how many takes were heard before and after. */
    data class Lesson(
        val keywords: String,
        val before: Int,
        val after: Int,
        val takes: Int,
    )

    companion object {
        private const val TAG = "VoiceTeacher"
        private const val CHUNK = WakeWord.SAMPLE_RATE / 10
        private const val START_FLOOR = 0.002f
        private const val FLOOR_RISE = 0.05f
        private const val MIN_SOUND = 0.002f
        private const val ONSET = 2.5f
        private const val MIN_PEAK = 0.006f
        private const val MIN_ABOVE_ROOM = 5f
        private const val CLIP = 32_000
        private const val CLIPPED_SHARE = 200
        private const val BEFORE_CHUNKS = 3
        private const val WAIT_CHUNKS = 80
        private const val END_CHUNKS = 8
        private const val MAX_CHUNKS = 70
        private const val FRAME = WakeWord.SAMPLE_RATE / 50
        private const val PAUSE_SHARE = 0.12f
        private const val PAUSE_FRAMES = 10
        private const val MIN_PHRASE_FRAMES = 15
        private const val TAIL_FRAMES = 5
        private const val MIN_LOUD_CHUNKS = 3
        private const val MAX_SPELLINGS = 5

        // Shorter ones ("hey be", "a bit") went off in everyday talk in the broad test (28.09).
        private const val MIN_PIECES = 5

        /** Boost and threshold, strictest first; keywords.txt uses the first. Looser than the last wasn't tried. */
        private val STEPS = listOf(2.0f to 0.15f, 2.5f to 0.12f, 3.0f to 0.10f)

        private val CONFIG =
            OnlineRecognizerConfig(
                featConfig = WakeWord.FEATURES,
                modelConfig = WakeWord.MODEL_CONFIG,
                decodingMethod = "modified_beam_search",
                maxActivePaths = 4,
                enableEndpoint = false,
            )

        /**
         * What's worth listening for in one take's spelling: two or three words (one alone would
         * fire on too much) of at least [MIN_PIECES] pieces; when the sentence ran on without a
         * pause, its first two words. Null if nothing is. The first piece starts a word even
         * without the mark: the model sometimes hears it that way.
         */
        fun shape(spelling: List<String>): List<String>? {
            if (spelling.isEmpty()) return null
            val starts = spelling.indices.filter { it == 0 || spelling[it].startsWith("▁") }
            val s = if (starts.size > 3) spelling.take(starts[2]) else spelling
            val words = s.indices.count { it == 0 || s[it].startsWith("▁") }
            return s.takeIf { words in 2..3 && it.size in MIN_PIECES..10 }
        }

        /** keywords.txt lines for [spellings] at a boost and threshold, "/" between them. */
        fun keywords(
            spellings: List<List<String>>,
            step: Pair<Float, Float>,
        ): String = spellings.joinToString("/") { "${it.joinToString(" ")} :${step.first} #${step.second} @HEY_BUDDY" }
    }
}
