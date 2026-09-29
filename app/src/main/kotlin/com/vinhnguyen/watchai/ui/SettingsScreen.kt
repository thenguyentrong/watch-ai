package com.vinhnguyen.watchai.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vinhnguyen.watchai.AppSettings
import com.vinhnguyen.watchai.BuildConfig
import com.vinhnguyen.watchai.brain.BrainId
import com.vinhnguyen.watchai.voice.ChatGptRealtimeSession
import kotlinx.coroutines.launch

/** Buddy's voice, working in the background, privacy and what the app is made of. */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onDeleteEverything: suspend () -> Unit,
    benchmark: BenchmarkViewModel?,
    offlineReady: Boolean,
    onSafetyCheck: () -> Unit,
) {
    val p = LocalPalette.current
    var voice by remember { mutableStateOf(settings.voice) }
    var privateOnPhone by remember { mutableStateOf(settings.privateOnPhone) }
    var confirm by remember { mutableStateOf(false) }
    var deleted by remember { mutableStateOf(false) }
    var licences by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Group("Buddy's voice", footer = "On the watch and on this phone, from your next conversation.") {
        FlowRow(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ChatGptRealtimeSession.VOICES.forEach { v ->
                Pill(
                    v.replaceFirstChar { it.uppercase() },
                    onClick = {
                        settings.voice = v
                        voice = v
                    },
                    filled = voice == v,
                )
            }
        }
    }

    Group("Background") { BackgroundItem() }

    Group(
        "Privacy",
        footer = "Chats stay in memory and are gone when you close the app. Your ChatGPT sign-in is encrypted with a key " +
            "that can't leave this phone. No analytics, no ads, no backups of app data.",
    ) {
        Item(
            "Private things stay on this phone",
            subtitle =
            when {
                !privateOnPhone -> "Off: ChatGPT reads your messages, notes and calendar when you ask, with codes, numbers and links taken out."

                offlineReady ->
                    "Gemma reads your messages, notes and calendar here and your phone says the answer in its own voice. " +
                        "ChatGPT only hears what you ask, never what's in them."

                else ->
                    "Your phone reads them out itself; ChatGPT only hears what you ask. With the offline model (Your AI), Gemma " +
                        "answers just what you asked instead of reading everything."
            },
            trailing = {
                Switch(
                    checked = privateOnPhone,
                    onCheckedChange = {
                        settings.privateOnPhone = it
                        privateOnPhone = it
                    },
                    colors = switchColors(),
                )
            },
        )
        Item(
            "See what ChatGPT gets",
            subtitle = "Try a tricky message and see what's left of it",
            onClick = onSafetyCheck,
        )
        Item(
            if (deleted) "Everything was deleted" else "Delete everything",
            subtitle = "Signs you out and removes the offline model, notes and settings",
            danger = true,
            last = true,
            onClick = { confirm = true },
        )
    }

    Group("About") {
        Item("Version", last = false, trailing = { Text(BuildConfig.VERSION_NAME, style = MaterialTheme.typography.bodyMedium, color = p.textSecondary) })
        Item(
            "Licences",
            subtitle = if (licences) LICENCES else "Open-source parts and their licences",
            last = true,
            onClick = { licences = !licences },
        )
    }

    if (benchmark != null) Group("Benchmark (test builds)") { BenchmarkItems(benchmark) }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Delete everything?") },
            text = { Text("Signs you out of ChatGPT, deletes the offline model, your notes, flagged answers and all settings.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    scope.launch {
                        onDeleteEverything()
                        deleted = true
                    }
                }) { Text("Delete", color = p.danger) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun BenchmarkItems(vm: BenchmarkViewModel) {
    val p = LocalPalette.current
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill("Gemma", onClick = { if (!state.running) vm.run(BrainId.GEMMA) }, filled = false)
        Pill("ChatGPT", onClick = { if (!state.running) vm.run(BrainId.CHATGPT) }, filled = false)
        Pill("Nano", onClick = { if (!state.running) vm.run(BrainId.GEMINI_NANO) }, filled = false)
    }
    Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.running) Pill("Stop", onClick = vm::stop, filled = false)
        if (state.rows.isNotEmpty() && !state.running) Pill("Save", onClick = { vm.save(context) }, filled = false)
        if (!state.running) Pill("Clear", onClick = vm::clear, filled = false)
    }
    state.savedTo?.let { Text("Saved to $it", style = MaterialTheme.typography.bodySmall, color = p.textSecondary, modifier = Modifier.padding(12.dp)) }
    state.rows.forEach { r ->
        Text(
            "${r.brain} #${r.prompt}: " +
                if (r.ok) "first ${r.firstTokenMillis} ms · total ${r.totalMillis} ms · ${r.chars} chars · ${r.backend}" else "failed (${r.error})",
            style = MaterialTheme.typography.bodySmall,
            color = p.textSecondary,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
        )
    }
}

/**
 * One switch for working in the background: Android's battery optimisation exemption. With it,
 * the watch reaches Buddy with the phone app closed (Android may refuse to start the call service
 * otherwise), and a conversation on the phone keeps going after leaving the app.
 */
@Composable
private fun BackgroundItem() {
    val p = LocalPalette.current
    val context = LocalContext.current
    val power = remember { context.getSystemService(PowerManager::class.java) }
    var allowed by remember { mutableStateOf(power.isIgnoringBatteryOptimizations(context.packageName)) }
    // The answer comes back from a system screen: read it again whenever this one returns.
    LifecycleResumeEffect(Unit) {
        allowed = power.isIgnoringBatteryOptimizations(context.packageName)
        onPauseOrDispose { }
    }
    Item(
        "Work in the background",
        subtitle =
        if (allowed) {
            "Your watch reaches Buddy with the phone app closed, and a conversation here keeps going when you switch apps."
        } else {
            "Lets your watch reach Buddy with the phone app closed. Uses a bit more battery."
        },
        last = true,
        onClick = if (allowed) ({ context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }) else null,
        trailing = {
            if (allowed) {
                Text("On", style = MaterialTheme.typography.labelLarge, color = p.textSecondary)
            } else {
                Pill("Allow", onClick = {
                    @SuppressLint("BatteryLife") // A companion for the watch: calls come in with the app closed.
                    val ask = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri())
                    context.startActivity(ask)
                })
            }
        },
    )
}

private const val LICENCES =
    "Buddy's animation is ported from bloub by Jérémy Perret, MIT License (github.com/jeremy-prt/bloub). " +
        "Inter typeface: SIL Open Font License 1.1 (The Inter Project Authors). Liquid Glass (backdrop, shapes) by Kyant: " +
        "Apache License 2.0. Material Symbols: Apache License 2.0 (Google). Map data © OpenStreetMap contributors (ODbL). " +
        "Gemma 4 model: Apache License 2.0 (Google). LiteRT-LM, Tink, OkHttp, Timber, AndroidX, Kotlin: Apache License 2.0. " +
        "WebRTC: BSD 3-Clause. On the watch, sherpa-onnx: Apache License 2.0; ONNX Runtime: MIT. " +
        "ML Kit and Play services: Google APIs Terms of Service."
