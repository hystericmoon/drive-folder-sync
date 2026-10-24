package io.github.hystericmoon.drivesync.device

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.github.hystericmoon.drivesync.local.SafLocalFs
import org.junit.rules.ExternalResource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * An emptied folder in the emulator's shared storage (Download/drivesync-ci), granted to the app
 * through the real system folder picker, exactly as on the phone, and reached through Android's
 * real ExternalStorageProvider. CI creates the folder with adb before the tests start.
 */
class SharedStorageFolder : ExternalResource() {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    private val resolver = context.contentResolver

    lateinit var treeUri: Uri private set
    lateinit var fs: SafLocalFs private set

    override fun before() {
        treeUri = grantedTree()
        fs = SafLocalFs(context, treeUri)
        check(fs.hasPermission()) { "no persisted permission for $treeUri" }
        clear()
    }

    override fun after() = clear()

    private fun clear() {
        for (child in fs.children(fs.rootDocId, "")) fs.delete(child.docId)
    }

    /** Reuses the grant from an earlier test, or walks the picker: "Use this folder", then "Allow". One retry. */
    private fun grantedTree(): Uri {
        resolver.persistedUriPermissions.firstOrNull { it.uri.toString().endsWith(Uri.encode(FOLDER)) }?.let { return it.uri }
        return runCatching { pickTree() }.getOrElse { pickTree() }
    }

    private fun pickTree(): Uri {
        val initial = DocumentsContract.buildDocumentUri(AUTHORITY, FOLDER)
        val picked = CountDownLatch(1)
        var result: Uri? = null
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.activityResultRegistry
                    .register("tree", ActivityResultContracts.OpenDocumentTree()) { uri -> result = uri; picked.countDown() }
                    .launch(initial)
            }
            val device = UiDevice.getInstance(instrumentation)
            if (Build.VERSION.SDK_INT >= 30) {
                // Android 11+: "Use this folder", then always an "Allow" dialog.
                device.wait(Until.findObject(By.text(Pattern.compile("(?i)use this folder"))), 20_000)?.click()
                    ?: error("folder picker didn't show \"Use this folder\"")
                device.wait(Until.findObject(By.text(Pattern.compile("(?i)allow"))), 10_000)?.click()
                    ?: error("folder picker didn't ask to allow access")
            } else {
                // Android 10 words it differently, and may or may not ask again.
                device.wait(Until.findObject(By.text(Pattern.compile("(?i)(select|allow access.*)"))), 20_000)?.click()
                    ?: error("folder picker didn't show its confirm button")
                device.wait(Until.findObject(By.text(Pattern.compile("(?i)allow"))), 10_000)?.click()
            }
            check(picked.await(15, TimeUnit.SECONDS)) { "no result from the folder picker" }
        }
        val uri = checkNotNull(result) { "folder picker returned nothing" }
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        return uri
    }

    // ---- helpers acting "as the user", through the same provider ----

    fun write(path: String, text: String): String {
        val parent = mkdirs(path.substringBeforeLast('/', ""))
        val name = path.substringAfterLast('/')
        val docId = fs.findChild(parent, name)?.docId ?: fs.createFile(parent, name)
        fs.openOutput(docId).use { it.write(text.toByteArray()) }
        return docId
    }

    fun mkdirs(path: String): String {
        var current = fs.rootDocId
        if (path.isEmpty()) return current
        for (segment in path.split('/')) {
            current = fs.findChild(current, segment)?.docId ?: fs.createDir(current, segment)
        }
        return current
    }

    fun docId(path: String): String? {
        var current = fs.rootDocId
        for (segment in path.split('/')) current = fs.findChild(current, segment)?.docId ?: return null
        return current
    }

    fun read(path: String): String? = docId(path)?.let { id -> fs.openInput(id).use { String(it.readBytes()) } }

    fun remove(path: String) = fs.delete(requireNotNull(docId(path)) { "no $path" })

    /** Every file below the root (including .sync-trash): path → text. */
    fun allFiles(): Map<String, String> {
        val out = sortedMapOf<String, String>()
        fun walk(id: String, prefix: String) {
            for (c in fs.children(id, prefix)) {
                if (c.isDir) walk(c.docId, c.path) else out[c.path] = fs.openInput(c.docId).use { String(it.readBytes()) }
            }
        }
        walk(fs.rootDocId, "")
        return out
    }

    companion object {
        const val AUTHORITY = "com.android.externalstorage.documents"
        const val FOLDER = "primary:Download/drivesync-ci"
    }
}
