package com.vinhnguyen.watchai.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.vinhnguyen.watchai.R
import com.vinhnguyen.watchai.brain.ChatTurn
import com.vinhnguyen.watchai.brain.RoutePreference

/**
 * Typing with Buddy, like ChatGPT: the user's words in soft bubbles on the right, Buddy's answers
 * as plain text with who answered under them, and a glass bar for typing that floats over the chat.
 * The chat runs under the page's bar; [top] is the room it takes.
 */
@Composable
fun ChatScreen(
    vm: ChatViewModel,
    top: Dp,
) {
    val p = LocalPalette.current
    val state by vm.state.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    var flagging by remember { mutableStateOf<Long?>(null) }
    val listState = rememberLazyListState()
    val backdrop = rememberLayerBackdrop()
    val askCloud = !state.cloudAllowed && state.preference != RoutePreference.ON_DEVICE
    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.text?.length) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex + if (askCloud) 1 else 0)
    }
    val send = { text: String ->
        vm.send(text)
        input = ""
    }

    Box(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().layerBackdrop(backdrop).background(p.background),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top + 8.dp, bottom = 104.dp),
        ) {
            if (askCloud) {
                item(key = "cloud") {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(p.surface).padding(start = 16.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Let ChatGPT answer when this phone can't", style = MaterialTheme.typography.bodyMedium, color = p.textSecondary, modifier = Modifier.weight(1f))
                        Switch(checked = false, onCheckedChange = vm::setCloudAllowed, colors = switchColors())
                    }
                }
            }
            if (state.messages.isEmpty()) item(key = "empty") { Suggestions(onPick = send) }
            items(state.messages, key = { it.id }) { message -> MessageItem(message, onFlag = { flagging = message.id }) }
        }
        Composer(
            input = input,
            busy = state.busy,
            backdrop = backdrop,
            onInput = { input = it },
            onSend = { if (input.isNotBlank()) send(input) },
            onStop = vm::stop,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 12.dp, vertical = 12.dp),
        )
    }

    flagging?.let { id ->
        FlagDialog(onDismiss = { flagging = null }, onSubmit = { includePrompt, reason ->
            vm.flag(id, includePrompt, reason)
            flagging = null
        })
    }
}

/** Which AI answers, in the chat's bar, like ChatGPT's model picker. */
@Composable
fun ChatRoutePicker(vm: ChatViewModel) {
    val p = LocalPalette.current
    val state by vm.state.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    var labelWidth by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current
    val current = ROUTES.first { it.preference == state.preference }
    Box {
        Row(
            Modifier
                .onSizeChanged { labelWidth = with(density) { it.width.toDp() } }
                .clip(CircleShape)
                .clickable(role = Role.DropdownList, onClickLabel = "Choose who answers") { open = true }
                .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(current.title, style = MaterialTheme.typography.titleSmall, color = p.text, maxLines = 1)
            Icon(painterResource(R.drawable.sym_expand_more), contentDescription = null, tint = p.textSecondary, modifier = Modifier.padding(start = 2.dp).size(20.dp))
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            offset = DpOffset((labelWidth - PICKER_WIDTH) / 2, 6.dp),
            shape = RoundedCornerShape(22.dp),
            containerColor = p.surface,
            shadowElevation = 16.dp,
            border = BorderStroke(0.5.dp, p.separator),
        ) {
            ROUTES.forEach { route ->
                DropdownMenuItem(
                    text = {
                        Column(Modifier.padding(vertical = 10.dp)) {
                            Text(route.title, style = MaterialTheme.typography.titleMedium, color = p.text)
                            Text(route.detail, style = MaterialTheme.typography.bodySmall, color = p.textSecondary)
                        }
                    },
                    trailingIcon = {
                        if (route.preference == state.preference) Icon(painterResource(R.drawable.sym_check), contentDescription = "Chosen", tint = p.text, modifier = Modifier.size(20.dp))
                    },
                    onClick = {
                        vm.setPreference(route.preference)
                        open = false
                    },
                    modifier = Modifier.width(PICKER_WIDTH),
                )
            }
        }
    }
}

