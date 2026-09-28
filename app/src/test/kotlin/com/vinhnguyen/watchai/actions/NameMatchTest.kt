package com.vinhnguyen.watchai.actions

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NameMatchTest {
    private val people = listOf("Anna Schmidt", "Anna Weber", "Jan de Vries", "José García", "Nguyễn Minh", "Zoë", "Đặng Thu", "王伟")

    private fun find(query: String) = NameMatch.best(query, people) { it }

    @Test
    fun `a full name finds that one person`() {
        assertThat(find("anna schmidt")).containsExactly("Anna Schmidt")
        assertThat(find("Schmidt Anna")).containsExactly("Anna Schmidt")
    }

    @Test
    fun `a first name shared by two people returns both so the assistant can ask`() {
        assertThat(find("Anna")).containsExactly("Anna Schmidt", "Anna Weber")
    }

    @Test
    fun `accents and other marks don't have to be said`() {
        assertThat(find("jose garcia")).containsExactly("José García")
        assertThat(find("Nguyen")).containsExactly("Nguyễn Minh")
        assertThat(find("zoe")).containsExactly("Zoë")
        assertThat(find("dang")).containsExactly("Đặng Thu")
    }

    @Test
    fun `names in any script match as written`() {
        assertThat(find("王伟")).containsExactly("王伟")
    }

    @Test
    fun `the start of a name is enough, a better match wins`() {
        assertThat(find("Schm")).containsExactly("Anna Schmidt")
        assertThat(find("Jan")).containsExactly("Jan de Vries")
    }

    @Test
    fun `app names match with or without their space`() {
        val apps = listOf("WhatsApp", "WhatsApp Business", "Spotify", "Google Maps", "Maps")
        assertThat(NameMatch.best("whats app", apps) { it }).containsExactly("WhatsApp")
        assertThat(NameMatch.best("spotify", apps) { it }).containsExactly("Spotify")
        assertThat(NameMatch.best("maps", apps) { it }).containsExactly("Maps")
    }

    @Test
    fun `nothing close means nothing`() {
        assertThat(find("Bob")).isEmpty()
        assertThat(find("  ")).isEmpty()
    }
}
