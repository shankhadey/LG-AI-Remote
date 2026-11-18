package com.home.tvremote

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.home.tvremote.lg.Discovery
import com.home.tvremote.lg.FoundTv
import com.home.tvremote.lg.LgTv
import com.home.tvremote.lg.WakeOnLan
import com.home.tvremote.voice.Command
import com.home.tvremote.voice.CommandParser
import com.home.tvremote.voice.TvApp
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.URLEncoder

enum class Screen { Setup, Pairing, Remote }

class TvController(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("tv", Context.MODE_PRIVATE)

    var screen by mutableStateOf(if (prefs.getString("ip", null) != null) Screen.Remote else Screen.Setup)
    var tvName by mutableStateOf(prefs.getString("name", "LG TV")!!)
    var connected by mutableStateOf(false)
    var connecting by mutableStateOf(false)
    var scanning by mutableStateOf(false)
    val found = mutableStateListOf<FoundTv>()
    val apps = mutableStateListOf<TvApp>()
    var tvKeyboardOpen by mutableStateOf(false)
    var feedback by mutableStateOf<String?>(null)   // short "what just happened" line
    var feedbackIsError by mutableStateOf(false)
    private var feedbackJob: Job? = null

    private var tv: LgTv? = null
    // All TV commands run in order through this queue so fast taps don't reorder.
    private val queue = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        viewModelScope.launch { for (job in queue) runCatching { job() }.onFailure { showError(it) } }
        if (screen == Screen.Remote) connect()
    }

    // ---- setup ------------------------------------------------------------
    fun scan() {
        if (scanning) return
        scanning = true
        viewModelScope.launch {
            try {
                found.clear(); found.addAll(Discovery.find(getApplication()))
                if (found.isEmpty()) say("No LG TV answered. Make sure it's on and on the same Wi-Fi.", error = true)
            } catch (e: Exception) { showError(e) }
            scanning = false
        }
    }

    fun choose(ip: String, name: String) {
        if (ip != prefs.getString("ip", null)) prefs.edit().remove("clientKey").remove("mac").apply()
        prefs.edit().putString("ip", ip).putString("name", name).apply()
        tvName = name
        tv?.close(); tv = null
        connect(pairing = true)
    }

    fun forget() {
        tv?.close(); tv = null
        prefs.edit().clear().apply()
        apps.clear(); found.clear(); connected = false
        screen = Screen.Setup
    }

    // ---- connection -------------------------------------------------------
    fun connect(pairing: Boolean = false) {
        val ip = prefs.getString("ip", null) ?: return
        if (connecting) return
        connecting = true
        viewModelScope.launch {
            try {
                val t = tv ?: LgTv(
                    ip, prefs.getString("clientKey", null),
                    onClientKey = { prefs.edit().putString("clientKey", it).apply() },
                    onDisconnect = { connected = false },
                ).also { tv = it }
                val firstTime = prefs.getString("clientKey", null) == null
                t.connect(onPrompt = { screen = Screen.Pairing })
                connected = true
                screen = Screen.Remote
                if (firstTime || pairing) {
                    runCatching { t.request("ssap://system.notifications/createToast", JSONObject().put("message", "Phone remote connected")) }
                    say("Connected to $tvName")
                }
                afterConnect(t)
            } catch (e: Exception) {
                connected = false
                if (screen == Screen.Pairing) screen = if (prefs.getString("clientKey", null) == null) Screen.Setup else Screen.Remote
                showError(e)
            } finally { connecting = false }
        }
    }

    private suspend fun afterConnect(t: LgTv) {
        runCatching {
            val lp = t.request("ssap://com.webos.applicationManager/listLaunchPoints").optJSONArray("launchPoints")
            if (lp != null) {
                apps.clear()
                for (i in 0 until lp.length()) {
                    val a = lp.getJSONObject(i)
                    apps += TvApp(a.optString("id"), a.optString("title"), a.optString("largeIcon").ifEmpty { a.optString("icon") }.ifEmpty { null })
                }
            }
        }
        runCatching {
            // Tell us when a text box is focused on the TV, so typing/voice goes straight into it.
            t.subscribe("ssap://com.webos.service.ime/registerRemoteKeyboard") { p ->
                p.optJSONObject("currentWidget")?.let { tvKeyboardOpen = it.optBoolean("focus", false) }
            }
        }
        if (prefs.getString("mac", null) == null) runCatching {
            val info = t.request("ssap://com.webos.service.connectionmanager/getinfo")
            val mac = info.optJSONObject("wiredInfo")?.optString("macAddress")?.takeIf { it.length >= 12 }
                ?: info.optJSONObject("wifiInfo")?.optString("macAddress")?.takeIf { it.length >= 12 }
            mac?.let { prefs.edit().putString("mac", it).apply() }
        }
    }

    private suspend fun tv(): LgTv {
        val t = tv ?: throw IllegalStateException("No TV set up")
        if (!t.isConnected) { t.connect(); connected = true; afterConnect(t) }
        return t
    }

    private fun enqueue(job: suspend () -> Unit) { queue.trySend(job) }

    // ---- direct controls --------------------------------------------------
    fun button(name: String) = enqueue { tv().button(name) }
    fun ssap(uri: String, payload: JSONObject? = null) = enqueue { tv().request(uri, payload) }
    fun volume(up: Boolean) = ssap(if (up) "ssap://audio/volumeUp" else "ssap://audio/volumeDown")
    fun move(dx: Float, dy: Float) = enqueue { tv().move(dx, dy) }
    fun click() = enqueue { tv().click() }
    fun scroll(dy: Float) = enqueue { tv().scroll(dy) }
    fun launch(app: TvApp) { say("Opening ${app.title}"); ssap("ssap://system.launcher/launch", JSONObject().put("id", app.id)) }

    fun togglePlayPause() = enqueue {
        playing = !playing
        tv().request("ssap://media.controls/${if (playing) "play" else "pause"}")
    }
    private var playing = true

    fun toggleMute() = enqueue {
        val muted = tv().request("ssap://audio/getStatus").optBoolean("mute")
        tv().request("ssap://audio/setMute", JSONObject().put("mute", !muted))
        say(if (muted) "Sound on" else "Muted")
    }

    fun power() {
        if (connected) { say("Turning off"); ssap("ssap://system/turnOff") } else powerOn()
    }

    private fun powerOn() {
        val mac = prefs.getString("mac", null)
        if (mac == null) { say("Can't turn the TV on: connect once while it's on so I can learn its address.", error = true); return }
        viewModelScope.launch {
            runCatching { WakeOnLan.send(mac) }.onFailure { showError(it) }
            say("Turning on…")
            // Give the TV time to boot, then reconnect.
            repeat(8) { delay(2500); if (!connected) connect() else return@launch }
        }
    }

    /** Live typing into a focused TV text box. */
    fun typeOnTv(text: String) = ssap("ssap://com.webos.service.ime/insertText", JSONObject().put("text", text).put("replace", true))
    fun enterOnTv() = ssap("ssap://com.webos.service.ime/sendEnterKey")

    // ---- voice / search bar -------------------------------------------------
    /** Handles anything said or typed. Returns false if it wasn't understood. */
    fun handle(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        if (tvKeyboardOpen) {
            // A search box is open on the TV: whatever was said goes straight into it.
            val cmd = CommandParser(apps).parse(t)
            if (cmd == null || cmd is Command.Search) {
                typeOnTv((cmd as? Command.Search)?.query ?: t); enterOnTv(); say("Typed \"${(cmd as? Command.Search)?.query ?: t}\"")
                return true
            }
        }
        val cmd = CommandParser(apps).parse(t) ?: Command.Search(t, null)
        run(cmd)
        return true
    }

    fun run(cmd: Command) {
        when (cmd) {
            is Command.Power -> if (cmd.on) powerOn() else { say("Turning off"); ssap("ssap://system/turnOff") }
            is Command.VolumeStep -> {
                say(if (cmd.up) "Louder" else "Quieter")
                enqueue { repeat(cmd.steps) { tv().request(if (cmd.up) "ssap://audio/volumeUp" else "ssap://audio/volumeDown"); delay(40) } }
            }
            is Command.VolumeSet -> { say("Volume ${cmd.level}"); ssap("ssap://audio/setVolume", JSONObject().put("volume", cmd.level)) }
            is Command.Mute -> { say(if (cmd.mute) "Muted" else "Sound on"); ssap("ssap://audio/setMute", JSONObject().put("mute", cmd.mute)) }
            is Command.Media -> { say(cmd.action.replaceFirstChar { it.uppercase() }.replace("Forward", " forward")); ssap("ssap://media.controls/${cmd.action}") }
            is Command.Button -> button(cmd.name)
            is Command.ChannelStep -> { say(if (cmd.up) "Channel up" else "Channel down"); ssap(if (cmd.up) "ssap://tv/channelUp" else "ssap://tv/channelDown") }
            is Command.ChannelNumber -> { say("Channel ${cmd.number}"); ssap("ssap://tv/openChannel", JSONObject().put("channelNumber", cmd.number)) }
            is Command.Input -> { say("HDMI ${cmd.hdmi}"); ssap("ssap://system.launcher/launch", JSONObject().put("id", "com.webos.app.hdmi${cmd.hdmi}")) }
            is Command.OpenApp -> launch(cmd.app)
            is Command.Search -> search(cmd.query, cmd.app)
        }
    }

    private fun search(query: String, app: TvApp?) {
        val target = app ?: apps.firstOrNull { it.id.startsWith("youtube") } ?: TvApp("youtube.leanback.v4", "YouTube")
        if (target.id.startsWith("youtube")) {
            say("Searching YouTube for \"$query\"")
            val url = "https://www.youtube.com/tv?launch=voice&vq=" + URLEncoder.encode(query, "UTF-8")
            ssap("ssap://system.launcher/launch", JSONObject().put("id", target.id).put("params", JSONObject().put("contentTarget", url)))
        } else {
            // Other apps don't accept a search from outside: open it, then the TV's search
            // box will pick up typing/voice automatically once it's focused.
            launch(target)
            say("Opened ${target.title}. Open its search box, then say \"$query\" again.")
        }
    }

    // ---- feedback ---------------------------------------------------------
    fun say(msg: String, error: Boolean = false) {
        feedback = msg; feedbackIsError = error
        feedbackJob?.cancel()
        feedbackJob = viewModelScope.launch { delay(if (error) 4500 else 2200); feedback = null }
    }
    private fun showError(e: Throwable) = say(e.message ?: "Something went wrong", error = true)

    override fun onCleared() { tv?.close() }
}
