package com.home.tvremote.picks

import kotlin.math.ln

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

    suspend fun pick(who: String, slot: String, ask: Ask, languagePref: String?, minutesLeft: Int?, context: String): PickSet {
        val providers = tmdb.providerIds(services)
        if (providers.isEmpty()) throw PicksException("None of your services were found in TMDB's US list")

        val language = ask.language ?: languagePref
        val household = store.languages.toSet()
        val maxMin = ask.maxMinutes ?: minutesLeft
        val kinds = ask.kind?.let { listOf(it) } ?: listOf(Kind.Tv, Kind.Movie)

        val candidates = mutableListOf<Title>()
        for (k in kinds) {
            // Movies must fit the time left (+10 min grace); series are judged by episode length.
            val cap = when {
                maxMin == null -> null
                k == Kind.Movie -> if (maxMin < 70) continue else maxMin + 10
                else -> minOf(maxMin, 70).coerceAtLeast(25)
            }
            val newOnly = ask.newOnly || ask.mood == Mood.New
            // Recent titles: the whole list for "New", otherwise an extra helping so new things can compete.
            val recent = runCatching { tmdb.discover(k, providers, ask.mood, language, cap, since = monthsAgo(if (newOnly) 12 else 24)) }
                .getOrElse { if (it is PicksException) throw it; emptyList() }.map { it.copy(fresh = true) }
            candidates += recent
            if (!newOnly) candidates += runCatching { tmdb.discover(k, providers, ask.mood, language, cap) }.getOrElse { if (it is PicksException) throw it; emptyList() }
            if (language != null && language != "en" && candidates.size < 12) {
                candidates += runCatching { tmdb.discover(k, providers, ask.mood, language, cap, page = 2) }.getOrDefault(emptyList())
            }
        }

        val blocked = store.never(who) + store.notNow(who, slot) + store.recentBails(who) + store.liked(who).map { it.first } +
            store.favourites(who).map { it.first }
        val fans = fansMap(who)
        val pool = candidates.distinctBy { it.key }
            .filter { it.key !in blocked }
            .filter { language != null || household.isEmpty() || it.language in household }
            .sortedByDescending { score(it, fans) }
        if (pool.isEmpty()) throw PicksException("Nothing matched — try another mood, language or “Any”")

        val chosen = choosePicks(balanced(pool), who, ask, context, fans)
        return finish(chosen, pool, fans, minutesLeft)
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

    /** Without AI: the top [PICKS], mixing series and films when both are available. */
    private fun rulePicks(top: List<Title>, fans: Map<String, String>): List<Pick> {
        val out = mutableListOf<Title>()
        top.firstOrNull { it.kind == Kind.Tv }?.let { out += it }
        top.firstOrNull { it.kind == Kind.Movie }?.let { out += it }
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

    /** TMDB recommendations of what this person liked: candidate key → liked title. */
    private suspend fun fansMap(who: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        // Favourites first (what they said they love), then recent likes; up to 5 titles' "also liked" lists.
        val sources = (store.favourites(who) + store.liked(who).reversed()).distinctBy { it.first }.take(5)
        for ((key, name) in sources) {
            for (rec in runCatching { tmdb.recommendations(key) }.getOrDefault(emptySet())) out.putIfAbsent(rec, name)
        }
        return out
    }

    companion object {
        /** Last AI failure (shown in Diagnostics); picks fall back to rules when the AI fails. */
        @Volatile var lastAiError: String? = null
        /** Which engine made the last set of picks: "Groq", "On-phone" or "Rules". */
        @Volatile var lastEngine: String? = null

        private val GENRES = mapOf(35 to "Comedy", 18 to "Drama", 80 to "Crime", 9648 to "Mystery", 53 to "Thriller",
            878 to "Sci-fi", 10765 to "Sci-fi & fantasy", 14 to "Fantasy", 10749 to "Romance", 10751 to "Family",
            16 to "Animation", 28 to "Action", 10759 to "Action", 99 to "Documentary", 27 to "Horror", 12 to "Adventure")

        /** Weighted rating (so a 9.0 with 12 votes doesn't beat an 8.4 with 4,000), plus popularity and taste. */
        fun score(t: Title, fans: Map<String, String>): Double {
            val m = 200.0; val c = 6.8
            val weighted = (t.votes * t.rating + m * c) / (t.votes + m)
            return weighted + 0.35 * ln(1 + t.popularity) + (if (t.key in fans) 1.2 else 0.0) + recency(t)
        }

        /** New things get a lift; without it, decades-old classics with huge vote counts always win. */
        fun recency(t: Title, thisYear: Int = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)): Double {
            val age = t.year.toIntOrNull()?.let { thisYear - it }
            return (if (t.fresh) 0.8 else 0.0) + when { age == null -> 0.0; age <= 1 -> 0.7; age <= 3 -> 0.35; else -> 0.0 }
        }

        fun ruleReason(t: Title, fans: Map<String, String>): String = fans[t.key]?.let { "Fans of $it also loved it." }
            ?: listOfNotNull(t.genres.firstNotNullOfOrNull { GENRES[it] }, if (t.rating > 0) "★ ${"%.1f".format(t.rating)}" else null,
                if (t.fresh) "new" else if (t.popularity > 50) "popular right now" else null).joinToString(" · ").ifEmpty { t.overview.take(80) }
    }
}
