package com.vinhnguyen.watchai.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vinhnguyen.watchai.CustomTabLauncher
import com.vinhnguyen.watchai.brain.chatgpt.auth.AuthState
import com.vinhnguyen.watchai.ondevice.ModelState

/** The AI behind Buddy: the user's ChatGPT account, and the optional models that run on the phone. */
@Composable
fun BrainsScreen(vm: BrainsViewModel) {
    val p = LocalPalette.current
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

    state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = p.textSecondary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) }

    Group("ChatGPT", footer = "Buddy talks and acts with your own ChatGPT plan, through OpenAI's sign-in. Your words go to OpenAI, never to us.") {
        when (val a = auth) {
            is AuthState.SignedIn -> {
                Item(
                    "Signed in",
                    subtitle = listOfNotNull(a.emailMasked, a.planType?.let { "$it plan" }).joinToString(" · ").ifEmpty { null },
                    last = usage?.primaryUsedPercent == null,
                    trailing = { Pill("Sign out", onClick = vm::signOut, filled = false) },
                )
                usage?.primaryUsedPercent?.let { pct ->
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Plan usage, last 5 hours: ${pct.toInt()}%", style = MaterialTheme.typography.bodyMedium, color = if (usage?.nearLimit == true) p.danger else p.textSecondary)
                        LinearProgressIndicator(progress = { (pct / 100).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = p.text, trackColor = p.surfaceHigh)
                    }
                }
            }

            else -> {
                val code = state.deviceCode
                when {
                    code != null ->
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Enter this code on OpenAI's page", style = MaterialTheme.typography.titleMedium, color = p.text)
                            Text(code.userCode, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, color = p.text)
                            Text("Only continue if you started this sign-in yourself. Never enter a code someone sent you.", style = MaterialTheme.typography.bodySmall, color = p.textSecondary)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Pill("Open OpenAI's page", onClick = { CustomTabLauncher(activity).open(code.url) })
                                Pill("Cancel", onClick = vm::cancelSignIn, filled = false)
                            }
                        }

                    state.signingIn -> Item("Finish signing in in the browser…", last = true, trailing = { Pill("Cancel", onClick = vm::cancelSignIn, filled = false) })

                    else -> {
                        Item("Sign in with ChatGPT", subtitle = "Plus or Pro", last = false, trailing = { Pill("Sign in", onClick = { vm.signInWithBrowser(activity) }) })
                        Item("Use a code instead", last = true, onClick = vm::signInWithCode)
                    }
                }
            }
        }
    }

    val spec = vm.spec
    Group(
        "Offline on this phone",
        footer = "${spec.displayName}, ${Texts.gb(spec.sizeBytes)}, ${spec.license}, from ${spec.source}. For chat without a connection; " +
            "Hugging Face sees your IP address during the one download. Talking to Buddy uses ChatGPT either way.",
    ) {
        when (val m = model) {
            ModelState.Ready -> Item("Gemma is ready", subtitle = "Takes ${Texts.gb(spec.sizeBytes)} of storage", last = true, trailing = { Pill("Delete", onClick = vm::deleteModel, filled = false) })

            is ModelState.Downloading ->
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(m.progress?.let { "Downloading ${(it * 100).toInt()}% (Wi-Fi only)" } ?: "Waiting for Wi-Fi…", style = MaterialTheme.typography.titleMedium, color = p.text)
                    m.progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth(), color = p.text, trackColor = p.surfaceHigh) }
                    Pill("Cancel", onClick = vm::cancelDownload, filled = false)
                }

            ModelState.NotEnoughRam -> Item("Not on this phone", subtitle = "It needs more memory than this phone has", last = true)

            is ModelState.Failed ->
                Item(
                    "The download didn't finish",
                    subtitle = if (m.reason == "integrity") "It failed its safety check and was deleted" else null,
                    last = true,
                    trailing = { Pill("Try again", onClick = startDownload) },
                )

            ModelState.NotDownloaded -> Item("Gemma", subtitle = "Optional, ${Texts.gb(spec.sizeBytes)} over Wi-Fi", last = true, trailing = { Pill("Download", onClick = startDownload, filled = false) })
        }
    }

    Group(
        "Gemini Nano",
        footer = "Google's on-device model, only on some newer phones. Google's ML Kit sends Google anonymous usage diagnostics, " +
            "and it only answers while this app is open.",
    ) {
        Item(
            "Use it when available",
            subtitle = state.nanoAvailability?.let { Texts.availability(it) },
            last = true,
            trailing = { Switch(checked = state.nanoOptIn, onCheckedChange = vm::setNanoOptIn) },
        )
    }
}
