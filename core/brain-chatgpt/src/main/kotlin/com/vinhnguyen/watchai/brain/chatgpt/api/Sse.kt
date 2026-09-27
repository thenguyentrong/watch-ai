package com.vinhnguyen.watchai.brain.chatgpt.api

import okio.BufferedSource

internal data class SseEvent(
    val event: String?,
    val data: String,
)

/**
 * Minimal server-sent-events reader: `event:` and multi-line `data:` fields, comments ignored,
 * CRLF or LF line endings. Blocking - run it on an IO thread and cancel the HTTP call to stop it.
 */
internal class SseReader(
    private val source: BufferedSource,
) {
    /** Next event, or null when the stream ends. A trailing event without a blank line is dropped. */
    fun next(): SseEvent? {
        var event: String? = null
        val data = StringBuilder()
        var hasData = false
        while (true) {
            val line = source.readUtf8Line() ?: return null
            when {
                line.isEmpty() -> {
                    if (hasData) return SseEvent(event, data.toString())
                    event = null
                }

                line.startsWith(":") -> {
                    Unit
                }

                else -> {
                    val field = line.substringBefore(':')
                    val value = line.substringAfter(':', "").removePrefix(" ")
                    when (field) {
                        "event" -> {
                            event = value
                        }

                        "data" -> {
                            if (hasData) data.append('\n')
                            data.append(value)
                            hasData = true
                        }
                    }
                }
            }
        }
    }
}
