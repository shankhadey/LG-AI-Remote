package com.home.tvremote.picks

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Opening a title inside a streaming app instead of just the app.
 * TMDB doesn't carry the apps' own title IDs; Wikidata (public, CC0) maps TMDB IDs to Netflix, Prime Video (GTI) and
 * Disney+ IDs for many titles (Prime: mostly films). How each LG app accepts a title differs between firmware
 * versions, so there are variants to try on the TV ("Test opening a show").
 */
object DeepLinks {
    /** Variants of the extra launch fields for Netflix, tried on the TV from the menu ("Test opening a show"). */
    val NETFLIX_VARIANTS = listOf("A", "C")   // "B" (contentTarget …/watch/…) didn't work on our 2023 TV

    /** A different well-known title per variant, so a test can't be fooled by Netflix reopening the last page. */
    val NETFLIX_TESTS = mapOf("A" to ("80057281" to "Stranger Things"), "B" to ("81231974" to "Wednesday"), "C" to ("80025678" to "The Crown"))

    /** Netflix's search page with the words filled in (for titles without a known ID, and as the first fallback). */
    val NETFLIX_SEARCH_VARIANTS = listOf("S1", "S2")
    fun netflixSearch(query: String, variant: String): JSONObject {
        val q = java.net.URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        return when (variant) {
            "S2" -> JSONObject().put("params", JSONObject().put("contentTarget", "https://www.netflix.com/search?q=$q"))
            else -> JSONObject().put("contentId", "m=https://www.netflix.com/search?q=$q&source_type=4")
        }
    }

    // ---- Prime Video (LG app "amazon"): Amazon's GTI title ID. P1 reported working on a 2024 LG (17 Nov 2026). ----
    val PRIME_VARIANTS = listOf("P1", "P2", "P3")
    /** Well-known films with a GTI on Wikidata, a different one per try. */
    val PRIME_TESTS = mapOf(
        "P1" to ("amzn1.dv.gti.6aa9f790-371c-d77c-e4a2-2539f0bbcc92" to "Groundhog Day"),
        "P2" to ("amzn1.dv.gti.67d67132-66a8-46fb-9b4d-3ebe25602945" to "Casino Royale"),
        "P3" to ("amzn1.dv.gti.98a9f73a-f078-b2ed-17d1-91d6ab15b397" to "Blade Runner"),
    )
    fun prime(gti: String, variant: String): JSONObject {
        val url = "https://app.primevideo.com/detail?gti=$gti"
        return when (variant) {
            "P2" -> JSONObject().put("contentId", url)
            "P3" -> JSONObject().put("params", JSONObject().put("contentTarget", url))
            else -> JSONObject().put("contentId", gti)
        }
    }

    // ---- Disney+ (LG app "com.disney.disneyplus-prod"): Disney's 12-character title ID. No format confirmed yet. ----
    val DISNEY_VARIANTS = listOf("D1", "D2", "D3")
    val DISNEY_TESTS = mapOf(
        "D1" to ("4uKGzAJi3ROz" to "Frozen"),
        "D2" to ("aRbVJUb2h2Rf" to "Avengers: Endgame"),
        "D3" to ("db9orsI5O4gC" to "Coco"),
    )
    fun disney(id: String, movie: Boolean, name: String, variant: String): JSONObject {
        val slug = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "title" }
        val url = "https://www.disneyplus.com/${if (movie) "movies" else "series"}/$slug/$id"
        return when (variant) {
            "D2" -> JSONObject().put("contentId", url)
            "D3" -> JSONObject().put("params", JSONObject().put("contentTarget", "https://www.disneyplus.com/video/$id"))
            else -> JSONObject().put("contentId", id).put("params", JSONObject().put("contentTarget", url))
        }
    }

    /** Services we can open straight to a title, and their link formats ("tries"). */
    val DIRECT = listOf("Netflix", "Prime Video", "Disney+")
    fun variants(service: String) = when (service) { "Prime Video" -> PRIME_VARIANTS; "Disney+" -> DISNEY_VARIANTS; else -> NETFLIX_VARIANTS }
    fun tests(service: String) = when (service) { "Prime Video" -> PRIME_TESTS; "Disney+" -> DISNEY_TESTS; else -> NETFLIX_TESTS }
    fun forService(service: String, id: String, key: String, name: String, variant: String): JSONObject = when (service) {
        "Prime Video" -> prime(id, variant)
        "Disney+" -> disney(id, key.startsWith("movie:"), name, variant)
        else -> netflix(id, variant)
    }

    fun netflix(id: String, variant: String): JSONObject = when (variant) {
        "B" -> JSONObject().put("params", JSONObject().put("contentTarget", "https://www.netflix.com/watch/$id"))
        "C" -> JSONObject().put("contentId", "m=https://www.netflix.com/title/$id&source_type=4")
        else -> JSONObject().put("contentId", "m=http://api-global.netflix.com/catalog/titles/movies/$id&source_type=4")
    }
}

