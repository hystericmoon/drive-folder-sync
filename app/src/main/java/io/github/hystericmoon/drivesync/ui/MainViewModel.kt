package io.github.hystericmoon.drivesync.ui

import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.hystericmoon.drivesync.DriveSyncApp
import io.github.hystericmoon.drivesync.data.FolderPair
import io.github.hystericmoon.drivesync.drive.DriveClient
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

    fun driveClient(): DriveClient = app.engine.driveClient()

    fun signIn(activity: Activity) = viewModelScope.launch {
        _auth.update { it.copy(busy = true, message = null) }
        runCatching { app.auth.signIn(activity) }
            .onSuccess { email -> _auth.update { it.copy(email = email) }; requestDriveAccess() }
            .onFailure { e -> _auth.update { it.copy(busy = false, message = "Sign-in failed: ${e.message}") } }
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
            }
            .onFailure { e -> _auth.update { it.copy(busy = false, message = "Drive access failed: ${e.message}") } }
    }

    fun onConsentResult(data: Intent?) {
        val ok = runCatching { app.auth.resultFromIntent(data).accessToken != null }.getOrDefault(false)
        _auth.update {
            it.copy(consent = null, driveReady = ok, message = if (ok) null else "Drive access wasn't granted.")
        }
        if (ok) pairs.value.forEach(app.scheduler::schedule)
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
