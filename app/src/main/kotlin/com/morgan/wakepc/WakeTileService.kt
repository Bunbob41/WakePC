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
    // which at worst cuts the status polling short: the command already ran.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onStartListening() {
        updateTile(subtitle = null, state = Tile.STATE_INACTIVE)
        scope.launch {
            val target = tileTarget()
            if (target == null) {
                updateTile(subtitle = "Tap to set up", state = Tile.STATE_INACTIVE)
            } else if (target.command.ping) {
                val now = WakeApi.status(target.connection, target.command.name).getOrNull()
                if (now != null && now != Readiness.ASLEEP) {
                    updateTile(subtitle = tileSubtitle(target.machine.name, now), state = Tile.STATE_ACTIVE)
                }
            }
        }
    }

    override fun onClick() {
        scope.launch {
            val target = tileTarget()
            if (target == null) {
                openApp()
                return@launch
            }
            // The tile cannot show a keypad, and unlike a widget it was never
            // authorised at placement. Hand an elevated command to the app,
            // which can ask properly.
            if (target.command.elevated) {
                updateTile(subtitle = "Confirm in the app", state = Tile.STATE_INACTIVE)
                openApp()
                return@launch
            }

            updateTile(subtitle = "Running…", state = Tile.STATE_ACTIVE)
            WakeApi.run(target.connection, target.command.name).exceptionOrNull()?.let { failure ->
                val relay = target.connection.name
                val why =
                    when (failure.failureKind) {
                        FailureKind.NOT_RECOGNISED -> "$relay doesn't recognise this phone"
                        FailureKind.NOT_FOUND, FailureKind.OLD_RELAY -> "$relay no longer has this button"
                        else -> "Couldn't reach $relay"
                    }
                updateTile(subtitle = why, state = Tile.STATE_INACTIVE)
                return@launch
            }
            if (!target.command.ping) {
                updateTile(subtitle = "Done", state = Tile.STATE_ACTIVE)
                return@launch
            }
            var last: Readiness? = null
            repeat(POLL_ATTEMPTS) {
                delay(POLL_INTERVAL_MS)
                val now = WakeApi.status(target.connection, target.command.name).getOrNull() ?: return@repeat
                last = now
                if (now.usable) {
                    updateTile(subtitle = tileSubtitle(target.machine.name, now), state = Tile.STATE_ACTIVE)
                    return@launch
                }
                if (now == Readiness.UP) {
                    updateTile(subtitle = tileSubtitle(target.machine.name, now), state = Tile.STATE_ACTIVE)
                }
            }
            updateTile(
                subtitle = if (last == Readiness.UP) "${target.machine.name} up, not ready yet" else "Sent, PC not answering yet",
                state = Tile.STATE_INACTIVE,
            )
        }
    }

    private suspend fun tileTarget(): ResolvedButton? {
        val state = Store(applicationContext).current()
        return state.resolve(state.tile) ?: state.resolve(state.hero)
    }

    private fun openApp() {
        val intent =
            Intent(this, MainActivity::class.java)
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

    private fun updateTile(
        subtitle: String?,
        state: Int,
    ) {
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
        // 90 seconds, as in the app: a PC is UP well before it is READY.
        const val POLL_ATTEMPTS = 18
        const val POLL_INTERVAL_MS = 5_000L
    }
}

internal fun tileSubtitle(
    machine: String,
    readiness: Readiness,
): String =
    when (readiness) {
        Readiness.READY -> "$machine ready"
        Readiness.UP -> "$machine starting up"
        Readiness.AWAKE -> "$machine awake"
        Readiness.ASLEEP -> "$machine asleep"
    }
