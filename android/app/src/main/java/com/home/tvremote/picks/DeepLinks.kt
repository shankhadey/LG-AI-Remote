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
 * TMDB doesn't carry the apps' own title IDs; Wikidata (public, CC0) maps TMDB IDs to Netflix IDs for many titles.
 * How the LG Netflix app accepts a title differs between firmware versions, so there are variants to try on the TV.
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

    fun netflix(id: String, variant: String): JSONObject = when (variant) {
        "B" -> JSONObject().put("params", JSONObject().put("contentTarget", "https://www.netflix.com/watch/$id"))
        "C" -> JSONObject().put("contentId", "m=https://www.netflix.com/title/$id&source_type=4")
        else -> JSONObject().put("contentId", "m=http://api-global.netflix.com/catalog/titles/movies/$id&source_type=4")
    }
}

/** TMDB key ("tv:1396") → Netflix title ID, via Wikidata. Null when Wikidata doesn't know it. */
class Wikidata(private val base: String = "https://query.wikidata.org/sparql") {
    private val http = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()

    suspend fun netflixId(key: String): String? = withContext(Dispatchers.IO) {
        val (kind, id) = key.split(':').takeIf { it.size == 2 } ?: return@withContext null
        if (id.toIntOrNull() == null) return@withContext null
        val tmdbProp = if (kind == "movie") "P4947" else "P4983"          // TMDB movie ID / TMDB TV series ID
        val query = """SELECT ?nf WHERE { ?i wdt:$tmdbProp "$id" ; wdt:P1874 ?nf } LIMIT 1"""   // P1874 = Netflix ID
        val url = base.toHttpUrl().newBuilder().addQueryParameter("format", "json").addQueryParameter("query", query).build()
        val req = Request.Builder().url(url).header("User-Agent", "TVRemoteApp/0.7 (Android)").header("Accept", "application/sparql-results+json").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return@use null
            JSONObject(r.body?.string().orEmpty()).optJSONObject("results")?.optJSONArray("bindings")
                ?.optJSONObject(0)?.optJSONObject("nf")?.optString("value")?.takeIf { it.all(Char::isDigit) && it.isNotEmpty() }
        }
    }
}
