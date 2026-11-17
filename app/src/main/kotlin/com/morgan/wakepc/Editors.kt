package com.morgan.wakepc

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

val connectionColors =
    listOf(
        0xFFFF5D49,
        0xFF45D06D,
        0xFFE2A63D,
        0xFF4AA3FF,
        0xFFA06BFF,
        0xFF3ECFC0,
    )

/** Bundles can't hold CommandRef, so flatten each field in turn. */
private val commandsSaver =
    listSaver<List<CommandRef>, Any>(
        save = { list -> list.flatMap { listOf(it.name, it.ping, it.connectionId ?: "", it.elevated) } },
        restore = { flat ->
            flat.chunked(4).map {
                CommandRef(
                    name = it[0] as String,
                    ping = it[1] as Boolean,
                    connectionId = (it[2] as String).takeIf(String::isNotBlank),
                    elevated = it[3] as Boolean,
                )
            }
        },
    )

@Composable
fun EditorScaffold(
    title: String,
    onBack: (() -> Unit)?,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 26.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Null on a fresh install's setup: there is nothing to go back to.
            if (onBack != null) {
                TintedIcon(
                    R.drawable.ic_back,
                    Palette.dim,
                    modifier = Modifier.clickable(onClick = onBack),
                )
                Spacer(modifier = Modifier.size(14.dp))
            }
            ConsoleText(title, size = 13, weight = FontWeight.Bold, letterSpacing = 3.0)
        }
        content()
    }
}

/** What a passed test found, including anything the relay wants looked at. */
private fun testSummary(result: RelayCheck.Reachable): String {
    val buttons = result.commands.joinToString(", ") { it.name }.ifBlank { "none yet" }
    val notes =
        result.health
            ?.warnings
            .orEmpty()
            .joinToString("") { "\nnote: $it" }
    return "ok, reached ${result.connection.name}. buttons: $buttons$notes"
}

@Composable
fun ConnectionEditor(
    store: Store,
    connectionId: String?,
    onSaved: () -> Unit,
    onClosed: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var loaded by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var color by rememberSaveable { mutableStateOf(connectionColors.first()) }
    var baseUrl by rememberSaveable { mutableStateOf("") }
    var fallbackUrl by rememberSaveable { mutableStateOf("") }
    var token by rememberSaveable { mutableStateOf("") }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Pair<String, Color>?>(null) }
    val newId =
        rememberSaveable {
            java.util.UUID
                .randomUUID()
                .toString()
        }

    LaunchedEffect(connectionId) {
        // Already populated (rotation, process death): keep what's on screen.
        if (loaded) return@LaunchedEffect
        val existing = store.current().connection(connectionId)
        if (existing != null) {
            name = existing.name
            color = existing.color
            baseUrl = existing.baseUrl
            fallbackUrl = existing.fallbackUrl
            token = existing.token
            advanced = existing.fallbackUrl.isNotBlank() || existing.token.isNotBlank()
        }
        loaded = true
    }
    if (!loaded) return

    fun draft() =
        Connection(
            id = connectionId ?: newId,
            // Unnamed relays took the name "" and showed up as a blank row.
            name = name.trim().ifBlank { hostOf(cleanUrl(baseUrl)) },
            color = color,
            baseUrl = cleanUrl(baseUrl),
            fallbackUrl = cleanUrl(fallbackUrl),
            token = cleanToken(token),
        )

    EditorScaffold(if (connectionId == null) "NEW RELAY" else "EDIT RELAY", onBack = onClosed) {
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("ADDRESS")
                ConsoleField(baseUrl, { baseUrl = it }, placeholder = "its tailscale name, or 100.x.y.z")
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("NAME")
                ConsoleField(name, { name = it }, placeholder = "my relay")
            }
            // The address is the only field most relays need; the rest stays
            // folded away unless it is already in use.
            ConsoleText(
                if (advanced) "hide advanced" else "advanced: colour, fallback address, token",
                size = 12,
                color = Palette.sub,
                modifier = Modifier.clickable { advanced = !advanced }.padding(vertical = 4.dp),
            )
            if (advanced) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionLabel("COLOUR")
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        connectionColors.forEach { c ->
                            Box(
                                modifier =
                                    Modifier
                                        .size(34.dp)
                                        .background(Color(c), RoundedCornerShape(6.dp))
                                        .then(
                                            if (c == color) {
                                                Modifier.border(2.dp, Palette.text, RoundedCornerShape(6.dp))
                                            } else {
                                                Modifier
                                            },
                                        ).clickable { color = c },
                            )
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionLabel("FALLBACK ADDRESS")
                    ConsoleField(fallbackUrl, { fallbackUrl = it }, placeholder = "tried if the first address fails")
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionLabel("TOKEN")
                    ConsoleField(token, { token = it }, secret = true, placeholder = "only for a relay without tailscale")
                }
            }
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(Palette.selBg, RoundedCornerShape(6.dp))
                        .border(1.dp, Palette.selBorder, RoundedCornerShape(6.dp))
                        .clickable {
                            scope.launch {
                                testResult = "testing…" to Palette.dim
                                testResult =
                                    when (val result = checkRelay(WakeApi, draft())) {
                                        is RelayCheck.Reachable -> testSummary(result) to Palette.green
                                        is RelayCheck.Unreachable -> result.text to Palette.red
                                    }
                            }
                        }.padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                ConsoleText("test connection", size = 13, color = Palette.green, letterSpacing = 1.0)
            }
            testResult?.let { (text, color) ->
                androidx.compose.material3.Text(text, color = color, fontSize = 12.sp, fontFamily = Mono, lineHeight = 19.sp)
            }
            if (connectionId != null) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                scope.launch {
                                    store.update { s ->
                                        val machines = s.machines.filterNot { it.connectionId == connectionId }
                                        val ids = machines.map { it.id }.toSet()
                                        s.copy(
                                            connections = s.connections.filterNot { it.id == connectionId },
                                            machines = machines,
                                            hero = s.hero?.takeIf { it.machineId in ids },
                                            tile = s.tile?.takeIf { it.machineId in ids },
                                        )
                                    }
                                    onClosed()
                                }
                            }.padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    ConsoleText("delete relay", size = 12, color = Palette.red)
                }
            }
        }
        PrimaryButton(
            "save relay",
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 28.dp),
        ) {
            scope.launch {
                val cleaned = draft()
                store.update { s ->
                    val index = s.connections.indexOfFirst { it.id == cleaned.id }
                    s.copy(
                        connections =
                            if (index >= 0) {
                                s.connections.toMutableList().apply { set(index, cleaned) }
                            } else {
                                s.connections + cleaned
                            },
                    )
                }
                onSaved()
            }
        }
    }
}

