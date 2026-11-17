package com.morgan.wakepc

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val STEP_ADDRESS = "address"
private const val STEP_PICK = "pick"
private const val STEP_WAKE = "wake"
private const val IDLE_POLL_MS = 3_000L
private const val SECONDS_PER_MINUTE = 60

/**
 * From nothing to a first wake, one checked step at a time: reach the relay,
 * pick the PC from what the relay can see, then wake it while we watch.
 *
 * [onBack] is null on a fresh install, where there is nowhere to go back to.
 */
@Composable
fun SetupScreen(
    store: Store,
    state: AppState,
    onBack: (() -> Unit)?,
    onAdvanced: () -> Unit,
    onDone: () -> Unit,
    api: WakeRepository = WakeApi,
) {
    var step by rememberSaveable { mutableStateOf(if (state.connections.isEmpty()) STEP_ADDRESS else STEP_PICK) }
    var connectionId by rememberSaveable { mutableStateOf(state.connections.firstOrNull()?.id) }
    var machineId by rememberSaveable { mutableStateOf<String?>(null) }

    val title =
        when (step) {
            STEP_ADDRESS -> "SET UP · 1 OF 3"
            STEP_PICK -> if (state.machines.isEmpty()) "SET UP · 2 OF 3" else "ADD A MACHINE"
            else -> if (state.machines.size <= 1) "SET UP · 3 OF 3" else "TEST IT"
        }
    // Once a machine is saved, backing out means finishing, not undoing it.
    EditorScaffold(title, onBack = if (step == STEP_WAKE) onDone else onBack) {
        when (step) {
            STEP_ADDRESS -> {
                AddressStep(store, api, onAdvanced) { connection ->
                    connectionId = connection.id
                    step = STEP_PICK
                }
            }

            STEP_PICK -> {
                val connection = state.connection(connectionId) ?: state.connections.firstOrNull()
                if (connection == null) {
                    LaunchedEffect(Unit) { step = STEP_ADDRESS }
                } else {
                    PickStep(store, state, api, connection, onConnection = { connectionId = it }) { added ->
                        machineId = added
                        step = STEP_WAKE
                    }
                }
            }

            else -> {
                WakeStep(state, api, machineId, onDone)
            }
        }
    }
}

@Composable
private fun ColumnScope.AddressStep(
    store: Store,
    api: WakeRepository,
    onAdvanced: () -> Unit,
    onConnected: (Connection) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var address by rememberSaveable { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<RelayCheck.Unreachable?>(null) }

    StepBody {
        Prose(
            "wakepc needs one computer at home that stays on: a raspberry pi, a NAS, a spare pc. " +
                "it is the relay that wakes the others.",
        )
        Prose(
            "install the relay on it first, from github.com/Bunbob41/WakePC. " +
                "it prints a name when it finishes. type that here.",
            Palette.dim,
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("RELAY NAME OR ADDRESS")
            ConsoleField(
                address,
                {
                    address = it
                    failure = null
                },
                placeholder = "its tailscale name, or 100.x.y.z",
            )
        }
        failure?.let { failed ->
            // Three links, in order, so the broken one is the one marked.
            val vpnOff = WakeApi.vpnLooksOff()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CheckLine(!vpnOff, "tailscale is on, on this phone")
                CheckLine(if (vpnOff) null else !failed.kind.relayDown, "the relay answers")
                CheckLine(
                    when {
                        failed.kind.relayDown -> null
                        else -> failed.kind != FailureKind.NOT_RECOGNISED
                    },
                    "the relay recognises this phone",
                )
                Prose(failed.text, Palette.red)
            }
        }
        ConsoleText(
            "advanced: token, fallback address",
            size = 12,
            color = Palette.faint,
            modifier = Modifier.clickable(onClick = onAdvanced).padding(vertical = 6.dp),
        )
    }
    PrimaryButton(if (checking) "checking…" else "connect", modifier = Modifier.stepButton()) {
        if (checking || address.isBlank()) return@PrimaryButton
        scope.launch {
            checking = true
            failure = null
            when (val result = checkRelay(api, draftConnection(address))) {
                is RelayCheck.Reachable -> {
                    store.update { it.withConnection(result.connection) }
                    onConnected(result.connection)
                }

                is RelayCheck.Unreachable -> {
                    failure = result
                }
            }
            checking = false
        }
    }
}

