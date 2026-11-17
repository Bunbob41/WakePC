package com.morgan.wakepc

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Setup to first wake, as logic. The old path was a config file on the relay
 * and two forms in the app, with nothing checked until the very end.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SetupTest {
    private val pi = Connection(id = "c1", name = "", baseUrl = "http://pi-nd:8787")
    private val healthy = RelayHealth(api = 2, name = "garage-pc", you = "phone", machines = 0, phoneSetup = true, warnings = emptyList())
    private val wake = CommandRef("wake-desk-pc", ping = true)

    private class FakeRelay(
        var health: Result<RelayHealth>,
        var commands: Result<List<CommandRef>> = Result.success(emptyList()),
        var runResult: Result<Unit> = Result.success(Unit),
    ) : WakeRepository {
        val statuses = ArrayDeque<Result<PingStats>>()
        var runs = 0

        override suspend fun health(connection: Connection) = health

        override suspend fun fetchCommands(connection: Connection) = commands

        override suspend fun run(
            connection: Connection,
            command: String,
            confirmCode: String,
        ): Result<Unit> {
            runs++
            return runResult
        }

        override suspend fun stats(
            connection: Connection,
            command: String,
            count: Int,
        ): Result<PingStats> = statuses.removeFirstOrNull() ?: Result.success(asleep)
    }

    private fun down(kind: FailureKind) = Result.failure<Nothing>(RelayException(kind, failureText(kind, "pi-nd")))

    @Test
    fun `an address typed any way becomes a connection`() {
        assertEquals("http://pi-nd:8787", draftConnection(" pi-nd ").baseUrl)
        assertEquals("http://100.84.117.69:8787", draftConnection("100.84.117.69").baseUrl)
        assertEquals("pi-nd", hostOf("http://pi-nd:8787"))
        assertEquals("pi.tail.ts.net", hostOf("https://pi.tail.ts.net/"))
    }

    @Test
    fun `a relay that answers is named after itself`() =
        runTest {
            val check = checkRelay(FakeRelay(Result.success(healthy)), pi) as RelayCheck.Reachable
            assertEquals("garage-pc", check.connection.name)
            assertTrue(check.canAddMachines)
        }

    @Test
    fun `a name the user chose is kept`() =
        runTest {
            val check = checkRelay(FakeRelay(Result.success(healthy)), pi.copy(name = "my pi")) as RelayCheck.Reachable
            assertEquals("my pi", check.connection.name)
        }

    @Test
    fun `an older relay still connects but cannot look for machines`() =
        runTest {
            val old = FakeRelay(down(FailureKind.OLD_RELAY), Result.success(listOf(CommandRef("wake-pc", ping = true))))
            val check = checkRelay(old, pi) as RelayCheck.Reachable
            assertNull(check.health)
            assertFalse(check.canAddMachines)
            assertEquals("pi-nd", check.connection.name)
            assertEquals(listOf("wake-pc"), check.commands.map { it.name })
        }

    @Test
    fun `phone setup switched off on the relay is respected`() =
        runTest {
            val check = checkRelay(FakeRelay(Result.success(healthy.copy(phoneSetup = false))), pi) as RelayCheck.Reachable
            assertFalse(check.canAddMachines)
        }

    @Test
    fun `a relay that cannot be reached says which link broke`() =
        runTest {
            val check = checkRelay(FakeRelay(down(FailureKind.NO_ANSWER)), pi) as RelayCheck.Unreachable
            assertEquals(FailureKind.NO_ANSWER, check.kind)
            assertTrue(check.kind.relayDown)
            assertTrue(check.text, "pi-nd" in check.text)

            val stranger = checkRelay(FakeRelay(down(FailureKind.NOT_RECOGNISED)), pi) as RelayCheck.Unreachable
            assertFalse("the relay answered, so it is not down", stranger.kind.relayDown)
        }

    @Test
    fun `a failure never blames the pc for a relay that is out of reach`() {
        FailureKind.entries.forEach { kind ->
            val text = failureText(kind, "my pi", detail = "detail")
            assertFalse("$kind: $text", "machine awake" in text)
            assertTrue("$kind should say something", text.isNotBlank())
        }
        assertTrue("tailscale" in failureText(FailureKind.NO_ANSWER, "my pi", vpnOff = true))
        assertTrue("not running" in failureText(FailureKind.REFUSED, "my pi"))
        assertEquals(
            "that machine is already here as Desk PC",
            failureText(FailureKind.REJECTED, "my pi", "that machine is already here as Desk PC"),
        )
    }

    @Test
    fun `the first machine becomes the big button and the tile`() {
        val relayMachine = RelayMachine("desk-pc", "Desk PC", "3c:7c:3f:12:34:56", "192.168.1.50", 3389)
        val connection = pi.copy(name = "my pi")
        val machine = machineFor(connection, "Desk PC", relayMachine)
        val state = AppState().withConnection(connection).withMachine(machine)

        assertEquals(listOf(CommandRef("wake-desk-pc", ping = true, connectionId = "c1")), machine.commands)
        assertEquals(ButtonRef(machine.id, "wake-desk-pc"), state.hero)
        assertEquals(state.hero, state.tile)
        assertEquals("my pi", state.resolve(state.hero)?.connection?.name)

        // A second machine does not take the button over.
        val second = machineFor(connection, "Server", relayMachine.copy(id = "server"))
        assertEquals(state.hero, state.withMachine(second).hero)
    }

    @Test
    fun `a machine from setup survives being saved and loaded`() {
        val connection = pi.copy(name = "my pi")
        val machine = machineFor(connection, "Desk PC", RelayMachine("desk-pc", "Desk PC", "3c:7c:3f:12:34:56", "192.168.1.50", null))
        val loaded = decodeState(encodeState(AppState().withConnection(connection).withMachine(machine)))

        assertEquals("my pi", loaded.resolve(loaded.hero)?.connection?.name)
        // Already behind a button, so setup does not offer it a second time.
        assertEquals(emptyList<CommandRef>(), unusedCommands(loaded, connection, machine.commands))
    }

    @Test
    fun `a command saved with no relay of its own loads as that, on a phone too`() {
        // Android's org.json reads a JSON null back as the word "null". A
        // command that came back pointing at a relay called "null" resolved
        // to nothing, and every button on its machine went dead.
        val saved =
            """{"connections": [{"id": "c1", "name": "my pi", "baseUrl": "http://pi:8787"}],
               "machines": [{"id": "m1", "name": "desk", "connectionId": "c1", "commands": [
                 {"name": "wake-pc", "ping": true, "connectionId": null},
                 {"name": "wake-b", "ping": true, "connectionId": "null"},
                 {"name": "wake-c", "ping": true}]}],
               "hero": {"machineId": "m1", "command": "wake-b"}}"""
        val loaded = decodeState(saved)

        assertEquals(
            listOf(null, null, null),
            loaded.machines
                .single()
                .commands
                .map { it.connectionId },
        )
        assertEquals("my pi", loaded.resolve(loaded.hero)?.connection?.name)
    }

    @Test
    fun `a machine added by MAC alone has no status to show`() {
        val machine = machineFor(pi, "Old PC", RelayMachine("old-pc", "Old PC", "3c:7c:3f:12:34:56", ip = null, ready = null))
        assertFalse(machine.commands.single().ping)
    }

    @Test
    fun `saving a relay twice updates it rather than duplicating it`() {
        val state = AppState().withConnection(pi).withConnection(pi.copy(name = "renamed"))
        assertEquals(listOf("renamed"), state.connections.map { it.name })
    }

    @Test
    fun `only safe relay commands not already in use are offered`() {
        val existing = Machine(id = "m1", name = "desk", connectionId = "c1", commands = listOf(CommandRef("wake-pc", ping = true)))
        val offered =
            unusedCommands(
                AppState(connections = listOf(pi), machines = listOf(existing)),
                pi,
                listOf(
                    CommandRef("wake-pc", ping = true),
                    CommandRef("wake-server", ping = true),
                    CommandRef("reboot-pi", ping = false, elevated = true),
                ),
            )
        assertEquals(listOf("wake-server"), offered.map { it.name })
    }

    @Test
    fun `a typed MAC is accepted in any common form`() {
        listOf("3C:7C:3F:12:34:56", "3c-7c-3f-12-34-56", "3c7c.3f12.3456", " 3C7C3F123456 ").forEach {
            assertEquals(it, "3c:7c:3f:12:34:56", cleanMac(it))
        }
        listOf("", "nonsense", "3C:7C:3F:12:34", "3C:7C:3F:12:34:5G").forEach { assertNull(it, cleanMac(it)) }
    }

    @Test
    fun `a scanned device suggests its own name`() {
        assertEquals("desk pc", suggestedName(LanDevice("192.168.1.50", "3c:7c:3f:12:34:56", "desk-pc", null)))
        assertEquals("my pc", suggestedName(LanDevice("192.168.1.50", "3c:7c:3f:12:34:56", null, null)))
    }

    @Test
    fun `a guided wake reports each stage until the pc is ready`() =
        runTest {
            val relay = FakeRelay(Result.success(healthy))
            relay.statuses += listOf(Result.success(asleep), Result.success(up), Result.success(ready))
            val stages = mutableListOf<WakeStage>()

            val end = guidedWake(relay, pi, wake) { stages += it }

            assertEquals(WakeStage.Usable(9, Readiness.READY), end)
            assertEquals(
                listOf(
                    WakeStage.Sending,
                    WakeStage.Waiting(0, up = false),
                    WakeStage.Waiting(3, up = false),
                    WakeStage.Waiting(6, up = true),
                    WakeStage.Usable(9, Readiness.READY),
                ),
                stages,
            )
            assertEquals(9_000, currentTime)
        }

    @Test
    fun `a wake the relay never took says so and does not wait`() =
        runTest {
            val relay = FakeRelay(Result.success(healthy), runResult = down(FailureKind.NO_ANSWER))
            val end = guidedWake(relay, pi, wake) {}
            assertTrue(end is WakeStage.RelayFailed)
            assertEquals("nothing was sent, so there is nothing to wait for", 0, currentTime)
        }

    @Test
    fun `a pc that never answers ends in the hints, after ninety seconds`() =
        runTest {
            val end = guidedWake(FakeRelay(Result.success(healthy)), pi, wake) {}
            assertEquals(WakeStage.NoAnswer(up = false), end)
            assertEquals(90_000, currentTime)
            assertTrue(WAKE_HINTS.isNotEmpty())
        }

    @Test
    fun `a pc that pings but never opens its port is not called a failure to wake`() =
        runTest {
            val relay = FakeRelay(Result.success(healthy))
            repeat(FIRST_WAKE_ATTEMPTS) { relay.statuses += Result.success(up) }
            assertEquals(WakeStage.NoAnswer(up = true), guidedWake(relay, pi, wake) {})
        }

    @Test
    fun `the relay dropping out mid-wake is ridden through`() =
        runTest {
            val relay = FakeRelay(Result.success(healthy))
            relay.statuses += listOf(down(FailureKind.NO_ANSWER), Result.success(awake))
            assertEquals(WakeStage.Usable(6, Readiness.AWAKE), guidedWake(relay, pi, wake) {})
        }

    @Test
    fun `a machine with no address is done once the packet is sent`() =
        runTest {
            val end = guidedWake(FakeRelay(Result.success(healthy)), pi, wake.copy(ping = false)) {}
            assertEquals(WakeStage.Usable(0, Readiness.AWAKE), end)
            assertEquals(0, currentTime)
        }

    private companion object {
        val asleep = PingStats(awake = false, lossPct = 100.0, minMs = null, avgMs = null, maxMs = null)
        val awake = PingStats(awake = true, lossPct = 0.0, minMs = 1.0, avgMs = 1.0, maxMs = 1.0)
        val up = awake.copy(ready = false)
        val ready = awake.copy(ready = true)
    }
}
