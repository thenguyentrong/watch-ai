package com.vinhnguyen.watchai.brain

import com.google.common.truth.Truth.assertThat
import com.vinhnguyen.watchai.testing.FakeBrain
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test

class BrainRouterTest {
    private val request = ChatRequest("c1", emptyList(), "What is 15% of 80?")
    private val done = ChatEvent.Done(TurnStats(BrainId.GEMMA, "gemma", "gpu", 10, 20, 2))

    private val nano = FakeBrain(BrainId.GEMINI_NANO, Availability.Unavailable(UnavailableReason.DEVICE_NOT_SUPPORTED))
    private val gemma = FakeBrain(BrainId.GEMMA, script = listOf(ChatEvent.Delta("12"), done))
    private val chatgpt = FakeBrain(BrainId.CHATGPT, script = listOf(ChatEvent.Delta("12."), done))
    private val router = BrainRouter(listOf(nano, gemma, chatgpt))

    @Test
    fun `auto prefers on-device and skips an unsupported Nano`() = runTest {
        val route = router.route(RoutePreference.AUTO, cloudAllowed = true)
        assertThat((route as Route.Chosen).brain.id).isEqualTo(BrainId.GEMMA)
    }

    @Test
    fun `cloud is never used unless the user allows it`() = runTest {
        gemma.availabilityValue = Availability.NeedsDownload(1_000)
        val route = router.route(RoutePreference.AUTO, cloudAllowed = false)
        assertThat(route).isInstanceOf(Route.NoneReady::class.java)
        assertThat((route as Route.NoneReady).availability.keys)
            .containsExactly(BrainId.GEMINI_NANO, BrainId.GEMMA)
            .inOrder()
    }

    @Test
    fun `auto falls back to ChatGPT when allowed and nothing on-device is ready`() = runTest {
        gemma.availabilityValue = Availability.NeedsDownload(1_000)
        val events = router.stream(request, RoutePreference.AUTO, cloudAllowed = true).toList()
        assertThat(events.first()).isEqualTo(ChatEvent.Delta("12."))
        assertThat(chatgpt.calls.get()).isEqualTo(1)
    }

    @Test
    fun `a busy on-device brain hands over before it has said anything`() = runTest {
        nano.availabilityValue = Availability.Ready
        nano.script = listOf(ChatEvent.Failed(BrainError.Busy))
        val events = router.stream(request, RoutePreference.ON_DEVICE, cloudAllowed = false).toList()
        assertThat(events).containsExactly(ChatEvent.Delta("12"), done).inOrder()
        assertThat(gemma.calls.get()).isEqualTo(1)
    }

    @Test
    fun `no hand-over once the answer has started`() = runTest {
        nano.availabilityValue = Availability.Ready
        val failed = ChatEvent.Failed(BrainError.Busy, partialText = "Twel")
        nano.script = listOf(ChatEvent.Delta("Twel"), failed)
        val events = router.stream(request, RoutePreference.ON_DEVICE, cloudAllowed = false).toList()
        assertThat(events).containsExactly(ChatEvent.Delta("Twel"), failed).inOrder()
        assertThat(gemma.calls.get()).isEqualTo(0)
    }

    @Test
    fun `errors that are not about the device are passed through`() = runTest {
        val failed = ChatEvent.Failed(BrainError.UsageLimitReached(resetsAtEpochSeconds = 1L, notIncluded = false))
        chatgpt.script = listOf(failed)
        val events = router.stream(request, RoutePreference.CHATGPT, cloudAllowed = true).toList()
        assertThat(events).containsExactly(failed)
    }

    @Test
    fun `signed-out ChatGPT reports NotSignedIn`() = runTest {
        chatgpt.availabilityValue = Availability.NeedsSignIn
        val events = router.stream(request, RoutePreference.CHATGPT, cloudAllowed = true).toList()
        assertThat(events).containsExactly(ChatEvent.Failed(BrainError.NotSignedIn))
    }

    @Test
    fun `trim keeps only the most recent turns`() {
        val turns = (1..6).map { ChatTurn(ChatTurn.Role.USER, "t$it") }
        assertThat(PromptStyle.trim(turns).map { it.text }).containsExactly("t3", "t4", "t5", "t6").inOrder()
    }
}
