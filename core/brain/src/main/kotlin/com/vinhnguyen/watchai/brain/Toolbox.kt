package com.vinhnguyen.watchai.brain

/**
 * Things a brain may do on the user's behalf (add a note, a calendar event, a timer…). The brain
 * decides when to call a tool; the app runs it and the result goes back to the brain.
 *
 * Model output is untrusted: implementations validate every argument and only offer actions that
 * are safe to run without asking (adding and reading, never deleting or sending).
 */
public interface Toolbox {
    /** Tools usable right now (e.g. calendar tools only once the permission is granted). */
    public fun tools(): List<ToolSpec>

    /** Runs one call and returns what to tell the model: a short result or an error. Never throws. */
    public suspend fun run(
        name: String,
        argumentsJson: String,
    ): String

    /** The same, knowing what the user asked (so private data can be cut down to what the question needs). */
    public suspend fun run(
        name: String,
        argumentsJson: String,
        context: ToolContext,
    ): String = run(name, argumentsJson)

    /**
     * True once a result carried the user's private data (messages, notes, the calendar) in this
     * conversation. From then on the brain keeps the model away from the live web, so a poisoned
     * message can't make it open a link with that data in it.
     */
    public val sharedPrivateData: Boolean get() = false
}

/** What the call is for: the user's question in this turn. */
public data class ToolContext(
    val question: String,
)

/** [parametersJson] is a JSON Schema object for the arguments. */
public data class ToolSpec(
    val name: String,
    val description: String,
    val parametersJson: String,
)

/**
 * Several toolboxes offered as one (e.g. the phone's actions and Buddy's outfits). [onResult] sees
 * every call's result, so the app can react to what the tools did.
 */
public class Toolboxes(
    private val parts: List<Toolbox>,
    private val onResult: (name: String, result: String) -> Unit = { _, _ -> },
) : Toolbox {
    override fun tools(): List<ToolSpec> = parts.flatMap { it.tools() }

    override suspend fun run(
        name: String,
        argumentsJson: String,
    ): String = run(name, argumentsJson, ToolContext(""))

    override suspend fun run(
        name: String,
        argumentsJson: String,
        context: ToolContext,
    ): String {
        val part = parts.firstOrNull { toolbox -> toolbox.tools().any { it.name == name } }
        val result = part?.run(name, argumentsJson, context) ?: "error: there is no action called $name"
        onResult(name, result)
        return result
    }

    override val sharedPrivateData: Boolean get() = parts.any { it.sharedPrivateData }
}
