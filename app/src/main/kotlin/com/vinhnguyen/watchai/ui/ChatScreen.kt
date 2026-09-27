package com.vinhnguyen.watchai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vinhnguyen.watchai.brain.ChatTurn
import com.vinhnguyen.watchai.brain.RoutePreference

@Composable
fun ChatScreen(vm: ChatViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    var flagging by remember { mutableStateOf<Long?>(null) }
    val listState = rememberLazyListState()
    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.text?.length) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(RoutePreference.AUTO to "Auto", RoutePreference.ON_DEVICE to "On this phone", RoutePreference.CHATGPT to "ChatGPT")
                .forEach { (pref, label) ->
                    FilterChip(selected = state.preference == pref, onClick = { vm.setPreference(pref) }, label = { Text(label) })
                }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Allow ChatGPT answers", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = state.cloudAllowed, onCheckedChange = vm::setCloudAllowed)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        ) {
            if (state.messages.isEmpty()) {
                item {
                    Text(
                        "Ask something short, like you would on a watch.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(state.messages, key = { it.id }) { message -> MessageItem(message, onFlag = { flagging = message.id }) }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask…") },
                singleLine = false,
                maxLines = 4,
            )
            if (state.busy) {
                OutlinedButton(onClick = vm::stop, modifier = Modifier.padding(start = 8.dp)) { Text("Stop") }
            } else {
                Button(
                    onClick = {
                        vm.send(input)
                        input = ""
                    },
                    enabled = input.isNotBlank(),
                    modifier = Modifier.padding(start = 8.dp),
                ) { Text("Send") }
            }
        }
        TextButton(onClick = vm::newConversation, modifier = Modifier.align(Alignment.End).padding(end = 8.dp)) { Text("New chat") }
    }

    flagging?.let { id ->
        FlagDialog(onDismiss = { flagging = null }, onSubmit = { includePrompt, reason ->
            vm.flag(id, includePrompt, reason)
            flagging = null
        })
    }
}

@Composable
private fun MessageItem(
    message: ChatViewModel.Message,
    onFlag: () -> Unit,
) {
    val mine = message.role == ChatTurn.Role.USER
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Card(
            modifier = Modifier.widthIn(max = 320.dp),
            colors =
            CardDefaults.cardColors(
                containerColor = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (message.text.isNotEmpty() || message.streaming) Text(message.text.ifEmpty { "…" })
                message.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (!mine && !message.streaming) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val stats = message.stats
                        val label =
                            buildString {
                                append(message.brain?.let { "Answered by ${it.label}" } ?: "AI answer")
                                if (stats != null) {
                                    stats.firstTokenMillis?.let { append(" · first word $it ms") }
                                    append(" · ${stats.totalMillis} ms")
                                }
                            }
                        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (message.text.isNotEmpty()) {
                            TextButton(onClick = onFlag, enabled = !message.flagged) { Text(if (message.flagged) "Flagged" else "Flag") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FlagDialog(
    onDismiss: () -> Unit,
    onSubmit: (includePrompt: Boolean, reason: String) -> Unit,
) {
    val reasons = listOf("Offensive or harmful", "Wrong or misleading", "Other")
    var reason by remember { mutableStateOf(reasons.first()) }
    var includePrompt by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Flag this answer") },
        text = {
            Column {
                reasons.forEach { r ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = reason == r, onClick = { reason = r })
                        Text(r)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = includePrompt, onCheckedChange = { includePrompt = it })
                    Text("Include my question")
                }
                Text("Saved encrypted on this phone.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onSubmit(includePrompt, reason) }) { Text("Flag") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
