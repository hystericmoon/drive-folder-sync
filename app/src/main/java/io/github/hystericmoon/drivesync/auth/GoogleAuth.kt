package io.github.hystericmoon.drivesync.auth

import android.accounts.Account
import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import io.github.hystericmoon.drivesync.BuildConfig
import io.github.hystericmoon.drivesync.drive.DriveClient
import kotlinx.coroutines.tasks.await

/** Thrown when Drive access needs the user to approve a consent screen (not possible from a background job). */
class NeedsUserConsentException : Exception("Google sign-in needs attention. Open the app and sign in again.")

/**
 * Credential Manager identifies the account; AuthorizationClient grants the full `drive` scope for it.
 * Once granted, [accessToken] works silently from background workers.
 */
class GoogleAuth(private val context: Context) {

    private val prefs = context.getSharedPreferences("auth", Context.MODE_PRIVATE)

    val accountEmail: String? get() = prefs.getString(KEY_EMAIL, null)

    val isConfigured: Boolean get() = BuildConfig.WEB_CLIENT_ID.isNotBlank()

    /** Shows the "Sign in with Google" sheet and remembers the chosen account. */
    suspend fun signIn(activity: Activity): String {
        val option = GetSignInWithGoogleOption.Builder(BuildConfig.WEB_CLIENT_ID).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val credential = CredentialManager.create(activity).getCredential(activity, request).credential
        require(
            credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL,
        ) { "Unexpected credential type" }
        val email = GoogleIdTokenCredential.createFrom(credential.data).id
        prefs.edit { putString(KEY_EMAIL, email) }
        return email
    }

    suspend fun signOut() {
        prefs.edit { remove(KEY_EMAIL) }
        runCatching { CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest()) }
    }

    /** Result may carry a pendingIntent (consent screen) the UI must launch. */
    suspend fun authorize(): AuthorizationResult =
        Identity.getAuthorizationClient(context).authorize(request()).await()

    fun resultFromIntent(data: Intent?): AuthorizationResult =
        Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data)

    /** Silent token for API calls. Play services caches and refreshes it. */
    suspend fun accessToken(): String {
        val result = authorize()
        if (result.hasResolution()) throw NeedsUserConsentException()
        return result.accessToken ?: throw NeedsUserConsentException()
    }

    /** Drive client that authenticates with this account. */
    fun driveClient() = DriveClient(token = ::accessToken, invalidateToken = ::invalidate)

    /** Drops a token Drive rejected (401) so the next [accessToken] call fetches a fresh one. */
    suspend fun invalidate(token: String) {
        runCatching {
            Identity.getAuthorizationClient(context)
                .clearToken(ClearTokenRequest.builder().setToken(token).build())
                .await()
        }
    }

    private fun request(): AuthorizationRequest {
        val builder = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(DRIVE_SCOPE)))
        accountEmail?.let { builder.setAccount(Account(it, "com.google")) }
        return builder.build()
    }

    companion object {
        const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive"
        private const val KEY_EMAIL = "email"
    }
}
