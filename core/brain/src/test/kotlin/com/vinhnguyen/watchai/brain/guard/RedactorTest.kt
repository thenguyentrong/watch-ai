package com.vinhnguyen.watchai.brain.guard

import com.google.common.truth.Truth.assertThat
import com.vinhnguyen.watchai.brain.guard.Redactor.Kind
import org.junit.Test

/** Synthetic texts only: made-up codes, test card numbers, the standard example IBAN. */
class RedactorTest {
    private fun clean(text: String) = Redactor.clean(text)

    @Test
    fun `one-time codes are hidden`() {
        assertThat(clean("G-482913 is your Google verification code.").text).isEqualTo("[code hidden] is your Google verification code.")
        assertThat(clean("Ihre TAN lautet 482913").text).isEqualTo("Ihre TAN lautet [code hidden]")
        assertThat(clean("Your login code: 482 913").text).isEqualTo("Your login code: [code hidden]")
        assertThat(clean("Use X7K9PQ to sign in").text).isEqualTo("Use [code hidden] to sign in")
        assertThat(clean("Your code is 4829. Don't share it.").text).isEqualTo("Your code is [code hidden]. Don't share it.")
    }

    @Test
    fun `numbers without code words stay`() {
        val text = "See you at 18:30 on 28.09.2026, table for 4, it was 2024 when we met, costs 12,50 €"
        assertThat(clean(text).text).isEqualTo(text)
    }

    @Test
    fun `with code words, dates times and prices still stay`() {
        val text = "Your booking code arrives on 28.09.2026 at 18:30, price 1250,00 €"
        assertThat(clean(text).text).isEqualTo(text)
    }

    @Test
    fun `card numbers are hidden only when they pass the check`() {
        assertThat(clean("my card 4111 1111 1111 1111 exp 12/29").text).isEqualTo("my card [card number hidden] exp 12/29")
        assertThat(clean("order 4111 1111 1111 1112").text).doesNotContain("card number hidden")
    }

    @Test
    fun `IBANs are hidden only when they pass the check`() {
        assertThat(clean("pay to DE89 3704 0044 0532 0130 00 please").text).isEqualTo("pay to [IBAN hidden] please")
        assertThat(clean("pay to DE89370400440532013000").text).isEqualTo("pay to [IBAN hidden]")
        assertThat(clean("ref DE00 1234 5678 9012 3456 78").hidden).doesNotContain(Kind.IBAN)
    }

    @Test
    fun `passwords after their word are hidden`() {
        assertThat(clean("wifi password: hunter2!").text).isEqualTo("wifi password: [hidden]")
        assertThat(clean("Das Passwort ist Sonne123").text).isEqualTo("Das Passwort ist [hidden]")
        assertThat(clean("PIN: 1234").text).isEqualTo("PIN: [hidden]")
        assertThat(clean("the pin is on the map").text).isEqualTo("the pin is on the map")
    }

    @Test
    fun `links keep only their site`() {
        assertThat(clean("Reset here: https://evil.example/reset?token=abc123XYZ.").text).isEqualTo("Reset here: [link to evil.example].")
        assertThat(clean("look at www.dhl.de/track/123").text).isEqualTo("look at [link to www.dhl.de]")
    }

    @Test
    fun `phone numbers and email addresses are hidden`() {
        assertThat(clean("call me on +49 151 23456789 or 0151 2345 6789").text).isEqualTo("call me on [phone number hidden] or [phone number hidden]")
        assertThat(clean("write to anna.test@example.com").text).isEqualTo("write to [email address hidden]")
        assertThat(clean("(555) 123-4567").text).isEqualTo("[phone number hidden]")
    }

    @Test
    fun `keys and tokens are hidden`() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U" // SYNTHETIC
        assertThat(clean("token $jwt").text).isEqualTo("token [key hidden]")
        assertThat(clean("key sk-proj-AbCdEfGhIjKlMnOpQrStUv123").text).isEqualTo("key [key hidden]") // SYNTHETIC
        assertThat(clean("ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789").text).isEqualTo("[key hidden]") // SYNTHETIC
        assertThat(clean("-----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBg\n-----END PRIVATE KEY-----").text).isEqualTo("[private key hidden]") // SYNTHETIC
        assertThat(clean("secret 9fQ2xL7pVb3KmZt8RwN4yH6cJd1sGa5E").text).isEqualTo("secret [key hidden]") // SYNTHETIC
    }

    @Test
    fun `ordinary words and long plain words stay`() {
        val text = "Anna: are we still on for dinner tonight? Bring the Donaudampfschifffahrtsgesellschaftskapitän"
        assertThat(clean(text).text).isEqualTo(text)
        assertThat(clean(text).hidden).isEmpty()
    }

    @Test
    fun `what was hidden is listed`() {
        val kinds = clean("code 482913, card 4111 1111 1111 1111, https://x.example/a").hidden
        assertThat(kinds).containsAtLeast(Kind.CODE, Kind.CARD, Kind.LINK)
    }
}
