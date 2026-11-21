package com.home.tvremote.picks

import kotlin.math.ln
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/** Picks shown now, plus the ranked remainder used for "Not now", "Never" and "Show more". */
data class PickSet(val picks: List<Pick>, val pool: List<Title>, val fans: Map<String, String>)

class Recommender(
    private val store: PicksStore,
    private val tmdb: Tmdb,
    private val ai: AiPicker?,
    private val now: () -> java.util.Calendar = { java.util.Calendar.getInstance() },
) {
    /** yyyy-MM-dd, [months] ago. */
    private fun monthsAgo(months: Int): String = now().apply { add(java.util.Calendar.MONTH, -months) }
        .let { String.format(java.util.Locale.US, "%04d-%02d-%02d", it.get(java.util.Calendar.YEAR), it.get(java.util.Calendar.MONTH) + 1, it.get(java.util.Calendar.DAY_OF_MONTH)) }

    private val services get() = Catalog.SERVICES.filter { it.name in store.services }

    /** What a person's favourites have in common, for a small genre lift. */
    data class Taste(val genres: Set<Int> = emptySet()) { companion object { val NONE = Taste() } }

    private fun ids(s: String) = s.split('|').mapNotNull { it.toIntOrNull() }.toSet()
    private fun genreFits(t: Title, mood: Mood): Boolean {
        val inc = ids(if (t.kind == Kind.Movie) mood.movieGenres else mood.tvGenres)
        val exc = ids(if (t.kind == Kind.Movie) mood.movieWithout else mood.tvWithout)
        // The mood's genre must be what the title mainly is: its first genre for Light and Cozy (so dramas with a
        // comedy tag don't count as light), first or second for the others.
        val lead = t.genres.take(if (mood == Mood.Light || mood == Mood.Cozy) 1 else 2)
        return (inc.isEmpty() || lead.any { it in inc }) && t.genres.none { it in exc }
    }
    private fun thisYear() = now().get(java.util.Calendar.YEAR)
    private fun age(t: Title) = t.year.toIntOrNull()?.let { thisYear() - it } ?: 99

    /**
     * Candidates (all fetched in parallel): the mood on their services, most popular overall and released in the last
     * two years; plus "fans also liked" of their favourites and recent likes that fit the mood and are on their
     * services. Scored on quality, what's trending this week, recency and taste; then re-ordered for variety.
     */
    suspend fun pick(who: String, slot: String, ask: Ask, languagePref: String?, minutesLeft: Int?, context: String): PickSet = kotlinx.coroutines.coroutineScope {
        val providers = tmdb.providerIds(services)
        if (providers.isEmpty()) throw PicksException("None of your services were found in TMDB's US list")

        val language = ask.language ?: languagePref
        val household = store.languages.toSet()
        val maxMin = ask.maxMinutes ?: minutesLeft
        val kinds = ask.kind?.let { listOf(it) } ?: listOf(Kind.Tv, Kind.Movie)
        val newOnly = ask.newOnly || ask.mood == Mood.New
        val today = monthsAgo(0)
        fun cap(k: Kind): Int? = when {
            maxMin == null -> null
            k == Kind.Movie -> if (maxMin < 70) -1 else maxMin + 10    // -1: no film fits
            else -> minOf(maxMin, 70).coerceAtLeast(25)
        }
        val wanted = kinds.filter { cap(it) != -1 }
        suspend fun safe(block: suspend () -> List<Title>) = runCatching { block() }.getOrElse { if (it is PicksException) throw it; emptyList() }

        val lists: List<Deferred<List<Title>>> = wanted.flatMap { k ->
            val c = cap(k)
            buildList<Deferred<List<Title>>> {
                add(async { safe { tmdb.discover(k, providers, ask.mood, language, c, since = monthsAgo(if (newOnly) 12 else 24), until = today) } })
                if (newOnly) add(async { safe { tmdb.discover(k, providers, ask.mood, language, c, page = 2, since = monthsAgo(12), until = today) } })
                else add(async { safe { tmdb.discover(k, providers, ask.mood, language, c) } })
                if (language != null && language != "en") add(async { safe { tmdb.discover(k, providers, ask.mood, language, c, page = 2) } })
            }
        }
        val trendJobs = wanted.map { k -> async { runCatching { tmdb.trending(k) }.getOrDefault(emptyMap()) } }
        val sources = (store.favourites(who) + store.liked(who).reversed()).distinctBy { it.first }.take(5)
        val recJobs = sources.map { src -> async { src.second to runCatching { tmdb.recommendationTitles(src.first) }.getOrDefault(emptyList()) } }
        val tasteJob = async { Taste(store.favourites(who).take(5).flatMap { runCatching { tmdb.genresOf(it.first) }.getOrDefault(emptyList()) }.toSet()) }

        val discovered = lists.flatMap { it.await() }
        val trend = trendJobs.flatMap { it.await().entries }.associate { it.key to it.value }
        val fans = mutableMapOf<String, String>(); val fanCount = mutableMapOf<String, Int>(); val recTitles = mutableListOf<Title>()
        for (j in recJobs) { val (name, list) = j.await(); for (t in list) { fans.putIfAbsent(t.key, name); fanCount.merge(t.key, 1, Int::plus); recTitles += t } }
        val taste = tasteJob.await()

        // Favourites' "fans also liked" that fit the mood: check they're on their services (in parallel) and add them.
        val known = discovered.map { it.key }.toSet()
        val extra = recTitles.distinctBy { it.key }
            .filter { it.key !in known && it.kind in wanted && genreFits(it, ask.mood) && it.votes >= 50 && (!newOnly || age(it) <= 1) }
            .filter { language == null || it.language == language }
            .sortedByDescending { fanCount[it.key] ?: 0 }.take(12)
            .map { t -> async { runCatching { tmdb.enrich(t, services) }.getOrNull() } }
            .mapNotNull { it.await() }
            .filter { it.service != null && fitsCap(it, maxMin) }

        val blocked = store.never(who) + store.notNow(who, slot) + store.recentBails(who) + store.liked(who).map { it.first } +
            store.favourites(who).map { it.first }
        val scored = (discovered + extra).distinctBy { it.key }
            .map { it.copy(fresh = age(it) <= 2, trendRank = trend[it.key] ?: -1) }
            .filter { it.key !in blocked }
            .filter { language != null || household.isEmpty() || it.language in household }
            .filter { genreFits(it, ask.mood) }
            .filter { it.votes >= (if (it.fresh) 40 else 80) }   // too few votes to trust the rating
            .associateWith { score(it, fans, taste, fanCount[it.key] ?: 0, thisYear()) }
        if (scored.isEmpty()) throw PicksException("Nothing matched — try another mood, language or “Any”")
        val pool = diversify(scored)

        val chosen = choosePicks(balanced(pool), who, ask, context, fans)
        finish(chosen, pool, fans, minutesLeft)
    }

    private fun fitsCap(t: Title, maxMin: Int?) = maxMin == null || t.runtimeMin == null ||
        (if (t.kind == Kind.Movie) t.runtimeMin <= maxMin + 10 else t.runtimeMin <= 75)

    /**
     * Variety: greedy re-ranking (maximal marginal relevance). Each next title is the best score minus a penalty for
     * being like ones already chosen (same main genre, both animated, same franchise), so five picks aren't five
     * crime procedurals.
     */
    private fun diversify(scored: Map<Title, Double>): List<Title> {
        val rest = scored.keys.sortedByDescending { scored[it] }.toMutableList()
        val out = mutableListOf<Title>()
        fun franchise(t: Title) = t.name.lowercase().split(Regex("[:\\s]+")).take(2).joinToString(" ")
        fun sim(a: Title, b: Title): Double =
            (if (a.genres.firstOrNull() != null && a.genres.firstOrNull() == b.genres.firstOrNull()) 0.5 else 0.0) +
            (if (16 in a.genres && 16 in b.genres) 0.6 else 0.0) +
            (if (franchise(a) == franchise(b)) 1.5 else 0.0)
        while (rest.isNotEmpty() && out.size < 40) {
            val next = rest.maxBy { t -> scored.getValue(t) - 0.6 * (out.maxOfOrNull { sim(it, t) } ?: 0.0) }
            out += next; rest -= next
        }
        return out + rest
    }

    /** "Show more": the next set from the pool, skipping what's been shown. */
    suspend fun more(prev: PickSet, shown: Set<String>, who: String, context: String, minutesLeft: Int?): PickSet {
        val rest = prev.pool.filter { it.key !in shown }
        if (rest.isEmpty()) throw PicksException("That's everything for this mood — try another one")
        val chosen = choosePicks(balanced(rest), who, Ask(), context, prev.fans)
        return finish(chosen, rest, prev.fans, minutesLeft)
    }

    /** Replacement for one pick (after Not now / Never): the best remaining title, with a rule-based reason. */
    suspend fun replacement(prev: PickSet, shown: Set<String>, minutesLeft: Int?): Pick? {
        for (t in prev.pool.filter { it.key !in shown }.take(4)) {
            val e = runCatching { tmdb.enrich(t, services) }.getOrDefault(t)
            if (fits(e, minutesLeft)) return Pick(e, ruleReason(e, prev.fans))
        }
        return null
    }

    /** The 15 the AI chooses from: best series and best films side by side, so neither crowds the other out. */
    private fun balanced(pool: List<Title>): List<Title> {
        val tv = pool.filter { it.kind == Kind.Tv }; val films = pool.filter { it.kind == Kind.Movie }
        if (tv.isEmpty() || films.isEmpty()) return pool.take(15)
        val keep = (tv.take(8) + films.take(7)).toSet()
        return pool.filter { it in keep }
    }

    /** At least one film when films are on offer (series tend to win on ratings alone). */
    private fun withFilm(picks: List<Pick>, top: List<Title>, fans: Map<String, String>): List<Pick> {
        if (picks.size < PICKS || picks.any { it.title.kind == Kind.Movie }) return picks
        val film = top.firstOrNull { it.kind == Kind.Movie } ?: return picks
        return picks.dropLast(1) + Pick(film, ruleReason(film, fans))
    }

    private suspend fun choosePicks(top: List<Title>, who: String, ask: Ask, context: String, fans: Map<String, String>): List<Pick> {
        if (ai != null && !ai.isEmpty) {
            val taste = buildString {
                val favs = store.favourites(who).map { it.second }
                val liked = store.liked(who).takeLast(8).map { it.second }.filter { it !in favs }
                if (favs.isNotEmpty()) append("loves ${favs.joinToString()}")
                if (liked.isNotEmpty()) append((if (favs.isNotEmpty()) "; recently enjoyed " else "enjoyed ") + liked.joinToString())
                if (favs.isEmpty() && liked.isEmpty()) append("no history yet")
                if (ask.likeTitles.isNotEmpty()) append("; asked for something like ${ask.likeTitles.joinToString()}")
            }
            val annotated = top.map { t -> fans[t.key]?.let { t.copy(overview = "(fans of $it also liked this) " + t.overview) } ?: t }
            val got = ai.choose(context, taste, annotated)
            lastEngine = if (got.isNullOrEmpty()) "Rules" else ai.lastEngine
            lastAiError = ai.lastError
            if (!got.isNullOrEmpty()) {
                val byKey = top.associateBy { it.key }
                val picks = got.mapNotNull { (k, why) -> byKey[k]?.let { Pick(it, why.ifBlank { ruleReason(it, fans) }) } }
                if (picks.size == PICKS) return withFilm(picks, top, fans)
                return withFilm((picks + rulePicks(top.filter { t -> picks.none { it.title.key == t.key } }, fans)).take(PICKS), top, fans)
            }
        } else lastEngine = "Rules"
        return rulePicks(top, fans)
    }

    /** Without AI: the top [PICKS], with two films when there are films and series to choose from. */
    private fun rulePicks(top: List<Title>, fans: Map<String, String>): List<Pick> {
        val out = mutableListOf<Title>()
        top.firstOrNull { it.kind == Kind.Tv }?.let { out += it }
        top.filter { it.kind == Kind.Movie }.take(2).let { out += it }
        val seriesLeft = { top.any { it.kind == Kind.Tv && it !in out } }
        for (t in top) { if (out.size >= PICKS) break; if (t !in out && !(t.kind == Kind.Movie && out.count { it.kind == Kind.Movie } >= 2 && seriesLeft())) out += t }
        for (t in top) { if (out.size >= PICKS) break; if (t !in out) out += t }
        return out.take(PICKS).map { Pick(it, ruleReason(it, fans)) }
    }

    private suspend fun finish(chosen: List<Pick>, pool: List<Title>, fans: Map<String, String>, minutesLeft: Int?): PickSet {
        val out = mutableListOf<Pick>()
        val used = chosen.map { it.title.key }.toMutableSet()
        for (p in chosen) {
            var e = runCatching { tmdb.enrich(p.title, services) }.getOrDefault(p.title)
            var why = p.why
            if (!fits(e, minutesLeft)) {
                // Too long for tonight: swap in the next title that fits.
                // Too long: try the next films first (a shorter one keeps the mix), then a series.
                for (alt in pool.filter { it.key !in used && it.kind == Kind.Movie }.take(3) + pool.filter { it.key !in used && it.kind == Kind.Tv }.take(1)) {
                    val a = runCatching { tmdb.enrich(alt, services) }.getOrDefault(alt)
                    if (fits(a, minutesLeft)) { used += alt.key; e = a; why = ruleReason(a, fans); break }
                }
            }
            out += Pick(e, why)
        }
        return PickSet(out, pool, fans)
    }

    private fun fits(t: Title, minutesLeft: Int?) =
        minutesLeft == null || t.kind == Kind.Tv || (t.runtimeMin ?: 0) <= minutesLeft + 20

    companion object {
        /** Last AI failure (shown in Diagnostics); picks fall back to rules when the AI fails. */
        @Volatile var lastAiError: String? = null
        /** Which engine made the last set of picks: "Groq", "On-phone" or "Rules". */
        @Volatile var lastEngine: String? = null

        private val GENRES = mapOf(35 to "Comedy", 18 to "Drama", 80 to "Crime", 9648 to "Mystery", 53 to "Thriller",
            878 to "Sci-fi", 10765 to "Sci-fi & fantasy", 14 to "Fantasy", 10749 to "Romance", 10751 to "Family",
            16 to "Animation", 28 to "Action", 10759 to "Action", 99 to "Documentary", 27 to "Horror", 12 to "Adventure")

        /**
         * Quality (rating, shrunk towards 7.0 until a title has a few hundred votes) + trending this week + a little
         * popularity + recency + taste (fans of their favourites, shared genres) − cartoons unless they like them.
         * Tuned on live TMDB data: raw popularity alone favoured decades-old procedurals with endless reruns.
         */
        fun score(t: Title, fans: Map<String, String>, taste: Taste = Taste.NONE, fanCount: Int = 0,
                  thisYear: Int = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)): Double {
            val m = 300.0; val c = 6.6
            val quality = (t.votes * t.rating + m * c) / (t.votes + m)
            val trend = if (t.trendRank >= 0) 1.2 * (1 - t.trendRank / 40.0) else 0.0
            val pop = 0.12 * ln(1 + t.popularity)
            val fan = if (t.key in fans) 0.9 + 0.3 * (fanCount - 1).coerceAtLeast(0) else 0.0
            val genre = 0.15 * t.genres.count { it in taste.genres }.coerceAtMost(3)
            val cartoon = if (16 in t.genres && 16 !in taste.genres) -0.7 else 0.0
            // Newness only helps once a title has enough votes and a decent rating.
            val confidence = t.votes / (t.votes + 150.0)
            val rec = recency(t, thisYear).let { if (it > 0) (if (t.rating >= 7.0) it * confidence else 0.0) else it }
            return quality + trend * confidence + pop + fan + genre + cartoon + rec
        }

        /** New things get a lift and very old series a dip; without it, classics with huge vote counts always win. */
        fun recency(t: Title, thisYear: Int = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)): Double {
            val age = t.year.toIntOrNull()?.let { thisYear - it } ?: return 0.0
            return when {
                age <= 1 -> 0.8
                age <= 3 -> 0.4
                t.kind == Kind.Tv && age > 20 -> -0.9
                t.kind == Kind.Tv && age > 10 -> -0.5
                age > 25 -> -0.3
                else -> 0.0
            }
        }

        fun ruleReason(t: Title, fans: Map<String, String>): String = fans[t.key]?.let { "Fans of $it also loved it." }
            ?: listOfNotNull(t.genres.firstNotNullOfOrNull { GENRES[it] }, if (t.rating > 0) "★ ${"%.1f".format(t.rating)}" else null,
                if (t.fresh) "new" else if (t.popularity > 50) "popular right now" else null).joinToString(" · ").ifEmpty { t.overview.take(80) }
    }
}
