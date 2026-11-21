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
        assertEquals(listOf("tv:6", "movie:101", "tv:5"), r.picks.map { it.title.key })   // "tv:999" (not a candidate) dropped
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
        assertEquals(3, r.picks.size)
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
        PicksController(store(), tv, CoroutineScope(StandardTestDispatcher()), clock = { at(Calendar.TUESDAY, 21, 30) }, onDevice = null)

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
        val pc = PicksController(store(), tv, CoroutineScope(StandardTestDispatcher()), clock = { at(Calendar.TUESDAY, 21, 30) }, onDevice = null)
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
