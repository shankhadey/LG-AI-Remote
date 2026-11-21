package com.home.tvremote

import com.home.tvremote.picks.Ask
import com.home.tvremote.picks.Mood
import com.home.tvremote.picks.PicksCache
import com.home.tvremote.picks.PicksStore
import com.home.tvremote.picks.Recommender
import com.home.tvremote.picks.Tmdb
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Picks latency against live TMDB: cold (nothing cached), after warm-up, and repeat. TMDB_KEY=… to run. */
class LiveLatencyReport {
    @Test fun latency() = runBlocking {
        val key = System.getenv("TMDB_KEY"); assumeTrue(!key.isNullOrBlank())
        val s = PicksStore(FakePrefs()).apply {
            people = listOf("A", "B"); languages = listOf("en"); tmdbKey = key!!
            services = listOf("Netflix", "Prime Video", "Disney+", "Apple TV+", "Max", "Hulu", "hoichoi", "ZEE5")
            setFavourites("A", listOf("tv:76331" to "Succession", "tv:2316" to "The Office", "tv:95396" to "Severance"))
        }
        val cache = PicksCache()
        val r = Recommender(s, Tmdb(key!!), null, cache = cache, aiChooses = false)
        fun ms(block: suspend () -> Unit): Long { val t = System.currentTimeMillis(); runBlocking { block() }; return System.currentTimeMillis() - t }
        val cold = ms { r.pick("A", "weekend-late", Ask(Mood.Gripping), null, null, "x") }
        val cold2 = Recommender.lastTimings
        val r2 = Recommender(s, Tmdb(key), null, cache = PicksCache(), aiChooses = false)
        val warm = ms { r2.warm("A") }
        val afterWarm = ms { r2.pick("A", "weekend-late", Ask(Mood.Light), null, null, "x") }
        val t2 = Recommender.lastTimings
        val repeat = ms { r2.pick("A", "weekend-late", Ask(Mood.Cozy), null, 120, "x") }
        println("LATENCY cold ${cold} ms ($cold2) · warm-up ${warm} ms · after warm-up ${afterWarm} ms ($t2) · next mood ${repeat} ms")
    }
}
