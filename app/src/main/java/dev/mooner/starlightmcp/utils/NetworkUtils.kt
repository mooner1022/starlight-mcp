package dev.mooner.starlightmcp.utils

import java.net.Inet4Address
import java.net.NetworkInterface

/** IPv4 addresses of interfaces that are up, Wi-Fi first, excluding loopback. */
fun localIpv4Addresses(): List<String> =
    runCatching {
        NetworkInterface.getNetworkInterfaces()
            .toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
            .flatMap { iface -> iface.inetAddresses.toList().filterIsInstance<Inet4Address>() }
            .mapNotNull { it.hostAddress }
            .distinct()
    }.getOrDefault(emptyList())

/** Base URLs a server bound to [host]:[port] can be reached at. */
fun serverUrls(host: String, port: Int): List<String> {
    val hosts = if (host == ANY_HOST)
        localIpv4Addresses().ifEmpty { listOf(LOCALHOST) }
    else
        listOf(host)
    return hosts.map { "http://$it:$port" }
}

const val ANY_HOST = "0.0.0.0"
const val LOCALHOST = "127.0.0.1"
