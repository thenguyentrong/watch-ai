package com.vinhnguyen.watchai.brain.chatgpt.auth

import com.vinhnguyen.watchai.brain.BrainLogger
import com.vinhnguyen.watchai.brain.LogEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import java.time.Clock

/** Opens a URL in a browser tab the user can see (Custom Tab on Android). */
public fun interface BrowserLauncher {
    public fun open(url: String)
}

public sealed interface DeviceSignInState {
    public data class ShowCode(
        val userCode: String,
        val verificationUrl: String,
        val expiresAtEpochSeconds: Long,
    ) : DeviceSignInState

    public data class Done(
        val state: AuthState.SignedIn,
    ) : DeviceSignInState
}

public class ChatGptSignIn(
    private val oauth: CodexOAuthClient,
    private val session: AuthSession,
    private val endpoints: OpenAiEndpoints = OpenAiEndpoints(),
    private val clock: Clock = Clock.systemUTC(),
    private val logger: BrainLogger = BrainLogger.None,
) {
    /**
     * Browser sign-in: bind the loopback port, open OpenAI's page, wait for the redirect, exchange
     * the code once. Throws [SignInException]; [SignInError.PortsBusy] means "offer device code".
     */
    public suspend fun withBrowser(
        launcher: BrowserLauncher,
        timeoutMillis: Long = 10 * 60_000L,
    ): AuthState.SignedIn {
        val receiver = LoopbackReceiver.bind() ?: throw SignInException(SignInError.PortsBusy)
        receiver.use {
            val pkce = Pkce.generate()
            val state = newState()
            logger.log(LogEvent.SignIn("browser_opened"))
            launcher.open(authorizeUrl(endpoints, receiver.redirectUri, pkce, state))
            val callback =
                try {
                    withTimeout(timeoutMillis) { receiver.await(state) }
                } catch (_: TimeoutCancellationException) {
                    throw SignInException(SignInError.TimedOut)
                }
            return when (callback) {
                is CallbackResult.ProviderError -> {
                    logger.log(LogEvent.SignIn("provider_error"))
                    throw SignInException(
                        if (callback.missingEntitlement) SignInError.MissingEntitlement else SignInError.ProviderRefused(callback.error),
                    )
                }

                is CallbackResult.Code -> {
                    val tokens = oauth.exchangeCode(callback.code, pkce.verifier, receiver.redirectUri)
                    session.completeSignIn(tokens)
                }
            }
        }
    }

    /** Device-code sign-in: show a code, the user enters it on OpenAI's page, we poll until done. */
    public fun withDeviceCode(): Flow<DeviceSignInState> = flow {
        val code = oauth.requestDeviceCode()
        val expiresAt = clock.instant().epochSecond + DEVICE_CODE_LIFETIME_SECONDS
        emit(DeviceSignInState.ShowCode(code.userCode, code.verificationUrl, expiresAt))
        var interval = code.intervalSeconds
        while (true) {
            if (clock.instant().epochSecond >= expiresAt) throw SignInException(SignInError.DeviceCodeExpired)
            delay(interval * 1_000)
            when (val poll = oauth.pollDeviceCode(code)) {
                is DevicePoll.Pending -> {
                    if (poll.slowDown) interval = (interval + 5).coerceAtMost(60)
                }

                is DevicePoll.Authorized -> {
                    val tokens = oauth.exchangeCode(poll.authorizationCode, poll.codeVerifier, oauth.deviceRedirectUri)
                    emit(DeviceSignInState.Done(session.completeSignIn(tokens)))
                    return@flow
                }
            }
        }
    }

    public companion object {
        public const val DEVICE_CODE_LIFETIME_SECONDS: Long = 15 * 60
    }
}

/** True for errors that mean "the user backed out", which the UI shows quietly. */
public val Throwable.isSignInCancel: Boolean
    get() = this is CancellationException || (this is SignInException && error == SignInError.Cancelled)
