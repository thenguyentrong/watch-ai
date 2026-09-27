package com.vinhnguyen.watchai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.vinhnguyen.watchai.security.protectScreen
import com.vinhnguyen.watchai.ui.BenchmarkViewModel
import com.vinhnguyen.watchai.ui.BrainsScreen
import com.vinhnguyen.watchai.ui.BrainsViewModel
import com.vinhnguyen.watchai.ui.ChatScreen
import com.vinhnguyen.watchai.ui.ChatViewModel
import com.vinhnguyen.watchai.ui.NoticeScreen
import com.vinhnguyen.watchai.ui.SettingsScreen
import com.vinhnguyen.watchai.ui.VoiceLabScreen
import com.vinhnguyen.watchai.ui.VoiceLabViewModel
import com.vinhnguyen.watchai.ui.WatchAiTheme

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Answers, codes and account details: no screenshots or recents thumbnails in release builds.
        protectScreen(!BuildConfig.DEBUG)
        val graph = (application as WatchAiApp).graph
        val factory =
            viewModelFactory {
                initializer { ChatViewModel(graph) }
                initializer { BrainsViewModel(graph) }
                initializer { BenchmarkViewModel(graph) }
                initializer { VoiceLabViewModel(graph) }
            }

        setContent {
            WatchAiTheme {
                var accepted by rememberSaveable { mutableStateOf(graph.settings.noticeAccepted) }
                if (!accepted) {
                    Scaffold { padding ->
                        Box(Modifier.padding(padding)) {
                            NoticeScreen(onAccept = {
                                graph.settings.noticeAccepted = true
                                accepted = true
                            })
                        }
                    }
                    return@WatchAiTheme
                }
                val chat: ChatViewModel = viewModel(factory = factory)
                val brains: BrainsViewModel = viewModel(factory = factory)
                val benchmark: BenchmarkViewModel? = if (BuildConfig.DEBUG) viewModel(factory = factory) else null
                val voiceLab: VoiceLabViewModel? = if (BuildConfig.DEBUG) viewModel(factory = factory) else null
                var tab by rememberSaveable { mutableIntStateOf(0) }
                val tabs = listOf("Chat", "Brains", "Settings") + if (voiceLab != null) listOf("Voice") else emptyList()
                Scaffold(
                    topBar = { TopAppBar(title = { Text("Watch AI") }) },
                    bottomBar = {
                        NavigationBar {
                            tabs.forEachIndexed { index, label ->
                                NavigationBarItem(
                                    selected = tab == index,
                                    onClick = {
                                        tab = index
                                        if (index == 1) brains.refresh()
                                    },
                                    icon = { Text(label.take(1)) },
                                    label = { Text(label) },
                                )
                            }
                        }
                    },
                ) { padding ->
                    Box(Modifier.padding(padding)) {
                        when (tab) {
                            0 -> ChatScreen(chat)
                            1 -> BrainsScreen(brains)
                            3 -> voiceLab?.let { VoiceLabScreen(it) }
                            else -> SettingsScreen(onDeleteEverything = graph::deleteEverything, benchmark = benchmark)
                        }
                    }
                }
            }
        }
    }
}
