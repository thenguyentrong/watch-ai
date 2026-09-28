package com.vinhnguyen.watchai.ui

import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.vinhnguyen.watchai.AppGraph
import com.vinhnguyen.watchai.BuildConfig
import com.vinhnguyen.watchai.R
import com.vinhnguyen.watchai.brain.chatgpt.auth.AuthState
import com.vinhnguyen.watchai.buddy.Act
import com.vinhnguyen.watchai.buddy.Genes
import com.vinhnguyen.watchai.buddy.ui.BuddyView
import com.vinhnguyen.watchai.buddy.ui.accentOf

/** Everything behind the menu; the last two are in test builds only. */
enum class Page(
    val title: String,
    val forTesting: Boolean = false,
) {
    CHAT("Chat"),
    ABILITIES("What Buddy can do"),
    AI("Your AI"),
    SETTINGS("Settings"),
    VOICE_LAB("Voice lab", forTesting = true),
    BUDDIES("More Buddies", forTesting = true),
}

/** The screens the menu opens. */
class Pages(
    val chat: ChatViewModel,
    val brains: BrainsViewModel,
    val abilities: AbilitiesViewModel,
    val benchmark: BenchmarkViewModel?,
    val voiceLab: VoiceLabViewModel?,
)

/**
 * The phone app, as calm as the watch: Buddy alone on the home screen, and everything else behind
 * one menu, a glass panel that slides over it. A page opens over the home; back returns to Buddy.
 */
@Composable
fun BuddyApp(
    graph: AppGraph,
    talk: TalkViewModel,
    pages: Pages,
) {
    var page by rememberSaveable { mutableStateOf<Page?>(null) }
    var menu by rememberSaveable { mutableStateOf(false) }
    val backdrop = rememberLayerBackdrop()
    val pageBackdrop = rememberLayerBackdrop()
    val seed by produceState<Long?>(null) { value = talk.buddySeed() }
    val genes = remember(seed) { seed?.let { Genes.of(it) } }
    val island by talk.island.collectAsStateWithLifecycle()
    val level by talk.level.collectAsStateWithLifecycle()
    val palette = LocalPalette.current
    val open = { p: Page ->
        page = p
        menu = false
        if (p == Page.AI) pages.brains.refresh()
    }
    BackHandler(menu) { menu = false }
    BackHandler(!menu && page != null) { page = null }
    SystemBars(lightIcons = isSystemInDarkTheme())

    Box(Modifier.fillMaxSize()) {
        when (val p = page) {
            null -> HomeScreen(talk, genes, backdrop, onMenu = { menu = true }, onChat = { open(Page.CHAT) }, onSignIn = { open(Page.AI) })

            Page.CHAT ->
                Page(
                    p.title,
                    onBack = { page = null },
                    scrolls = false,
                    center = { ChatRoutePicker(pages.chat) },
                    action = { glass -> GlassCircle(glass, painterResource(R.drawable.sym_edit_square), "New chat", pages.chat::newConversation) },
                    backdrop = pageBackdrop,
                ) { top -> ChatScreen(pages.chat, top) }

            Page.ABILITIES -> Page(p.title, onBack = { page = null }, backdrop = pageBackdrop) { AbilitiesScreen(pages.abilities) }

            Page.AI -> Page(p.title, onBack = { page = null }, backdrop = pageBackdrop) { BrainsScreen(pages.brains) }

            Page.SETTINGS -> Page(p.title, onBack = { page = null }, backdrop = pageBackdrop) { SettingsScreen(graph.settings, onDeleteEverything = graph::deleteEverything, benchmark = pages.benchmark) }

            Page.VOICE_LAB ->
                Page(p.title, onBack = { page = null }, scrolls = false, backdrop = pageBackdrop) { top ->
                    Spacer(Modifier.height(top))
                    pages.voiceLab?.let { VoiceLabScreen(it) }
                }

            Page.BUDDIES ->
                Page(p.title, onBack = { page = null }, scrolls = false, backdrop = pageBackdrop) { top ->
                    Spacer(Modifier.height(top))
                    BuddyScreen(graph, showOthers = true)
                }
        }

        // Buddy's pop-up floats over every page; away from home it only shows cards, not the conversation.
        BuddyIsland(
            island = if (page == null || island is Island.Showing) island else Island.Hidden,
            backdrop = if (page == null) backdrop else pageBackdrop,
            accent = remember(genes) { genes?.let { accentOf(it) } } ?: palette.textSecondary,
            level = level,
            onDismiss = talk::dismissCard,
            onDecide = talk::decide,
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 10.dp),
        )

        AnimatedVisibility(menu, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.32f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = "Close the menu") { menu = false },
            )
        }
        AnimatedVisibility(menu, enter = slideInHorizontally { -it } + fadeIn(), exit = slideOutHorizontally { -it } + fadeOut()) {
            Menu(talk, genes, backdrop, onOpen = open)
        }
    }
}

