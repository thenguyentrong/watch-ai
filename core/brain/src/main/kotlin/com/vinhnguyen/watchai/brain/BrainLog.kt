package com.vinhnguyen.watchai.brain

/**
 * The only way brains log. Events carry codes, counts and timings - never prompts, answers,
 * tokens, URLs or provider messages - so logs can't leak anything even if they're read.
 */
public sealed interface LogEvent {
    public data class TurnStarted(
        val brain: BrainId,
    ) : LogEvent

    public data class TurnFinished(
        val brain: BrainId,
        val model: String?,
        val backend: String?,
        val firstTokenMillis: Long?,
        val totalMillis: Long,
    ) : LogEvent

    public data class TurnFailed(
        val brain: BrainId,
        val error: String,
        val status: Int?,
    ) : LogEvent

    public data class SignIn(
        val step: String,
    ) : LogEvent

    public data class TokenRefresh(
        val outcome: String,
    ) : LogEvent

    public data class Download(
        val phase: String,
        val percent: Int? = null,
    ) : LogEvent

    public data class Engine(
        val phase: String,
        val backend: String?,
        val millis: Long? = null,
    ) : LogEvent

    /** A non-2xx answer from a provider: which model, which status, what we made of it. Codes only. */
    public data class ProviderResponse(
        val model: String,
        val status: Int,
        val kind: String,
    ) : LogEvent

    /** Debug builds only: a redacted snippet of a provider's error body, to diagnose contract changes. */
    public data class ProviderDiagnostic(
        val status: Int,
        val snippet: String,
    ) : LogEvent

    public data class VaultReset(
        val cause: String,
    ) : LogEvent

    /** A phone action a brain asked for: which one and how it went ("ok", "invalid", "denied", …). */
    public data class ToolUsed(
        val tool: String,
        val outcome: String,
    ) : LogEvent
}

public fun interface BrainLogger {
    public fun log(event: LogEvent)

    public companion object {
        public val None: BrainLogger = BrainLogger { }
    }
}

/** Short code for an error, e.g. "RateLimited". Safe to log. */
public val BrainError.code: String get() = this::class.simpleName ?: "BrainError"

/** Exception class name only; exception messages can contain user or provider text. */
public val Throwable.code: String get() = this::class.simpleName ?: "Throwable"
