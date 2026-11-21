package com.home.tvremote

import com.home.tvremote.picks.AiEngine
import com.home.tvremote.picks.AiPicker
import com.home.tvremote.picks.Ask
import com.home.tvremote.picks.extractJson
import com.home.tvremote.picks.Catalog
import com.home.tvremote.picks.Groq
import com.home.tvremote.picks.Kind
import com.home.tvremote.picks.Moment
import com.home.tvremote.picks.Mood
import com.home.tvremote.picks.Pick
import com.home.tvremote.picks.PicksController
import com.home.tvremote.picks.PicksStore
import com.home.tvremote.picks.PicksWeb
import com.home.tvremote.picks.Recommender
import com.home.tvremote.picks.RulesAsk
import com.home.tvremote.picks.Session
import com.home.tvremote.picks.Step
import com.home.tvremote.picks.Title
import com.home.tvremote.picks.Tmdb
import com.home.tvremote.picks.TvActions
import com.home.tvremote.voice.Command
import com.home.tvremote.voice.CommandParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar

class PicksTest {
    private fun at(dow: Int, h: Int, m: Int = 0): Calendar = Calendar.getInstance().apply {
        set(2026, Calendar.NOVEMBER, 16, h, m, 0) // Mon 16 Nov 2026
        while (get(Calendar.DAY_OF_WEEK) != dow) add(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m)
    }

    // ---------------- time of day / day of week ----------------
    @Test fun timeLeftUsesTheRightBedtime() {
        val wk = 23 * 60; val we = 30 // 11 pm weeknights, 12:30 am Fri/Sat
        assertEquals(90, Moment.minutesLeft(at(Calendar.TUESDAY, 21, 30), wk, we))
        assertEquals(90, Moment.minutesLeft(at(Calendar.FRIDAY, 23, 0), wk, we))          // Friday night → 12:30
        assertNull(Moment.minutesLeft(at(Calendar.TUESDAY, 23, 30), wk, we))                // past bedtime
        assertNull(Moment.minutesLeft(at(Calendar.SATURDAY, 1, 0), wk, we))                 // 1 am Sat = Friday night, past 12:30
        assertEquals(15, Moment.minutesLeft(at(Calendar.SATURDAY, 0, 15), wk, we))
    }

    @Test fun contextLineAndSlots() {
        assertEquals("Tuesday evening · about 1 h 15 m free", Moment.contextLine(at(Calendar.TUESDAY, 19, 0), 75))
        assertEquals("Saturday afternoon · plenty of time", Moment.contextLine(at(Calendar.SATURDAY, 15, 0), 600))
        assertEquals("weekday-late", Moment.slot(at(Calendar.TUESDAY, 22, 0)))
        assertEquals("weekend-late", Moment.slot(at(Calendar.FRIDAY, 22, 0)))
        assertEquals("weekend-late", Moment.slot(at(Calendar.SATURDAY, 1, 0)))   // still Friday night
        assertEquals("weekend-afternoon", Moment.slot(at(Calendar.SUNDAY, 14, 0)))
        assertEquals("weekday-evening", Moment.slot(at(Calendar.SUNDAY, 19, 0)))  // Sunday evening is a school night
    }

    @Test fun moodsAreOrderedForTheMomentThenByHabit() {
        assertEquals(Mood.Light, Moment.moodOrder("weekday-late", emptyMap()).first())
        assertEquals(Mood.Gripping, Moment.moodOrder("weekend-late", emptyMap()).first())
        assertEquals(Mood.MindBending, Moment.moodOrder("weekday-late", mapOf("MindBending" to 3, "Light" to 1)).first())
    }

    // ---------------- learning store ----------------
    @Test fun neverIsPerPersonAndNotNowIsPerSlot() {
        val s = PicksStore(FakePrefs())
        s.addNever("Together", "tv:1")
        assertEquals(setOf("tv:1"), s.never("Together"))
        assertTrue(s.never("Alex").isEmpty())                      // Never for Together doesn't hide it from Alex
        val now = 1_000_000_000_000L
        s.addNotNow("Alex", "weekday-late", "movie:2", now)
        assertEquals(setOf("movie:2"), s.notNow("Alex", "weekday-late", now))
        assertTrue(s.notNow("Alex", "weekend-afternoon", now).isEmpty())   // another time: can come back
        assertTrue(s.notNow("Alex", "weekday-late", now + 22 * PicksStore.DAY).isEmpty())
    }

