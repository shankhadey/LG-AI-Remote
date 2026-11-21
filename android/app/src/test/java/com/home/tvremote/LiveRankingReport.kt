package com.home.tvremote

import com.home.tvremote.picks.Ask
import com.home.tvremote.picks.Kind
import com.home.tvremote.picks.Mood
import com.home.tvremote.picks.PicksStore
import com.home.tvremote.picks.Recommender
import com.home.tvremote.picks.Tmdb
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Calendar

/**
 * Ranking report against live TMDB (rules only, no AI), run by hand:
 *   TMDB_KEY=… ./gradlew testDebugUnitTest --tests '*LiveRankingReport*'
 * Skipped when TMDB_KEY isn't set, so the normal suite never needs the network or a key.
 */
class LiveRankingReport {
    private val key = System.getenv("TMDB_KEY")

    private fun store(favs: List<Pair<String, String>>) = PicksStore(FakePrefs()).apply {
        people = listOf("A", "B"); languages = listOf("en"); tmdbKey = key ?: ""
        services = listOf("Netflix", "Prime Video", "Disney+", "Apple TV+", "Max", "Hulu", "hoichoi", "ZEE5")
        if (favs.isNotEmpty()) setFavourites("A", favs)
    }

    @Test fun report() = runBlocking {
        assumeTrue("set TMDB_KEY to run", !key.isNullOrBlank())
        val out = StringBuilder()
        val year = Calendar.getInstance().get(Calendar.YEAR)
        val favSets = mapOf("no favourites" to emptyList(), "favs: Succession, The Office, Severance" to
            listOf("tv:76331" to "Succession", "tv:2316" to "The Office", "tv:95396" to "Severance"))
        var n = 0; var withFilm = 0; var films = 0; var fresh2y = 0; var titles = 0; var fanHits = 0; var fits = 0; var filmsCounted = 0
        val years = mutableListOf<Int>(); var ms = 0L
        for ((favName, favs) in favSets) for (mood in Mood.entries) for (left in listOf(null, 120, 45)) {
            val s = store(favs)
            val r = Recommender(s, Tmdb(key!!), null)
            val t0 = System.currentTimeMillis()
            val set = runCatching { r.pick("A", "weekend-late", Ask(mood), null, left, "test") }
            ms += System.currentTimeMillis() - t0
            out.appendLine("\n== $favName · ${mood.label} · ${left?.let { "$it min left" } ?: "no time limit"} (${System.currentTimeMillis() - t0} ms)")
            val picks = set.getOrElse { out.appendLine("   ERROR ${it.message}"); continue }.picks
            n++
            if (picks.any { it.title.kind == Kind.Movie }) withFilm++
            for (p in picks) {
                val t = p.title; titles++
                if (t.kind == Kind.Movie) { films++; filmsCounted++; if (left == null || (t.runtimeMin ?: 0) <= left + 20) fits++ }
                t.year.toIntOrNull()?.let { years += it; if (year - it <= 2) fresh2y++ }
                if (p.why.startsWith("Fans of")) fanHits++
                out.appendLine("   ${if (t.kind == Kind.Movie) "🎬" else "📺"} ${t.name} (${t.year}${if (t.fresh) ", fresh" else ""}) · ${t.service} · ${t.runtimeMin ?: "?"} min · ★${t.rating} (${t.votes}) · pop ${t.popularity.toInt()} · score ${"%.2f".format(Recommender.score(t, emptyMap()))} — ${p.why}")
            }
        }
        years.sort()
        val summary = """
            |SETS $n · titles $titles · avg ${if (n > 0) ms / n else 0} ms per set
            |sets with a film: $withFilm/$n · films ${films * 100 / titles.coerceAtLeast(1)}% of titles · films fitting time: $fits/$filmsCounted
            |released within 2 years: ${fresh2y * 100 / titles.coerceAtLeast(1)}% · median year ${years.getOrNull(years.size / 2)} · oldest ${years.firstOrNull()}
            |"Fans of …" reasons: $fanHits
        """.trimMargin()
        File(System.getProperty("user.dir"), "build/ranking-report.txt").writeText(summary + "\n" + out)
        println(summary)
    }
}
