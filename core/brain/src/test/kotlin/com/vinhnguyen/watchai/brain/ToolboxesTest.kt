package com.vinhnguyen.watchai.brain

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

class ToolboxesTest {
    private class Fake(
        private val names: List<String>,
    ) : Toolbox {
        val calls = mutableListOf<String>()

        override fun tools() = names.map { ToolSpec(it, "does $it", """{"type":"object"}""") }

        override suspend fun run(
            name: String,
            argumentsJson: String,
        ): String {
            calls += name
            return "ok: $name"
        }
    }

    @Test
    fun `offers every part's tools and runs each call in the part that has it`() = runBlocking {
        val actions = Fake(listOf("add_note", "set_timer"))
        val buddy = Fake(listOf("dress_buddy"))
        val seen = mutableListOf<Pair<String, String>>()
        val all = Toolboxes(listOf(actions, buddy)) { name, result -> seen += name to result }

        assertThat(all.tools().map { it.name }).containsExactly("add_note", "set_timer", "dress_buddy").inOrder()
        assertThat(all.run("dress_buddy", "{}")).isEqualTo("ok: dress_buddy")
        assertThat(all.run("set_timer", "{}")).isEqualTo("ok: set_timer")
        assertThat(buddy.calls).containsExactly("dress_buddy")
        assertThat(actions.calls).containsExactly("set_timer")
        assertThat(seen).containsExactly("dress_buddy" to "ok: dress_buddy", "set_timer" to "ok: set_timer").inOrder()
    }

    @Test
    fun `an unknown tool is an error, not a crash`() = runBlocking {
        val all = Toolboxes(listOf(Fake(listOf("add_note"))))
        assertThat(all.run("delete_everything", "{}")).startsWith("error:")
    }
}
