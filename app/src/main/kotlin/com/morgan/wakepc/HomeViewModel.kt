package com.morgan.wakepc

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Semantic status colour: the UI maps this to the palette, the VM stays colour-free. */
enum class Tone { NEUTRAL, ACTIVE, GOOD, BAD }

data class Transient(
    val text: String,
    val tone: Tone,
    val pulse: Boolean,
    val glow: Boolean,
)

/** Per-machine live status the home screen renders over the persisted model. */
data class MachineRuntime(
    val transient: Transient? = null,
    val awake: Boolean? = null,
    val avgMs: Double? = null,
    val running: String? = null,
    /** The relay's ready port answers; null when it has none to check. See [Readiness]. */
    val ready: Boolean? = null,
    /**
     * The last request never reached the relay, so nothing above is known:
     * the PC may be on or off. Shown as its own state rather than left as a
     * stale AWAKE or a blank, which is what made a dead relay look like a
     * sleeping PC.
     */
    val relayDown: Boolean = false,
) {
    val readiness: Readiness? get() = readinessOf(awake, ready)
}

/**
 * Owns everything that must outlive a screen rotation: the background status
 * poll and any in-flight wake/probe. Because these run in [viewModelScope],
 * tapping wake and then rotating no longer abandons the polling loop.
 */
