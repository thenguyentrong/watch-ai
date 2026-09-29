package com.vinhnguyen.watchai.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vinhnguyen.watchai.AppSettings
import com.vinhnguyen.watchai.actions.Memory
import kotlinx.coroutines.launch

/**
 * What Buddy remembers: what the user asked it to keep, and the conversations of the last 30 days
 * (their words and Buddy's replies), encrypted on this phone. Everything can be turned off or deleted here.
 */
@Composable
fun HistoryScreen(
    memory: Memory,
    settings: AppSettings,
) {
    val p = LocalPalette.current
    val kept by memory.kept.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var on by remember { mutableStateOf(settings.rememberChats) }
    var open by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(memory) { memory.load() }

    Text(
        "Kept 30 days, encrypted on this phone. A new conversation starts knowing the last ones and what you asked Buddy to " +
            "remember, with codes and numbers taken out. What your phone read out to you itself is never kept.",
        style = MaterialTheme.typography.bodyLarge,
        color = p.textSecondary,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 8.dp),
    )

    Group {
        Item(
            "Remember conversations",
            subtitle = if (on) "Buddy knows what you talked about lately." else "Off: every conversation starts fresh. What you asked Buddy to remember is still used.",
            last = true,
            trailing = {
                Switch(
                    checked = on,
                    onCheckedChange = {
                        settings.rememberChats = it
                        on = it
                    },
                    colors = switchColors(),
                )
            },
        )
    }

    Group("What Buddy remembers", footer = "Say \"remember that…\" or \"forget that…\" to Buddy, in any language.") {
        if (kept.facts.isEmpty()) {
            Item("Nothing yet", subtitle = "Tell Buddy something about you and ask it to remember it.", last = true)
        } else {
            val facts = kept.facts.asReversed()
            facts.forEachIndexed { i, fact ->
                Item(
                    fact.text,
                    subtitle = day(fact.at),
                    last = i == facts.lastIndex,
                    trailing = { Pill("Forget", onClick = { scope.launch { memory.forget(fact.id) } }, filled = false) },
                )
            }
        }
    }

    if (kept.conversations.isEmpty()) {
        Group("Conversations") { Item("Nothing yet", subtitle = "Talk to Buddy on your watch or here.", last = true) }
        return
    }
    kept.conversations.asReversed().take(SHOWN).groupBy { day(it.startedAt) }.forEach { (day, conversations) ->
        Group(day) {
            conversations.forEachIndexed { i, c ->
                val last = i == conversations.lastIndex
                val first = c.turns.firstOrNull { it.user } ?: c.turns.firstOrNull()
                Item(
                    first?.text?.take(TITLE_MAX).orEmpty(),
                    subtitle = "${time(c.startedAt)} · ${c.where} · ${c.turns.size} ${if (c.turns.size == 1) "turn" else "turns"}",
                    last = last && open != c.id,
                    onClick = { open = if (open == c.id) null else c.id },
                )
                if (open == c.id) {
                    c.turns.forEach { t ->
                        Text(
                            (if (t.user) "You: " else "Buddy: ") + t.text + if (t.local) " (on this phone only)" else "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (t.user) p.text else p.textSecondary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                    Item(
                        "Delete this conversation",
                        danger = true,
                        last = last,
                        onClick = {
                            open = null
                            scope.launch { memory.deleteConversation(c.id) }
                        },
                    )
                }
            }
        }
    }
    Group {
        Item("Delete all conversations", danger = true, last = true, onClick = { scope.launch { memory.clearConversations() } })
    }
}

private const val SHOWN = 100
private const val TITLE_MAX = 90
