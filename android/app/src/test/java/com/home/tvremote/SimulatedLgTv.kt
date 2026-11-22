package com.home.tvremote

import android.content.Context
import com.home.tvremote.lg.LgTv
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A simulated LG webOS TV, scripted from real Diagnostics of an OLED65B3PUA (webOS 23):
 * pairing, power states incl. Quick Start+ standby, the app in front and play state, launch replies,
 * the on-screen keyboard opening, the pointer socket, and connections that hang or drop.
 *
 * What it can't simulate: what happens inside apps (Netflix, Prime…), the TV's web browser, speech recognition.
 */
class SimulatedLgTv {
    // ---- state, as the real TV reports it ----
    @Volatile var power = "Active"                 // "Active", "Active Standby" (Quick Start+), "Off" (unreachable)
    @Volatile var quickStart = true                // with Quick Start+ an off TV stays reachable
    @Volatile var foreground = "com.webos.app.home"
    @Volatile var playState: String? = null        // "playing" / "paused" for the app in front
    /** A TV in standby accepts the connection but never answers pairing (seen with Quick Start+). */
    @Volatile var hangPairingWhenOff = true
    @Volatile var wakeDelayMs = 300L
    /** False: the TV ignores Wake-on-LAN ("Turn on via Wi-Fi" off, or the router drops it). */
    @Volatile var wakeWorks = true
    /** True: the TV takes requests but never answers (its network stuck after a bad suspend, 22 Nov). */
    @Volatile var stuck = false

    // ---- what the app sent ----
    val requests = CopyOnWriteArrayList<JSONObject>()
    val pointer = CopyOnWriteArrayList<String>()
    fun uris() = requests.map { it.optString("uri").removePrefix("ssap://") }
    fun sent(uri: String) = requests.filter { it.optString("uri").endsWith(uri) }

    private val server = MockWebServer()
    private val pointerServer = MockWebServer()
    private val sockets = CopyOnWriteArrayList<WebSocket>()
    private val subs = ConcurrentHashMap<String, MutableList<Pair<WebSocket, String>>>()   // uri → (socket, id)