    @Test fun sessionsRoundTripAndKeepWatching() {
        val s = PicksStore(FakePrefs())
        val now = System.currentTimeMillis()
        val sess = Session(openedAt = now - 60_000, who = "Together", playedKey = "tv:9", playedName = "Severance",
            service = "Apple TV+", appId = "com.apple.appletv", playedAt = now - 20_000, outcome = "played")
        s.saveSession(sess)
        val back = s.sessions().single()
        assertEquals(40L, back.secondsToPlay)
        assertEquals("Severance", s.keepWatching("Together")?.playedName)
        assertNull(s.keepWatching("Alex"))
    }

    // ---------------- recommender against simulated TMDB + Groq ----------------
    private val server = MockWebServer()
    @After fun stop() = runCatching { server.shutdown() }.let { }

    private fun tmdbAndGroq(groqReply: String?, groqStatus: Int = 200) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(r: RecordedRequest): MockResponse {
                val p = r.path ?: ""
                fun json(o: Any) = MockResponse().setBody(o.toString()).setHeader("Content-Type", "application/json")
                return when {
                    p.startsWith("/3/watch/providers/") -> json(JSONObject().put("results", JSONArray()
                        .put(JSONObject().put("provider_id", 8).put("provider_name", "Netflix"))
                        .put(JSONObject().put("provider_id", 15).put("provider_name", "Hulu"))))
                    p.startsWith("/3/discover/tv") -> json(JSONObject().put("results", JSONArray((1..6).map {
                        JSONObject().put("id", it).put("name", "Show $it").put("first_air_date", "2020-01-01").put("overview", "A comedy")
                            .put("genre_ids", JSONArray(listOf(35))).put("original_language", "en").put("vote_average", 7.0 + it / 10.0)
                            .put("vote_count", 1000).put("popularity", 50.0 + it)
                    })))
                    p.startsWith("/3/discover/movie") -> json(JSONObject().put("results", JSONArray((101..104).map {
                        JSONObject().put("id", it).put("title", "Film $it").put("release_date", "2019-05-01").put("overview", "Funny film")
                            .put("genre_ids", JSONArray(listOf(35))).put("original_language", "en").put("vote_average", 7.5)
                            .put("vote_count", 500).put("popularity", 40.0)
                    })))
                    p.matches(Regex("/3/(tv|movie)/\\d+/watch/providers.*")) -> json(JSONObject().put("results", JSONObject()
                        .put("US", JSONObject().put("flatrate", JSONArray().put(JSONObject().put("provider_name", "Netflix"))))))
                    p.matches(Regex("/3/tv/\\d+\\?.*")) -> json(JSONObject().put("episode_run_time", JSONArray(listOf(28))))
                    p.matches(Regex("/3/movie/\\d+\\?.*")) -> json(JSONObject().put("runtime", 95))
                    p.contains("/recommendations") -> json(JSONObject().put("results", JSONArray().put(JSONObject().put("id", 3))))
                    p.startsWith("/openai/v1/chat/completions") ->
                        if (groqReply == null) MockResponse().setResponseCode(groqStatus)
                        else json(JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", groqReply)))))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    private fun store() = PicksStore(FakePrefs()).apply {
        people = listOf("Alex", "Sam"); services = listOf("Netflix", "Hulu"); languages = listOf("en"); tmdbKey = "k"
    }

    @Test fun aiChoosesOnlyFromCandidatesAndNeverIsRespected() = runBlocking {
        tmdbAndGroq("""{"picks":[{"id":"tv:999","why":"invented"},{"id":"tv:6","why":"Very funny."},{"id":"movie:101","why":"Light film."},{"id":"tv:5","why":"Easy watch."}]}""")
        val s = store()
        s.addNever("Together", "tv:4")
        val r = Recommender(s, Tmdb("k", server.url("/3").toString().trimEnd('/')), AiPicker(listOf(Groq("g", "m", server.url("/openai/v1").toString().trimEnd('/')))))
            .pick("Together", "weekday-late", Ask(Mood.Light), null, 90, "Tuesday night")
        assertEquals(listOf("tv:6", "movie:101", "tv:5"), r.picks.take(3).map { it.title.key })   // "tv:999" (not a candidate) dropped
        assertEquals(5, r.picks.size)                                                       // topped up by rules
        assertTrue(r.picks.none { it.title.key == "tv:999" })
        assertTrue(r.pool.none { it.key == "tv:4" })                                       // Never excluded
        assertEquals("Netflix", r.picks[0].title.service)
        assertEquals(28, r.picks[0].title.runtimeMin)
        assertEquals("Very funny.", r.picks[0].why)
    }

    @Test fun withoutAiRulesStillGiveThreeWithReasons() = runBlocking {
        tmdbAndGroq(null, groqStatus = 500)
        val s = store()
        s.addLiked("Together", "tv:1", "Schitt's Creek")      // recommendations of tv:1 include tv:3 → "Fans of…"
        val r = Recommender(s, Tmdb("k", server.url("/3").toString().trimEnd('/')), AiPicker(listOf(Groq("g", "m", server.url("/openai/v1").toString().trimEnd('/')))))
            .pick("Together", "weekday-late", Ask(Mood.Light), null, 90, "Tuesday night")
        assertEquals(5, r.picks.size)
        assertTrue(r.picks.any { it.title.kind == Kind.Tv } && r.picks.any { it.title.kind == Kind.Movie })
        assertEquals("Fans of Schitt's Creek also loved it.", r.picks.first { it.title.key == "tv:3" }.why)
        assertTrue(r.pool.none { it.key == "tv:1" })          // already watched
    }

    @Test fun shortEveningsSkipFilms() = runBlocking {
        tmdbAndGroq(null, 500)
        val r = Recommender(store(), Tmdb("k", server.url("/3").toString().trimEnd('/')), null)
            .pick("Alex", "weekday-late", Ask(Mood.Light), null, 40, "late")
        assertTrue(r.picks.all { it.title.kind == Kind.Tv })
        assertTrue(server.takeRequestsPaths().none { it.startsWith("/3/discover/movie") })
    }

    @Test fun aFilmIsIncludedEvenWhenTheAiPicksOnlySeries() = runBlocking {
        tmdbAndGroq("""{"picks":[{"id":"tv:6","why":"a"},{"id":"tv:5","why":"b"},{"id":"tv:4","why":"c"}]}""")
        val r = Recommender(store(), Tmdb("k", server.url("/3").toString().trimEnd('/')), AiPicker(listOf(Groq("g", "m", server.url("/openai/v1").toString().trimEnd('/')))))
            .pick("Together", "weekend-late", Ask(Mood.Light), null, null, "Saturday night")
        assertEquals(listOf("tv:6", "tv:5", "tv:4"), r.picks.take(3).map { it.title.key })
        assertTrue(r.picks.any { it.title.kind == Kind.Movie })
    }

    @Test fun newMoodAsksTmdbForRecentTitlesOnly() = runBlocking {
        tmdbAndGroq(null, 500)
        val now = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 21) }
        val r = Recommender(store(), Tmdb("k", server.url("/3").toString().trimEnd('/')), null, now = { now.clone() as Calendar })
            .pick("Alex", "weekend-late", Ask(Mood.New), null, null, "late")
        val discover = server.takeRequestsPaths().filter { it.startsWith("/3/discover") }
        assertTrue(discover.isNotEmpty())
        assertTrue(discover.toString(), discover.all { "2025-11-21" in it })   // 12 months back, every query
        assertTrue(r.picks.all { it.title.fresh })
    }

