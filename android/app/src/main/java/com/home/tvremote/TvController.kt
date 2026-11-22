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
import com.home.tvremote.tv.LgDriver
import com.home.tvremote.tv.TvDriver
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
    /** "LG" or "Samsung": chosen at setup (found by discovery), kept with the TV. */
    val brand: String get() = prefs.getString("brand", "LG") ?: "LG"
    /** Wake-up timeline and other events for Diagnostics. Declared early: init code below logs to it. */
    private val events = java.util.concurrent.ConcurrentLinkedDeque<String>(
        // Kept across app restarts, so Diagnostics shows what happened before the app was reopened.
        prefs.getString("events", "").orEmpty().split('\n').filter { it.isNotBlank() })
    /** "What should we watch?" (declared early: onForeground() forwards to it). */
    val picks = PicksController(PicksStore(app.getSharedPreferences("picks", Context.MODE_PRIVATE)), this, viewModelScope)

    var screen by mutableStateOf(if (prefs.getString("ip", null) != null) Screen.Remote else Screen.Setup)
    var tvName by mutableStateOf(prefs.getString("name", "$brand TV")!!)
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
    /** An optional action on the message, e.g. "Fix" → the setting that fixes it. */
    var feedbackAction by mutableStateOf<Pair<String, () -> Unit>?>(null)
    /** The Settings screen (replaces the menu under the TV name) and its Help page. */
    var showSettings by mutableStateOf(false)
    var showHelp by mutableStateOf(false)
    private var feedbackJob: Job? = null

    private var tv: TvDriver? = null
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
                val t0 = System.currentTimeMillis()
                val up = net.reachable(ip, 500)
                event("CHECK TV ${if (up) "reachable" else "not reachable"} (${System.currentTimeMillis() - t0} ms)")
                // Reachable doesn't mean on: with Quick Start+ an off TV stays on the network. connect() asks the TV.
                if (up) connect() else tvOff = true
            } finally { checking = false }
        }
    }

    /** Bumped by every connection attempt and by turning on/off, so a slow, older attempt can't overwrite newer state. */
    @Volatile private var connEpoch = 0
    /** Whether the app is on screen (reconnect only then). */
    @Volatile var appVisible = false
    private var reconnectJob: kotlinx.coroutines.Job? = null

    /** The connection dropped by itself: try again a few times while the app is open (1.5 s, 3 s, 6 s, 12 s, 24 s). */
    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = viewModelScope.launch {
            var wait = 1500L
            repeat(5) {
                delay(wait); wait *= 2
                if (connected || waking || connecting || !appVisible) return@launch
                event("RECONNECT try ${it + 1}")
                refresh()
                delay(3000)
                if (connected || tvOff) return@launch
            }
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
                if (found.isEmpty()) scanReport = Discovery.lastReport.ifEmpty { "No TV found." } +
                    " If the TV's address isn't in that range, the phone and TV are on different networks (e.g. a guest network). You can still enter the TV's IP below."
                else scanReport = null
            } catch (e: Exception) { showError(e) }
            scanning = false
        }
    }

    /** Pair with the TV at [ip]. [brand] comes from discovery; for an address typed in by hand it's asked from the TV. */
    fun choose(ip: String, name: String, brand: String? = null) = viewModelScope.launch {
        val b = brand ?: Discovery.brandAt(ip)
        if (ip != prefs.getString("ip", null) || b != this@TvController.brand) { prefs.edit().remove("clientKey").remove("mac").apply(); macKnown = false }
        prefs.edit().putString("ip", ip).putString("brand", b).putString("name", if (brand == null) "$b TV" else name).remove("nameCustom").apply()
        tvName = prefs.getString("name", name) ?: name
        tv?.dispose(); tv = null
        connect(pairing = true)
    }

    // ---- using a different TV: the current one is kept until the new one has paired ----
    private val TV_KEYS = listOf("ip", "clientKey", "mac", "name", "brand", "nameCustom")
    /** A TV to go back to from setup (the one in use before "Use a different TV"). */
    var canCancelSetup by mutableStateOf(prefs.contains("prev_ip"))
        private set

    /** "Use a different TV": search for another TV. Nothing is forgotten yet. */
    fun findAnotherTv() {
        if (!prefs.contains("prev_ip") && prefs.contains("ip")) prefs.edit().apply {
            for (k in TV_KEYS) prefs.all[k]?.let { v -> if (v is Boolean) putBoolean("prev_$k", v) else putString("prev_$k", v.toString()) }
        }.apply()
        canCancelSetup = prefs.contains("prev_ip")
        event("SETUP looking for a different TV (current one kept)")
        found.clear(); scanReport = null
        screen = Screen.Setup
    }

    /** Back from setup: return to the TV in use before, as it was. */
    fun cancelSetup() {
        if (!prefs.contains("prev_ip")) return
        tv?.dispose(); tv = null
        prefs.edit().apply {
            for (k in TV_KEYS) {
                remove(k)
                prefs.all["prev_$k"]?.let { v -> if (v is Boolean) putBoolean(k, v) else putString(k, v.toString()) }
                remove("prev_$k")
            }
        }.apply()
        canCancelSetup = false
        tvName = prefs.getString("name", "$brand TV") ?: "$brand TV"
        macKnown = prefs.getString("mac", null) != null
        event("SETUP cancelled, back to ${prefs.getString("ip", "?")}")
        screen = Screen.Remote
        connect()
    }

    /** The new TV has paired: the previous one is no longer needed. */
    private fun dropPreviousTv() {
        if (!prefs.contains("prev_ip")) return
        prefs.edit().apply { for (k in TV_KEYS) remove("prev_$k") }.apply()
        canCancelSetup = false
    }

    // ---- connection -------------------------------------------------------
    /** The TV connection object, created on first need (also from the wake loop, which used to skip this). */
    private fun ensureTv(ip: String): TvDriver = tv ?: net.driver(
        brand, ip, prefs.getString("clientKey", null),
        onClientKey = { prefs.edit().putString("clientKey", it).apply() },
        onDisconnect = {
            connected = false
            event("DROPPED connection")
            if (!waking && System.currentTimeMillis() - offAt > 20_000) scheduleReconnect()
        },
    ).also { tv = it }

    fun connect(pairing: Boolean = false) {
        val ip = prefs.getString("ip", null) ?: return
        if (connecting) return
        connecting = true
        val epoch = ++connEpoch
        val t0 = System.currentTimeMillis()
        viewModelScope.launch {
            try {
                val t = ensureTv(ip)
                val firstTime = prefs.getString("clientKey", null) == null
                t.connect(onPrompt = { screen = Screen.Pairing })
                if (epoch != connEpoch && connected) return@launch   // a newer attempt (e.g. turning on) already won
                connected = true
                // Ask the TV, don't guess: with Quick Start+ the app can connect to a TV that is in standby.
                val state = runCatching { withTimeout(3000) { t.powerState() } }.getOrNull()
                event("CONNECT ok in ${System.currentTimeMillis() - t0} ms, TV says ${state ?: "?"}")
                // Turned off (or a newer attempt began) while we waited for that answer: it's stale, don't apply it.
                if (epoch != connEpoch) return@launch
                tvOff = state in STANDBY_STATES
                screen = Screen.Remote
                if (firstTime || pairing) {
                    dropPreviousTv()
                    runCatching { t.toast(com.home.tvremote.i18n.I18n.t("Phone remote connected")) }
                    say("Connected to $tvName")
                }
                // Setting up subscriptions can take seconds on a slow TV; a new connect (e.g. a different TV) mustn't wait.
                connecting = false
                if (epoch == connEpoch) afterConnect(t)
            } catch (e: Exception) {
                event("CONNECT failed after ${System.currentTimeMillis() - t0} ms: ${e.message?.take(80)}")
                if (epoch != connEpoch) return@launch   // stale: something newer (turning on, a later connect) is in charge
                connected = false
                if (screen == Screen.Pairing) screen = if (prefs.getString("clientKey", null) == null) Screen.Setup else Screen.Remote
                // Paired before, and now unreachable or silent = switched off (with Quick Start+ a TV in standby takes the
                // connection but never answers): show the "Turn on" screen instead of an error.
                val silent = e.message?.let { it.startsWith("Can't reach") || it.startsWith("No answer") } == true
                if (silent && prefs.getString("clientKey", null) != null) tvOff = true
                else showError(e)
            } finally { connecting = false }
        }
    }

    private suspend fun afterConnect(t: TvDriver) {
        // Tell us when the TV turns on or off by any remote (with Quick Start+ the connection survives standby).
        runCatching {
            t.onPower { st ->
                val off = st in STANDBY_STATES
                if (off != tvOff && !waking) {
                    event("POWER TV says $st")
                    if (!off && System.currentTimeMillis() - offAt < 3000) return@onPower   // still shutting down
                    tvOff = off
                }
            }
        }
        viewModelScope.launch { com.home.tvremote.analytics.Analytics.log(com.home.tvremote.analytics.Analytics.Event.TvConnected, "brand" to t.brand, "model" to runCatching { t.modelName() }.getOrNull()) }
        if (!prefs.getBoolean("nameCustom", false)) viewModelScope.launch {
            // In the background so it never delays a button press.
            prefs.getString("ip", null)?.let { ip ->
                val n = net.lookupName(getApplication(), ip)
                    ?: runCatching { t.modelName() }.getOrNull()
                        ?.takeIf { it.isNotBlank() }?.let { "${t.brand} $it" }
                if (n != null && n != tvName) { tvName = n; prefs.edit().putString("name", n).apply() }
            }
        }
        runCatching {
            val list = runCatching { t.installedApps() }.getOrNull()
            if (list == null) {
                // Newer LG firmware doesn't let this kind of pairing read the app list: show the common apps instead.
                if (apps.isEmpty()) apps.addAll(CommandParser.knownApps(t.brand).filter { a -> !a.id.startsWith("com.webos") })
            } else { apps.clear(); apps.addAll(list) }
        }
        runCatching {
            // What's on screen: drives the status line, Live TV channel buttons and "Most used".
            t.onForeground { onForeground(it) }
        }
        runCatching { t.foregroundApp()?.let { onForeground(it) } }
        runCatching {
            // Play state of the app on screen, where the TV shares it (sharpens tap-to-pause).
            t.onMedia { items ->
                // Only the app in front counts, and only a real app: the LG Home screen plays banner videos and a
                // live-TV preview tile (com.webos.app.livedmost), which were taken for "watching" (22 Nov, 1.2.1).
                val front = foregroundApp
                val item = items.firstOrNull { it.first == front }?.takeIf { front != null && front !in SYSTEM_APPS }
                mediaState = item?.second?.takeIf { it == "playing" || it == "paused" }
            }
        }
        runCatching {
            // Tell us when a text box is focused on the TV, so typing/voice goes straight into it.
            t.onKeyboard { open ->
                run {
                    val wasOpen = tvKeyboardOpen
                    tvKeyboardOpen = open
                    // Logged so Diagnostics shows whether an app's search box uses the TV's keyboard at all
                    // (apps that draw their own letter grid never report it, so auto-type can't work there).
                    if (open != wasOpen) event("KEYBOARD ${if (open) "opened" else "closed"} on the TV (in front: ${foregroundApp ?: "?"})")
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
            val fresh = t.macAddresses()
            if (fresh.isEmpty()) Log.w("TvRemote", "TV reported no MAC addresses")
            val merged = mergeMacs(prefs.getString("mac", null).orEmpty().split(',') + fresh)
            if (merged.isNotEmpty()) { prefs.edit().putString("mac", merged.joinToString(",")).apply(); macKnown = true }
        }.onFailure { Log.w("TvRemote", "Couldn't read TV MAC: ${it.message}") }
    }

    private fun onForeground(appId: String) {
        if (appId.isEmpty() || appId == foregroundApp) return
        foregroundApp = appId
        assumePlaying = true
        picks.onForeground(appId)
        // Left the app we opened a title in: stop showing that title on the remote.
        if (picks.nowShowingApp != null && appId != picks.nowShowingApp && appId !in SYSTEM_APPS.minus("com.webos.app.browser")) { picks.nowShowing = null; picks.nowShowingApp = null }
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
        val defaults = CommandParser.knownApps(brand).filter { !it.id.startsWith("com.webos") }
        return (learned + defaults).distinctBy { it.id }.take(4)
    }

    fun appTitle(id: String): String =
        (CommandParser.knownApps(brand) + CommandParser.KNOWN_APPS).firstOrNull { it.id == id }?.title
            ?: apps.firstOrNull { it.id == id && it.title.isNotBlank() }?.title
            ?: when {
                id == "com.wbd.stream" -> "Max"
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

    private suspend fun tv(): TvDriver {
        val t = tv ?: throw IllegalStateException("No TV set up")
        if (!t.isConnected) { t.connect(); connected = true; afterConnect(t) }
        return t
    }

    private fun enqueue(job: suspend () -> Unit) { queue.trySend(job) }

    // ---- direct controls --------------------------------------------------
    fun button(name: String) = enqueue { tv().button(name) }
    fun volume(up: Boolean) = enqueue { tv().volume(up) }
    fun channel(up: Boolean) = enqueue { tv().channel(up) }
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
        when {
            !pointedRecently && inPlayback -> togglePlayback()
            // Pointed at something while watching (e.g. the timeline): OK would just pause, so click where the pointer is.
            inPlayback -> sendTap("click")
            else -> sendTap(tapMethod)
        }
    }

    private fun togglePlayback() {
        val pause = when (mediaState) { "playing" -> true; "paused" -> false; else -> assumePlaying }
        assumePlaying = !pause
        mediaState = mediaState?.let { if (pause) "paused" else "playing" }
        say(if (pause) "Paused" else "Playing")
        enqueue { tv().media(if (pause) "pause" else "play") }
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
            "enter" -> tv().sendEnter()
            else -> tv().click()
        }
    }
    fun scroll(dy: Float) = enqueue { tv().scroll(dy) }

    /**
     * Skip while watching: streaming apps skip ~10 s per left/right arrow during playback, so an amount becomes
     * that many presses (capped at 10 minutes).
     */
    fun seek(seconds: Int) {
        val presses = (kotlin.math.abs(seconds) + 5) / 10
        if (presses == 0) return
        val key = if (seconds > 0) "RIGHT" else "LEFT"
        say(if (seconds > 0) "Forward ${humanTime(seconds)}" else "Back ${humanTime(-seconds)}")
        enqueue { repeat(presses.coerceAtMost(60)) { tv().button(key); delay(180) } }
    }
    /** "Barbie is on Max — not one of your services" with "Add" when it's a service the app knows. */
    private fun sayElsewhere(r: com.home.tvremote.picks.PicksController.Found.Elsewhere) {
        val known = r.services.firstNotNullOfOrNull { n -> com.home.tvremote.picks.Catalog.SERVICES.firstOrNull { s -> s.tmdbNames.any { it.equals(n, true) } }?.name }
        if (known != null) say("${r.name} is on $known — not one of your services", error = true, action = "Add" to { openPicksSettings() })
        else say(picks.elsewhere(r), error = true)
    }
    fun openPicksSettings() { showSettings = false; picks.open = true; picks.step = com.home.tvremote.picks.Step.Setup }

    /** One 10-second skip (double-tap on the touchpad's side while watching). */
    fun skip(forward: Boolean) = button(if (forward) "RIGHT" else "LEFT")
    private fun humanTime(s: Int) = if (s >= 60 && s % 60 == 0) "${s / 60} min" else if (s >= 60) "${s / 60} min ${s % 60} s" else "$s s"
    /**
     * Opens an app and checks it actually came to the front. If the usual launch call is rejected
     * or silently ignored, retry through the application manager, and report what the TV said.
     */
    fun launch(app: TvApp) {
        say("Opening ${app.title}")
        // Runs beside the command queue (not in it) so checking the result never holds up other buttons.
        viewModelScope.launch {
            val t = runCatching { tv() }.getOrElse { showError(it); return@launch }
            app.launchExtras?.let { x -> event("LINK opening ${app.title} with $x") }
            val first = runCatching { t.launch(app.id, app.launchExtras) }
            if (first.isSuccess && waitForForeground(t, app.id)) return@launch
            val second = runCatching { t.launch(app.id, alternative = true) }
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
    private suspend fun waitForForeground(t: TvDriver, appId: String): Boolean {
        repeat(8) {
            delay(500)
            val fg = runCatching { t.foregroundApp() }
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
    override fun typeWhenSearchOpens(text: String) {
        autoType = text; autoTypeUntil = System.currentTimeMillis() + 120_000
        event("TYPE waiting for a search box to type “$text” (2 min)")
    }

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

    /** "Test opening a show": opens a well-known title in [service] with one link format, and keeps that format. */
    fun testLink(service: String, variant: String) {
        val dl = com.home.tvremote.picks.DeepLinks
        picks.store.setLinkVariant(service, variant)
        event("LINK test $service $variant")
        val (id, name) = dl.tests(service).getValue(variant)
        val appId = com.home.tvremote.picks.Catalog.SERVICES.firstOrNull { it.name == service }?.let { picks.appIdFor(it) }
            ?: when (service) { "Prime Video" -> "amazon"; "Disney+" -> "com.disney.disneyplus-prod"; else -> "netflix" }
        launch(TvApp(appId, service, launchExtras = dl.forService(service, id, "movie:0", name, variant)))
    }
    fun testNetflixLink(variant: String) = testLink("Netflix", variant)
    override fun knownAppIds(): Set<String> = (apps.map { it.id } + usage.keys).toSet()
    override fun openOnTvBrowser(url: String) = enqueue {
        val ok = tv().openUrl(url)
        if (!ok) say("The TV didn't open the picks page (details in Diagnostics)", error = true)
    }
    override fun toastOnTv(message: String) = enqueue { tv().toast(com.home.tvremote.i18n.I18n.t(message)) }
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

    /** "Start fresh": empty the log, so the next Share holds only what happens from now. */
    fun clearDiagnostics() {
        events.clear(); prefs.edit().putString("events", "").apply()
        tv?.history?.clear()
        picks.store.diagnosticsSince = System.currentTimeMillis()
        event("Diagnostics started fresh")
    }

    fun diagnostics(): String = buildString {
        appendLine("App ${com.home.tvremote.BuildConfig.VERSION_NAME} · TV ${prefs.getString("ip", "?")} · connected=$connected")
        appendLine("MACs saved: ${prefs.getString("mac", null)?.split(',')?.size ?: 0}")
        appendLine("Apps listed by TV: ${apps.size} · on screen: $foregroundApp · play state: ${mediaState ?: "unknown"}")
        TimerEngine.state?.let { appendLine("Timer: ${it.kind} ends in ${TimerEngine.format(it.endsAt - System.currentTimeMillis())}") }
        apps.firstOrNull { it.title.contains("netflix", true) }?.let { appendLine("Netflix id: ${it.id}") }
        appendLine("Usage sharing: ${when (com.home.tvremote.analytics.Analytics.consent) { true -> "on"; false -> "off"; null -> "not asked" }}" +
            (if (com.home.tvremote.analytics.Analytics.active) "" else " (not set up in this build)") + com.home.tvremote.analytics.Analytics.recent.take(5).joinToString("") { "\n  $it" })
        appendLine()
        events.forEach { appendLine(it) }
        tv?.history?.forEach { appendLine(it) }
        appendLine()
        append(picks.diagnostics())
    }


    fun toggleMute() = enqueue {
        val muted = tv().isMuted()
        tv().setMute(!muted)
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
                val reachable = (t?.isConnected == true) || (ip != null && net.reachable(ip, 500))
                if (!reachable) { powerOn(); return@launch }
                say("Checking the TV…")
                if (t != null && !t.isConnected) {
                    runCatching { withTimeout(6000) { t.connect() }; connected = true }
                }
                val state = if (t?.isConnected == true) withTimeoutOrNull(3000) {
                    runCatching { t.powerState() }.getOrNull()
                } else null
                when (state) {
                    "Screen Off" -> { say("Turning the screen on"); enqueue { tv().turnOnScreen() } }
                    "Active Standby", "Suspend", "Power Off" -> powerOn()
                    // "Active", or the TV answered but didn't say: it's on, so turn it off.
                    else -> turnOff()
                }
            } finally { powerBusy = false }
        }
    }

    /** Turn off, then drop the connection: the TV goes away without closing it, which left a stale socket. */
    private fun turnOff() {
        say("Turning off"); tvOff = true; foregroundApp = null; offAt = System.currentTimeMillis(); connEpoch++
        enqueue {
            runCatching { tv().turnOff() }
            tv?.close(); connected = false
        }
    }

    /** When the app last turned the TV off: for a few seconds after, the shutting-down TV still answers. */
    @Volatile private var offAt = 0L

    /** Press-and-hold on the power button: always try to wake the TV. */
    fun forcePowerOn() { if (!waking) powerOn() }


    /** Seconds since the wake signal was first sent (shown on the Turn on screen). */
    var wakeSeconds by mutableStateOf(0)
    fun appEvent(msg: String) = event(msg)

    init { com.home.tvremote.analytics.Analytics.init(app); com.home.tvremote.analytics.Analytics.setProfile(picks.store.country, com.home.tvremote.i18n.Regions.resolve(picks.store.appLanguage)) }
    init { picks.commandHandler = ::pickerSpeech; com.home.tvremote.i18n.I18n.lang = com.home.tvremote.i18n.Regions.resolve(picks.store.appLanguage); com.home.tvremote.i18n.I18n.country = picks.store.country }

    // ---- anonymous usage (asked once; Settings › Privacy) ----
    var usageConsent by mutableStateOf(com.home.tvremote.analytics.Analytics.consent)
        internal set
    /** The one-time question shows on the remote once the TV is connected and the user hasn't answered. */
    val askUsage get() = usageConsent == null && connected && !tvOff && usageAvailable
    /** Only ask (and show the setting) when this build can actually send: Firebase set up. */
    val usageAvailable get() = com.home.tvremote.analytics.Analytics.active || forceUsageUi
    internal var forceUsageUi = false   // screenshots of the design
    fun changeUsageConsent(on: Boolean) {
        com.home.tvremote.analytics.Analytics.setConsent(getApplication(), on); usageConsent = on
        if (on) com.home.tvremote.analytics.Analytics.setProfile(picks.store.country, com.home.tvremote.i18n.Regions.resolve(picks.store.appLanguage))
        event("SETTINGS usage sharing ${if (on) "on" else "off"}")
    }

    // ---- language and country ----
    var appLanguage by mutableStateOf(picks.store.appLanguage)
        private set
    var country by mutableStateOf(picks.store.country)
        private set
    fun changeAppLanguage(setting: String) {
        picks.store.appLanguage = setting; appLanguage = setting
        com.home.tvremote.i18n.I18n.lang = com.home.tvremote.i18n.Regions.resolve(setting)
        picks.regionChanged()   // titles and reasons come in the app's language
        event("SETTINGS language $setting")
    }
    fun changeCountry(code: String) {
        picks.store.country = code; country = code; com.home.tvremote.i18n.I18n.country = code
        picks.regionChanged()
        event("SETTINGS country $code")
    }

    /** Wake-up timeline and other controller events, for Diagnostics. */

    private fun event(msg: String) {
        events.addFirst(java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date()) + " " + msg)
        while (events.size > 60) events.pollLast()
        prefs.edit().putString("events", events.joinToString("\n")).apply()
    }

    private fun powerOn() {
        val macs = prefs.getString("mac", null)?.split(',')?.filter { it.isNotBlank() }.orEmpty()
        val ip = prefs.getString("ip", null)
        if (waking) return
        if (macs.isEmpty()) {
            // Can't wake it, but it may already be on.
            viewModelScope.launch {
                if (ip != null && net.reachable(ip, 500)) { tvOff = false; connect() }
                else say("To turn the TV on I need its MAC address — add it from the menu under the TV name.", error = true)
            }
            return
        }
        waking = true
        wakeSeconds = 0
        connEpoch++   // any connection attempt still running is now stale
        tv?.close(); connected = false
        if (homeOnWake) homeAfterWakeUntil = System.currentTimeMillis() + 90_000
        viewModelScope.launch {
            try {
                val loop = WakeLoop(
                    sendWake = { net.wake(getApplication(), macs, ip) },
                    reachable = { ip != null && net.reachable(ip, 400) },
                    // Short attempts: just after waking, the TV accepts connections before its control
                    // service is ready. The loop creates the connection itself (no hand-off).
                    connectOnce = { ip != null && run { withTimeout(2500) { ensureTv(ip).connect() }; true } },
                    powerState = { withTimeout(2000) { tv?.powerState() } },
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
                        com.home.tvremote.analytics.Analytics.log(com.home.tvremote.analytics.Analytics.Event.TurnOnFromPhone, "result" to "on", "brand" to brand)
                        tv?.let { afterConnect(it) }
                    }
                    is WakeLoop.Result.GaveUp -> {
                        com.home.tvremote.analytics.Analytics.log(com.home.tvremote.analytics.Analytics.Event.TurnOnFromPhone, "result" to "gave_up", "brand" to brand)
                        when {
                            r.packetsSent == 0 -> say("This phone couldn't send the wake-up signal (${r.lastError?.message ?: "no Wi-Fi?"}). Make sure the phone is on Wi-Fi.", error = true)
                            else -> say("The TV didn't respond to the wake-up signal. See Power-on settings (menu under the TV name) for what to check.", error = true)
                        }
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
    fun typeOnTv(text: String) = enqueue { tv().typeText(text) }
    fun enterOnTv() = enqueue { tv().sendEnter() }

    // ---- voice / search bar -------------------------------------------------
    /** Handles anything said or typed. Returns false if it wasn't understood. */
    /** Voice gives several guesses; use the first one that's a real command (else the top guess). */
    fun handleSpoken(guesses: List<String>): String {
        val parser = CommandParser(apps)
        if (guesses.size > 1) event("HEARD ${guesses.take(4).joinToString(" | ")}")
        alternatives = guesses
        val understood = guesses.firstOrNull { g -> parser.parse(g).let { it != null && it !is Command.NoSuchApp } }
        if (understood == null) CommandParser.powerFromGuesses(guesses, tvOff)?.let { on ->
            event("VOICE power ${if (on) "on" else "off"} from mangled guesses")
            run(Command.Power(on)); return guesses.first()
        }
        val pick = understood ?: guesses.first()
        handle(pick, fromVoice = true)
        return pick
    }

    /** The recognizer's other guesses for what was just said ("open chrome" may also have been "open the crown"). */
    @Volatile private var alternatives: List<String> = emptyList()

    /** Title names to try for "open X": what was parsed first, then the same phrase from the other guesses. */
    private fun titleCandidates(name: String): List<String> {
        val parser = CommandParser(apps)
        val others = alternatives.mapNotNull { g ->
            when (val c = parser.parse(g)) {
                is Command.NoSuchApp -> c.name; is Command.MaybeApp -> c.spoken
                is Command.Search -> c.query.takeIf { c.title }
                null -> g.removePrefix("open ").removePrefix("play ").removePrefix("watch ")
                else -> null
            }
        }
        val primary = name.trim()
        return (listOf(primary) + others).map { it.trim().trimStart('(', '"', '\'', '-', ' ') }.filter { it.isNotEmpty() }
            .filter { it == primary || !primary.lowercase().contains(it.lowercase()) }
            .distinctBy { it.lowercase() }.take(4)
    }

    /** Picker voice: anything that isn't a request for recommendations runs as a normal command. */
    private fun pickerSpeech(guesses: List<String>): Boolean {
        alternatives = guesses
        val parser = CommandParser(apps)
        val cmd = guesses.firstNotNullOfOrNull { g -> parser.parse(g)?.takeIf { it !is Command.NoSuchApp } }
            ?: CommandParser.powerFromGuesses(guesses, tvOff)?.let { Command.Power(it) } ?: parser.parse(guesses.first())
        if (cmd == null || cmd is Command.WhatToWatch) return false
        event("PICKER voice “${guesses.first()}” → ${cmd::class.simpleName}")
        val opens = cmd is Command.OpenApp || cmd is Command.MaybeApp || cmd is Command.NoSuchApp || (cmd is Command.Search && cmd.title)
        if (opens) picks.close()
        run(cmd)
        return true
    }

    fun voiceHints(): List<String> = (apps.map { it.title } + CommandParser.knownApps(brand).map { it.title }).distinct() +
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
            // Rules didn't understand: a person ("Adam Sandler movies")? a request for picks? else the AI. Never YouTube silently.
            say("Thinking…")
            viewModelScope.launch {
                picks.resolvePerson(t)?.let { (person, kind) -> picks.showPerson(person, kind)?.let { say(it, error = true) }; return@launch }
                if (WANTS_PICKS.containsMatchIn(t) || com.home.tvremote.voice.Portuguese.WANTS_PICKS.containsMatchIn(t)) { picks.openPicker(); if (picks.step == com.home.tvremote.picks.Step.Choose) picks.ask(t); return@launch }
                picks.interpret(t)?.let { say("$it. To search, say “search for …”", error = true) }
            }
            return true
        }
        val cmd = parsed ?: Command.Search(t, null)
        run(cmd)
        return true
    }

    fun run(cmd: Command) {
        com.home.tvremote.analytics.Analytics.log(com.home.tvremote.analytics.Analytics.Event.VoiceCommand, "kind" to cmd::class.simpleName?.lowercase())
        when (cmd) {
            is Command.Power -> if (cmd.on) powerOn() else turnOff()
            is Command.VolumeStep -> {
                say(if (cmd.up) "Louder" else "Quieter")
                enqueue { repeat(cmd.steps) { tv().volume(cmd.up); delay(40) } }
            }
            is Command.VolumeSet -> { say("Volume ${cmd.level}"); enqueue { tv().setVolume(cmd.level) } }
            is Command.Mute -> { say(if (cmd.mute) "Muted" else "Sound on"); enqueue { tv().setMute(cmd.mute) } }
            is Command.Media -> { if (cmd.action == "pause") assumePlaying = false else if (cmd.action == "play") assumePlaying = true; say(cmd.action.replaceFirstChar { it.uppercase() }.replace("Forward", " forward")); enqueue { tv().media(cmd.action) } }
            is Command.Button -> button(cmd.name)
            is Command.Seek -> seek(cmd.seconds)
            is Command.ChannelStep -> { say(if (cmd.up) "Channel up" else "Channel down"); channel(cmd.up) }
            is Command.ChannelNumber -> { say("Channel ${cmd.number}"); enqueue { tv().openChannel(cmd.number) } }
            is Command.Input -> { say("HDMI ${cmd.hdmi}"); enqueue { tv().openInput(cmd.hdmi) } }
            is Command.OpenApp -> launch(cmd.app)
            is Command.Search -> if (!cmd.title || cmd.app?.id?.startsWith("youtube") == true) search(cmd.query, cmd.app) else viewModelScope.launch {
                // "play / watch X": a show or film on their services first.
                // A person, trying the recognizer's other guesses too ("adam sanfler" → "adam sandler").
                val guesses = listOf(cmd.query) + alternatives.mapNotNull { g -> (CommandParser(apps).parse(g) as? Command.Search)?.query }
                for (q in guesses.distinct().take(4)) picks.resolvePerson(q)?.let { (person, kind) ->
                    say("Looking for ${person}…"); picks.showPerson(person, kind)?.let { say(it, error = true) }; return@launch
                }
                say("Looking for ${cmd.query}…")
                val hint = cmd.app?.let { a -> com.home.tvremote.picks.Catalog.SERVICES.firstOrNull { s -> picks.appIdFor(s) == a.id }?.name }
                when (val r = picks.findAndOpen(cmd.query, hint)) {
                    com.home.tvremote.picks.PicksController.Found.Opened -> {}
                    is com.home.tvremote.picks.PicksController.Found.Elsewhere -> sayElsewhere(r)
                    is com.home.tvremote.picks.PicksController.Found.NoApp -> say("${r.name} is on ${r.service} — open ${r.service} once on the TV", error = true)
                    com.home.tvremote.picks.PicksController.Found.Nothing -> {
                        // The AI next: a misheard person ("adam sanfler movies"), a description, or a mood (→ picks).
                        val msg = picks.interpret(cmd.query) ?: return@launch
                        // "some funny movies", "a good thriller": a request for picks, not YouTube.
                        if (WANTS_PICKS.containsMatchIn(cmd.query) || com.home.tvremote.voice.Portuguese.WANTS_PICKS.containsMatchIn(cmd.query)) { picks.openPicker(); if (picks.step == com.home.tvremote.picks.Step.Choose) picks.ask(cmd.query); return@launch }
                        // Last resort YouTube (music, clips), saying so.
                        search(cmd.query, cmd.app); say("“${cmd.query}” isn't a show or film — searching YouTube")
                    }
                }
            }
            is Command.NoSuchApp -> viewModelScope.launch {
                // "open X" where X isn't an app: maybe it's a show or film, a person, or a description.
                say("Looking for ${cmd.name}…")
                picks.resolvePerson(cmd.name)?.let { (person, kind) -> picks.showPerson(person, kind)?.let { say(it, error = true) }; return@launch }
                // Misheard titles: try each of the recognizer's guesses for an exact title before settling.
                // Your first guess exactly, then the recognizer's other guesses exactly, then the first guess loosely.
                val names = titleCandidates(cmd.name)
                for (n in names) if (picks.findAndOpen(n, exactOnly = true) == com.home.tvremote.picks.PicksController.Found.Opened) return@launch
                when (val r = picks.findAndOpen(cmd.name)) {
                    com.home.tvremote.picks.PicksController.Found.Opened -> {}
                    is com.home.tvremote.picks.PicksController.Found.Elsewhere -> sayElsewhere(r)
                    is com.home.tvremote.picks.PicksController.Found.NoApp -> say("${r.name} is on ${r.service} — open ${r.service} once on the TV", error = true)
                    com.home.tvremote.picks.PicksController.Found.Nothing ->
                        picks.interpret("open ${cmd.name}")?.let { say("Couldn't find an app or a show called “${cmd.name}” on your services", error = true) }
                }
            }
            is Command.MaybeApp -> viewModelScope.launch {
                // Sounds a bit like an app, but an exact show or film title wins ("open Severance" isn't Settings).
                for (n in titleCandidates(cmd.spoken))
                    if (picks.findAndOpen(n, exactOnly = true) == com.home.tvremote.picks.PicksController.Found.Opened) return@launch
                launch(cmd.app)
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
            enqueue { tv().launch(target.id, JSONObject().put("params", JSONObject().put("contentTarget", url))) }
        } else {
            // No search link for this app yet: open it and type the words as soon as its search box is selected.
            launch(target)
            typeWhenSearchOpens(query)
            say("Opened ${target.title}. Select its search box and “$query” is typed for you.")
        }
    }

    // ---- feedback ---------------------------------------------------------
    fun say(msg: String, error: Boolean = false, action: Pair<String, () -> Unit>? = null) {
        feedback = msg; feedbackIsError = error; feedbackAction = action
        feedbackJob?.cancel()
        feedbackJob = viewModelScope.launch { delay(if (action != null) 7000 else if (error) 4500 else 2200); feedback = null; feedbackAction = null }
    }
    private fun showError(e: Throwable) = say(e.message ?: "Something went wrong", error = true)

    override fun onCleared() { tv?.dispose() }

    /**
     * Everything that touches the network to reach the TV. Tests swap in a simulated LG TV (SimulatedTvTest);
     * on the phone it's always [RealNet].
     */
    interface Net {
        suspend fun reachable(ip: String, timeoutMs: Int): Boolean
        suspend fun wake(context: Context, macs: List<String>, ip: String?): Int
        fun driver(brand: String, ip: String, clientKey: String?, onClientKey: (String) -> Unit, onDisconnect: () -> Unit): TvDriver
        suspend fun lookupName(context: Context, ip: String): String?
    }
    object RealNet : Net {
        override suspend fun reachable(ip: String, timeoutMs: Int) = WakeOnLan.reachable(ip, timeoutMs)
        override suspend fun wake(context: Context, macs: List<String>, ip: String?) = WakeOnLan.send(context, macs, ip)
        override fun driver(brand: String, ip: String, clientKey: String?, onClientKey: (String) -> Unit, onDisconnect: () -> Unit): TvDriver =
            when (brand) {
                "Samsung" -> com.home.tvremote.samsung.SamsungDriver(ip, clientKey, onClientKey = onClientKey, onDisconnect = onDisconnect)
                else -> LgDriver(LgTv(ip, clientKey, onClientKey = onClientKey, onDisconnect = onDisconnect))
            }
        override suspend fun lookupName(context: Context, ip: String) = Discovery.lookupName(context, ip)
    }

    companion object {
        /** Swapped only by tests. */
        @Volatile var net: Net = RealNet

        /** "movies", "a show", "something funny", "a thriller": asking for recommendations, not a title. */
        val WANTS_PICKS = Regex("\\b(movies|films|shows|series|something|anything|comedy|comedies|thriller|thrillers|documentar(y|ies)|rom-?com|horror|drama)\\b", RegexOption.IGNORE_CASE)

        /** Power states that mean "the TV is off" (the screen-off state still counts as on). */
        val STANDBY_STATES = setOf("Active Standby", "Suspend", "Power Off")

        /** Screens that aren't "apps you use" and shouldn't count towards Most used. */
        val SYSTEM_APPS = setOf(
            "com.webos.app.home", "com.webos.app.livetv", "com.webos.surfacemanager", "com.webos.app.inputpicker",
            "com.webos.app.screensaver", "com.webos.app.settings", "com.palm.app.settings", "com.webos.app.notificationcenter",
            "com.webos.app.browser", // the timer / picks pages: taps should select there, not pause
            "com.webos.app.livedmost", // the live-TV preview on the Home screen
        ) + (1..4).map { "com.webos.app.hdmi$it" }
    }
}
