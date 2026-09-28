package com.vinhnguyen.watchai.watchlink

/**
 * Evens out how loud speech reaches the wake word model. A watch mic hears speech far quieter
 * than the recordings the model learned from, and it misses quiet speech: on the PC (28.09), at a
 * watch-like level it heard 13 of 24 test phrases as they were and 24 evened out, with no more
 * false alarms on look-alike speech. That differs by device, distance and voice, so the gain
 * follows the loudest recent sound instead of a fixed number: only ever louder, at most
 * [MAX_GAIN] times.
 */
public class Leveller {
    private var speech = 0f

    /** The gain for the next 100 ms chunk at [level] (RMS, 0..1); [sound] says whether it passed the gate. */
    public fun gain(
        level: Float,
        sound: Boolean,
    ): Float {
        speech *= DECAY
        if (sound) speech = maxOf(speech, level)
        return gainFor(speech)
    }

    /** Starts over, as for a new listening window. */
    public fun reset() {
        speech = 0f
    }

    public companion object {
        /** Where speech is brought to (100 ms RMS): about how loud the model's own recordings are. */
        public const val TARGET: Float = 0.1f
        public const val MAX_GAIN: Float = 30f

        // Per chunk: a loud sound stops holding the gain down after a few seconds.
        private const val DECAY = 0.98f

        /** The gain that brings a recording whose loudest 100 ms is at [peak] to [TARGET]. */
        public fun gainFor(peak: Float): Float = (TARGET / maxOf(peak, TARGET / MAX_GAIN)).coerceIn(1f, MAX_GAIN)
    }
}
