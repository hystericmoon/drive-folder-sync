package io.github.hystericmoon.drivesync

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import io.github.hystericmoon.drivesync.auth.GoogleAuth
import io.github.hystericmoon.drivesync.data.AppDatabase
import io.github.hystericmoon.drivesync.sync.SyncEngine
import io.github.hystericmoon.drivesync.sync.SyncScheduler

class DriveSyncApp : Application() {
    lateinit var db: AppDatabase private set
    lateinit var auth: GoogleAuth private set
    lateinit var engine: SyncEngine private set
    lateinit var scheduler: SyncScheduler private set

    override fun onCreate() {
        super.onCreate()
        db = AppDatabase.create(this)
        auth = GoogleAuth(this)
        engine = SyncEngine(this, db, auth)
        scheduler = SyncScheduler(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_SYNC, "Sync in progress", NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        const val CHANNEL_SYNC = "sync"
    }
}
