package com.morgan.wakepc

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** UI-layer mapping from the ViewModel's colour-free status to the palette. */
private fun Tone.color(): Color =
    when (this) {
        Tone.NEUTRAL -> Palette.dim
        Tone.ACTIVE -> Palette.amber
        Tone.GOOD -> Palette.green
        Tone.BAD -> Palette.red
    }

@Composable
fun HomeScreen(
    vm: HomeViewModel,
    onAddMachine: () -> Unit,
    onEditMachine: (String) -> Unit,
    onSettings: () -> Unit,
    onConsole: () -> Unit,
) {
    val state by vm.state.collectAsState()
    val runtime by vm.runtime.collectAsState()
    val logLines by AppLog.lines.collectAsState()

    LaunchedEffect(Unit) { vm.startPolling() }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TintedIcon(R.drawable.ic_power_stroke, Palette.red, size = 15.dp)
            Spacer(modifier = Modifier.size(10.dp))
            ConsoleText("WAKEPC", size = 13, weight = FontWeight.Bold, letterSpacing = 4.0)
            Spacer(modifier = Modifier.weight(1f))
            TintedIcon(
                R.drawable.ic_sliders,
                Palette.dim,
                size = 20.dp,
                modifier = Modifier.clickable(onClick = onSettings),
            )
        }

        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.machines.forEach { machine ->
                val connection = state.connection(machine.connectionId)
                val pingCmd = machine.commands.firstOrNull { it.ping }
                val rt = runtime[machine.id] ?: MachineRuntime()
                MachineCard(
                    machine = machine,
                    connection = connection,
                    transient = rt.transient,
                    awake = if (pingCmd != null) rt.awake else null,
                    avgMs = if (pingCmd != null) rt.avgMs else null,
                    runningCommand = rt.running,
                    onRun = { cmd -> connection?.let { vm.run(machine, it, cmd) } },
                    onEdit = { onEditMachine(machine.id) },
                    onProbe =
                        if (pingCmd != null && connection != null) {
                            { vm.probe(machine, connection, pingCmd) }
                        } else {
                            null
                        },
                )
            }
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .dashedBorder(Palette.dashed, 10.dp)
                        .clickable(onClick = onAddMachine)
                        .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                ConsoleText("+ add machine", size = 12, color = Palette.dim)
            }
            Spacer(modifier = Modifier.height(4.dp))
        }

        logLines.lastOrNull()?.let { last ->
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onConsole)
                        .padding(horizontal = 20.dp, vertical = 5.dp),
            ) {
                ConsoleText(
                    "> ${last.text}",
                    size = 10,
                    color = if (last.ok == false) Palette.red else Palette.faint,
                    maxLines = 1,
                )
            }
        }

        val hero = state.resolve(state.hero)
        if (hero != null) {
            val rt = runtime[hero.machine.id] ?: MachineRuntime()
            HeroButton(
                style = state.heroStyle,
                resolved = hero,
                transient = rt.transient,
                awake = if (hero.command.ping) rt.awake else null,
                onRun = { vm.run(hero.machine, hero.connection, hero.command) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MachineCard(
    machine: Machine,
    connection: Connection?,
    transient: Transient?,
    awake: Boolean?,
    avgMs: Double?,
    runningCommand: String?,
    onRun: (CommandRef) -> Unit,
    onEdit: () -> Unit,
    onProbe: (() -> Unit)?,
) {
    val active = transient?.tone == Tone.ACTIVE
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(if (active) Palette.activeBg else Palette.card, RoundedCornerShape(10.dp))
                .border(1.dp, if (active) Palette.activeBorder else Palette.border, RoundedCornerShape(10.dp))
                .combinedClickable(onClick = {}, onLongClick = onEdit)
                .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Led(
                color = Color(connection?.color ?: 0xFF5F6871),
                pulse = active,
            )
            Spacer(modifier = Modifier.size(2.dp))
            ConsoleText(machine.name.ifBlank { "unnamed" }, size = 15, weight = FontWeight.Medium)
            Spacer(modifier = Modifier.weight(1f))
            Box(
                modifier =
                    if (onProbe != null) {
                        Modifier.clickable(onClick = onProbe).padding(4.dp)
                    } else {
                        Modifier
                    },
            ) {
                when {
                    transient != null -> {
                        StatusText(transient.text, transient.tone.color(), pulse = transient.pulse, glow = transient.glow)
                    }

                    awake == true -> {
                        StatusText(
                            "AWAKE" + (avgMs?.let { " · ${fmtMs(it)}MS" } ?: ""),
                            Palette.green,
                            glow = true,
                        )
                    }

                    awake == false -> {
                        StatusText("ASLEEP", Palette.faint)
                    }
                }
            }
        }
        ConsoleText(
            "via ${connection?.name ?: "missing connection"}",
            size = 10,
            color = Palette.faint,
            modifier = Modifier.padding(start = 26.dp, top = 2.dp),
        )
        Row(
            modifier = Modifier.padding(top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            machine.commands.forEach { cmd ->
                val isRunning = runningCommand == cmd.name
                Box(
                    modifier =
                        Modifier
                            .border(
                                1.dp,
                                if (isRunning) Palette.amber else Palette.dashed,
                                RoundedCornerShape(5.dp),
                            ).clickable { onRun(cmd) }
                            .padding(horizontal = 18.dp, vertical = 10.dp),
                ) {
                    ConsoleText(cmd.name, size = 12, color = if (isRunning) Palette.amber else Palette.sub)
                }
            }
        }
    }
}

@Composable
private fun HeroButton(
    style: HeroStyle,
    resolved: ResolvedButton,
    transient: Transient?,
    awake: Boolean?,
    onRun: () -> Unit,
) {
    when (style) {
        HeroStyle.BANNER -> {
            Row(
                modifier =
                    Modifier
                        .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 22.dp)
                        .fillMaxWidth()
                        .background(Palette.heroBg, RoundedCornerShape(12.dp))
                        .border(1.dp, Palette.heroBorder, RoundedCornerShape(12.dp))
                        .clickable(onClick = onRun)
                        .padding(horizontal = 18.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(42.dp)
                            .background(Palette.heroBg, CircleShape)
                            .border(1.dp, Palette.heroBorder, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    TintedIcon(R.drawable.ic_power_stroke, Palette.red, size = 20.dp)
                }
                Column(modifier = Modifier.weight(1f).padding(start = 15.dp)) {
                    ConsoleText(
                        resolved.machine.name.ifBlank { resolved.command.name },
                        size = 17,
                        weight = FontWeight.Bold,
                    )
                    ConsoleText(
                        "${resolved.command.name} · via ${resolved.connection.name}",
                        size = 10,
                        color = Palette.dim,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
                HeroStatus(transient, awake)
            }
        }

        HeroStyle.DIAL -> {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp, bottom = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(150.dp)
                            .background(Palette.card, CircleShape)
                            .border(2.dp, Palette.red, CircleShape)
                            .clickable(onClick = onRun),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        TintedIcon(R.drawable.ic_power_stroke, Palette.text, size = 38.dp)
                        ConsoleText(
                            resolved.machine.name.ifBlank { resolved.command.name },
                            size = 11,
                            weight = FontWeight.Bold,
                            letterSpacing = 2.0,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                }
                Box(modifier = Modifier.padding(top = 14.dp)) { HeroStatus(transient, awake) }
            }
        }

        HeroStyle.MINI -> {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(end = 22.dp, bottom = 24.dp, top = 10.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(64.dp)
                            .background(Palette.card, CircleShape)
                            .border(2.dp, Palette.red, CircleShape)
                            .clickable(onClick = onRun),
                    contentAlignment = Alignment.Center,
                ) {
                    TintedIcon(R.drawable.ic_power_stroke, Palette.text, size = 26.dp)
                }
            }
        }
    }
}

@Composable
private fun HeroStatus(
    transient: Transient?,
    awake: Boolean?,
) {
    when {
        transient != null -> StatusText(transient.text, transient.tone.color(), pulse = transient.pulse, glow = transient.glow)
        awake == true -> StatusText("AWAKE", Palette.green, glow = true)
        awake == false -> StatusText("ASLEEP", Palette.faint)
    }
}
