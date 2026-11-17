package com.morgan.wakepc

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    private val connection = Connection(id = "c1", name = "my pi", baseUrl = "http://pi:8787", token = "test-token")
    private val wake = CommandRef("wake-pc", ping = true)
    private val reboot = CommandRef("reboot-pi", ping = false)
    private val machine = Machine(id = "m1", name = "desk pc", connectionId = "c1", commands = listOf(wake, reboot))
    private val appState = AppState(connections = listOf(connection), machines = listOf(machine))

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(fake: FakeWakeRepository) = HomeViewModel(MutableStateFlow(appState), fake, pollIntervalMs = 1_000)

    @Test
    fun `a ping command that comes up marks the machine awake`() =
        runTest(dispatcher) {
            val fake = FakeWakeRepository()
            val vm = viewModel(fake)

            vm.run(machine, connection, wake)
            advanceUntilIdle()

            assertTrue(fake.runCalls.contains("wake-pc"))
            assertEquals(true, vm.runtime.value["m1"]?.awake)
            assertNull("running should clear when done", vm.runtime.value["m1"]?.running)
        }

    @Test
    fun `the confirmation code is forwarded to the relay, which checks it too`() =
        runTest(dispatcher) {
            val fake = FakeWakeRepository()
            val vm = viewModel(fake)

            vm.run(machine, connection, reboot, confirmCode = "246810")
            advanceUntilIdle()

            assertEquals(listOf("246810"), fake.confirmCodes)
        }

    @Test
    fun `an ordinary command sends no code`() =
        runTest(dispatcher) {
            val fake = FakeWakeRepository()
            val vm = viewModel(fake)

            vm.run(machine, connection, wake)
            advanceUntilIdle()

            assertEquals(listOf(""), fake.confirmCodes)
        }

    @Test
    fun `a wake that never answers leaves the machine not awake`() =
        runTest(dispatcher) {
            val fake = FakeWakeRepository().apply { statsResult = Result.success(asleep()) }
            val vm = viewModel(fake)

            vm.run(machine, connection, wake)
            advanceUntilIdle()

            assertTrue(fake.runCalls.contains("wake-pc"))
            assertEquals(false, vm.runtime.value["m1"]?.awake == true)
            assertNull(vm.runtime.value["m1"]?.running)
        }

    @Test
    fun `an unreachable pi surfaces and clears`() =
        runTest(dispatcher) {
            val fake = FakeWakeRepository().apply { runResult = Result.failure(RuntimeException("boom")) }
            val vm = viewModel(fake)

            vm.run(machine, connection, wake)
            advanceUntilIdle()

            assertNull(vm.runtime.value["m1"]?.running)
            assertEquals(false, vm.runtime.value["m1"]?.awake == true)
        }

    @Test
    fun `probe records average rtt and clears running`() =
        runTest(dispatcher) {
            val fake =
                FakeWakeRepository().apply {
                    statsResult = Result.success(PingStats(awake = true, lossPct = 0.0, minMs = 1.0, avgMs = 4.5, maxMs = 6.0))
                }
            val vm = viewModel(fake)

            vm.probe(machine, connection, wake)
            advanceUntilIdle()

            assertEquals(true, vm.runtime.value["m1"]?.awake)
            assertEquals(4.5, vm.runtime.value["m1"]?.avgMs)
            assertNull(vm.runtime.value["m1"]?.running)
        }

    @Test
    fun `a second tap while one is in flight is ignored`() =
        runTest(dispatcher) {
            val fake = FakeWakeRepository().apply { runGate = CompletableDeferred() }
            val vm = viewModel(fake)

            vm.run(machine, connection, wake)
            advanceUntilIdle() // suspends inside run() awaiting the gate
            assertEquals("wake-pc", vm.runtime.value["m1"]?.running)

            vm.run(machine, connection, wake) // must no-op
            advanceUntilIdle()
            assertEquals("one run only", 1, fake.runCalls.size)

            fake.runGate!!.complete(Unit)
            advanceUntilIdle()
        }

    @Test
    fun `a wake keeps waiting through UP until the ready port answers`() =
        runTest(dispatcher) {
            val fake =
                FakeWakeRepository().apply {
                    statsQueue.addAll(listOf(asleep(), up(), up(), ready()))
                }
            val vm = viewModel(fake)

            vm.run(machine, connection, wake)
            advanceUntilIdle()

            assertEquals("stopped polling at the wrong point", 4, fake.statsCalls)
            assertEquals(Readiness.READY, vm.runtime.value["m1"]?.readiness)
            assertNull(vm.runtime.value["m1"]?.transient)
        }

    @Test
    fun `a machine that never gets past UP ends up but not ready`() =
        runTest(dispatcher) {
            val fake = FakeWakeRepository().apply { statsResult = Result.success(up()) }
            val vm = viewModel(fake)

            vm.run(machine, connection, wake)
            advanceUntilIdle()

            assertEquals("should poll the whole window", 30, fake.statsCalls)
            assertEquals(Readiness.UP, vm.runtime.value["m1"]?.readiness)
            assertNull(vm.runtime.value["m1"]?.running)
        }

    @Test
    fun `a relay with no ready port finishes at the first ping, as before`() =
        runTest(dispatcher) {
            val fake = FakeWakeRepository().apply { statsQueue.addAll(listOf(asleep(), awakeOnly())) }
            val vm = viewModel(fake)

            vm.run(machine, connection, wake)
            advanceUntilIdle()

            assertEquals(2, fake.statsCalls)
            assertEquals(Readiness.AWAKE, vm.runtime.value["m1"]?.readiness)
        }

    @Test
    fun `the background poll records readiness`() =
        runTest(dispatcher) {
            val fake = FakeWakeRepository().apply { statsResult = Result.success(up()) }
            val vm = viewModel(fake)

            vm.startPolling()
            dispatcher.scheduler.runCurrent()

            assertEquals(Readiness.UP, vm.runtime.value["m1"]?.readiness)
            // The poll never ends by itself; left running, it keeps the test alive.
            vm.viewModelScope.cancel()
        }

    @Test
    fun `a relay that cannot be reached is shown as that, not as a sleeping pc`() =
        runTest(dispatcher) {
            val fake = FakeWakeRepository()
            val vm = viewModel(fake)

            vm.startPolling()
            dispatcher.scheduler.runCurrent()
            assertEquals(Readiness.AWAKE, vm.runtime.value["m1"]?.readiness)

            // The relay drops off: what we knew about the PC is now unknown.
            fake.statsResult = Result.failure(RelayException(FailureKind.NO_ANSWER, "no answer from my pi"))
            dispatcher.scheduler.advanceTimeBy(1_500)
            assertEquals(true, vm.runtime.value["m1"]?.relayDown)
            assertNull("a stale AWAKE must not survive a dead relay", vm.runtime.value["m1"]?.readiness)

            // And it comes back by itself.
            fake.statsResult = Result.success(asleep())
            dispatcher.scheduler.advanceTimeBy(1_000)
            assertEquals(false, vm.runtime.value["m1"]?.relayDown)
            assertEquals(Readiness.ASLEEP, vm.runtime.value["m1"]?.readiness)
            vm.viewModelScope.cancel()
        }

    @Test
    fun `a relay that answers with an error is not treated as down`() =
        runTest(dispatcher) {
            val fake = FakeWakeRepository()
            val vm = viewModel(fake)

            vm.startPolling()
            dispatcher.scheduler.runCurrent()
            fake.statsResult = Result.failure(RelayException(FailureKind.NOT_FOUND, "unknown command"))
            dispatcher.scheduler.advanceTimeBy(1_500)

            assertEquals(false, vm.runtime.value["m1"]?.relayDown)
            vm.viewModelScope.cancel()
        }

    @Test
    fun `a wake the relay never received marks the relay down`() =
        runTest(dispatcher) {
            val fake = FakeWakeRepository().apply { runResult = Result.failure(RelayException(FailureKind.NO_ANSWER, "no answer")) }
            val vm = viewModel(fake)

            vm.run(machine, connection, wake)
            dispatcher.scheduler.runCurrent()

            assertEquals(
                "NO RELAY",
                vm.runtime.value["m1"]
                    ?.transient
                    ?.text,
            )
            assertEquals(
                Tone.BAD,
                vm.runtime.value["m1"]
                    ?.transient
                    ?.tone,
            )
            advanceUntilIdle()
            assertEquals(true, vm.runtime.value["m1"]?.relayDown)
            assertEquals("nothing was sent, so nothing is polled", 0, fake.statsCalls)
        }

    @Test
    fun `what a relay wants looked at reaches the home screen`() =
        runTest(dispatcher) {
            val fake = FakeWakeRepository().apply { warnings = listOf("this relay's Tailscale key expires in 5 days") }
            val vm = viewModel(fake)

            vm.startPolling()
            dispatcher.scheduler.runCurrent()

            assertEquals(mapOf("c1" to fake.warnings), vm.relayNotes.value)
            vm.viewModelScope.cancel()
        }

    private fun asleep() = PingStats(awake = false, lossPct = 100.0, minMs = null, avgMs = null, maxMs = null)

    private fun up() = PingStats(awake = true, lossPct = 0.0, minMs = 1.0, avgMs = 2.0, maxMs = 3.0, ready = false)

    private fun ready() = up().copy(ready = true)

    private fun awakeOnly() = up().copy(ready = null)
}

