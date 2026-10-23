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
class Updater(private val context: Context) {
    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val http = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()

    var autoUpdate: Boolean
        get() = prefs.getBoolean(KEY_AUTO, true)
        set(value) = prefs.edit { putBoolean(KEY_AUTO, value) }

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    /** Returns the latest release if it's newer than what's installed. */
    suspend fun checkForUpdate(): AppUpdate? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        http.newCall(request).execute().use { resp ->
            if (resp.code == 404) return@withContext null // no full release yet
            if (!resp.isSuccessful) throw IOException("GitHub returned ${resp.code}")
            val release = JSONObject(resp.body!!.string())
            // Tags are "v0.1.<versionCode>" (see release.yml).
            val tag = release.getString("tag_name")
            val code = tag.substringAfterLast('.').toIntOrNull() ?: return@withContext null
            val assets = release.optJSONArray("assets") ?: return@withContext null
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.getString("name").endsWith(".apk") } ?: return@withContext null
            AppUpdate(code, tag.removePrefix("v"), apk.getString("browser_download_url"))
                .takeIf { it.versionCode > BuildConfig.VERSION_CODE }
        }
    }

    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Settings screen where the user allows this app to install updates (one-time). */
    fun installPermissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Downloads, verifies and hands the APK to the system installer. Result arrives in [InstallResultReceiver]. */
    suspend fun downloadAndInstall(update: AppUpdate) = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val apk = File(dir, "update-${update.versionCode}.apk")
        http.newCall(Request.Builder().url(update.apkUrl).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Download failed (${resp.code})")
            apk.outputStream().use { out -> resp.body!!.byteStream().use { it.copyTo(out) } }
        }
        verify(apk, update.versionCode)
        install(apk)
    }

    private fun verify(apk: File, expectedCode: Int) {
        val pm = context.packageManager
        val archive = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: throw IOException("Downloaded file isn't a valid APK")
        if (archive.packageName != context.packageName) throw SecurityException("APK is for a different app")
        if (archive.longVersionCode < expectedCode) throw IOException("APK version doesn't match the release")
        val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        if (signers(archive.signingInfo) != signers(installed.signingInfo)) {
            throw SecurityException("Update is signed with a different key; ignoring it")
        }
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

    companion object {
        private const val KEY_AUTO = "auto"
    }
}