    fun start(): SimulatedLgTv {
        pointerServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val line = text.lines().filter { it.startsWith("type:") || it.startsWith("name:") }.joinToString(" ")
                    pointer += line
                    if (line == "type:button name:HOME") { foreground = "com.webos.app.home"; playState = null; pushForeground() }
                }
            })
        }
        pointerServer.start()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().withWebSocketUpgrade(listener)
        }
        server.start()
        return this
    }

    fun stop() { runCatching { server.shutdown() }; runCatching { pointerServer.shutdown() } }
    val url get() = server.url("/").toString().replace("http", "ws")

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) { sockets += webSocket }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { sockets -= webSocket }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { sockets -= webSocket }
        override fun onMessage(webSocket: WebSocket, text: String) = handle(webSocket, JSONObject(text))
    }

    private fun reply(ws: WebSocket, id: String, payload: JSONObject, type: String = "response") =
        ws.send(JSONObject().put("id", id).put("type", type).put("payload", payload).toString())

    private fun handle(ws: WebSocket, m: JSONObject) {
        requests += m
        val id = m.optString("id"); val uri = m.optString("uri"); val type = m.optString("type")
        val on = power == "Active"
        if (type == "register") {
            if (!on && hangPairingWhenOff) return            // standby: silence
            reply(ws, id, JSONObject().put("client-key", "SIMKEY"), "registered"); return
        }
        if (stuck) return
        if (type == "subscribe") subs.getOrPut(uri) { CopyOnWriteArrayList() } += ws to id
        when {
            uri.endsWith("tvpower/power/getPowerState") -> reply(ws, id, powerPayload())
            uri.endsWith("applicationManager/getForegroundAppInfo") -> reply(ws, id, JSONObject().put("returnValue", true).put("appId", foreground))
            uri.endsWith("com.webos.media/getForegroundAppInfo") -> reply(ws, id, mediaPayload())
            uri.endsWith("ime/registerRemoteKeyboard") -> reply(ws, id, JSONObject().put("returnValue", true))
            uri.endsWith("listLaunchPoints") -> ws.send(JSONObject().put("id", id).put("type", "error").put("error", "401 insufficient permissions").toString())
            uri.endsWith("getPointerInputSocket") -> reply(ws, id, JSONObject().put("returnValue", true).put("socketPath", pointerServer.url("/").toString().replace("http", "ws")))
            uri.endsWith("system.launcher/launch") || uri.endsWith("applicationManager/launch") -> {
                foreground = m.optJSONObject("payload")?.optString("id") ?: foreground; playState = null
                reply(ws, id, JSONObject().put("returnValue", true).put("id", foreground)); pushForeground()
            }
            uri.endsWith("system/turnOff") -> {
                reply(ws, id, JSONObject().put("returnValue", true))
                power = if (quickStart) "Active Standby" else "Off"; pushPower()
            }
            uri.endsWith("media.controls/pause") -> { playState = "paused"; reply(ws, id, ok()); pushMedia() }
            uri.endsWith("media.controls/play") -> { playState = "playing"; reply(ws, id, ok()); pushMedia() }
            else -> reply(ws, id, ok())
        }
    }

    private fun ok() = JSONObject().put("returnValue", true)
    private fun powerPayload() = JSONObject().put("returnValue", true).put("state", power)
    private fun mediaPayload() = JSONObject().put("returnValue", true).put("foregroundAppInfo",
        JSONArray().apply { playState?.let { put(JSONObject().put("appId", foreground).put("playState", it).put("type", "media")) } })

    private fun push(uriEnd: String, payload: JSONObject) {
        for ((uri, list) in subs) if (uri.endsWith(uriEnd)) for ((ws, id) in list) runCatching { reply(ws, id, payload) }
    }
    private fun pushPower() = push("tvpower/power/getPowerState", powerPayload())
    private fun pushForeground() { push("applicationManager/getForegroundAppInfo", JSONObject().put("returnValue", true).put("appId", foreground)); pushMedia() }
    private fun pushMedia() = push("com.webos.media/getForegroundAppInfo", mediaPayload())

    // ---- things that happen on the TV side ----
    fun turnedOnByRemote() { power = "Active"; foreground = "com.webos.app.home"; pushPower() }
    fun turnedOffByRemote() { power = if (quickStart) "Active Standby" else "Off"; pushPower() }
    fun startsPlaying(app: String) { foreground = app; playState = "playing"; pushForeground() }
    /** On the Home screen, the banner video and the live-TV preview tile play by themselves (seen 22 Nov). */
    fun homeScreenVideosPlay() {
        foreground = "com.webos.app.home"; playState = null; pushForeground()
        push("com.webos.media/getForegroundAppInfo", JSONObject().put("returnValue", true).put("foregroundAppInfo", JSONArray()
            .put(JSONObject().put("appId", "com.webos.app.livedmost").put("playState", "playing").put("type", "media"))
            .put(JSONObject().put("appId", "com.webos.app.home").put("playState", "playing").put("type", "media"))))
    }
    fun keyboardOpens() = push("ime/registerRemoteKeyboard", JSONObject().put("currentWidget", JSONObject().put("focus", true)))
    fun keyboardCloses() = push("ime/registerRemoteKeyboard", JSONObject().put("currentWidget", JSONObject().put("focus", false)))
    /** The TV drops every connection (Wi-Fi blip, phone sleeping…). */
    fun dropsConnections() { for (s in sockets) runCatching { s.close(1001, "going away") }; sockets.clear(); subs.clear() }

    /** Network access as the app sees it, pointed at this simulated TV. */
    /** The phone's Wi-Fi (address, prefix); null = the phone isn't on Wi-Fi. */
    @Volatile var phoneWifi: Pair<String, Int>? = "192.168.1.20" to 24

    val net = object : TvController.Net {
        override fun phoneWifi(context: Context): Pair<String, Int>? = this@SimulatedLgTv.phoneWifi
        override suspend fun reachable(ip: String, timeoutMs: Int) = power == "Active" || (power == "Active Standby" && quickStart)
        override suspend fun wake(context: Context, macs: List<String>, ip: String?): Int {
            if (power != "Active" && wakeWorks) Thread {
                Thread.sleep(wakeDelayMs)
                if (power != "Active") { if (power == "Off") power = "Active Standby"; turnedOnByRemote() }
            }.start()
            return 4
        }
        override fun driver(brand: String, ip: String, clientKey: String?, onClientKey: (String) -> Unit, onDisconnect: () -> Unit) =
            com.home.tvremote.tv.LgDriver(LgTv(ip, clientKey, onClientKey = onClientKey, onDisconnect = onDisconnect, urls = listOf(url), resubscribeDelayMs = 100))
        override suspend fun lookupName(context: Context, ip: String): String? = "LG OLED65B3PUA (simulated)"
    }
}
