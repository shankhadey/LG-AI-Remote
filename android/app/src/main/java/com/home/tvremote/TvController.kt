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
import com.home.tvremote.lg.WakeLoop
import com.home.tvremote.lg.WakeOnLan
import com.home.tvremote.voice.Command
import com.home.tvremote.voice.CommandParser
import com.home.tvremote.voice.TvApp
import com.home.tvremote.timer.TimerEngine
import com.home.tvremote.timer.TimerKind
import com.home.tvremote.picks.PicksController
import com.home.tvremote.picks.PicksStore
import com.home.tvremote.picks.TvActions
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

class TvController(app: Application) : AndroidViewModel(app), TvActions {
    private val prefs = app.getSharedPreferences("tv", Context.MODE_PRIVATE)
    /** "What should we watch?" (declared early: onForeground() forwards to it). */
    val picks = PicksController(PicksStore(app.getSharedPreferences("picks", Context.MODE_PRIVATE)), this, viewModelScope)

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

    /** True for the first second or so after opening, while we check whether the TV is on. */
    var checking by mutableStateOf(false)
    /** True while the wake-up signal is being sent and we wait for the TV. (Declared before init: refresh() reads it.) */
    var waking by mutableStateOf(false)
    /** After the app wakes the TV, open Home if it starts on Live TV. A setting, for people who watch Live TV. */
    var homeOnWake by mutableStateOf(prefs.getBoolean("homeOnWake", true))
    fun changeHomeOnWake(on: Boolean) { homeOnWake = on; prefs.edit().putBoolean("homeOnWake", on).apply() }
    @Volatile private var homeAfterWakeUntil = 0L

    init {
        viewModelScope.launch { for (job in queue) runCatching { job() }.onFailure { showError(it) } }
        // When a timer turns the TV off, show the "Turn on" screen instead of a dead remote.
        TimerEngine.onTvTurnedOff = { tvOff = true; foregroundApp = null; mediaState = null; tv?.close() }
        if (screen == Screen.Remote) refresh()
    }

    /**
     * On open/resume: a quick port check (under a second) decides between the remote and the
     * "Turn on" screen, instead of showing a remote that can't do anything while a full
     * connection attempt times out.
     */
    fun refresh() {
        if (checking || connecting || connected || waking) return
        val ip = prefs.getString("ip", null) ?: return
        if (System.currentTimeMillis() - offAt < 20_000) { tvOff = true; return }
        checking = true
        viewModelScope.launch {
            try {
                if (WakeOnLan.reachable(ip)) { tvOff = false; connect() } else tvOff = true
            } finally { checking = false }
        }
    }

