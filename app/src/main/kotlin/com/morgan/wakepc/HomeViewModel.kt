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

/** Semantic status colour — the UI maps this to the palette, the VM stays colour-free. */
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
)

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

    private var pollJob: Job? = null

    /** Idempotent: the screen calls this when it becomes active. */
    fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob =
            viewModelScope.launch {
                while (isActive) {
                    pollOnce()
                    delay(pollIntervalMs)
                }
            }
    }

    private suspend fun pollOnce() {
        state.value.machines.forEach { machine ->
            val pingCmd = machine.commands.firstOrNull { it.ping } ?: return@forEach
            val connection = state.value.connectionFor(machine, pingCmd) ?: return@forEach
            if (_runtime.value[machine.id]?.running != null) return@forEach
            api.stats(connection, pingCmd.name).onSuccess { stats ->
                update(machine.id) { it.copy(awake = stats.awake, avgMs = stats.avgMs ?: it.avgMs) }
            }
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
        if (api.run(connection, command.name, confirmCode).isFailure) {
            flash(machine.id, "UNREACHABLE", Tone.NEUTRAL, glow = false, holdMs = 3_000)
            return
        }
        repeat(WAKE_ATTEMPTS) { attempt ->
            delay(POLL_STEP_MS)
            val secs = (attempt + 1) * (POLL_STEP_MS / MS_PER_SECOND).toInt()
            val clock = "${secs / SECONDS_PER_MINUTE}:${(secs % SECONDS_PER_MINUTE).toString().padStart(2, '0')}"
            setTransient(machine.id, "WAKING · $clock", Tone.ACTIVE, pulse = true, glow = true)
            if (api.status(connection, command.name).getOrDefault(false)) {
                update(machine.id) { it.copy(awake = true, transient = null) }
                return
            }
        }
        flash(machine.id, "NO REPLY YET", Tone.NEUTRAL, glow = false, holdMs = 4_000)
    }

    private suspend fun runFireAndForget(
        machine: Machine,
        connection: Connection,
        command: CommandRef,
        confirmCode: String,
    ) {
        setTransient(machine.id, "RUNNING", Tone.ACTIVE, pulse = true, glow = true)
        val ok = api.run(connection, command.name, confirmCode).isSuccess
        flash(machine.id, if (ok) "OK" else "FAILED", if (ok) Tone.GOOD else Tone.BAD, glow = ok, holdMs = 2_500)
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
                    update(machine.id) { it.copy(awake = stats.awake, avgMs = stats.avgMs ?: it.avgMs) }
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
                onFailure = { setTransient(machine.id, "UNREACHABLE", Tone.NEUTRAL, glow = false) },
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
        const val MS_PER_SECOND = 1_000L
        const val SECONDS_PER_MINUTE = 60
    }
}

internal fun fmtMs(value: Double): String = String.format(java.util.Locale.US, "%.1f", value)
