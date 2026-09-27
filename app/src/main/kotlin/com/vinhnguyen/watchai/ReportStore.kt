package com.vinhnguyen.watchai

import com.vinhnguyen.watchai.brain.SecretStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * "Flag this answer" reports, kept encrypted on the phone. How they reach us is decided before the
 * Play release; the prompt is only included if the user ticks the box.
 */
class ReportStore(
    private val store: SecretStore,
) {
    @Serializable
    data class Report(
        val atEpochMillis: Long,
        val brain: String,
        val model: String?,
        val answer: String,
        val prompt: String?,
        val reason: String,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(Report.serializer())

    suspend fun add(report: Report) {
        val all = list() + report
        store.write(NAME, json.encodeToString(serializer, all.takeLast(MAX)).encodeToByteArray())
    }

    suspend fun list(): List<Report> = store.read(NAME)?.let { runCatching { json.decodeFromString(serializer, it.decodeToString()) }.getOrNull() } ?: emptyList()

    private companion object {
        const val NAME = "reports"
        const val MAX = 50
    }
}
