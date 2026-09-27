package com.vinhnguyen.watchai.brain.chatgpt.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.coroutines.executeAsync
import java.io.IOException

public sealed interface SignInError {
    public data object Cancelled : SignInError

    public data object TimedOut : SignInError

    public data object PortsBusy : SignInError

    public data object TooManyBadCallbacks : SignInError

    /** The ChatGPT workspace doesn't include Codex, so its plan can't be used here. */
    public data object MissingEntitlement : SignInError

    public data class ProviderRefused(
        val error: String,
    ) : SignInError

    public data class ExchangeFailed(
        val status: Int?,
    ) : SignInError

    public data object AccountClaimMissing : SignInError

    public data object Offline : SignInError

    public data object DeviceCodeDisabled : SignInError

    public data object DeviceCodeExpired : SignInError

    /** The phone couldn't store the sign-in in hardware-backed encrypted storage. */
    public data object SecureStorageUnavailable : SignInError
}

public class SignInException(
    public val error: SignInError,
    cause: Throwable? = null,
) : Exception(error::class.simpleName, cause)

@Serializable
public data class TokenResponse(
    @SerialName("id_token") val idToken: String? = null,
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
)

public sealed interface RefreshResult {
    public data class Ok(
        val tokens: TokenResponse,
    ) : RefreshResult

    /** The refresh token is dead: sign in again. */
    public data class Permanent(
        val reason: String,
    ) : RefreshResult

    public data class Transient(
        val status: Int?,
    ) : RefreshResult
}

public data class DeviceCode(
    val deviceAuthId: String,
    val userCode: String,
    val intervalSeconds: Long,
    val verificationUrl: String,
)

public sealed interface DevicePoll {
    public data class Pending(
        val slowDown: Boolean,
    ) : DevicePoll

    public data class Authorized(
        val authorizationCode: String,
        val codeVerifier: String,
    ) : DevicePoll
}

/**
 * Talks to auth.openai.com. The client passed in must not follow redirects or retry on its own:
 * an authorization code is single-use, so a retried or redirected exchange could burn it.
 */
