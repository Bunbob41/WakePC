package com.morgan.wakepc

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

    private fun asleep() = PingStats(awake = false, lossPct = 100.0, minMs = null, avgMs = null, maxMs = null)
}

private class FakeWakeRepository : WakeRepository {
    val runCalls = mutableListOf<String>()
    var runResult: Result<Unit> = Result.success(Unit)
    var statsResult: Result<PingStats> = Result.success(PingStats(true, 0.0, 1.0, 2.0, 3.0))
    var runGate: CompletableDeferred<Unit>? = null

    override suspend fun fetchCommands(connection: Connection): Result<List<CommandRef>> = Result.success(emptyList())

    override suspend fun run(
        connection: Connection,
        command: String,
    ): Result<Unit> {
        runCalls += command
        runGate?.await()
        return runResult
    }

    override suspend fun status(
        connection: Connection,
        command: String,
    ): Result<Boolean> = statsResult.map { it.awake }

    override suspend fun stats(
        connection: Connection,
        command: String,
        count: Int,
    ): Result<PingStats> = statsResult
}
