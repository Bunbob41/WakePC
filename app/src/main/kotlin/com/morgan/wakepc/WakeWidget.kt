package com.morgan.wakepc

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import kotlinx.coroutines.delay

/**
 * Home-screen widget: one row per machine, tapped to fire that machine's
 * command. A widget action cannot hold a long polling loop, so a tap fires the
 * command and takes one quick look; the refresh control and the periodic
 * update in wake_widget_info.xml pick up the result afterwards.
 */
class WakeWidget : GlanceAppWidget() {
    override val stateDefinition = PreferencesGlanceStateDefinition

    // One widget that renders for its actual size, so the three providers below
    // (and any resize afterwards) all stay correct when the widget redraws.
    override val sizeMode =
        SizeMode.Responsive(
            setOf(
                DpSize(110.dp, 55.dp),
                DpSize(180.dp, 110.dp),
                DpSize(300.dp, 200.dp),
            ),
        )

    override suspend fun provideGlance(
        context: Context,
        id: GlanceId,
    ) {
        val appState = Store(context.applicationContext).current()
        provideContent { WidgetBody(appState) }
    }
}

class WakeWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WakeWidget()
}

/** 2x1 entry in the picker: just the hero. */
class WakeWidgetCompactReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WakeWidget()
}

/** 2x2 entry in the picker. */
class WakeWidgetMediumReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WakeWidget()
}

/** Per-machine status cached in the widget's own state, so it paints instantly. */
internal fun statusKey(machineId: String) = stringPreferencesKey("status_$machineId")

internal val machineIdParam = ActionParameters.Key<String>("machineId")
internal val commandParam = ActionParameters.Key<String>("command")

@Composable
private fun WidgetBody(appState: AppState) {
    val prefs = currentState<Preferences>()
    val height = LocalSize.current.height
    val showHeader = height >= HEADER_MIN_HEIGHT
    val showMachines = height >= MACHINES_MIN_HEIGHT
    Column(
        modifier =
            GlanceModifier
                .fillMaxSize()
                .background(ColorProvider(Palette.bg))
                .cornerRadius(16.dp)
                .padding(if (showHeader) 12.dp else 8.dp),
    ) {
        if (showHeader) {
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    provider = ImageProvider(R.drawable.ic_power_stroke),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(ColorProvider(Palette.red)),
                    modifier = GlanceModifier.size(12.dp),
                )
                Spacer(GlanceModifier.width(8.dp))
                Text(
                    "WAKEPC",
                    style = console(10, Palette.text, FontWeight.Bold),
                    modifier = GlanceModifier.defaultWeight().clickable(actionStartActivity<MainActivity>()),
                )
                Text(
                    "refresh",
                    style = console(9, Palette.dim),
                    modifier = GlanceModifier.clickable(actionRunCallback<RefreshAction>()),
                )
            }
            Spacer(GlanceModifier.height(8.dp))
        }

        val hero = appState.resolve(appState.hero)
        if (hero != null) {
            HeroRow(hero, prefs[statusKey(hero.machine.id)].orEmpty())
            Spacer(GlanceModifier.height(6.dp))
        }

        if (appState.machines.isEmpty()) {
            Text("no machines yet — tap to open", style = console(11, Palette.dim))
        } else if (showMachines) {
            appState.machines
                .filter { it.id != hero?.machine?.id }
                .forEach { machine ->
                    MachineRow(
                        machine = machine,
                        connection = appState.connection(machine.connectionId),
                        status = prefs[statusKey(machine.id)].orEmpty(),
                    )
                }
        }
    }
}

/** The configured hero, given the prominence it has in the app. */
@Composable
private fun HeroRow(
    hero: ResolvedButton,
    status: String,
) {
    Row(
        modifier =
            GlanceModifier
                .fillMaxWidth()
                .background(ColorProvider(Palette.heroBg))
                .cornerRadius(12.dp)
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .clickable(
                    actionRunCallback<RunAction>(
                        actionParametersOf(
                            machineIdParam to hero.machine.id,
                            commandParam to hero.command.name,
                        ),
                    ),
                ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            provider = ImageProvider(R.drawable.ic_power_stroke),
            contentDescription = null,
            colorFilter = ColorFilter.tint(ColorProvider(Palette.red)),
            modifier = GlanceModifier.size(20.dp),
        )
        Spacer(GlanceModifier.width(10.dp))
        Text(
            hero.machine.name.ifBlank { hero.command.name },
            style = console(15, Palette.text, FontWeight.Bold),
            modifier = GlanceModifier.defaultWeight(),
        )
        Text(status.ifBlank { "tap" }, style = console(10, statusColor(status)))
    }
}

