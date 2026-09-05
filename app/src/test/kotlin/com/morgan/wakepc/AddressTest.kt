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
    fun `a token is taken as typed`() {
        assertEquals("test-token", cleanToken("  test-token "))
        assertEquals("alpha-bravo-charlie", cleanToken("alpha-bravo-charlie"))
        assertEquals("6548", cleanToken("6548"))
        assertEquals("", cleanToken(""))
    }

    /** Typing a word passphrase with spaces is the natural thing to do. */
    @Test
    fun `spaced words become one hyphenated passphrase`() {
        assertEquals("lemon-jade-panda-cricket", cleanToken("lemon jade panda cricket"))
        assertEquals("lemon-jade-panda-cricket", cleanToken("  lemon  jade panda   cricket "))
        assertEquals("lemon-jade-panda-cricket", cleanToken("lemon-jade-panda-cricket"))
    }

    @Test
    fun `a pasted label is dropped, not glued on`() {
        assertEquals("6548", cleanToken("Token: 6548"))
        assertEquals("lemon-jade", cleanToken("Token: lemon jade"))
    }

    /** Anything that is not plain words keeps the old last-chunk rescue. */
    @Test
    fun `mixed junk still falls back to the last chunk`() {
        assertEquals("abc123XYZ", cleanToken("Bearer abc123XYZ"))
        assertEquals("hex0f9", cleanToken("some/label hex0f9"))
    }
}
