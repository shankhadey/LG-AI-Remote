package com.home.tvremote.lg

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL

data class FoundTv(val ip: String, val name: String, val model: String)

/** Finds LG webOS TVs with an SSDP search (the TV answers as "webos-second-screen"). */
object Discovery {
    private const val ST = "urn:lge-com:service:webos-second-screen:1"

    suspend fun find(context: Context, timeoutMs: Int = 3500): List<FoundTv> = withContext(Dispatchers.IO) {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        // Android drops multicast replies unless we hold this lock.
        val lock = wifi.createMulticastLock("tv-discovery").apply { setReferenceCounted(false); acquire() }
        val found = linkedMapOf<String, String?>() // ip -> description URL
        try {
            DatagramSocket().use { sock ->
                sock.soTimeout = 300
                val msg = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\n" +
                    "MX: 2\r\nST: $ST\r\n\r\n").toByteArray()
                val group = InetAddress.getByName("239.255.255.250")
                repeat(3) { sock.send(DatagramPacket(msg, msg.size, group, 1900)) }
                val buf = ByteArray(2048)
                val end = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < end) {
                    val p = DatagramPacket(buf, buf.size)
                    try { sock.receive(p) } catch (_: SocketTimeoutException) { continue }
                    val text = String(p.data, 0, p.length)
                    if (!text.contains("webos", ignoreCase = true) && !text.contains("lge", ignoreCase = true)) continue
                    val ip = p.address.hostAddress ?: continue
                    if (ip !in found) found[ip] = header(text, "location")
                }
            }
        } finally { lock.release() }
        found.map { (ip, loc) ->
            val xml = loc?.let { fetch(it) } ?: ""
            FoundTv(ip, tag(xml, "friendlyName").ifEmpty { "LG TV" }, tag(xml, "modelName"))
        }
    }

    internal fun header(msg: String, name: String): String? = msg.lineSequence()
        .firstOrNull { it.startsWith("$name:", ignoreCase = true) }?.substringAfter(':')?.trim()

    internal fun tag(xml: String, name: String): String =
        Regex("<(?:\\w+:)?$name>([^<]*)</").find(xml)?.groupValues?.get(1)?.trim().orEmpty()

    private fun fetch(url: String): String? = try {
        (URL(url).openConnection() as HttpURLConnection).run {
            connectTimeout = 2000; readTimeout = 2000
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
