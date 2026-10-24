package io.github.hystericmoon.drivesync.update

import android.app.Application
import io.github.hystericmoon.drivesync.update.Updater.ApkFacts
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/**
 * Self-update against a fake GitHub API. If this logic breaks, the phone stays on a broken build
 * for good, so every way it can pick the wrong release or accept the wrong APK is pinned here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class UpdaterTest {
    private val server = MockWebServer()
    private val context = RuntimeEnvironment.getApplication()

    @Before fun setUp() = server.start()
    @After fun tearDown() = server.shutdown()

    private fun updater(installed: Int) =
        Updater(context, apiBase = server.url("/").toString().trimEnd('/'), repo = "owner/app", installedVersionCode = installed)

    private fun release(
        tag: String,
        prerelease: Boolean = false,
        draft: Boolean = false,
        assets: List<String> = listOf("drive-folder-sync-${tag.removePrefix("v")}.apk"),
    ) = JSONObject()
        .put("tag_name", tag)
        .put("prerelease", prerelease)
        .put("draft", draft)
        .put(
            "assets",
            JSONArray(assets.map { JSONObject().put("name", it).put("browser_download_url", "https://example.invalid/$it") }),
        )

    private fun serve(json: JSONObject) = server.enqueue(MockResponse().setBody(json.toString()))

    // ---- picking the release ----

    @Test fun newerFullReleaseIsOffered() = runBlocking {
        serve(release("v0.1.15"))
        val update = updater(installed = 14).checkForUpdate()!!
        assertEquals(15, update.versionCode)
        assertEquals("0.1.15", update.versionName)
        assertEquals("https://example.invalid/drive-folder-sync-0.1.15.apk", update.apkUrl)
        val request = server.takeRequest()
        assertEquals("/repos/owner/app/releases/latest", request.path)
        assertEquals("application/vnd.github+json", request.getHeader("Accept"))
    }

    @Test fun versionsCompareAsNumbersNotText() = runBlocking {
        // "0.1.15" < "0.1.9" as text; as version codes 15 > 9.
        serve(release("v0.1.15"))
        assertEquals(15, updater(installed = 9).checkForUpdate()?.versionCode)
        serve(release("v0.1.9"))
        assertNull(updater(installed = 15).checkForUpdate())
    }

    @Test fun sameVersionIsNotAnUpdate() = runBlocking {
        serve(release("v0.1.15"))
        assertNull(updater(installed = 15).checkForUpdate())
    }

    @Test fun preReleasesAndDraftsAreIgnored() {
        assertNull(Updater.parseRelease(release("v0.1.99", prerelease = true), installedVersionCode = 1))
        assertNull(Updater.parseRelease(release("v0.1.99", draft = true), installedVersionCode = 1))
    }

    @Test fun releaseWithoutApkOrWithOddTagIsIgnored() {
        assertNull(Updater.parseRelease(release("v0.1.20", assets = listOf("notes.txt")), 1))
        assertNull(Updater.parseRelease(release("v0.1.20", assets = emptyList()), 1))
        assertNull(Updater.parseRelease(release("nightly"), 1))
        assertNull(Updater.parseRelease(JSONObject(), 1))
    }

    @Test fun picksTheApkAmongOtherAssets() {
        val update = Updater.parseRelease(release("v0.1.20", assets = listOf("checksums.txt", "app.apk")), 1)!!
        assertEquals("https://example.invalid/app.apk", update.apkUrl)
    }

    @Test fun noReleaseYetIsNotAnError() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        assertNull(updater(installed = 1).checkForUpdate())
    }

    @Test fun githubErrorsAreReportedSoTheWorkerRetries() {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"message":"API rate limit exceeded"}"""))
        assertThrows(IOException::class.java) { runBlocking { updater(installed = 1).checkForUpdate() } }
        server.enqueue(MockResponse().setResponseCode(502))
        assertThrows(IOException::class.java) { runBlocking { updater(installed = 1).checkForUpdate() } }
    }

    // ---- downloading ----

    @Test fun downloadWritesTheApkAndReplacesOldOnes() = runBlocking {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        File(dir, "update-3.apk").writeText("old")
        server.enqueue(MockResponse().setBody("APK BYTES"))
        val apk = updater(installed = 3).download(AppUpdate(4, "0.1.4", server.url("/a.apk").toString()))
        assertEquals("APK BYTES", apk.readText())
        assertEquals(listOf("update-4.apk"), dir.list()!!.toList())
    }

    @Test fun failedDownloadLeavesNothingBehind() {
        server.enqueue(MockResponse().setResponseCode(500))
        assertThrows(IOException::class.java) {
            runBlocking { updater(installed = 3).download(AppUpdate(4, "0.1.4", server.url("/a.apk").toString())) }
        }
        assertTrue(File(context.cacheDir, "updates").list()!!.isEmpty())
    }

    // ---- verifying the APK before Android sees it ----

    private val installed = ApkFacts("io.github.hystericmoon.drivesync", 14, setOf("our-key"))

    @Test fun apkSignedWithOurKeyPasses() {
        Updater.checkApk(installed.copy(versionCode = 15), installed, expectedCode = 15)
    }

    @Test fun apkSignedWithAnotherKeyIsRefused() {
        assertThrows(SecurityException::class.java) {
            Updater.checkApk(installed.copy(versionCode = 15, signers = setOf("someone-else")), installed, 15)
        }
        // An extra signer is not "the same key" either.
        assertThrows(SecurityException::class.java) {
            Updater.checkApk(installed.copy(versionCode = 15, signers = setOf("our-key", "someone-else")), installed, 15)
        }
    }

    @Test fun unsignedApkIsRefused() {
        assertThrows(SecurityException::class.java) {
            Updater.checkApk(installed.copy(versionCode = 15, signers = emptySet()), installed.copy(signers = emptySet()), 15)
        }
    }

    @Test fun apkForAnotherAppIsRefused() {
        assertThrows(SecurityException::class.java) {
            Updater.checkApk(installed.copy(packageName = "com.example.other", versionCode = 15), installed, 15)
        }
    }

    @Test fun apkOlderThanTheReleaseOrNotAnApkIsRefused() {
        assertThrows(IOException::class.java) { Updater.checkApk(installed.copy(versionCode = 14), installed, 15) }
        assertThrows(IOException::class.java) { Updater.checkApk(null, installed, 15) }
    }

    @Test fun autoUpdateIsOnByDefaultAndRemembered() {
        val u = updater(installed = 1)
        assertTrue(u.autoUpdate)
        u.autoUpdate = false
        assertFalse(updater(installed = 1).autoUpdate)
    }
}
