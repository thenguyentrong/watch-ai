package com.vinhnguyen.watchai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** First run: users are told they're talking to an AI and where their words go (EU AI Act Art. 50, GDPR). */
@Composable
fun NoticeScreen(onAccept: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Before you start", style = MaterialTheme.typography.headlineMedium)
        Text("Buddy is an AI assistant. It answers and acts with AI models, and it can be wrong.")
        Section(
            "Your words go to your own ChatGPT",
            "When you talk to Buddy, on your watch or this phone, or chat with it, your words go from this phone to " +
                "OpenAI under your own ChatGPT account and plan. We never see them. OpenAI's terms and your ChatGPT data " +
                "settings apply.",
        )
        Section(
            "It asks before it acts",
            "Messages and calls are read back to you and only go out after your yes. Reading your messages, contacts or " +
                "calendar, and opening apps, each need your OK first, and you can take it back any time.",
        )
        Section(
            "\"Hey Buddy\" stays on the watch",
            "The watch listens for it only for a short while after you raise your wrist, or all the time if you switch " +
                "that on. Nothing is recorded or sent until it hears the phrase.",
        )
        Section(
            "What we keep",
            "Your ChatGPT sign-in, encrypted with a key that never leaves this phone, and your notes. Conversations " +
                "aren't stored. No analytics, no ads. Settings, Delete everything removes it all.",
        )
        Button(onClick = onAccept, modifier = Modifier.fillMaxWidth()) { Text("Start") }
    }
}

@Composable
private fun Section(
    title: String,
    body: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodyMedium)
    }
}