@Composable
fun MachineEditor(
    store: Store,
    machineId: String?,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var loaded by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var connectionId by rememberSaveable { mutableStateOf<String?>(null) }
    var selected by rememberSaveable(stateSaver = commandsSaver) {
        mutableStateOf<List<CommandRef>>(emptyList())
    }
    // Commands offered by each connection, so one machine can mix them:
    // wake from the Pi, shutdown from the machine itself.
    var offered by remember { mutableStateOf<Map<String, List<CommandRef>>>(emptyMap()) }
    var fetchErrors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var fetching by remember { mutableStateOf(true) }
    var connections by remember { mutableStateOf<List<Connection>>(emptyList()) }

    LaunchedEffect(machineId) {
        val state = store.current()
        // connections isn't saveable, so it reloads every time; the edited
        // fields only load once, or rotation would discard what's on screen.
        connections = state.connections
        if (!loaded) {
            val existing = state.machine(machineId)
            if (existing != null) {
                name = existing.name
                connectionId = existing.connectionId
                selected = existing.commands
            } else {
                connectionId = state.connections.firstOrNull()?.id
            }
            loaded = true
        }
    }
    if (!loaded) return

    LaunchedEffect(connections) {
        if (connections.isEmpty()) return@LaunchedEffect
        fetching = true
        val results = mutableMapOf<String, List<CommandRef>>()
        val errors = mutableMapOf<String, String>()
        connections.forEach { conn ->
            WakeApi.fetchCommands(conn).fold(
                onSuccess = { fetched ->
                    results[conn.id] = fetched.map { it.copy(connectionId = conn.id) }
                },
                onFailure = { errors[conn.id] = it.message ?: "unreachable" },
            )
        }
        offered = results
        fetchErrors = errors
        fetching = false
        // A brand-new machine starts with its own connection's commands checked.
        if (machineId == null && selected.isEmpty()) {
            selected = results[connectionId].orEmpty()
        }
    }

    EditorScaffold(if (machineId == null) "NEW MACHINE" else "EDIT MACHINE", onBack = onDone) {
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("NAME")
                ConsoleField(name, { name = it }, placeholder = "desk pc")
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionLabel("RELAY")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    connections.forEach { conn ->
                        val isSel = conn.id == connectionId
                        Row(
                            modifier =
                                Modifier
                                    .background(
                                        if (isSel) Color(conn.color).copy(alpha = 0.09f) else Palette.card,
                                        RoundedCornerShape(6.dp),
                                    ).border(
                                        1.dp,
                                        if (isSel) Color(conn.color) else Palette.border,
                                        RoundedCornerShape(6.dp),
                                    ).clickable { connectionId = conn.id }
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(
                                modifier =
                                    Modifier
                                        .size(8.dp)
                                        .background(Color(conn.color), CircleShape),
                            )
                            ConsoleText(
                                conn.name,
                                size = 12,
                                color = if (isSel) Palette.text else Palette.dim,
                            )
                        }
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionLabel("BUTTONS")
                if (fetching) {
                    ConsoleText("fetching commands…", size = 11, color = Palette.dim)
                }
                connections.forEach { conn ->
                    val commands = offered[conn.id].orEmpty()
                    val error = fetchErrors[conn.id]
                    if (commands.isEmpty() && error == null) return@forEach

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 6.dp),
                    ) {
                        Box(modifier = Modifier.size(8.dp).background(Color(conn.color), CircleShape))
                        ConsoleText(conn.name.uppercase(), size = 11, color = Palette.dim, letterSpacing = 2.0)
                    }
                    if (error != null) {
                        ConsoleText(error, size = 11, color = Palette.red, maxLines = 3)
                    }
                    commands.forEach { cmd ->
                        val isSel = selected.any { it.name == cmd.name && it.connectionId == cmd.connectionId }
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .background(if (isSel) Palette.selBg else Palette.card, RoundedCornerShape(6.dp))
                                    .border(
                                        1.dp,
                                        if (isSel) Palette.selBorder else Palette.border,
                                        RoundedCornerShape(6.dp),
                                    ).clickable {
                                        selected =
                                            if (isSel) {
                                                selected.filterNot {
                                                    it.name == cmd.name && it.connectionId == cmd.connectionId
                                                }
                                            } else {
                                                selected + cmd
                                            }
                                    }.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (isSel) {
                                TintedIcon(R.drawable.ic_check, Palette.green, size = 15.dp)
                            } else {
                                Box(
                                    modifier =
                                        Modifier
                                            .size(15.dp)
                                            .border(1.dp, Palette.faint, RoundedCornerShape(3.dp)),
                                )
                            }
                            ConsoleText(
                                cmd.name,
                                size = 13,
                                color = if (isSel) Palette.text else Palette.sub,
                                modifier = Modifier.padding(start = 12.dp).weight(1f),
                            )
                            if (cmd.ping) {
                                ConsoleText("PING", size = 11, color = Palette.green, letterSpacing = 1.5)
                            }
                            if (isSel) {
                                val picked = selected.first { it.name == cmd.name && it.connectionId == cmd.connectionId }
                                ConsoleText(
                                    if (picked.elevated) "LONG CODE" else "SHORT CODE",
                                    size = 11,
                                    color = if (picked.elevated) Palette.amber else Palette.dim,
                                    letterSpacing = 1.5,
                                    modifier =
                                        Modifier
                                            .padding(start = 10.dp)
                                            .clickable {
                                                selected =
                                                    selected.map {
                                                        if (it.name == cmd.name && it.connectionId == cmd.connectionId) {
                                                            it.copy(elevated = !it.elevated)
                                                        } else {
                                                            it
                                                        }
                                                    }
                                            },
                                )
                            }
                        }
                    }
                }
                ConsoleText(
                    "pick from any relay: wake this machine through the pi, " +
                        "shut it down through the machine itself",
                    size = 11,
                    color = Palette.faint,
                )
            }
            if (machineId != null) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                scope.launch {
                                    store.update { s ->
                                        s.copy(
                                            machines = s.machines.filterNot { it.id == machineId },
                                            hero = s.hero?.takeIf { it.machineId != machineId },
                                            tile = s.tile?.takeIf { it.machineId != machineId },
                                        )
                                    }
                                    onDone()
                                }
                            }.padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    ConsoleText("delete machine", size = 12, color = Palette.red)
                }
            }
        }
        PrimaryButton(
            "save machine",
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 28.dp),
        ) {
            val connId = connectionId ?: return@PrimaryButton
            scope.launch {
                store.update { s ->
                    val machine =
                        (s.machine(machineId) ?: Machine()).copy(
                            name = name.trim(),
                            connectionId = connId,
                            commands = selected,
                        )
                    val index = s.machines.indexOfFirst { it.id == machine.id }
                    val machines =
                        if (index >= 0) {
                            s.machines.toMutableList().apply { set(index, machine) }
                        } else {
                            s.machines + machine
                        }
                    val defaultRef =
                        machine.commands
                            .firstOrNull()
                            ?.let { ButtonRef(machine.id, it.name) }
                    s.copy(
                        machines = machines,
                        hero = s.hero ?: defaultRef,
                        tile = s.tile ?: defaultRef,
                    )
                }
                onDone()
            }
        }
    }
}
