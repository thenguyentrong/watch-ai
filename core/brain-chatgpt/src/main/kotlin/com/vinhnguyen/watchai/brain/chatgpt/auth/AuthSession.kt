package com.vinhnguyen.watchai.brain.chatgpt.auth

import com.vinhnguyen.watchai.brain.BrainError
import com.vinhnguyen.watchai.brain.BrainException
import com.vinhnguyen.watchai.brain.BrainLogger
import com.vinhnguyen.watchai.brain.LogEvent
import com.vinhnguyen.watchai.brain.SecretStore
import com.vinhnguyen.watchai.brain.code
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Clock

public sealed interface AuthState {
    public data object Unknown : AuthState

    public data object SignedOut : AuthState

    public data class SignedIn(
        val emailMasked: String?,
        val planType: String?,
    ) : AuthState
}

/** What a request needs to authenticate. */
public data class Bearer(
    val accessToken: String,
    val accountId: String,
    val fedramp: Boolean,
) {
    override fun toString(): String = "Bearer(account=${accountId.take(8)}…)"
}

/**
 * Owns the ChatGPT tokens. Refresh tokens rotate and a reused one is fatal, so every refresh runs
 * under one mutex and the new tokens are saved (uncancellably) before anyone can use them.
 */
public class AuthSession(
    store: SecretStore,
    private val oauth: CodexOAuthClient,
    private val clock: Clock = Clock.systemUTC(),
    private val logger: BrainLogger = BrainLogger.None,
) {
    private val tokens = TokenStore(store)
    private val mutex = Mutex()
    private var cached: TokenSet? = null
    private val _state = MutableStateFlow<AuthState>(AuthState.Unknown)
    public val state: StateFlow<AuthState> = _state.asStateFlow()

    public suspend fun isSignedIn(): Boolean = mutex.withLock { load() != null }

    /** A valid bearer, refreshing first if it expires within five minutes. */
    public suspend fun bearer(): Bearer = mutex.withLock {
        val current = load() ?: throw BrainException(BrainError.NotSignedIn)
        val fresh = if (current.expiresAtEpochSeconds > now() + EARLY_REFRESH_SECONDS) current else refresh(current)
        fresh.toBearer()
    }

    /** Called after a 401. Refreshes once, unless another caller already did. */
    public suspend fun onUnauthorized(rejected: Bearer): Bearer = mutex.withLock {
        val current = load() ?: throw BrainException(BrainError.NotSignedIn)
        val fresh = if (current.accessToken != rejected.accessToken) current else refresh(current)
        fresh.toBearer()
    }

    /** Turns a token response from sign-in into stored tokens. */
    public suspend fun completeSignIn(response: TokenResponse): AuthState.SignedIn = mutex.withLock {
        val access = response.accessToken
        val refresh = response.refreshToken
        if (access.isNullOrEmpty() || refresh.isNullOrEmpty()) throw SignInException(SignInError.ExchangeFailed(null))
        val idClaims = response.idToken?.let(JwtClaims::parse)
        val accessClaims = JwtClaims.parse(access)
        val accountId = accessClaims?.accountId ?: idClaims?.accountId
        if (accountId == null ||
            (idClaims?.accountId != null && accessClaims?.accountId != null && idClaims.accountId != accessClaims.accountId)
        ) {
            throw SignInException(SignInError.AccountClaimMissing)
        }
        val set =
            TokenSet(
                accessToken = access,
                refreshToken = refresh,
                expiresAtEpochSeconds = expiry(accessClaims?.expiresAtEpochSeconds, response.expiresIn),
                accountId = accountId,
                planType = accessClaims?.planType ?: idClaims?.planType,
                emailMasked = maskEmail(idClaims?.email ?: accessClaims?.email),
                fedramp = accessClaims?.fedramp == true || idClaims?.fedramp == true,
                lastRefreshEpochSeconds = now(),
            )
        try {
            withContext(NonCancellable) { tokens.write(set) }
        } catch (e: Exception) {
            logger.log(LogEvent.SignIn("store_failed_${e.code}"))
            throw SignInException(SignInError.SecureStorageUnavailable, e)
        }
        cached = set
        logger.log(LogEvent.SignIn("completed"))
        AuthState.SignedIn(set.emailMasked, set.planType).also { _state.value = it }
    }

    /** Revokes (best effort) and forgets everything, even if revoking fails. */
    public suspend fun signOut(): Unit = withContext(NonCancellable) {
        mutex.withLock {
            val current = load()
            if (current != null) {
                val revoked = runCatching { oauth.revoke(current.refreshToken) }.getOrDefault(false)
                logger.log(LogEvent.SignIn(if (revoked) "revoked" else "revoke_failed"))
            }
            wipe("signed_out")
        }
    }

    private suspend fun load(): TokenSet? {
        cached?.let { return it }
        val stored =
            try {
                tokens.read()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keystore trouble: behave as signed out rather than crash; the user can sign in again.
                logger.log(LogEvent.SignIn("read_failed_${e.code}"))
                null
            }
        cached = stored
        _state.value = stored?.let { AuthState.SignedIn(it.emailMasked, it.planType) } ?: AuthState.SignedOut
        return stored
    }

    private suspend fun refresh(current: TokenSet): TokenSet = withContext(NonCancellable) {
        when (val result = oauth.refresh(current.refreshToken)) {
            is RefreshResult.Ok -> {
                val r = result.tokens
                val access = r.accessToken!!
                val claims = JwtClaims.parse(access)
                val accountId = claims?.accountId ?: current.accountId
                if (accountId != current.accountId) {
                    wipe("account_changed")
                    throw BrainException(BrainError.AuthExpired("account_changed"))
                }
                val next =
                    current.copy(
                        accessToken = access,
                        refreshToken = r.refreshToken ?: current.refreshToken,
                        expiresAtEpochSeconds = expiry(claims?.expiresAtEpochSeconds, r.expiresIn),
                        planType = claims?.planType ?: current.planType,
                        lastRefreshEpochSeconds = now(),
                    )
                tokens.write(next)
                cached = next
                logger.log(LogEvent.TokenRefresh("ok"))
                next
            }

            is RefreshResult.Permanent -> {
                logger.log(LogEvent.TokenRefresh("permanent_${result.reason}"))
                wipe(result.reason)
                throw BrainException(BrainError.AuthExpired(result.reason))
            }

            is RefreshResult.Transient -> {
                logger.log(LogEvent.TokenRefresh("transient_${result.status}"))
                throw BrainException(if (result.status == null) BrainError.Offline else BrainError.Unknown(result.status))
            }
        }
    }

    private suspend fun wipe(reason: String) {
        tokens.clear()
        cached = null
        _state.value = AuthState.SignedOut
        logger.log(LogEvent.SignIn("cleared_$reason"))
    }

    private fun expiry(
        jwtExp: Long?,
        expiresIn: Long?,
    ): Long {
        val fromResponse = expiresIn?.let { now() + it }
        return listOfNotNull(jwtExp, fromResponse).minOrNull() ?: (now() + DEFAULT_LIFETIME_SECONDS)
    }

    private fun now(): Long = clock.instant().epochSecond

    private fun TokenSet.toBearer() = Bearer(accessToken, accountId, fedramp)

    private companion object {
        const val EARLY_REFRESH_SECONDS = 300L
        const val DEFAULT_LIFETIME_SECONDS = 3_600L
    }
}
