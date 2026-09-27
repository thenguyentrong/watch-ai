package com.vinhnguyen.watchai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vinhnguyen.watchai.AppGraph
import com.vinhnguyen.watchai.buddy.Act
import com.vinhnguyen.watchai.buddy.Genes
import com.vinhnguyen.watchai.buddy.Look
import com.vinhnguyen.watchai.buddy.Mood
import com.vinhnguyen.watchai.buddy.Outfit
import com.vinhnguyen.watchai.buddy.Reaction
import com.vinhnguyen.watchai.buddy.ui.BuddyView
import kotlin.random.Random

/**
 * Debug builds: the user's own Buddy (from their account), and a grid of other Buddies to review
 * the variety and how items sit on different shapes. Tap a Buddy for its next mood.
 */
@Composable
fun BuddyScreen(graph: AppGraph) {
    val seed by produceState<Long?>(null) { value = graph.buddySeed() }
    var batch by rememberSaveable { mutableLongStateOf(1L) }
    val others = remember(batch) { List(12) { Random(batch * 1000 + it).nextLong() } }
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(span = { GridItemSpan(3) }) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                seed?.let { mine ->
                    Tile(Genes.of(mine), Outfit.parse(graph.settings.buddyOutfit), Modifier.size(240.dp))
                    Text("Your Buddy, from your account. Tap for the next mood.", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                }
                TextButton(onClick = { batch++ }) { Text("Shuffle the others") }
            }
        }
        items(others) { other ->
            val random = Random(other)
            val look = Look.entries[random.nextInt(Look.entries.size)]
            Tile(Genes.of(other), look.outfit, Modifier.fillMaxWidth().aspectRatio(1f))
        }
    }
}

@Composable
private fun Tile(
    genes: Genes,
    outfit: Outfit,
    modifier: Modifier,
) {
    var next by remember { mutableIntStateOf(0) }
    val mood = MOODS[next % MOODS.size]
    Box(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .background(Color.Black)
            .clickable { next++ },
    ) {
        BuddyView(
            genes = genes,
            outfit = outfit,
            act = Act.AWAKE,
            reaction = mood?.let { Reaction(it, 0.8f) },
            reactionId = next,
            level = 0f,
            modifier = Modifier.fillMaxSize().padding(8.dp),
        )
    }
}

private val MOODS: List<Mood?> = listOf(null) + Mood.entries.filter { it != Mood.NEUTRAL }
