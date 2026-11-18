package com.home.tvremote

import com.home.tvremote.lg.Discovery
import com.home.tvremote.lg.LgTv
import com.home.tvremote.lg.Pointer
import com.home.tvremote.lg.WakeOnLan
import com.home.tvremote.lg.TvException
import kotlinx.coroutines.runBlocking
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Simulated LG TV: answers SSAP over a websocket, like the real one. */
class LgTvTest {
    private val server = MockWebServer()
    private val pointerServer = MockWebServer()
    private val requests = CopyOnWriteArrayList<JSONObject>()
    private val pointerMsgs = LinkedBlockingQueue<String>()
    var acceptPairing = true
    var blacklistSigned = false

    private fun startTv() {
        pointerServer.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) { pointerMsgs.add(text) }
        }))
        pointerServer.start()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val m = JSONObject(text); requests += m
                val id = m.getString("id")
                fun reply(type: String, payload: JSONObject) = webSocket.send(JSONObject().put("id", id).put("type", type).put("payload", payload).toString())
                when {
                    m.getString("type") == "register" && blacklistSigned && m.getJSONObject("payload").getJSONObject("manifest").has("signed") ->
                        webSocket.send(JSONObject().put("id", id).put("type", "error").put("error", "403 Pairing rejected: blacklisted certificate detected").toString())
                    m.getString("type") == "register" -> {
                        reply("response", JSONObject().put("pairingType", "PROMPT"))
                        if (acceptPairing) reply("registered", JSONObject().put("client-key", "KEY123"))
                        else webSocket.send(JSONObject().put("id", id).put("type", "error").put("error", "403 User denied access").toString())
                    }
                    m.optString("uri").endsWith("getPointerInputSocket") ->
                        reply("response", JSONObject().put("returnValue", true).put("socketPath", pointerServer.url("/").toString().replace("http", "ws")))
                    m.optString("uri") == "ssap://audio/getVolume" -> reply("response", JSONObject().put("returnValue", true).put("volume", 12))
                    m.optString("uri") == "ssap://bad" -> reply("response", JSONObject().put("returnValue", false).put("errorText", "nope"))
                    else -> reply("response", JSONObject().put("returnValue", true))
                }
            }
        }))
        server.start()
    }

    private fun url() = server.url("/").toString().replace("http", "ws")

    @After fun stop() { server.shutdown(); pointerServer.shutdown() }

    @Test fun pairsSavesKeyAndSendsCommands() = runBlocking {
        startTv()
        var saved: String? = null
        var prompted = false
        val tv = LgTv("127.0.0.1", null, onClientKey = { saved = it }, urls = listOf(url()))
        tv.connect(onPrompt = { prompted = true })
        assertTrue(prompted)
        assertEquals("KEY123", saved)
        val reg = requests.first()
        assertEquals("PROMPT", reg.getJSONObject("payload").getString("pairingType"))
        assertTrue(reg.getJSONObject("payload").getJSONObject("manifest").getJSONArray("permissions").toString().contains("CONTROL_AUDIO"))

        assertEquals(12, tv.request("ssap://audio/getVolume").getInt("volume"))
        try { tv.request("ssap://bad"); fail() } catch (e: TvException) { assertEquals("nope", e.message) }

        tv.button("HOME")
        tv.move(10.6f, -3f)
        tv.click()
        assertEquals("type:button\nname:HOME\n\n", pointerMsgs.poll(2, TimeUnit.SECONDS))
        assertEquals("type:move\ndx:10\ndy:-3\ndown:0\n\n", pointerMsgs.poll(2, TimeUnit.SECONDS))
        assertEquals("type:click\n\n", pointerMsgs.poll(2, TimeUnit.SECONDS))
        tv.close()
    }

    @Test fun fallsBackToUnsignedManifestWhenSignatureIsBlacklisted() = runBlocking {
        blacklistSigned = true
        startTv()
        var saved: String? = null
        val tv = LgTv("127.0.0.1", null, onClientKey = { saved = it }, urls = listOf(url()))
        tv.connect()
        assertEquals("KEY123", saved)
        val regs = requests.filter { it.getString("type") == "register" }
        assertEquals(2, regs.size)
        val unsigned = regs[1].getJSONObject("payload").getJSONObject("manifest")
        assertTrue(!unsigned.has("signed"))
        assertEquals("1.0", unsigned.getString("appVersion"))
        assertTrue(unsigned.getJSONArray("permissions").toString().contains("CONTROL_MOUSE_AND_KEYBOARD"))
        assertEquals(12, tv.request("ssap://audio/getVolume").getInt("volume"))
        tv.close()
    }

    @Test fun reusesSavedKey() = runBlocking {
        startTv()
        val tv = LgTv("127.0.0.1", "OLDKEY", onClientKey = {}, urls = listOf(url()))
        tv.connect()
        assertEquals("OLDKEY", requests.first().getJSONObject("payload").getString("client-key"))
        tv.close()
    }

    @Test fun declinedPairingIsReported() = runBlocking {
        acceptPairing = false
        startTv()
        val tv = LgTv("127.0.0.1", null, onClientKey = {}, urls = listOf(url()))
        try { tv.connect(); fail("should throw") } catch (e: TvException) { assertTrue(e.message!!.contains("declined")) }
    }

    @Test fun unreachableTvGivesFriendlyError() = runBlocking {
        val tv = LgTv("127.0.0.1", null, onClientKey = {}, urls = listOf("ws://127.0.0.1:1"))
        try { tv.connect(); fail() } catch (e: TvException) { assertTrue(e.message!!.startsWith("Can't reach the TV")) }
    }

    @Test fun subnetScanCoversTheRealNetwork() {
        val c24 = Discovery.hostsToScan("192.168.1.37", 24)
        assertEquals(253, c24.size)
        assertEquals("192.168.1.1", c24.first()); assertEquals("192.168.1.254", c24.last())
        assertTrue("192.168.1.37" !in c24)
        val c22 = Discovery.hostsToScan("192.168.5.20", 22) // eero-style /22
        assertEquals(1021, c22.size)
        assertEquals("192.168.4.1", c22.first()); assertEquals("192.168.7.254", c22.last())
        assertEquals(1021, Discovery.hostsToScan("10.0.3.9", 16).size) // capped at /22
    }

    @Test fun helpers() {
        val pkt = WakeOnLan.packet("A8:23:FE:01:02:03")
        assertEquals(102, pkt.size)
        assertEquals(0xFF.toByte(), pkt[5]); assertEquals(0xA8.toByte(), pkt[6]); assertEquals(0x03.toByte(), pkt[101])
        assertTrue(Discovery.looksLikeLg("SERVER: WebOS/4.1.0 UPnP/1.0\r\nST: urn:lge-com:service:webos-second-screen:1"))
        assertTrue(!Discovery.looksLikeLg("SERVER: Linux UPnP/1.0 Sonos/70.3"))
        assertEquals("http://1.2.3.4:1234/", Discovery.header("HTTP/1.1 200 OK\r\nLocation: http://1.2.3.4:1234/\r\n", "location"))
        assertEquals("[LG] webOS TV OLED55C1", Discovery.tag("<root><device><friendlyName>[LG] webOS TV OLED55C1</friendlyName></device></root>", "friendlyName"))
        assertEquals("type:scroll\ndx:0\ndy:5\n\n", Pointer.scroll(5.9f))
    }
}
