package com.vinhnguyen.watchai.watch

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.google.android.gms.wearable.ChannelClient
import com.vinhnguyen.watchai.AppGraph
import com.vinhnguyen.watchai.actions.ConversationActions
import com.vinhnguyen.watchai.actions.PhoneActions
import com.vinhnguyen.watchai.actions.ReachActions
import com.vinhnguyen.watchai.brain.Toolboxes
import com.vinhnguyen.watchai.brain.chatgpt.ChatGptHttp
import com.vinhnguyen.watchai.buddy.Mood
import com.vinhnguyen.watchai.buddy.MoodReader
import com.vinhnguyen.watchai.buddy.Reaction
import com.vinhnguyen.watchai.voice.ChatGptRealtimeSession
import com.vinhnguyen.watchai.voice.VoiceState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Where a watch conversation's audio goes. */
enum class Route {
    /** The watch is microphone and speaker. */
    WATCH,

    /** Headphones without a mic on the phone play the answer; the watch is the microphone. */
    HEADPHONES,

    /** A headset on the phone (e.g. earbuds) is microphone and speaker; the watch only shows the face. */
    HEADSET,
}

/**
 * The phone side of talking from the watch, whether the phone app is open or not: one call at a
 * time, ChatGPT voice, audio routed to the best place ([Route]), and hung up after a quiet spell,
 * like a smart speaker that goes back to sleep.
 */
