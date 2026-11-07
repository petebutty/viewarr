package io.github.petebutty.viewarr.data.remote.services

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** "192.168.1" for a TV at 192.168.1.50: the first private IPv4 address on an active interface. */
fun localPrefix(): String? = runCatching {
    java.net.NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<java.net.Inet4Address>()
        .firstOrNull { it.isSiteLocalAddress }
        ?.hostAddress?.substringBeforeLast('.')
}.getOrNull()

/**
 * Addresses ("http://ip:port") in this TV's own /24 with [port] open, for which [confirm] says it's the right thing.
 * A few seconds; nothing outside the home network is contacted.
 */
suspend fun <T> lanScan(port: Int, confirm: suspend (String) -> T?): List<T> = withContext(Dispatchers.IO) {
    val prefix = localPrefix() ?: return@withContext emptyList()
    val gate = Semaphore(48)
    coroutineScope {
        (1..254).map { host ->
            async {
                gate.withPermit {
                    val ip = "$prefix.$host"
                    val open = runCatching { java.net.Socket().use { it.connect(java.net.InetSocketAddress(ip, port), 300); true } }.getOrDefault(false)
                    if (open) runCatching { confirm("http://$ip:$port") }.getOrNull() else null
                }
            }
        }.awaitAll().filterNotNull()
    }
}