private val PICKER_WIDTH = 280.dp

private class ChatRoute(
    val preference: RoutePreference,
    val title: String,
    val detail: String,
)

private val ROUTES =
    listOf(
        ChatRoute(RoutePreference.AUTO, "Auto", "This phone first, then ChatGPT"),
        ChatRoute(RoutePreference.ON_DEVICE, "On this phone", "Offline, nothing leaves the phone"),
        ChatRoute(RoutePreference.CHATGPT, "ChatGPT", "On your ChatGPT plan"),
    )

@Composable
private fun LazyItemScope.Suggestions(onPick: (String) -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillParentMaxHeight(0.78f).fillMaxWidth(), verticalArrangement = Arrangement.Center) {
        Text("What can I do for you?", style = MaterialTheme.typography.headlineSmall, color = p.text)
        Text(
            "Ask anything, or tell Buddy to do something on your phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = p.textSecondary,
            modifier = Modifier.padding(top = 6.dp, bottom = 24.dp),
        )
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(p.surface)) {
            SUGGESTIONS.forEachIndexed { i, (icon, text) ->
                Item(text, painter = painterResource(icon), last = i == SUGGESTIONS.lastIndex, onClick = { onPick(text) })
            }
        }
    }
}

private val SUGGESTIONS =
    listOf(
        R.drawable.sym_timer to "Set a timer for ten minutes",
        R.drawable.sym_event to "What's on my calendar today?",
        R.drawable.sym_sticky_note_2 to "Add milk to my notes",
        R.drawable.sym_chat_bubble to "Any new messages?",
    )

@Composable
private fun Composer(
    input: String,
    busy: Boolean,
    backdrop: Backdrop,
    onInput: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    Row(
        modifier
            .fillMaxWidth()
            .glass(backdrop, p.surface.copy(alpha = 0.78f), corner = 28.dp)
            .padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
            if (input.isEmpty()) Text("Ask Buddy", style = MaterialTheme.typography.bodyLarge, color = p.textTertiary)
            BasicTextField(
                value = input,
                onValueChange = onInput,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = p.text),
                cursorBrush = SolidColor(p.text),
                maxLines = 5,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        val ready = busy || input.isNotBlank()
        Box(
            Modifier
                .padding(start = 8.dp)
                .size(40.dp)
                .clip(CircleShape)
                .background(if (ready) p.text else p.surfaceHigh)
                .clickable(role = Role.Button, onClickLabel = if (busy) "Stop" else "Send", onClick = if (busy) onStop else onSend),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(if (busy) R.drawable.sym_stop else R.drawable.sym_arrow_upward),
                contentDescription = if (busy) "Stop" else "Send",
                tint = if (ready) p.background else p.textTertiary,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun MessageItem(
    message: ChatViewModel.Message,
    onFlag: () -> Unit,
) {
    val p = LocalPalette.current
    val mine = message.role == ChatTurn.Role.USER
    if (mine) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Text(
                message.text,
                style = MaterialTheme.typography.bodyLarge,
                color = p.text,
                modifier = Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(22.dp)).background(p.surfaceHigh).padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
        return
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (message.text.isNotEmpty() || message.streaming) Text(message.text.ifEmpty { "…" }, style = MaterialTheme.typography.bodyLarge, color = p.text)
        message.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = p.danger) }
        if (!message.streaming) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val stats = message.stats
                val label =
                    buildString {
                        append(message.brain?.let { "Answered by ${it.label}" } ?: "AI answer")
                        if (stats != null) append(" · %.1f s".format(stats.totalMillis / 1000.0))
                    }
                Text(label, style = MaterialTheme.typography.labelSmall, color = p.textTertiary, modifier = Modifier.weight(1f))
                if (message.text.isNotEmpty()) {
                    Text(
                        if (message.flagged) "Flagged" else "Flag",
                        style = MaterialTheme.typography.labelSmall,
                        color = p.textTertiary,
                        modifier = Modifier.clip(CircleShape).clickable(enabled = !message.flagged, role = Role.Button, onClick = onFlag).padding(horizontal = 8.dp, vertical = 4.dp),
                    )
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