class WatchCalls(
    private val graph: AppGraph,
) {
    data class Call(
        val watch: String,
        val route: Route,
        val voice: VoiceState,
    )

    private val _call = MutableStateFlow<Call?>(null)
    val call: StateFlow<Call?> = _call.asStateFlow()

    private val lock = Mutex()
    private val bridge = WatchBridge(graph.appContext)
    private var session: ChatGptRealtimeSession? = null
    private var audio: WatchAudio? = null
    private var channel: ChannelClient.Channel? = null
    private var watcher: Job? = null
    private var phoneAudio: PhoneAudio? = null
    private var onEnded: (() -> Unit)? = null

    suspend fun answer(
        opened: ChannelClient.Channel,
        ended: () -> Unit,
    ) {
        lock.withLock {
            hangUpLocked()
            onEnded = ended
            val phone = PhoneAudio(graph.appContext).also { phoneAudio = it }
            var route = phone.route()
            val watch = bridge.answer(opened, route) { graph.scope.launch { hangUp() } }
            phone.onChange {
                // A headset or headphones going away hands the call to the watch; one arriving mid-call doesn't take it.
                val next =
                    when (route) {
                        Route.HEADSET -> if (phone.headset()) {
                            Route.HEADSET
                        } else if (phone.headphones()) {
                            Route.HEADPHONES
                        } else {
                            Route.WATCH
                        }

                        Route.HEADPHONES -> if (phone.headphones()) Route.HEADPHONES else Route.WATCH

                        Route.WATCH -> Route.WATCH
                    }
                if (next != route) {
                    route = next
                    watch.setRoute(next)
                }
            }
            watch.mascot(seed = graph.buddySeed())
            val tools =
                Toolboxes(
                    listOf(
                        // Timers and alarms go to the watch unless the user asks for the phone: it's on the wrist, and on screen.
                        PhoneActions(graph.appContext, graph.notes, graph.controls, graph.phoneClock, WatchOnCall(watch), graph.logger),
                        // A call started from here takes over the phone's audio: Buddy makes way.
                        ReachActions(graph.appContext, graph.contacts, graph.inbox, graph.userTurns, graph.logger, onCalling = { session?.endSoon() }),
                        graph.shortcuts,
                        ConversationActions({ session?.endSoon() }, graph.logger),
                    ),
                ) { name, result -> afterTool(watch, name, result) }
            val s =
                ChatGptRealtimeSession(
                    graph.appContext,
                    graph.session,
                    ChatGptHttp.authClient(),
                    graph.chatGpt,
                    tools,
                    graph.logger,
                    voice = graph.settings.voice,
                    external = watch,
                    idleHangUpMs = IDLE_HANG_UP_MS,
                    onIdle = { graph.scope.launch { hangUp() } },
                    onUserWords = graph.userTurns::heard,
                )
            session = s
            audio = watch
            channel = opened
            watcher =
                graph.scope.launch {
                    val face = BuddyFace(watch)
                    s.state.collect { vs ->
                        _call.value = Call(watch.name, watch.route, vs)
                        face.follow(vs)
                    }
                }
            s.start()
        }
    }

    /** Debug builds: plays [pcm] into the current call as if said on the watch. False if there's no call. */
    fun say(pcm: ShortArray): Boolean = audio?.say(pcm) != null

    /** Buddy after an action: proud when it's done, an oops when it failed, the play triangle for music; reading things shows nothing. */
    private fun afterTool(
        watch: WatchAudio,
        name: String,
        result: String,
    ) {
        // Reading things shows nothing, and neither does something only proposed (it waits for the user's yes).
        if (name in QUIET_TOOLS || result.startsWith("not done yet")) return
        val reaction =
            when {
                result.startsWith("error") -> Reaction(Mood.OOPS, 0.7f)
                name == PhoneActions.MEDIA && result.startsWith("ok: pressed play") -> Reaction(Mood.PLAY, 0.7f)
                else -> Reaction(Mood.PROUD, 0.8f)
            }
        watch.mascot(reaction = reaction)
    }

    /**
     * Buddy's face follows the words: the user's thanks and greetings, the answer's tone. Each mood
     * plays once per turn; a new answer can play the same mood again.
     */
    private class BuddyFace(
        private val watch: WatchAudio,
    ) {
        private var heard: String? = null
        private var said: String? = null
        private var shown: Mood? = null

        fun follow(state: VoiceState) {
            state.lastUserText?.takeIf { it != heard }?.let { text ->
                if (!continues(heard, text)) shown = null
                heard = text
                show(MoodReader.user(text))
            }
            state.lastAssistantText?.takeIf { it != said }?.let { text ->
                if (!continues(said, text)) shown = null
                said = text
                show(MoodReader.assistant(text))
            }
        }

        /** The same turn still being written, rather than a new one. */
        private fun continues(
            before: String?,
            now: String,
        ) = before != null && now.startsWith(before.take(TURN_PREFIX))

        private fun show(reaction: Reaction?) {
            if (reaction == null || reaction.mood == shown) return
            shown = reaction.mood
            watch.mascot(reaction = reaction)
        }
    }

    suspend fun hangUp() {
        lock.withLock { hangUpLocked() }
    }

    private suspend fun hangUpLocked() {
        val s = session ?: return
        session = null
        s.stop()
        watcher?.cancel()
        watcher = null
        phoneAudio?.stop()
        phoneAudio = null
        audio?.close()
        audio = null
        channel?.let { bridge.hangUp(it) }
        channel = null
        _call.value = null
        onEnded?.invoke()
        onEnded = null
    }

    /**
     * What's plugged into or connected to the phone. The watch never counts: Android didn't offer it
     * to apps on the S23 Ultra (27.09), and if a watch ever shows up as a headset it's skipped by name.
     */
    private class PhoneAudio(
        context: Context,
    ) {
        private val audioManager = context.getSystemService(AudioManager::class.java)
        private var callback: AudioDeviceCallback? = null

        /** A microphone and speaker for calls: Bluetooth or wired headset. */
        fun headset(): Boolean = audioManager.availableCommunicationDevices.any { it.type in HEADSET_TYPES && !it.isWatch() }

        fun headphones(): Boolean = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in HEADPHONE_TYPES && !it.isWatch() }

        private fun AudioDeviceInfo.isWatch() = productName?.contains("watch", ignoreCase = true) == true

        fun route(): Route = when {
            headset() -> Route.HEADSET
            headphones() -> Route.HEADPHONES
            else -> Route.WATCH
        }

        fun onChange(listener: () -> Unit) {
            val cb =
                object : AudioDeviceCallback() {
                    override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = listener()

                    override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = listener()
                }
            callback = cb
            audioManager.registerAudioDeviceCallback(cb, null)
        }

        fun stop() {
            callback?.let { audioManager.unregisterAudioDeviceCallback(it) }
            callback = null
        }

        private companion object {
            val HEADSET_TYPES =
                setOf(
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    AudioDeviceInfo.TYPE_BLE_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_USB_HEADSET,
                )
            val HEADPHONE_TYPES =
                setOf(
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_BLE_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AudioDeviceInfo.TYPE_USB_HEADSET,
                )
        }
    }

    private companion object {
        /** Like a smart speaker: back to sleep after 10 s with nobody talking. */
        const val IDLE_HANG_UP_MS = 10_000L
        const val TURN_PREFIX = 12
        val QUIET_TOOLS = setOf(PhoneActions.LIST_NOTES, PhoneActions.LIST_EVENTS, PhoneActions.DEVICE_STATUS, ReachActions.READ_MESSAGES, ConversationActions.END)
    }
}
