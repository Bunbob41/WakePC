package com.morgan.wakepc

/*
 * The logic of first-time setup, kept apart from its screen so it can be
 * tested without one. Setup used to be two forms and a config file on the
 * relay, with nothing checking any step: every mistake surfaced at the end
 * as the same timeout. Here each step proves itself before the next begins.
 */

/** What a first look at a relay found. */
sealed interface RelayCheck {
    data class Reachable(
        /** The connection, named after the relay if it had no name yet. */
        val connection: Connection,
        /** Null from a relay that predates `/health`. */
        val health: RelayHealth?,
        val commands: List<CommandRef>,
    ) : RelayCheck {
        /** Whether this relay can look around its network and take a machine from the phone. */
        val canAddMachines: Boolean get() = health != null && health.api >= MACHINES_API && health.phoneSetup
    }

    data class Unreachable(
        val kind: FailureKind,
        val text: String,
    ) : RelayCheck
}

/** The relay API version that brought `/scan` and `/machines`. */
const val MACHINES_API = 2

/** The connection a typed address stands for, before anything is known about the relay. */
fun draftConnection(address: String): Connection = Connection(baseUrl = cleanUrl(address))

/** "http://pi-nd:8787" is "pi-nd": what to call a relay that has not said its own name. */
fun hostOf(url: String): String =
    url
        .substringAfter("://")
        .substringBefore('/')
        .substringBeforeLast(':')
        .ifBlank { "relay" }

/**
 * Ask the relay how it is and what it offers. An older relay has no
 * `/health`, which is not a failure: its commands still tell us it is there.
 */
suspend fun checkRelay(
    api: WakeRepository,
    connection: Connection,
): RelayCheck {
    val health = api.health(connection)
    health.exceptionOrNull()?.let { failure ->
        if (failure.failureKind != FailureKind.OLD_RELAY) {
            return RelayCheck.Unreachable(failure.failureKind, failure.message.orEmpty())
        }
    }
    val commands = api.fetchCommands(connection)
    commands.exceptionOrNull()?.let { failure ->
        return RelayCheck.Unreachable(failure.failureKind, failure.message.orEmpty())
    }
    val found = health.getOrNull()
    val name = connection.name.ifBlank { found?.name?.takeIf { it.isNotBlank() } ?: hostOf(connection.baseUrl) }
    return RelayCheck.Reachable(connection.copy(name = name), found, commands.getOrDefault(emptyList()))
}

/** A starting name for a machine picked from the scan; the user can change it. */
fun suggestedName(device: LanDevice): String =
    device.name
        ?.replace('-', ' ')
        ?.trim()
        .orEmpty()
        .ifBlank { "my pc" }

/** The app's own record of a machine the relay now holds. */
fun machineFor(
    connection: Connection,
    name: String,
    relayMachine: RelayMachine,
): Machine =
    Machine(
        name = name,
        connectionId = connection.id,
        // Without an address the relay cannot ping it, so there is no status to show.
        commands = listOf(CommandRef(relayMachine.commandName, ping = relayMachine.ip != null)),
    )

/** A machine built around a command the relay already had, for relays set up by hand. */
fun machineFor(
    connection: Connection,
    name: String,
    command: CommandRef,
): Machine = Machine(name = name, connectionId = connection.id, commands = listOf(command.copy(connectionId = null)))

/** Save a relay, replacing an earlier save of the same one. */
fun AppState.withConnection(connection: Connection): AppState =
    if (connections.any { it.id == connection.id }) {
        copy(connections = connections.map { if (it.id == connection.id) connection else it })
    } else {
        copy(connections = connections + connection)
    }

/** Add a machine. The first one also becomes the big button and the tile. */
fun AppState.withMachine(machine: Machine): AppState {
    val first = machine.commands.firstOrNull()?.let { ButtonRef(machine.id, it.name) }
    return copy(machines = machines + machine, hero = hero ?: first, tile = tile ?: first)
}

/** Relay commands not yet behind a button on this relay, and safe to offer as a wake. */
fun unusedCommands(
    state: AppState,
    connection: Connection,
    commands: List<CommandRef>,
): List<CommandRef> {
    val used =
        state.machines
            .flatMap { machine -> machine.commands.map { (it.connectionId ?: machine.connectionId) to it.name } }
            .filter { it.first == connection.id }
            .map { it.second }
            .toSet()
    return commands.filter { !it.elevated && it.name !in used }
}

/** Accepts a MAC however it is typed, or null when it is not one. */
fun cleanMac(raw: String): String? {
    val hex = raw.filter { it.isLetterOrDigit() }.lowercase()
    if (hex.length != MAC_HEX_DIGITS || hex.any { it !in "0123456789abcdef" }) return null
    return hex.chunked(2).joinToString(":")
}

private const val MAC_HEX_DIGITS = 12
