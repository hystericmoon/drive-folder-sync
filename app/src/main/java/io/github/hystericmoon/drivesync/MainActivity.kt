package io.github.hystericmoon.drivesync

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import io.github.hystericmoon.drivesync.ui.AppScreen
import io.github.hystericmoon.drivesync.ui.DriveSyncTheme

class MainActivity : ComponentActivity() {
    /** Set when opened from a conflict notification: show that pair's history. */
    private val historyRequest = mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) {
            // For conflict / sign-in / update notifications and the "Syncing…" notice.
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        handle(intent)
        setContent {
            DriveSyncTheme {
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
