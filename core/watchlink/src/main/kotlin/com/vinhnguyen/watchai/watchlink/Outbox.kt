package com.vinhnguyen.watchai.watchlink

import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Frames waiting to go over the link. Producers never block; the writer takes waiting frames in
 * batches and sends them with a single flush so Bluetooth isn't hammered with tiny per-frame
 * flushes. If the link falls behind, the oldest audio is dropped ([maxAudio]) so the delay can't
 * keep growing; control frames are never dropped.
 */
public class Outbox(
    private val maxAudio: Int,
) {
    private class Entry(
        val frame: Frame,
        val urgent: Boolean,
    )

    private val lock = ReentrantLock()
    private val ready = lock.newCondition()
    private val frames = ArrayDeque<Entry>()
    private val backlog = ArrayDeque<Frame>()
    private var audioCount = 0
    private var urgentCount = 0

    /** Frames dropped because the link was behind (for the link counters). */
    public var dropped: Long = 0
        private set

    public val size: Int get() = lock.withLock { backlog.size + frames.size }

    /**
     * Audio that is late on purpose (what the user said before the phone picked up): goes out
     * first and is never dropped for being behind, however long it is.
     */
    public fun offerBacklog(backlogFrames: List<Frame>) {
        if (backlogFrames.isEmpty()) return
        lock.withLock {
            backlog.addAll(backlogFrames)
            ready.signal()
        }
    }

    public fun offer(frame: Frame): Unit = offer(frame, urgent = frame.defaultUrgent())

    public fun offer(
        frame: Frame,
        urgent: Boolean,
    ) {
        lock.withLock {
            if (frame.isAudio()) {
                while (audioCount >= maxAudio) dropOldestAudio()
                audioCount++
            }
            if (urgent) urgentCount++
            frames.addLast(Entry(frame, urgent))
            ready.signal()
        }
    }

    /** Drops queued audio (the user interrupted the answer); control frames stay. */
    public fun clearAudio() {
        lock.withLock {
            val it = frames.iterator()
            while (it.hasNext()) {
                val entry = it.next()
                if (entry.frame.isAudio()) {
                    if (entry.urgent) urgentCount--
                    it.remove()
                }
            }
            backlog.clear()
            audioCount = 0
        }
    }

    /** Everything waiting (the backlog first), or an empty list after [timeoutMs] without anything. */
    public fun takeAll(timeoutMs: Long): List<Frame> = takeBatch(timeoutMs, batchWindowMs = 0L, minAudioFrames = 1)

    /**
     * Waits up to [timeoutMs] for the first frame, then coalesces audio frames for up to
     * [batchWindowMs] until at least [minAudioFrames] are queued — unless backlog or an urgent
     * control frame is waiting, in which case it returns immediately.
     */
    public fun takeBatch(
        timeoutMs: Long,
        batchWindowMs: Long,
        minAudioFrames: Int,
    ): List<Frame> {
        lock.withLock {
            if (frames.isEmpty() && backlog.isEmpty()) ready.await(timeoutMs, TimeUnit.MILLISECONDS)
            if (frames.isEmpty() && backlog.isEmpty()) return emptyList()
            if (batchWindowMs > 0L && backlog.isEmpty() && urgentCount == 0 && audioCount < minAudioFrames) {
                var remainingNs = TimeUnit.MILLISECONDS.toNanos(batchWindowMs)
                while (remainingNs > 0L && backlog.isEmpty() && urgentCount == 0 && audioCount < minAudioFrames) {
                    remainingNs = ready.awaitNanos(remainingNs)
                }
            }
            val all = ArrayList<Frame>(backlog.size + frames.size)
            all.addAll(backlog)
            for (entry in frames) all.add(entry.frame)
            backlog.clear()
            frames.clear()
            audioCount = 0
            urgentCount = 0
            return all
        }
    }

    private fun dropOldestAudio() {
        val it = frames.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (entry.frame.isAudio()) {
                if (entry.urgent) urgentCount--
                it.remove()
                audioCount--
                dropped++
                return
            }
        }
        audioCount = 0
    }

    private fun Frame.isAudio() = this is Frame.Audio || this is Frame.Adpcm

    private fun Frame.defaultUrgent(): Boolean = when (this) {
        is Frame.Audio, is Frame.Adpcm -> false
        Frame.Flush -> true
        is Frame.Message -> control.type != "status"
    }
}
