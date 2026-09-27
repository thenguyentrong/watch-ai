package com.vinhnguyen.watchai.brain.chatgpt.auth

import com.google.common.truth.Truth.assertThat
import com.vinhnguyen.watchai.testing.Synthetic
import org.junit.Test

class JwtClaimsTest {
    @Test
    fun `reads account, plan, email and expiry`() {
        val claims = JwtClaims.parse(Synthetic.jwt(expEpochSeconds = 1_900_000_000))!!
        assertThat(claims.accountId).isEqualTo(Synthetic.ACCOUNT_ID)
        assertThat(claims.planType).isEqualTo("plus")
        assertThat(claims.email).isEqualTo(Synthetic.EMAIL)
        assertThat(claims.expiresAtEpochSeconds).isEqualTo(1_900_000_000)
        assertThat(claims.fedramp).isFalse()
    }

    @Test
    fun `fedramp flag is read`() {
        val jwt = Synthetic.jwt(1, extraAuthClaims = ""","chatgpt_account_is_fedramp":true""")
        assertThat(JwtClaims.parse(jwt)!!.fedramp).isTrue()
    }

    @Test
    fun `malformed tokens give null instead of throwing`() {
        listOf("", "abc", "a.b", "a.b.c.d", "x.!!!.y", "x.${Synthetic.b64("not json")}.y", "x..y").forEach {
            assertThat(JwtClaims.parse(it)).isNull()
        }
    }

    @Test
    fun `missing claims are just null`() {
        val jwt = "x.${Synthetic.b64("""{"sub":"SYNTHETIC"}""")}.y"
        val claims = JwtClaims.parse(jwt)!!
        assertThat(claims.accountId).isNull()
        assertThat(claims.expiresAtEpochSeconds).isNull()
    }

    @Test
    fun `emails are masked`() {
        assertThat(maskEmail("tester@example.invalid")).isEqualTo("t***@example.invalid")
        assertThat(maskEmail("@x")).isEqualTo("***")
        assertThat(maskEmail(null)).isNull()
    }
}
