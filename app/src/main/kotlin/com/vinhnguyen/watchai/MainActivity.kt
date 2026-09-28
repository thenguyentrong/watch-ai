package com.vinhnguyen.watchai

import android.content.Intent
import android.os.Bundle
import android.provider.Telephony
import android.telecom.TelecomManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.vinhnguyen.watchai.actions.BuddyCard
import com.vinhnguyen.watchai.actions.Symbol
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
                    NoticeScreen(onAccept = {
                        graph.settings.noticeAccepted = true
                        accepted = true
                    })
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
        if (BuildConfig.DEBUG) showTestCard(intent, afterMillis = 1_500)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (BuildConfig.DEBUG) showTestCard(intent, afterMillis = 300)
    }

    /**
     * Test builds only: shows a made-up pop-up, to look at every kind without sending anything, e.g.
     * `adb shell am start -n com.vinhnguyen.watchai/.MainActivity --es card place`.
     */
    private fun showTestCard(
        intent: Intent,
        afterMillis: Long,
    ) {
        val sms = runCatching { Telephony.Sms.getDefaultSmsPackage(this) }.getOrNull() ?: packageName
        val card =
            when (intent.getStringExtra("card")) {
                "timer" -> BuddyCard.Done(Symbol.TIMER, "Timer · 10 minutes", "Pasta")

                "note" -> BuddyCard.Done(Symbol.NOTE, "Note saved", "Buy oat milk and batteries")

                "app" -> BuddyCard.App("com.android.chrome", "Opened Chrome")

                "text" -> BuddyCard.Ask("Text Alex", "Running ten minutes late, see you at the station", "Send", sms)

                "call" -> BuddyCard.Ask("Call Alex", "Mobile", "Call", getSystemService(TelecomManager::class.java).defaultDialerPackage)

                "place" -> BuddyCard.Place("Aachen Hauptbahnhof")

                "messages" ->
                    BuddyCard.Messages(
                        listOf(
                            BuddyCard.Messages.Line(sms, "Sam", "Are we still on for dinner tonight?"),
                            BuddyCard.Messages.Line(sms, "Alex", "On my way, ten minutes"),
                        ),
                    )

                else -> null
            } ?: return
        val graph = (application as WatchAiApp).graph
        lifecycleScope.launch {
            delay(afterMillis)
            graph.cards.show(card)
        }
    }
}
