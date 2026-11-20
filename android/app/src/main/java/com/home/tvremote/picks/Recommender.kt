package com.home.tvremote.picks

import kotlin.math.ln

/** Picks shown now, plus the ranked remainder used for "Not now", "Never" and "Show 3 more". */
data class PickSet(val picks: List<Pick>, val pool: List<Title>, val fans: Map<String, String>)

class Recommender(
    private val store: PicksStore,
    private val tmdb: Tmdb,
    private val groq: Groq?,
) {
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
            candidates += runCatching { tmdb.discover(k, providers, ask.mood, language, cap) }.getOrElse { if (it is PicksException) throw it; emptyList() }
            if (language != null && language != "en" && candidates.size < 12) {
                candidates += runCatching { tmdb.discover(k, providers, ask.mood, language, cap, page = 2) }.getOrDefault(emptyList())
            }
        }

        val blocked = store.never(who) + store.notNow(who, slot) + store.recentBails(who) + store.liked(who).map { it.first }
        val fans = fansMap(who)
        val pool = candidates.distinctBy { it.key }
            .filter { it.key !in blocked }
            .filter { language != null || household.isEmpty() || it.language in household }
            .sortedByDescending { score(it, fans) }
        if (pool.isEmpty()) throw PicksException("Nothing matched — try another mood, language or “Any”")

        val chosen = chooseThree(pool.take(15), who, ask, context, fans)
        return finish(chosen, pool, fans, minutesLeft)
    }

    /** "Show 3 more": next three from the pool, skipping what's been shown. */
    suspend fun more(prev: PickSet, shown: Set<String>, who: String, context: String, minutesLeft: Int?): PickSet {
        val rest = prev.pool.filter { it.key !in shown }
        if (rest.isEmpty()) throw PicksException("That's everything for this mood — try another one")
        val chosen = chooseThree(rest.take(15), who, Ask(), context, prev.fans)
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

    private suspend fun chooseThree(top: List<Title>, who: String, ask: Ask, context: String, fans: Map<String, String>): List<Pick> {
        val ai = groq
        if (ai != null) {
            val taste = buildString {
                val liked = store.liked(who).takeLast(8).map { it.second }
                append(if (liked.isEmpty()) "no history yet" else "enjoyed ${liked.joinToString()}")
                if (ask.likeTitles.isNotEmpty()) append("; asked for something like ${ask.likeTitles.joinToString()}")
            }
            val annotated = top.map { t -> fans[t.key]?.let { t.copy(overview = "(fans of $it also liked this) " + t.overview) } ?: t }
            val got = runCatching { ai.choose(context, taste, annotated) }
                .onFailure { lastAiError = it.message }.getOrNull()
            if (!got.isNullOrEmpty()) {
                val byKey = top.associateBy { it.key }
                val picks = got.mapNotNull { (k, why) -> byKey[k]?.let { Pick(it, why.ifBlank { ruleReason(it, fans) }) } }
                if (picks.size == 3) return picks
                return (picks + ruleThree(top.filter { t -> picks.none { it.title.key == t.key } }, fans)).take(3)
            }
        }
        return ruleThree(top, fans)
    }

    /** Without AI: the top three, mixing series and films when both are available. */
    private fun ruleThree(top: List<Title>, fans: Map<String, String>): List<Pick> {
        val out = mutableListOf<Title>()
        top.firstOrNull { it.kind == Kind.Tv }?.let { out += it }
        top.firstOrNull { it.kind == Kind.Movie }?.let { out += it }
        for (t in top) { if (out.size >= 3) break; if (t !in out) out += t }
        return out.take(3).map { Pick(it, ruleReason(it, fans)) }
    }

    private suspend fun finish(chosen: List<Pick>, pool: List<Title>, fans: Map<String, String>, minutesLeft: Int?): PickSet {
        val out = mutableListOf<Pick>()
        val used = chosen.map { it.title.key }.toMutableSet()
        for (p in chosen) {
            var e = runCatching { tmdb.enrich(p.title, services) }.getOrDefault(p.title)
            var why = p.why
            if (!fits(e, minutesLeft)) {
                // Too long for tonight: swap in the next title that fits.
                val alt = pool.firstOrNull { it.key !in used && it.kind == Kind.Tv } ?: pool.firstOrNull { it.key !in used }
                if (alt != null) { used += alt.key; e = runCatching { tmdb.enrich(alt, services) }.getOrDefault(alt); why = ruleReason(e, fans) }
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
        for ((key, name) in store.liked(who).takeLast(3).reversed()) {
            for (rec in runCatching { tmdb.recommendations(key) }.getOrDefault(emptySet())) out.putIfAbsent(rec, name)
        }
        return out
    }

    companion object {
        /** Last AI failure (shown in Diagnostics); picks fall back to rules when the AI fails. */
        @Volatile var lastAiError: String? = null

        private val GENRES = mapOf(35 to "Comedy", 18 to "Drama", 80 to "Crime", 9648 to "Mystery", 53 to "Thriller",
            878 to "Sci-fi", 10765 to "Sci-fi & fantasy", 14 to "Fantasy", 10749 to "Romance", 10751 to "Family",
            16 to "Animation", 28 to "Action", 10759 to "Action", 99 to "Documentary", 27 to "Horror", 12 to "Adventure")

        /** Weighted rating (so a 9.0 with 12 votes doesn't beat an 8.4 with 4,000), plus popularity and taste. */
        fun score(t: Title, fans: Map<String, String>): Double {
            val m = 200.0; val c = 6.8
            val weighted = (t.votes * t.rating + m * c) / (t.votes + m)
            return weighted + 0.35 * ln(1 + t.popularity) + (if (t.key in fans) 1.2 else 0.0)
        }

        fun ruleReason(t: Title, fans: Map<String, String>): String = fans[t.key]?.let { "Fans of $it also loved it." }
            ?: listOfNotNull(t.genres.firstNotNullOfOrNull { GENRES[it] }, if (t.rating > 0) "★ ${"%.1f".format(t.rating)}" else null,
                if (t.popularity > 50) "popular right now" else null).joinToString(" · ").ifEmpty { t.overview.take(80) }
    }
}