@Composable
private fun ColumnScope.PickStep(
    store: Store,
    state: AppState,
    api: WakeRepository,
    connection: Connection,
    onConnection: (String) -> Unit,
    onAdded: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var check by remember(connection.id) { mutableStateOf<RelayCheck?>(null) }
    var devices by remember(connection.id) { mutableStateOf<List<LanDevice>?>(null) }
    var scanError by remember(connection.id) { mutableStateOf<String?>(null) }
    var scanRound by remember { mutableIntStateOf(0) }

    // What is picked: a scanned device (by MAC), an existing relay command, or a MAC typed by hand.
    var pickedMac by rememberSaveable(connection.id) { mutableStateOf<String?>(null) }
    var pickedCommand by rememberSaveable(connection.id) { mutableStateOf<String?>(null) }
    var byHand by rememberSaveable(connection.id) { mutableStateOf(false) }
    var typedMac by rememberSaveable(connection.id) { mutableStateOf("") }
    var name by rememberSaveable(connection.id) { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(connection.id, scanRound) {
        devices = null
        scanError = null
        val result = checkRelay(api, connection)
        check = result
        if (result is RelayCheck.Reachable && result.canAddMachines) {
            api.scan(connection).fold(
                onSuccess = { devices = it },
                onFailure = { scanError = it.message },
            )
        }
    }

    val reachable = check as? RelayCheck.Reachable
    val commands = reachable?.let { unusedCommands(state, connection, it.commands) }.orEmpty()
    val pickedDevice = devices?.firstOrNull { it.mac == pickedMac }
    val handMac = if (byHand) cleanMac(typedMac) else null
    val ready = pickedDevice != null || pickedCommand != null || handMac != null

    StepBody {
        if (state.connections.size > 1) {
            RelayChips(state.connections, connection.id, onConnection)
        }
        when (val result = check) {
            null -> {
                Prose("asking ${connection.name}…", Palette.dim)
            }

            is RelayCheck.Unreachable -> {
                Prose(result.text, Palette.red)
                LinkText("try again") { scanRound++ }
            }

            is RelayCheck.Reachable -> {
                result.health?.warnings?.forEach { Prose("${connection.name}: $it", Palette.amber) }
                if (result.canAddMachines) {
                    Prose("which one is your pc? these are the devices ${connection.name} can see at home.")
                    DeviceList(devices, scanError, pickedMac) { device ->
                        pickedMac = device.mac
                        pickedCommand = null
                        byHand = false
                        name = suggestedName(device)
                    }
                    Prose(
                        "only devices that are switched on show up, so have the pc running. " +
                            "its address is under settings, network, ethernet on the pc.",
                        Palette.dim,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                        LinkText("scan again") { scanRound++ }
                        LinkText(if (byHand) "pick from the list" else "type a MAC address instead") {
                            byHand = !byHand
                            pickedMac = null
                            pickedCommand = null
                        }
                    }
                    if (byHand) {
                        ConsoleField(typedMac, { typedMac = it }, placeholder = "AA:BB:CC:DD:EE:FF")
                        Prose(
                            "added by MAC alone, the app cannot show whether the pc is on. pick it from the list for that.",
                            Palette.dim,
                        )
                    }
                } else {
                    Prose(cannotAddReason(connection, result))
                }
                if (commands.isNotEmpty()) {
                    SectionLabel("ALREADY SET UP ON ${connection.name.uppercase()}")
                    commands.forEach { cmd ->
                        PickRow(cmd.name, if (cmd.ping) "shows whether it is on" else "", picked = pickedCommand == cmd.name) {
                            pickedCommand = cmd.name
                            pickedMac = null
                            byHand = false
                        }
                    }
                }
            }
        }
        if (ready) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("CALL IT")
                ConsoleField(name, { name = it }, placeholder = "my pc")
            }
        }
        saveError?.let { Prose(it, Palette.red) }
    }
    if (ready) {
        PrimaryButton(if (saving) "adding…" else "add this pc", modifier = Modifier.stepButton()) {
            if (saving) return@PrimaryButton
            val finalName = name.trim().ifBlank { "my pc" }
            scope.launch {
                saving = true
                saveError = null
                val command = commands.firstOrNull { it.name == pickedCommand }
                val machine =
                    if (command != null) {
                        machineFor(connection, finalName, command)
                    } else {
                        api
                            .addMachine(connection, finalName, pickedDevice?.mac ?: handMac.orEmpty(), pickedDevice?.ip)
                            .fold(
                                onSuccess = { machineFor(connection, finalName, it) },
                                onFailure = {
                                    saveError = it.message
                                    null
                                },
                            )
                    }
                saving = false
                if (machine != null) {
                    store.update { it.withMachine(machine) }
                    onAdded(machine.id)
                }
            }
        }
    }
}

