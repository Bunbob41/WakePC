package com.morgan.wakepc

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class WakeTileService : TileService() {

    // The QS panel can unbind this service at any time; the scope dies with it,
    // which at worst cuts the status polling short — the wake was already sent.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onStartListening() {
        updateTile(subtitle = null, state = Tile.STATE_INACTIVE)
        scope.launch {
            val settings = SettingsRepository(applicationContext).current()
            if (!settings.isConfigured) {
                updateTile(subtitle = "Tap to set up", state = Tile.STATE_INACTIVE)
            } else if (WakeApi.status(settings).getOrDefault(false)) {
                updateTile(subtitle = "PC awake", state = Tile.STATE_ACTIVE)
            }
        }
    }

    override fun onClick() {
        scope.launch {
            val settings = SettingsRepository(applicationContext).current()
            if (!settings.isConfigured) {
                openSettingsScreen()
                return@launch
            }

            updateTile(subtitle = "Waking…", state = Tile.STATE_ACTIVE)
            if (WakeApi.wake(settings).isFailure) {
                updateTile(subtitle = "Couldn't reach Pi", state = Tile.STATE_INACTIVE)
                return@launch
            }

            repeat(POLL_ATTEMPTS) {
                delay(POLL_INTERVAL_MS)
                if (WakeApi.status(settings).getOrDefault(false)) {
                    updateTile(subtitle = "PC awake", state = Tile.STATE_ACTIVE)
                    return@launch
                }
            }
            updateTile(subtitle = "Sent — no ping yet", state = Tile.STATE_INACTIVE)
        }
    }

    private fun openSettingsScreen() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun updateTile(subtitle: String?, state: Int) {
        qsTile?.apply {
            this.subtitle = subtitle
            this.state = state
            updateTile()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val POLL_ATTEMPTS = 8
        const val POLL_INTERVAL_MS = 5_000L
    }
}
