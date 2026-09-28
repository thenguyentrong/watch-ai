package com.vinhnguyen.watchai.watchlink

import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Frames waiting to go over the link. Producers never block; the writer takes everything waiting
 * in one go and sends it with a single flush. If the link falls behind, the oldest audio is dropped
 * ([maxAudio]) so the delay can't keep growing; control frames are never dropped.
 */
public class Outbox(
    private val maxAudio: Int,
) {
    private val lock = ReentrantLock()
    private val ready = lock.newCondition()
    private val frames = ArrayDeque<Frame>()
    private val backlog = ArrayDeque<Frame>()
    private var audioCount = 0

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

    public fun offer(frame: Frame) {
        lock.withLock {
            if (frame.isAudio()) {
                while (audioCount >= maxAudio) dropOldestAudio()
                audioCount++
            }
            frames.addLast(frame)
            ready.signal()
        }
    }

    /** Drops queued audio (the user interrupted the answer); control frames stay. */
    public fun clearAudio() {
        lock.withLock {
            frames.removeIf { it.isAudio() }
            backlog.clear()
            audioCount = 0
        }
    }

    /** Everything waiting (the backlog first), or an empty list after [timeoutMs] without anything. */
    public fun takeAll(timeoutMs: Long): List<Frame> {
        lock.withLock {
            if (frames.isEmpty() && backlog.isEmpty()) ready.await(timeoutMs, TimeUnit.MILLISECONDS)
            val all = backlog.toList() + frames.toList()
            backlog.clear()
            frames.clear()
            audioCount = 0
            return all
        }
    }

    private fun dropOldestAudio() {
        val it = frames.iterator()
        while (it.hasNext()) {
            if (it.next().isAudio()) {
                it.remove()
                audioCount--
                dropped++
                return
            }
        }
        audioCount = 0
    }

    private fun Frame.isAudio() = this is Frame.Audio || this is Frame.Adpcm
}
