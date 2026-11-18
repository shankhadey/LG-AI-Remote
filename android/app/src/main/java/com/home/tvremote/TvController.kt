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
import kotlinx.coroutines.withTimeoutOrNull
import android.util.Log
import com.home.tvremote.lg.TvException
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
    var scanReport by mutableStateOf<String?>(null)
    var macKnown by mutableStateOf(prefs.getString("mac", null) != null)
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
                val results = Discovery.find(getApplication())
                // Keep TVs found earlier: discovery is lossy, and losing the list after a failed pairing is confusing.
                for (r in results) {
                    val i = found.indexOfFirst { it.ip == r.ip }
                    if (i >= 0) found[i] = r else found += r
                }
                if (found.isEmpty()) scanReport = Discovery.lastReport.ifEmpty { "No LG TV found." } +
                    " If the TV's address isn't in that range, the phone and TV are on different networks (e.g. a guest network). You can still enter the TV's IP below."
                else scanReport = null
            } catch (e: Exception) { showError(e) }
            scanning = false
        }
    }

    fun choose(ip: String, name: String) {
        if (ip != prefs.getString("ip", null)) { prefs.edit().remove("clientKey").remove("mac").apply(); macKnown = false }
        prefs.edit().putString("ip", ip).putString("name", name).apply()
        tvName = name
        tv?.close(); tv = null
        connect(pairing = true)
    }

    fun forget() {
        tv?.close(); tv = null
        prefs.edit().clear().apply()
        apps.clear(); found.clear(); connected = false; macKnown = false
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
            // Keep both: Wake-on-LAN only works on the interface the TV actually uses (Wi-Fi vs cable).
            val macs = listOf("wifiInfo", "wiredInfo").mapNotNull { info.optJSONObject(it)?.optString("macAddress")?.takeIf { m -> m.length >= 12 } }
            if (macs.isNotEmpty()) { prefs.edit().putString("mac", macs.joinToString(",")).apply(); macKnown = true }
        }.onFailure { Log.w("TvRemote", "Couldn't read TV MAC: ${it.message}") }
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

    fun currentMac() = prefs.getString("mac", null).orEmpty()
    fun setMac(value: String) {
        val clean = value.split(',', ' ').map { it.trim() }.filter { it.count { c -> c.isLetterOrDigit() } == 12 }
        if (clean.isEmpty()) { say("That doesn't look like a MAC address (e.g. A8:23:FE:01:02:03)", error = true); return }
        prefs.edit().putString("mac", clean.joinToString(",")).apply(); macKnown = true; say("Saved. Power on is ready.")
    }

    /**
     * One power button. After "off", LG TVs with Quick Start+ stay on the network in standby and
     * keep the connection open, so "connected" doesn't mean "on" — ask the TV for its power state.
     */
    fun power() {
        viewModelScope.launch {
            val t = tv
            val state = if (t != null && t.isConnected) withTimeoutOrNull(2500) {
                runCatching { t.request("ssap://com.webos.service.tvpower/power/getPowerState").optString("state") }
                    .getOrElse { if (it is TvException && t.isConnected) "unknown-but-reachable" else null }
            } else null
            when (state) {
                "Active", "unknown-but-reachable" -> { say("Turning off"); ssap("ssap://system/turnOff") }
                "Screen Off" -> { say("Turning the screen on"); ssap("ssap://com.webos.service.tvpower/power/turnOnScreen") }
                else -> powerOn() // null (unreachable), "Active Standby", "Suspend", ...
            }
        }
    }

    var waking by mutableStateOf(false)

    private fun powerOn() {
        val macs = prefs.getString("mac", null)?.split(',')?.filter { it.isNotBlank() }.orEmpty()
        if (macs.isEmpty()) {
            say("To turn the TV on I need its MAC address — add it from the ⋮ menu.", error = true); return
        }
        if (waking) return
        val ip = prefs.getString("ip", null)
        waking = true
        viewModelScope.launch {
            try {
                // Keep sending the wake signal for ~40 s: a TV that was just switched off ignores it
                // until it has finished shutting down, and routers sometimes drop a single packet.
                val start = System.currentTimeMillis()
                var sent = 0
                var lastErr: Throwable? = null
                while (System.currentTimeMillis() - start < 40_000) {
                    val elapsed = (System.currentTimeMillis() - start) / 1000
                    say(if (elapsed < 8) "Turning on…" else "Turning on… (${elapsed}s, waiting for the TV)")
                    runCatching { sent += WakeOnLan.send(getApplication(), macs, ip) }.onFailure { lastErr = it }
                    if (ip != null && WakeOnLan.reachable(ip)) {
                        // The TV's network is back; connect quietly (it may still be booting).
                        repeat(5) {
                            val t = tv ?: run { connect(); return@launch }
                            if (runCatching { t.connect(); true }.getOrDefault(false)) {
                                // Right after "off" the TV answers for a few seconds while shutting down;
                                // only call it on once it reports itself awake.
                                val state = runCatching {
                                    t.request("ssap://com.webos.service.tvpower/power/getPowerState").optString("state")
                                }.getOrNull()
                                if (state == null || state == "Active" || state.isEmpty()) {
                                    connected = true; afterConnect(t); say("TV is on"); return@launch
                                }
                            }
                            delay(1500)
                        }
                    }
                    delay(2500)
                }
                when {
                    sent == 0 -> say("This phone couldn't send the wake-up signal (${lastErr?.message ?: "no Wi-Fi?"}). Make sure the phone is on Wi-Fi.", error = true)
                    else -> say("The TV didn't respond to the wake-up signal. If it's on Wi-Fi, see ⋮ › Power-on settings for what to check.", error = true)
                }
            } finally { waking = false }
        }
    }

    /** Live typing into a focused TV text box. */
    fun typeOnTv(text: String) = ssap("ssap://com.webos.service.ime/insertText", JSONObject().put("text", text).put("replace", true))
    fun enterOnTv() = ssap("ssap://com.webos.service.ime/sendEnterKey")

    // ---- voice / search bar -------------------------------------------------
    /** Handles anything said or typed. Returns false if it wasn't understood. */
    /** Voice gives several guesses; use the first one that's a real command (else the top guess). */
    fun handleSpoken(guesses: List<String>): String {
        val parser = CommandParser(apps)
        val pick = guesses.firstOrNull { g -> parser.parse(g).let { it != null && it !is Command.NoSuchApp } } ?: guesses.first()
        handle(pick)
        return pick
    }

    fun voiceHints(): List<String> = (apps.map { it.title } + CommandParser.KNOWN_APPS.map { it.title }).distinct() +
        listOf("open", "volume", "mute", "pause", "play", "HDMI", "channel", "search", "turn off")

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
            is Command.NoSuchApp -> say("There's no app called \"${cmd.name}\" on the TV", error = true)
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
