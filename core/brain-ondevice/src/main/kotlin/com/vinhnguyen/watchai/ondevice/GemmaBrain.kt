package com.vinhnguyen.watchai.ondevice

import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import com.vinhnguyen.watchai.brain.Availability
import com.vinhnguyen.watchai.brain.Brain
import com.vinhnguyen.watchai.brain.BrainError
import com.vinhnguyen.watchai.brain.BrainId
import com.vinhnguyen.watchai.brain.BrainLogger
import com.vinhnguyen.watchai.brain.ChatEvent
import com.vinhnguyen.watchai.brain.ChatRequest
import com.vinhnguyen.watchai.brain.ChatTurn
import com.vinhnguyen.watchai.brain.LogEvent
import com.vinhnguyen.watchai.brain.Preloadable
import com.vinhnguyen.watchai.brain.Preloaded
import com.vinhnguyen.watchai.brain.PromptStyle
import com.vinhnguyen.watchai.brain.TurnStats
import com.vinhnguyen.watchai.brain.UnavailableReason
import com.vinhnguyen.watchai.brain.code
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/** Gemma 4 on this phone through LiteRT-LM. Free, offline, nothing leaves the device. */
class GemmaBrain(
    private val models: ModelRepository,
    private val settings: OnDeviceSettings,
    private val holder: EngineHolder,
    private val logger: BrainLogger = BrainLogger.None,
) : Brain,
    Preloadable {
    override val id: BrainId = BrainId.GEMMA

    private fun spec() = models.spec(settings.modelId)

    /** Loads the engine now (7-11 s cold on the S23 Ultra) and keeps it loaded until released. No-op if not downloaded. */
    override suspend fun preload(): Preloaded {
        val spec = spec()
        val path = models.readyPath(spec) ?: return Preloaded {}
        val lease = holder.acquire(path, spec.sha256)
        return Preloaded { lease.release() }
    }

    override suspend fun availability(): Availability = when (val state = models.state(spec())) {
        ModelState.Ready -> Availability.Ready
        ModelState.NotDownloaded, is ModelState.Failed -> Availability.NeedsDownload(spec().sizeBytes)
        is ModelState.Downloading -> Availability.Downloading(state.progress)
        ModelState.NotEnoughRam -> Availability.Unavailable(UnavailableReason.INSUFFICIENT_RAM)
    }

    override fun stream(request: ChatRequest): Flow<ChatEvent> = flow {
        val spec = spec()
        val path = models.readyPath(spec)
        if (path == null) {
            emit(ChatEvent.Failed(BrainError.ModelNotReady))
            return@flow
        }
        val started = System.currentTimeMillis()
        var firstToken: Long? = null
        val text = StringBuilder()
        logger.log(LogEvent.TurnStarted(id))
        val lease =
            try {
                holder.acquire(path, spec.sha256)
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                emit(ChatEvent.Failed(BrainError.OutOfMemory))
                return@flow
            } catch (e: Throwable) {
                logger.log(LogEvent.TurnFailed(id, e.code, null))
                emit(ChatEvent.Failed(BrainError.BackendFailed("init")))
                return@flow
            }
        try {
            generate(lease, request).collect { delta ->
                if (firstToken == null) firstToken = System.currentTimeMillis() - started
                text.append(delta)
                emit(ChatEvent.Delta(delta))
            }
            val total = System.currentTimeMillis() - started
            logger.log(LogEvent.TurnFinished(id, spec.id, lease.backend, firstToken, total))
            emit(ChatEvent.Done(TurnStats(id, spec.id, lease.backend, firstToken, total, text.length)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.log(LogEvent.TurnFailed(id, e.code, null))
            if (lease.backend == "gpu" && text.isEmpty()) holder.gpuFailedDuringInference(spec.sha256)
            emit(
                ChatEvent.Failed(
                    if (e is OutOfMemoryError) BrainError.OutOfMemory else BrainError.BackendFailed(lease.backend),
                    text.toString(),
                ),
            )
        } finally {
            lease.release()
        }
    }.flowOn(Dispatchers.Default)

    /**
     * A fresh conversation per turn (history replayed), closed afterwards. LiteRT-LM's own Flow
     * doesn't stop native work when the collector goes away, so this one calls cancelProcess().
     */
    private fun generate(
        lease: EngineHolder.Lease,
        request: ChatRequest,
    ): Flow<String> = callbackFlow {
        val conversation =
            lease.engine.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(PromptStyle.systemWithoutTools(request)),
                    initialMessages = PromptStyle.trim(request.history).map { it.toMessage() },
                    samplerConfig = SamplerConfig(topK = 64, topP = 0.95, temperature = 0.7),
                    maxOutputToken = request.maxOutputTokens,
                    thinkingConfig = ThinkingConfig(enableThinking = false),
                ),
            )
        conversation.sendMessageAsync(
            request.userText,
            object : MessageCallback {
                override fun onMessage(message: Message) {
                    val chunk = message.text()
                    if (chunk.isNotEmpty()) trySend(chunk)
                }

                override fun onDone() {
                    close()
                }

                override fun onError(throwable: Throwable) {
                    close(throwable)
                }
            },
        )
        awaitClose {
            runCatching { conversation.cancelProcess() }
            runCatching { conversation.close() }
        }
    }.buffer(Channel.UNLIMITED)

    private fun ChatTurn.toMessage(): Message = if (role == ChatTurn.Role.USER) Message.user(text) else Message.model(text)

    private fun Message.text(): String = contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
}
