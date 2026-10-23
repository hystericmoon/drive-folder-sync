package io.github.hystericmoon.drivesync.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import io.github.hystericmoon.drivesync.DriveSyncApp
import io.github.hystericmoon.drivesync.Notifier

/** Receives the system installer's verdict for a self-update session. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                } ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val app = context.applicationContext as DriveSyncApp
                if (app.inForeground) {
                    context.startActivity(confirm)
                } else {
                    Notifier.update(context, "Update ready", "Tap to install the new version of Drive Folder Sync.", confirm)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // the app restarts as the new version
            PackageInstaller.STATUS_FAILURE_ABORTED -> Unit // user tapped Cancel
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $status"
                Notifier.update(context, "Update failed", msg, null)
            }
        }
    }
}
