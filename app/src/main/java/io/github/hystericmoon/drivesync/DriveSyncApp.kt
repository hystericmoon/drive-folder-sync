package io.github.hystericmoon.drivesync

import android.app.Activity
import android.app.Application
import android.os.Bundle
import io.github.hystericmoon.drivesync.auth.GoogleAuth
import io.github.hystericmoon.drivesync.data.AppDatabase
import io.github.hystericmoon.drivesync.sync.SyncEngine
import io.github.hystericmoon.drivesync.sync.SyncScheduler
import io.github.hystericmoon.drivesync.ui.AppSettings
import io.github.hystericmoon.drivesync.update.Updater

class DriveSyncApp : Application() {
    lateinit var db: AppDatabase private set
    lateinit var auth: GoogleAuth private set
    lateinit var engine: SyncEngine private set
    lateinit var scheduler: SyncScheduler private set
    lateinit var updater: Updater private set
    lateinit var settings: AppSettings private set

    /** True while any activity is started; lets background code decide between a dialog and a notification. */
    @Volatile var inForeground = false
        private set

    override fun onCreate() {
        super.onCreate()
        db = AppDatabase.create(this)
        auth = GoogleAuth(this)
        engine = SyncEngine(this, db, auth)
        scheduler = SyncScheduler(this)
        updater = Updater(this)
        settings = AppSettings(this)
        Notifier.createChannels(this)
        scheduler.scheduleUpdateChecks(updater.autoUpdate)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var started = 0
            override fun onActivityStarted(a: Activity) { inForeground = ++started > 0 }
            override fun onActivityStopped(a: Activity) { inForeground = --started > 0 }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }
}