    fun rename(name: String) {
        val n = name.trim().ifEmpty { return }
        tvName = n
        prefs.edit().putString("name", n).putBoolean("nameCustom", true).apply()
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
        prefs.edit().putString("ip", ip).putString("name", name).remove("nameCustom").apply()
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
    /** The TV connection object, created on first need (also from the wake loop, which used to skip this). */
    private fun ensureTv(ip: String): LgTv = tv ?: LgTv(
        ip, prefs.getString("clientKey", null),
        onClientKey = { prefs.edit().putString("clientKey", it).apply() },
        onDisconnect = { connected = false },
    ).also { tv = it }

    fun connect(pairing: Boolean = false) {
        val ip = prefs.getString("ip", null) ?: return
        if (connecting) return
        connecting = true
        viewModelScope.launch {
            try {
                val t = ensureTv(ip)
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
        if (!prefs.getBoolean("nameCustom", false)) viewModelScope.launch {
            // In the background so it never delays a button press.
            prefs.getString("ip", null)?.let { ip ->
                val n = Discovery.lookupName(getApplication(), ip)
                    ?: runCatching { t.request("ssap://system/getSystemInfo").optString("modelName") }.getOrNull()
                        ?.takeIf { it.isNotBlank() }?.let { "LG $it" }
                if (n != null && n != tvName) { tvName = n; prefs.edit().putString("name", n).apply() }
            }
        }
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
            // Play state of the app on screen, where the TV shares it (sharpens tap-to-pause).
            t.subscribe("ssap://com.webos.media/getForegroundAppInfo") { p ->
                val list = p.optJSONArray("foregroundAppInfo")
                val items = (0 until (list?.length() ?: 0)).map { list!!.getJSONObject(it) }
                val item = items.firstOrNull { it.optString("appId") == foregroundApp } ?: items.firstOrNull()
                mediaState = item?.optString("playState")?.takeIf { it == "playing" || it == "paused" }
            }
        }
        runCatching {
            // Tell us when a text box is focused on the TV, so typing/voice goes straight into it.
            t.subscribe("ssap://com.webos.service.ime/registerRemoteKeyboard") { p ->
                p.optJSONObject("currentWidget")?.let {
                    val open = it.optBoolean("focus", false)
                    val wasOpen = tvKeyboardOpen
                    tvKeyboardOpen = open
                    // A search box was just selected and something is waiting to be typed: type it and search.
                    val waiting = autoType
                    if (open && !wasOpen && waiting != null && System.currentTimeMillis() < autoTypeUntil) {
                        autoType = null
                        event("TYPE “$waiting” into the search box")
                        typeOnTv(waiting); enterOnTv(); say("Typed “$waiting”")
                    }
                }
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
        assumePlaying = true
        picks.onForeground(appId)
        // Just woken by the app and the TV came up on Live TV ("No signal" without an antenna): go Home.
        if (appId == "com.webos.app.livetv" && System.currentTimeMillis() < homeAfterWakeUntil) {
            homeAfterWakeUntil = 0
            button("HOME")
            say("Opened Home instead of Live TV")
        }
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
        waking -> "Turning on…"
        tvOff -> "Off"
        checking -> "Checking…"
        connecting && !connected -> "Connecting…"
        !connected -> "Not connected — tap to retry"
        foregroundApp == "com.webos.app.livetv" -> "Live TV"
        foregroundApp != null -> appTitle(foregroundApp!!)
        else -> "Connected"
    }

    /** The big "Turn on" button: connect if the TV is actually reachable, otherwise wake it. */
    /** The big "Turn on" button: send the wake signal immediately; the loop notices if the TV was already on. */
    fun turnOn() = powerOn()

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
    fun move(dx: Float, dy: Float) { lastPointAt = System.currentTimeMillis(); enqueue { tv().move(dx, dy) } }

    /** "playing" / "paused" when the TV reports it for the app on screen; null when it doesn't say. */
    var mediaState by mutableStateOf<String?>(null)
    private var lastPointAt = 0L
    private var assumePlaying = true

    /** Watching something (vs. browsing menus): taps pause/play instead of selecting. */
    val inPlayback: Boolean
        get() = mediaState != null || foregroundApp?.let { it !in SYSTEM_APPS } == true

    /**
     * Touchpad tap. While watching, a tap WITHOUT dragging first pauses/plays (the same command for
     * every app, unlike OK). Drag-then-tap still selects, so "Skip intro" and menus keep working.
     */
    fun tap() {
        val pointedRecently = System.currentTimeMillis() - lastPointAt < 3000
        if (!pointedRecently && inPlayback) togglePlayback() else sendTap(tapMethod)
    }

    private fun togglePlayback() {
        val pause = when (mediaState) { "playing" -> true; "paused" -> false; else -> assumePlaying }
        assumePlaying = !pause
        mediaState = mediaState?.let { if (pause) "paused" else "playing" }
        say(if (pause) "Paused" else "Playing")
        ssap(if (pause) "ssap://media.controls/pause" else "ssap://media.controls/play")
    }
    /**
     * What a touchpad tap sends. Some LG firmware ignores the pointer "click" message, so this is
     * selectable: "click" (pointer click), "ok" (the remote's OK button), "enter" (keyboard Enter).
     */
    var tapMethod by mutableStateOf(prefs.getString("tapMethod", "ok")!!)
    fun setTap(method: String) { tapMethod = method; prefs.edit().putString("tapMethod", method).apply() }
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
            app.launchExtras?.let { x -> x.keys().forEach { k -> payload.put(k, x.get(k)) }; event("LINK opening ${app.title} with $x") }
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

    // ---- TvActions (for the picker) -------------------------------------------
    override fun openApp(appId: String, title: String) = launch(TvApp(appId, title))
    override fun openTitle(appId: String, title: String, extras: JSONObject) = launch(TvApp(appId, title, launchExtras = extras))
    @Volatile private var autoType: String? = null
    @Volatile private var autoTypeUntil = 0L
    override fun typeWhenSearchOpens(text: String) { autoType = text; autoTypeUntil = System.currentTimeMillis() + 120_000 }

    override fun typeIntoSearch(text: String): Boolean {
        if (!tvKeyboardOpen) return false
        typeOnTv(text); enterOnTv(); return true
    }

    /** "Test opening a show": Netflix's search page for The Crown, one format at a time. */
    fun testNetflixSearch(variant: String) {
        picks.store.netflixSearch = variant
        event("LINK search test $variant")
        launch(TvApp("netflix", "Netflix", launchExtras = com.home.tvremote.picks.DeepLinks.netflixSearch("The Crown", variant)))
    }

    /** "Test opening a show": opens Stranger Things on Netflix with one link variant, and keeps it for Play. */
    fun testNetflixLink(variant: String) {
        picks.store.netflixLink = variant
        event("LINK test $variant")
        val (id, _) = com.home.tvremote.picks.DeepLinks.NETFLIX_TESTS.getValue(variant)
        launch(TvApp("netflix", "Netflix", launchExtras = com.home.tvremote.picks.DeepLinks.netflix(id, variant)))
    }
    override fun knownAppIds(): Set<String> = (apps.map { it.id } + usage.keys).toSet()
    override fun openOnTvBrowser(url: String) = enqueue {
        val t = tv()
        val ok = runCatching { t.request("ssap://system.launcher/open", JSONObject().put("target", url)) }.isSuccess ||
            runCatching { t.request("ssap://system.launcher/launch", JSONObject().put("id", "com.webos.app.browser").put("params", JSONObject().put("target", url))) }.isSuccess
        if (!ok) say("The TV didn't open the picks page (details in Diagnostics)", error = true)
    }
    override fun toastOnTv(message: String) = ssap("ssap://system.notifications/createToast", JSONObject().put("message", message))
    override fun goHome() = button("HOME")
    override val foreground: String? get() = foregroundApp
    override fun phoneIp(): String? = Discovery.phoneWifiIp(getApplication())
    override val tvIp: String? get() = prefs.getString("ip", null)
    override fun note(message: String, error: Boolean) = say(message, error)
    override fun pointerMove(dx: Float, dy: Float) = move(dx, dy)
    // On the picks page a real pointer click is what a web page understands (OK is a key press there).
    override fun pointerTap() = sendTap("click")
    override fun log(message: String) = event(message)
    override fun pointerScroll(dy: Float) = scroll(dy)

    fun diagnostics(): String = buildString {
        appendLine("App ${com.home.tvremote.BuildConfig.VERSION_NAME} · TV ${prefs.getString("ip", "?")} · connected=$connected")
        appendLine("MACs saved: ${prefs.getString("mac", null)?.split(',')?.size ?: 0}")
        appendLine("Apps listed by TV: ${apps.size} · on screen: $foregroundApp · play state: ${mediaState ?: "unknown"}")
        TimerEngine.state?.let { appendLine("Timer: ${it.kind} ends in ${TimerEngine.format(it.endsAt - System.currentTimeMillis())}") }
        apps.firstOrNull { it.title.contains("netflix", true) }?.let { appendLine("Netflix id: ${it.id}") }
        appendLine()
        events.forEach { appendLine(it) }
        tv?.history?.forEach { appendLine(it) }
        appendLine()
        append(picks.diagnostics())
    }


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
                    else -> turnOff()
                }
            } finally { powerBusy = false }
        }
    }

