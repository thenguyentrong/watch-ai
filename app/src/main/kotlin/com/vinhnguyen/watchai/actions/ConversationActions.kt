package com.vinhnguyen.watchai.actions

import com.vinhnguyen.watchai.brain.BrainLogger
import com.vinhnguyen.watchai.brain.LogEvent
import com.vinhnguyen.watchai.brain.ToolSpec
import com.vinhnguyen.watchai.brain.Toolbox

/** Ending the conversation by voice ("bye", "that's all"): Buddy says goodbye and goes back to waiting for "Hey Buddy". */
class ConversationActions(
    private val end: () -> Unit,
    private val logger: BrainLogger = BrainLogger.None,
) : Toolbox {
    override fun tools(): List<ToolSpec> = SPECS

    override suspend fun run(
        name: String,
        argumentsJson: String,
    ): String {
        if (name != END) return "error: there is no action called $name"
        end()
        logger.log(LogEvent.ToolUsed(END, "ok"))
        return "ok: the conversation ends right after your goodbye; say it in two or three words"
    }

    companion object {
        const val END = "end_conversation"

        val SPECS =
            listOf(
                ToolSpec(
                    END,
                    "End the conversation when the user says goodbye or that they're done ('bye', 'that's all', 'stop listening', " +
                        "'thanks, that's it', in any language). Buddy then waits for \"Hey Buddy\" again.",
                    """{"type":"object","properties":{},"additionalProperties":false}""",
                ),
            )
    }
}
