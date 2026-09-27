package com.vinhnguyen.watchai.brain.chatgpt.auth

import com.google.common.truth.Truth.assertThat
import com.vinhnguyen.watchai.brain.BrainError
import com.vinhnguyen.watchai.brain.BrainException
import com.vinhnguyen.watchai.brain.chatgpt.TestClock
import com.vinhnguyen.watchai.brain.chatgpt.endpoints
import com.vinhnguyen.watchai.brain.chatgpt.json
import com.vinhnguyen.watchai.brain.chatgpt.testAuthClient
import com.vinhnguyen.watchai.testing.InMemorySecretStore
import com.vinhnguyen.watchai.testing.Synthetic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class AuthSessionTest {
    private lateinit var server: MockWebServer
    private val clock = TestClock()
    private val store = InMemorySecretStore()
    private lateinit var session: AuthSession

    private val now get() = clock.now.epochSecond

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        session = AuthSession(store, CodexOAuthClient(testAuthClient(), server.endpoints()), clock)
    }

    @After
    fun tearDown() = server.close()

    private fun tokenResponse(
        exp: Long,
        refresh: String = "SYNTHETIC_RT",
        account: String = Synthetic.ACCOUNT_ID,
    ) = TokenResponse(
        idToken = Synthetic.jwt(exp, accountId = account),
        accessToken = Synthetic.jwt(exp, accountId = account),
        refreshToken = refresh,
        expiresIn = exp - now,
    )

    private fun refreshBody(
        exp: Long,
        refresh: String,
        account: String = Synthetic.ACCOUNT_ID,
    ) = """{"access_token":"${Synthetic.jwt(exp, accountId = account)}","refresh_token":"$refresh","expires_in":${exp - now}}"""

    @Test
    fun `sign-in stores tokens and masks the email`(): Unit = runBlocking {
        val state = session.completeSignIn(tokenResponse(now + 864_000))
        assertThat(state.emailMasked).isEqualTo("t***@example.invalid")
        assertThat(state.planType).isEqualTo("plus")
        assertThat(store.names()).containsExactly("chatgpt_tokens")
        val stored = store.read("chatgpt_tokens")!!.decodeToString()
        assertThat(stored).doesNotContain("tester@example.invalid")
        assertThat(session.bearer().accountId).isEqualTo(Synthetic.ACCOUNT_ID)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `mismatched account ids are refused`(): Unit = runBlocking {
        val bad = tokenResponse(now + 1_000).copy(idToken = Synthetic.jwt(now + 1_000, accountId = Synthetic.OTHER_ACCOUNT_ID))
        val error = runCatching { session.completeSignIn(bad) }.exceptionOrNull() as SignInException
        assertThat(error.error).isEqualTo(SignInError.AccountClaimMissing)
        assertThat(store.names()).isEmpty()
    }

    @Test
    fun `fifty parallel requests with an expiring token cause exactly one refresh`(): Unit = runBlocking {
        session.completeSignIn(tokenResponse(now + 60)) // inside the 5-minute early-refresh window
        server.enqueue(
            MockResponse
                .Builder()
                .addHeader("Content-Type", "application/json")
                .body(refreshBody(now + 864_000, "SYNTHETIC_RT2"))
                .bodyDelay(200, TimeUnit.MILLISECONDS)
                .build(),
        )
        val bearers = withContext(Dispatchers.Default) { (1..50).map { async { session.bearer() } }.awaitAll() }
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(bearers.map { it.accessToken }.toSet()).hasSize(1)
        assertThat(store.read("chatgpt_tokens")!!.decodeToString()).contains("SYNTHETIC_RT2")
    }

    @Test
    fun `two parallel 401s refresh once`(): Unit = runBlocking {
        session.completeSignIn(tokenResponse(now + 864_000))
        val rejected = session.bearer()
        server.enqueue(json(refreshBody(now + 864_001, "SYNTHETIC_RT2")))
        val results =
            withContext(Dispatchers.Default) {
                listOf(async { session.onUnauthorized(rejected) }, async { session.onUnauthorized(rejected) }).awaitAll()
            }
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(results[0]).isEqualTo(results[1])
        assertThat(results[0].accessToken).isNotEqualTo(rejected.accessToken)
    }

    @Test
    fun `a dead refresh token wipes the session`(): Unit = runBlocking {
        session.completeSignIn(tokenResponse(now + 10))
        server.enqueue(json("""{"error":"refresh_token_expired"}""", 400))
        val error = runCatching { session.bearer() }.exceptionOrNull() as BrainException
        assertThat(error.error).isEqualTo(BrainError.AuthExpired("refresh_token_expired"))
        assertThat(store.names()).isEmpty()
        assertThat(session.state.value).isEqualTo(AuthState.SignedOut)
        assertThat((runCatching { session.bearer() }.exceptionOrNull() as BrainException).error).isEqualTo(BrainError.NotSignedIn)
    }

    @Test
    fun `a server hiccup during refresh keeps the tokens`(): Unit = runBlocking {
        session.completeSignIn(tokenResponse(now + 10))
        server.enqueue(json("{}", 503))
        val error = runCatching { session.bearer() }.exceptionOrNull() as BrainException
        assertThat(error.error).isEqualTo(BrainError.Unknown(503))
        assertThat(store.names()).containsExactly("chatgpt_tokens")
    }

    @Test
    fun `a refresh that switches account is rejected`(): Unit = runBlocking {
        session.completeSignIn(tokenResponse(now + 10))
        server.enqueue(json(refreshBody(now + 864_000, "SYNTHETIC_RT2", account = Synthetic.OTHER_ACCOUNT_ID)))
        val error = runCatching { session.bearer() }.exceptionOrNull() as BrainException
        assertThat(error.error).isEqualTo(BrainError.AuthExpired("account_changed"))
        assertThat(store.names()).isEmpty()
    }

    @Test
    fun `sign-out revokes and forgets even if revoking fails`(): Unit = runBlocking {
        session.completeSignIn(tokenResponse(now + 864_000))
        server.enqueue(json("{}", 500))
        session.signOut()
        assertThat(server.takeRequest().url.encodedPath).isEqualTo("/oauth/revoke")
        assertThat(store.names()).isEmpty()
        assertThat(session.state.value).isEqualTo(AuthState.SignedOut)
    }

    @Test
    fun `a cancelled caller still leaves the rotated tokens saved`(): Unit = runBlocking {
        session.completeSignIn(tokenResponse(now + 10))
        server.enqueue(
            MockResponse
                .Builder()
                .addHeader("Content-Type", "application/json")
                .body(refreshBody(now + 864_000, "SYNTHETIC_RT2"))
                .bodyDelay(300, TimeUnit.MILLISECONDS)
                .build(),
        )
        val caller = async(Dispatchers.Default) { session.bearer() }
        kotlinx.coroutines.delay(100)
        caller.cancel()
        // The refresh runs uncancellably; wait for it to land.
        repeat(50) {
            if (store.read("chatgpt_tokens")?.decodeToString()?.contains("SYNTHETIC_RT2") == true) return@runBlocking
            kotlinx.coroutines.delay(50)
        }
        error("rotated refresh token was not saved")
    }
}
