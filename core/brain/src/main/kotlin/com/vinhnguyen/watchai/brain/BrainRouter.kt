package com.vinhnguyen.watchai.brain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

public enum class RoutePreference { AUTO, ON_DEVICE, CHATGPT }

public sealed interface Route {
    public data class Chosen(
        val brain: Brain,
    ) : Route

    public data class NoneReady(
        val availability: Map<BrainId, Availability>,
    ) : Route
}

/**
 * Picks a brain in a fixed order: Gemini Nano, then Gemma, then ChatGPT (only when the user allows
 * cloud answers). If an on-device brain fails before saying anything with a "try elsewhere" error,
 * the next ready brain answers instead.
 */
public class BrainRouter(
    brains: List<Brain>,
) {
    private val byId = brains.associateBy { it.id }

    public fun candidates(
        preference: RoutePreference,
        cloudAllowed: Boolean,
    ): List<BrainId> = when (preference) {
        RoutePreference.CHATGPT -> {
            listOf(BrainId.CHATGPT)
        }

        RoutePreference.ON_DEVICE -> {
            listOf(BrainId.GEMINI_NANO, BrainId.GEMMA)
        }

        RoutePreference.AUTO -> {
            listOf(BrainId.GEMINI_NANO, BrainId.GEMMA) + if (cloudAllowed) listOf(BrainId.CHATGPT) else emptyList()
        }
    }.filter { it in byId }

    public suspend fun route(
        preference: RoutePreference,
        cloudAllowed: Boolean,
    ): Route {
        val seen = LinkedHashMap<BrainId, Availability>()
        for (id in candidates(preference, cloudAllowed)) {
            val brain = byId.getValue(id)
            val availability = brain.safeAvailability()
            if (availability == Availability.Ready) return Route.Chosen(brain)
            seen[id] = availability
        }
        return Route.NoneReady(seen)
    }

    public fun stream(
        request: ChatRequest,
        preference: RoutePreference,
        cloudAllowed: Boolean,
    ): Flow<ChatEvent> = flow {
        var lastFailure: ChatEvent.Failed? = null
        for (id in candidates(preference, cloudAllowed)) {
            val brain = byId.getValue(id)
            val availability = brain.safeAvailability()
            if (availability != Availability.Ready) {
                if (lastFailure == null) lastFailure = ChatEvent.Failed(availability.toError())
                continue
            }
            var spoke = false
            var failure: ChatEvent.Failed? = null
            brain.stream(request).collect { event ->
                when {
                    event is ChatEvent.Failed && !spoke && event.error.isFallbackable() -> {
                        failure = event
                    }

                    else -> {
                        if (event is ChatEvent.Delta) spoke = true
                        emit(event)
                    }
                }
            }
            if (failure == null) return@flow
            lastFailure = failure
        }
        emit(lastFailure ?: ChatEvent.Failed(BrainError.ModelNotReady))
    }

    private suspend fun Brain.safeAvailability(): Availability = try {
        availability()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        Availability.Unavailable(UnavailableReason.DISABLED)
    }

    private fun Availability.toError(): BrainError = when (this) {
        Availability.NeedsSignIn -> BrainError.NotSignedIn
        else -> BrainError.ModelNotReady
    }

    private fun BrainError.isFallbackable(): Boolean = this is BrainError.Busy ||
        this is BrainError.QuotaExceeded ||
        this is BrainError.BackgroundBlocked ||
        this is BrainError.BackendFailed ||
        this is BrainError.OutOfMemory ||
        this is BrainError.ModelNotReady
}
