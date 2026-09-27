package com.vinhnguyen.watchai.ondevice

import android.content.Context
import com.google.mlkit.common.MlKit
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ModelPreference
import com.google.mlkit.genai.prompt.ModelReleaseStage
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.google.mlkit.genai.prompt.generationConfig
import com.google.mlkit.genai.prompt.modelConfig
import com.vinhnguyen.watchai.brain.Availability
import com.vinhnguyen.watchai.brain.Brain
import com.vinhnguyen.watchai.brain.BrainError
import com.vinhnguyen.watchai.brain.BrainId
import com.vinhnguyen.watchai.brain.BrainLogger
import com.vinhnguyen.watchai.brain.ChatEvent
import com.vinhnguyen.watchai.brain.ChatRequest
import com.vinhnguyen.watchai.brain.ChatTurn
import com.vinhnguyen.watchai.brain.LogEvent
import com.vinhnguyen.watchai.brain.PromptStyle
import com.vinhnguyen.watchai.brain.TurnStats
import com.vinhnguyen.watchai.brain.UnavailableReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Gemini Nano through ML Kit's Prompt API, on the phones Google supports (not the S23 Ultra).
 * Opt-in only (ML Kit sends Google diagnostics), and only while the app is on screen: Google
 * blocks background use even with a foreground service.
 */
class GeminiNanoBrain(
    context: Context,
    private val settings: OnDeviceSettings,
    private val foreground: AppForeground,
    private val logger: BrainLogger = BrainLogger.None,
) : Brain {
    override val id: BrainId = BrainId.GEMINI_NANO

    private val appContext = context.applicationContext
    private var client: GenerativeModel? = null

    @Synchronized
    private fun client(): GenerativeModel = client ?: run {
        // ML Kit's auto-start provider is removed from the manifest; it starts only after opt-in.
        MlKit.initialize(appContext)
        Generation
            .getClient(
                generationConfig {
                    modelConfig =
                        modelConfig {
                            releaseStage = ModelReleaseStage.STABLE
                            preference = ModelPreference.FAST
                        }
                },
            ).also { client = it }
    }

    override suspend fun availability(): Availability {
        if (!settings.nanoOptIn) return Availability.Unavailable(UnavailableReason.NOT_OPTED_IN)
        if (!foreground.isForeground()) return Availability.Unavailable(UnavailableReason.DISABLED)
        return try {
            when (client().checkStatus()) {
                FeatureStatus.AVAILABLE -> Availability.Ready
                FeatureStatus.DOWNLOADABLE -> Availability.NeedsDownload(0)
                FeatureStatus.DOWNLOADING -> Availability.Downloading(null)
                else -> Availability.Unavailable(UnavailableReason.DEVICE_NOT_SUPPORTED)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Availability.Unavailable(UnavailableReason.DEVICE_NOT_SUPPORTED)
        }
    }

    override fun stream(request: ChatRequest): Flow<ChatEvent> = flow {
        if (!foreground.isForeground()) {
            emit(ChatEvent.Failed(BrainError.BackgroundBlocked))
            return@flow
        }
        val started = System.currentTimeMillis()
        var firstToken: Long? = null
        val text = StringBuilder()
        logger.log(LogEvent.TurnStarted(id))
        try {
            val model = client()
            val prompt = PromptStyle.trim(request.history).joinToString("") { it.asLine() } + request.userText
            val genRequest =
                generateContentRequest(SystemInstruction(PromptStyle.systemWithoutTools(request)), TextPart(prompt)) {
                    temperature = 0.4f
                    topK = 16
                    maxOutputTokens = request.maxOutputTokens
                }
            model.generateContentStream(genRequest).collect { response ->
                val chunk =
                    response.candidates
                        .firstOrNull()
                        ?.text
                        .orEmpty()
                if (chunk.isNotEmpty()) {
                    if (firstToken == null) firstToken = System.currentTimeMillis() - started
                    text.append(chunk)
                    emit(ChatEvent.Delta(chunk))
                }
            }
            val total = System.currentTimeMillis() - started
            logger.log(LogEvent.TurnFinished(id, "gemini-nano", "aicore", firstToken, total))
            emit(ChatEvent.Done(TurnStats(id, "gemini-nano", "aicore", firstToken, total, text.length)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: GenAiException) {
            logger.log(LogEvent.TurnFailed(id, "GenAi_${e.errorCode}", null))
            emit(ChatEvent.Failed(map(e.errorCode), text.toString()))
        } catch (e: Exception) {
            emit(ChatEvent.Failed(BrainError.BackendFailed("aicore"), text.toString()))
        }
    }

    private fun ChatTurn.asLine(): String = (if (role == ChatTurn.Role.USER) "User: " else "Assistant: ") + text + "\n"

    private fun map(code: Int): BrainError = when (code) {
        GenAiException.ErrorCode.BUSY -> BrainError.Busy
        GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED -> BrainError.QuotaExceeded
        GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED -> BrainError.BackgroundBlocked
        GenAiException.ErrorCode.NOT_AVAILABLE, GenAiException.ErrorCode.NOT_SUPPORTED -> BrainError.ModelNotReady
        GenAiException.ErrorCode.REQUEST_TOO_LARGE -> BrainError.Unknown(code = "request_too_large")
        else -> BrainError.BackendFailed("aicore")
    }
}
