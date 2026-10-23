package io.github.hystericmoon.drivesync

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hystericmoon.drivesync.ui.AppScreen
import io.github.hystericmoon.drivesync.ui.ThemeMode
import io.github.hystericmoon.drivesync.ui.glass.DriveSyncTheme

class MainActivity : ComponentActivity() {
    /** Set when opened from a conflict notification: show that pair's history. */
    private val historyRequest = mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            // For conflict / sign-in / update notifications and the "Syncing…" notice.
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        handle(intent)
        val settings = (application as DriveSyncApp).settings
        setContent {
            val mode by settings.themeMode.collectAsStateWithLifecycle()
            val dynamic by settings.dynamicColor.collectAsStateWithLifecycle()
            val dark = when (mode) {
                ThemeMode.System -> isSystemInDarkTheme()
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }
            // Transparent system bars whose icons follow the app's theme, not just the system's.
            DisposableEffect(dark) {
                val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            DriveSyncTheme(dark = dark, dynamicColor = dynamic) {
                AppScreen(historyRequest = historyRequest.value, onHistoryShown = { historyRequest.value = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        intent?.getLongExtra(EXTRA_HISTORY_PAIR, -1)?.takeIf { it >= 0 }?.let { historyRequest.value = it }
    }

    companion object {
        const val EXTRA_HISTORY_PAIR = "historyPair"
    }
}
