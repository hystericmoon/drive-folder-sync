package io.github.hystericmoon.drivesync.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import io.github.hystericmoon.drivesync.data.FolderPair
import java.util.concurrent.TimeUnit

class SyncScheduler(context: Context) {
    private val wm = WorkManager.getInstance(context)

    /** (Re)creates the 15-minute periodic job using the pair's current constraints. */
    fun schedule(pair: FolderPair) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (pair.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresCharging(pair.chargingOnly)
            .build()
        val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .setInputData(workDataOf(SyncWorker.KEY_PAIR_ID to pair.id))
            .addTag(TAG)
            .build()
        wm.enqueueUniquePeriodicWork(periodicName(pair.id), ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** Manual sync: only needs a network; the user asked for it, so Wi-Fi/charging rules don't apply. */
    fun syncNow(pairId: Long) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(SyncWorker.KEY_PAIR_ID to pairId))
            .addTag(TAG)
            .build()
        wm.enqueueUniqueWork(nowName(pairId), ExistingWorkPolicy.KEEP, request)
    }

    fun cancel(pairId: Long) {
        wm.cancelUniqueWork(periodicName(pairId))
        wm.cancelUniqueWork(nowName(pairId))
    }

    private fun periodicName(id: Long) = "sync-periodic-$id"
    private fun nowName(id: Long) = "sync-now-$id"

    companion object {
        const val TAG = "sync"
    }
}
