package com.vinhnguyen.watchai.brain

/** Everything a brain can fail with. Messages for users are built from these, never from provider text. */
public sealed interface BrainError {
    public val retryable: Boolean

    public data object NotSignedIn : BrainError {
        override val retryable: Boolean = false
    }

    public data class AuthExpired(
        val reason: String,
    ) : BrainError {
        override val retryable: Boolean = false
    }

    public data class RateLimited(
        val retryAfterMillis: Long?,
    ) : BrainError {
        override val retryable: Boolean = true
    }

    public data class UsageLimitReached(
        val resetsAtEpochSeconds: Long?,
        val notIncluded: Boolean,
    ) : BrainError {
        override val retryable: Boolean = false
    }

    public data object Offline : BrainError {
        override val retryable: Boolean = true
    }

    /** The provider answered in a way this app version doesn't understand. Needs an app update. */
    public data class ProviderChanged(
        val kind: String,
        val status: Int? = null,
        val requestId: String? = null,
    ) : BrainError {
        override val retryable: Boolean = false
    }

    public data object ModelNotReady : BrainError {
        override val retryable: Boolean = false
    }

    public data object BackgroundBlocked : BrainError {
        override val retryable: Boolean = true
    }

    public data object Busy : BrainError {
        override val retryable: Boolean = true
    }

    public data object QuotaExceeded : BrainError {
        override val retryable: Boolean = false
    }

    public data class BackendFailed(
        val backend: String,
    ) : BrainError {
        override val retryable: Boolean = true
    }

    public data object OutOfMemory : BrainError {
        override val retryable: Boolean = false
    }

    public data class Refused(
        val code: String,
    ) : BrainError {
        override val retryable: Boolean = false
    }

    public data class Unknown(
        val status: Int? = null,
        val code: String? = null,
        val requestId: String? = null,
    ) : BrainError {
        override val retryable: Boolean = false
    }
}

/** Thrown inside brain implementations; turned into [ChatEvent.Failed] at the edge. */
public class BrainException(
    public val error: BrainError,
    cause: Throwable? = null,
) : Exception(error::class.simpleName, cause)