@Composable
private fun MachineRow(
    machine: Machine,
    connection: Connection?,
    status: String,
) {
    val command = machine.commands.firstOrNull { it.ping } ?: machine.commands.firstOrNull()
    val tappable = command != null && connection != null
    Row(
        modifier =
            GlanceModifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
                .then(
                    if (tappable) {
                        GlanceModifier.clickable(
                            actionRunCallback<RunAction>(
                                actionParametersOf(
                                    machineIdParam to machine.id,
                                    commandParam to command.name,
                                ),
                            ),
                        )
                    } else {
                        GlanceModifier
                    },
                ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("●", style = console(11, Color(connection?.color ?: MISSING_COLOR)))
        Spacer(GlanceModifier.width(8.dp))
        Text(
            machine.name.ifBlank { "unnamed" },
            style = console(13, Palette.text),
            modifier = GlanceModifier.defaultWeight(),
        )
        Text(status.ifBlank { "—" }, style = console(9, statusColor(status)))
    }
}

private const val MISSING_COLOR = 0xFF5F6871

// Below these the widget drops the header, then the machine list, so the hero
// always survives at the smallest size.
private val HEADER_MIN_HEIGHT = 90.dp
private val MACHINES_MIN_HEIGHT = 130.dp

private fun statusColor(status: String) =
    when {
        status.startsWith("AWAKE") || status == "OK" -> Palette.green
        status == "FAILED" -> Palette.red
        status == "ASLEEP" || status.isBlank() -> Palette.faint
        else -> Palette.amber
    }

private fun console(
    size: Int,
    color: Color,
    weight: FontWeight = FontWeight.Normal,
) = TextStyle(
    color = ColorProvider(color),
    fontSize = size.sp,
    fontFamily = FontFamily.Monospace,
    fontWeight = weight,
)

/** Fires one command, then takes a single quick look at the result. */
class RunAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val machineId = parameters[machineIdParam] ?: return
        val commandName = parameters[commandParam] ?: return
        val state = Store(context.applicationContext).current()
        val target = state.resolve(ButtonRef(machineId, commandName)) ?: return

        setStatus(context, glanceId, machineId, "SENDING")
        when {
            WakeApi.run(target.connection, target.command.name).isFailure -> {
                setStatus(context, glanceId, machineId, "FAILED")
            }

            !target.command.ping -> {
                setStatus(context, glanceId, machineId, "OK")
            }

            else -> {
                // A PC takes far longer to boot than a widget action may run,
                // so look once and leave the rest to refresh.
                setStatus(context, glanceId, machineId, "SENT")
                delay(QUICK_LOOK_MS)
                val awake = WakeApi.status(target.connection, target.command.name).getOrDefault(false)
                setStatus(context, glanceId, machineId, if (awake) "AWAKE" else "SENT")
            }
        }
    }

    private companion object {
        const val QUICK_LOOK_MS = 4_000L
    }
}

/** Re-reads status for every machine that can report one. */
class RefreshAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val state = Store(context.applicationContext).current()
        state.machines.forEach { machine ->
            val connection = state.connection(machine.connectionId) ?: return@forEach
            val ping = machine.commands.firstOrNull { it.ping } ?: return@forEach
            val stats = WakeApi.stats(connection, ping.name).getOrNull()
            val label =
                when {
                    stats == null -> "?"
                    stats.awake -> "AWAKE " + fmtMs(stats.avgMs ?: 0.0) + "MS"
                    else -> "ASLEEP"
                }
            setStatus(context, glanceId, machine.id, label)
        }
    }
}

private suspend fun setStatus(
    context: Context,
    glanceId: GlanceId,
    machineId: String,
    value: String,
) {
    updateAppWidgetState(context, glanceId) { prefs -> prefs[statusKey(machineId)] = value }
    WakeWidget().update(context, glanceId)
}
