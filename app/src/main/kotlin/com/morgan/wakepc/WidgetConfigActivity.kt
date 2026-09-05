package com.morgan.wakepc

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Runs when a widget is dropped on the home screen: pick which machine and
 * command *this* instance controls, so several widgets can target different
 * machines instead of all mirroring the app-wide hero.
 */
class WidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val appWidgetId =
            intent?.extras?.getInt(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID,
            ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        // Backing out must leave the widget unplaced, so refuse by default.
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val store = Store(applicationContext)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(surface = Palette.bg, background = Palette.bg)) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize().background(Palette.bg).safeDrawingPadding()) {
                        ChooseButton(store) { machineId, command ->
                            confirm(appWidgetId, machineId, command)
                        }
                    }
                }
            }
        }
    }

    private fun confirm(
        appWidgetId: Int,
        machineId: String,
        command: String,
    ) {
        lifecycleScope.launch {
            val glanceId = GlanceAppWidgetManager(this@WidgetConfigActivity).getGlanceIdBy(appWidgetId)
            updateAppWidgetState(this@WidgetConfigActivity, glanceId) { prefs ->
                prefs[selectedMachineKey] = machineId
                prefs[selectedCommandKey] = command
            }
            WakeWidget().update(this@WidgetConfigActivity, glanceId)
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
            finish()
        }
    }
}

@androidx.compose.runtime.Composable
private fun ChooseButton(
    store: Store,
    onPick: (machineId: String, command: String) -> Unit,
) {
    var state by remember { mutableStateOf<AppState?>(null) }
    LaunchedEffect(Unit) { state = store.current() }
    val current = state ?: return

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ConsoleText("THIS WIDGET RUNS", size = 13, weight = FontWeight.Bold, letterSpacing = 3.0)
        ConsoleText(
            "each widget can point at a different machine",
            size = 11,
            color = Palette.dim,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        if (current.machines.isEmpty()) {
            ConsoleText("no machines yet — set one up in the app first", size = 12, color = Palette.dim)
            return@Column
        }

        current.machines.forEach { machine ->
            val connection = current.connection(machine.connectionId)
            machine.commands.forEach { command ->
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(Palette.card, RoundedCornerShape(8.dp))
                            .border(1.dp, Palette.border, RoundedCornerShape(8.dp))
                            .clickable { onPick(machine.id, command.name) }
                            .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier =
                            Modifier
                                .size(8.dp)
                                .background(Color(connection?.color ?: 0xFF5F6871), CircleShape),
                    )
                    Column(modifier = Modifier.padding(start = 12.dp)) {
                        ConsoleText(machine.name.ifBlank { "unnamed" }, size = 14, weight = FontWeight.Medium)
                        ConsoleText(
                            "${command.name} · via ${connection?.name ?: "?"}",
                            size = 10,
                            color = Palette.dim,
                        )
                    }
                }
            }
        }
    }
}
