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
import kotlinx.coroutines.withTimeoutOrNull
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
    /** Open an app at a title (extra launch fields); falls back to just the app on TVs that don't support it. */
    fun openTitle(appId: String, title: String, extras: JSONObject) = openApp(appId, title)
    /** Types into the TV's search box and presses Enter, only if a keyboard is open there. False if none is. */
    fun typeIntoSearch(text: String): Boolean = false
    /** Type [text] (and press Enter) the moment a search box is selected on the TV, within the next ~2 minutes. */
    fun typeWhenSearchOpens(text: String) {}
    // The TV pointer, for the touchpad shown while the picker is on the TV.
    fun pointerMove(dx: Float, dy: Float) {}
    fun pointerTap() {}
    fun pointerScroll(dy: Float) {}
    /** A line for Diagnostics. */
    fun log(message: String) {}
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
    /** On-phone model engine; injectable so tests don't need Android's AI service. */
    private val onDevice: AiEngine? = OnDeviceAi,
    private val makeRecommender: ((PicksStore, AiPicker) -> Recommender)? = null,
    /** Netflix title ID for a TMDB key; null turns deep links off (tests). */
    private val netflixIdOf: (suspend (String) -> String?)? = { Wikidata().netflixId(it) },
    private val tmdbBase: String = "https://api.themoviedb.org/3",
) {
    /** Engines in the user's order: Groq and/or the on-phone model. Rules are the final fallback in the recommender. */
    fun aiChain(s: PicksStore = store): AiPicker {
        val groq = s.groqKey.takeIf { it.isNotBlank() }?.let { Groq(it, s.groqModel) }
        val phone = onDevice
        return AiPicker(when (s.aiChoice) {
            "rules" -> emptyList()
            "groq" -> listOfNotNull(groq, phone)
            else -> listOfNotNull(phone, groq)
        })
    }

    private fun recommender(): Recommender =
        makeRecommender?.invoke(store, aiChain()) ?: Recommender(store, Tmdb(store.tmdbKey, tmdbBase), aiChain())

    var open by mutableStateOf(false)
    var step by mutableStateOf(Step.Choose)
    var firstRun by mutableStateOf(false)
    var who by mutableStateOf("")
    /** null = Any. */
    var language by mutableStateOf<String?>(null)
    var showOnTv by mutableStateOf(store.showOnTv)
    /** The picker page is up on the TV: the phone becomes a touchpad. */
    var tvPageOn by mutableStateOf(false)
        internal set
    /** What the TV pointer is over, reported by the TV page. */
    var pointingAt by mutableStateOf("")
        internal set
    fun move(dx: Float, dy: Float) = tv.pointerMove(dx, dy)
    fun noteMicNeeded() = tv.note("Allow the microphone first (tap the remote's mic once)", true)
    fun tap() = tv.pointerTap()
    fun scroll(dy: Float) = tv.pointerScroll(dy)
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
    /** Status line for the on-phone model (refreshed when the picker or setup opens). */
    var onDeviceStatus by mutableStateOf("")

    fun refreshOnDevice() {
        if (onDevice !== OnDeviceAi) { onDeviceStatus = ""; return }
        scope.launch { OnDeviceAi.refresh(); onDeviceStatus = OnDeviceAi.describe() }
    }

    fun openPicker() {
        settleOldPlay()
        open = true
        refreshOnDevice()
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
        // Closing without any picks shown is just looking (or changing settings), not giving up on the picks.
        session?.let { if (it.outcome == "open") { it.outcome = if (it.shown == 0) "browsed" else "abandoned"; store.saveSession(it) } }
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
            val parsed = aiChain(s).parseRequest(t, Catalog.LANGUAGES.filterValues { it in s.languages })
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
                val r = recommender().pick(who, slot, ask, ask.language ?: language, minutesLeft, contextLine + " · " + summary)
                set = r; picks.clear(); picks.addAll(r.picks); shown += r.picks.map { it.title.key }
                sess.shown += r.picks.size; sess.engine = Recommender.lastEngine ?: ""; store.saveSession(sess)
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
                val r = recommender().more(prev, shown, who, contextLine, minutesLeft)
                set = r.copy(pool = prev.pool); picks.clear(); picks.addAll(r.picks); shown += r.picks.map { it.title.key }
                session?.let { it.shown += r.picks.size; store.saveSession(it) }
                step = Step.Results
            } catch (e: Exception) { message = e.message ?: "No more picks"; step = Step.Error }
            bump()
        }
    }

    fun back() { job?.cancel(); step = Step.Choose; bump() }

    // ---- favourites ("shows you loved") -------------------------------------
    /** Show the card for this person: not "Together", fewer than 3 favourites, not snoozed. */
    fun wantsFavourites(person: String = who) = person != Catalog.TOGETHER && person in store.people &&
        store.favourites(person).size < 3 && !store.favouritesSnoozed(person)
    /** Bumped when favourites change, so screens showing them redraw. */
    var favVersion by mutableStateOf(0)
        private set
    fun favouritesLater(person: String = who) { store.snoozeFavourites(person); favVersion++ }
    fun saveFavourites(person: String, list: List<Pair<String, String>>) { store.setFavourites(person, list); favVersion++ }
    suspend fun searchTitles(q: String): List<Title> = runCatching { Tmdb(store.tmdbKey, tmdbBase).search(q) }.getOrDefault(emptyList())

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
            val r = runCatching { recommender().replacement(prev, shown, minutesLeft) }.getOrNull()
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
        pending = session; pendingStartedAt = null
        openIn(key, name, svc, appId)
        session = null
        open = false
        scope.launch { delay(30_000); stopTvPage() }
    }

    // ---- opening a title, and "Not it?" fallbacks --------------------------------
    /** What was last opened, and the fallbacks still to try (shown on the remote as "Not it?"). */
    data class Opened(val key: String, val name: String, val service: String, val appId: String, val netflixId: String?, val steps: List<String>)
    var lastOpened by mutableStateOf<Opened?>(null)
        internal set
    var fixStep by mutableStateOf(0)
        private set
    private var openedJob: Job? = null

    /**
     * Opens [name] in the service's app: straight to the title where we can (Netflix with a known ID), Netflix's
     * search page when the ID is unknown, otherwise the app with "Look for". Fallbacks, in order: search page,
     * the other link formats, then typing the name into the app's search box.
     */
    private fun openIn(key: String, name: String, svc: Service, appId: String) {
        val lookup = netflixIdOf
        if (svc.name == "Netflix" && lookup != null) scope.launch {
            val id = withTimeoutOrNull(3000) { runCatching { lookup(key) }.getOrNull() }
            tv.log("LINK Netflix id for $name: ${id ?: "not found"}")
            if (id != null) {
                tv.openTitle(appId, svc.name, DeepLinks.netflix(id, store.netflixLink))
                remember(Opened(key, name, svc.name, appId, id,
                    listOf("search") + DeepLinks.NETFLIX_VARIANTS.filter { it != store.netflixLink && it != "B" }.map { "format:$it" } + "type"))
            } else {
                tv.openTitle(appId, svc.name, DeepLinks.netflixSearch(name, store.netflixSearch))
                remember(Opened(key, name, svc.name, appId, null, listOf("type")))
            }
            tv.note("Opening $name on Netflix")
        } else {
            // No direct link for this app yet: open it, and type the name as soon as its search box is selected.
            tv.openApp(appId, svc.name)
            tv.typeWhenSearchOpens(name)
            scope.launch { delay(4000); tv.toastOnTv("Select Search — the remote will type “$name”") }
            tv.note("Opening ${svc.name}. Select its search box and “$name” is typed for you.")
            remember(Opened(key, name, svc.name, appId, null, listOf("type")))
        }
    }

    private fun remember(o: Opened) {
        lastOpened = o; fixStep = 0
        openedJob?.cancel(); openedJob = scope.launch { delay(3 * 60_000); lastOpened = null }
    }

    fun dismissOpened() { openedJob?.cancel(); lastOpened = null }

    /** Label for the next fallback, e.g. "Not it? Search Netflix for it". */
    fun fixLabel(): String? {
        val o = lastOpened ?: return null
        return when (val s = o.steps.getOrNull(fixStep) ?: return null) {
            "search" -> "Not it? Search ${o.service} for it"
            "type" -> "Type “${o.name}” in the search box"
            else -> if (s.startsWith("format:")) "Still not it? Try another way" else null
        }
    }

    fun tryNextFix() {
        val o = lastOpened ?: return
        val s = o.steps.getOrNull(fixStep) ?: return
        tv.log("FIX $s for ${o.name}")
        when {
            s == "search" -> tv.openTitle(o.appId, o.service, DeepLinks.netflixSearch(o.name, store.netflixSearch))
            s.startsWith("format:") && o.netflixId != null -> tv.openTitle(o.appId, o.service, DeepLinks.netflix(o.netflixId, s.removePrefix("format:")))
            s == "type" -> {
                if (tv.typeIntoSearch(o.name)) tv.note("Typed “${o.name}”")
                else { tv.typeWhenSearchOpens(o.name); tv.note("Select ${o.service}'s search box: “${o.name}” is typed as soon as it opens") }
                return   // stays on "type" so it can be tapped again
            }
        }
        fixStep = (fixStep + 1).coerceAtMost(o.steps.lastIndex)
    }

    /**
     * Voice: "open / play / watch <title>" (optionally "on <service>"). Finds the title on TMDB, checks which of
     * the household's services has it, and opens it like Play. False when nothing on their services matches.
     */
    /** Result of looking a title up by name. */
    sealed interface Found {
        data object Opened : Found
        data class Elsewhere(val name: String, val services: List<String>) : Found
        data object Nothing : Found
    }

    suspend fun openByName(query: String, serviceHint: String? = null): Boolean = findAndOpen(query, serviceHint) == Found.Opened

    /**
     * "open / play / watch <title>" (optionally "on <service>"): TMDB search, which of the household's services has
     * it, then open it like Play. [exactOnly]: only a title whose name matches exactly (used before a loose app match).
     */
    suspend fun findAndOpen(query: String, serviceHint: String? = null, exactOnly: Boolean = false): Found {
        if (store.tmdbKey.isBlank()) return Found.Nothing
        val tmdb = Tmdb(store.tmdbKey, tmdbBase)
        val mine = Catalog.SERVICES.filter { it.name in store.services }
        val found = runCatching { tmdb.search(query) }.getOrDefault(emptyList()).take(5)
        val ranked = found.sortedByDescending { if (norm(it.name) == norm(query)) 1 else 0 }.filter { !exactOnly || norm(it.name) == norm(query) }
        for (t in ranked) {
            val e = runCatching { tmdb.enrich(t, mine) }.getOrDefault(t)
            val svcName = e.service?.takeIf { serviceHint == null || it == serviceHint }
                ?: serviceHint?.takeIf { norm(t.name) == norm(query) }   // they said where it is; trust them
                ?: continue
            val svc = Catalog.SERVICES.firstOrNull { it.name == svcName } ?: continue
            val appId = appIdFor(svc) ?: continue
            tv.log("VOICE “$query” → ${t.name} (${t.year}) on $svcName")
            openIn(t.key, t.name, svc, appId)
            return Found.Opened
        }
        // Found, but not on their services: say where it is instead of searching YouTube.
        ranked.firstOrNull { norm(it.name) == norm(query) }?.let { t ->
            val where = runCatching { tmdb.providerNames(t) }.getOrDefault(emptyList())
            tv.log("VOICE “$query” → ${t.name}, on ${where.ifEmpty { listOf("no subscription service") }.joinToString()}")
            return Found.Elsewhere(t.name, where)
        }
        tv.log("VOICE “$query” → nothing found")
        return Found.Nothing
    }

    private fun norm(x: String) = x.lowercase().trim().removePrefix("the ").replace("&", "and").replace(Regex("[^a-z0-9]"), "")

    /**
     * Last resort for what rules didn't understand: the AI says if it's a title, a person, a description or a mood.
     * Titles open directly; several matches (a person's films, a description) are shown as picks. Returns a message
     * for the user when nothing on their services fits, or null when handled.
     */
    suspend fun interpret(text: String): String? {
        if (!store.isSetUp || store.tmdbKey.isBlank()) return "Didn't understand “$text”"
        val j = aiChain().interpret(text) ?: return "Didn't understand “$text”"
        tv.log("VOICE AI “$text” → $j")
        val tmdb = Tmdb(store.tmdbKey, tmdbBase)
        val mine = Catalog.SERVICES.filter { it.name in store.services }
        suspend fun onMine(ts: List<Title>) = ts.take(10).map { runCatching { tmdb.enrich(it, mine) }.getOrDefault(it) }.filter { it.service != null }
        val titles = j.optJSONArray("titles")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty().filter { it.isNotBlank() }
        return when (j.optString("kind")) {
            "title" -> titles.firstOrNull()?.let { name ->
                when (val r = findAndOpen(name)) {
                    Found.Opened -> null
                    is Found.Elsewhere -> elsewhere(r)
                    Found.Nothing -> "Couldn't find “$name”"
                }
            } ?: "Didn't understand “$text”"
            "person" -> {
                val person = j.optString("person").takeIf { it.isNotBlank() && it != "null" } ?: return "Didn't understand “$text”"
                val list = onMine(runCatching { tmdb.personCredits(person) }.getOrDefault(emptyList()))
                if (list.isEmpty()) "Nothing with $person on your services" else { showList(list, "With $person") { "With $person." }; null }
            }
            "description" -> {
                val list = onMine(titles.mapNotNull { n -> runCatching { tmdb.search(n) }.getOrNull()?.firstOrNull() }.distinctBy { it.key })
                when {
                    list.size == 1 -> { val t = list[0]; Catalog.SERVICES.firstOrNull { it.name == t.service }?.let { s -> appIdFor(s)?.let { openIn(t.key, t.name, s, it) } }; null }
                    list.isNotEmpty() -> { showList(list, "“$text”") { "Matches what you described." }; null }
                    else -> "Nothing like that on your services"
                }
            }
            "mood" -> { openPicker(); if (step == Step.Choose) ask(text); null }
            else -> "Didn't understand “$text”"
        }
    }

    fun elsewhere(r: Found.Elsewhere): String =
        if (r.services.isEmpty()) "${r.name} isn't on a subscription service in the US"
        else "${r.name} is on ${r.services.take(2).joinToString(" and ")}, which isn't one of your services"

    /** Shows a ready-made list (a person's films, titles matching a description) on the picks screen. */
    private fun showList(list: List<Title>, label: String, why: (Title) -> String) {
        openPicker()
        if (step != Step.Choose) return   // first-run setup comes first
        session?.how = "voice: $label"
        summary = label
        val ps = list.map { Pick(it, why(it)) }
        set = PickSet(ps.take(PICKS), list.drop(PICKS), emptyMap())
        picks.clear(); picks.addAll(ps.take(PICKS)); shown += ps.take(PICKS).map { it.title.key }
        session?.let { it.shown += picks.size; store.saveSession(it) }
        step = Step.Results; bump()
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
    private companion object { const val NO_START_MS = 2 * 60_000L }

    /** When the played app was first seen in front of the TV (the clock for quick-backs); null = not yet. */
    private var pendingStartedAt: Long? = null

    /**
     * Called with every change of the app on the TV screen.
     * Time is measured from when the app actually came up, not from tapping Play (apps take a while to open).
     * Leaving within [NO_START_MS] isn't a judgement of the show (a misfire, a profile screen, a wrong app):
     * it's a "no-start", not a quick-back, and we keep watching in case the app comes back.
     */
    fun onForeground(appId: String, now: Long = System.currentTimeMillis()) {
        val p = pending ?: return
        if (p.playedAt == null) return
        if (appId == p.appId) {
            if (pendingStartedAt == null) { pendingStartedAt = now; if (p.outcome == "no-start") p.outcome = "played" }
            return
        }
        val started = pendingStartedAt ?: return
        if (now - started < NO_START_MS) { p.outcome = "no-start"; store.saveSession(p); pendingStartedAt = null; return }
        val min = ((now - started) / 60_000).toInt()
        if (min < 30) { p.leftAfterMin = min; p.outcome = "quick-back" } else p.outcome = "watched"
        if (min >= 15) store.addLiked(p.who, p.playedKey!!, p.playedName ?: "") // 15–30 min is a weak signal, still liked
        store.saveSession(p); pending = null; pendingStartedAt = null
    }

    /** A play that wasn't left within 30 minutes counts as watched (and liked); a no-start is just recorded. */
    private fun settleOldPlay(now: Long = System.currentTimeMillis()) {
        val p = pending ?: return
        if (now - (p.playedAt ?: now) < 30 * 60_000) return
        if (p.outcome != "no-start") { p.outcome = "watched"; p.playedKey?.let { store.addLiked(p.who, it, p.playedName ?: "") } }
        store.saveSession(p); pending = null; pendingStartedAt = null
    }

    // ---- settings ----------------------------------------------------------
    fun finishSetup() { firstRun = false; openPicker() }

    // ---- TV page -----------------------------------------------------------
    private fun startTvPage() {
        if (web != null) return
        val ip = tv.phoneIp() ?: run { tv.note("The phone isn't on Wi-Fi, so the TV can't show the picks", true); return }
        val w = PicksWeb(state = ::webState, action = { d, v -> scope.launch(Dispatchers.Main) { webAction(d, v) } }, allowedIp = tv.tvIp,
            focus = { l -> scope.launch(Dispatchers.Main) { pointingAt = l } }, log = { tv.log("PAGE $it") })
        w.start(); web = w; tvPageOn = true; pointingAt = ""
        tv.log("PAGE opening on the TV")
        tv.openOnTvBrowser(w.url(ip))
    }

    private fun stopTvPage() { web?.stop(); web = null; tvPageOn = false; pointingAt = "" }

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
        val j = JSONObject().put("v", version).put("phase", phase).put("leanBack", true)
            .put("title", title).put("context", contextLine).put("summary", summary.ifEmpty { "Waiting for a mood…" })
            .put("message", if (phase == "playing") "Opening ${pending?.service ?: "the app"} — look for ${pending?.playedName ?: ""}" else message)
            .put("keep", keep?.playedName)
            .put("who", JSONArray(profiles().map { JSONObject().put("label", it).put("on", it == who) }))
            .put("langs", JSONArray((listOf<String?>(null) + store.languages).map { c ->
                JSONObject().put("code", c ?: "any").put("label", c?.let { Catalog.languageLabel(it) } ?: "Any").put("on", c == language) }))
            .put("moods", JSONArray(moods().map { JSONObject().put("id", it.name).put("label", it.label).put("hint", it.hint) }))
            .put("picks", JSONArray(picks.map { p ->
                JSONObject().put("name", p.title.name).put("meta", meta(p.title)).put("why", p.why).put("fresh", p.title.fresh)
                    .put("poster", p.title.posterPath?.let { "https://image.tmdb.org/t/p/w342$it" }).put("color", color(p.title.name))
            }))
        return j.toString()
    }

    // ---- display helpers ---------------------------------------------------
    fun meta(t: Title): String = listOfNotNull(
        t.service, if (t.kind == Kind.Tv) "series" else "movie",
        Catalog.languageLabel(t.language).takeIf { t.language.isNotEmpty() && t.language != "en" },
        t.runtimeMin?.let { (if (t.kind == Kind.Tv) "~" else "") + Moment.duration(it) },
        if (t.kind == Kind.Movie) endsAt(t) else null, if (t.rating > 0) "★ ${"%.1f".format(t.rating)}" else null,
    ).joinToString(" · ")

    private fun endsAt(t: Title): String? {
        val m = t.runtimeMin ?: return null
        val c = clock().apply { add(Calendar.MINUTE, m) }
        return "ends " + java.text.SimpleDateFormat("h:mm", java.util.Locale.US).format(c.time)
    }

    fun posterUrl(t: Title) = t.posterPath?.let { "https://image.tmdb.org/t/p/w342$it" }

    fun color(name: String): String = listOf("#1F5F8B", "#3E6B48", "#7A3B2E", "#5B4B8A", "#2E4057", "#8A5A1F")[Math.floorMod(name.hashCode(), 6)]

    /** Picks were shown and none was played (older versions also recorded a plain open/close as "abandoned"). */
    private fun gaveUp(s: Session) = s.outcome == "abandoned" && s.shown > 0

    /** Measurement summary + last sessions, for Diagnostics. */
    fun diagnostics(): String = buildString {
        val all = store.sessions()
        val played = all.filter { it.playedAt != null }
        val secs = played.mapNotNull { it.secondsToPlay }.sorted()
        appendLine("PICKS · ${all.size} sessions · ${played.size} played · ${all.count { gaveUp(it) }} gave up")
        if (secs.isNotEmpty()) appendLine("Typical time to Play: ${secs[secs.size / 2]} s (fastest ${secs.first()} s, slowest ${secs.last()} s)")
        val counted = played.filter { it.outcome != "no-start" }
        if (counted.isNotEmpty()) {
            val qb = counted.filter { it.outcome == "quick-back" }
            val strong = qb.count { (it.leftAfterMin ?: 99) < 15 }; val weak = qb.count { (it.leftAfterMin ?: 99) in 15..29 }
            appendLine("Quick-backs: <15 min ${strong * 100 / counted.size}% · 15–30 min ${weak * 100 / counted.size}%" +
                (played.size - counted.size).let { if (it > 0) " · $it left within 2 min (not counted)" else "" })
        }
        Recommender.lastAiError?.let { appendLine("Last AI error (fell back to rules): $it") }
        val order = aiChain().let { c -> c.engines.map { it.name } } + "Rules"
        appendLine("Settings: ${store.people.size} people · ${store.services.size} services · languages ${store.languages.joinToString()} · on TV ${if (showOnTv) "on" else "off"}")
        appendLine("AI order: ${order.joinToString(" → ")} · on-phone: ${if (onDevice == OnDeviceAi) OnDeviceAi.describe() else "n/a"} · last picks by ${Recommender.lastEngine ?: "—"}")
        val fmt = java.text.SimpleDateFormat("EEE d MMM h:mm a", java.util.Locale.US)
        for (s in all.takeLast(20).reversed()) {
            append("${fmt.format(java.util.Date(s.openedAt))} · ${s.who} · ${s.slot} · ${s.how} · ${s.language} · ${s.shown} shown" + if (s.engine.isNotEmpty()) " by ${s.engine}" else "")
            if (s.notNow > 0) append(" · ${s.notNow} not now"); if (s.never > 0) append(" · ${s.never} never")
            if (s.playedAt != null) append(" → ${s.playedName} (${s.service}) after ${s.secondsToPlay} s")
            when (s.outcome) {
                "quick-back" -> append(" · LEFT after ${s.leftAfterMin} min")
                "watched" -> append(" · watched 30+ min")
                "no-start" -> append(" · left within 2 min (not counted)")
                "abandoned", "browsed" -> append(if (gaveUp(s)) " · gave up" else " · closed before any picks")
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
        val newOnly = listOf("new ", "newest", "latest", "recent", "just came out", "just released").any { it in "$t " }
        val lang = Catalog.LANGUAGES.entries.firstOrNull { it.key.lowercase() in t }?.value
        val max = Regex("(\\d+) ?(?:min|minutes)").find(t)?.groupValues?.get(1)?.toIntOrNull()
            ?: if (listOf("short", "quick").any { it in t }) 35 else null
        return Ask(if (mood == Mood.Any && newOnly) Mood.New else mood, kind, lang, max, newOnly = newOnly)
    }
}