/** A title's IDs in the streaming apps (null where Wikidata doesn't know it). */
data class TitleIds(val netflix: String? = null, val prime: String? = null, val disney: String? = null) {
    fun forService(service: String) = when (service) { "Netflix" -> netflix; "Prime Video" -> prime; "Disney+" -> disney; else -> null }
}

/** TMDB key ("tv:1396") → the title's Netflix / Prime Video / Disney+ IDs, via Wikidata, in one query. */
class Wikidata(private val base: String = "https://query.wikidata.org/sparql") {
    private val http = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()

    suspend fun netflixId(key: String): String? = ids(key)?.netflix

    /**
     * Finds the Wikidata item by its TMDB ID; if Wikidata never recorded that TMDB ID, by the item TMDB itself
     * points to ([wikidataIdOf], TMDB's external IDs). Returns null when no item is found either way.
     */
    suspend fun ids(key: String, wikidataIdOf: (suspend () -> String?)? = null): TitleIds? {
        val (kind, id) = key.split(':').takeIf { it.size == 2 } ?: return null
        if (id.toIntOrNull() == null) return null
        val movie = kind == "movie"
        val tmdbProp = if (movie) "P4947" else "P4983"          // TMDB movie ID / TMDB TV series ID
        query(movie, "?i wdt:$tmdbProp \"$id\" .")?.let { return it }
        val qid = wikidataIdOf?.let { runCatching { it() }.getOrNull() }?.takeIf { it.matches(Regex("Q[0-9]+")) } ?: return null
        return query(movie, "VALUES ?i { wd:$qid }")
    }

    /** The IDs on the item matched by [match]; null when no item matched. */
    private suspend fun query(movie: Boolean, match: String): TitleIds? = withContext(Dispatchers.IO) {
        val disneyProp = if (movie) "P7595" else "P7596"        // Disney+ movie ID / Disney+ series ID
        // P1874 = Netflix ID, P14462 = Prime Video GTI
        val query = """SELECT (SAMPLE(?i) AS ?item) (SAMPLE(?n) AS ?nf) (SAMPLE(?p) AS ?pv) (SAMPLE(?d) AS ?dp) WHERE { $match
            OPTIONAL { ?i wdt:P1874 ?n } OPTIONAL { ?i wdt:P14462 ?p } OPTIONAL { ?i wdt:$disneyProp ?d } }"""
        val url = base.toHttpUrl().newBuilder().addQueryParameter("format", "json").addQueryParameter("query", query).build()
        val req = Request.Builder().url(url).header("User-Agent", "TVRemoteApp/1.2 (Android)").header("Accept", "application/sparql-results+json").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return@use null
            val b = JSONObject(r.body?.string().orEmpty()).optJSONObject("results")?.optJSONArray("bindings")?.optJSONObject(0) ?: return@use null
            fun v(name: String) = b.optJSONObject(name)?.optString("value")?.takeIf { it.isNotEmpty() }
            if (v("item") == null) return@use null   // no item matched
            TitleIds(
                netflix = v("nf")?.takeIf { it.all(Char::isDigit) },
                prime = v("pv")?.takeIf { it.startsWith("amzn1.dv.gti.") },
                disney = v("dp")?.takeIf { it.matches(Regex("[A-Za-z0-9]{8,20}")) },
            )
        }
    }
}
