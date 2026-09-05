package com.morgan.wakepc

import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

class SearchDomainDnsTest {
    private val address = InetAddress.getByAddress("host", byteArrayOf(100, 84, 117, 69))

    /** Resolves only the names given to it; everything else is unknown. */
    private fun fakeDns(vararg known: String) =
        Dns { hostname ->
            if (hostname in known) listOf(address) else throw UnknownHostException(hostname)
        }

    @Test
    fun `a name that already resolves is left alone`() {
        val dns = SearchDomainDns({ listOf("tailnet.ts.net") }, fakeDns("mypi"))
        assertEquals(listOf(address), dns.lookup("mypi"))
    }

    /** The whole point: short name fails, the search domain rescues it. */
    @Test
    fun `a bare name falls back to the search domain`() {
        val dns = SearchDomainDns({ listOf("tailnet.ts.net") }, fakeDns("mypi.tailnet.ts.net"))
        assertEquals(listOf(address), dns.lookup("mypi"))
    }

    @Test
    fun `each search domain is tried in turn`() {
        val dns = SearchDomainDns({ listOf("wrong.example", "tailnet.ts.net") }, fakeDns("mypi.tailnet.ts.net"))
        assertEquals(listOf(address), dns.lookup("mypi"))
    }

    @Test
    fun `a dotted name is never suffixed`() {
        // "pi.example.com" must not become "pi.example.com.tailnet.ts.net".
        val dns = SearchDomainDns({ listOf("tailnet.ts.net") }, fakeDns("pi.example.com.tailnet.ts.net"))
        assertThrows(UnknownHostException::class.java) { dns.lookup("pi.example.com") }
    }

    @Test
    fun `with no search domains it behaves like the plain resolver`() {
        val dns = SearchDomainDns({ emptyList() }, fakeDns("something.else"))
        assertThrows(UnknownHostException::class.java) { dns.lookup("mypi") }
    }

    @Test
    fun `a trailing dot on the domain does not double up`() {
        val dns = SearchDomainDns({ listOf("tailnet.ts.net") }, fakeDns("mypi.tailnet.ts.net"))
        assertEquals(listOf(address), dns.lookup("mypi"))
    }
}
