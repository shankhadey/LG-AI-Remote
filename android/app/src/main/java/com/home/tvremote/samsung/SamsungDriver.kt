package com.home.tvremote.samsung

import com.home.tvremote.lg.TvException
import com.home.tvremote.tv.AppIds
import com.home.tvremote.tv.TvDriver
import com.home.tvremote.voice.TvApp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Samsung Tizen TVs (2016+), over the TV's local remote-control websocket and its small REST API:
 *  - wss://IP:8002/api/v2/channels/samsung.remote.control?name=…&token=…  (ws://IP:8001 on older models):
 *    remote keys, typing into the TV keyboard (with ime start/end events), the installed-app list, launching apps
 *    with a deep link. First connection shows "Allow" on the TV; the token it returns is kept like LG's client key.
 *  - http://IP:8001/api/v2/ : model, Wi-Fi MAC, power state ("on"/"standby", 2018+);
 *    /api/v2/applications/<id> : is that app running / in front (polled for the few apps that matter).
 *
 * App IDs: the rest of the app uses one shared name per app (LG's); this driver converts at the edge ([AppIds]).
 * No free pointer on Samsung: drags become arrow keys and a tap is OK. No play state and no on-screen notifications.
 * Written from the community-documented protocol; checked against [SimulatedSamsungTv], not yet a real TV.
 */
class SamsungDriver(
    val ip: String,
    private var token: String?,
    private val onClientKey: (String) -> Unit,
    private val onDisconnect: () -> Unit = {},
    private val urls: List<String> = listOf("wss://$ip:8002", "ws://$ip:8001"),
    private val restBase: String = "http://$ip:8001",
    private val pollMs: Long = 3000,
) : TvDriver {
    override val brand = "Samsung"
    override val caps = TvDriver.Caps(pointer = false, playState = false, toasts = false)
    override val history = ConcurrentLinkedDeque<String>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client: OkHttpClient = run {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
            override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), SecureRandom()) }
        OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory, trustAll).hostnameVerifier { _, _ -> true }
            .connectTimeout(4, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).pingInterval(20, TimeUnit.SECONDS).build()
    }
    @Volatile private var ws: WebSocket? = null
    @Volatile private var ready = CompletableDeferred<Unit>()
    private val generation = AtomicInteger(0)
    @Volatile private var appsReply: CompletableDeferred<JSONArray>? = null
    private var keyboardCb: ((Boolean) -> Unit)? = null
    private var pollJob: Job? = null

    override val isConnected get() = ws != null && ready.isCompleted && !ready.isCancelled

    private fun log(line: String) { history.addFirst(java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date()) + " " + line); while (history.size > 40) history.pollLast() }

    // ---- connection ----
    override suspend fun connect(onPrompt: () -> Unit) {
        if (isConnected) return
        var last: Exception? = null
        for (base in urls) {
            try { open(base, onPrompt); return } catch (e: TvException) { close(); throw e } catch (e: Exception) { last = e; close() }
        }
        throw TvException("Can't reach the TV at $ip. Is it on and on the same Wi-Fi? (${last?.message})")
    }

    private suspend fun open(base: String, onPrompt: () -> Unit) {
        val name = java.util.Base64.getEncoder().encodeToString("Phone remote".toByteArray())
        val url = "$base/api/v2/channels/samsung.remote.control?name=$name" + (token?.let { "&token=$it" } ?: "")
        val opened = CompletableDeferred<Unit>()
        ready = CompletableDeferred()
        val gen = generation.incrementAndGet()
        ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { opened.complete(Unit) }
            override fun onMessage(webSocket: WebSocket, text: String) { if (gen == generation.get()) handle(text) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                opened.completeExceptionally(t); if (gen == generation.get()) dropped(t.message ?: "failed")
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null); if (gen == generation.get()) dropped("closed by the TV") }
        })
        withTimeout(5000) { opened.await() }
        if (token == null) onPrompt()
        try {
            // Paired: answers at once. First time: someone has to press Allow on the TV.
            withTimeout(if (token == null) 60_000 else 4_000) { ready.await() }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw TvException("No answer from the TV. Choose Allow on the TV screen and try again.")
        }
        log("OK  connected (${if (base.startsWith("wss")) "8002" else "8001"})")
        startPolling()
    }

    private fun handle(text: String) {
        val m = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (m.optString("event")) {
            "ms.channel.connect" -> {
                m.optJSONObject("data")?.optString("token")?.takeIf { it.isNotEmpty() && it != token }?.let { token = it; onClientKey(it) }
                ready.complete(Unit)
            }
            "ms.channel.unauthorized" -> ready.completeExceptionally(TvException("The TV declined. Try again and choose Allow on the TV."))
            "ms.remote.imeStart" -> { log("SUB keyboard open"); keyboardCb?.invoke(true) }
            "ms.remote.imeEnd" -> { log("SUB keyboard closed"); keyboardCb?.invoke(false) }
            "ed.installedApp.get" -> m.optJSONObject("data")?.optJSONArray("data")?.let { appsReply?.complete(it) }
        }
    }

    private fun dropped(why: String) {
        val wasUp = ws != null
        ws = null
        if (!ready.isCompleted) ready.completeExceptionally(TvException("Lost connection ($why)"))
        pollJob?.cancel()
        if (wasUp) onDisconnect()
    }

    override fun close() {
        generation.incrementAndGet()
        pollJob?.cancel()
        // close, not cancel: keys are fire-and-forget, and cancel would drop one still queued (e.g. KEY_POWER right before closing).
        ws?.close(1000, null); ws = null
        if (!ready.isCompleted) ready.completeExceptionally(TvException("Connection closed"))
    }

    private suspend fun ensure() { if (!isConnected) connect() }
    private fun send(obj: JSONObject, what: String) {
        if (ws?.send(obj.toString()) != true) throw TvException("Not connected to the TV")
        log("OK  $what")
    }

    // ---- REST ----
    private suspend fun rest(path: String, method: String = "GET"): JSONObject? = withContext(Dispatchers.IO) {
        runCatching {
            val b = Request.Builder().url("$restBase$path")
            when (method) { "POST" -> b.post("".toRequestBody("application/json".toMediaType())); "PUT" -> b.put("".toRequestBody(null)) }
            client.newCall(b.build()).execute().use { r -> if (r.isSuccessful) JSONObject(r.body?.string().orEmpty().ifEmpty { "{}" }) else null }
        }.getOrNull()
    }
    private suspend fun device() = rest("/api/v2/")?.optJSONObject("device")

    // ---- power and info ----
    override suspend fun powerState(): String? = when (device()?.optString("PowerState")) {
        "on" -> "Active"; "standby" -> "Active Standby"; else -> if (isConnected) "Active" else null
    }
    override suspend fun turnOff() = key("KEY_POWER")
    override suspend fun modelName(): String? = device()?.let { d -> d.optString("modelName").ifEmpty { d.optString("name") }.ifEmpty { null } }
    override suspend fun macAddresses(): List<String> = listOfNotNull(device()?.optString("wifiMac")?.takeIf { it.length >= 12 })

    override suspend fun installedApps(): List<TvApp>? {
        ensure()
        val reply = CompletableDeferred<JSONArray>().also { appsReply = it }
        send(JSONObject().put("method", "ms.channel.emit").put("params", JSONObject().put("event", "ed.installedApp.get").put("to", "host")), "installed apps")
        val arr = withTimeoutOrNull(4000) { reply.await() } ?: return null
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map { TvApp(AppIds.canonical(it.optString("appId")), it.optString("name")) }
    }

    /** Apps whose "in front" status is polled (the TV has no event for it): the well-known ones plus the installed list. */
    @Volatile var watchedApps: Set<String> = SamsungApps.COMMON.map { AppIds.forBrand(it.id, brand) }.toSet()
    @Volatile private var lastFront: String? = null
    @Volatile private var lastPower: String? = null
    private var foregroundCb: ((String) -> Unit)? = null
    private var powerCb: ((String) -> Unit)? = null

    override suspend fun foregroundApp(): String? {
        for (id in watchedApps) if (rest("/api/v2/applications/$id")?.optBoolean("visible") == true) return AppIds.canonical(id)
        return "com.webos.app.home"   // nothing we know is in front: the home screen (shared name)
    }
    override suspend fun onForeground(cb: (String) -> Unit) { foregroundCb = cb }
    override suspend fun onPower(cb: (String) -> Unit) { powerCb = cb }
    override suspend fun onKeyboard(cb: (Boolean) -> Unit) { keyboardCb = cb }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (true) {
                runCatching { foregroundApp() }.getOrNull()?.let { if (it != lastFront) { lastFront = it; log("SUB in front: $it"); foregroundCb?.invoke(it) } }
                runCatching { powerState() }.getOrNull()?.let { if (it != lastPower) { lastPower = it; powerCb?.invoke(it) } }
                delay(pollMs)
            }
        }
    }

    // ---- control ----
    private suspend fun key(code: String) {
        ensure()
        send(JSONObject().put("method", "ms.remote.control").put("params", JSONObject()
            .put("Cmd", "Click").put("DataOfCmd", code).put("Option", "false").put("TypeOfRemote", "SendRemoteKey")), "key $code")
    }
    override suspend fun button(name: String) = key(KEYS[name] ?: "KEY_$name")

    // No pointer: a drag becomes arrow presses (one per ~60 px of movement in the main direction).
    private var accX = 0f; private var accY = 0f
    override suspend fun move(dx: Float, dy: Float) {
        accX += dx; accY += dy
        val step = 60f
        while (kotlin.math.abs(accX) >= step || kotlin.math.abs(accY) >= step) {
            if (kotlin.math.abs(accX) >= kotlin.math.abs(accY)) { key(if (accX > 0) "KEY_RIGHT" else "KEY_LEFT"); accX -= step * kotlin.math.sign(accX); accY = 0f }
            else { key(if (accY > 0) "KEY_DOWN" else "KEY_UP"); accY -= step * kotlin.math.sign(accY); accX = 0f }
        }
    }
    override suspend fun click() { accX = 0f; accY = 0f; key("KEY_ENTER") }
    override suspend fun scroll(dy: Float) { if (kotlin.math.abs(dy) > 2) key(if (dy > 0) "KEY_UP" else "KEY_DOWN") }
    override suspend fun volume(up: Boolean) = key(if (up) "KEY_VOLUP" else "KEY_VOLDOWN")
    override suspend fun setVolume(level: Int) { log("volume to $level isn't available on Samsung over this connection") }
    @Volatile private var muted = false
    override suspend fun isMuted(): Boolean = muted
    override suspend fun setMute(mute: Boolean) { if (mute != muted) { key("KEY_MUTE"); muted = mute } }
    override suspend fun media(action: String) = key(when (action) { "play" -> "KEY_PLAY"; "pause" -> "KEY_PAUSE"; "stop" -> "KEY_STOP"; "rewind" -> "KEY_REWIND"; "fastForward" -> "KEY_FF"; else -> "KEY_PLAY" })
    override suspend fun channel(up: Boolean) = key(if (up) "KEY_CHUP" else "KEY_CHDOWN")
    override suspend fun openChannel(number: String) { for (d in number) { key(if (d.isDigit()) "KEY_$d" else "KEY_PLUS100"); delay(150) }; key("KEY_ENTER") }
    override suspend fun openInput(hdmi: Int) = key("KEY_HDMI$hdmi")

    override suspend fun launch(appId: String, extras: JSONObject?, alternative: Boolean) {
        val id = AppIds.forBrand(appId, brand)
        if (alternative) { rest("/api/v2/applications/$id", "POST") ?: throw TvException("The TV didn't open $id"); log("OK  open $id (REST)"); return }
        ensure()
        // LG-shaped extras → Samsung's metaTag: Netflix's contentId string, or a URL / parameters for other apps.
        val meta = extras?.optString("contentId")?.ifEmpty { null } ?: extras?.optJSONObject("params")?.optString("contentTarget")?.ifEmpty { null }
        val data = JSONObject().put("appId", id).put("action_type", if (id == AppIds.forBrand("com.webos.app.browser", brand)) "NATIVE_LAUNCH" else "DEEP_LINK")
        meta?.let { data.put("metaTag", it) }
        send(JSONObject().put("method", "ms.channel.emit").put("params", JSONObject().put("event", "ed.apps.launch").put("to", "host").put("data", data)),
            "open $id${meta?.let { " · $it" } ?: ""}")
    }
    override suspend fun openUrl(url: String): Boolean = runCatching { launch("com.webos.app.browser", JSONObject().put("contentId", url)) }.isSuccess

    override suspend fun typeText(text: String) {
        ensure()
        send(JSONObject().put("method", "ms.remote.control").put("params", JSONObject()
            .put("Cmd", java.util.Base64.getEncoder().encodeToString(text.toByteArray())).put("DataOfCmd", "base64").put("TypeOfRemote", "SendInputString")), "type “$text”")
    }
    override suspend fun sendEnter() {
        ensure()
        send(JSONObject().put("method", "ms.remote.control").put("params", JSONObject().put("TypeOfRemote", "SendInputEnd")), "input end")
        key("KEY_ENTER")
    }

    companion object {
        /** App names (LG's, used across the app) → Samsung key codes. */
        val KEYS = mapOf(
            "ENTER" to "KEY_ENTER", "BACK" to "KEY_RETURN", "HOME" to "KEY_HOME", "UP" to "KEY_UP", "DOWN" to "KEY_DOWN",
            "LEFT" to "KEY_LEFT", "RIGHT" to "KEY_RIGHT", "MENU" to "KEY_MENU", "INFO" to "KEY_INFO", "EXIT" to "KEY_EXIT",
        )
    }
}

/** Common apps on Samsung TVs, by their shared names (the driver converts to Tizen IDs). */
object SamsungApps {
    val COMMON = listOf(
        TvApp("netflix", "Netflix"), TvApp("youtube.leanback.v4", "YouTube"), TvApp("amazon", "Prime Video"),
        TvApp("com.disney.disneyplus-prod", "Disney+"), TvApp("hulu", "Hulu"), TvApp("com.apple.appletv", "Apple TV"),
        TvApp("com.wbd.stream", "Max"), TvApp("spotify-beehive", "Spotify"), TvApp("peacock", "Peacock"), TvApp("paramount", "Paramount+"),
    )
}
