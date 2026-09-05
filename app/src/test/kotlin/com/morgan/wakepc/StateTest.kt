package com.morgan.wakepc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StateTest {
    private val connection =
        Connection(
            id = "c1",
            name = "my pi",
            color = 0xFF45D06D,
            baseUrl = "http://homepi:8787",
            fallbackUrl = "http://100.64.0.2:8787",
            token = "test-token",
        )
    private val machine =
        Machine(
            id = "m1",
            name = "desk pc",
            connectionId = "c1",
            commands = listOf(CommandRef("wake-pc", ping = true), CommandRef("suspend-pc", ping = false)),
        )
    private val state =
        AppState(
            connections = listOf(connection),
            machines = listOf(machine),
            hero = ButtonRef("m1", "wake-pc"),
            heroStyle = HeroStyle.MINI,
            tile = ButtonRef("m1", "suspend-pc"),
        )

    @Test
    fun `state survives an encode-decode round trip`() {
        assertEquals(state, decodeState(encodeState(state)))
    }

    @Test
    fun `empty state round trips`() {
        assertEquals(AppState(), decodeState(encodeState(AppState())))
    }

    @Test
    fun `garbage decodes to empty state instead of crashing`() {
        assertEquals(AppState(), decodeState("not json at all"))
        assertEquals(AppState(), decodeState(""))
    }

    @Test
    fun `an unknown hero style falls back to banner`() {
        val json = encodeState(state).replace("\"MINI\"", "\"HOLOGRAM\"")
        assertEquals(HeroStyle.BANNER, decodeState(json).heroStyle)
    }

    @Test
    fun `resolve walks a ref to its machine, connection and command`() {
        val resolved = state.resolve(state.hero)
        assertNotNull(resolved)
        assertEquals("desk pc", resolved!!.machine.name)
        assertEquals("my pi", resolved.connection.name)
        assertTrue(resolved.command.ping)
    }

    @Test
    fun `refs to deleted things resolve to null rather than a stale button`() {
        assertNull(state.resolve(ButtonRef("gone", "wake-pc")))
        assertNull(state.resolve(ButtonRef("m1", "reboot-pi")))
        assertNull(state.resolve(null))
        // Machine kept, its connection deleted.
        assertNull(state.copy(connections = emptyList()).resolve(state.hero))
    }

    @Test
    fun `a command can name its own connection, so one machine spans two relays`() {
        // Wake runs through the pi; shutdown runs on the machine itself.
        val pc = Connection(id = "c2", name = "desk pc", baseUrl = "http://pc:8787", token = "t")
        val mixed =
            machine.copy(
                commands =
                    listOf(
                        CommandRef("wake-pc", ping = true),
                        CommandRef("shutdown-pc", ping = false, connectionId = "c2"),
                    ),
            )
        val s = state.copy(connections = listOf(connection, pc), machines = listOf(mixed))

        assertEquals("my pi", s.resolve(ButtonRef("m1", "wake-pc"))!!.connection.name)
        assertEquals("desk pc", s.resolve(ButtonRef("m1", "shutdown-pc"))!!.connection.name)
    }

    @Test
    fun `a command whose connection is gone resolves to nothing`() {
        val orphan = machine.copy(commands = listOf(CommandRef("x", ping = false, connectionId = "deleted")))
        val s = state.copy(machines = listOf(orphan))
        assertNull(s.resolve(ButtonRef("m1", "x")))
    }

    @Test
    fun `per-command connections survive the round trip`() {
        val mixed =
            machine.copy(commands = listOf(CommandRef("shutdown-pc", ping = false, connectionId = "c2")))
        val s = state.copy(machines = listOf(mixed))
        assertEquals(s, decodeState(encodeState(s)))
    }
}
