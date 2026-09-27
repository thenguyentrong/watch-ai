package com.vinhnguyen.watchai.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vinhnguyen.watchai.CustomTabLauncher
import com.vinhnguyen.watchai.brain.chatgpt.auth.AuthState
import com.vinhnguyen.watchai.ondevice.ModelState

@Composable
fun BrainsScreen(vm: BrainsViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val auth by vm.auth.collectAsStateWithLifecycle()
    val usage by vm.usage.collectAsStateWithLifecycle()
    val model by vm.modelState.collectAsStateWithLifecycle()
    val activity = LocalActivity.current ?: return
    // The download shows a progress notification; Android 13+ needs permission for it (the download runs either way).
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val startDownload = {
        val needsAsk =
            Build.VERSION.SDK_INT >= 33 &&
                activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsAsk) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        vm.startDownload()
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }

        BrainCard("ChatGPT", "Uses your own ChatGPT Plus or Pro plan through OpenAI's sign-in. Your questions go to OpenAI, not to us.") {
            when (val a = auth) {
                is AuthState.SignedIn -> {
                    Text("Signed in${a.emailMasked?.let { " as $it" } ?: ""}${a.planType?.let { " · $it plan" } ?: ""}")
                    usage?.let { u ->
                        u.primaryUsedPercent?.let { pct ->
                            Text("Plan usage (5-hour window): ${pct.toInt()}%", style = MaterialTheme.typography.bodySmall)
                            LinearProgressIndicator(progress = { (pct / 100).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                            if (u.nearLimit) Text("Almost at your limit.", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    OutlinedButton(onClick = vm::signOut) { Text("Sign out") }
                }

                else -> {
                    val code = state.deviceCode
                    if (code != null) {
                        Text("Enter this code on OpenAI's page:")
                        Text(code.userCode, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 28.sp)
                        Text(
                            "Only continue if you started this sign-in yourself. Never enter a code someone sent you.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { CustomTabLauncher(activity).open(code.url) }) { Text("Open OpenAI's page") }
                            TextButton(onClick = vm::cancelSignIn) { Text("Cancel") }
                        }
                    } else if (state.signingIn) {
                        Text("Finish signing in in the browser…")
                        TextButton(onClick = vm::cancelSignIn) { Text("Cancel") }
                    } else {
                        Button(onClick = { vm.signInWithBrowser(activity) }) { Text("Sign in with ChatGPT") }
                        TextButton(onClick = vm::signInWithCode) { Text("Use a code instead") }
                    }
                }
            }
        }

        BrainCard(
            "Gemma on this phone",
            "Google's open Gemma 4 model running fully on your phone. Free, works offline, nothing leaves the phone.",
        ) {
            val spec = vm.spec
            Text("${spec.displayName} · ${Texts.gb(spec.sizeBytes)} · ${spec.license} · from ${spec.source}", style = MaterialTheme.typography.bodySmall)
            when (val m = model) {
                ModelState.Ready -> {
                    Text("Ready")
                    TextButton(onClick = vm::deleteModel) { Text("Delete model") }
                }

                is ModelState.Downloading -> {
                    Text(m.progress?.let { "Downloading ${(it * 100).toInt()}% (Wi-Fi only)" } ?: "Waiting for Wi-Fi…")
                    m.progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
                    TextButton(onClick = vm::cancelDownload) { Text("Cancel") }
                }

                ModelState.NotEnoughRam -> Text("This phone doesn't have enough memory for this model.")

                is ModelState.Failed -> {
                    Text(if (m.reason == "integrity") "The download failed its safety check and was deleted." else "The download failed.")
                    Button(onClick = startDownload) { Text("Try again") }
                }

                ModelState.NotDownloaded -> {
                    Text("Downloads once over Wi-Fi. Hugging Face sees your IP address during the download.", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = startDownload) { Text("Download (${Texts.gb(spec.sizeBytes)})") }
                }
            }
        }

        BrainCard(
            "Gemini Nano",
            "Google's on-device model, only on some newer phones (Pixel 9/10, Galaxy S26…). Uses Google's ML Kit, " +
                "which sends Google anonymous usage diagnostics, and only answers while this app is open.",
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Use Gemini Nano when available", Modifier.weight(1f))
                Switch(checked = state.nanoOptIn, onCheckedChange = vm::setNanoOptIn)
            }
            state.nanoAvailability?.let { Text(Texts.availability(it), style = MaterialTheme.typography.bodySmall) }
        }
        Text("Phone memory: %.1f GB".format(state.ramGb), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun BrainCard(
    title: String,
    description: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}
