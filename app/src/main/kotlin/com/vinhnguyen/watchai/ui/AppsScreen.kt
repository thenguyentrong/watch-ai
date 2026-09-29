package com.vinhnguyen.watchai.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vinhnguyen.watchai.actions.AppLimits
import com.vinhnguyen.watchai.actions.AppLimits.Status
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The apps Buddy may use: every app is off until the user turns it on here. The phone's settings,
 * app stores, password, sign-in code and payment apps can't be turned on at all.
 */
@Composable
fun AppsScreen(limits: AppLimits) {
    val p = LocalPalette.current
    var apps by remember { mutableStateOf<List<AppLimits.App>?>(null) }
    var changed by remember { mutableIntStateOf(0) }
    LaunchedEffect(changed) { apps = withContext(Dispatchers.Default) { limits.apps() } }

    Text(
        "Buddy only uses the apps you turn on here: it opens them, uses them for you and reads their messages. Everything else " +
            "stays off. If you ask for an app that's off, Buddy asks you first. Your phone's settings, app stores, password, sign-in " +
            "code and payment apps are always off.",
        style = MaterialTheme.typography.bodyLarge,
        color = p.textSecondary,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 8.dp),
    )
    val shown = apps
    if (shown == null) {
        Group { Item("Looking at your apps…", last = true) }
        return
    }
    listOf(
        "On" to shown.filter { it.status == Status.ON },
        "Off" to shown.filter { it.status == Status.OFF },
        "Always off" to shown.filter { it.status == Status.ALWAYS_OFF },
    ).forEach { (title, list) ->
        if (list.isEmpty()) return@forEach
        Group(title, footer = if (title == "Always off") "Settings, stores, passwords, sign-in codes and payments, as your phone tells them." else null) {
            list.forEachIndexed { i, app ->
                Item(
                    app.label,
                    last = i == list.lastIndex,
                    leading = { AppIcon(app.packageName) },
                    trailing = {
                        Switch(
                            checked = app.status == Status.ON,
                            enabled = app.status != Status.ALWAYS_OFF,
                            onCheckedChange = {
                                limits.choose(app.packageName, it)
                                changed++
                            },
                            colors = switchColors(),
                        )
                    },
                )
            }
        }
    }
}
