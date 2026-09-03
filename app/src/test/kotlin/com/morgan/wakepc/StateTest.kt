package com.morgan.wakepc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StateTest {

    private val connection = Connection(
        id = "c1", name = "my pi", color = 0xFF45D06D,
        baseUrl = "http://homepi:8787", fallbackUrl = "http://100.64.0.2:8787",
        token = "test-token",
    )
    private val machine = Machine(
        id = "m1", name = "desk pc", connectionId = "c1",
        commands = listOf(CommandRef("wake-pc", ping = true), CommandRef("suspend-pc", ping = false)),
    )
    private val state = AppState(
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
}
