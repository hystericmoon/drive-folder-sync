package io.github.hystericmoon.drivesync.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.edit
import io.github.hystericmoon.drivesync.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class AppUpdate(val versionCode: Int, val versionName: String, val apkUrl: String)

/**
 * Self-update from this repo's GitHub Releases. Only full releases count (builds from the default
 * branch); pre-releases from other branches are ignored. The downloaded APK must carry the same
 * signing certificate as the installed app, otherwise it's discarded before Android ever sees it.
 */
class Updater internal constructor(
    private val context: Context,
    private val apiBase: String,
    private val repo: String,
    private val installedVersionCode: Int,
) {
    constructor(context: Context) : this(context, "https://api.github.com", BuildConfig.UPDATE_REPO, BuildConfig.VERSION_CODE)

    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val http = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()

    var autoUpdate: Boolean
        get() = prefs.getBoolean(KEY_AUTO, true)
        set(value) = prefs.edit { putBoolean(KEY_AUTO, value) }

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    /** Returns the latest release if it's newer than what's installed. */
    suspend fun checkForUpdate(): AppUpdate? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$apiBase/repos/$repo/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        http.newCall(request).execute().use { resp ->
            if (resp.code == 404) return@withContext null // no full release yet
            if (!resp.isSuccessful) throw IOException("GitHub returned ${resp.code}")
            parseRelease(JSONObject(resp.body!!.string()), installedVersionCode)
        }
    }

    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Settings screen where the user allows this app to install updates (one-time). */
    fun installPermissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Downloads, verifies and hands the APK to the system installer. Result arrives in [InstallResultReceiver]. */
    suspend fun downloadAndInstall(update: AppUpdate) = withContext(Dispatchers.IO) {
        val apk = download(update)
        verify(apk, update.versionCode)
        install(apk)
    }

    /** Fetches the APK into the cache, replacing older downloads. Leaves nothing behind on failure. */
    internal suspend fun download(update: AppUpdate): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val apk = File(dir, "update-${update.versionCode}.apk")
        try {
            http.newCall(Request.Builder().url(update.apkUrl).build()).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("Download failed (${resp.code})")
                apk.outputStream().use { out -> resp.body!!.byteStream().use { it.copyTo(out) } }
            }
        } catch (e: Exception) {
            apk.delete()
            throw e
        }
        apk
    }

    private fun verify(apk: File, expectedCode: Int) {
        val pm = context.packageManager
        val archive = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)
        val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        checkApk(
            archive = archive?.let { ApkFacts(it.packageName, it.longVersionCode, signers(it.signingInfo)) },
            installed = ApkFacts(installed.packageName, installed.longVersionCode, signers(installed.signingInfo)),
            expectedCode = expectedCode,
        )
    }

    private fun signers(info: android.content.pm.SigningInfo?): Set<String> =
        info?.apkContentsSigners.orEmpty().mapTo(HashSet()) { sig ->
            MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        }

    private fun install(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            // Silent once this app is the installer of record (i.e. after the first self-update).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val status = PendingIntent.getBroadcast(
                context,
                sessionId,
                Intent(context, InstallResultReceiver::class.java),
                // Mutable: the installer fills in status extras.
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            session.commit(status.intentSender)
        }
    }

    /** What [checkApk] needs to know about an APK: package, version and SHA-256 of each signing cert. */
    internal data class ApkFacts(val packageName: String, val versionCode: Long, val signers: Set<String>)

    companion object {
        private const val KEY_AUTO = "auto"

        /**
         * Reads GitHub's "latest release" JSON. Tags are "v0.1.<versionCode>" (see release.yml).
         * Returns null unless it's a published full release with an APK and a higher version code.
         */
        internal fun parseRelease(release: JSONObject, installedVersionCode: Int): AppUpdate? {
            // /releases/latest never returns these, but a pre-release must never reach the phone.
            if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null
            val tag = release.optString("tag_name")
            val code = tag.substringAfterLast('.').toIntOrNull() ?: return null
            val assets = release.optJSONArray("assets") ?: return null
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk") } ?: return null
            return AppUpdate(code, tag.removePrefix("v"), apk.getString("browser_download_url"))
                .takeIf { it.versionCode > installedVersionCode }
        }

        /** Throws unless [archive] is this app, at least [expectedCode], and signed by exactly the installed key(s). */
        internal fun checkApk(archive: ApkFacts?, installed: ApkFacts, expectedCode: Int) {
            if (archive == null) throw IOException("Downloaded file isn't a valid APK")
            if (archive.packageName != installed.packageName) throw SecurityException("APK is for a different app")
            if (archive.versionCode < expectedCode) throw IOException("APK version doesn't match the release")
            if (archive.signers.isEmpty() || archive.signers != installed.signers) {
                throw SecurityException("Update is signed with a different key; ignoring it")
            }
        }
    }
}
