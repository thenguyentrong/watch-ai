package com.vinhnguyen.watchai.actions

import com.vinhnguyen.watchai.AppSettings
import com.vinhnguyen.watchai.brain.BrainLogger
import com.vinhnguyen.watchai.brain.LogEvent
import com.vinhnguyen.watchai.brain.ToolSpec
import com.vinhnguyen.watchai.brain.Toolbox
import com.vinhnguyen.watchai.buddy.Item
import com.vinhnguyen.watchai.buddy.Look
import com.vinhnguyen.watchai.buddy.Outfit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * What the AI can do with Buddy, the mascot on the watch: dress it for who the user is. The user
 * never picks clothes; the AI does, from what the user tells it. The outfit is kept on the phone
 * and goes to the watch at the start of every watch conversation, or right away in one ([onOutfit]).
 */
class BuddyTools(
    private val settings: AppSettings,
    private val logger: BrainLogger = BrainLogger.None,
    private val onOutfit: (Outfit) -> Unit = {},
) : Toolbox {
    override fun tools(): List<ToolSpec> = SPECS

    override suspend fun run(
        name: String,
        argumentsJson: String,
    ): String {
        if (name != DRESS) return "error: there is no action called $name"
        val args = ActionArgs.parse(argumentsJson) ?: return invalid()
        val look = ActionArgs.choice(args, "look", LOOKS)?.let { Look.of(it) }
        val items = (args["items"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.let(Item::of) }
        val outfit =
            when {
                // Single items go on over what Buddy wears (or over the look, if both are given).
                items.isNotEmpty() -> Outfit.of((look?.outfit ?: Outfit.parse(settings.buddyOutfit)).items.values + items)

                look != null -> look.outfit

                else -> return invalid()
            }
        settings.buddyOutfit = outfit.wire
        onOutfit(outfit)
        logger.log(LogEvent.ToolUsed(DRESS, "ok"))
        val worn = outfit.items.values.joinToString { it.label }
        return if (worn.isEmpty()) "ok: Buddy wears nothing special now" else "ok: Buddy now wears $worn on the watch"
    }

    private fun invalid(): String {
        logger.log(LogEvent.ToolUsed(DRESS, "invalid"))
        return "error: give a look (${LOOKS.joinToString()}) or items (${ITEMS.joinToString()})"
    }

    companion object {
        const val DRESS = "dress_buddy"
        private val LOOKS = Look.entries.map { it.wire }.toSet()
        private val ITEMS = Item.entries.map { it.wire }

        val SPECS =
            listOf(
                ToolSpec(
                    DRESS,
                    "Dress Buddy, the little mascot on the user's watch (that's you), for who the user is: when they mention their job, " +
                        "sport or hobby, or ask Buddy to dress up, wear something or change clothes. Use a look for a whole outfit " +
                        "('plain' takes everything off), or items to put single things on; an item replaces what Buddy wears in the same " +
                        "place (head, face, neck, body, hand). Don't change clothes without a reason.",
                    """{"type":"object","properties":{"look":{"type":"string","enum":[${LOOKS.joinToString { "\"$it\"" }}]},""" +
                        """"items":{"type":"array","items":{"type":"string","enum":[${ITEMS.joinToString { "\"$it\"" }}]}}},"additionalProperties":false}""",
                ),
            )
    }
}
