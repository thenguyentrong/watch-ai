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
import com.vinhnguyen.watchai.ondevice.ModelSpec
import com.vinhnguyen.watchai.ondevice.ModelState

/** The AI behind Buddy: the user's ChatGPT account, and the optional models that run on the phone. */
@Composable
fun BrainsScreen(vm: BrainsViewModel) {
    val p = LocalPalette.current
    val state by vm.state.collectAsStateWithLifecycle()
    val auth by vm.auth.collectAsStateWithLifecycle()
    val usage by vm.usage.collectAsStateWithLifecycle()
    val activity = LocalActivity.current ?: return
    // The download shows a progress notification; Android 13+ needs permission for it (the download runs either way).
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val askNotifications = {
        val needsAsk =
            Build.VERSION.SDK_INT >= 33 &&
                activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsAsk) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = p.textSecondary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) }

    Group("ChatGPT", footer = "Buddy talks and acts with your own ChatGPT plan, through OpenAI's sign-in. Your words go to OpenAI, never to us.") {
        when (val a = auth) {
            is AuthState.SignedIn -> {
                Item(
                    "Signed in",
                    subtitle = listOfNotNull(a.emailMasked, a.planType?.let { "${planName(it)} plan" }).joinToString(" · ").ifEmpty { null },
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

    val states by vm.modelStates.collectAsStateWithLifecycle()
    val inUse by vm.inUse.collectAsStateWithLifecycle()
    Group(
        "Offline on this phone",
        footer = "Gemma reads your messages, notes and calendar on the phone (Settings, Privacy) and chats without a connection. " +
            "A bigger model answers better but is slower and needs more memory. From Hugging Face (litert-community), Apache-2.0; " +
            "they see your IP address during the one download, Wi-Fi only.",
    ) {
        vm.models.forEachIndexed { i, spec ->
            ModelRow(
                spec = spec,
                state = states[spec.id] ?: ModelState.NotDownloaded,
                inUse = spec.id == inUse,
                recommended = spec.id == vm.recommended,
                ramGb = state.ramGb,
                last = i == vm.models.lastIndex,
                onDownload = {
                    askNotifications()
                    vm.download(spec)
                },
                onCancel = { vm.cancelDownload(spec) },
                onUse = { vm.use(spec) },
                onDelete = { vm.delete(spec) },
            )
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
            trailing = { Switch(checked = state.nanoOptIn, onCheckedChange = vm::setNanoOptIn, colors = switchColors()) },
        )
    }
}

/** ChatGPT's plan id ("plus", "prolite") as its name. */
internal fun planName(id: String): String = PLAN_NAMES[id.lowercase()] ?: id.replaceFirstChar { it.uppercase() }

private val PLAN_NAMES =
    mapOf(
        "free" to "Free",
        "go" to "Go",
        "plus" to "Plus",
        "pro" to "Pro",
        "prolite" to "Pro Lite",
        "team" to "Team",
        "business" to "Business",
        "enterprise" to "Enterprise",
        "edu" to "Edu",
    )

/** One offline model: what it is, whether it fits this phone, and what can be done with it. */
@Composable
private fun ModelRow(
    spec: ModelSpec,
    state: ModelState,
    inUse: Boolean,
    recommended: Boolean,
    ramGb: Double,
    last: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onUse: () -> Unit,
    onDelete: () -> Unit,
) {
    val p = LocalPalette.current
    val about =
        listOfNotNull(
            Texts.gb(spec.sizeBytes),
            if (spec.minRamGb >= BIG_MODEL_RAM_GB) "better answers, slower" else "quicker",
            "needs ${spec.minRamGb} GB memory",
            "best for this phone".takeIf { recommended },
        ).joinToString(" · ")
    when (state) {
        ModelState.Ready ->
            Item(
                spec.displayName + if (inUse) " · in use" else "",
                subtitle = about,
                last = last,
                trailing = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!inUse) Pill("Use", onClick = onUse)
                        Pill("Delete", onClick = onDelete, filled = false)
                    }
                },
            )

        is ModelState.Downloading ->
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(spec.displayName, style = MaterialTheme.typography.titleMedium, color = p.text)
                Text(state.progress?.let { "Downloading ${(it * 100).toInt()}% (Wi-Fi only)" } ?: "Waiting for Wi-Fi…", style = MaterialTheme.typography.bodyMedium, color = p.textSecondary)
                state.progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth(), color = p.text, trackColor = p.surfaceHigh) }
                Pill("Cancel", onClick = onCancel, filled = false)
            }

        ModelState.NotEnoughRam ->
            Item(spec.displayName, subtitle = "Not for this phone: it needs ${spec.minRamGb} GB of memory, this one has %.0f".format(ramGb), last = last)

        is ModelState.Failed ->
            Item(
                spec.displayName,
                subtitle = if (state.reason == "integrity") "The download failed its safety check and was deleted" else "The download didn't finish",
                last = last,
                trailing = { Pill("Try again", onClick = onDownload) },
            )

        ModelState.NotDownloaded ->
            Item(
                spec.displayName,
                subtitle = about,
                last = last,
                trailing = {
                    Pill("Download", onClick = {
                        onDownload()
                        // The first one downloaded is the one used.
                        if (!inUse) onUse()
                    }, filled = !inUse && recommended)
                },
            )
    }
}

/** Models needing this much memory are the bigger, better ones. */
private const val BIG_MODEL_RAM_GB = 10
