package io.github.hystericmoon.drivesync.update

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.hystericmoon.drivesync.DriveSyncApp
import io.github.hystericmoon.drivesync.Notifier

/** Periodic background check; installs silently where Android allows, otherwise asks via notification. */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val updater = (applicationContext as DriveSyncApp).updater
        if (!updater.autoUpdate) return Result.success()
        val update = runCatching { updater.checkForUpdate() }.getOrElse { return Result.retry() } ?: return Result.success()
        if (!updater.canInstall()) {
            Notifier.update(
                applicationContext,
                "Update ${update.versionName} available",
                "Tap to allow Drive Folder Sync to install its own updates.",
                updater.installPermissionIntent(),
            )
            return Result.success()
        }
        return try {
            updater.downloadAndInstall(update)
            Result.success()
        } catch (e: SecurityException) {
            // Wrong key or wrong app: retrying won't help. Say so and wait for the next release.
            Notifier.update(applicationContext, "Update ${update.versionName} not installed", e.message ?: "Signature check failed.", null)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
