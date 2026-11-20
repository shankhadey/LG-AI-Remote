package com.home.tvremote.picks

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/** What the picker needs from the remote (implemented by TvController). */
interface TvActions {
    fun openApp(appId: String, title: String)
    fun knownAppIds(): Set<String>
    fun openOnTvBrowser(url: String)
    fun toastOnTv(message: String)
    fun goHome()
    val foreground: String?
    fun phoneIp(): String?
    val tvIp: String?
    fun note(message: String, error: Boolean = false)
}

enum class Step { Setup, Choose, Loading, Results, Error }

/**
 * "What should we watch?": state and actions shared by the phone screens and the TV page.
 * Learning (Not now / Never / plays / quick-backs / time-slot habits) lives in [PicksStore].
 */
class PicksController(
    val store: PicksStore,
    private val tv: TvActions,
    private val scope: CoroutineScope,
    private val clock: () -> Calendar = { Calendar.getInstance() },
    private val makeRecommender: (PicksStore) -> Recommender = { s ->
        Recommender(s, Tmdb(s.tmdbKey), s.groqKey.takeIf { it.isNotBlank() }?.let { Groq(it, s.groqModel) })
    },
) {
    var open by mutableStateOf(false)
    var step by mutableStateOf(Step.Choose)
    var firstRun by mutableStateOf(false)
    var who by mutableStateOf("")
    /** null = Any. */
    var language by mutableStateOf<String?>(null)
    var showOnTv by mutableStateOf(store.showOnTv)
    var message by mutableStateOf("")
    var summary by mutableStateOf("")
    val picks = mutableStateListOf<Pick>()
    var keep by mutableStateOf<Session?>(null)

    private var set: PickSet? = null
    private val shown = mutableSetOf<String>()
    private var session: Session? = null
    private var job: Job? = null
    private var web: PicksWeb? = null
    @Volatile private var version = 0

    // ---- context ----------------------------------------------------------
    val slot get() = Moment.slot(clock())
    val minutesLeft get() = Moment.minutesLeft(clock(), store.weekdayBedtime, store.weekendBedtime)
    val contextLine get() = Moment.contextLine(clock(), minutesLeft)
    val title get() = if (who == Catalog.TOGETHER || store.profiles().size > 1 && who.isEmpty()) "What should we watch?" else "What should I watch?"
    fun moods(): List<Mood> = Moment.moodOrder(slot, store.slotCounts(slot, "mood"))
    fun profiles() = store.profiles()

    // ---- open / close -----------------------------------------------------
    fun openPicker() {
        settleOldPlay()
        open = true
        if (!store.isSetUp) { firstRun = true; step = Step.Setup; return }
        firstRun = false
        val profiles = store.profiles()
        who = store.slotCounts(slot, "who").maxByOrNull { it.value }?.key?.takeIf { it in profiles }
            ?: if (Catalog.TOGETHER in profiles) Catalog.TOGETHER else profiles.first()
        language = store.slotCounts(slot, "lang").maxByOrNull { it.value }?.key?.takeIf { it != "any" && it in store.languages }
        picks.clear(); shown.clear(); set = null
        keep = store.keepWatching(who)
        session = Session(openedAt = System.currentTimeMillis(), who = who, slot = slot)
        step = Step.Choose
        summary = ""
        bump()
        if (showOnTv) startTvPage()
    }

    fun close() {
        job?.cancel()
        session?.let { if (it.outcome == "open") { it.outcome = "abandoned"; store.saveSession(it) } }
        session = null
        open = false
        if (web != null) { stopTvPage(); if (tv.foreground == "com.webos.app.browser") tv.goHome() }
    }

    fun changeShowOnTv(on: Boolean) {
        showOnTv = on; store.showOnTv = on
        if (on && open) startTvPage() else if (!on) { stopTvPage(); if (tv.foreground == "com.webos.app.browser") tv.goHome() }
    }

    fun selectWho(w: String) { who = w; keep = store.keepWatching(w); session?.who = w; bump() }
    fun selectLanguage(code: String?) { language = code; bump() }

    // ---- asking ------------------------------------------------------------
    fun chooseMood(m: Mood) = run(Ask(mood = m), m.name, m.label)
    fun surprise() = run(Ask(mood = Mood.Any), "surprise", "Surprise us")
    fun ask(text: String) {
        val t = text.trim(); if (t.isEmpty()) return
        val s = store
        job?.cancel()
        step = Step.Loading; summary = "“$t”"; bump()
        job = scope.launch {
            val parsed = if (s.groqKey.isNotBlank())
                runCatching { Groq(s.groqKey, s.groqModel).parseRequest(t, Catalog.LANGUAGES.filterValues { it in s.languages }) }.getOrNull()
            else null
            run(parsed ?: RulesAsk.parse(t), "voice: $t", "“$t”")
        }
    }

    private fun run(ask: Ask, how: String, label: String) {
        val sess = session ?: return
        sess.how = how; sess.who = who; sess.language = ask.language ?: language ?: "any"
        store.countSlot(slot, "who", who)
        store.countSlot(slot, "lang", language ?: "any")
        if (ask.mood != Mood.Any && !how.startsWith("voice")) store.countSlot(slot, "mood", ask.mood.name)
        summary = listOfNotNull(label, who, language?.let { Catalog.languageLabel(it) }).joinToString(" · ")
        step = Step.Loading; bump()
        job?.cancel()
        job = scope.launch {
            try {
                val r = makeRecommender(store).pick(who, slot, ask, ask.language ?: language, minutesLeft, contextLine + " · " + summary)
                set = r; picks.clear(); picks.addAll(r.picks); shown += r.picks.map { it.title.key }
                sess.shown += r.picks.size; store.saveSession(sess)
                step = Step.Results
            } catch (e: Exception) {
                message = (e as? PicksException)?.message ?: "Couldn't get picks (${e.message}). Check your internet and keys."
                step = Step.Error
            }
            bump()
        }
    }

    fun more() {
        val prev = set ?: return
        step = Step.Loading; bump()
        job = scope.launch {
            try {
                val r = makeRecommender(store).more(prev, shown, who, contextLine, minutesLeft)
                set = r.copy(pool = prev.pool); picks.clear(); picks.addAll(r.picks); shown += r.picks.map { it.title.key }
                session?.let { it.shown += r.picks.size; store.saveSession(it) }
                step = Step.Results
            } catch (e: Exception) { message = e.message ?: "No more picks"; step = Step.Error }
            bump()
        }
    }

    fun back() { job?.cancel(); step = Step.Choose; bump() }

    // ---- feedback ----------------------------------------------------------
    fun notNow(i: Int) = dismiss(i, never = false)
    fun never(i: Int) = dismiss(i, never = true)

    private fun dismiss(i: Int, never: Boolean) {
        val p = picks.getOrNull(i) ?: return
        if (never) { store.addNever(who, p.title.key); session?.let { it.never++ } }
        else { store.addNotNow(who, slot, p.title.key); session?.let { it.notNow++ } }
        session?.let { store.saveSession(it) }
        val prev = set ?: return
        scope.launch {
            val r = runCatching { makeRecommender(store).replacement(prev, shown, minutesLeft) }.getOrNull()
            val idx = picks.indexOfFirst { it.title.key == p.title.key }
            if (idx < 0) return@launch
            if (r != null) { picks[idx] = r; shown += r.title.key; session?.let { it.shown++ } } else picks.removeAt(idx)
            bump()
        }
        tv.note(if (never) "Won't suggest ${p.title.name} for $who again" else "Skipped for now")
    }

    // ---- playing -----------------------------------------------------------
    fun play(i: Int) { picks.getOrNull(i)?.let { playTitle(it.title.key, it.title.name, it.title.service) } }

    fun playKeepWatching() { keep?.let { session?.how = "keep"; playTitle(it.playedKey ?: return, it.playedName ?: "", it.service) } }

    private fun playTitle(key: String, name: String, service: String?) {
        val svc = Catalog.SERVICES.firstOrNull { it.name == service }
        val appId = svc?.let { appIdFor(it) }
        if (appId == null) {
            message = if (svc == null) "Couldn't tell which app has $name" else "Open ${svc.name} once from the TV, so the remote can learn it"
            tv.note(message, error = true); return
        }
        session?.apply {
            playedKey = key; playedName = name; this.service = service; this.appId = appId
            playedAt = System.currentTimeMillis(); outcome = "played"; store.saveSession(this)
        }
        pending = session
        tv.openApp(appId, svc.name)
        // There's no reliable way to open a specific title in these apps from outside, so say what to look for.
        scope.launch { delay(4000); tv.toastOnTv("Look for: $name") }
        tv.note("Opening ${svc.name} — look for $name")
        session = null
        open = false
        scope.launch { delay(30_000); stopTvPage() }
    }

    /** Which app on this TV is the service: learned app IDs first, then well-known LG IDs. */
    fun appIdFor(s: Service): String? {
        val known = tv.knownAppIds()
        for (h in s.lgAppHints) known.firstOrNull { it.equals(h, true) }?.let { return it }
        for (h in s.lgAppHints) known.firstOrNull { it.contains(h, true) }?.let { return it }
        return s.lgAppHints.firstOrNull()?.takeIf { s.name in setOf("Netflix", "Prime Video", "Disney+", "Apple TV+", "Hulu") }
    }

    // ---- measurement: quick-backs --------------------------------------------
    private var pending: Session? = null
    private var pendingStarted = false

    /** Called with every change of the app on the TV screen. */
    fun onForeground(appId: String, now: Long = System.currentTimeMillis()) {
        val p = pending ?: return
        val at = p.playedAt ?: return
        if (appId == p.appId) { pendingStarted = true; return }
        if (!pendingStarted) return
        val min = ((now - at) / 60_000).toInt()
        if (min < 30) { p.leftAfterMin = min; p.outcome = "quick-back" } else { p.outcome = "watched"; store.addLiked(p.who, p.playedKey!!, p.playedName ?: "") }
        if (min in 15..29) store.addLiked(p.who, p.playedKey!!, p.playedName ?: "") // weak signal: still counts as liked
        store.saveSession(p); pending = null; pendingStarted = false
    }

    /** A play that wasn't left within 30 minutes counts as watched (and liked). */
    private fun settleOldPlay(now: Long = System.currentTimeMillis()) {
        val p = pending ?: return
        if (now - (p.playedAt ?: now) >= 30 * 60_000) {
            p.outcome = "watched"; p.playedKey?.let { store.addLiked(p.who, it, p.playedName ?: "") }
            store.saveSession(p); pending = null; pendingStarted = false
        }
    }

    // ---- settings ----------------------------------------------------------
    fun finishSetup() { firstRun = false; openPicker() }

    // ---- TV page -----------------------------------------------------------
    private fun startTvPage() {
        if (web != null) return
        val ip = tv.phoneIp() ?: run { tv.note("The phone isn't on Wi-Fi, so the TV can't show the picks", true); return }
        val w = PicksWeb(state = ::webState, action = { d, v -> scope.launch(Dispatchers.Main) { webAction(d, v) } }, allowedIp = tv.tvIp)
        w.start(); web = w
        tv.openOnTvBrowser(w.url(ip))
    }

    private fun stopTvPage() { web?.stop(); web = null }

    private fun bump() { version++ }

    fun webAction(d: String, v: String) {
        when (d) {
            "who" -> selectWho(v)
            "lang" -> selectLanguage(v.takeIf { it != "any" })
            "mood" -> Mood.entries.firstOrNull { it.name == v }?.let { chooseMood(it) }
            "surprise" -> surprise()
            "keep" -> playKeepWatching()
            "play" -> v.toIntOrNull()?.let { session?.how = (session?.how ?: "") + " · tv"; play(it) }
            "notnow" -> v.toIntOrNull()?.let { notNow(it) }
            "never" -> v.toIntOrNull()?.let { never(it) }
            "more" -> more()
            "back" -> back()
        }
    }

    fun webState(): String {
        val phase = when {
            !open && session == null && pending != null -> "playing"
            step == Step.Loading -> "loading"; step == Step.Results -> "picks"; step == Step.Error -> "error"; else -> "choose"
        }
        val j = JSONObject().put("v", version).put("phase", phase).put("leanBack", store.leanBack)
            .put("title", title).put("context", contextLine).put("summary", summary.ifEmpty { "Waiting for a mood…" })
            .put("message", if (phase == "playing") "Opening ${pending?.service ?: "the app"} — look for ${pending?.playedName ?: ""}" else message)
            .put("keep", keep?.playedName)
            .put("who", JSONArray(profiles().map { JSONObject().put("label", it).put("on", it == who) }))
            .put("langs", JSONArray((listOf<String?>(null) + store.languages).map { c ->
                JSONObject().put("code", c ?: "any").put("label", c?.let { Catalog.languageLabel(it) } ?: "Any").put("on", c == language) }))
            .put("moods", JSONArray(moods().map { JSONObject().put("id", it.name).put("label", it.label).put("hint", it.hint) }))
            .put("picks", JSONArray(picks.map { p ->
                JSONObject().put("name", p.title.name).put("meta", meta(p.title)).put("why", p.why)
                    .put("poster", p.title.posterPath?.let { "https://image.tmdb.org/t/p/w342$it" }).put("color", color(p.title.name))
            }))
        return j.toString()
    }

    // ---- display helpers ---------------------------------------------------
    fun meta(t: Title): String = listOfNotNull(
        t.service, Catalog.languageLabel(t.language).takeIf { t.language.isNotEmpty() },
        t.runtimeMin?.let { (if (t.kind == Kind.Tv) "~" else "") + Moment.duration(it) + if (t.kind == Kind.Tv) " episodes" else "" },
        endsAt(t), if (t.rating > 0) "★ ${"%.1f".format(t.rating)}" else null,
    ).joinToString(" · ")

    private fun endsAt(t: Title): String? {
        val m = t.runtimeMin ?: return null
        val c = clock().apply { add(Calendar.MINUTE, m) }
        return "ends " + java.text.SimpleDateFormat("h:mm", java.util.Locale.US).format(c.time)
    }

    fun posterUrl(t: Title) = t.posterPath?.let { "https://image.tmdb.org/t/p/w342$it" }

    fun color(name: String): String = listOf("#1F5F8B", "#3E6B48", "#7A3B2E", "#5B4B8A", "#2E4057", "#8A5A1F")[Math.floorMod(name.hashCode(), 6)]

    /** Measurement summary + last sessions, for Diagnostics. */
    fun diagnostics(): String = buildString {
        val all = store.sessions()
        val played = all.filter { it.playedAt != null }
        val secs = played.mapNotNull { it.secondsToPlay }.sorted()
        appendLine("PICKS · ${all.size} sessions · ${played.size} played · ${all.count { it.outcome == "abandoned" }} gave up")
        if (secs.isNotEmpty()) appendLine("Typical time to Play: ${secs[secs.size / 2]} s (fastest ${secs.first()} s, slowest ${secs.last()} s)")
        if (played.isNotEmpty()) {
            val strong = played.count { (it.leftAfterMin ?: 99) < 15 }; val weak = played.count { (it.leftAfterMin ?: 99) in 15..29 }
            appendLine("Quick-backs: <15 min ${strong * 100 / played.size}% · 15–30 min ${weak * 100 / played.size}%")
        }
        Recommender.lastAiError?.let { appendLine("Last AI error (fell back to rules): $it") }
        appendLine("Settings: ${store.people.size} people · ${store.services.size} services · languages ${store.languages.joinToString()} · lean-back ${if (store.leanBack) "on" else "off"} · AI ${if (store.groqKey.isNotBlank()) store.groqModel else "off (rules)"}")
        val fmt = java.text.SimpleDateFormat("EEE d MMM h:mm a", java.util.Locale.US)
        for (s in all.takeLast(20).reversed()) {
            append("${fmt.format(java.util.Date(s.openedAt))} · ${s.who} · ${s.slot} · ${s.how} · ${s.language} · ${s.shown} shown")
            if (s.notNow > 0) append(" · ${s.notNow} not now"); if (s.never > 0) append(" · ${s.never} never")
            if (s.playedAt != null) append(" → ${s.playedName} (${s.service}) after ${s.secondsToPlay} s")
            when (s.outcome) {
                "quick-back" -> append(" · LEFT after ${s.leftAfterMin} min")
                "watched" -> append(" · watched 30+ min")
                "abandoned" -> append(" · gave up")
            }
            appendLine()
        }
    }
}

/** Without AI: pull simple filters out of what was said. */
object RulesAsk {
    fun parse(text: String): Ask {
        val t = text.lowercase()
        val mood = when {
            listOf("funny", "comedy", "laugh", "light", "silly").any { it in t } -> Mood.Light
            listOf("cozy", "cosy", "comfort", "feel good", "feel-good", "warm", "romantic").any { it in t } -> Mood.Cozy
            listOf("thriller", "gripping", "crime", "mystery", "intense", "suspense", "action").any { it in t } -> Mood.Gripping
            listOf("sci-fi", "scifi", "mind", "twist", "weird", "space").any { it in t } -> Mood.MindBending
            else -> Mood.Any
        }
        val kind = when { "movie" in t || "film" in t -> Kind.Movie; "series" in t || "show" in t || "episode" in t -> Kind.Tv; else -> null }
        val lang = Catalog.LANGUAGES.entries.firstOrNull { it.key.lowercase() in t }?.value
        val max = Regex("(\\d+) ?(?:min|minutes)").find(t)?.groupValues?.get(1)?.toIntOrNull()
            ?: if (listOf("short", "quick").any { it in t }) 35 else null
        return Ask(mood, kind, lang, max)
    }
}
