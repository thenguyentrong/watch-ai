package com.vinhnguyen.watchai.ui

import com.vinhnguyen.watchai.brain.Availability
import com.vinhnguyen.watchai.brain.BrainError
import com.vinhnguyen.watchai.brain.UnavailableReason
import com.vinhnguyen.watchai.brain.chatgpt.auth.SignInError
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** User-facing wording. Built from error codes only; provider text is never shown. */
object Texts {
    private val time = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

    fun error(error: BrainError): String = when (error) {
        BrainError.NotSignedIn -> {
            "Sign in to ChatGPT first (Brains tab)."
        }

        is BrainError.AuthExpired -> {
            "Your ChatGPT sign-in ended. Please sign in again."
        }

        is BrainError.RateLimited -> {
            error.retryAfterMillis?.let { "ChatGPT is busy. Try again in ${(it / 1000).coerceAtLeast(1)} s." }
                ?: "ChatGPT is busy. Try again in a moment."
        }

        is BrainError.UsageLimitReached -> {
            if (error.notIncluded) {
                "Your ChatGPT plan doesn't include this."
            } else {
                error.resetsAtEpochSeconds?.let {
                    "You've used your ChatGPT allowance for now. It resets at ${time.format(
                        Instant.ofEpochSecond(it),
                    )}."
                }
                    ?: "You've used your ChatGPT allowance for now."
            }
        }

        BrainError.Offline -> {
            "No connection. Check your internet and try again."
        }

        is BrainError.ProviderChanged -> {
            "ChatGPT changed something on their side. This needs an app update."
        }

        BrainError.ModelNotReady -> {
            "No model is ready yet. Download the on-device model or sign in to ChatGPT in the Brains tab."
        }

        BrainError.BackgroundBlocked -> {
            "Gemini Nano only answers while the app is open."
        }

        BrainError.Busy -> {
            "The on-device model is busy. Try again."
        }

        BrainError.QuotaExceeded -> {
            "The daily on-device limit is reached. Try again tomorrow."
        }

        is BrainError.BackendFailed -> {
            "The on-device model failed. Try again."
        }

        BrainError.OutOfMemory -> {
            "Not enough memory for the on-device model. Close other apps and try again."
        }

        is BrainError.Refused -> {
            "The model declined to answer that."
        }

        is BrainError.Unknown -> {
            "Something went wrong (${error.code ?: error.status ?: "unknown"})."
        }
    }

    fun signIn(error: SignInError): String = when (error) {
        SignInError.Cancelled -> {
            "Sign-in cancelled."
        }

        SignInError.TimedOut -> {
            "Sign-in timed out. Try again."
        }

        SignInError.PortsBusy -> {
            "Another app is using the sign-in port. Use a code instead."
        }

        SignInError.TooManyBadCallbacks -> {
            "Sign-in was interrupted by unexpected requests. Try again."
        }

        SignInError.MissingEntitlement -> {
            "This ChatGPT workspace doesn't include Codex, so its plan can't be used here."
        }

        is SignInError.ProviderRefused -> {
            "OpenAI didn't complete the sign-in. Try again."
        }

        is SignInError.ExchangeFailed -> {
            "Sign-in couldn't be completed. Try again."
        }

        SignInError.AccountClaimMissing -> {
            "This account can't be used here."
        }

        SignInError.Offline -> {
            "No connection. Check your internet and try again."
        }

        SignInError.DeviceCodeDisabled -> {
            "Code sign-in is off for your account. Turn on \"device code login\" in ChatGPT → Settings → Security, or use the browser."
        }

        SignInError.DeviceCodeExpired -> {
            "The code expired. Start again."
        }

        SignInError.SecureStorageUnavailable -> {
            "This phone can't store the sign-in securely, so it wasn't saved."
        }
    }

    fun availability(availability: Availability): String = when (availability) {
        Availability.Ready -> {
            "Ready"
        }

        Availability.NeedsSignIn -> {
            "Not signed in"
        }

        is Availability.NeedsDownload -> {
            "Not downloaded"
        }

        is Availability.Downloading -> {
            availability.progress?.let { "Downloading ${(it * 100).toInt()}%" } ?: "Downloading…"
        }

        is Availability.Unavailable -> {
            when (availability.reason) {
                UnavailableReason.NOT_OPTED_IN -> "Off"
                UnavailableReason.DEVICE_NOT_SUPPORTED -> "Not supported on this phone"
                UnavailableReason.INSUFFICIENT_RAM -> "Not enough memory on this phone"
                UnavailableReason.INSUFFICIENT_STORAGE -> "Not enough storage"
                UnavailableReason.INTEGRITY_FAILED -> "Model file failed its check"
                UnavailableReason.DISABLED -> "Only while the app is open"
            }
        }
    }

    fun gb(bytes: Long): String = "%.1f GB".format(bytes / 1_000_000_000.0)
}
