package com.home.tvremote

import android.content.Context
import com.home.tvremote.samsung.SamsungDriver
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A simulated Samsung Tizen TV, written from the community-documented protocol (no real Samsung TV to record from):
 * the remote-control websocket with its "Allow" pairing and token, keys, the installed-app list, deep-link launches,
 * the keyboard's ime events and text input, and the REST API's device info, power state and app visibility.
 * In standby ("network standby" on) the REST port still answers "standby" but the remote socket refuses.
 */
class SimulatedSamsungTv {
    @Volatile var power = "on"                     // "on", "standby" (network standby), "off" (unreachable)
    @Volatile var foreground: String? = null       // Tizen app ID in front, null = home / live TV
    /** Pairing: a known token connects at once; no token → the TV shows Allow/Deny, answered after [allowAfterMs]. */
    @Volatile var allowAfterMs = 300L
    @Volatile var userAllows = true
    @Volatile var wakeDelayMs = 300L
    val token = "SAMTOKEN"
    val apps = listOf("3201907018807" to "Netflix", "111299001912" to "YouTube", "3201910019365" to "Prime Video", "3201901017640" to "Disney+")

    // ---- what the app sent ----
    val messages = CopyOnWriteArrayList<JSONObject>()
    val connectUrls = CopyOnWriteArrayList<String>()
    fun keys() = messages.filter { it.optJSONObject("params")?.optString("TypeOfRemote") == "SendRemoteKey" }.map { it.getJSONObject("params").getString("DataOfCmd") }
    fun typed() = messages.filter { it.optJSONObject("params")?.optString("TypeOfRemote") == "SendInputString" }
        .map { String(java.util.Base64.getDecoder().decode(it.getJSONObject("params").getString("Cmd"))) }
    fun launches() = messages.filter { it.optJSONObject("params")?.optString("event") == "ed.apps.launch" }.map { it.getJSONObject("params").getJSONObject("data") }

    private val server = MockWebServer()
    private val sockets = CopyOnWriteArrayList<WebSocket>()

    fun start(): SimulatedSamsungTv {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                if (path.startsWith("/api/v2/channels/")) {
                    if (power != "on") return MockResponse().setResponseCode(503)
                    connectUrls += path
                    return MockResponse().withWebSocketUpgrade(listener(path))
                }
                if (power == "off") return MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START)
                return when {
                    path == "/api/v2/" -> json(JSONObject().put("device", JSONObject().put("type", "Samsung SmartTV").put("name", "[TV] Samsung Q80 Series (65)")
                        .put("modelName", "QN65Q80CAFXZA").put("wifiMac", "70:2a:d5:11:22:33").put("PowerState", power)))
                    path.startsWith("/api/v2/applications/") -> {
                        val id = path.removePrefix("/api/v2/applications/")
                        if (request.method == "POST") { if (power == "on") { foreground = id; pushNothing() }; json(JSONObject()) }
                        else json(JSONObject().put("id", id).put("running", foreground == id).put("visible", power == "on" && foreground == id))
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        return this
    }
    private fun pushNothing() {}
    private fun json(o: JSONObject) = MockResponse().setHeader("Content-Type", "application/json").setBody(o.toString())

    fun stop() { runCatching { server.shutdown() } }
    val wsUrl get() = server.url("/").toString().removeSuffix("/").replace("http", "ws")
    val restUrl get() = server.url("/").toString().removeSuffix("/")

    private fun listener(path: String) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            sockets += webSocket
            val known = "token=$token" in path
            val connect = JSONObject().put("event", "ms.channel.connect").put("data", JSONObject().put("id", "sim").put("token", token))
            if (known) webSocket.send(connect.toString())
            else Thread {   // the Allow / Deny prompt on the TV
                Thread.sleep(allowAfterMs)
                webSocket.send(if (userAllows) connect.toString() else JSONObject().put("event", "ms.channel.unauthorized").toString())
            }.start()
        }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { sockets -= webSocket }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { sockets -= webSocket }
        override fun onMessage(webSocket: WebSocket, text: String) = handle(webSocket, JSONObject(text))
    }

    private fun handle(ws: WebSocket, m: JSONObject) {
        messages += m
        val p = m.optJSONObject("params") ?: return
        when {
            p.optString("TypeOfRemote") == "SendRemoteKey" && p.optString("DataOfCmd") == "KEY_POWER" -> {
                // The TV goes to standby and drops the remote connection a moment later.
                power = "standby"; foreground = null
                Thread { Thread.sleep(100); dropsConnections() }.start()
            }
            p.optString("TypeOfRemote") == "SendRemoteKey" && p.optString("DataOfCmd") == "KEY_HOME" -> foreground = null
            p.optString("event") == "ed.installedApp.get" -> ws.send(JSONObject().put("event", "ed.installedApp.get").put("from", "host")
                .put("data", JSONObject().put("data", JSONArray().apply { apps.forEach { (id, n) -> put(JSONObject().put("appId", id).put("name", n).put("app_type", 2)) } })).toString())
            p.optString("event") == "ed.apps.launch" -> {
                foreground = p.getJSONObject("data").getString("appId")
                ws.send(JSONObject().put("event", "ed.apps.launch").put("from", "host").put("data", 200).toString())
            }
        }
    }

    // ---- things that happen on the TV side ----
    fun turnedOnByRemote() { power = "on"; foreground = null }
    fun turnedOffByRemote() { power = "standby"; foreground = null; dropsConnections() }
    fun keyboardOpens() = sockets.forEach { it.send(JSONObject().put("event", "ms.remote.imeStart").put("data", "").toString()) }
    fun keyboardCloses() = sockets.forEach { it.send(JSONObject().put("event", "ms.remote.imeEnd").put("data", "").toString()) }
    fun dropsConnections() { for (s in sockets) runCatching { s.close(1001, "going away") }; sockets.clear() }

    /** The phone's Wi-Fi (address, prefix); null = the phone isn't on Wi-Fi. */
    @Volatile var phoneWifi: Pair<String, Int>? = "192.168.1.21" to 24

    val net = object : TvController.Net {
        override fun phoneWifi(context: Context): Pair<String, Int>? = this@SimulatedSamsungTv.phoneWifi
        override suspend fun reachable(ip: String, timeoutMs: Int) = power != "off"
        override suspend fun wake(context: Context, macs: List<String>, ip: String?): Int {
            if (power != "on") Thread { Thread.sleep(wakeDelayMs); turnedOnByRemote() }.start()
            return 4
        }
        override fun driver(brand: String, ip: String, clientKey: String?, onClientKey: (String) -> Unit, onDisconnect: () -> Unit) =
            SamsungDriver(ip, clientKey, onClientKey, onDisconnect, urls = listOf(wsUrl), restBase = restUrl, pollMs = 200)
        override suspend fun lookupName(context: Context, ip: String): String? = "Samsung Q80 Series (65) (simulated)"
    }
}
