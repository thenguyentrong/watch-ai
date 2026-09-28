package com.vinhnguyen.watchai.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaRecorder
import android.os.SystemClock
import com.vinhnguyen.watchai.brain.Brain
import com.vinhnguyen.watchai.brain.BrainLogger
import com.vinhnguyen.watchai.brain.ChatEvent
import com.vinhnguyen.watchai.brain.ChatRequest
import com.vinhnguyen.watchai.brain.ChatTurn
import com.vinhnguyen.watchai.brain.DeviceContext
import com.vinhnguyen.watchai.brain.LogEvent
import com.vinhnguyen.watchai.brain.ReplyStyle
import com.vinhnguyen.watchai.brain.Toolbox
import com.vinhnguyen.watchai.brain.chatgpt.auth.AuthSession
import com.vinhnguyen.watchai.brain.chatgpt.auth.Bearer
import com.vinhnguyen.watchai.brain.chatgpt.auth.OpenAiAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.coroutines.executeAsync
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Speech-to-speech with OpenAI's GPT-Live voice model on the user's own ChatGPT plan, the way
 * OpenClaw does it (merged 2026-07-29): WebRTC offer + session settings POSTed to the Codex
 * realtime endpoint with the ChatGPT sign-in; audio then flows directly between the phone and
 * OpenAI (DTLS-SRTP). The model handles turn-taking and interruptions itself.
 *
 * Every WebRTC object is created, used and disposed on one thread, and [stop] waits for the
 * connect step to finish before disposing, so a Stop tap while connecting can't touch a disposed
 * connection (that crashed natively on 27.09).
 *
 * The voice model answers small talk itself and hands anything that needs facts, current
 * information or the exact time to the client (a delegation). [consult] answers it and the result
 * goes back on the data channel for the voice to say. Unanswered, the voice just waits (27.09).
 *
 * Undocumented endpoint: this is a spike to see whether it works from a phone, not a contract.
 */
