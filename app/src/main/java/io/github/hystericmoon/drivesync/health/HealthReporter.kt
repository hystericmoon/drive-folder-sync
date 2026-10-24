package io.github.hystericmoon.drivesync.health

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import io.github.hystericmoon.drivesync.BuildConfig
import io.github.hystericmoon.drivesync.auth.GoogleAuth
import io.github.hystericmoon.drivesync.data.AppDatabase
import io.github.hystericmoon.drivesync.data.LogKind
import io.github.hystericmoon.drivesync.drive.DriveApi
import io.github.hystericmoon.drivesync.drive.DriveHttpException
import io.github.hystericmoon.drivesync.sync.SkipNote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.time.Clock
import java.time.Instant

/**
 * Keeps a small status file, "Drive Folder Sync health.json", at the top of the user's My Drive:
 * installed version (so a self-update can be confirmed), each pair's last result, recent runs
 * and problems, and the phone settings that stop background work. The owner doesn't test on the
 * phone; this file is how a later session sees what the app is really doing there.
 *
 * Reporting must never get in the way of syncing, so nothing here throws.
 */
class HealthReporter internal constructor(
    private val context: Context,
    private val db: AppDatabase,
    private val newDrive: () -> DriveApi,
    private val clock: Clock,
    private val versionName: String,
    private val versionCode: Int,
    /** Where the file goes. "root" is Drive's alias for My Drive, which can't be a pair folder. */
    private val parentId: String = "root",
) {
    constructor(context: Context, db: AppDatabase, auth: GoogleAuth) : this(
        context, db, auth::driveClient, Clock.systemDefaultZone(), BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE,
    )

    private val prefs = context.getSharedPreferences("health", Context.MODE_PRIVATE)

    /** Call once per process start. A different version than last time means an update was installed. */
    fun noteAppStart() {
        val last = prefs.getInt(KEY_SEEN_VERSION, -1)
        if (last == versionCode) return
        noteEvent(if (last < 0) "Installed $versionName" else "Updated from 0.1.$last to $versionName")
        prefs.edit { putInt(KEY_SEEN_VERSION, versionCode) }
    }

    /** Something worth seeing later: update checks and installs, sign-in expiry. Keeps the last [KEEP_EVENTS]. */
    fun noteEvent(text: String) = append(KEY_EVENTS, KEEP_EVENTS, JSONObject().put("at", now()).put("event", text))

    /** One sync run. [trigger] is "scheduled" or "manual". Keeps the last [KEEP_RUNS]. */
    fun noteRun(pairId: Long, trigger: String, outcome: String) =
        append(KEY_RUNS, KEEP_RUNS, JSONObject().put("at", now()).put("pair", pairId).put("trigger", trigger).put("outcome", outcome))

    /**
     * Writes the report to Drive, at most every [MIN_INTERVAL_MS] unless the version changed since
     * the last write. Returns whether it was written; failures are swallowed.
     */
    suspend fun publish(): Boolean {
        val due = clock.millis() - prefs.getLong(KEY_PUBLISHED_AT, 0) >= MIN_INTERVAL_MS ||
            prefs.getInt(KEY_PUBLISHED_VERSION, -1) != versionCode
        if (!due) return false
        return try {
            upload(buildReport().toString(2).toByteArray())
            prefs.edit { putLong(KEY_PUBLISHED_AT, clock.millis()); putInt(KEY_PUBLISHED_VERSION, versionCode) }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun upload(bytes: ByteArray) {
        val drive = newDrive()
        suspend fun put(existingId: String?) =
            drive.upload(FILE_NAME, parentId, existingId, "application/json", bytes.size.toLong(), { ByteArrayInputStream(bytes) })

        val known = prefs.getString(KEY_FILE_ID, null) ?: drive.findChild(parentId, FILE_NAME)?.id
        val updated = known?.let { id ->
            try {
                put(id)
            } catch (e: DriveHttpException) {
                if (e.code != 404) throw e // only "gone" means start over; anything else is retried next time
                null
            }
        }
        // Never written, deleted, or trashed by the user: start a fresh file.
        val file = updated?.takeUnless { it.trashed } ?: put(null)
        prefs.edit { putString(KEY_FILE_ID, file.id) }
    }

    internal suspend fun buildReport(): JSONObject {
        val pkg = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        val power = context.getSystemService(PowerManager::class.java)
        val activity = context.getSystemService(ActivityManager::class.java)
        val pairs = db.pairs().all()
        val problems = db.log().observeAll(200).first()
            .filter { it.kind == LogKind.ERROR || it.kind == LogKind.CONFLICT }
            .take(KEEP_PROBLEMS)
        return JSONObject()
            .put("about", "Status written by the Drive Folder Sync app after syncs. Safe to delete; it's recreated.")
            .put("writtenAt", now())
            .put(
                "app",
                JSONObject()
                    .put("version", versionName)
                    .put("versionCode", versionCode)
                    .put("installedAt", pkg?.let { iso(it.lastUpdateTime) })
                    .put("autoUpdate", context.getSharedPreferences("updates", Context.MODE_PRIVATE).getBoolean("auto", true)),
            )
            .put(
                "phone",
                JSONObject()
                    .put("android", Build.VERSION.SDK_INT)
                    .put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
                    // The usual reasons background syncs stop: battery optimisation and background restriction.
                    .put("batteryUnrestricted", power?.isIgnoringBatteryOptimizations(context.packageName))
                    .put("backgroundRestricted", activity?.isBackgroundRestricted)
                    .put("notificationsEnabled", NotificationManagerCompat.from(context).areNotificationsEnabled()),
            )
            .put(
                "pairs",
                JSONArray(
                    pairs.map { p ->
                        JSONObject()
                            .put("id", p.id)
                            .put("phoneFolder", p.localName)
                            .put("driveFolder", p.driveFolderName)
                            .put("wifiOnly", p.wifiOnly)
                            .put("chargingOnly", p.chargingOnly)
                            .put("firstSyncDone", p.initialSyncDone)
                            .put("lastSyncAt", p.lastSyncAt.takeIf { it > 0 }?.let(::iso))
                            .put("lastFullScanAt", p.lastFullScanAt.takeIf { it > 0 }?.let(::iso))
                            .put("lastSummary", p.lastSummary)
                            .put("lastError", p.lastError)
                            .put("skipped", SkipNote.decode(p.skippedItems).size)
                            .put("excludeRules", p.excludePatterns.lines().count { it.isNotBlank() && !it.trim().startsWith("#") })
                    },
                ),
            )
            .put("runs", read(KEY_RUNS))
            .put("events", read(KEY_EVENTS))
            .put(
                "problems",
                JSONArray(
                    problems.map {
                        JSONObject().put("at", iso(it.time)).put("pair", it.pairId).put("kind", it.kind.name)
                            .put("path", it.path).put("detail", it.detail)
                    },
                ),
            )
    }

    private fun append(key: String, keep: Int, entry: JSONObject) {
        val list = read(key)
        list.put(entry)
        val trimmed = JSONArray()
        for (i in maxOf(0, list.length() - keep) until list.length()) trimmed.put(list.get(i))
        prefs.edit { putString(key, trimmed.toString()) }
    }

    private fun read(key: String): JSONArray = runCatching { JSONArray(prefs.getString(key, "[]")) }.getOrDefault(JSONArray())

    private fun now() = iso(clock.millis())
    private fun iso(millis: Long) = Instant.ofEpochMilli(millis).toString()

    companion object {
        const val FILE_NAME = "Drive Folder Sync health.json"
        const val MIN_INTERVAL_MS = 10L * 60 * 1000
        private const val KEEP_RUNS = 40
        private const val KEEP_EVENTS = 20
        private const val KEEP_PROBLEMS = 20
        private const val KEY_SEEN_VERSION = "seenVersion"
        private const val KEY_EVENTS = "events"
        private const val KEY_RUNS = "runs"
        private const val KEY_FILE_ID = "fileId"
        private const val KEY_PUBLISHED_AT = "publishedAt"
        private const val KEY_PUBLISHED_VERSION = "publishedVersion"
    }
}
