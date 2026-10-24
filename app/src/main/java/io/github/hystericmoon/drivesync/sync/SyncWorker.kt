package io.github.hystericmoon.drivesync.sync

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import io.github.hystericmoon.drivesync.DriveSyncApp
import io.github.hystericmoon.drivesync.Notifier
import io.github.hystericmoon.drivesync.R
import io.github.hystericmoon.drivesync.auth.NeedsUserConsentException
import java.io.IOException

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val pairId = inputData.getLong(KEY_PAIR_ID, -1)
        if (pairId < 0) return Result.failure()
        val app = applicationContext as DriveSyncApp
        // Lets big uploads run past the 10-minute worker limit. Android may refuse this for
        // background starts; the sync then still runs, just under normal limits.
        runCatching { setForeground(getForegroundInfo()) }
        val result = try {
            app.engine.sync(pairId)
            Result.success()
        } catch (_: NeedsUserConsentException) {
            Notifier.needsSignIn(applicationContext)
            app.health.noteEvent("Google access expired; syncing paused until access is granted again")
            Result.failure()
        } catch (_: IOException) {
            if (runAttemptCount < 5) Result.retry() else Result.failure()
        }
        val pair = app.db.pairs().get(pairId)
        val outcome = listOfNotNull(pair?.lastSummary, pair?.lastError?.lineSequence()?.first()).joinToString(" | ")
        app.health.noteRun(pairId, inputData.getString(KEY_TRIGGER) ?: "scheduled", outcome.ifEmpty { "no result" })
        app.health.publish()
        return result
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification: Notification = NotificationCompat.Builder(applicationContext, Notifier.CHANNEL_SYNC)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Syncing with Google Drive")
            .setOngoing(true)
            .setSilent(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        const val KEY_PAIR_ID = "pairId"
        const val KEY_TRIGGER = "trigger"
        private const val NOTIFICATION_ID = 1
    }
}
