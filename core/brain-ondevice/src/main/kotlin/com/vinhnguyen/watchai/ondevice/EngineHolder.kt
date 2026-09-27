package com.vinhnguyen.watchai.ondevice

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.LogSeverity
import com.vinhnguyen.watchai.brain.BrainLogger
import com.vinhnguyen.watchai.brain.LogEvent
import com.vinhnguyen.watchai.brain.code
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * One LiteRT-LM engine for the whole app (the model takes ~1-2 GB of memory). GPU first, CPU if the
 * GPU fails; a failed GPU is remembered for this phone + engine version + model. The engine is
 * unloaded after a few idle minutes or when Android asks for memory.
 */
class EngineHolder(
    context: Context,
    private val settings: OnDeviceSettings,
    private val scope: CoroutineScope,
    private val logger: BrainLogger = BrainLogger.None,
    private val idleMillis: Long = 3 * 60_000L,
) {
    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private var engine: Engine? = null
    private var loadedPath: String? = null
    private var backend: String? = null
    private var users = 0
    private var idleJob: Job? = null

    class Lease internal constructor(
        val engine: Engine,
        val backend: String,
        private val holder: EngineHolder,
    ) {
        private var released = false

        suspend fun release() {
            if (released) return
            released = true
            holder.release()
        }
    }

    init {
        appContext.registerComponentCallbacks(
            object : ComponentCallbacks2 {
                override fun onTrimMemory(level: Int) {
                    if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) scope.launch { unloadIfIdle("trim") }
                }

                override fun onConfigurationChanged(newConfig: Configuration) = Unit

                @Deprecated("Deprecated in Java")
                override fun onLowMemory() {
                    scope.launch { unloadIfIdle("low_memory") }
                }
            },
        )
    }

    suspend fun acquire(
        modelPath: String,
        modelSha: String,
    ): Lease = mutex.withLock {
        idleJob?.cancel()
        if (engine == null || loadedPath != modelPath) {
            closeLocked()
            val started = System.currentTimeMillis()
            // Not cancellable: a load abandoned halfway would leak a native engine of 1-2 GB.
            val (created, name) = withContext(NonCancellable + Dispatchers.Default) { create(modelPath, modelSha) }
            engine = created
            backend = name
            loadedPath = modelPath
            logger.log(LogEvent.Engine("loaded", name, System.currentTimeMillis() - started))
        }
        if (!currentCoroutineContext().isActive) {
            // The caller gave up while we were loading: keep the engine for a while, then free it.
            if (users == 0) scheduleIdleUnloadLocked()
            throw CancellationException("acquire cancelled")
        }
        users++
        Lease(engine!!, backend!!, this)
    }

    /** Called when the GPU fails at inference time: remember it and reload on CPU next time. */
    suspend fun gpuFailedDuringInference(modelSha: String) = mutex.withLock {
        settings.markGpuBroken(gpuKey(modelSha))
        logger.log(LogEvent.Engine("gpu_failed_inference", "gpu"))
        if (users == 0) closeLocked() else loadedPath = null // reload after the current user is done
    }

    fun isGpuBroken(modelSha: String): Boolean = settings.isGpuBroken(gpuKey(modelSha))

    /** Frees the model now if nobody is using it (e.g. before loading a different engine). True if unloaded. */
    suspend fun unloadNow(): Boolean = mutex.withLock {
        if (users != 0) return@withLock false
        idleJob?.cancel()
        closeLocked()
        true
    }

    private suspend fun release() = withContext(NonCancellable) {
        mutex.withLock {
            users = (users - 1).coerceAtLeast(0)
            if (users == 0) scheduleIdleUnloadLocked()
        }
    }

    private fun scheduleIdleUnloadLocked() {
        idleJob?.cancel()
        idleJob =
            scope.launch {
                delay(idleMillis)
                unloadIfIdle("idle")
            }
    }

    private suspend fun unloadIfIdle(reason: String) = mutex.withLock {
        if (users == 0 && engine != null) {
            closeLocked()
            logger.log(LogEvent.Engine("unloaded_$reason", null))
        }
    }

    private fun closeLocked() {
        runCatching { engine?.close() }
        engine = null
        loadedPath = null
        backend = null
    }

    private fun create(
        modelPath: String,
        modelSha: String,
    ): Pair<Engine, String> {
        Engine.setNativeMinLogSeverity(LogSeverity.ERROR)
        val cacheDir = File(appContext.noBackupFilesDir, "litertlm-cache").apply { mkdirs() }.path
        if (!settings.isGpuBroken(gpuKey(modelSha))) {
            try {
                val gpu =
                    Engine(EngineConfig(modelPath = modelPath, backend = Backend.GPU(), maxNumTokens = MAX_TOKENS, cacheDir = cacheDir))
                gpu.initialize()
                return gpu to "gpu"
            } catch (t: Throwable) {
                settings.markGpuBroken(gpuKey(modelSha))
                logger.log(LogEvent.Engine("gpu_init_failed_${t.code}", "gpu"))
            }
        }
        val cpu = Engine(EngineConfig(modelPath = modelPath, backend = Backend.CPU(), maxNumTokens = MAX_TOKENS, cacheDir = cacheDir))
        cpu.initialize()
        return cpu to "cpu"
    }

    private fun gpuKey(modelSha: String) = "${Build.MODEL}_${ENGINE_VERSION}_${modelSha.take(12)}"

    private companion object {
        const val MAX_TOKENS = 2_048
        const val ENGINE_VERSION = "0.17.1"
    }
}
