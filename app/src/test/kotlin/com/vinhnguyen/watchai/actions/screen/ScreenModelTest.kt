package com.vinhnguyen.watchai.actions.screen

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ScreenModelTest {
    // A chat list: a search button, a tab, two chats with names and message previews, a new-chat icon.
    private val chats =
        listOf(
            ScreenNode(1, "button", description = "Search", clickable = true),
            ScreenNode(2, "tab", text = "Chats", clickable = true),
            ScreenNode(3, "button", text = "Anna", clickable = true, inList = true),
            ScreenNode(4, "text", text = "See you at the station, bring the keys", inList = true),
            ScreenNode(5, "button", text = "Sam", clickable = true, inList = true),
            ScreenNode(6, "text", text = "Dinner tonight?", inList = true),
            ScreenNode(7, "button", viewId = "com.example.chat:id/new_chat", clickable = true),
            ScreenNode(8, "list", scrollable = true),
        )

    @Test
    fun `the planner sees controls, never names or messages`() {
        val seen = ScreenModel.controls("Chat", chats)
        assertThat(seen).contains("[1] button \"Search\"")
        assertThat(seen).contains("[2] tab \"Chats\"")
        assertThat(seen).contains("[3] item 1 in the list")
        assertThat(seen).contains("[5] item 2 in the list")
        assertThat(seen).contains("[7] button \"new chat\"")
        assertThat(seen).contains("[8] scrollable list")
        for (secret in listOf("Anna", "Sam", "station", "keys", "Dinner")) assertThat(seen).doesNotContain(secret)
    }

    @Test
    fun `a long text on a button isn't taken for a label`() {
        val node = ScreenNode(1, "button", text = "Your order from yesterday has been shipped and arrives on Friday", clickable = true)
        assertThat(ScreenModel.label(node)).isNull()
        assertThat(ScreenModel.controls("Mail", listOf(node))).doesNotContain("shipped")
    }

    @Test
    fun `the phone reads everything on the screen except password fields`() {
        val nodes = chats + ScreenNode(9, "field", text = "hunter2-SYNTHETIC", editable = true, password = true)
        val content = ScreenModel.content(nodes)
        assertThat(content).contains("See you at the station")
        assertThat(content).doesNotContain("hunter2")
        assertThat(ScreenModel.controls("Mail", nodes)).contains("[9] password field (Buddy never touches it)")
    }

    /** No word lists: the phone's own model judges the control's words, whatever language they're in. */
    @Test
    fun `before a tap the phone's model gets the control's own words and the app`() {
        val asked = ScreenModel.tapQuestion("Messenger", ScreenNode(1, "button", description = "Envoyer", viewId = "com.example.chat:id/send", clickable = true))
        assertThat(asked?.substringAfter("Now:")?.trim()).isEqualTo("In \"Messenger\", a button that says \"Envoyer\", named \"send\" by the app:")
        assertThat(ScreenModel.tapQuestion("Messenger", chats[2])?.substringAfter("Now:")).contains("an entry in a list")
    }

    @Test
    fun `an icon with no words at all can't be judged`() {
        assertThat(ScreenModel.tapQuestion("Chat", ScreenNode(1, "button", clickable = true))).isNull()
    }
}