    /** Turn off, then drop the connection: the TV goes away without closing it, which left a stale socket. */
    private fun turnOff() {
        say("Turning off"); tvOff = true; foregroundApp = null; offAt = System.currentTimeMillis()
        enqueue {
            runCatching { tv().request("ssap://system/turnOff") }
            tv?.close(); connected = false
        }
    }

    /** When the app last turned the TV off: for a few seconds after, the shutting-down TV still answers. */
    @Volatile private var offAt = 0L

    /** Press-and-hold on the power button: always try to wake the TV. */
    fun forcePowerOn() { if (!waking) powerOn() }


    /** Seconds since the wake signal was first sent (shown on the Turn on screen). */
    var wakeSeconds by mutableStateOf(0)
    /** Wake-up timeline and other controller events, for Diagnostics. */
    private val events = java.util.concurrent.ConcurrentLinkedDeque<String>()
    private fun event(msg: String) {
        events.addFirst(java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date()) + " " + msg)
        while (events.size > 60) events.pollLast()
    }

    private fun powerOn() {
        val macs = prefs.getString("mac", null)?.split(',')?.filter { it.isNotBlank() }.orEmpty()
        val ip = prefs.getString("ip", null)
        if (waking) return
        if (macs.isEmpty()) {
            // Can't wake it, but it may already be on.
            viewModelScope.launch {
                if (ip != null && WakeOnLan.reachable(ip)) { tvOff = false; connect() }
                else say("To turn the TV on I need its MAC address — add it from the menu under the TV name.", error = true)
            }
            return
        }
        waking = true
        wakeSeconds = 0
        tv?.close(); connected = false
        if (homeOnWake) homeAfterWakeUntil = System.currentTimeMillis() + 90_000
        viewModelScope.launch {
            try {
                val loop = WakeLoop(
                    sendWake = { WakeOnLan.send(getApplication(), macs, ip) },
                    reachable = { ip != null && WakeOnLan.reachable(ip, 400) },
                    // Short attempts: just after waking, the TV accepts connections before its control
                    // service is ready. The loop creates the connection itself (no hand-off).
                    connectOnce = { ip != null && run { withTimeout(2500) { ensureTv(ip).connect() }; true } },
                    powerState = { withTimeout(2000) { tv?.request("ssap://com.webos.service.tvpower/power/getPowerState")?.optString("state") } },
                    reset = { tv?.close() },
                    now = System::currentTimeMillis,
                    log = ::event,
                    onTick = { wakeSeconds = it },
                )
                when (val r = loop.run()) {
                    is WakeLoop.Result.On -> {
                        // Mark on *before* leaving the turning-on state, so the screen goes straight to the remote.
                        connected = true; tvOff = false; waking = false
                        say("TV is on")
                        tv?.let { afterConnect(it) }
                    }
                    is WakeLoop.Result.GaveUp -> when {
                        r.packetsSent == 0 -> say("This phone couldn't send the wake-up signal (${r.lastError?.message ?: "no Wi-Fi?"}). Make sure the phone is on Wi-Fi.", error = true)
                        else -> say("The TV didn't respond to the wake-up signal. See Power-on settings (menu under the TV name) for what to check.", error = true)
                    }
                }
            } finally { waking = false }
        }
    }

    fun startTimer(kind: TimerKind, minutes: Int) {
        TimerEngine.start(getApplication(), kind, minutes)
        say(if (kind == TimerKind.TurnOff) "TV turns off in $minutes min" else "$minutes-minute timer on the TV")
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
            // Rules didn't understand: let the AI try (a person, a description, a mood). Never a silent YouTube search.
            say("Thinking…")
            viewModelScope.launch {
                picks.interpret(t)?.let { say("$it. To search, say “search for …”", error = true) }
            }
            return true
        }
        val cmd = parsed ?: Command.Search(t, null)
        run(cmd)
        return true
    }

    fun run(cmd: Command) {
        when (cmd) {
            is Command.Power -> if (cmd.on) powerOn() else turnOff()
            is Command.VolumeStep -> {
                say(if (cmd.up) "Louder" else "Quieter")
                enqueue { repeat(cmd.steps) { tv().request(if (cmd.up) "ssap://audio/volumeUp" else "ssap://audio/volumeDown"); delay(40) } }
            }
            is Command.VolumeSet -> { say("Volume ${cmd.level}"); ssap("ssap://audio/setVolume", JSONObject().put("volume", cmd.level)) }
            is Command.Mute -> { say(if (cmd.mute) "Muted" else "Sound on"); ssap("ssap://audio/setMute", JSONObject().put("mute", cmd.mute)) }
            is Command.Media -> { if (cmd.action == "pause") assumePlaying = false else if (cmd.action == "play") assumePlaying = true; say(cmd.action.replaceFirstChar { it.uppercase() }.replace("Forward", " forward")); ssap("ssap://media.controls/${cmd.action}") }
            is Command.Button -> button(cmd.name)
            is Command.ChannelStep -> { say(if (cmd.up) "Channel up" else "Channel down"); ssap(if (cmd.up) "ssap://tv/channelUp" else "ssap://tv/channelDown") }
            is Command.ChannelNumber -> { say("Channel ${cmd.number}"); ssap("ssap://tv/openChannel", JSONObject().put("channelNumber", cmd.number)) }
            is Command.Input -> { say("HDMI ${cmd.hdmi}"); ssap("ssap://system.launcher/launch", JSONObject().put("id", "com.webos.app.hdmi${cmd.hdmi}")) }
            is Command.OpenApp -> launch(cmd.app)
            is Command.Search -> if (!cmd.title || cmd.app?.id?.startsWith("youtube") == true) search(cmd.query, cmd.app) else viewModelScope.launch {
                // "play / watch X": a show or film on their services first.
                say("Looking for ${cmd.query}…")
                val hint = cmd.app?.let { a -> com.home.tvremote.picks.Catalog.SERVICES.firstOrNull { s -> picks.appIdFor(s) == a.id }?.name }
                when (val r = picks.findAndOpen(cmd.query, hint)) {
                    com.home.tvremote.picks.PicksController.Found.Opened -> {}
                    is com.home.tvremote.picks.PicksController.Found.Elsewhere -> say(picks.elsewhere(r), error = true)
                    com.home.tvremote.picks.PicksController.Found.Nothing -> {
                        // Not a title: maybe a person or a description. Last resort: YouTube, saying so.
                        val msg = picks.interpret(cmd.query)
                        if (msg != null) { search(cmd.query, cmd.app); say("Not a show or film on your services — searching YouTube") }
                    }
                }
            }
            is Command.NoSuchApp -> viewModelScope.launch {
                // "open X" where X isn't an app: maybe it's a show or film, a person, or a description.
                say("Looking for ${cmd.name}…")
                when (val r = picks.findAndOpen(cmd.name)) {
                    com.home.tvremote.picks.PicksController.Found.Opened -> {}
                    is com.home.tvremote.picks.PicksController.Found.Elsewhere -> say(picks.elsewhere(r), error = true)
                    com.home.tvremote.picks.PicksController.Found.Nothing ->
                        picks.interpret("open ${cmd.name}")?.let { say("Couldn't find an app or a show called “${cmd.name}” on your services", error = true) }
                }
            }
            is Command.MaybeApp -> viewModelScope.launch {
                // Sounds a bit like an app, but an exact show or film title wins ("open Severance" isn't Settings).
                if (picks.findAndOpen(cmd.spoken, exactOnly = true) != com.home.tvremote.picks.PicksController.Found.Opened) launch(cmd.app)
            }
            is Command.SleepTimer -> startTimer(TimerKind.TurnOff, cmd.minutes)
            is Command.ScreenTimer -> startTimer(TimerKind.OnScreen, cmd.minutes)
            Command.CancelTimer -> if (TimerEngine.state != null) { TimerEngine.cancel(getApplication()); say("Timer cancelled") } else say("No timer is running")
            is Command.WhatToWatch -> { picks.openPicker(); if (cmd.text != null && picks.step == com.home.tvremote.picks.Step.Choose) picks.ask(cmd.text) }
            is Command.ExtendTimer -> if (TimerEngine.state != null) { TimerEngine.extend(getApplication(), cmd.minutes); say("Added ${cmd.minutes} minutes") } else say("No timer is running")
        }
    }

    private fun search(query: String, app: TvApp?) {
        val target = app ?: apps.firstOrNull { it.id.startsWith("youtube") } ?: TvApp("youtube.leanback.v4", "YouTube")
        if (target.id.startsWith("youtube")) {
            say("Searching YouTube for \"$query\"")
            val url = "https://www.youtube.com/tv?launch=voice&vq=" + URLEncoder.encode(query, "UTF-8")
            ssap("ssap://system.launcher/launch", JSONObject().put("id", target.id).put("params", JSONObject().put("contentTarget", url)))
        } else if (target.id == "netflix") {
            say("Searching Netflix for “$query”")
            launch(target.copy(launchExtras = com.home.tvremote.picks.DeepLinks.netflixSearch(query, picks.store.netflixSearch)))
        } else {
            // No search link for this app yet: open it and type the words as soon as its search box is selected.
            launch(target)
            typeWhenSearchOpens(query)
            say("Opened ${target.title}. Select its search box and “$query” is typed for you.")
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
            "com.webos.app.browser", // the timer / picks pages: taps should select there, not pause
        ) + (1..4).map { "com.webos.app.hdmi$it" }
    }
}
