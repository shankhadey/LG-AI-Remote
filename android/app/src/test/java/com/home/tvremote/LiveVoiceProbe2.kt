package com.home.tvremote

import com.home.tvremote.picks.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assume.assumeTrue
import org.junit.Test

class LiveVoiceProbe2 {
    @Test fun probe() = runBlocking {
        val key = System.getenv("TMDB_KEY"); assumeTrue(!key.isNullOrBlank())
        val log = mutableListOf<String>()
        val tv = object : TvActions {
            override fun openApp(appId: String, title: String) { log += "open $appId" }
            override fun openTitle(appId: String, title: String, extras: org.json.JSONObject) { log += "link $appId" }
            override fun knownAppIds() = setOf("netflix", "amazon", "com.disney.disneyplus-prod", "hulu", "com.apple.appletv")
            override fun openOnTvBrowser(url: String) {}
            override fun toastOnTv(message: String) {}
            override fun goHome() {}
            override val foreground: String? = null
            override fun phoneIp(): String? = null
            override val tvIp: String? = null
            override fun note(message: String, error: Boolean) {}
            override fun log(message: String) { println("PROBE log $message") }
        }
        val s = PicksStore(FakePrefs()).apply {
            people = listOf("A", "B"); languages = listOf("en"); tmdbKey = key!!
            services = listOf("Netflix", "Prime Video", "Disney+", "Apple TV+", "Hulu", "hoichoi", "ZEE5")
        }
        val pc = PicksController(s, tv, CoroutineScope(StandardTestDispatcher()), onDevice = null, titleIdsOf = null, directLinks = false)
        for (q in listOf("show me some adam sandler movies", "some Adam Sandler movies", "adam sandler films", "movies by christopher nolan",
                "pedro pascal shows", "show me some funny movies", "show me something scary", "the office", "movies with jennifer aniston and adam sandler"))
            println("PROBE resolve '$q' -> ${pc.resolvePerson(q)}")
        val b = pc.findAndOpen("barbie")
        println("PROBE barbie -> $b · " + ((b as? PicksController.Found.Elsewhere)?.let { pc.elsewhere(it) } ?: ""))
        for (q in listOf("adam sandler" to Kind.Movie, "christopher nolan" to Kind.Movie, "jennifer aniston" to Kind.Movie, "bryan cranston" to null, "jennifer aniston and adam sandler" to Kind.Movie)) {
            pc.openPicker()
            val msg = pc.showPerson(q.first, q.second)
            println("PROBE person ${q.first} -> ${msg ?: pc.picks.joinToString { it.title.name + " (" + it.title.service + ")" }}")
        }
    }
}