class ChatGptRealtimeSession(
    context: Context,
    private val auth: AuthSession,
    private val http: OkHttpClient,
    /** Answers what the voice model hands off (delegations): ChatGPT on the same plan, with web search. */
    private val consult: Brain,
    /** Phone actions the consult may take (notes, calendar, timers…). */
    private val tools: Toolbox? = null,
    private val logger: BrainLogger = BrainLogger.None,
    private val voice: String = "cove",
    /** When set (e.g. the watch), the user's voice comes from here and the answer plays there instead of on the phone. */
    private val external: ExternalAudio? = null,
    /** Hang up after this long without words from the user, an answer or a look-up (like a smart speaker); null = never. */
    private val idleHangUpMs: Long? = null,
    private val onIdle: () -> Unit = {},
    private val instructions: String = VOICE_INSTRUCTIONS,
) : VoiceSession {
    override val name: String = "ChatGPT voice (GPT-Live)"

    private val appContext = context.applicationContext
    private val audio = CallAudio(appContext)
    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow(VoiceState())
    override val state: StateFlow<VoiceState> = _state.asStateFlow()
    private val _level = MutableStateFlow(0f)
    override val level: StateFlow<Float> = _level.asStateFlow()

    private val lifecycle = Mutex()
    private var scope: CoroutineScope? = null
    private val conversationId = UUID.randomUUID().toString()
    private val transcript = mutableListOf<ChatTurn>()
    private var consultJob: Job? = null
    private var userTurnOpen = false
    private var assistantTurnOpen = false

    // Only touched on the RTC thread.
    private var factory: PeerConnectionFactory? = null
    private var adm: JavaAudioDeviceModule? = null
    private var pc: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var events: DataChannel? = null
    private var iceComplete = CompletableDeferred<Unit>()

    @Volatile private var responsePending = false

    /** The user is talking over the answer: it is neither played nor forwarded. */
    @Volatile private var answerMuted = false

    /** RTC thread only: what the phone's own speaker was last set to. */
    private var phoneSpeakerMuted = false

    /** RTC thread only: whether the phone is in call audio mode (its own mic, headset or loudspeaker). */
    private var callAudioOn = false

    /**
     * When the conversation last moved, for [idleHangUpMs]: words from the user (as OpenAI hears them,
     * so background noise doesn't count), an answer playing, or a look-up running.
     */
    @Volatile private var lastActivityAt = 0L
    private var idleReported = false

    @Volatile private var heardUser = false
    private val seenTypes = mutableSetOf<String>()

    override suspend fun start() {
        lifecycle.withLock {
            if (scope != null) return
            _state.value = VoiceState(VoicePhase.CONNECTING, "Connecting…")
            scope = CoroutineScope(SupervisorJob() + RTC).also { it.launch { run() } }
        }
    }

    override suspend fun stop() {
        lifecycle.withLock {
            val s = scope ?: return
            scope = null
            // Waits for the connect step and any delegation still being answered.
            s.coroutineContext.job.cancelAndJoin()
            withContext(RTC) { release() }
            audio.exit()
            _level.value = 0f
            if (_state.value.phase != VoicePhase.ERROR) _state.update { it.copy(phase = VoicePhase.IDLE, detail = "Stopped") }
        }
    }

    private suspend fun run() {
        val started = SystemClock.elapsedRealtime()
        try {
            var bearer = auth.bearer()
            // The phone's own audio (call mode, headset or loudspeaker) is used unless the external device is the mic.
            if (external == null || !external.micOnDevice) {
                audio.enter()
                callAudioOn = true
            }
            val connection = createPeerConnection()
            val offer = connection.createOfferSdp()
            val answer =
                try {
                    postOffer(offer, bearer)
                } catch (e: CallFailed) {
                    if (e.status != 401) throw e
                    bearer = auth.onUnauthorized(bearer)
                    postOffer(offer, bearer)
                }
            currentCoroutineContext().ensureActive()
            if (!answer.trimStart().startsWith("v=0")) throw CallFailed(200, "the answer was not an SDP")
            connection.setRemote(SessionDescription(SessionDescription.Type.ANSWER, answer))
            val connectMs = SystemClock.elapsedRealtime() - started
            val playsOn = external?.name ?: "${audio.output} (offered: ${audio.offered})"
            _state.update { it.copy(phase = VoicePhase.LISTENING, detail = "Connected in $connectMs ms · just talk", metrics = it.metrics.withNote("answers play on $playsOn")) }
            logger.log(LogEvent.Engine("voice_connected", "gpt-live", connectMs))
            pollLevels(connection)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.log(LogEvent.Engine("voice_failed_${e::class.simpleName}", "gpt-live"))
            val detail = (e as? CallFailed)?.let { "OpenAI said ${it.status}: ${it.snippet}" } ?: (e::class.simpleName ?: "error")
            release()
            audio.exit()
            _level.value = 0f
            _state.value = VoiceState(VoicePhase.ERROR, detail, metrics = _state.value.metrics)
        }
    }

    /** Runs on the RTC thread only. Safe to call twice. */
    private fun release() {
        runCatching { events?.unregisterObserver() }
        runCatching { events?.dispose() }
        runCatching { pc?.dispose() }
        runCatching { audioSource?.dispose() }
        runCatching { factory?.dispose() }
        runCatching { adm?.release() }
        events = null
        pc = null
        audioSource = null
        factory = null
        adm = null
    }

    private fun createPeerConnection(): PeerConnection {
        iceComplete = CompletableDeferred()
        initWebRtcOnce(appContext)
        val module =
            JavaAudioDeviceModule
                .builder(appContext)
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .apply {
                    external?.let { ext ->
                        // Runs before WebRTC sends each 10 ms buffer: put the external mic's audio in it.
                        setAudioBufferCallback { buffer, _, channels, sampleRate, bytes, captureTimeNs ->
                            // With a headset on the phone, its microphone goes through unchanged, once
                            // what was said before the call connected has been played in.
                            if (ext.micOnDevice || ext.catchingUp) ext.fillMicrophone(buffer, bytes, sampleRate, channels)
                            captureTimeNs
                        }
                    }
                }
                // With an external mic the phone only plays into headphones: a media-like route, not a call.
                .setAudioAttributes(if (external != null && external.micOnDevice) ASSISTANT else CallAudio.SPEECH)
                .setUseHardwareAcousticEchoCanceler(true)
                .setUseHardwareNoiseSuppressor(true)
                .createAudioDeviceModule()
        adm = module
        // With an external speaker the phone stays silent; the answer reaches it through a sink.
        if (external != null) module.setSpeakerMute(!external.answerOnPhone)
        phoneSpeakerMuted = external != null && !external.answerOnPhone
        val f = PeerConnectionFactory.builder().setAudioDeviceModule(module).createPeerConnectionFactory()
        factory = f
        val config = PeerConnection.RTCConfiguration(emptyList()).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN }
        val connection = f.createPeerConnection(config, observer) ?: error("no peer connection")
        pc = connection
        val source = f.createAudioSource(MediaConstraints())
        audioSource = source
        connection.addTrack(f.createAudioTrack("mic", source), listOf("local"))
        events = connection.createDataChannel("oai-events", DataChannel.Init()).also { it.registerObserver(dataObserver(it)) }
        return connection
    }

    private suspend fun PeerConnection.createOfferSdp(): String {
        val offer =
            suspendCancellableCoroutine { cont ->
                createOffer(
                    object : SdpObserverAdapter() {
                        override fun onCreateSuccess(description: SessionDescription) = cont.resume(description)

                        override fun onCreateFailure(error: String?) = cont.resumeWithException(IllegalStateException("offer: $error"))
                    },
                    MediaConstraints(),
                )
            }
        suspendCancellableCoroutine { cont ->
            setLocalDescription(
                object : SdpObserverAdapter() {
                    override fun onSetSuccess() = cont.resume(Unit)

                    override fun onSetFailure(error: String?) = cont.resumeWithException(IllegalStateException("local: $error"))
                },
                offer,
            )
        }
        // No trickle ICE with this endpoint. Host candidates arrive within a few ms; the server is
        // reachable directly, so don't wait long for the rest.
        withTimeoutOrNull(ICE_GATHER_TIMEOUT_MS) { iceComplete.await() }
        return localDescription?.description ?: offer.description
    }

    private suspend fun PeerConnection.setRemote(answer: SessionDescription) = suspendCancellableCoroutine { cont ->
        setRemoteDescription(
            object : SdpObserverAdapter() {
                override fun onSetSuccess() = cont.resume(Unit)

                override fun onSetFailure(error: String?) = cont.resumeWithException(IllegalStateException("remote: $error"))
            },
            answer,
        )
    }

    private suspend fun postOffer(
        sdp: String,
        bearer: Bearer,
    ): String {
        val body =
            buildJsonObject {
                put("sdp", sdp)
                putJsonObject("session") {
                    put("model", MODEL)
                    put("instructions", "$instructions\nAt the start of this call: ${DeviceContext.describe()}")
                    putJsonObject("audio") { putJsonObject("output") { put("voice", voice) } }
                    putJsonObject("delegation") { put("type", "client") }
                }
            }
        val request =
            Request
                .Builder()
                .url(CALL_URL)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .header("Authorization", "Bearer ${bearer.accessToken}")
                .header("chatgpt-account-id", bearer.accountId)
                .header("OpenAI-Alpha", "quicksilver=v2")
                .header("session-id", UUID.randomUUID().toString())
                .header("thread-id", UUID.randomUUID().toString())
                .header("x-session-id", UUID.randomUUID().toString())
                .header("originator", OpenAiAuth.ORIGINATOR)
                .header("User-Agent", "WatchAI/0.1 (Android)")
                .apply { if (bearer.fedramp) header("X-OpenAI-Fedramp", "true") }
                .build()
        return withContext(Dispatchers.IO) {
            http.newCall(request).executeAsync().use { response ->
                val text = response.body.string()
                if (!response.isSuccessful) throw CallFailed(response.code, text.take(300))
                text
            }
        }
    }

    /**
     * Every [POLL_MS]: measures turn and interrupt times, and mutes the answer while the user talks
     * over it (the levels are measured before playback, so muting doesn't hide the answer from them).
     */
    private suspend fun pollLevels(connection: PeerConnection) {
        val timer = TurnTimer()
        val bargeIn = BargeIn()
        // Loudest mic level over each answer, logged with the leak and the threshold: shows why a talk-over did or didn't mute.
        var micPeak = 0.0
        var answering = false
        // And the loudest mic level while listening, every 2 s: shows whether the user's voice arrives at all.
        var listenPeak = 0.0
        var listenLoggedAt = 0L
        val start = SystemClock.elapsedRealtime()
        lastActivityAt = start
        while (currentCoroutineContext().isActive) {
            val (user, assistant) = connection.levels()
            val now = SystemClock.elapsedRealtime() - start
            when (val event = timer.sample(now, user, assistant)) {
                is TurnTimer.Event.Turn -> _state.update { it.copy(metrics = it.metrics.withTurn(event.latencyMs)) }
                is TurnTimer.Event.Interrupt -> note("OpenAI stopped the answer after ${event.latencyMs} ms")
                null -> Unit
            }
            if (!timer.assistantSpeaking) listenPeak = maxOf(listenPeak, user)
            if (now - listenLoggedAt >= LISTEN_LOG_MS) {
                listenLoggedAt = now
                if (!timer.assistantSpeaking) logger.log(LogEvent.Engine("voice_listen_mic_peak${(listenPeak * 1000).toInt()}", "gpt-live"))
                listenPeak = 0.0
            }
            if (timer.assistantSpeaking) {
                micPeak = maxOf(micPeak, user)
                answering = true
            } else if (answering) {
                answering = false
                val permille = { v: Double -> (v * 1000).toInt() }
                logger.log(LogEvent.Engine("voice_answer_mic_peak${permille(micPeak)}_leak${permille(bargeIn.echoLevel)}_talk${permille(bargeIn.talkThreshold)}", "gpt-live"))
                micPeak = 0.0
            }
            when (bargeIn.sample(now, user, timer.assistantSpeaking)) {
                BargeIn.Action.MUTE -> {
                    logger.log(LogEvent.Engine("voice_barge_in_mute", "gpt-live", bargeIn.lastMuteAfterMs))
                    answerMuted = true
                    external?.takeUnless { it.answerOnPhone }?.flushAnswer()
                    _state.update { it.copy(metrics = it.metrics.withInterrupt(bargeIn.lastMuteAfterMs)) }
                }

                BargeIn.Action.UNMUTE -> {
                    logger.log(LogEvent.Engine("voice_barge_in_unmute", "gpt-live"))
                    answerMuted = false
                }

                null -> Unit
            }
            // The phone plays when there's no external speaker in use, and not while the user talks over it.
            val phoneSilent = answerMuted || (external != null && !external.answerOnPhone)
            if (phoneSilent != phoneSpeakerMuted) {
                phoneSpeakerMuted = phoneSilent
                adm?.setSpeakerMute(phoneSilent)
            }
            // The headset went away mid-call: the phone leaves call mode, the external device takes over.
            val wantCallAudio = external == null || !external.micOnDevice
            if (wantCallAudio != callAudioOn) {
                callAudioOn = wantCallAudio
                if (wantCallAudio) audio.enter() else audio.exit()
            }
            val clock = start + now
            if (timer.assistantSpeaking || consultJob?.isActive == true) lastActivityAt = clock
            // Before the first words, more time: the start can be slow (a sleeping phone took 5.4 s to
            // connect, 28.09) and people wait for the chime.
            val idleLimit = idleHangUpMs?.let { if (heardUser) it else maxOf(it, FIRST_WORDS_MS) }
            if (idleLimit != null && !idleReported && clock - lastActivityAt >= idleLimit) {
                idleReported = true
                note("hung up after ${idleLimit / 1000} s of quiet")
                onIdle()
            }
            if (timer.assistantSpeaking) responsePending = false
            _level.value = (if (timer.assistantSpeaking) assistant else user).toFloat()
            val phase =
                when {
                    timer.assistantSpeaking -> VoicePhase.SPEAKING
                    responsePending && !timer.userSpeaking -> VoicePhase.THINKING
                    else -> VoicePhase.LISTENING
                }
            if (_state.value.phase != phase && _state.value.phase != VoicePhase.ERROR) _state.update { it.copy(phase = phase) }
            external?.status(phase, _level.value)
            delay(POLL_MS)
        }
    }

    /** Mic level (after echo cancellation) and the answer's level, both 0..1, from WebRTC stats. */
    private suspend fun PeerConnection.levels(): Pair<Double, Double> = suspendCancellableCoroutine { cont ->
        getStats { report ->
            var mic = 0.0
            var remote = 0.0
            report.statsMap.values.forEach { stat ->
                val kind = stat.members["kind"] as? String
                val level = (stat.members["audioLevel"] as? Number)?.toDouble()
                when {
                    stat.type == "media-source" && kind == "audio" && level != null -> mic = level
                    stat.type == "inbound-rtp" && kind == "audio" && level != null -> remote = level
                }
            }
            if (cont.isActive) cont.resume(mic to remote)
        }
    }

    private val observer =
        object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                note("ice ${state?.name?.lowercase()}")
                when (state) {
                    PeerConnection.IceConnectionState.FAILED ->
                        _state.update { it.copy(phase = VoicePhase.ERROR, detail = "Connection lost. Tap Stop, then start again.") }

                    PeerConnection.IceConnectionState.DISCONNECTED -> _state.update { it.copy(detail = "Connection unstable…") }

                    else -> Unit
                }
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                if (state == PeerConnection.IceGatheringState.COMPLETE) iceComplete.complete(Unit)
            }

            override fun onIceCandidate(candidate: IceCandidate?) = Unit

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit

            override fun onAddStream(stream: MediaStream?) = Unit

            override fun onRemoveStream(stream: MediaStream?) = Unit

            override fun onDataChannel(channel: DataChannel?) {
                channel?.registerObserver(dataObserver(channel))
            }

            override fun onRenegotiationNeeded() = Unit

            override fun onAddTrack(
                receiver: RtpReceiver?,
                streams: Array<out MediaStream>?,
            ) {
                note("remote audio track")
                val ext = external ?: return
                (receiver?.track() as? AudioTrack)?.addSink { data, _, sampleRate, channels, frames, _ ->
                    if (!answerMuted && !ext.answerOnPhone) ext.playAnswer(data, sampleRate, channels, frames)
                }
            }
        }

    /** Events on the data channel: captions, the call's transcript, and delegations to answer. Only event types are logged. */
    private fun dataObserver(channel: DataChannel) = object : DataChannel.Observer {
        override fun onBufferedAmountChange(previousAmount: Long) = Unit

        override fun onStateChange() = note("events ${runCatching { channel.state().name.lowercase() }.getOrDefault("?")}")

        override fun onMessage(buffer: DataChannel.Buffer) {
            val bytes = ByteArray(buffer.data.remaining()).also { buffer.data.get(it) }
            val (type, event) = QuicksilverWire.parse(json, String(bytes, StandardCharsets.UTF_8)) ?: return
            when (event) {
                is QuicksilverWire.Event.UserText -> onUserText(event)

                is QuicksilverWire.Event.AssistantText -> onAssistantText(event)

                is QuicksilverWire.Event.Delegation -> delegate(event)

                is QuicksilverWire.Event.Failure -> note("error event ${event.code}")

                is QuicksilverWire.Event.Closed -> {
                    note("call closed: ${event.reason}")
                    if (event.reason != "close_requested") {
                        _state.update { it.copy(phase = VoicePhase.ERROR, detail = "OpenAI ended the call (${event.reason}). Tap Stop, then start again.") }
                    }
                }

                QuicksilverWire.Event.Other -> Unit
            }
            if (seenTypes.add(type)) {
                note("event $type")
                logger.log(LogEvent.Engine("voice_event_${type.filter { it.isLetterOrDigit() || it in "._-" }.take(60)}", "gpt-live"))
            }
        }
    }

    private fun onUserText(event: QuicksilverWire.Event.UserText) {
        lastActivityAt = SystemClock.elapsedRealtime()
        heardUser = true
        if (event.final) {
            userTurnOpen = false
            remember(ChatTurn.Role.USER, event.text)
            _state.update { it.copy(lastUserText = event.text) }
        } else {
            val fresh = !userTurnOpen
            userTurnOpen = true
            _state.update { it.copy(lastUserText = if (fresh) event.text else (it.lastUserText.orEmpty() + event.text).takeLast(300)) }
        }
    }

    private fun onAssistantText(event: QuicksilverWire.Event.AssistantText) {
        if (event.final) {
            assistantTurnOpen = false
            remember(ChatTurn.Role.ASSISTANT, event.text)
            _state.update { it.copy(lastAssistantText = event.text) }
        } else {
            val fresh = !assistantTurnOpen
            assistantTurnOpen = true
            responsePending = false
            _state.update { it.copy(lastAssistantText = if (fresh) event.text else (it.lastAssistantText.orEmpty() + event.text).takeLast(300)) }
        }
    }

    private fun remember(
        role: ChatTurn.Role,
        text: String,
    ) {
        if (text.isBlank()) return
        synchronized(transcript) {
            transcript += ChatTurn(role, text)
            while (transcript.size > MAX_TRANSCRIPT) transcript.removeAt(0)
        }
    }

    /**
     * The voice model handed a question to us. Ask ChatGPT (same plan, web search on, told the local
     * time) and hand the answer back; the voice says it. A newer delegation replaces an older one.
     */
    private fun delegate(delegation: QuicksilverWire.Event.Delegation) {
        val s = scope ?: return
        responsePending = true
        _state.update { it.copy(phase = VoicePhase.THINKING, detail = "Looking it up…") }
        consultJob?.cancel()
        consultJob =
            s.launch {
                val started = SystemClock.elapsedRealtime()
                val past = synchronized(transcript) { transcript.toList() }
                val question = delegation.prompt.ifBlank { past.lastOrNull { it.role == ChatTurn.Role.USER }?.text.orEmpty() }
                val answer = if (question.isBlank()) QuicksilverWire.NO_INPUT_RESULT else lookUp(question, past)
                val sent = send(QuicksilverWire.resultFrames(delegation.id, answer))
                val ms = SystemClock.elapsedRealtime() - started
                logger.log(LogEvent.Engine(if (sent) "voice_delegation_answered" else "voice_delegation_unsent", "gpt-live", ms))
                note("looked up in $ms ms")
                _state.update { if (it.detail == "Looking it up…") it.copy(detail = null) else it }
            }
    }

    private suspend fun lookUp(
        question: String,
        past: List<ChatTurn>,
    ): String {
        val text = StringBuilder()
        // The question itself usually ends the transcript; don't send it twice.
        val history = if (past.lastOrNull()?.role == ChatTurn.Role.USER) past.dropLast(1) else past
        val request =
            ChatRequest(
                conversationId = conversationId,
                history = history.takeLast(CONSULT_HISTORY),
                userText = question,
                style = ReplyStyle.SPOKEN,
                context = DeviceContext.describe(),
                webSearch = true,
                tools = tools,
            )
        consult.stream(request).collect { event ->
            if (event is ChatEvent.Delta) text.append(event.text)
        }
        return text.toString().trim().ifEmpty { QuicksilverWire.FAILED_RESULT }
    }

    /** RTC thread only. False if the call is already gone. */
    private fun send(frames: List<String>): Boolean {
        val channel = events ?: return false
        return frames.all { channel.send(DataChannel.Buffer(ByteBuffer.wrap(it.toByteArray(StandardCharsets.UTF_8)), false)) }
    }

    private fun note(text: String) = _state.update { it.copy(metrics = it.metrics.withNote(text)) }

    private class CallFailed(
        val status: Int,
        val snippet: String,
    ) : Exception("call failed $status")

    private open class SdpObserverAdapter : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = Unit

        override fun onSetSuccess() = Unit

        override fun onCreateFailure(error: String?) = Unit

        override fun onSetFailure(error: String?) = Unit
    }

    companion object {
        const val MODEL = "gpt-live-1-codex"

        private val ASSISTANT: AudioAttributes =
            AudioAttributes
                .Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        private const val CALL_URL = "https://chatgpt.com/backend-api/codex/realtime/calls?intent=quicksilver&architecture=avas"
        private const val ICE_GATHER_TIMEOUT_MS = 400L
        private const val POLL_MS = 50L
        private const val LISTEN_LOG_MS = 2_000L
        private const val FIRST_WORDS_MS = 20_000L
        private const val MAX_TRANSCRIPT = 16
        private const val CONSULT_HISTORY = 6
        val VOICES = listOf("cove", "arbor", "breeze", "ember", "juniper", "maple", "sol", "spruce", "vale")

        /** One thread for every WebRTC call in the app, so creating, using and disposing never race. */
        private val RTC = Executors.newSingleThreadExecutor { r -> Thread(r, "voice-rtc").apply { isDaemon = true } }.asCoroutineDispatcher()

        @Volatile private var webRtcReady = false

        private fun initWebRtcOnce(context: Context) {
            if (webRtcReady) return
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
            webRtcReady = true
        }

        const val VOICE_INSTRUCTIONS =
            "You are a friendly companion that lives on the user's smartwatch and talks with them out loud. " +
                "Talk naturally and briefly: one or two short sentences, then let the user speak. " +
                "Don't end every reply with a question or an offer to help. No lists. " +
                "Answer in the language the user speaks. " +
                "When a question needs facts you are not sure of, current information, or the exact time, or when the user wants " +
                "something done on their phone or watch (a note, a calendar event, a reminder, a timer or an alarm, music or volume, " +
                "the ringer or Do Not Disturb, ringing the phone to find it, battery levels, or reading their notes or calendar), " +
                "or when they tell you their job, sport or hobby or ask you to dress up or change clothes (you are Buddy, the little " +
                "mascot on their watch, and the client can dress you), " +
                "delegate it to the client and wait for the result, then say the answer in your own words. Never claim something was done " +
                "unless the result says so. If a request misses something it needs, like how long a timer should run, ask for that first."
    }
}