private class FakeWakeRepository : WakeRepository {
    val runCalls = mutableListOf<String>()
    val confirmCodes = mutableListOf<String>()
    var runResult: Result<Unit> = Result.success(Unit)
    var statsResult: Result<PingStats> = Result.success(PingStats(true, 0.0, 1.0, 2.0, 3.0))
    var runGate: CompletableDeferred<Unit>? = null

    /** Replies handed out one per status call before falling back to [statsResult]. */
    val statsQueue = ArrayDeque<PingStats>()
    var statsCalls = 0

    override suspend fun fetchCommands(connection: Connection): Result<List<CommandRef>> = Result.success(emptyList())

    var warnings = emptyList<String>()

    override suspend fun health(connection: Connection): Result<RelayHealth> =
        Result.success(RelayHealth(api = 2, name = "pi", you = null, machines = 1, phoneSetup = true, warnings = warnings))

    override suspend fun run(
        connection: Connection,
        command: String,
        confirmCode: String,
    ): Result<Unit> {
        runCalls += command
        confirmCodes += confirmCode
        runGate?.await()
        return runResult
    }

    override suspend fun stats(
        connection: Connection,
        command: String,
        count: Int,
    ): Result<PingStats> {
        statsCalls++
        return statsQueue.removeFirstOrNull()?.let { Result.success(it) } ?: statsResult
    }
}