/** Why this relay offers no list to pick from, and what to do about it. */
private fun cannotAddReason(
    connection: Connection,
    reachable: RelayCheck.Reachable,
): String =
    when {
        reachable.health == null || reachable.health.api < MACHINES_API -> {
            "${connection.name} runs an older wakepc that cannot look for your pc. update it by running its " +
                "installer again, or use a button it already has."
        }

        else -> {
            "${connection.name} has phone setup switched off, so machines are added in its config file."
        }
    }

@Composable
private fun DeviceList(
    devices: List<LanDevice>?,
    scanError: String?,
    pickedMac: String?,
    onPick: (LanDevice) -> Unit,
) {
    when {
        scanError != null -> {
            Prose(scanError, Palette.red)
        }

        devices == null -> {
            Prose("looking around the network, a few seconds…", Palette.dim)
        }

        devices.isEmpty() -> {
            Prose("nothing found. is the pc switched on, and on the same network as the relay?", Palette.amber)
        }

        else -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                devices.forEach { device ->
                    val added = device.machineId != null
                    PickRow(
                        device.name ?: device.ip,
                        "${device.ip} · ${device.mac}" + if (added) " · already added" else "",
                        picked = device.mac == pickedMac,
                        enabled = !added,
                    ) { onPick(device) }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.WakeStep(
    state: AppState,
    api: WakeRepository,
    machineId: String?,
    onDone: () -> Unit,
) {
    val machine = state.machine(machineId)
    val command = machine?.commands?.firstOrNull()
    val connection = if (machine != null && command != null) state.connectionFor(machine, command) else null
    if (machine == null || command == null || connection == null) {
        LaunchedEffect(Unit) { onDone() }
        return
    }
    val scope = rememberCoroutineScope()
    var stage by remember { mutableStateOf<WakeStage?>(null) }
    var before by remember { mutableStateOf<Readiness?>(null) }
    val running = stage?.finished == false

    // Until a wake is tried, keep saying whether the PC is on, so "shut it
    // down, then tap wake" can be followed by watching this screen.
    LaunchedEffect(stage == null) {
        while (stage == null && command.ping) {
            before = api.status(connection, command.name).getOrNull()
            delay(IDLE_POLL_MS)
        }
    }

    StepBody {
        when (val now = stage) {
            null -> {
                Prose("last step: make sure it really wakes.")
                Prose(idleText(machine.name, command.ping, before), idleColor(before))
            }

            else -> {
                WakeProgress(now, machine.name, connection.name)
            }
        }
    }
    val done = stage is WakeStage.Usable
    if (!done) {
        ConsoleText(
            if (stage == null) "skip, i will test it later" else "finish anyway",
            size = 12,
            color = Palette.faint,
            modifier =
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .clickable(onClick = onDone)
                    .padding(vertical = 10.dp),
        )
    }
    val label =
        when {
            done -> "done"
            running -> "waking…"
            stage == null -> "wake it"
            else -> "try again"
        }
    PrimaryButton(label, modifier = Modifier.stepButton()) {
        when {
            done -> onDone()
            running -> Unit
            else -> scope.launch { guidedWake(api, connection, command) { stage = it } }
        }
    }
}

private fun idleText(
    name: String,
    canCheck: Boolean,
    before: Readiness?,
): String =
    when {
        !canCheck -> "the app has no address for $name, so it cannot see it come on. tap wake and watch the pc itself."
        before == null -> "checking whether $name is on…"
        before == Readiness.ASLEEP -> "$name is off. tap wake."
        else -> "$name is on right now. to test a real wake, shut it down, wait for this to say it is off, then tap wake."
    }

private fun idleColor(before: Readiness?): Color =
    when (before) {
        null -> Palette.dim
        Readiness.ASLEEP -> Palette.text
        else -> Palette.amber
    }

@Composable
private fun WakeProgress(
    stage: WakeStage,
    machine: String,
    relay: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (stage) {
            WakeStage.Sending -> {
                CheckLine(null, "asking $relay to send the wake packet")
            }

            is WakeStage.RelayFailed -> {
                CheckLine(false, "wake packet not sent")
                Prose(stage.text, Palette.red)
                Prose("this is between the phone and $relay. your pc's settings are not the problem yet.", Palette.dim)
            }

            is WakeStage.Waiting -> {
                CheckLine(true, "wake packet sent by $relay")
                CheckLine(
                    null,
                    (if (stage.up) "$machine is starting up" else "waiting for $machine to answer") + " · ${clock(stage.seconds)}",
                )
            }

            is WakeStage.Usable -> {
                CheckLine(true, "wake packet sent by $relay")
                CheckLine(true, usableText(machine, stage))
                Prose("that is setup done. from now on it is one tap, from anywhere.", Palette.green)
            }

            is WakeStage.NoAnswer -> {
                CheckLine(true, "wake packet sent by $relay")
                if (stage.up) {
                    CheckLine(null, "$machine is on and answering, but not ready yet")
                    Prose("it pings, but the port the relay waits for has not opened. it is probably usable, just slow.", Palette.dim)
                } else {
                    CheckLine(false, "no answer from $machine after ${clock(FIRST_WAKE_SECONDS)}")
                    Prose("the phone and $relay are fine, so this is down to the pc. the usual causes:")
                    WAKE_HINTS.forEach { Prose("· $it", Palette.sub) }
                    Prose("it can also just be slow to start. try again once more before changing anything.", Palette.dim)
                }
            }
        }
    }
}

private const val MS_PER_SECOND = 1_000L
private const val FIRST_WAKE_SECONDS = (FIRST_WAKE_ATTEMPTS * FIRST_WAKE_STEP_MS / MS_PER_SECOND).toInt()

private fun usableText(
    machine: String,
    stage: WakeStage.Usable,
): String =
    when {
        stage.seconds == 0 -> "$machine has been sent its wake packet"
        stage.readiness == Readiness.READY -> "$machine is on and ready, after ${clock(stage.seconds)}"
        else -> "$machine is on, after ${clock(stage.seconds)}"
    }

private fun clock(seconds: Int): String = "${seconds / SECONDS_PER_MINUTE}:${(seconds % SECONDS_PER_MINUTE).toString().padStart(2, '0')}"

/** The scrolling part of a step, above its button. */
@Composable
private fun ColumnScope.StepBody(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier =
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        content = content,
    )
}

private fun Modifier.stepButton(): Modifier = padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 28.dp)

