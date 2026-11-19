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

    /** What the last search did, shown to the user when nothing is found. */
    @Volatile var lastReport: String = ""

    suspend fun find(context: Context, timeoutMs: Int = 3500): List<FoundTv> {
        val wifi = wifiNetwork(context)
        if (wifi == null) { lastReport = "This phone isn't connected to Wi-Fi."; return emptyList() }
        val viaSsdp = runCatching { ssdp(context, wifi, timeoutMs) }.getOrDefault(emptyList())
        if (viaSsdp.isNotEmpty()) return viaSsdp
        return scanSubnet(context, wifi)
    }

    /** The TV's own name (as set on the TV), asked directly; null if it doesn't answer. */
    suspend fun lookupName(context: Context, ip: String): String? = withContext(Dispatchers.IO) {
        val net = wifiNetwork(context) ?: return@withContext null
        val loc = unicastSearch(net, ip)?.takeIf { it.isNotEmpty() } ?: return@withContext null
        describe(ip, loc).name.takeIf { it != "LG TV" }?.let { prettyName(it) }
    }

    /** "[LG] webOS TV OLED65B3PUA" → "LG OLED65B3PUA"; a name the owner set is kept as is. */
    fun prettyName(friendly: String): String {
        val m = Regex("^\\[LG\\]\\s*webOS TV\\s*(.*)$", RegexOption.IGNORE_CASE).find(friendly.trim())
        return if (m != null) ("LG " + m.groupValues[1]).trim() else friendly.trim()
    }

    /** Addresses to probe around [ip]: the real subnet, capped at a /22 (1022 hosts). */
    fun hostsToScan(ip: String, prefixLength: Int): List<String> {
        val parts = ip.split('.').map { it.toInt() }
        val addr = (parts[0] shl 24) or (parts[1] shl 16) or (parts[2] shl 8) or parts[3]
        val prefix = prefixLength.coerceIn(22, 30)
        val mask = (-1 shl (32 - prefix))
        val network = addr and mask
        val size = 1 shl (32 - prefix)
        return (1 until size - 1).map { network + it }.filter { it != addr }.map {
            "${(it ushr 24) and 255}.${(it ushr 16) and 255}.${(it ushr 8) and 255}.${it and 255}"
        }
    }

    /** This phone's IPv4 address on Wi-Fi (for the TV to load the timer page from). */
    fun phoneWifiIp(context: Context): String? {
        val net = wifiNetwork(context) ?: return null
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.getLinkProperties(net)?.linkAddresses?.map { it.address }
            ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
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
        val link = cm.getLinkProperties(n)?.linkAddresses
            ?.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }
        if (link == null) { lastReport = "Couldn't read this phone's Wi-Fi address."; return@withContext emptyList() }
        val myIp = link.address.hostAddress!!
        val hosts = hostsToScan(myIp, link.prefixLength)
        lastReport = "This phone is $myIp. Checked ${hosts.size} addresses on its Wi-Fi network " +
            "(${hosts.first()} – ${hosts.last()}) and none answered like an LG TV."
        val gate = Semaphore(64)
        val hits = hosts.map { ip ->
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

/**
 * Wake-on-LAN magic packet: turns on an LG TV that has "Turn on via Wi-Fi" enabled.
 * Sent over the Wi-Fi network (not mobile data) to the global broadcast, the subnet's
 * broadcast address and the TV's own address, on ports 9 and 7 — routers differ in
 * which of these they pass to a sleeping TV.
 */
object WakeOnLan {
    /** Quick check: is the TV's control port answering? */
    suspend fun reachable(ip: String): Boolean = withContext(Dispatchers.IO) {
        listOf(3001, 3000).any { port ->
            try { java.net.Socket().use { it.connect(InetSocketAddress(ip, port), 800); true } } catch (_: Exception) { false }
        }
    }

    /** Sends the packets; returns how many actually left the phone (0 = nothing could be sent). */
    suspend fun send(context: Context, macs: List<String>, tvIp: String?): Int = withContext(Dispatchers.IO) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        val net = cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        val link = net?.let { cm.getLinkProperties(it) }?.linkAddresses?.firstOrNull { it.address is Inet4Address }
        val targets = listOfNotNull("255.255.255.255", link?.let { subnetBroadcast(it.address.hostAddress!!, it.prefixLength) }, tvIp).distinct()
        var ok = 0
        var last: Exception? = null
        DatagramSocket().use { s ->
            net?.bindSocket(s)
            s.broadcast = true
            repeat(3) {
                for (mac in macs) {
                    val bytes = packet(mac)
                    for (t in targets) for (port in listOf(9, 7)) {
                        try { s.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(t), port)); ok++ }
                        catch (e: Exception) { last = e; android.util.Log.w("TvRemote", "WOL to $t:$port failed: ${e.message}") }
                    }
                }
                Thread.sleep(100)
            }
        }
        if (ok == 0) throw last ?: IllegalStateException("no packets sent")
        ok
    }

    fun subnetBroadcast(ip: String, prefixLength: Int): String {
        val p = ip.split('.').map { it.toInt() }
        val addr = (p[0] shl 24) or (p[1] shl 16) or (p[2] shl 8) or p[3]
        val bc = addr or (-1 ushr prefixLength.coerceIn(0, 32)).let { if (prefixLength >= 32) 0 else it }
        return "${(bc ushr 24) and 255}.${(bc ushr 16) and 255}.${(bc ushr 8) and 255}.${bc and 255}"
    }

    fun packet(mac: String): ByteArray {
        val hex = mac.filter { it.isLetterOrDigit() }
        require(hex.length == 12) { "Invalid MAC address" }
        val m = ByteArray(6) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        return ByteArray(6) { 0xFF.toByte() } + ByteArray(16 * 6) { m[it % 6] }
    }
}
