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

    // ---- Prime Video (LG app "amazon"): contentId = Amazon's GTI. Confirmed on our 2023 LG (22 Nov): opens the title,
    // after "Who's watching?" on a cold start. URL forms (contentId or contentTarget) landed on another show.
    val PRIME_VARIANTS = listOf("P1")
    val PRIME_TESTS = mapOf("P1" to ("amzn1.dv.gti.6aa9f790-371c-d77c-e4a2-2539f0bbcc92" to "Groundhog Day"))
    fun prime(gti: String, @Suppress("UNUSED_PARAMETER") variant: String): JSONObject = JSONObject().put("contentId", gti)

    // ---- Disney+ (LG app "com.disney.disneyplus-prod"): the current "browse" ID (entity-<uuid>, Wikidata P13902).
    // The old 12-character IDs opened the Disney+ home screen in every format (22 Nov), so they're no longer used.
    val DISNEY_VARIANTS = listOf("D1", "D2", "D3")
    val DISNEY_TESTS = mapOf(
        "D1" to ("entity-04c97b72-504b-47f2-9c6f-fe13d9aea82f" to "Frozen"),
        "D2" to ("entity-b39aa962-be56-4b09-a536-98617031717f" to "Avengers: Endgame"),
        "D3" to ("entity-ce1ccdca-f468-4960-b67c-026b01ba42ab" to "Coco"),
    )
    fun disney(entity: String, variant: String): JSONObject {
        val url = "https://www.disneyplus.com/browse/$entity"
        val uuid = entity.removePrefix("entity-")
        return when (variant) {
            "D2" -> JSONObject().put("params", JSONObject().put("contentTarget", url))
            "D3" -> JSONObject().put("contentId", url)
            else -> JSONObject().put("contentId", uuid).put("params", JSONObject().put("contentTarget", url).put("entityId", uuid))
        }
    }

    /** Services we can open straight to a title, and their link formats ("tries"). */
    val DIRECT = listOf("Netflix", "Prime Video", "Disney+")
    fun variants(service: String) = when (service) { "Prime Video" -> PRIME_VARIANTS; "Disney+" -> DISNEY_VARIANTS; else -> NETFLIX_VARIANTS }
    fun tests(service: String) = when (service) { "Prime Video" -> PRIME_TESTS; "Disney+" -> DISNEY_TESTS; else -> NETFLIX_TESTS }
    fun forService(service: String, id: String, key: String, name: String, variant: String): JSONObject = when (service) {
        "Prime Video" -> prime(id, variant)
        "Disney+" -> disney(id, variant)
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
        val disneyProp = "P13902"                                 // Disney+ browse ID (entity-<uuid>), movies and series
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
                disney = v("dp")?.takeIf { it.matches(Regex("entity-[0-9a-f-]{36}")) },
            )
        }
    }
}
