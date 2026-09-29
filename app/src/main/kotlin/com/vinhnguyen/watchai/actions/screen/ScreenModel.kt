package com.vinhnguyen.watchai.actions.screen

/**
 * One thing on the phone's screen, as Android's accessibility tree has it. [id] is Buddy's own
 * number for it in this look at the screen.
 */
data class ScreenNode(
    val id: Int,
    /** "button", "field", "tab", "item", "text", … */
    val kind: String,
    /** What's written on it. */
    val text: String = "",
    /** The app's own label for it (content description), e.g. "Send" on an icon. */
    val description: String = "",
    /** The hint in an empty field, e.g. "Search". */
    val hint: String = "",
    /** The app's name for the view, e.g. "com.whatsapp:id/send". */
    val viewId: String = "",
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val password: Boolean = false,
    /** Inside a list (chats, emails, search results): its words are content, not a control. */
    val inList: Boolean = false,
)

/**
 * The split between what ChatGPT (the planner) may see of a screen and what only the phone reads.
 *
 * ChatGPT decides the steps (open the app, tap Search, type the name, open the chat) but never gets
 * content: it sees buttons, tabs and fields by their short labels, list entries only as "item 3",
 * and nothing longer than a label. Everything on the screen (messages, emails, list entries) goes to
 * the phone's own model through `read_screen`, with ChatGPT's instruction as its task.
 */
object ScreenModel {
    /** What the planner sees: one line per control. */
    fun controls(
        app: String,
        nodes: List<ScreenNode>,
    ): String {
        var item = 0
        val lines =
            nodes.mapNotNull { node ->
                when {
                    node.password -> "[${node.id}] password field (Buddy never touches it)"
                    node.editable -> "[${node.id}] text field${label(node)?.let { " \"$it\"" } ?: ""}"
                    node.inList && node.clickable -> "[${node.id}] item ${++item} in the list"
                    node.clickable -> label(node)?.let { "[${node.id}] ${node.kind} \"$it\"" }
                    node.scrollable -> "[${node.id}] scrollable list"
                    else -> null
                }
            }
        val content = nodes.count { it.text.isNotBlank() && !it.clickable && !it.editable }
        return buildString {
            append("On screen: $app. Controls:\n")
            append(if (lines.isEmpty()) "(none found)" else lines.joinToString("\n"))
            append("\nThere are $content pieces of text on the screen; only read_screen reads them, on the phone.")
        }
    }

    /** A short label for a control, or null when its only words are content (too long to be a label). */
    fun label(node: ScreenNode): String? {
        val words = listOf(node.description, node.hint, node.text).map { it.trim() }.firstOrNull { it.isNotEmpty() }
        val fromId = node.viewId.substringAfterLast('/').replace('_', ' ').trim()
        return when {
            words != null && words.length <= LABEL_MAX && words.count { it == ' ' } < LABEL_WORDS -> words
            fromId.isNotEmpty() -> fromId
            else -> null
        }
    }

    /** Everything written on the screen, for the phone's own model: never for the planner. Password fields are left out. */
    fun content(nodes: List<ScreenNode>): String = nodes
        .filterNot { it.password }
        .flatMap { listOf(it.text, it.description) }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .joinToString("\n")

    /**
     * What the phone's own model is asked before a tap: whether it only [MOVES] around the app or
     * [DOES] something (sends, pays, posts, deletes, confirms). It reads the control's own words in
     * whatever language the app shows them, its view name and the app's name; the examples only show
     * it where the line is. Null when there's nothing to judge by (an icon without a label): that tap
     * waits for the user's yes.
     */
    fun tapQuestion(
        app: String,
        node: ScreenNode,
    ): String? {
        val shown = shown(node) ?: return null
        val what = if (node.inList) "an entry in a list" else "a ${node.kind}"
        return "$TAP_TASK\nNow:\nIn \"$app\", $what $shown:"
    }

    /**
     * The same for pressing Enter in a text field: in a search or address field it only goes there, in a
     * message field it can send. Null when the field has no words or name to judge by.
     */
    fun enterQuestion(
        app: String,
        node: ScreenNode,
    ): String? {
        val shown = shown(node) ?: return null
        return "$TAP_TASK\nNow:\nIn \"$app\", pressing Enter in a text field $shown:"
    }

    /** "that says "…", named "…" by the app", or null when a control has neither. */
    private fun shown(node: ScreenNode): String? {
        val words =
            listOf(node.description, node.text, node.hint)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .joinToString(" / ")
                .take(TAP_WORDS_MAX)
        val name = node.viewId.substringAfterLast('/')
        if (words.isEmpty() && name.isEmpty()) return null
        return listOfNotNull(words.takeIf { it.isNotEmpty() }?.let { "that says \"$it\"" }, name.takeIf { it.isNotEmpty() }?.let { "named \"$it\" by the app" })
            .joinToString(", ")
    }

    /** The phone's model's answers to [tapQuestion] and [enterQuestion]. */
    const val MOVES = "moves"
    const val DOES = "does"
    val TAP_CHOICES = listOf(MOVES, DOES)

    private const val TAP_TASK =
        "The user's assistant is about to tap a control, or press Enter in a text field, in a phone app. Say whether that only " +
            "moves around the app (opens, searches, goes to a web address, scrolls, goes back, switches a tab, shows more) or does " +
            "something (sends, posts, shares, pays, buys, orders, books, deletes, confirms, accepts, calls, joins, signs up or out, " +
            "changes the account).\n" +
            "Examples:\n" +
            "In \"Chat\", a button that says \"Search\": moves\n" +
            "In \"Chat\", a button that says \"Send\": does\n" +
            "In \"Chat\", an entry in a list that says \"Anna: see you at 8\": moves\n" +
            "In \"Shop\", a button that says \"Pay now\": does\n" +
            "In \"Photos\", a button that says \"Delete\": does\n" +
            "In \"Mail\", a button that says \"Back\": moves\n" +
            "In \"Social\", a button that says \"Join\": does\n" +
            "In \"Mail\", an entry in a list that says \"Your parcel is on its way\": moves\n" +
            "In \"Browser\", pressing Enter in a text field that says \"Search or type web address\": moves\n" +
            "In \"Chat\", pressing Enter in a text field that says \"Message\": does"

    private const val LABEL_MAX = 30
    private const val LABEL_WORDS = 4
    private const val TAP_WORDS_MAX = 120
}
