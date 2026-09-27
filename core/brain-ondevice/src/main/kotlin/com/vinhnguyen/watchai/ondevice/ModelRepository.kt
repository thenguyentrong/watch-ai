package com.vinhnguyen.watchai.ondevice

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.storage.StorageManager
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File
import java.util.concurrent.TimeUnit

sealed interface ModelState {
    data object Ready : ModelState

    data object NotDownloaded : ModelState

    data class Downloading(
        val progress: Float?,
    ) : ModelState

    data object NotEnoughRam : ModelState

    data class Failed(
        val reason: String,
    ) : ModelState
}

class ModelRepository(
    context: Context,
) {
    private val appContext = context.applicationContext
    val specs: List<ModelSpec> =
        ModelCatalog.parse(
            appContext.assets
                .open("models.json")
                .bufferedReader()
                .use { it.readText() },
        )
    val dir: File = File(appContext.noBackupFilesDir, "models")
    private val downloader = ModelDownloader(ModelDownloader.httpClient(), dir)

    fun spec(id: String): ModelSpec = specs.firstOrNull { it.id == id } ?: specs.first()

    fun readyPath(spec: ModelSpec): String? = if (downloader.isReady(spec)) downloader.finalFile(spec).path else null

    fun deviceRamGb(): Double {
        val info = ActivityManager.MemoryInfo()
        appContext.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        return info.totalMem / 1_073_741_824.0
    }

    suspend fun state(spec: ModelSpec): ModelState {
        if (downloader.isReady(spec)) return ModelState.Ready
        if (deviceRamGb() + 0.5 < spec.minRamGb) return ModelState.NotEnoughRam
        val info =
            WorkManager
                .getInstance(appContext)
                .getWorkInfosForUniqueWorkFlow(workName(spec))
                .first()
                .firstOrNull()
        return info.toState()
    }

    fun observe(spec: ModelSpec): Flow<ModelState> = WorkManager.getInstance(appContext).getWorkInfosForUniqueWorkFlow(workName(spec)).map {
        if (downloader.isReady(spec)) ModelState.Ready else it.firstOrNull().toState()
    }

    /** Wi-Fi (unmetered) only, never when storage is low; resumes where it stopped. */
    fun startDownload(spec: ModelSpec) {
        val request =
            OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setInputData(workDataOf(ModelDownloadWorker.KEY_MODEL_ID to spec.id))
                .setConstraints(
                    Constraints
                        .Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresStorageNotLow(true)
                        .build(),
                ).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(workName(spec), ExistingWorkPolicy.KEEP, request)
    }

    fun cancelDownload(spec: ModelSpec) {
        WorkManager.getInstance(appContext).cancelUniqueWork(workName(spec))
    }

    fun delete(spec: ModelSpec) {
        cancelDownload(spec)
        downloader.delete(spec)
    }

    private fun WorkInfo?.toState(): ModelState = when (this?.state) {
        null, WorkInfo.State.CANCELLED -> {
            ModelState.NotDownloaded
        }

        WorkInfo.State.SUCCEEDED -> {
            ModelState.NotDownloaded
        }

        // file gone since
        WorkInfo.State.FAILED -> {
            ModelState.Failed(outputData.getString(ModelDownloadWorker.KEY_ERROR) ?: "unknown")
        }

        else -> {
            val done = progress.getLong(ModelDownloadWorker.KEY_DONE, -1)
            val total = progress.getLong(ModelDownloadWorker.KEY_TOTAL, -1)
            ModelState.Downloading(if (done >= 0 && total > 0) done.toFloat() / total else null)
        }
    }

    internal fun downloader(): ModelDownloader = ModelDownloader(ModelDownloader.httpClient(), dir) { needed ->
        val storage = appContext.getSystemService(StorageManager::class.java)
        val uuid = storage.getUuidForPath(appContext.noBackupFilesDir)
        storage.getAllocatableBytes(uuid) >= needed
    }

    private fun workName(spec: ModelSpec) = "model-${spec.id}"
}

class ModelDownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repo = ModelRepository(applicationContext)
        val spec = repo.spec(inputData.getString(KEY_MODEL_ID) ?: ModelCatalog.DEFAULT_ID)
        setForeground(foregroundInfo(null))
        return try {
            var lastPercent = -1
            repo.downloader().download(spec) { done, total ->
                val percent = (done * 100 / total).toInt()
                if (percent != lastPercent) {
                    lastPercent = percent
                    setProgress(workDataOf(KEY_DONE to done, KEY_TOTAL to total))
                    setForeground(foregroundInfo(percent))
                }
            }
            Result.success()
        } catch (_: RetryableDownloadException) {
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure(workDataOf(KEY_ERROR to "network"))
        } catch (_: ModelIntegrityException) {
            Result.failure(workDataOf(KEY_ERROR to "integrity"))
        } catch (_: NotEnoughSpaceException) {
            Result.failure(workDataOf(KEY_ERROR to "space"))
        } catch (_: OversizeException) {
            Result.failure(workDataOf(KEY_ERROR to "integrity"))
        } catch (e: java.io.IOException) {
            Result.failure(workDataOf(KEY_ERROR to (e::class.simpleName ?: "io")))
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(null)

    private fun foregroundInfo(percent: Int?): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Model download", NotificationManager.IMPORTANCE_LOW))
        }
        val notification =
            NotificationCompat
                .Builder(applicationContext, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Downloading the on-device model")
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setProgress(100, percent ?: 0, percent == null)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        const val KEY_MODEL_ID = "model_id"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_ERROR = "error"
        private const val CHANNEL = "model_download"
        private const val NOTIFICATION_ID = 4_101
        private const val MAX_ATTEMPTS = 8
    }
}
