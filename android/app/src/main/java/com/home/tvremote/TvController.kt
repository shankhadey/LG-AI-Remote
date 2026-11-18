package com.home.tvremote

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
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
import kotlinx.coroutines.withTimeout
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
    /** App currently on the TV screen (from the TV), e.g. "netflix", "com.webos.app.livetv". */
    var foregroundApp by mutableStateOf<String?>(null)
    val isLiveTv get() = foregroundApp == "com.webos.app.livetv"
    /** True when the TV is believed to be off: the remote shows a single "Turn on" button. */
    var tvOff by mutableStateOf(false)
    /** How often each app was opened on the TV, learned on this phone (the TV doesn't share this). */
    private val usage = mutableStateMapOf<String, Int>().apply {
        runCatching { JSONObject(prefs.getString("usage", "{}")!!).let { j -> j.keys().forEach { k -> put(k, j.getInt(k)) } } }
    }
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
                tvOff = false
                screen = Screen.Remote
                if (firstTime || pairing) {
                    runCatching { t.request("ssap://system.notifications/createToast", JSONObject().put("message", "Phone remote connected")) }
                    say("Connected to $tvName")
                }
                afterConnect(t)
            } catch (e: Exception) {
                connected = false
                if (screen == Screen.Pairing) screen = if (prefs.getString("clientKey", null) == null) Screen.Setup else Screen.Remote
                // Unreachable after pairing = most likely switched off: show the "Turn on" screen instead of an error.
                if (e.message?.startsWith("Can't reach") == true && prefs.getString("clientKey", null) != null) tvOff = true
                else showError(e)
            } finally { connecting = false }
        }
    }

    private suspend fun afterConnect(t: LgTv) {
        runCatching {
            val lp = runCatching { t.request("ssap://com.webos.applicationManager/listLaunchPoints").optJSONArray("launchPoints") }
                .getOrElse {
                    // Newer firmware doesn't let this kind of pairing read the app list: show the common apps instead.
                    if (apps.isEmpty()) apps.addAll(CommandParser.KNOWN_APPS.filter { a -> !a.id.startsWith("com.webos") })
                    null
                }
            if (lp != null) {
                apps.clear()
                for (i in 0 until lp.length()) {
                    val a = lp.getJSONObject(i)
                    apps += TvApp(a.optString("id"), a.optString("title"), a.optString("largeIcon").ifEmpty { a.optString("icon") }.ifEmpty { null })
                }
            }
        }
        runCatching {
            // What's on screen: drives the status line, Live TV channel buttons and "Most used".
            t.subscribe("ssap://com.webos.applicationManager/getForegroundAppInfo") { p ->
                p.optString("appId").takeIf { it.isNotEmpty() }?.let { onForeground(it) }
            }
        }
        runCatching { onForeground(t.request("ssap://com.webos.applicationManager/getForegroundAppInfo").optString("appId")) }
        runCatching {
            // Tell us when a text box is focused on the TV, so typing/voice goes straight into it.
            t.subscribe("ssap://com.webos.service.ime/registerRemoteKeyboard") { p ->
                p.optJSONObject("currentWidget")?.let { tvKeyboardOpen = it.optBoolean("focus", false) }
            }
        }
        // Refresh on every connect and MERGE: Wake-on-LAN only works on the interface the TV actually
        // uses (Wi-Fi vs cable), and an older version saved only the wired address and never re-checked.
        runCatching {
            val info = t.request("ssap://com.webos.service.connectionmanager/getinfo")
            val fresh = listOf("wifiInfo", "wiredInfo").mapNotNull { info.optJSONObject(it)?.optString("macAddress")?.takeIf { m -> m.length >= 12 } }
            if (fresh.isEmpty()) Log.w("TvRemote", "TV reported no MAC addresses: ${info.keys().asSequence().toList()}")
            val merged = mergeMacs(prefs.getString("mac", null).orEmpty().split(',') + fresh)
            if (merged.isNotEmpty()) { prefs.edit().putString("mac", merged.joinToString(",")).apply(); macKnown = true }
        }.onFailure { Log.w("TvRemote", "Couldn't read TV MAC: ${it.message}") }
    }

    private fun onForeground(appId: String) {
        if (appId.isEmpty() || appId == foregroundApp) return
        foregroundApp = appId
        if (appId in SYSTEM_APPS) return
        usage[appId] = (usage[appId] ?: 0) + 1
        prefs.edit().putString("usage", JSONObject(usage.toMap() as Map<*, *>).toString()).apply()
        // Apps the TV wouldn't list become voice targets once they've been opened.
        if (apps.none { it.id == appId }) apps += TvApp(appId, appTitle(appId))
    }

    /** Top 4 apps by how often they were opened, topped up with common ones. */
    fun mostUsed(): List<TvApp> {
        val learned = usage.entries.sortedByDescending { it.value }.map { TvApp(it.key, appTitle(it.key)) }
        val defaults = CommandParser.KNOWN_APPS.filter { !it.id.startsWith("com.webos") }
        return (learned + defaults).distinctBy { it.id }.take(4)
    }

    fun appTitle(id: String): String =
        CommandParser.KNOWN_APPS.firstOrNull { it.id == id }?.title
            ?: apps.firstOrNull { it.id == id && it.title.isNotBlank() }?.title
            ?: when {
                id == "com.webos.app.home" -> "Home"
                id.startsWith("com.webos.app.hdmi") -> "HDMI " + id.removePrefix("com.webos.app.hdmi")
                else -> id.substringAfterLast('.').replace('-', ' ').replace('_', ' ')
                    .replaceFirstChar { it.uppercase() }
            }

    /** Short line under the TV name: what's on screen. */
    fun nowShowing(): String = when {
        tvOff -> "Off"
        waking -> "Turning on…"
        connecting && !connected -> "Connecting…"
        !connected -> "Not connected — tap to retry"
        foregroundApp == "com.webos.app.livetv" -> "Live TV"
        foregroundApp != null -> appTitle(foregroundApp!!)
        else -> "Connected"
    }

    /** The big "Turn on" button: connect if the TV is actually reachable, otherwise wake it. */
    fun turnOn() {
        viewModelScope.launch {
            val ip = prefs.getString("ip", null)
            if (ip != null && WakeOnLan.reachable(ip)) { tvOff = false; connect() } else powerOn()
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
    /**
     * What a touchpad tap sends. Some LG firmware ignores the pointer "click" message, so this is
     * selectable: "click" (pointer click), "ok" (the remote's OK button), "enter" (keyboard Enter).
     */
    var tapMethod by mutableStateOf(prefs.getString("tapMethod", "ok")!!)
    fun setTap(method: String) { tapMethod = method; prefs.edit().putString("tapMethod", method).apply() }
    fun click() = sendTap(tapMethod)
    fun sendTap(method: String) = enqueue {
        when (method) {
            "ok" -> tv().button("ENTER")
            "enter" -> tv().request("ssap://com.webos.service.ime/sendEnterKey")
            else -> tv().click()
        }
    }
    fun scroll(dy: Float) = enqueue { tv().scroll(dy) }
    /**
     * Opens an app and checks it actually came to the front. If the usual launch call is rejected
     * or silently ignored, retry through the application manager, and report what the TV said.
     */
    fun launch(app: TvApp) {
        say("Opening ${app.title}")
        // Runs beside the command queue (not in it) so checking the result never holds up other buttons.
        viewModelScope.launch {
            val t = runCatching { tv() }.getOrElse { showError(it); return@launch }
            val payload = JSONObject().put("id", app.id)
            val first = runCatching { t.request("ssap://system.launcher/launch", payload) }
            if (first.isSuccess && waitForForeground(t, app.id)) return@launch
            val second = runCatching { t.request("ssap://com.webos.applicationManager/launch", JSONObject().put("id", app.id)) }
            if (second.isSuccess && waitForForeground(t, app.id)) return@launch
            val hung = listOf(first, second).all { it.exceptionOrNull()?.message == "The TV didn't respond" }
            if (hung) {
                // Seen with Netflix: the TV never answers the launch at all, while other apps open fine.
                say("The TV isn't responding to opening ${app.title}. Try it with the TV remote; if it won't open there either, restart the TV or update ${app.title}.", error = true)
            } else {
                val why = (second.exceptionOrNull() ?: first.exceptionOrNull())?.message ?: "the TV accepted the request but didn't open it"
                say("${app.title} didn't open: $why", error = true)
            }
        }
    }

    /** Polls the foreground app for a few seconds. Unknown (no permission) counts as success. */
    private suspend fun waitForForeground(t: LgTv, appId: String): Boolean {
        repeat(8) {
            delay(500)
            val fg = runCatching { t.request("ssap://com.webos.applicationManager/getForegroundAppInfo").optString("appId") }
            if (fg.isFailure) return true
            if (fg.getOrNull() == appId) return true
        }
        return false
    }

    fun diagnostics(): String = buildString {
        appendLine("App ${com.home.tvremote.BuildConfig.VERSION_NAME} · TV ${prefs.getString("ip", "?")} · connected=$connected")
        appendLine("MACs saved: ${prefs.getString("mac", null)?.split(',')?.size ?: 0}")
        appendLine("Apps listed by TV: ${apps.size}")
        apps.firstOrNull { it.title.contains("netflix", true) }?.let { appendLine("Netflix id: ${it.id}") }
        appendLine()
        tv?.history?.forEach { appendLine(it) }
    }

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

    /** Normalises to AA:BB:CC:DD:EE:FF, drops invalid entries and duplicates (case-insensitive). */
    private fun mergeMacs(list: List<String>): List<String> = list
        .map { it.filter { c -> c.isLetterOrDigit() }.uppercase() }
        .filter { it.length == 12 && it.all { c -> c in "0123456789ABCDEF" } }
        .distinct()
        .map { it.chunked(2).joinToString(":") }

    fun currentMac() = prefs.getString("mac", null).orEmpty()
    fun setMac(value: String) {
        val clean = mergeMacs(value.split(',', ' ', '\n'))
        if (clean.isEmpty()) { say("That doesn't look like a MAC address (e.g. A8:23:FE:01:02:03)", error = true); return }
        prefs.edit().putString("mac", clean.joinToString(",")).apply(); macKnown = true
        say(if (clean.size > 1) "Saved ${clean.size} addresses. Power on will try all of them." else "Saved. Power on is ready.")
    }

    /**
     * One power button. After "off", LG TVs with Quick Start+ stay on the network in standby and
     * keep the connection open, so "connected" doesn't mean "on" — ask the TV for its power state.
     */
    private var powerBusy = false

    /**
     * One power button. Decide from the NETWORK, not from whether the app happens to have a live
     * connection right now (it may still be reconnecting after the app opened): if the TV answers
     * on its control port it's on (or in screen-off/standby, which it reports), else wake it.
     */
    fun power() {
        if (powerBusy || waking) return
        powerBusy = true
        viewModelScope.launch {
            try {
                val ip = prefs.getString("ip", null)
                val t = tv
                val reachable = (t?.isConnected == true) || (ip != null && WakeOnLan.reachable(ip))
                if (!reachable) { powerOn(); return@launch }
                say("Checking the TV…")
                if (t != null && !t.isConnected) {
                    runCatching { withTimeout(6000) { t.connect() }; connected = true }
                }
                val state = if (t?.isConnected == true) withTimeoutOrNull(3000) {
                    runCatching { t.request("ssap://com.webos.service.tvpower/power/getPowerState").optString("state") }.getOrNull()
                } else null
                when (state) {
                    "Screen Off" -> { say("Turning the screen on"); ssap("ssap://com.webos.service.tvpower/power/turnOnScreen") }
                    "Active Standby", "Suspend", "Power Off" -> powerOn()
                    // "Active", or the TV answered but didn't say: it's on, so turn it off.
                    else -> { say("Turning off"); ssap("ssap://system/turnOff"); tvOff = true; foregroundApp = null }
                }
            } finally { powerBusy = false }
        }
    }

    /** Press-and-hold on the power button: always try to wake the TV. */
    fun forcePowerOn() { if (!waking) powerOn() }

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
                                    connected = true; tvOff = false; afterConnect(t); say("TV is on"); return@launch
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
        handle(pick, fromVoice = true)
        return pick
    }

    fun voiceHints(): List<String> = (apps.map { it.title } + CommandParser.KNOWN_APPS.map { it.title }).distinct() +
        listOf("open", "volume", "mute", "pause", "play", "HDMI", "channel", "search", "turn off")

    fun handle(text: String, fromVoice: Boolean = false): Boolean {
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
        val parsed = CommandParser(apps).parse(t)
        if (parsed == null && fromVoice) {
            // Don't turn an unrecognised spoken command into a YouTube search; typed text still searches.
            say("Didn't understand \"$t\". To search, say \"search for …\"", error = true)
            return false
        }
        val cmd = parsed ?: Command.Search(t, null)
        run(cmd)
        return true
    }

    fun run(cmd: Command) {
        when (cmd) {
            is Command.Power -> if (cmd.on) powerOn() else { say("Turning off"); ssap("ssap://system/turnOff"); tvOff = true; foregroundApp = null }
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

    companion object {
        /** Screens that aren't "apps you use" and shouldn't count towards Most used. */
        val SYSTEM_APPS = setOf(
            "com.webos.app.home", "com.webos.app.livetv", "com.webos.surfacemanager", "com.webos.app.inputpicker",
            "com.webos.app.screensaver", "com.webos.app.settings", "com.palm.app.settings", "com.webos.app.notificationcenter",
        ) + (1..4).map { "com.webos.app.hdmi$it" }
    }
}