class HomeViewModel(
    stateFlow: Flow<AppState>,
    private val api: WakeRepository = WakeApi,
    private val pollIntervalMs: Long = 15_000,
) : ViewModel() {
    val state: StateFlow<AppState> =
        stateFlow.stateIn(viewModelScope, SharingStarted.Eagerly, AppState())

    private val _runtime = MutableStateFlow<Map<String, MachineRuntime>>(emptyMap())
    val runtime: StateFlow<Map<String, MachineRuntime>> = _runtime.asStateFlow()

    /** What each relay says needs looking at, keyed by connection id. */
    private val _relayNotes = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val relayNotes: StateFlow<Map<String, List<String>>> = _relayNotes.asStateFlow()

    private var pollJob: Job? = null

    /** Idempotent: the screen calls this when it becomes active. */
    fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob =
            viewModelScope.launch {
                var round = 0
                while (isActive) {
                    // Health changes slowly (a key nearing expiry), so ask rarely.
                    if (round++ % HEALTH_EVERY_N_POLLS == 0) checkRelays()
                    pollOnce()
                    delay(pollIntervalMs)
                }
            }
    }

    private suspend fun checkRelays() {
        state.value.connections.forEach { connection ->
            // An older relay has no /health and so no notes; that is not news.
            api.health(connection).onSuccess { health ->
                _relayNotes.update { it + (connection.id to health.warnings) }
            }
        }
    }

    private suspend fun pollOnce() {
        state.value.machines.forEach { machine ->
            val pingCmd = machine.commands.firstOrNull { it.ping } ?: return@forEach
            val connection = state.value.connectionFor(machine, pingCmd) ?: return@forEach
            if (_runtime.value[machine.id]?.running != null) return@forEach
            val result = api.stats(connection, pingCmd.name)
            // A wake may have started while this was in flight; it owns the card now.
            if (_runtime.value[machine.id]?.running != null) return@forEach
            result.fold(
                onSuccess = { stats ->
                    update(machine.id) {
                        it.copy(awake = stats.awake, ready = stats.ready, avgMs = stats.avgMs ?: it.avgMs, relayDown = false)
                    }
                },
                onFailure = { failure ->
                    // Only a relay we could not reach wipes what we knew. One
                    // that answered with an error is still there.
                    if (failure.failureKind.relayDown) {
                        update(machine.id) { it.copy(awake = null, ready = null, relayDown = true) }
                    } else {
                        update(machine.id) { it.copy(relayDown = false) }
                    }
                },
            )
        }
    }

    /**
     * [confirmCode] is forwarded to the relay for commands it marked elevated:
     * the relay checks it too, so the gate is not just this app's good manners.
     */
    fun run(
        machine: Machine,
        connection: Connection,
        command: CommandRef,
        confirmCode: String = "",
    ) {
        if (_runtime.value[machine.id]?.running != null) return
        viewModelScope.launch {
            update(machine.id) { it.copy(running = command.name) }
            try {
                if (command.ping) {
                    runWithWake(machine, connection, command, confirmCode)
                } else {
                    runFireAndForget(machine, connection, command, confirmCode)
                }
            } finally {
                update(machine.id) { it.copy(running = null) }
            }
        }
    }

    private suspend fun runWithWake(
        machine: Machine,
        connection: Connection,
        command: CommandRef,
        confirmCode: String,
    ) {
        setTransient(machine.id, "WAKING · 0:00", Tone.ACTIVE, pulse = true, glow = true)
        api.run(connection, command.name, confirmCode).exceptionOrNull()?.let { failure ->
            val kind = failure.failureKind
            if (kind.relayDown) update(machine.id) { it.copy(awake = null, ready = null, relayDown = true) }
            flash(machine.id, kind.chip(), Tone.BAD, glow = false, holdMs = 4_000)
            return
        }
        update(machine.id) { it.copy(relayDown = false) }
        // Answering ping is not the end when the relay checks a ready port: a
        // machine that is UP is still starting, so keep waiting for READY.
        var last: Readiness? = null
        repeat(WAKE_ATTEMPTS) { attempt ->
            delay(POLL_STEP_MS)
            val secs = (attempt + 1) * (POLL_STEP_MS / MS_PER_SECOND).toInt()
            val clock = "${secs / SECONDS_PER_MINUTE}:${(secs % SECONDS_PER_MINUTE).toString().padStart(2, '0')}"
            val now = api.status(connection, command.name).getOrNull()
            if (now != null) last = now
            if (now?.usable == true) {
                update(machine.id) { it.copy(awake = true, ready = now.readyFlag, transient = null, relayDown = false) }
                return
            }
            val word = if (now == Readiness.UP) "UP" else "WAKING"
            setTransient(machine.id, "$word · $clock", Tone.ACTIVE, pulse = true, glow = true)
        }
        if (last == Readiness.UP) {
            update(machine.id) { it.copy(awake = true, ready = false) }
            flash(machine.id, "UP · NOT READY YET", Tone.ACTIVE, glow = false, holdMs = 4_000)
        } else {
            // The relay took the wake, so this is the PC not answering, not the relay.
            flash(machine.id, "PC DID NOT ANSWER", Tone.BAD, glow = false, holdMs = 6_000)
        }
    }

    private suspend fun runFireAndForget(
        machine: Machine,
        connection: Connection,
        command: CommandRef,
        confirmCode: String,
    ) {
        setTransient(machine.id, "RUNNING", Tone.ACTIVE, pulse = true, glow = true)
        val failure = api.run(connection, command.name, confirmCode).exceptionOrNull()
        val ok = failure == null
        // Any answer at all means the relay is there; only silence means it is not.
        update(machine.id) { it.copy(relayDown = failure?.failureKind?.relayDown == true) }
        flash(machine.id, if (ok) "OK" else failure.failureKind.chip(), if (ok) Tone.GOOD else Tone.BAD, glow = ok, holdMs = 2_500)
    }

    fun probe(
        machine: Machine,
        connection: Connection,
        command: CommandRef,
    ) {
        if (_runtime.value[machine.id]?.running != null) return
        viewModelScope.launch {
            update(machine.id) { it.copy(running = command.name) }
            setTransient(machine.id, "PROBING · 5 PINGS", Tone.ACTIVE, pulse = true, glow = true)
            api.stats(connection, command.name, count = 5).fold(
                onSuccess = { stats ->
                    update(machine.id) {
                        it.copy(awake = stats.awake, ready = stats.ready, avgMs = stats.avgMs ?: it.avgMs, relayDown = false)
                    }
                    val text =
                        stats.avgMs?.let { avg ->
                            "${fmtMs(stats.minMs ?: avg)}/${fmtMs(avg)}/${fmtMs(stats.maxMs ?: avg)}MS · " +
                                "${stats.lossPct.toInt()}% LOSS"
                        }
                    if (text != null) {
                        setTransient(machine.id, text, if (stats.lossPct > 0) Tone.ACTIVE else Tone.GOOD, glow = true)
                    } else {
                        setTransient(machine.id, "NO REPLY · 100% LOSS", Tone.BAD, glow = false)
                    }
                },
                onFailure = { failure ->
                    update(machine.id) { it.copy(relayDown = failure.failureKind.relayDown) }
                    setTransient(machine.id, failure.failureKind.chip(), Tone.BAD, glow = false)
                },
            )
            delay(PROBE_RESULT_HOLD_MS)
            update(machine.id) { it.copy(transient = null, running = null) }
        }
    }

    private fun setTransient(
        id: String,
        text: String,
        tone: Tone,
        pulse: Boolean = false,
        glow: Boolean,
    ) {
        update(id) { it.copy(transient = Transient(text, tone, pulse, glow)) }
    }

    private suspend fun flash(
        id: String,
        text: String,
        tone: Tone,
        glow: Boolean,
        holdMs: Long,
    ) {
        setTransient(id, text, tone, glow = glow)
        delay(holdMs)
        update(id) { it.copy(transient = null) }
    }

    private fun update(
        id: String,
        transform: (MachineRuntime) -> MachineRuntime,
    ) {
        _runtime.update { it + (id to transform(it[id] ?: MachineRuntime())) }
    }

    private companion object {
        const val WAKE_ATTEMPTS = 30
        const val POLL_STEP_MS = 3_000L
        const val PROBE_RESULT_HOLD_MS = 6_000L
        const val HEALTH_EVERY_N_POLLS = 40
        const val MS_PER_SECOND = 1_000L
        const val SECONDS_PER_MINUTE = 60
    }
}

/** A failure as the few capitals a status chip has room for. */
fun FailureKind.chip(): String =
    when (this) {
        FailureKind.NO_ADDRESS, FailureKind.BAD_NAME, FailureKind.NO_ANSWER, FailureKind.REFUSED -> RELAY_DOWN_CHIP
        FailureKind.NOT_RECOGNISED -> "NOT RECOGNISED"
        FailureKind.WRONG_CODE -> "WRONG CODE"
        FailureKind.NOT_FOUND, FailureKind.OLD_RELAY -> "NOT ON RELAY"
        FailureKind.REJECTED, FailureKind.OTHER -> "FAILED"
    }

const val RELAY_DOWN_CHIP = "NO RELAY"

internal fun fmtMs(value: Double): String = String.format(java.util.Locale.US, "%.1f", value)