public class CodexOAuthClient(
    private val http: OkHttpClient,
    private val endpoints: OpenAiEndpoints = OpenAiEndpoints(),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonType = "application/json".toMediaType()

    public suspend fun exchangeCode(
        code: String,
        codeVerifier: String,
        redirectUri: String,
    ): TokenResponse {
        val body =
            FormBody
                .Builder()
                .add("grant_type", "authorization_code")
                .add("client_id", OpenAiAuth.CLIENT_ID)
                .add("code", code)
                .add("redirect_uri", redirectUri)
                .add("code_verifier", codeVerifier)
                .build()
        val response =
            call(
                Request
                    .Builder()
                    .url(endpoints.token)
                    .post(body)
                    .build(),
            ) ?: throw SignInException(SignInError.Offline)
        response.use {
            if (!it.isSuccessful) throw SignInException(SignInError.ExchangeFailed(it.code))
            return parseTokens(it) ?: throw SignInException(SignInError.ExchangeFailed(it.code))
        }
    }

    public suspend fun refresh(refreshToken: String): RefreshResult {
        val body =
            buildJsonObject {
                put("client_id", OpenAiAuth.CLIENT_ID)
                put("grant_type", "refresh_token")
                put("refresh_token", refreshToken)
            }
        val response =
            call(
                Request
                    .Builder()
                    .url(endpoints.token)
                    .post(body.toString().toRequestBody(jsonType))
                    .build(),
            )
        response ?: return RefreshResult.Transient(null)
        response.use {
            if (it.isSuccessful) {
                val tokens = parseTokens(it) ?: return RefreshResult.Transient(it.code)
                return if (tokens.accessToken.isNullOrEmpty()) RefreshResult.Transient(it.code) else RefreshResult.Ok(tokens)
            }
            val code = errorCode(it.body.string())
            return when {
                it.code == 401 -> RefreshResult.Permanent(code ?: "unauthorized")
                code in PERMANENT_REFRESH_ERRORS -> RefreshResult.Permanent(code!!)
                else -> RefreshResult.Transient(it.code)
            }
        }
    }

    /** Best effort: local sign-out happens whether or not this reaches OpenAI. */
    public suspend fun revoke(refreshToken: String): Boolean {
        val body =
            buildJsonObject {
                put("token", refreshToken)
                put("token_type_hint", "refresh_token")
                put("client_id", OpenAiAuth.CLIENT_ID)
            }
        return call(
            Request
                .Builder()
                .url(endpoints.revoke)
                .post(body.toString().toRequestBody(jsonType))
                .build(),
        )?.use { it.isSuccessful } ?: false
    }

    public suspend fun requestDeviceCode(): DeviceCode {
        val body = buildJsonObject { put("client_id", OpenAiAuth.CLIENT_ID) }
        val response =
            call(
                Request
                    .Builder()
                    .url(endpoints.deviceUserCode)
                    .post(body.toString().toRequestBody(jsonType))
                    .build(),
            )
                ?: throw SignInException(SignInError.Offline)
        response.use {
            if (it.code == 404) throw SignInException(SignInError.DeviceCodeDisabled)
            if (!it.isSuccessful) throw SignInException(SignInError.ExchangeFailed(it.code))
            val obj = runCatching { json.parseToJsonElement(it.body.string()).jsonObject }.getOrNull()
            val id = obj.str("device_auth_id")
            val userCode = obj.str("user_code") ?: obj.str("usercode")
            if (id == null || userCode == null) throw SignInException(SignInError.ExchangeFailed(it.code))
            val interval = obj.str("interval")?.toLongOrNull()?.coerceIn(1, 60) ?: 5
            return DeviceCode(id, userCode, interval, endpoints.deviceVerificationPage)
        }
    }

    public suspend fun pollDeviceCode(deviceCode: DeviceCode): DevicePoll {
        val body =
            buildJsonObject {
                put("device_auth_id", deviceCode.deviceAuthId)
                put("user_code", deviceCode.userCode)
            }
        val response =
            call(
                Request
                    .Builder()
                    .url(endpoints.deviceToken)
                    .post(body.toString().toRequestBody(jsonType))
                    .build(),
            )
                ?: return DevicePoll.Pending(slowDown = false)
        response.use {
            val text = it.body.string()
            if (it.code == 403 || it.code == 404) return DevicePoll.Pending(slowDown = false)
            val code = errorCode(text)
            if (code == "slow_down") return DevicePoll.Pending(slowDown = true)
            if (code == "deviceauth_authorization_pending" || code == "authorization_pending") return DevicePoll.Pending(false)
            if (code == "expired_token" || code == "deviceauth_expired") throw SignInException(SignInError.DeviceCodeExpired)
            if (!it.isSuccessful) throw SignInException(SignInError.ExchangeFailed(it.code))
            val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            val authCode = obj.str("authorization_code")
            val verifier = obj.str("code_verifier")
            if (authCode == null || verifier == null) throw SignInException(SignInError.ExchangeFailed(it.code))
            return DevicePoll.Authorized(authCode, verifier)
        }
    }

    public val deviceRedirectUri: String get() = endpoints.deviceRedirect

    private suspend fun call(request: Request): Response? = try {
        http.newCall(request).executeAsync()
    } catch (_: IOException) {
        null
    }

    private fun parseTokens(response: Response): TokenResponse? = runCatching { json.decodeFromString(TokenResponse.serializer(), response.body.string()) }.getOrNull()

    /** Handles both `{"error":"invalid_grant"}` and `{"error":{"code":"refresh_token_expired"}}`. */
    private fun errorCode(text: String): String? {
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        return when (val error = obj["error"]) {
            is JsonPrimitive -> error.contentOrNull
            is JsonObject -> error.str("code") ?: error.str("type")
            else -> obj.str("code")
        }
    }

    private fun JsonObject?.str(key: String): String? = (this?.get(key) as? JsonPrimitive)?.contentOrNull

    private companion object {
        val PERMANENT_REFRESH_ERRORS =
            setOf("invalid_grant", "refresh_token_expired", "refresh_token_reused", "refresh_token_invalidated")
    }
}
