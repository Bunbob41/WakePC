package com.morgan.wakepc

import kotlinx.coroutines.delay

/** Where a guided wake has got to. Each is a line the user can read. */
sealed interface WakeStage {
    data object Sending : WakeStage

    /** The packet is out. [up] once the machine pings but is not usable yet. */
    data class Waiting(
        val seconds: Int,
        val up: Boolean,
    ) : WakeStage

    data class Usable(
        val seconds: Int,
        val readiness: Readiness,
    ) : WakeStage

    /** The relay could not be asked: nothing was learned about the PC. */
    data class RelayFailed(
        val text: String,
    ) : WakeStage

    /** The relay sent the packet and the time ran out. [up] if it at least pinged. */
    data class NoAnswer(
        val up: Boolean,
    ) : WakeStage

    val finished: Boolean get() = this is Usable || this is RelayFailed || this is NoAnswer
}

const val FIRST_WAKE_ATTEMPTS = 30
const val FIRST_WAKE_STEP_MS = 3_000L
private const val MS_PER_SECOND = 1_000L

/**
 * Wake a machine and report each stage as it happens. By the time this runs
 * the phone and the relay are known to be fine, so a wake that fails here
 * can be pinned on the PC's own settings, which is what the hints then say.
 */
suspend fun guidedWake(
    api: WakeRepository,
    connection: Connection,
    command: CommandRef,
    onStage: (WakeStage) -> Unit,
): WakeStage {
    onStage(WakeStage.Sending)
    val failure = api.run(connection, command.name).exceptionOrNull()
    val end =
        when {
            failure != null -> WakeStage.RelayFailed(failure.message.orEmpty().ifBlank { "the relay could not be reached" })

            // No address to ping: the packet went out, and that is all anyone can know.
            !command.ping -> WakeStage.Usable(0, Readiness.AWAKE)

            else -> awaitUsable(api, connection, command, onStage)
        }
    return end.also(onStage)
}

/** Poll until the machine is usable or the time runs out, reporting the wait. */
private suspend fun awaitUsable(
    api: WakeRepository,
    connection: Connection,
    command: CommandRef,
    onStage: (WakeStage) -> Unit,
): WakeStage {
    var up = false
    onStage(WakeStage.Waiting(0, up))
    repeat(FIRST_WAKE_ATTEMPTS) { attempt ->
        delay(FIRST_WAKE_STEP_MS)
        val seconds = ((attempt + 1) * FIRST_WAKE_STEP_MS / MS_PER_SECOND).toInt()
        // A relay that drops out for a moment mid-wake is ridden through.
        val now = api.status(connection, command.name).getOrNull()
        if (now?.usable == true) return WakeStage.Usable(seconds, now)
        if (now == Readiness.UP) up = true
        onStage(WakeStage.Waiting(seconds, up))
    }
    return WakeStage.NoAnswer(up)
}

/** What to check when the packet went out and the PC never answered. */
val WAKE_HINTS =
    listOf(
        "wake on lan is switched off in the pc's BIOS. while there, turn off ErP or deep sleep too",
        "windows: device manager, your ethernet adapter, power management, allow this device to wake the computer",
        "windows fast startup is on. turn it off under power options, choose what the power buttons do",
        "the pc is on wifi. wake on lan needs an ethernet cable",
    )
