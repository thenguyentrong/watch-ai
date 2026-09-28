package com.vinhnguyen.watchai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.vinhnguyen.watchai.security.protectScreen
import com.vinhnguyen.watchai.ui.AbilitiesViewModel
import com.vinhnguyen.watchai.ui.BenchmarkViewModel
import com.vinhnguyen.watchai.ui.BrainsViewModel
import com.vinhnguyen.watchai.ui.BuddyApp
import com.vinhnguyen.watchai.ui.ChatViewModel
import com.vinhnguyen.watchai.ui.NoticeScreen
import com.vinhnguyen.watchai.ui.Pages
import com.vinhnguyen.watchai.ui.TalkViewModel
import com.vinhnguyen.watchai.ui.VoiceLabViewModel
import com.vinhnguyen.watchai.ui.WatchAiTheme

class MainActivity : ComponentActivity() {
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
                initializer { TalkViewModel(graph) }
                initializer { AbilitiesViewModel(graph) }
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
                val pages =
                    Pages(
                        chat = viewModel(factory = factory),
                        brains = viewModel(factory = factory),
                        abilities = viewModel(factory = factory),
                        benchmark = if (BuildConfig.DEBUG) viewModel<BenchmarkViewModel>(factory = factory) else null,
                        voiceLab = if (BuildConfig.DEBUG) viewModel<VoiceLabViewModel>(factory = factory) else null,
                    )
                BuddyApp(graph, talk = viewModel(factory = factory), pages = pages)
            }
        }
    }
}
