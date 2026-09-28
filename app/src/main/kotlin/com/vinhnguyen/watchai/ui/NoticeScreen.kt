package com.vinhnguyen.watchai.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.vinhnguyen.watchai.R

/** First run: users are told they're talking to an AI and where their words go (EU AI Act Art. 50, GDPR). */
@Composable
fun NoticeScreen(onAccept: () -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxSize().background(p.background).safeDrawingPadding()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Spacer(Modifier.height(32.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Before you start", style = MaterialTheme.typography.displaySmall, color = p.text)
                Text("Buddy is an AI assistant. It answers and acts with AI models, and it can be wrong.", style = MaterialTheme.typography.bodyLarge, color = p.textSecondary)
            }
            Section(
                R.drawable.sym_neurology,
                "Your words go to your own ChatGPT",
                "When you talk to Buddy, on your watch or this phone, or chat with it, your words go from this phone to " +
                    "OpenAI under your own ChatGPT account and plan. We never see them. OpenAI's terms and your ChatGPT data " +
                    "settings apply.",
            )
            Section(
                R.drawable.sym_check_circle,
                "It asks before it acts",
                "Messages and calls are read back to you and only go out after your yes. Reading your messages, contacts or " +
                    "calendar, and opening apps, each need your OK first, and you can take it back any time.",
            )
            Section(
                R.drawable.sym_graphic_eq,
                "\"Hey Buddy\" stays on the watch",
                "The watch listens for it only for a short while after you raise your wrist, or all the time if you switch " +
                    "that on. Nothing is recorded or sent until it hears the phrase.",
            )
            Section(
                R.drawable.sym_lock,
                "What we keep",
                "Your ChatGPT sign-in, encrypted with a key that never leaves this phone, and your notes. Conversations " +
                    "aren't stored. No analytics, no ads. Settings, Delete everything removes it all.",
            )
            Spacer(Modifier.height(8.dp))
        }
        Box(
            Modifier
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .fillMaxWidth()
                .height(54.dp)
                .clip(CircleShape)
                .background(p.text)
                .clickable(role = Role.Button, onClick = onAccept),
            contentAlignment = Alignment.Center,
        ) {
            Text("Start", style = MaterialTheme.typography.titleMedium, color = p.background)
        }
    }
}

@Composable
private fun Section(
    @DrawableRes icon: Int,
    title: String,
    body: String,
) {
    val p = LocalPalette.current
    Row {
        Icon(painterResource(icon), contentDescription = null, tint = p.text, modifier = Modifier.padding(top = 2.dp).size(26.dp))
        Spacer(Modifier.width(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = p.text)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = p.textSecondary)
        }
    }
}
