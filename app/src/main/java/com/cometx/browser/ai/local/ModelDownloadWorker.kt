package com.cometx.browser.ai.local

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.cometx.browser.CometApp
import com.cometx.browser.util.Logx
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ModelDownloadWorker (v1.7.0) — downloads an on-device model in the BACKGROUND:
 *
 *  - runs as a foreground service (dataSync type) with a low-importance,
 *    silent progress notification → the process is protected while the app is
 *    closed or the user is doing something else, and NO other app is disturbed
 *  - WorkManager keeps the request in its persistent queue → downloads survive
 *    process death and even reboots; nothing the user has to re-tap
 *  - the NetworkType.CONNECTED constraint + our chunk resume means network
 *    fluctuations simply pause-and-continue silently: the work is stopped by
 *    WorkManager when the network drops and re-dispatched when it returns —
 *    never a failure state, never a stuck UI
 */
class ModelDownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val manager = CometApp.app.localAI
        val id = inputData.getString(KEY_MODEL_ID) ?: return Result.failure()
        val m = manager.byId(id) ?: return Result.failure()

        setForeground(foregroundInfo(m, 0))
        manager.markProgress(m, resumeBytes(m), 0)

        try {
            val part = File(manager.fileFor(m).absolutePath + ".part")
            val meta = File(part.absolutePath + ".meta")
            val downloader = ParallelChunkDownloader(
                url = m.downloadUrl,
                target = part,
                meta = meta,
                totalBytes = m.sizeBytes,
                onProgress = { bytes, speed ->
                    manager.markProgress(m, bytes, speed)
                    notifyProgress(m, bytes)
                },
            )
            downloader.run()

            // Completion + verify + activate must not be killed by a pause tap
            // landing in the same instant — it is short (hash + load).
            val ok = withContext(NonCancellable) {
                manager.markVerifying(m)
                notifyProgress(m, m.sizeBytes)
                manager.finalizeDownload(m)
            }
            return if (ok) Result.success() else Result.failure()
        } catch (ce: CancellationException) {
            // Stopped by the network constraint (fluctuation) or system — NOT a
            // failure. WorkManager re-dispatches automatically when constraints
            // are satisfied again. Show "waiting for network", never an error.
            if (!manager.isPaused(id)) manager.markWaitingNetwork(m)
            throw ce
        } catch (t: Throwable) {
            Logx.e("local-dl: ${m.id} run failed: ${t.message}")
            // Transient trouble (server 5xx, dropped socket storm) → retry with
            // backoff; the chunk sidecar keeps every finished byte.
            return if (runAttemptCount < MAX_RUN_ATTEMPTS) {
                Result.retry()
            } else {
                manager.markFailed(m, t.message ?: "Download failed repeatedly")
                Result.failure()
            }
        }
    }

    private fun resumeBytes(m: LocalModelCatalog.CatalogModel): Long = runCatching {
        val part = File(CometApp.app.localAI.fileFor(m).absolutePath + ".part")
        val meta = File(part.absolutePath + ".meta")
        if (part.exists() && meta.exists()) {
            ChunkPlanner.parseSidecar(meta.readText()).values.sum().coerceAtMost(m.sizeBytes)
        } else 0L
    }.getOrDefault(0L)

    // ------------------------------------------------------------- foreground

    private fun foregroundInfo(m: LocalModelCatalog.CatalogModel, pct: Int): ForegroundInfo {
        val type = if (Build.VERSION.SDK_INT >= 29) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else 0
        return ForegroundInfo(NOTIFICATION_ID, buildNotification(m, pct), type)
    }

    private fun buildNotification(m: LocalModelCatalog.CatalogModel, pct: Int): Notification {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Model downloads", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Background on-device AI model downloads"
                    setShowBadge(false)
                    setSound(null, null)
                    enableVibration(false)
                }
            )
        }
        val b = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(applicationContext, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(applicationContext)
        }
        return b.apply {
            setContentTitle("Downloading ${m.fileName}")
            setContentText(if (pct in 1..99) "$pct% · continues in background" else "Preparing download…")
            setSmallIcon(android.R.drawable.stat_sys_download)
            setOngoing(true)
            setOnlyAlertOnce(true)
            setProgress(100, pct.coerceIn(0, 100), pct <= 0)
        }.build()
    }

    private var lastNotifyTick = 0L

    private fun notifyProgress(m: LocalModelCatalog.CatalogModel, bytes: Long) {
        val now = System.currentTimeMillis()
        if (now - lastNotifyTick < 3000) return
        lastNotifyTick = now
        val pct = if (m.sizeBytes > 0) (bytes * 100 / m.sizeBytes).toInt() else 0
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { nm.notify(NOTIFICATION_ID, buildNotification(m, pct)) }
    }

    companion object {
        const val KEY_MODEL_ID = "model_id"
        const val CHANNEL_ID = "cometx_downloads"
        const val NOTIFICATION_ID = 47001
        const val MAX_RUN_ATTEMPTS = 50

        fun uniqueName(modelId: String) = "cometx-model-$modelId"

        /** Enqueue (or no-op if already queued) a resilient background download. */
        fun enqueue(context: Context, modelId: String) {
            val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setInputData(workDataOf(KEY_MODEL_ID to modelId))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(androidx.work.BackoffPolicy.LINEAR, 10, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(uniqueName(modelId), ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context, modelId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(uniqueName(modelId))
        }
    }
}
