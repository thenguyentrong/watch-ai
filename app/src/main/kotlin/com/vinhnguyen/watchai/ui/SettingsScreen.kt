package com.vinhnguyen.watchai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vinhnguyen.watchai.BuildConfig
import com.vinhnguyen.watchai.brain.BrainId
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    onDeleteEverything: suspend () -> Unit,
    benchmark: BenchmarkViewModel?,
) {
    var confirm by remember { mutableStateOf(false) }
    var deleted by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
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
        Text("Licences", style = MaterialTheme.typography.titleMedium)
        Text(
            "Gemma 4 model: Apache License 2.0 (Google). LiteRT-LM, Tink, OkHttp, AndroidX, Kotlin: Apache License 2.0. " +
                "ML Kit: Google APIs Terms of Service.",
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
