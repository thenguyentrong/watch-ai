package com.vinhnguyen.watchai.brain

import kotlinx.coroutines.flow.Flow

public enum class BrainId(
    public val label: String,
    public val onDevice: Boolean,
) {
    CHATGPT("ChatGPT", onDevice = false),
    GEMINI_NANO("Gemini Nano on this phone", onDevice = true),
    GEMMA("Gemma on this phone", onDevice = true),
}

public interface Brain {
    public val id: BrainId

    public suspend fun availability(): Availability

    /** Cold flow: collecting starts the turn, cancelling the collector stops it. Ends with [ChatEvent.Done] or [ChatEvent.Failed]. */
    public fun stream(request: ChatRequest): Flow<ChatEvent>
}

/** A brain that loads a model can do it ahead of time, so the first answer doesn't wait for the load. */
public interface Preloadable {
    /** Loads the model now; it stays loaded until the returned handle is released. */
    public suspend fun preload(): Preloaded
}

public fun interface Preloaded {
    public suspend fun release()
}

/** How the answer will reach the user: read on a screen, or spoken aloud. */
public enum class ReplyStyle { TEXT, SPOKEN }

public data class ChatTurn(
    val role: Role,
    val text: String,
) {
    public enum class Role { USER, ASSISTANT }
}

public data class ChatRequest(
    val conversationId: String,
    val history: List<ChatTurn>,
    val userText: String,
    val maxOutputTokens: Int = DEFAULT_MAX_OUTPUT_TOKENS,
    val style: ReplyStyle = ReplyStyle.TEXT,
    /** Facts from the device the model can't know, e.g. the local time ([DeviceContext]). */
    val context: String? = null,
    /** Let a cloud brain look things up on the web. Brains without search ignore it. */
    val webSearch: Boolean = false,
    /** Actions the brain may take on the phone. Brains without tool use ignore it. */
    val tools: Toolbox? = null,
) {
    public companion object {
        public const val DEFAULT_MAX_OUTPUT_TOKENS: Int = 160
    }
}

public sealed interface ChatEvent {
    public data class Delta(
        val text: String,
    ) : ChatEvent

    public data class Done(
        val stats: TurnStats,
    ) : ChatEvent

    public data class Failed(
        val error: BrainError,
        val partialText: String = "",
    ) : ChatEvent
}

public data class TurnStats(
    val brain: BrainId,
    val model: String?,
    val backend: String?,
    val firstTokenMillis: Long?,
    val totalMillis: Long,
    val outputChars: Int,
)

public sealed interface Availability {
    public data object Ready : Availability

    public data object NeedsSignIn : Availability

    public data class NeedsDownload(
        val bytes: Long,
    ) : Availability

    /** [progress] is 0..1, or null when the size is unknown. */
    public data class Downloading(
        val progress: Float?,
    ) : Availability

    public data class Unavailable(
        val reason: UnavailableReason,
    ) : Availability
}

public enum class UnavailableReason {
    NOT_OPTED_IN,
    DEVICE_NOT_SUPPORTED,
    INSUFFICIENT_RAM,
    INSUFFICIENT_STORAGE,
    INTEGRITY_FAILED,
    DISABLED,
}
