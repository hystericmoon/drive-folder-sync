package io.github.hystericmoon.drivesync.ui

import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.hystericmoon.drivesync.DriveSyncApp
import io.github.hystericmoon.drivesync.Notifier
import io.github.hystericmoon.drivesync.data.SyncLogEntry
import io.github.hystericmoon.drivesync.data.FolderPair
import io.github.hystericmoon.drivesync.drive.DriveClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AuthState(
    val email: String? = null,
    val driveReady: Boolean = false,
    /** Consent screen the UI must launch to grant the Drive scope. */
    val consent: PendingIntent? = null,
    val busy: Boolean = false,
    val message: String? = null,
)

data class UpdateUi(
    val autoUpdate: Boolean,
    val currentVersion: String,
    val checking: Boolean = false,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as DriveSyncApp

    val webClientConfigured = app.auth.isConfigured
    val pairs: StateFlow<List<FolderPair>> =
        app.db.pairs().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val progress: StateFlow<Map<Long, String>> = app.engine.progress

    private val _auth = MutableStateFlow(AuthState(email = app.auth.accountEmail))
    val auth: StateFlow<AuthState> = _auth.asStateFlow()

    init {
        if (app.auth.accountEmail != null) requestDriveAccess()
    }

    /** One-shot snackbar text. */
    val snackbar = MutableStateFlow<String?>(null)
    /** One-shot intent the UI should start (install prompt / settings). */
    val launch = MutableStateFlow<Intent?>(null)

    private val _update = MutableStateFlow(UpdateUi(app.updater.autoUpdate, app.updater.currentVersion))
    val update: StateFlow<UpdateUi> = _update.asStateFlow()

    fun history(pairId: Long): Flow<List<SyncLogEntry>> = app.db.log().observe(pairId)

    fun setAutoUpdate(enabled: Boolean) {
        app.updater.autoUpdate = enabled
        app.scheduler.scheduleUpdateChecks(enabled)
        _update.update { it.copy(autoUpdate = enabled) }
    }

    fun checkForUpdates() = viewModelScope.launch {
        _update.update { it.copy(checking = true) }
        try {
            val update = app.updater.checkForUpdate()
            when {
                update == null -> snackbar.value = "You're on the latest version (${app.updater.currentVersion})."
                !app.updater.canInstall() -> {
                    snackbar.value = "Allow this app to install updates, then check again."
                    launch.value = app.updater.installPermissionIntent()
                }
                else -> {
                    snackbar.value = "Downloading ${update.versionName}…"
                    app.updater.downloadAndInstall(update)
                }
            }
        } catch (e: Exception) {
            snackbar.value = "Update check failed: ${e.message}"
        } finally {
            _update.update { it.copy(checking = false) }
        }
    }

    fun updateExcludes(pair: FolderPair, patterns: String) = viewModelScope.launch {
        app.db.pairs().get(pair.id)?.let { app.db.pairs().update(it.copy(excludePatterns = patterns)) }
    }

    fun driveClient(): DriveClient = app.engine.driveClient()

    fun signIn(activity: Activity) = viewModelScope.launch {
        _auth.update { it.copy(busy = true, message = null) }
        runCatching { app.auth.signIn(activity) }
            .onSuccess { email -> _auth.update { it.copy(email = email) }; requestDriveAccess() }
            .onFailure { e ->
                val message = when (e) {
                    is GetCredentialCancellationException -> null // user closed the sheet
                    is NoCredentialException -> "No Google account found. Add one in Android Settings → Accounts."
                    else -> "Sign-in failed: ${e.message}"
                }
                _auth.update { it.copy(busy = false, message = message) }
            }
    }

    fun requestDriveAccess() = viewModelScope.launch {
        _auth.update { it.copy(busy = true, message = null) }
        runCatching { app.auth.authorize() }
            .onSuccess { r ->
                _auth.update {
                    it.copy(
                        busy = false,
                        driveReady = !r.hasResolution() && r.accessToken != null,
                        consent = if (r.hasResolution()) r.pendingIntent else null,
                    )
                }
                if (!r.hasResolution()) Notifier.clearNeedsSignIn(app)
            }
            .onFailure { e -> _auth.update { it.copy(busy = false, message = "Drive access failed: ${e.message}") } }
    }

    fun onConsentResult(data: Intent?) {
        val ok = runCatching { app.auth.resultFromIntent(data).accessToken != null }.getOrDefault(false)
        _auth.update {
            it.copy(consent = null, driveReady = ok, message = if (ok) null else "Drive access wasn't granted.")
        }
        if (ok) {
            Notifier.clearNeedsSignIn(app)
            viewModelScope.launch {
                // Resume pairs that stopped while access was expired.
                app.db.pairs().all().forEach { app.scheduler.schedule(it); app.scheduler.syncNow(it.id) }
            }
        }
    }

    fun signOut() = viewModelScope.launch {
        app.auth.signOut()
        _auth.value = AuthState()
    }

    fun addPair(treeUri: Uri, localName: String, driveFolderId: String, driveFolderName: String) =
        viewModelScope.launch {
            val pair = FolderPair(
                localTreeUri = treeUri.toString(),
                localName = localName,
                driveFolderId = driveFolderId,
                driveFolderName = driveFolderName,
            )
            val id = app.db.pairs().insert(pair)
            app.scheduler.schedule(pair.copy(id = id))
            app.scheduler.syncNow(id)
        }

    fun updateConstraints(pair: FolderPair, wifiOnly: Boolean, chargingOnly: Boolean) = viewModelScope.launch {
        val updated = pair.copy(wifiOnly = wifiOnly, chargingOnly = chargingOnly)
        app.db.pairs().update(updated)
        app.scheduler.schedule(updated)
    }

    fun syncNow(pair: FolderPair) = app.scheduler.syncNow(pair.id)

    /** Stops syncing and forgets the pair. Files on both sides are left exactly as they are. */
    fun removePair(pair: FolderPair) = viewModelScope.launch {
        app.scheduler.cancel(pair.id)
        app.db.pairs().delete(pair.id)
        if (app.db.pairs().all().any { it.localTreeUri == pair.localTreeUri }) return@launch
        runCatching {
            getApplication<Application>().contentResolver.releasePersistableUriPermission(
                Uri.parse(pair.localTreeUri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }
}
