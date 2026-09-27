package com.vinhnguyen.watchai.testing

import java.util.Base64

/**
 * Synthetic test data only. Every token carries the SYNTHETIC marker so the build can reject
 * anything that looks like a real credential in test resources.
 */
object Synthetic {
    const val ACCOUNT_ID = "acct_SYNTHETIC_0001"
    const val OTHER_ACCOUNT_ID = "acct_SYNTHETIC_0002"
    const val EMAIL = "tester@example.invalid"

    private val encoder = Base64.getUrlEncoder().withoutPadding()

    /** An unsigned (alg=none) JWT with the claims the ChatGPT brain reads. */
    fun jwt(
        expEpochSeconds: Long,
        accountId: String = ACCOUNT_ID,
        planType: String = "plus",
        email: String? = EMAIL,
        extraAuthClaims: String = "",
    ): String {
        val header = """{"alg":"none","typ":"JWT","kid":"SYNTHETIC"}"""
        val emailClaim = email?.let { ""","email":"$it"""" } ?: ""
        val payload =
            """{"iss":"https://auth.openai.com","sub":"SYNTHETIC_user","exp":$expEpochSeconds$emailClaim,""" +
                """"https://api.openai.com/auth":{"chatgpt_account_id":"$accountId","chatgpt_plan_type":"$planType",""" +
                """"chatgpt_user_id":"user_SYNTHETIC"$extraAuthClaims}}"""
        return "${b64(header)}.${b64(payload)}.SYNTHETIC"
    }

    fun b64(s: String): String = encoder.encodeToString(s.toByteArray())
}
