package com.home.tvremote.lg

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URL

data class FoundTv(val ip: String, val name: String, val model: String)

/**
 * Finds LG webOS TVs. First asks via SSDP (multicast); many routers and mesh systems drop
 * multicast, so if nothing answers it falls back to probing every address on the Wi-Fi
 * subnet for the TV's control port (3001/3000).
 */
object Discovery {
    private val TARGETS = listOf(
        "urn:lge-com:service:webos-second-screen:1",
        "urn:dial-multiscreen-org:service:dial:1",
        "ssdp:all",
    )

    suspend fun find(context: Context, timeoutMs: Int = 3500): List<FoundTv> {
        val wifi = wifiNetwork(context)
        val viaSsdp = runCatching { ssdp(context, wifi, timeoutMs) }.getOrDefault(emptyList())
        if (viaSsdp.isNotEmpty()) return viaSsdp
        return scanSubnet(context, wifi)
    }

    private fun wifiNetwork(context: Context): Network? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        return cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
    }

    private suspend fun ssdp(context: Context, net: Network?, timeoutMs: Int): List<FoundTv> = withContext(Dispatchers.IO) {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        // Android drops multicast traffic unless we hold this lock.
        val lock = wifi.createMulticastLock("tv-discovery").apply { setReferenceCounted(false); acquire() }
        val found = linkedMapOf<String, String?>() // ip -> description URL
        try {
            DatagramSocket().use { sock ->
                net?.bindSocket(sock) // make sure the search goes out over Wi-Fi, not mobile data
                sock.soTimeout = 250
                val group = InetAddress.getByName("239.255.255.250")
                val msgs = TARGETS.map { st ->
                    ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\n" +
                        "MX: 2\r\nST: $st\r\n\r\n").toByteArray()
                }
                val buf = ByteArray(4096)
                val end = System.currentTimeMillis() + timeoutMs
                var nextSend = 0L
                var sends = 0
                while (System.currentTimeMillis() < end) {
                    if (sends < 3 && System.currentTimeMillis() >= nextSend) {
                        msgs.forEach { sock.send(DatagramPacket(it, it.size, group, 1900)) }
                        sends++; nextSend = System.currentTimeMillis() + 700
                    }
                    val p = DatagramPacket(buf, buf.size)
                    try { sock.receive(p) } catch (_: SocketTimeoutException) { continue }
                    val text = String(p.data, 0, p.length)
                    if (!looksLikeLg(text)) continue
                    val ip = p.address.hostAddress ?: continue
                    if (ip !in found) found[ip] = header(text, "location")
                }
            }
        } finally { lock.release() }
        found.map { (ip, loc) -> describe(ip, loc) }
    }

    internal fun looksLikeLg(ssdpResponse: String): Boolean {
        val t = ssdpResponse.lowercase()
        return "webos" in t || "lge" in t || "lg electronics" in t
    }

    private fun describe(ip: String, location: String?): FoundTv {
        val xml = location?.takeIf { it.isNotEmpty() }?.let { fetch(it) } ?: ""
        return FoundTv(ip, tag(xml, "friendlyName").ifEmpty { "LG TV" }, tag(xml, "modelName"))
    }

    /** Probe every host on the /24 for an open LG control port. ~2-3 s for 254 hosts. */
    private suspend fun scanSubnet(context: Context, net: Network?): List<FoundTv> = withContext(Dispatchers.IO) {
        val n = net ?: return@withContext emptyList()
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val me = cm.getLinkProperties(n)?.linkAddresses
            ?.map { it.address }?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?: return@withContext emptyList()
        val prefix = me.hostAddress!!.substringBeforeLast('.')
        val gate = Semaphore(48)
        val hits = (1..254).map { "$prefix.$it" }.filter { it != me.hostAddress }.map { ip ->
            async { gate.withPermit { if (portOpen(n, ip, 3001) || portOpen(n, ip, 3000)) ip else null } }
        }.awaitAll().filterNotNull()
        // Port 3000 is common on other devices too; keep hosts that confirm they're LG over unicast SSDP,
        // or that have the webOS secure port.
        hits.mapNotNull { ip ->
            val loc = unicastSearch(n, ip)
            when {
                loc != null -> describe(ip, loc)
                portOpen(n, ip, 3001) -> FoundTv(ip, "LG TV", "")
                else -> null
            }
        }
    }

    /** Ask one host directly (unicast SSDP usually works even where multicast is blocked). */
    private fun unicastSearch(net: Network, ip: String): String? = try {
        DatagramSocket().use { sock ->
            net.bindSocket(sock)
            sock.soTimeout = 800
            val msg = ("M-SEARCH * HTTP/1.1\r\nHOST: $ip:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 1\r\n" +
                "ST: ${TARGETS[0]}\r\n\r\n").toByteArray()
            sock.send(DatagramPacket(msg, msg.size, InetAddress.getByName(ip), 1900))
            val buf = ByteArray(4096)
            val p = DatagramPacket(buf, buf.size)
            sock.receive(p)
            val text = String(p.data, 0, p.length)
            if (looksLikeLg(text)) header(text, "location") ?: "" else null
        }
    } catch (_: Exception) { null }

    private fun portOpen(net: Network, ip: String, port: Int): Boolean = try {
        net.socketFactory.createSocket().use { s -> s.connect(InetSocketAddress(ip, port), 400); true }
    } catch (_: Exception) { false }

    internal fun header(msg: String, name: String): String? = msg.lineSequence()
        .firstOrNull { it.startsWith("$name:", ignoreCase = true) }?.substringAfter(':')?.trim()

    internal fun tag(xml: String, name: String): String =
        Regex("<(?:\\w+:)?$name>([^<]*)</").find(xml)?.groupValues?.get(1)?.trim().orEmpty()

    private fun fetch(url: String): String? = try {
        (URL(url).openConnection() as HttpURLConnection).run {
            connectTimeout = 1500; readTimeout = 1500
            inputStream.bufferedReader().use { it.readText() }
        }
    } catch (_: Exception) { null }
}

/** Wake-on-LAN magic packet: turns on an LG TV that has "Turn on via Wi-Fi" enabled. */
object WakeOnLan {
    suspend fun send(mac: String) = withContext(Dispatchers.IO) {
        val bytes = packet(mac)
        DatagramSocket().use { s ->
            s.broadcast = true
            val addr = InetAddress.getByName("255.255.255.255")
            repeat(3) { s.send(DatagramPacket(bytes, bytes.size, addr, 9)) }
        }
    }

    fun packet(mac: String): ByteArray {
        val hex = mac.filter { it.isLetterOrDigit() }
        require(hex.length == 12) { "Invalid MAC address" }
        val m = ByteArray(6) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        return ByteArray(6) { 0xFF.toByte() } + ByteArray(16 * 6) { m[it % 6] }
    }
}
