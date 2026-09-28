package com.vinhnguyen.watchai.ui

import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vinhnguyen.watchai.AppGraph
import com.vinhnguyen.watchai.BuildConfig
import com.vinhnguyen.watchai.brain.chatgpt.auth.AuthState
import com.vinhnguyen.watchai.buddy.Act
import com.vinhnguyen.watchai.buddy.Genes
import com.vinhnguyen.watchai.buddy.ui.BuddyView
import kotlinx.coroutines.launch

/** Everything behind the menu; the last two are in test builds only. */
enum class Page(
    val title: String,
    val icon: ImageVector,
    val forTesting: Boolean = false,
) {
    CHAT("Chat", Icons.AutoMirrored.Filled.Send),
    ABILITIES("What Buddy can do", Icons.Filled.Build),
    AI("Your AI", Icons.Filled.AccountCircle),
    SETTINGS("Settings", Icons.Filled.Settings),
    VOICE_LAB("Voice lab", Icons.Filled.Call, forTesting = true),
    BUDDIES("More Buddies", Icons.Filled.Face, forTesting = true),
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
 * The phone app, as clean as the watch: Buddy alone on the home screen, and everything else behind
 * one menu (top left, like ChatGPT's). A page opens over the home and back returns to Buddy.
 */
@Composable
fun BuddyApp(
    graph: AppGraph,
    talk: TalkViewModel,
    pages: Pages,
) {
    var page by rememberSaveable { mutableStateOf<Page?>(null) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val open = { p: Page ->
        page = p
        if (p == Page.AI) pages.brains.refresh()
        scope.launch { drawer.close() }
        Unit
    }
    BackHandler(page != null) { page = null }
    BackHandler(page == null && drawer.isOpen) { scope.launch { drawer.close() } }
    // Buddy's black stage wants light status bar icons whatever the theme; the open menu doesn't.
    SystemBars(lightIcons = isSystemInDarkTheme() || (page == null && drawer.targetValue == DrawerValue.Closed))

    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = page == null,
        drawerContent = { Menu(talk, onOpen = open) },
    ) {
        when (val p = page) {
            null -> HomeScreen(talk, onMenu = { scope.launch { drawer.open() } }, onSignIn = { open(Page.AI) })

            else ->
                PageScaffold(p.title, onBack = { page = null }) {
                    when (p) {
                        Page.CHAT -> ChatScreen(pages.chat)
                        Page.ABILITIES -> AbilitiesScreen(pages.abilities)
                        Page.AI -> BrainsScreen(pages.brains)
                        Page.SETTINGS -> SettingsScreen(graph.settings, onDeleteEverything = graph::deleteEverything, benchmark = pages.benchmark)
                        Page.VOICE_LAB -> pages.voiceLab?.let { VoiceLabScreen(it) }
                        Page.BUDDIES -> BuddyScreen(graph, showOthers = true)
                    }
                }
        }
    }
}

@Composable
private fun Menu(
    talk: TalkViewModel,
    onOpen: (Page) -> Unit,
) {
    val auth by talk.auth.collectAsStateWithLifecycle()
    val seed by produceState<Long?>(null) { value = talk.buddySeed() }
    ModalDrawerSheet {
        Row(Modifier.padding(horizontal = 24.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clip(CircleShape).background(Color.Black)) {
                seed?.let {
                    BuddyView(
                        genes = remember(it) { Genes.of(it) },
                        act = Act.REST,
                        reaction = null,
                        reactionId = 0,
                        level = 0f,
                        paper = Color.Black,
                        frozenAt = 1.0,
                        modifier = Modifier.fillMaxSize().padding(6.dp),
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text("Buddy", style = MaterialTheme.typography.titleLarge)
                Text(
                    when (val a = auth) {
                        is AuthState.SignedIn -> "Signed in with ChatGPT"
                        AuthState.SignedOut -> "Not signed in yet"
                        AuthState.Unknown -> ""
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Page.entries.filter { !it.forTesting || BuildConfig.DEBUG }.forEach { p ->
            if (p.forTesting && p == Page.entries.first { it.forTesting }) {
                HorizontalDivider(Modifier.padding(horizontal = 28.dp, vertical = 12.dp))
                Text(
                    "For testing",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 4.dp),
                )
            }
            NavigationDrawerItem(
                label = { Text(p.title) },
                icon = { Icon(p.icon, contentDescription = null) },
                selected = false,
                onClick = { onOpen(p) },
                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PageScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).consumeWindowInsets(padding)) { content() }
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
