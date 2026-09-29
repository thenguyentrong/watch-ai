package com.vinhnguyen.watchai.actions

import com.vinhnguyen.watchai.brain.SecretStore
import com.vinhnguyen.watchai.brain.guard.ActionLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * What Buddy did in the last 30 days, encrypted on the phone in its own vault: which action and
 * how it ended, never what was in it (no message text, no names). Shown on "What Buddy did".
 */
class ActionLogStore(
    private val store: SecretStore,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) : ActionLog {
    @Serializable
    data class Item(
        val at: Long,
        val tool: String,
        val level: String,
        val outcome: String,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(Item.serializer())
    private val mutex = Mutex()
    private var loaded = false
    private val _items = MutableStateFlow<List<Item>>(emptyList())

    /** Oldest first. */
    val items: StateFlow<List<Item>> = _items.asStateFlow()

    override fun record(entry: ActionLog.Entry) {
        scope.launch {
            mutex.withLock {
                val kept = loadLocked().filter { it.at > now() - KEEP_MS }
                saveLocked((kept + Item(entry.at, entry.tool, entry.level.name, entry.outcome.name)).takeLast(MAX))
            }
        }
    }

    suspend fun load(): List<Item> = mutex.withLock { loadLocked() }

    suspend fun clear() = mutex.withLock { saveLocked(emptyList()) }

    private suspend fun loadLocked(): List<Item> {
        if (!loaded) {
            _items.value = store.read(NAME)?.let { runCatching { json.decodeFromString(serializer, it.decodeToString()) }.getOrNull() }.orEmpty()
            loaded = true
        }
        return _items.value
    }

    private suspend fun saveLocked(items: List<Item>) {
        store.write(NAME, json.encodeToString(serializer, items).encodeToByteArray())
        _items.value = items
    }

    private companion object {
        const val NAME = "actions"
        const val MAX = 1_000
        const val KEEP_MS = 30L * 24 * 60 * 60 * 1000
    }
}
