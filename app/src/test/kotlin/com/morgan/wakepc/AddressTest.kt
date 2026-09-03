package com.morgan.wakepc

import org.junit.Assert.assertEquals
import org.junit.Test

class AddressTest {
    @Test
    fun `bare host gets scheme and default port`() {
        assertEquals("http://homepi:8787", cleanUrl("homepi"))
        assertEquals("http://100.64.0.2:8787", cleanUrl("100.64.0.2"))
    }

    @Test
    fun `explicit port is kept`() {
        assertEquals("http://homepi:9000", cleanUrl("homepi:9000"))
    }

    @Test
    fun `full urls pass through, minus a trailing slash`() {
        assertEquals("https://homepi:8787", cleanUrl("https://homepi:8787"))
        assertEquals("http://homepi:8787", cleanUrl("http://homepi:8787/"))
    }

    @Test
    fun `empty stays empty rather than becoming a bare scheme`() {
        assertEquals("", cleanUrl(""))
        assertEquals("", cleanUrl("   "))
    }

    /** The 0.1.1 bug: a chat paste dragged a newline and a label into the field. */
    @Test
    fun `pasted junk after the address is dropped`() {
        assertEquals("http://100.64.0.2:8787", cleanUrl("100.64.0.2:8787\nToken:"))
    }

    @Test
    fun `token keeps the last whitespace-delimited chunk`() {
        assertEquals("test-token", cleanToken("  test-token "))
        assertEquals("test-token", cleanToken("Token: test-token"))
        assertEquals("alpha-bravo-charlie", cleanToken("alpha-bravo-charlie"))
        assertEquals("", cleanToken(""))
    }
}
