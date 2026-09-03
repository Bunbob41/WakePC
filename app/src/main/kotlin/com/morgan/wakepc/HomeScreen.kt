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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private data class Transient(val text: String, val color: Color, val pulse: Boolean, val glow: Boolean)

@Composable
fun HomeScreen(
    state: AppState,
    onAddMachine: () -> Unit,
    onEditMachine: (String) -> Unit,
    onSettings: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val transients = remember { mutableStateMapOf<String, Transient>() }
    val awake = remember { mutableStateMapOf<String, Boolean>() }
    val running = remember { mutableStateMapOf<String, String>() }

    // Background status poll for machines that have a ping-able command.
    LaunchedEffect(state.machines) {
        while (isActive) {
            state.machines.forEach { machine ->
                val connection = state.connection(machine.connectionId) ?: return@forEach
                val pingCmd = machine.commands.firstOrNull { it.ping } ?: return@forEach
                if (!running.containsKey(machine.id)) {
                    WakeApi.status(connection, pingCmd.name).onSuccess { awake[machine.id] = it }
                }
            }
            delay(15_000)
        }
    }

    fun runButton(machine: Machine, connection: Connection, command: CommandRef) {
        if (running.containsKey(machine.id)) return
        runCommand(scope, machine, connection, command, transients, awake, running)
    }

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
                R.drawable.ic_sliders, Palette.dim, size = 20.dp,
                modifier = Modifier.clickable(onClick = onSettings),
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.machines.forEach { machine ->
                val connection = state.connection(machine.connectionId)
                MachineCard(
                    machine = machine,
                    connection = connection,
                    transient = transients[machine.id],
                    awake = if (machine.commands.any { it.ping }) awake[machine.id] else null,
                    runningCommand = running[machine.id],
                    onRun = { cmd -> connection?.let { runButton(machine, it, cmd) } },
                    onEdit = { onEditMachine(machine.id) },
                )
            }
            Box(
                modifier = Modifier
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

        val hero = state.resolve(state.hero)
        if (hero != null) {
            HeroButton(
                style = state.heroStyle,
                resolved = hero,
                transient = transients[hero.machine.id],
                awake = if (hero.command.ping) awake[hero.machine.id] else null,
                onRun = { runButton(hero.machine, hero.connection, hero.command) },
            )
        }
    }
}

private fun runCommand(
    scope: CoroutineScope,
    machine: Machine,
    connection: Connection,
    command: CommandRef,
    transients: MutableMap<String, Transient>,
    awake: MutableMap<String, Boolean>,
    running: MutableMap<String, String>,
) {
    scope.launch {
        running[machine.id] = command.name
        try {
            if (command.ping) {
                val start = System.currentTimeMillis()
                transients[machine.id] = Transient("WAKING · 0:00", Palette.amber, pulse = true, glow = true)
                if (WakeApi.run(connection, command.name).isFailure) {
                    transients[machine.id] = Transient("UNREACHABLE", Palette.faint, pulse = false, glow = false)
                    delay(3_000)
                    transients.remove(machine.id)
                    return@launch
                }
                val deadline = start + 90_000
                while (System.currentTimeMillis() < deadline) {
                    delay(3_000)
                    val secs = ((System.currentTimeMillis() - start) / 1000).toInt()
                    val clock = "${secs / 60}:${(secs % 60).toString().padStart(2, '0')}"
                    transients[machine.id] = Transient("WAKING · $clock", Palette.amber, pulse = true, glow = true)
                    if (WakeApi.status(connection, command.name).getOrDefault(false)) {
                        awake[machine.id] = true
                        transients.remove(machine.id)
                        return@launch
                    }
                }
                transients[machine.id] = Transient("NO REPLY YET", Palette.dim, pulse = false, glow = false)
                delay(4_000)
                transients.remove(machine.id)
            } else {
                transients[machine.id] = Transient("RUNNING", Palette.amber, pulse = true, glow = true)
                val ok = WakeApi.run(connection, command.name).isSuccess
                transients[machine.id] = Transient(
                    if (ok) "OK" else "FAILED",
                    if (ok) Palette.green else Palette.red,
                    pulse = false,
                    glow = ok,
                )
                delay(2_500)
                transients.remove(machine.id)
            }
        } finally {
            running.remove(machine.id)
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
    runningCommand: String?,
    onRun: (CommandRef) -> Unit,
    onEdit: () -> Unit,
) {
    val active = transient != null && transient.color == Palette.amber
    Column(
        modifier = Modifier
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
            when {
                transient != null ->
                    StatusText(transient.text, transient.color, pulse = transient.pulse, glow = transient.glow)
                awake == true -> StatusText("AWAKE", Palette.green, glow = true)
                awake == false -> StatusText("ASLEEP", Palette.faint)
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
                    modifier = Modifier
                        .border(
                            1.dp,
                            if (isRunning) Palette.amber else Palette.dashed,
                            RoundedCornerShape(5.dp),
                        )
                        .clickable { onRun(cmd) }
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
    val label = "${resolved.command.name} · ${resolved.machine.name}"
    when (style) {
        HeroStyle.BANNER -> Row(
            modifier = Modifier
                .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 22.dp)
                .fillMaxWidth()
                .background(Palette.heroBg, RoundedCornerShape(12.dp))
                .border(1.dp, Palette.heroBorder, RoundedCornerShape(12.dp))
                .clickable(onClick = onRun)
                .padding(horizontal = 18.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
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

        HeroStyle.DIAL -> Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp, bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
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

        HeroStyle.MINI -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 22.dp, bottom = 24.dp, top = 10.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            Box(
                modifier = Modifier
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

@Composable
private fun HeroStatus(transient: Transient?, awake: Boolean?) {
    when {
        transient != null -> StatusText(transient.text, transient.color, pulse = transient.pulse, glow = transient.glow)
        awake == true -> StatusText("AWAKE", Palette.green, glow = true)
        awake == false -> StatusText("ASLEEP", Palette.faint)
    }
}
