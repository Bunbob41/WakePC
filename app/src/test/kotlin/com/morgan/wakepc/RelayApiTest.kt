package com.morgan.wakepc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** What the relay sends, and how each way of failing to reach it is sorted. */
class RelayApiTest {
    @Test
    fun `health is read, warnings and all`() {
        val health =
            parseHealth(
                """{"ok": true, "api": 2, "name": "garage-pc", "platform": "win32", "you": "pixel", "machines": 1,
                   "commands": 0, "phone_setup": true, "warnings": ["this relay's Tailscale key expires in 5 days"]}""",
            )
        assertEquals(2, health.api)
        assertEquals("garage-pc", health.name)
        assertEquals("pixel", health.you)
        assertTrue(health.phoneSetup)
        assertEquals(1, health.warnings.size)
    }

    @Test
    fun `a relay that could not name the caller says null, not the word`() {
        assertNull(parseHealth("""{"api": 2, "name": "pi", "you": null}""").you)
    }

    @Test
    fun `a scan lists devices and marks the ones already added`() {
        val devices =
            parseScan(
                """{"networks": ["192.168.1.0/24"], "devices": [
                   {"ip": "192.168.1.2", "mac": "3c:7c:3f:00:00:02", "name": null, "machine": null},
                   {"ip": "192.168.1.50", "mac": "3c:7c:3f:12:34:56", "name": "desk", "machine": "desk-pc"}]}""",
            )
        assertEquals(listOf(null, "desk"), devices.map { it.name })
        assertEquals(listOf(null, "desk-pc"), devices.map { it.machineId })
        assertEquals(emptyList<LanDevice>(), parseScan("""{"networks": []}"""))
    }

    @Test
    fun `a machine carries the name of its wake button`() {
        val machine =
            parseMachine(
                """{"id": "desk-pc", "name": "Desk PC", "mac": "3c:7c:3f:12:34:56", "ip": "192.168.1.50",
                   "ready": 3389, "broadcast_ip": "192.168.1.255"}""",
            )
        assertEquals("wake-desk-pc", machine.commandName)
        assertEquals(3389, machine.ready)
        val bare = parseMachine("""{"id": "old", "name": "Old", "mac": "3c:7c:3f:12:34:57", "ip": null, "ready": null}""")
        assertNull(bare.ip)
        assertNull(bare.ready)
    }

    @Test
    fun `not reaching the relay is told apart from the relay saying no`() {
        assertEquals(FailureKind.NO_ANSWER, transportFailure(SocketTimeoutException("connect timed out")).kind)
        assertEquals(FailureKind.NO_ANSWER, transportFailure(InterruptedIOException("timeout")).kind)
        assertEquals(FailureKind.NO_ANSWER, transportFailure(ConnectException("Network is unreachable")).kind)
        assertEquals(FailureKind.REFUSED, transportFailure(ConnectException("Connection refused")).kind)
        assertEquals(FailureKind.REFUSED, transportFailure(ConnectException("failed to connect: ECONNREFUSED (Connection refused)")).kind)
        assertEquals(FailureKind.BAD_NAME, transportFailure(UnknownHostException("pi-nd")).kind)
        assertEquals(FailureKind.OTHER, transportFailure(IllegalStateException("odd")).kind)
    }

    @Test
    fun `the relay's own reason for refusing is kept`() {
        val rejected = httpFailure(400, """{"error": "that machine is already here as Desk PC"}""")
        assertEquals(FailureKind.REJECTED, rejected.kind)
        assertEquals("that machine is already here as Desk PC", rejected.message)

        assertEquals(FailureKind.NOT_RECOGNISED, httpFailure(401, """{"error": "unauthorized"}""").kind)
        assertEquals(FailureKind.WRONG_CODE, httpFailure(403, """{"error": "confirmation code required"}""").kind)
        assertEquals(FailureKind.REJECTED, httpFailure(403, """{"error": "phone setup is turned off on this relay"}""").kind)
        assertEquals(FailureKind.NOT_FOUND, httpFailure(404, """{"error": "unknown command"}""").kind)
        assertEquals("HTTP 502", httpFailure(502, "<html>bad gateway</html>").message)
    }

    @Test
    fun `only failures to reach the relay count as the relay being down`() {
        val down = FailureKind.entries.filter { it.relayDown }.toSet()
        assertEquals(setOf(FailureKind.NO_ADDRESS, FailureKind.BAD_NAME, FailureKind.NO_ANSWER, FailureKind.REFUSED), down)
        assertFalse(FailureKind.WRONG_CODE.relayDown)
    }

    @Test
    fun `every failure has a short label for a status chip`() {
        assertEquals("NO RELAY", FailureKind.NO_ANSWER.chip())
        assertEquals("NO RELAY", FailureKind.REFUSED.chip())
        assertEquals("NOT RECOGNISED", FailureKind.NOT_RECOGNISED.chip())
        FailureKind.entries.forEach { assertTrue(it.chip().isNotBlank()) }
    }
}
