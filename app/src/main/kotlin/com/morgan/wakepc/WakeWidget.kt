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
import androidx.glance.layout.Box
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
 * Home-screen widget. Each placed instance targets its own machine and command
 * (chosen in [WidgetConfigActivity] when it is dropped), and renders that
 * button in whichever hero style the app is set to.
 *
 * A widget action cannot hold a long polling loop, so a tap fires the command
 * and takes one quick look; the refresh control and the periodic update in the
 * provider XML pick up the result afterwards.
 */
class WakeWidget : GlanceAppWidget() {
    override val stateDefinition = PreferencesGlanceStateDefinition

    // One widget that renders for its actual size, so every provider below
    // (and any resize afterwards) stays correct when the widget redraws.
    override val sizeMode =
        SizeMode.Responsive(
            setOf(
                DpSize(60.dp, 60.dp),
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

/** 2x1 entry in the picker. */
class WakeWidgetCompactReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WakeWidget()
}

/** 2x2 entry in the picker. */
class WakeWidgetMediumReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WakeWidget()
}

/** 1x1 entry: the power glyph alone, tinted by state. */
class WakeWidgetIconReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WakeWidget()
}

/** Per-machine status cached in the widget's own state, so it paints instantly. */
internal fun statusKey(machineId: String) = stringPreferencesKey("status_$machineId")

/** What this particular widget instance controls, chosen when it was placed. */
internal val selectedMachineKey = stringPreferencesKey("selected_machine")
internal val selectedCommandKey = stringPreferencesKey("selected_command")

internal val machineIdParam = ActionParameters.Key<String>("machineId")
internal val commandParam = ActionParameters.Key<String>("command")

private const val MISSING_COLOR = 0xFF5F6871

// Below these the widget drops the header, then the machine list, so the
// button always survives at the smallest size.
private val HEADER_MIN_HEIGHT = 90.dp
private val MACHINES_MIN_HEIGHT = 130.dp
private val ICON_ONLY_MAX = 80.dp

@Composable
private fun WidgetBody(appState: AppState) {
    val prefs = currentState<Preferences>()
    val size = LocalSize.current

    // This instance's own target, falling back to the app-wide hero for
    // widgets placed before per-widget selection existed.
    val chosen =
        prefs[selectedMachineKey]?.let { machineId ->
            prefs[selectedCommandKey]?.let { command -> ButtonRef(machineId, command) }
        } ?: appState.hero
    val target = appState.resolve(chosen)
    val status = target?.let { prefs[statusKey(it.machine.id)].orEmpty() }.orEmpty()

    if (size.width <= ICON_ONLY_MAX && size.height <= ICON_ONLY_MAX) {
        IconOnly(target, status)
        return
    }

    val showHeader = size.height >= HEADER_MIN_HEIGHT
    val showMachines = size.height >= MACHINES_MIN_HEIGHT
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

        if (target != null) {
            HeroButton(appState.heroStyle, target, status, size)
            Spacer(GlanceModifier.height(6.dp))
        } else {
            Text("tap to set this widget up", style = console(11, Palette.dim))
        }

        if (showMachines) {
            appState.machines
                .filter { it.id != target?.machine?.id }
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

/** The 1x1: nothing but the glyph, coloured by what the machine is doing. */
@Composable
private fun IconOnly(
    target: ResolvedButton?,
    status: String,
) {
    Box(
        modifier =
            GlanceModifier
                .fillMaxSize()
                .background(ColorProvider(Palette.heroBg))
                .cornerRadius(24.dp)
                .then(if (target != null) GlanceModifier.clickable(runAction(target)) else GlanceModifier),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(R.drawable.ic_power_stroke),
            contentDescription = target?.machine?.name ?: "WakePC",
            colorFilter = ColorFilter.tint(ColorProvider(statusColor(status))),
            modifier = GlanceModifier.size(32.dp),
        )
    }
}

/** Honours the app's hero style, adapted to the space the widget actually has. */
@Composable
private fun HeroButton(
    style: HeroStyle,
    target: ResolvedButton,
    status: String,
    size: DpSize,
) {
    val label = target.machine.name.ifBlank { target.command.name }
    val dialFits = size.height >= HEADER_MIN_HEIGHT
    when {
        style == HeroStyle.MINI || (style == HeroStyle.DIAL && !dialFits) -> {
            Row(
                modifier = GlanceModifier.fillMaxWidth().clickable(runAction(target)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier =
                        GlanceModifier
                            .size(40.dp)
                            .background(ColorProvider(Palette.heroBg))
                            .cornerRadius(20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        provider = ImageProvider(R.drawable.ic_power_stroke),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(ColorProvider(Palette.red)),
                        modifier = GlanceModifier.size(20.dp),
                    )
                }
                Spacer(GlanceModifier.width(10.dp))
                Text(
                    label,
                    style = console(14, Palette.text, FontWeight.Bold),
                    modifier = GlanceModifier.defaultWeight(),
                )
                Text(status.ifBlank { "tap" }, style = console(10, statusColor(status)))
            }
        }

        style == HeroStyle.DIAL -> {
            Column(
                modifier = GlanceModifier.fillMaxWidth().clickable(runAction(target)),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier =
                        GlanceModifier
                            .size(72.dp)
                            .background(ColorProvider(Palette.heroBg))
                            .cornerRadius(36.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        provider = ImageProvider(R.drawable.ic_power_stroke),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(ColorProvider(Palette.red)),
                        modifier = GlanceModifier.size(34.dp),
                    )
                }
                Spacer(GlanceModifier.height(6.dp))
                Text(label, style = console(12, Palette.text, FontWeight.Bold))
                Text(status.ifBlank { "tap" }, style = console(10, statusColor(status)))
            }
        }

        else -> {
            Row(
                modifier =
                    GlanceModifier
                        .fillMaxWidth()
                        .background(ColorProvider(Palette.heroBg))
                        .cornerRadius(12.dp)
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .clickable(runAction(target)),
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
                    label,
                    style = console(15, Palette.text, FontWeight.Bold),
                    modifier = GlanceModifier.defaultWeight(),
                )
                Text(status.ifBlank { "tap" }, style = console(10, statusColor(status)))
            }
        }
    }
}

private fun runAction(target: ResolvedButton) =
    actionRunCallback<RunAction>(
        actionParametersOf(
            machineIdParam to target.machine.id,
            commandParam to target.command.name,
        ),
    )

@Composable
private fun MachineRow(
    machine: Machine,
    connection: Connection?,
    status: String,
) {
    val command = machine.commands.firstOrNull { it.ping } ?: machine.commands.firstOrNull()
    Row(
        modifier =
            GlanceModifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
                .then(
                    if (command != null && connection != null) {
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
            val ping = machine.commands.firstOrNull { it.ping } ?: return@forEach
            val connection = state.connectionFor(machine, ping) ?: return@forEach
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
