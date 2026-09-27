package com.vinhnguyen.watchai.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RedactorTest {
    private val jwt = "eyJhbGciOiJub25lIn0.eyJzdWIiOiJTWU5USEVUSUMifQ.SYNTHETIC"

    @Test
    fun `tokens and oauth parameters never survive`() {
        val line =
            "GET /auth/callback?code=SYNTHETIC_CODE_123&state=SYNTHETIC_STATE " +
                "Authorization: Bearer $jwt refresh_token=SYNTHETIC_RT {\"access_token\":\"SYNTHETIC_AT\"} " +
                "key sk-SYNTHETIC0123456789"
        val out = Redactor.redact(line)
        listOf("SYNTHETIC_CODE_123", "SYNTHETIC_STATE", "eyJ", "SYNTHETIC_RT", "SYNTHETIC_AT", "sk-SYNTHETIC").forEach {
            assertThat(out).doesNotContain(it)
        }
        assertThat(out).contains("code=[redacted]")
        assertThat(out).contains("Bearer [redacted]")
    }

    @Test
    fun `bearer values with padding are fully removed`() {
        assertThat(Redactor.redact("Bearer SYNTHETICabc==")).isEqualTo("Bearer [redacted]")
    }

    @Test
    fun `plain codes and numbers are left alone`() {
        val line = "TurnFinished(brain=GEMMA, model=gemma-4-e2b, totalMillis=812)"
        assertThat(Redactor.redact(line)).isEqualTo(line)
    }
}
