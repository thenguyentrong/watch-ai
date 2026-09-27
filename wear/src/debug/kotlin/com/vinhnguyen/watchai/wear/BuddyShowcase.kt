package com.vinhnguyen.watchai.wear

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.vinhnguyen.watchai.buddy.Act
import com.vinhnguyen.watchai.buddy.Genes
import com.vinhnguyen.watchai.buddy.Mood
import com.vinhnguyen.watchai.buddy.Reaction
import com.vinhnguyen.watchai.buddy.ui.BuddyView

/**
 * Debug builds only: one Buddy, frozen at a moment, for screenshots. Every extra is optional:
 *
 *   adb shell am start -n com.vinhnguyen.watchai/com.vinhnguyen.watchai.wear.BuddyShowcase \
 *     --el seed 42 --es mood happy --es act speak --ef t 0.4 --ef level 0.5 [--ez live true --ei fps 10]
 */
class BuddyShowcase : ComponentActivity() {
    private var shown by mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        shown = intent
        setContent {
            val extras = shown ?: return@setContent
            val seed = extras.getLongExtra("seed", 42L)
            val mood = Mood.of(extras.getStringExtra("mood"))
            val act = Act.entries.firstOrNull { it.name.equals(extras.getStringExtra("act"), ignoreCase = true) } ?: Act.AWAKE
            val live = extras.getBooleanExtra("live", false)
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                BuddyView(
                    genes = Genes.of(seed),
                    act = act,
                    reaction = mood?.let { Reaction(it, extras.getFloatExtra("intensity", 0.8f)) },
                    reactionId = extras.hashCode(),
                    level = extras.getFloatExtra("level", 0f),
                    paper = Color.Black,
                    frozenAt = if (live) null else extras.getFloatExtra("t", 0.4f).toDouble(),
                    fps = extras.getIntExtra("fps", 60),
                    busyFps = extras.getIntExtra("busy", extras.getIntExtra("fps", 60)),
                    modifier = Modifier.fillMaxWidth(0.8f).fillMaxHeight(0.8f),
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        shown = intent
    }
}
