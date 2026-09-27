package com.vinhnguyen.watchai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vinhnguyen.watchai.AppGraph
import com.vinhnguyen.watchai.buddy.Act
import com.vinhnguyen.watchai.buddy.Genes
import com.vinhnguyen.watchai.buddy.Mood
import com.vinhnguyen.watchai.buddy.Reaction
import com.vinhnguyen.watchai.buddy.ui.BuddyView
import kotlin.random.Random

/**
 * The phone's home: the user's own Buddy (made from their account) on a round black stage, like
 * the watch. A tap plays its next animation. Debug builds add a grid of other Buddies to review
 * the variety.
 */
@Composable
fun BuddyScreen(
    graph: AppGraph,
    showOthers: Boolean,
) {
    val seed by produceState<Long?>(null) { value = graph.buddySeed() }
    var batch by rememberSaveable { mutableLongStateOf(1L) }
    val others = remember(batch) { List(12) { Random(batch * 1000 + it).nextLong() } }
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(span = { GridItemSpan(3) }) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                seed?.let { mine ->
                    Stage(Genes.of(mine), Modifier.fillMaxWidth(0.82f).widthIn(max = 360.dp).aspectRatio(1f))
                    Spacer(Modifier.height(24.dp))
                    Text("This is your Buddy", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Made from your account, so no one else has this one. Tap it to play.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                    )
                }
                if (showOthers) TextButton(onClick = { batch++ }, modifier = Modifier.padding(top = 16.dp)) { Text("Shuffle the others") }
            }
        }
        if (showOthers) items(others) { other -> Stage(Genes.of(other), Modifier.fillMaxWidth().aspectRatio(1f)) }
    }
}

/** One Buddy on a black disc; each tap plays the next animation. */
@Composable
private fun Stage(
    genes: Genes,
    modifier: Modifier,
) {
    var next by remember { mutableIntStateOf(0) }
    val mood = MOODS[next % MOODS.size]
    Box(
        modifier
            .clip(CircleShape)
            .background(Color.Black)
            .clickable(onClickLabel = "Play the next animation", role = Role.Button) { next++ },
    ) {
        BuddyView(
            genes = genes,
            act = Act.REST,
            reaction = mood?.let { Reaction(it, 0.8f) },
            reactionId = next,
            level = 0f,
            paper = Color.Black,
            modifier = Modifier.fillMaxSize().padding(8.dp),
        )
    }
}

private val MOODS: List<Mood?> = listOf(null) + Mood.entries.filter { it != Mood.NEUTRAL }