    @Test fun otherMoodsAlsoFetchRecentTitlesAndBoostThem() = runBlocking {
        tmdbAndGroq(null, 500)
        Recommender(store(), Tmdb("k", server.url("/3").toString().trimEnd('/')), null).pick("Alex", "weekend-late", Ask(Mood.Light), null, null, "late")
        val discover = server.takeRequestsPaths().filter { it.startsWith("/3/discover/tv") }
        assertEquals(2, discover.size)                                          // recent + all-time
        assertEquals(1, discover.count { "air_date.gte" in it })
        val old = Title(Kind.Tv, 1, "Seinfeld", year = "1989", rating = 8.2, votes = 4000, popularity = 80.0)
        val new = Title(Kind.Tv, 2, "New show", year = "2026", rating = 7.8, votes = 300, popularity = 80.0, fresh = true)
        assertTrue(Recommender.score(new, emptyMap()) > Recommender.score(old, emptyMap()))
    }

    @Test fun sayingNewMeansRecent() {
        assertEquals(Mood.New, RulesAsk.parse("anything new tonight?").mood)
        val a = RulesAsk.parse("something new and funny")
        assertEquals(Mood.Light, a.mood); assertTrue(a.newOnly)
    }

    @Test fun wikidataFindsTheNetflixIdForATmdbTitle() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(r: RecordedRequest): MockResponse {
                val q = r.requestUrl?.queryParameter("query").orEmpty()
                val hit = "wdt:P4983 \"66732\"" in q && "wdt:P1874" in q
                val body = JSONObject().put("results", JSONObject().put("bindings", org.json.JSONArray().apply {
                    if (hit) put(JSONObject().put("nf", JSONObject().put("value", "80057281")))
                }))
                return MockResponse().setBody(body.toString())
            }
        }
        server.start()
        val w = com.home.tvremote.picks.Wikidata(server.url("/sparql").toString())
        assertEquals("80057281", w.netflixId("tv:66732"))
        assertNull(w.netflixId("movie:1"))
        assertNull(w.netflixId("bad"))
    }

    @Test fun playOnNetflixOpensTheTitleWhenItsIdIsKnown() {
        val opened = mutableListOf<String>()
        val tv = object : TvActions by FakeTv() {
            override fun openApp(appId: String, title: String) { opened += "app $appId" }
            override fun openTitle(appId: String, title: String, extras: JSONObject) { opened += "title $appId $extras" }
        }
        val d = StandardTestDispatcher()
        val pc = PicksController(store(), tv, CoroutineScope(d), clock = { at(Calendar.TUESDAY, 21, 30) }, onDevice = null,
            netflixIdOf = { if (it == "tv:7") "80057281" else null })
        pc.openPicker()
        pc.picks += Pick(Title(Kind.Tv, 7, "Stranger Things", service = "Netflix"), "why")
        pc.picks += Pick(Title(Kind.Tv, 8, "Unknown", service = "Netflix"), "why")
        pc.play(0); d.scheduler.advanceTimeBy(100)
        assertEquals(1, opened.size)
        assertTrue(opened[0], opened[0].startsWith("title netflix") && "80057281" in opened[0] && "source_type=4" in opened[0])
        pc.openPicker(); pc.picks += Pick(Title(Kind.Tv, 8, "Unknown", service = "Netflix"), "why")
        pc.play(0); d.scheduler.advanceTimeBy(100)
        assertTrue(opened[1], opened[1].startsWith("title netflix") && "search?q=Unknown" in opened[1])   // no ID: Netflix's search page
    }

    @Test fun netflixLinkVariantsDiffer() {
        val a = com.home.tvremote.picks.DeepLinks.netflix("1", "A").toString()
        val b = com.home.tvremote.picks.DeepLinks.netflix("1", "B").toString()
        val c = com.home.tvremote.picks.DeepLinks.netflix("1", "C").toString()
        assertEquals(3, setOf(a, b, c).size)
        assertTrue("contentTarget" in b)
    }

    @Test fun favouritesSteerPicksAndAreNeverOfferedThemselves() = runBlocking {
        tmdbAndGroq(null, 500)
        val s = store()
        s.setFavourites("Alex", listOf("tv:1" to "Schitt's Creek"))      // TMDB "also liked" of tv:1 includes tv:3
        val r = Recommender(s, Tmdb("k", server.url("/3").toString().trimEnd('/')), null)
            .pick("Alex", "weekend-late", Ask(Mood.Light), null, null, "late")
        assertTrue(r.pool.none { it.key == "tv:1" })
        assertEquals("Fans of Schitt's Creek also loved it.", r.picks.first { it.title.key == "tv:3" }.why)
        assertEquals("tv:3", r.pool.first().key)                          // the boost puts it first
    }

    @Test fun favouritesCardShowsUntilThreeOrLater() {
        val s = store()
        assertEquals(listOf("tv:1" to "A", "movie:2" to "B"), s.apply { setFavourites("Alex", listOf("tv:1" to "A", "movie:2" to "B")) }.favourites("Alex"))
        s.setFavourites("Sam", listOf("tv:1" to "A", "tv:9" to "C"))
        assertEquals(3, s.favourites(Catalog.TOGETHER).size)               // everyone's, without duplicates
        val pc = PicksController(s, FakeTv(), CoroutineScope(StandardTestDispatcher()), clock = { at(Calendar.TUESDAY, 21, 30) }, onDevice = null, netflixIdOf = null)
        assertTrue(pc.wantsFavourites("Alex"))
        assertFalse(pc.wantsFavourites(Catalog.TOGETHER))
        pc.saveFavourites("Alex", s.favourites("Alex") + ("tv:5" to "D"))
        assertFalse(pc.wantsFavourites("Alex"))
        pc.favouritesLater("Sam")
        assertFalse(pc.wantsFavourites("Sam"))
    }

    @Test fun tmdbSearchKeepsOnlySeriesAndFilms() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(r: RecordedRequest) = MockResponse().setBody(JSONObject().put("results", org.json.JSONArray()
                .put(JSONObject().put("media_type", "tv").put("id", 1).put("name", "Fleabag").put("first_air_date", "2016-07-21"))
                .put(JSONObject().put("media_type", "person").put("id", 2).put("name", "Phoebe"))
                .put(JSONObject().put("media_type", "movie").put("id", 3).put("title", "Fleabag Live").put("release_date", "2019-09-12"))).toString())
        }
        server.start()
        val r = Tmdb("k", server.url("/3").toString().trimEnd('/')).search("fleab")
        assertEquals(listOf("tv:1", "movie:3"), r.map { it.key })
        assertEquals("2016", r[0].year)
    }

    private class OpenLog(val typed: Boolean = false) : TvActions by FakeTvShared {
        val calls = mutableListOf<String>()
        override fun openApp(appId: String, title: String) { calls += "app $appId" }
        override fun openTitle(appId: String, title: String, extras: JSONObject) { calls += "link $extras" }
        override fun typeIntoSearch(text: String): Boolean { calls += "type $text"; return typed }
        override fun knownAppIds() = setOf("netflix", "com.disney.disneyplus-prod")
    }

    @Test fun notItTriesSearchThenOtherFormatsThenTyping() {
        val tv = OpenLog(typed = true)
        val d = StandardTestDispatcher()
        val pc = PicksController(store(), tv, CoroutineScope(d), clock = { at(Calendar.TUESDAY, 21, 30) }, onDevice = null, netflixIdOf = { "80025678" })
        pc.openPicker(); pc.picks += Pick(Title(Kind.Tv, 7, "The Crown", service = "Netflix"), "why")
        pc.play(0); d.scheduler.advanceTimeBy(100)
        assertTrue(tv.calls.last(), "titles/movies/80025678" in tv.calls.last())               // format 1 first
        assertEquals("Not it? Search Netflix for it", pc.fixLabel())
        pc.tryNextFix(); assertTrue(tv.calls.last(), "search?q=The%20Crown" in tv.calls.last())
        assertEquals("Still not it? Try another way", pc.fixLabel())
        pc.tryNextFix(); assertTrue(tv.calls.last(), "www.netflix.com/title/80025678" in tv.calls.last())   // format 3
        assertEquals("Type “The Crown” in the search box", pc.fixLabel())
        pc.tryNextFix(); assertEquals("type The Crown", tv.calls.last())
        pc.tryNextFix(); assertEquals("type The Crown", tv.calls.last())                       // can be tapped again
    }

    @Test fun netflixWithoutAnIdOpensItsSearchPage() {
        val tv = OpenLog()
        val d = StandardTestDispatcher()
        val pc = PicksController(store(), tv, CoroutineScope(d), clock = { at(Calendar.TUESDAY, 21, 30) }, onDevice = null, netflixIdOf = { null })
        pc.openPicker(); pc.picks += Pick(Title(Kind.Movie, 9, "Glass Onion", service = "Netflix"), "why")
        pc.play(0); d.scheduler.advanceTimeBy(100)
        assertTrue(tv.calls.last(), "search?q=Glass%20Onion" in tv.calls.last())
        assertEquals("Type “Glass Onion” in the search box", pc.fixLabel())
    }

    @Test fun voiceFindsATitleOnTheirServices() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(r: RecordedRequest): MockResponse {
                val p = r.path ?: ""
                fun json(o: Any) = MockResponse().setBody(o.toString())
                return when {
                    p.startsWith("/3/search/multi") -> json(JSONObject().put("results", org.json.JSONArray()
                        .put(JSONObject().put("media_type", "tv").put("id", 65494).put("name", "The Crown").put("first_air_date", "2016-11-04"))))
                    p.contains("/watch/providers") -> json(JSONObject().put("results", JSONObject().put("US", JSONObject()
                        .put("flatrate", org.json.JSONArray().put(JSONObject().put("provider_name", "Netflix"))))))
                    else -> json(JSONObject())
                }
            }
        }
        server.start()
        val tv = OpenLog()
        val d = StandardTestDispatcher()
        val pc = PicksController(store(), tv, CoroutineScope(d), clock = { at(Calendar.TUESDAY, 21, 30) }, onDevice = null,
            netflixIdOf = { "80025678" }, tmdbBase = server.url("/3").toString().trimEnd('/'))
        assertTrue(pc.openByName("the crown"))
        d.scheduler.advanceTimeBy(100)
        assertTrue(tv.calls.toString(), tv.calls.any { "80025678" in it })
        assertEquals("The Crown", pc.lastOpened?.name)
    }

    private fun MockWebServer.takeRequestsPaths(): List<String> = (0 until requestCount).mapNotNull { takeRequest().path }

    // ---------------- AI fallback chain ----------------
    private class FakeEngine(override val name: String, val reply: String?) : AiEngine {
        var calls = 0
        override suspend fun json(system: String, user: String): JSONObject { calls++; return extractJson(reply ?: throw IllegalStateException("$name down")) }
    }

    @Test fun firstEngineDownSecondAnswers() = runBlocking {
        val cands = listOf(Title(Kind.Tv, 1, "A"), Title(Kind.Tv, 2, "B"), Title(Kind.Movie, 3, "C"))
        val groq = FakeEngine("Groq", null)
        // Small on-phone models often wrap JSON in text / code fences.
        val phone = FakeEngine("On-phone", "Sure!\n```json\n{\"picks\":[{\"id\":\"tv:2\",\"why\":\"Short and sweet.\"},{\"id\":\"x:9\",\"why\":\"bad\"}]}\n```")
        val ai = AiPicker(listOf(groq, phone))
        assertEquals(listOf("tv:2" to "Short and sweet."), ai.choose("now", "none", cands))
        assertEquals("On-phone", ai.lastEngine)
        assertTrue(ai.lastError!!.startsWith("Groq"))
        assertNull(AiPicker(listOf(FakeEngine("Groq", null), FakeEngine("On-phone", """{"picks":[{"id":"x:1","why":"invented"}]}"""))).choose("now", "none", cands))
    }

    @Test fun chainOrderFollowsTheChoice() {
        val phone = FakeEngine("On-phone", "{}")
        val s = store().apply { groqKey = "g" }
        val pc = PicksController(s, FakeTv(), CoroutineScope(StandardTestDispatcher()), onDevice = phone)
        val order = { pc.aiChain().engines.map { it.name } }
        assertEquals("groq", s.aiChoice)                                  // default with a Groq key
        assertEquals(listOf("Groq", "On-phone"), order())
        s.aiChoice = "phone"; assertEquals(listOf("On-phone", "Groq"), order())
        s.aiChoice = "rules"; assertEquals(emptyList<String>(), order())
        assertEquals("phone", PicksStore(FakePrefs()).aiChoice)            // default without a key
    }

    // ---------------- quick-backs ----------------
    private object FakeTvShared : TvActions by FakeTv()

    private class FakeTv : TvActions {
        val opened = mutableListOf<String>()
        override fun openApp(appId: String, title: String) { opened += appId }
        override fun knownAppIds() = setOf("netflix", "hoichoi.tv")
        override fun openOnTvBrowser(url: String) {}
        override fun toastOnTv(message: String) {}
        override fun goHome() {}
        override val foreground: String? = null
        override fun phoneIp() = "127.0.0.1"
        override val tvIp: String? = null
        override fun note(message: String, error: Boolean) {}
    }

    private fun controller(tv: FakeTv = FakeTv()) =
        PicksController(store(), tv, CoroutineScope(StandardTestDispatcher()), clock = { at(Calendar.TUESDAY, 21, 30) }, onDevice = null, netflixIdOf = null)

    @Test fun quickBackIsRecordedAndLongWatchIsLiked() {
        val tv = FakeTv()
        val pc = controller(tv)
        pc.openPicker()
        assertEquals(Step.Choose, pc.step)
        assertEquals("Together", pc.who)
        pc.picks += Pick(Title(Kind.Tv, 7, "Panchayat", service = "Netflix"), "why")
        pc.play(0)
        assertEquals(listOf("netflix"), tv.opened)
        val t0 = pc.store.sessions().last().playedAt!!
        pc.onForeground("netflix", t0 + 30_000)
        pc.onForeground("com.webos.app.home", t0 + 30_000 + 9 * 60_000) // 9 min after the app came up
        val s = pc.store.sessions().last()
        assertEquals("quick-back", s.outcome); assertEquals(9, s.leftAfterMin)
        assertTrue(pc.store.recentBails("Together").contains("tv:7"))

        pc.openPicker()
        pc.picks += Pick(Title(Kind.Movie, 8, "Film", service = "Netflix"), "why")
        pc.play(0)
        val t1 = pc.store.sessions().last().playedAt!!
        pc.onForeground("netflix", t1 + 10_000)
        pc.onForeground("com.webos.app.home", t1 + 95 * 60_000)
        assertEquals("watched", pc.store.sessions().last().outcome)
        assertTrue(pc.store.liked("Together").any { it.first == "movie:8" })
        assertTrue(pc.diagnostics().contains("Quick-backs"))
    }

    @Test fun closingBeforeAnyPicksIsNotGivingUp() {
        val pc = controller()
        pc.openPicker(); pc.close()
        assertEquals("browsed", pc.store.sessions().last().outcome)
        assertTrue(pc.diagnostics(), pc.diagnostics().contains("0 gave up") && pc.diagnostics().contains("closed before any picks"))
    }

    @Test fun leavingWithinTwoMinutesIsNotAQuickBack() {
        val tv = FakeTv()
        val pc = controller(tv)
        pc.openPicker()
        pc.picks += Pick(Title(Kind.Tv, 9, "Modern Family", service = "Netflix"), "why")
        pc.play(0)
        val t0 = pc.store.sessions().last().playedAt!!
        pc.onForeground("com.webos.app.home", t0 + 5_000)        // before the app even came up: ignored
        pc.onForeground("netflix", t0 + 20_000)
        pc.onForeground("com.webos.app.home", t0 + 80_000)       // 1 min in: a no-start
        assertFalse(pc.store.recentBails("Together").contains("tv:9"))
        assertTrue(pc.store.liked("Together").none { it.first == "tv:9" })
        pc.onForeground("netflix", t0 + 120_000)                 // came back: measuring again from here
        pc.onForeground("com.webos.app.home", t0 + 120_000 + 5 * 60_000)
        val s = pc.store.sessions().last()
        assertEquals("quick-back", s.outcome); assertEquals(5, s.leftAfterMin)

        // A no-start that never comes back is recorded as such and excluded from the percentages.
        pc.openPicker()
        pc.picks += Pick(Title(Kind.Movie, 10, "Film", service = "Netflix"), "why")
        pc.play(0)
        val t1 = pc.store.sessions().last().playedAt!!
        pc.onForeground("netflix", t1 + 10_000)
        pc.onForeground("com.webos.app.home", t1 + 70_000)
        assertEquals("no-start", pc.store.sessions().last().outcome)
        val d = pc.diagnostics()
        assertTrue(d, d.contains("Quick-backs: <15 min 100%") && d.contains("1 left within 2 min (not counted)"))
    }

    @Test fun learnedAppIdsAreUsedForLesserKnownServices() {
        val pc = controller()
        assertEquals("hoichoi.tv", pc.appIdFor(Catalog.SERVICES.first { it.name == "hoichoi" }))
        assertEquals("netflix", pc.appIdFor(Catalog.SERVICES.first { it.name == "Netflix" }))
        assertNull(pc.appIdFor(Catalog.SERVICES.first { it.name == "Max" }))   // never opened: must be learned first
    }

    // ---------------- the TV page ----------------
    @Test fun tvPageNeedsTheTokenAndForwardsClicks() {
        val clicks = mutableListOf<String>()
        val focused = mutableListOf<String>()
        val web = PicksWeb(state = { """{"v":1}""" }, action = { d, v -> clicks += "$d=$v" }, allowedIp = null, focus = { focused += it })
        web.start(preferredPort = 0)
        try {
            fun get(path: String) = (URL("http://127.0.0.1:${web.port}$path").openConnection() as HttpURLConnection).run {
                responseCode to (if (responseCode == 200) inputStream.bufferedReader().readText() else "")
            }
            assertEquals(403, get("/picks").first)
            assertEquals(403, get("/picks?k=wrong").first)
            assertTrue(get("/picks?k=${web.token}").second.contains("What should we watch?"))
            assertEquals("""{"v":1}""", get("/s?k=${web.token}").second)
            get("/a?k=${web.token}&do=play&v=1")
            assertEquals(listOf("play=1"), clicks)
            get("/f?k=${web.token}&l=Seinfeld%20%C2%B7%20Play")
            assertEquals(listOf("Seinfeld · Play"), focused)
            assertEquals(403, get("/f?l=x").first)
        } finally { web.stop() }
    }

    @Test fun onTvTheTouchpadDrivesTheTvPointer() {
        val moves = mutableListOf<String>()
        val tv = object : TvActions by FakeTv() {
            override fun pointerMove(dx: Float, dy: Float) { moves += "move" }
            override fun pointerTap() { moves += "tap" }
        }
        val pc = PicksController(store(), tv, CoroutineScope(StandardTestDispatcher()), clock = { at(Calendar.TUESDAY, 21, 30) }, onDevice = null, netflixIdOf = null)
        pc.openPicker(); pc.changeShowOnTv(true)
        assertTrue(pc.tvPageOn)
        pc.move(3f, 4f); pc.tap()
        assertEquals(listOf("move", "tap"), moves)
        pc.changeShowOnTv(false)
        assertFalse(pc.tvPageOn)
    }

    // ---------------- voice ----------------
    @Test fun voiceOpensThePicker() {
        val p = CommandParser(emptyList())
        assertEquals(Command.WhatToWatch(null), p.parse("What should we watch tonight?"))
        assertEquals(Command.WhatToWatch("find us something funny"), p.parse("find us something funny"))
        assertEquals(Command.Search("cooking videos", null), p.parse("find cooking videos"))
    }

    @Test fun rulesUnderstandSimpleRequests() {
        val a = RulesAsk.parse("something short and funny in Bengali")
        assertEquals(Mood.Light, a.mood); assertEquals("bn", a.language); assertEquals(35, a.maxMinutes)
        assertEquals(Kind.Movie, RulesAsk.parse("a gripping movie").kind)
        assertFalse(RulesAsk.parse("anything").mood != Mood.Any)
    }
}
