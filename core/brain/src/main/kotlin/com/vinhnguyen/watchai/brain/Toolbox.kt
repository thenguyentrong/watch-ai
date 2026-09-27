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
}

/** [parametersJson] is a JSON Schema object for the arguments. */
public data class ToolSpec(
    val name: String,
    val description: String,
    val parametersJson: String,
)
