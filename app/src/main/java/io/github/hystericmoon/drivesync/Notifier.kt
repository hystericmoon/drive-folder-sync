package io.github.hystericmoon.drivesync

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/** All user-facing notifications. Silently does nothing if notification permission was denied. */
object Notifier {
    const val CHANNEL_SYNC = "sync"
    private const val CHANNEL_CONFLICTS = "conflicts"
    private const val CHANNEL_ATTENTION = "attention"
    private const val CHANNEL_UPDATES = "updates"

    private const val ID_ATTENTION = 10
    private const val ID_UPDATE = 11
    private const val ID_CONFLICT_BASE = 1000

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_SYNC, "Sync in progress", NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CHANNEL_CONFLICTS, "Conflicts", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "A file changed on both sides and both copies were kept" },
                NotificationChannel(CHANNEL_ATTENTION, "Needs attention", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "Syncing is paused until you act, e.g. Drive access expired" },
                NotificationChannel(CHANNEL_UPDATES, "App updates", NotificationManager.IMPORTANCE_DEFAULT),
            ),
        )
    }

    fun conflicts(context: Context, pairId: Long, pairName: String, conflictNames: List<String>) {
        if (conflictNames.isEmpty()) return
        val n = conflictNames.size
        val text = "Kept both copies: " + conflictNames.take(3).joinToString(", ") + if (n > 3) " and ${n - 3} more" else ""
        notify(
            context,
            (ID_CONFLICT_BASE + pairId).toInt(),
            NotificationCompat.Builder(context, CHANNEL_CONFLICTS)
                .setContentTitle(if (n == 1) "Conflict in $pairName" else "$n conflicts in $pairName")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(openApp(context, historyFor = pairId))
                .setAutoCancel(true),
        )
    }

    fun needsSignIn(context: Context) = notify(
        context,
        ID_ATTENTION,
        NotificationCompat.Builder(context, CHANNEL_ATTENTION)
            .setContentTitle("Drive sync is paused")
            .setContentText("Google access expired. Tap to grant access again.")
            .setContentIntent(openApp(context))
            .setOnlyAlertOnce(true)
            .setAutoCancel(true),
    )

    fun clearNeedsSignIn(context: Context) = NotificationManagerCompat.from(context).cancel(ID_ATTENTION)

    /** [action] is launched when tapped: the system install prompt or the "install unknown apps" setting. */
    fun update(context: Context, title: String, text: String, action: Intent?) = notify(
        context,
        ID_UPDATE,
        NotificationCompat.Builder(context, CHANNEL_UPDATES)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .apply {
                action?.let {
                    setContentIntent(
                        PendingIntent.getActivity(
                            context, ID_UPDATE, it,
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                        ),
                    )
                }
            },
    )

    private fun openApp(context: Context, historyFor: Long? = null): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        historyFor?.let { intent.putExtra(MainActivity.EXTRA_HISTORY_PAIR, it) }
        return PendingIntent.getActivity(
            context, historyFor?.toInt() ?: 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    @SuppressLint("MissingPermission") // checked via areNotificationsEnabled (covers pre-13 too)
    private fun notify(context: Context, id: Int, builder: NotificationCompat.Builder) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        builder.setSmallIcon(R.drawable.ic_launcher_monochrome)
        nm.notify(id, builder.build())
    }
}
