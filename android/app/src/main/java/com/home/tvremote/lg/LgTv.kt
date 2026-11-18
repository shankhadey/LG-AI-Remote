package com.home.tvremote.lg

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

class TvException(message: String) : Exception(message)

/**
 * Client for LG webOS's SSAP protocol. The TV uses a self-signed certificate, so this
 * client accepts any certificate — it only ever connects to the TV's LAN address.
 */
class LgTv(
    val ip: String,
    private var clientKey: String?,
    private val onClientKey: (String) -> Unit,
    private val onDisconnect: () -> Unit = {},
    private val urls: List<String> = listOf("wss://$ip:3001", "ws://$ip:3000"),
) {
    private val client: OkHttpClient = run {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
            override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), SecureRandom()) }
        OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(4, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    private var ws: WebSocket? = null
    private var pointer: WebSocket? = null
    private val seq = AtomicInteger(0)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val subscriptions = ConcurrentHashMap<String, (JSONObject) -> Unit>()
    @Volatile private var registerId: String? = null
    @Volatile private var registered = CompletableDeferred<Unit>()

    val isConnected get() = ws != null && registered.isCompleted && !registered.isCancelled

    /** Opens the socket and registers. First time, the TV shows a prompt the user must accept. */
    suspend fun connect(onPrompt: () -> Unit = {}) {
        if (isConnected) return
        var lastError: Exception? = null
        for (url in urls) {
            try {
                open(url)
                register(onPrompt)
                return
            } catch (e: TvException) {
                close(); throw e
            } catch (e: Exception) {
                lastError = e; close()
            }
        }
        throw TvException("Can't reach the TV at $ip. Is it on and on the same Wi-Fi? (${lastError?.message})")
    }

    private suspend fun open(url: String) {
        val opened = CompletableDeferred<Unit>()
        registered = CompletableDeferred()
        ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { opened.complete(Unit) }
            override fun onMessage(webSocket: WebSocket, text: String) = handle(text)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                opened.completeExceptionally(t); dropped(t.message ?: "connection failed")
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped("closed")
        })
        withTimeout(5000) { opened.await() }
    }

    private fun dropped(why: String) {
        val wasUp = ws != null
        ws = null; pointer?.cancel(); pointer = null
        pending.values.forEach { it.completeExceptionally(TvException("Lost connection to the TV ($why)")) }
        pending.clear(); subscriptions.clear()
        if (!registered.isCompleted) registered.completeExceptionally(TvException("Lost connection ($why)"))
        if (wasUp) onDisconnect()
    }

    private fun handle(text: String) {
        val msg = try { JSONObject(text) } catch (_: Exception) { return }
        val id = msg.optString("id")
        val type = msg.optString("type")
        val payload = msg.optJSONObject("payload") ?: JSONObject()
        if (id == registerId) {
            when (type) {
                "registered" -> {
                    payload.optString("client-key").takeIf { it.isNotEmpty() }?.let { clientKey = it; onClientKey(it) }
                    registered.complete(Unit)
                }
                "error" -> registered.completeExceptionally(TvException("The TV declined pairing: ${msg.optString("error")}"))
            }
            return
        }
        subscriptions[id]?.let { it(payload); return }
        val d = pending.remove(id) ?: return
        if (type == "error" || payload.optBoolean("returnValue", true) == false) {
            d.completeExceptionally(TvException(msg.optString("error").ifEmpty { payload.optString("errorText", "TV error") }))
        } else d.complete(payload)
    }

    private suspend fun register(onPrompt: () -> Unit) {
        val id = "register_${seq.incrementAndGet()}"
        registerId = id
        val payload = JSONObject(Manifest.REGISTER).apply { clientKey?.let { put("client-key", it) } }
        send(JSONObject().put("id", id).put("type", "register").put("payload", payload))
        if (clientKey == null) onPrompt()
        try {
            // A minute so someone can find the TV remote and accept the prompt.
            withTimeout(if (clientKey == null) 60_000 else 8_000) { registered.await() }
        } catch (e: TimeoutCancellationException) {
            throw TvException("No answer from the TV. Accept the prompt on the TV screen and try again.")
        }
    }

    private fun send(obj: JSONObject) {
        if (ws?.send(obj.toString()) != true) throw TvException("Not connected to the TV")
    }

    suspend fun request(uri: String, payload: JSONObject? = null): JSONObject {
        connect()
        val id = "req_${seq.incrementAndGet()}"
        val d = CompletableDeferred<JSONObject>()
        pending[id] = d
        send(JSONObject().put("id", id).put("type", "request").put("uri", uri).apply { payload?.let { put("payload", it) } })
        return try { withTimeout(6000) { d.await() } } catch (e: TimeoutCancellationException) {
            pending.remove(id); throw TvException("The TV didn't respond")
        }
    }

    suspend fun subscribe(uri: String, onEvent: (JSONObject) -> Unit) {
        connect()
        val id = "sub_${seq.incrementAndGet()}"
        subscriptions[id] = onEvent
        send(JSONObject().put("id", id).put("type", "subscribe").put("uri", uri))
    }

    // ---- pointer / buttons (the "magic remote" socket) ----
    private suspend fun pointerSocket(): WebSocket {
        pointer?.let { return it }
        val path = request("ssap://com.webos.service.networkinput/getPointerInputSocket").getString("socketPath")
        val opened = CompletableDeferred<Unit>()
        val s = client.newWebSocket(Request.Builder().url(path).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { opened.complete(Unit) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                opened.completeExceptionally(t); if (pointer === webSocket) pointer = null
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { if (pointer === webSocket) pointer = null }
        })
        withTimeout(4000) { opened.await() }
        pointer = s
        return s
    }

    suspend fun button(name: String) { pointerSocket().send(Pointer.button(name)) }
    suspend fun move(dx: Float, dy: Float) { pointerSocket().send(Pointer.move(dx, dy)) }
    suspend fun click() { pointerSocket().send(Pointer.click()) }
    suspend fun scroll(dy: Float) { pointerSocket().send(Pointer.scroll(dy)) }

    fun close() {
        pointer?.cancel(); pointer = null
        ws?.cancel(); ws = null
    }
}

/** Wire format of the pointer socket. */
object Pointer {
    fun button(name: String) = "type:button\nname:$name\n\n"
    fun move(dx: Float, dy: Float) = "type:move\ndx:${dx.toInt()}\ndy:${dy.toInt()}\ndown:0\n\n"
    fun click() = "type:click\n\n"
    fun scroll(dy: Float) = "type:scroll\ndx:0\ndy:${dy.toInt()}\n\n"
}
