package com.vinhnguyen.watchai.actions

import com.vinhnguyen.watchai.brain.SecretStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The user's notes, kept encrypted on the phone in their own vault (own Keystore key), so signing
 * out of ChatGPT never touches them. "Delete everything" does.
 */
class NoteStore(
    private val store: SecretStore,
    private val now: () -> Long = System::currentTimeMillis,
) {
    @Serializable
    data class Note(
        val id: Long,
        val text: String,
        val createdAt: Long,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(Note.serializer())
    private val mutex = Mutex()
    private var loaded = false
    private val _notes = MutableStateFlow<List<Note>>(emptyList())

    /** Newest last. */
    val notes: StateFlow<List<Note>> = _notes.asStateFlow()

    suspend fun list(): List<Note> = mutex.withLock { loadLocked() }

    suspend fun add(text: String): Note = mutex.withLock {
        val all = loadLocked()
        val note = Note(id = (all.maxOfOrNull { it.id } ?: 0) + 1, text = text, createdAt = now())
        saveLocked((all + note).takeLast(MAX))
        note
    }

    suspend fun delete(id: Long) = mutex.withLock {
        saveLocked(loadLocked().filterNot { it.id == id })
    }

    private suspend fun loadLocked(): List<Note> {
        if (!loaded) {
            _notes.value = store.read(NAME)?.let { runCatching { json.decodeFromString(serializer, it.decodeToString()) }.getOrNull() }.orEmpty()
            loaded = true
        }
        return _notes.value
    }

    private suspend fun saveLocked(notes: List<Note>) {
        store.write(NAME, json.encodeToString(serializer, notes).encodeToByteArray())
        _notes.value = notes
    }

    private companion object {
        const val NAME = "notes"
        const val MAX = 500
    }
}