@Composable
private fun Menu(
    talk: TalkViewModel,
    genes: Genes?,
    backdrop: Backdrop,
    onOpen: (Page) -> Unit,
) {
    val p = LocalPalette.current
    val auth by talk.auth.collectAsStateWithLifecycle()
    Column(
        Modifier
            .fillMaxHeight()
            .width(312.dp)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(10.dp)
            .glass(backdrop, p.surface.copy(alpha = 0.86f), corner = 30.dp)
            .padding(vertical = 14.dp),
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(p.background)) {
                genes?.let {
                    BuddyView(
                        genes = it,
                        act = Act.REST,
                        reaction = null,
                        reactionId = 0,
                        level = 0f,
                        paper = p.background,
                        eyes = p.eyes,
                        frozenAt = 1.0,
                        modifier = Modifier.fillMaxSize().padding(4.dp),
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text("Buddy", style = MaterialTheme.typography.titleLarge, color = p.text)
                Text(
                    when (auth) {
                        is AuthState.SignedIn -> "Signed in with ChatGPT"
                        AuthState.SignedOut -> "Not signed in yet"
                        AuthState.Unknown -> " "
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = p.textSecondary,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        MenuItem(painterResource(R.drawable.sym_chat_bubble), Page.CHAT.title) { onOpen(Page.CHAT) }
        MenuItem(painterResource(R.drawable.sym_bolt), Page.ABILITIES.title) { onOpen(Page.ABILITIES) }
        MenuItem(painterResource(R.drawable.sym_neurology), Page.AI.title) { onOpen(Page.AI) }
        MenuItem(painterResource(R.drawable.sym_settings), Page.SETTINGS.title) { onOpen(Page.SETTINGS) }
        if (BuildConfig.DEBUG) {
            Spacer(Modifier.height(18.dp))
            Text("For testing", style = MaterialTheme.typography.labelMedium, color = p.textTertiary, modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp))
            MenuItem(painterResource(R.drawable.sym_graphic_eq), Page.VOICE_LAB.title) { onOpen(Page.VOICE_LAB) }
            MenuItem(painterResource(R.drawable.sym_sentiment_satisfied), Page.BUDDIES.title) { onOpen(Page.BUDDIES) }
        }
    }
}

@Composable
private fun MenuItem(
    icon: Painter,
    title: String,
    onClick: () -> Unit,
) {
    val p = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = p.text, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = p.text)
    }
}

/** Light or dark status and navigation bar icons, drawn over the app (edge to edge). */
@Composable
private fun SystemBars(lightIcons: Boolean) {
    val activity = LocalActivity.current as? ComponentActivity ?: return
    DisposableEffect(lightIcons) {
        val transparent = android.graphics.Color.TRANSPARENT
        val style = if (lightIcons) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent)
        activity.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        onDispose { }
    }
}
