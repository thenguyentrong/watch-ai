package com.vinhnguyen.watchai.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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

@Composable
fun SettingsScreen(
    settings: AppSettings,
    onDeleteEverything: suspend () -> Unit,
    benchmark: BenchmarkViewModel?,
) {
    var voice by remember { mutableStateOf(settings.voice) }
    var confirm by remember { mutableStateOf(false) }
    var deleted by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Buddy's voice", style = MaterialTheme.typography.titleMedium)
        Text("On the watch and on this phone, from your next conversation.", style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChatGptRealtimeSession.VOICES.forEach { v ->
                FilterChip(
                    selected = voice == v,
                    onClick = {
                        settings.voice = v
                        voice = v
                    },
                    label = { Text(v.replaceFirstChar { it.uppercase() }) },
                )
            }
        }

        HorizontalDivider()
        Text("Privacy", style = MaterialTheme.typography.titleMedium)
        Text(
            "Chats stay in memory and disappear when you close the app. Your ChatGPT sign-in is encrypted with a key " +
                "that can't leave this phone. No analytics, no ads, no backups of app data.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(
            onClick = { confirm = true },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
        ) { Text("Delete everything") }
        if (deleted) Text("Everything was deleted.", color = MaterialTheme.colorScheme.primary)

        HorizontalDivider()
        BackgroundSection()

        HorizontalDivider()
        Text("Licences", style = MaterialTheme.typography.titleMedium)
        Text(
            "Buddy's animation is ported from bloub by Jérémy Perret, MIT License (github.com/jeremy-prt/bloub). " +
                "Gemma 4 model: Apache License 2.0 (Google). LiteRT-LM, Tink, OkHttp, Timber, AndroidX, Kotlin: Apache License 2.0. " +
                "WebRTC: BSD 3-Clause. On the watch, sherpa-onnx: Apache License 2.0; ONNX Runtime: MIT. " +
                "ML Kit and Play services: Google APIs Terms of Service.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)

        if (benchmark != null) {
            HorizontalDivider()
            BenchmarkSection(benchmark)
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Delete everything?") },
            text = { Text("Signs you out of ChatGPT, deletes the on-device model, your flagged answers and all settings.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    scope.launch {
                        onDeleteEverything()
                        deleted = true
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun BenchmarkSection(vm: BenchmarkViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Text("Benchmark (debug builds only)", style = MaterialTheme.typography.titleMedium)
    Text("Ten synthetic prompts. Only timings are saved.", style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { vm.run(BrainId.GEMMA) }, enabled = !state.running) { Text("Gemma") }
        OutlinedButton(onClick = { vm.run(BrainId.CHATGPT) }, enabled = !state.running) { Text("ChatGPT") }
        OutlinedButton(onClick = { vm.run(BrainId.GEMINI_NANO) }, enabled = !state.running) { Text("Nano") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.running) TextButton(onClick = vm::stop) { Text("Stop") }
        TextButton(onClick = { vm.save(context) }, enabled = state.rows.isNotEmpty() && !state.running) { Text("Save") }
        TextButton(onClick = vm::clear, enabled = !state.running) { Text("Clear") }
    }
    state.savedTo?.let { Text("Saved to $it", style = MaterialTheme.typography.bodySmall) }
    state.rows.forEach { r ->
        Text(
            "${r.brain} #${r.prompt}: " +
                if (r.ok) "first ${r.firstTokenMillis} ms · total ${r.totalMillis} ms · ${r.chars} chars · ${r.backend}" else "failed (${r.error})",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * One switch for working in the background: Android's battery optimisation exemption. With it,
 * the watch reaches Buddy with the phone app closed (Android may refuse to start the call service
 * otherwise), and a conversation on the phone keeps going after leaving the app.
 */
@Composable
private fun BackgroundSection() {
    val context = LocalContext.current
    val power = remember { context.getSystemService(PowerManager::class.java) }
    var allowed by remember { mutableStateOf(power.isIgnoringBatteryOptimizations(context.packageName)) }
    // The answer comes back from a system screen: read it again whenever this one returns.
    LifecycleResumeEffect(Unit) {
        allowed = power.isIgnoringBatteryOptimizations(context.packageName)
        onPauseOrDispose { }
    }
    Text("Work in the background", style = MaterialTheme.typography.titleMedium)
    Text(
        if (allowed) {
            "On. Your watch reaches Buddy with the phone app closed, and a conversation on the phone keeps going when you switch apps."
        } else {
            "Lets your watch reach Buddy with the phone app closed, and keeps a conversation on the phone going when you switch apps. Uses a bit more battery."
        },
        style = MaterialTheme.typography.bodyMedium,
    )
    if (allowed) {
        OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }) { Text("Change in Android settings") }
    } else {
        Button(onClick = {
            @SuppressLint("BatteryLife") // A companion for the watch: calls come in with the app closed.
            val ask = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri())
            context.startActivity(ask)
        }) { Text("Allow") }
    }
}
