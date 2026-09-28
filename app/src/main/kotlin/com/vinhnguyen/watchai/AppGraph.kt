package com.vinhnguyen.watchai

import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.vinhnguyen.watchai.actions.CardHub
import com.vinhnguyen.watchai.actions.Contacts
import com.vinhnguyen.watchai.actions.MessageInbox
import com.vinhnguyen.watchai.actions.NoteStore
import com.vinhnguyen.watchai.actions.Pending
import com.vinhnguyen.watchai.actions.PhoneActions
import com.vinhnguyen.watchai.actions.PhoneClock
import com.vinhnguyen.watchai.actions.PhoneControls
import com.vinhnguyen.watchai.actions.PhoneShortcuts
import com.vinhnguyen.watchai.actions.ReachActions
import com.vinhnguyen.watchai.actions.UserTurns
import com.vinhnguyen.watchai.brain.BrainRouter
import com.vinhnguyen.watchai.brain.Toolbox
import com.vinhnguyen.watchai.brain.Toolboxes
import com.vinhnguyen.watchai.brain.chatgpt.ChatGptBrain
import com.vinhnguyen.watchai.brain.chatgpt.ChatGptHttp
import com.vinhnguyen.watchai.brain.chatgpt.ChatGptSettings
import com.vinhnguyen.watchai.brain.chatgpt.auth.AuthSession
import com.vinhnguyen.watchai.brain.chatgpt.auth.ChatGptSignIn
import com.vinhnguyen.watchai.brain.chatgpt.auth.CodexOAuthClient
import com.vinhnguyen.watchai.buddy.Genes
import com.vinhnguyen.watchai.ondevice.AppForeground
import com.vinhnguyen.watchai.ondevice.EngineHolder
import com.vinhnguyen.watchai.ondevice.GeminiNanoBrain
import com.vinhnguyen.watchai.ondevice.GemmaBrain
import com.vinhnguyen.watchai.ondevice.ModelRepository
import com.vinhnguyen.watchai.ondevice.OnDeviceSettings
import com.vinhnguyen.watchai.security.KeystoreVault
import com.vinhnguyen.watchai.security.TimberBrainLogger
import com.vinhnguyen.watchai.watch.WatchCalls
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.UUID

/** Everything the app needs, created once. No DI framework: the graph is small. */
class AppGraph(
    context: Context,
) {
    val appContext: Context = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val logger = TimberBrainLogger()
    val vault = KeystoreVault(appContext, logger)
    val settings = AppSettings(appContext)

    private val oauth = CodexOAuthClient(ChatGptHttp.authClient())
    val session = AuthSession(vault, oauth, logger = logger)
    val signIn = ChatGptSignIn(oauth, session, logger = logger)
    val chatGpt =
        ChatGptBrain(
            session,
            ChatGptHttp.streamClient(),
            settings = ChatGptSettings(diagnostics = BuildConfig.DEBUG),
            logger = logger,
        )

    val onDeviceSettings = OnDeviceSettings(appContext)
    val models = ModelRepository(appContext)
    val engines = EngineHolder(appContext, onDeviceSettings, scope, logger)
    val gemma = GemmaBrain(models, onDeviceSettings, engines, logger)
    val foreground =
        AppForeground {
            ProcessLifecycleOwner
                .get()
                .lifecycle.currentState
                .isAtLeast(Lifecycle.State.STARTED)
        }
    val nano = GeminiNanoBrain(appContext, onDeviceSettings, foreground, logger)

    val router = BrainRouter(listOf(nano, gemma, chatGpt))
    val reports = ReportStore(vault)

    // Notes get their own vault and key: signing out of ChatGPT never touches them.
    private val notesVault = KeystoreVault(appContext, logger, alias = "watchai_notes_master_v1", dirName = "notes")
    val notes = NoteStore(notesVault)
    val controls = PhoneControls(appContext, scope)
    val phoneClock = PhoneClock(appContext) { foreground.isForeground() }

    /** When the user last said or typed something: a message or a call only goes out on their later yes. */
    val userTurns = UserTurns()

    /** The one message or call waiting for a yes, by voice or with the phone's pop-up. */
    val pending = Pending(userTurns)

    /** What the phone's pop-up shows while Buddy works. */
    val cards = CardHub()

    val actions = PhoneActions(appContext, notes, controls, phoneClock, logger = logger, cards = cards)

    /** Messages the user got, from notifications (with their OK); only in memory. */
    val inbox = MessageInbox()
    val contacts = Contacts(appContext)

    val shortcuts = PhoneShortcuts(appContext, logger, { foreground.isForeground() }, cards)

    /** Everything the AI may use in the app (chat and the phone's own voice): the phone's actions, messages and calls, shortcuts. */
    val tools: Toolbox = Toolboxes(listOf(actions, ReachActions(appContext, contacts, inbox, pending, logger, cards = cards), shortcuts))

    /** The user tapped Send, Call or Cancel in the pop-up: that tap is their answer. */
    suspend fun decideOnScreen(yes: Boolean): String {
        userTurns.heard()
        val result = pending.decide(yes)
        cards.show(CardHub.decision(result))
        cards.decidedOnScreen(result)
        return result
    }

    /** This user's Buddy: from their ChatGPT account, or this install until they sign in. */
    suspend fun buddySeed(): Long = Genes.seedFor(runCatching { session.bearer().accountId }.getOrNull() ?: settings.installId)
    val watchCalls = WatchCalls(this)

    /** "Delete everything": tokens, keys, reports, notes, models, settings. */
    suspend fun deleteEverything() {
        runCatching { session.signOut() }
        vault.wipe()
        notesVault.wipe()
        models.specs.forEach { models.delete(it) }
        onDeviceSettings.clearAll()
        settings.clearAll()
        appContext.noBackupFilesDir.resolve("litertlm-cache").deleteRecursively()
    }
}

class AppSettings(
    context: Context,
) {
    private val prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)

    var noticeAccepted: Boolean
        get() = prefs.getBoolean("notice_accepted_v1", false)
        set(value) = prefs.edit { putBoolean("notice_accepted_v1", value) }

    /** Off by default: answers stay on the phone unless the user allows ChatGPT. */
    var cloudAllowed: Boolean
        get() = prefs.getBoolean("cloud_allowed", false)
        set(value) = prefs.edit { putBoolean("cloud_allowed", value) }

    /** A random id for this install, for Buddy until the user signs in. Never sent anywhere. */
    val installId: String
        get() = prefs.getString("install_id", null) ?: UUID.randomUUID().toString().also { id -> prefs.edit { putString("install_id", id) } }

    /** GPT-Live voice, chosen in Settings; watch calls use it too. */
    var voice: String
        get() = prefs.getString("voice", null) ?: "cove"
        set(value) = prefs.edit { putString("voice", value) }

    fun clearAll() = prefs.edit { clear() }
}