/** Running text that wraps, unlike the one-line labels everywhere else. */
@Composable
private fun Prose(
    text: String,
    color: Color = Palette.text,
) {
    androidx.compose.material3.Text(
        text = text,
        color = color,
        fontSize = 13.sp,
        fontFamily = Mono,
        lineHeight = 21.sp,
    )
}

@Composable
private fun LinkText(
    text: String,
    onClick: () -> Unit,
) {
    ConsoleText(text, size = 12, color = Palette.sub, modifier = Modifier.clickable(onClick = onClick).padding(vertical = 6.dp))
}

/** One link in a chain: true passed, false broke, null not reached or still going. */
@Composable
private fun CheckLine(
    ok: Boolean?,
    text: String,
) {
    Row(verticalAlignment = Alignment.Top) {
        Box(modifier = Modifier.width(24.dp).padding(top = 3.dp)) {
            when (ok) {
                true -> TintedIcon(R.drawable.ic_check, Palette.green, size = 15.dp)
                false -> ConsoleText("x", size = 14, color = Palette.red, weight = FontWeight.Bold)
                null -> Box(modifier = Modifier.padding(top = 4.dp, start = 3.dp).size(7.dp).background(Palette.faint, CircleShape))
            }
        }
        Prose(
            text,
            when (ok) {
                true -> Palette.text
                false -> Palette.red
                null -> Palette.dim
            },
        )
    }
}

@Composable
private fun PickRow(
    title: String,
    detail: String,
    picked: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(if (picked) Palette.selBg else Palette.card, RoundedCornerShape(6.dp))
                .border(1.dp, if (picked) Palette.selBorder else Palette.border, RoundedCornerShape(6.dp))
                .clickable(enabled = enabled, onClick = onClick)
                .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ConsoleText(title, size = 14, color = if (enabled) Palette.text else Palette.faint, maxLines = 1)
        if (detail.isNotBlank()) {
            ConsoleText(detail, size = 12, color = if (enabled) Palette.dim else Palette.faint, maxLines = 1)
        }
    }
}

@Composable
private fun RelayChips(
    connections: List<Connection>,
    selectedId: String,
    onSelect: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel("RELAY")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            connections.forEach { conn ->
                val isSel = conn.id == selectedId
                Box(
                    modifier =
                        Modifier
                            .background(if (isSel) Palette.selBg else Palette.card, RoundedCornerShape(6.dp))
                            .border(1.dp, if (isSel) Palette.selBorder else Palette.border, RoundedCornerShape(6.dp))
                            .clickable { onSelect(conn.id) }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    ConsoleText(conn.name, size = 12, color = if (isSel) Palette.text else Palette.dim)
                }
            }
        }
    }
}
