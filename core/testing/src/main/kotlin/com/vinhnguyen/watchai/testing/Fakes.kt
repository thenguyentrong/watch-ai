package com.vinhnguyen.watchai.testing

import com.vinhnguyen.watchai.brain.Availability
import com.vinhnguyen.watchai.brain.Brain
import com.vinhnguyen.watchai.brain.BrainId
import com.vinhnguyen.watchai.brain.ChatEvent
import com.vinhnguyen.watchai.brain.ChatRequest
import com.vinhnguyen.watchai.brain.SecretStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** A brain that replays a fixed script of events. */
class FakeBrain(
    override val id: BrainId,
    var availabilityValue: Availability = Availability.Ready,
    var script: List<ChatEvent> = emptyList(),
) : Brain {
    val calls = AtomicInteger()
    val requests = mutableListOf<ChatRequest>()

    override suspend fun availability(): Availability = availabilityValue

    override fun stream(request: ChatRequest): Flow<ChatEvent> = flow {
        calls.incrementAndGet()
        requests += request
        script.forEach { emit(it) }
    }
}

class InMemorySecretStore : SecretStore {
    private val map = ConcurrentHashMap<String, ByteArray>()
    val writes = AtomicInteger()

    override suspend fun read(name: String): ByteArray? = map[name]?.copyOf()

    override suspend fun write(
        name: String,
        value: ByteArray,
    ) {
        writes.incrementAndGet()
        map[name] = value.copyOf()
    }

    override suspend fun delete(name: String) {
        map.remove(name)
    }

    override suspend fun wipe() {
        map.clear()
    }

    fun names(): Set<String> = map.keys.toSet()
}
