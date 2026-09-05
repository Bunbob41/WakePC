package com.morgan.wakepc

import android.content.Context
import android.net.ConnectivityManager
import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Resolves bare hostnames the way a desktop does.
 *
 * Tailscale publishes its MagicDNS suffix as a search domain on the VPN
 * network, but Android never applies search domains to an app's own lookups —
 * so "mypi" resolves from a laptop and fails from the phone, while
 * "mypi.tailnet.ts.net" works on both. This reads the suffix off the live
 * network and appends it when a dotless name fails, so the short name people
 * actually know keeps working.
 */
class SearchDomainDns(
    private val searchDomains: () -> List<String>,
    private val delegate: Dns = Dns.SYSTEM,
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val direct = runCatching { delegate.lookup(hostname) }
        direct.getOrNull()?.let { return it }

        // Only bare names can gain anything from a search domain.
        if (hostname.contains('.')) {
            throw direct.exceptionOrNull().asUnknownHost(hostname)
        }

        searchDomains().forEach { domain ->
            runCatching { delegate.lookup("$hostname.$domain") }
                .getOrNull()
                ?.let { return it }
        }
        throw direct.exceptionOrNull().asUnknownHost(hostname)
    }

    private fun Throwable?.asUnknownHost(hostname: String): UnknownHostException =
        this as? UnknownHostException ?: UnknownHostException(hostname)
}

/** The search domains the active network advertises, VPN included. */
fun networkSearchDomains(context: Context): List<String> =
    runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = manager?.activeNetwork ?: return emptyList()
        manager
            .getLinkProperties(network)
            ?.domains
            ?.split(',')
            ?.map { it.trim().trim('.') }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
    }.getOrDefault(emptyList())
