package com.home.tvremote.picks

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class PicksException(message: String) : Exception(message)

/** How many picks are shown at a time. */
const val PICKS = 5

private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

/** TMDB (themoviedb.org): catalog, US availability, runtimes, "people who liked X also liked". */
class Tmdb(private val key: String, private val base: String = "https://api.themoviedb.org/3", val region: String = "US",
           /** Titles and overviews in this language ("pt-BR"); null = TMDB's default (English). */
           private val language: String? = null) {
    private val providerCache = mutableMapOf<String, Int>()

    private suspend fun get(path: String, params: Map<String, String> = emptyMap()): JSONObject = withContext(Dispatchers.IO) {
        val url: HttpUrl = (base + path).toHttpUrl().newBuilder().apply {
            if (!key.startsWith("ey")) addQueryParameter("api_key", key) // v3 key; v4 tokens (JWT, "ey…") go in a header
            if (language != null && "language" !in params) addQueryParameter("language", language)
            params.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val req = Request.Builder().url(url).apply { if (key.startsWith("ey")) header("Authorization", "Bearer $key") }.build()
        http.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (r.code == 401) throw PicksException("TMDB rejected the key — check it in Picks settings")
            if (!r.isSuccessful) throw PicksException("TMDB error ${r.code}")
            JSONObject(body)
        }
    }

    /** TMDB provider IDs for the chosen services (looked up by name, so renamed IDs don't break us). */
    suspend fun providerIds(services: List<Service>): List<Int> {
        if (providerCache.isEmpty()) {
            for (kind in listOf("movie", "tv")) {
                val arr = get("/watch/providers/$kind", mapOf("watch_region" to region)).optJSONArray("results") ?: JSONArray()
                for (i in 0 until arr.length()) arr.getJSONObject(i).let { providerCache[it.optString("provider_name").lowercase()] = it.optInt("provider_id") }
            }
        }
        return services.flatMap { s -> s.tmdbNames.mapNotNull { providerCache[it.lowercase()] } }.distinct()
    }

    /** Titles on the user's services matching mood/language/length. */
    /**
     * [since] / [until] (yyyy-MM-dd): only films released, or series that first aired, in that window.
     * (Series by "first aired", not "any episode aired": otherwise 20-year-old shows count as new.)
     */
    suspend fun discover(kind: Kind, providers: List<Int>, mood: Mood, language: String?, maxRuntime: Int?, page: Int = 1,
                         since: String? = null, until: String? = null): List<Title> {
        val p = mutableMapOf(
            "watch_region" to region, "with_watch_providers" to providers.joinToString("|"),
            "with_watch_monetization_types" to "flatrate", "sort_by" to "popularity.desc",
            "include_adult" to "false", "page" to page.toString(),
            "vote_count.gte" to if (language == null || language == "en") "80" else "8",
        )
        val genres = if (kind == Kind.Movie) mood.movieGenres else mood.tvGenres
        if (genres.isNotEmpty()) p["with_genres"] = genres
        val without = if (kind == Kind.Movie) mood.movieWithout else mood.tvWithout
        if (without.isNotEmpty()) p["without_genres"] = without
        if (language != null) p["with_original_language"] = language
        if (maxRuntime != null) p["with_runtime.lte"] = maxRuntime.toString()
        val dateField = if (kind == Kind.Movie) "primary_release_date" else "first_air_date"
        if (since != null) {
            p["$dateField.gte"] = since
            p["vote_count.gte"] = "15"   // new titles haven't had time to collect votes
        }
        if (until != null) p["$dateField.lte"] = until
        val arr = get("/discover/${if (kind == Kind.Movie) "movie" else "tv"}", p).optJSONArray("results") ?: JSONArray()
        return (0 until arr.length()).map { parse(kind, arr.getJSONObject(it)) }
    }

    /** Title search for favourites (series and films; people and others dropped). */
    suspend fun search(query: String): List<Title> {
        if (query.isBlank()) return emptyList()
        val arr = get("/search/multi", mapOf("query" to query, "include_adult" to "false")).optJSONArray("results") ?: JSONArray()
        return (0 until arr.length()).map { arr.getJSONObject(it) }.mapNotNull { o ->
            when (o.optString("media_type")) { "movie" -> parse(Kind.Movie, o); "tv" -> parse(Kind.Tv, o); else -> null }
        }.take(8)
    }

    /** Names of the subscription services that carry a title in this region (for "it's on Max"). */
    suspend fun providerNames(t: Title): List<String> {
        val path = if (t.kind == Kind.Movie) "/movie/${t.id}" else "/tv/${t.id}"
        val flat = runCatching { get("$path/watch/providers").optJSONObject("results")?.optJSONObject(region)?.optJSONArray("flatrate") }.getOrNull()
        return (0 until (flat?.length() ?: 0)).map { flat!!.getJSONObject(it).optString("provider_name") }
    }

    /** The well-known person TMDB has under this name (e.g. "adam sandler" → "Adam Sandler"), or null. */
    suspend fun findPerson(name: String): String? {
        val p = runCatching { get("/search/person", mapOf("query" to name)).optJSONArray("results")?.optJSONObject(0) }.getOrNull() ?: return null
        val found = p.optString("name")
        fun n(s: String) = s.lowercase().replace(Regex("[^a-z]"), "")
        return found.takeIf { p.optDouble("popularity", 0.0) >= 3.0 && (n(it) == n(name) || n(it).startsWith(n(name))) }
    }

    /** A person's best-known series and films, most popular first ("shows with Jennifer Aniston"). */
    suspend fun personCredits(name: String): List<Title> {
        val p = get("/search/person", mapOf("query" to name)).optJSONArray("results")?.optJSONObject(0) ?: return emptyList()
        val credits = get("/person/${p.optInt("id")}/combined_credits")
        val arr = credits.optJSONArray("cast") ?: JSONArray()
        // Directors and creators too ("movies by Christopher Nolan").
        val crew = credits.optJSONArray("crew") ?: JSONArray()
        for (i in 0 until crew.length()) crew.getJSONObject(i).let { c ->
            if (c.optString("job") in setOf("Director", "Creator", "Showrunner")) arr.put(JSONObject(c.toString()).put("order", 0).put("episode_count", 99).put("character", ""))
        }
        // Real roles only: not talk shows or appearances as themselves, not one-off guest spots, not tiny film parts.
        // (Raw credits put Jimmy Fallon and a Seinfeld guest spot ahead of Friends and Breaking Bad.)
        val junkTv = setOf(10763, 10764, 10767)
        return (0 until arr.length()).map { arr.getJSONObject(it) }.mapNotNull { o ->
            val character = o.optString("character").lowercase()
            if (character.contains("self") || character.contains("host") || character.contains("narrator") && o.optString("media_type") == "tv") return@mapNotNull null
            when (o.optString("media_type")) {
                "movie" -> parse(Kind.Movie, o).takeIf { o.optInt("order", 99) <= 8 }
                "tv" -> parse(Kind.Tv, o).takeIf { o.optInt("episode_count", 0) >= 4 && it.genres.none { g -> g in junkTv } }
                else -> null
            }
        }.filter { it.votes >= 50 }.distinctBy { it.key }.sortedByDescending { it.votes }.take(30)
    }

    /** Runtime (movie length or typical episode length) and which of the user's services has it. */
    suspend fun enrich(t: Title, services: List<Service>): Title {
        val path = if (t.kind == Kind.Movie) "/movie/${t.id}" else "/tv/${t.id}"
        val d = runCatching { get(path) }.getOrNull()
        val runtime = when {
            d == null -> null
            t.kind == Kind.Movie -> d.optInt("runtime").takeIf { it > 0 }
            else -> d.optJSONArray("episode_run_time")?.let { a -> (0 until a.length()).map { a.getInt(it) }.minOrNull() }
                ?: d.optJSONObject("last_episode_to_air")?.optInt("runtime")?.takeIf { it > 0 }
        }
        val flat = runCatching { get("$path/watch/providers").optJSONObject("results")?.optJSONObject(region)?.optJSONArray("flatrate") }.getOrNull()
        val names = (0 until (flat?.length() ?: 0)).map { flat!!.getJSONObject(it).optString("provider_name").lowercase() }
        val service = services.firstOrNull { s -> s.tmdbNames.any { it.lowercase() in names } }?.name
        return t.copy(runtimeMin = runtime, service = service ?: t.service)
    }

    /** TMDB's "recommendations" for a title: used for "Fans of X also loved it". */
    /** TMDB's trending-this-week list: key → rank (0 = top). A "what people are watching now" signal. */
    suspend fun trending(kind: Kind): Map<String, Int> {
        val arr = runCatching { get("/trending/${if (kind == Kind.Movie) "movie" else "tv"}/week").optJSONArray("results") }.getOrNull() ?: return emptyMap()
        return (0 until arr.length()).associate { "${if (kind == Kind.Movie) "movie" else "tv"}:${arr.getJSONObject(it).optInt("id")}" to it }
    }

    /** "Fans also liked" as full titles (for adding to the candidates, not just boosting). */
    suspend fun recommendationTitles(key: String): List<Title> {
        val (kind, id) = key.split(':').let { it[0] to it[1] }
        val arr = runCatching { get("/$kind/$id/recommendations").optJSONArray("results") }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).map { parse(if (kind == "movie") Kind.Movie else Kind.Tv, arr.getJSONObject(it)) }
    }

    /** The title's Wikidata item ("Q48796994"), as TMDB records it; null if unknown. */
    suspend fun wikidataId(key: String): String? {
        val (kind, id) = key.split(':').takeIf { it.size == 2 } ?: return null
        return runCatching { get("/$kind/$id/external_ids").optString("wikidata_id") }.getOrNull()?.takeIf { it.matches(Regex("Q[0-9]+")) }
    }

    /** Genres of a title (for "what do their favourites have in common"). */
    suspend fun genresOf(key: String): List<Int> {
        val (kind, id) = key.split(':').let { it[0] to it[1] }
        val g = runCatching { get("/$kind/$id").optJSONArray("genres") }.getOrNull() ?: return emptyList()
        return (0 until g.length()).map { g.getJSONObject(it).optInt("id") }
    }

    suspend fun recommendations(key: String): Set<String> {
        val (kind, id) = key.split(':').let { it[0] to it[1] }
        val arr = runCatching { get("/$kind/$id/recommendations").optJSONArray("results") }.getOrNull() ?: return emptySet()
        return (0 until arr.length()).map { "$kind:${arr.getJSONObject(it).optInt("id")}" }.toSet()
    }

    companion object {
        fun parse(kind: Kind, o: JSONObject): Title {
            val g = o.optJSONArray("genre_ids")
            return Title(
                kind = kind, id = o.optInt("id"),
                name = o.optString(if (kind == Kind.Movie) "title" else "name"),
                year = o.optString(if (kind == Kind.Movie) "release_date" else "first_air_date").take(4),
                overview = o.optString("overview"),
                genres = (0 until (g?.length() ?: 0)).map { g!!.getInt(it) },
                language = o.optString("original_language"),
                rating = o.optDouble("vote_average", 0.0), votes = o.optInt("vote_count"),
                popularity = o.optDouble("popularity", 0.0),
                posterPath = o.optString("poster_path").ifEmpty { null }?.takeIf { it != "null" },
            )
        }
    }
}

/** Filters pulled out of a free-text request. */
data class Ask(
    val mood: Mood = Mood.Any,
    val kind: Kind? = null,
    val language: String? = null,
    val maxMinutes: Int? = null,
    val likeTitles: List<String> = emptyList(),
    /** Only recent titles ("something new"). */
    val newOnly: Boolean = false,
)

/** One AI backend: given instructions and input, return a JSON object. */
interface AiEngine {
    val name: String
    /** Small on-phone models get a shorter candidate list and descriptions. */
    val compact: Boolean get() = false
    suspend fun json(system: String, user: String): JSONObject
}

/** Pulls the JSON object out of a model reply (small models sometimes add text or code fences around it). */
fun extractJson(text: String): JSONObject {
    val a = text.indexOf('{'); val b = text.lastIndexOf('}')
    if (a < 0 || b <= a) throw PicksException("The AI didn't return JSON")
    return JSONObject(text.substring(a, b + 1))
}

/** Groq (OpenAI-compatible chat API, free tier). */
class Groq(private val key: String, private val model: String, private val base: String = "https://api.groq.com/openai/v1") : AiEngine {
    override val name = "Groq"
    override suspend fun json(system: String, user: String): JSONObject = withContext(Dispatchers.IO) {
        val body = JSONObject().put("model", model).put("temperature", 0.3)
            .put("response_format", JSONObject().put("type", "json_object"))
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user)))
        val req = Request.Builder().url("$base/chat/completions").header("Authorization", "Bearer $key")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (r.code == 401) throw PicksException("Groq rejected the key — check it in Picks settings")
            if (r.code == 429) throw PicksException("Groq's free limit was reached — try again in a minute")
            if (!r.isSuccessful) throw PicksException("Groq error ${r.code}")
            extractJson(JSONObject(text).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content"))
        }
    }
}

/**
 * The two AI jobs, with the same prompts and checks for every engine. Engines are tried in order
 * (e.g. Groq, then the on-phone model); if all fail, the caller falls back to rules.
 * The model only ever chooses candidate IDs, so it can't invent titles or availability.
 */
class AiPicker(val engines: List<AiEngine>) {
    /** Which engine produced the last result, and the last failure (for Diagnostics). */
    @Volatile var lastEngine: String? = null
    @Volatile var lastError: String? = null

    val isEmpty get() = engines.isEmpty()

    private suspend fun <T> firstSuccess(block: suspend (AiEngine) -> T?): T? {
        for (e in engines) {
            val r = runCatching { block(e) }.onFailure { lastError = "${e.name}: ${it.message}" }.getOrNull()
            if (r != null) { lastEngine = e.name; return r }
        }
        return null
    }

    suspend fun parseRequest(text: String, languages: Map<String, String>): Ask? = firstSuccess { e ->
        val j = e.json(
            """Convert a TV-viewing request into JSON filters. Reply with JSON only, no other text:
{"mood":"light|cozy|gripping|mindbending|any","kind":"movie|tv|any","language":"<ISO 639-1 code or null>","max_minutes":<number or null>,"like_titles":["..."],"new":<true if they want something new or recent, else false>}
Languages the household watches: ${languages.entries.joinToString { "${it.key}=${it.value}" }}.
The request may be in English or Portuguese.""",
            text,
        )
        Ask(
            mood = when (j.optString("mood")) { "light" -> Mood.Light; "cozy" -> Mood.Cozy; "gripping" -> Mood.Gripping; "mindbending" -> Mood.MindBending; else -> Mood.Any },
            kind = when (j.optString("kind")) { "movie" -> Kind.Movie; "tv" -> Kind.Tv; else -> null },
            language = j.optString("language").takeIf { it.length == 2 },
            maxMinutes = j.optInt("max_minutes").takeIf { it > 0 },
            likeTitles = j.optJSONArray("like_titles")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
            newOnly = j.optBoolean("new"),
        )
    }

    /** Open-ended requests ("that show with Jennifer Aniston", "the submarine movie"), when no rule understood them. */
    suspend fun interpret(text: String): JSONObject? = firstSuccess { e ->
        e.json(
            """Interpret what someone said to their TV remote. Reply with JSON only, no other text:
{"kind":"title|person|description|mood|other","titles":["..."],"person":"<name or null>"}
title: they named a show or film; give its official title (fix mishearings).
person: they want something with an actor or director; give the name.
description: they described a show or film; list up to 5 real titles that best match, best first.
mood: they want a recommendation by mood or genre.
other: anything else.
Use exactly one kind. What they said may be in English or Portuguese; give titles in the language they used.""",
            text,
        ).takeIf { it.has("kind") }
    }

    /** Up to [PICKS] (candidate key, reason). Unknown keys are dropped; an engine that returns none counts as failed. */
    suspend fun choose(context: String, taste: String, candidates: List<Title>): List<Pair<String, String>>? = firstSuccess { e ->
        val list = candidates.take(if (e.compact) 12 else 15).joinToString("\n") { t ->
            "${t.key} | ${t.name} (${t.year}${if (t.fresh) ", NEW" else ""}) | ${if (t.kind == Kind.Movie) "movie" else "series"} | lang ${t.language} | ★${"%.1f".format(t.rating)} | ${t.overview.take(if (e.compact) 90 else 160)}"
        }
        val j = e.json(
            """You help a household decide what to watch, fast. Choose exactly 5 titles from the candidate list,
using ONLY ids from the list. Prefer variety and a good fit for the moment. When the list has both, include
at least one movie and one series. Favour titles marked NEW over older ones of similar appeal. For each, write one short, warm
reason (max 14 words) grounded in the description or the household's taste. No spoilers, no made-up facts.
Write the reasons in ${com.home.tvremote.i18n.I18n.aiLanguage}.
Reply with JSON only, no other text: {"picks":[{"id":"tv:123","why":"..."}]}""",
            "Moment: $context\nTaste: $taste\nCandidates:\n$list",
        )
        val valid = candidates.map { it.key }.toSet()
        val arr = j.optJSONArray("picks") ?: return@firstSuccess null
        (0 until arr.length()).map { arr.getJSONObject(it) }
            .map { it.optString("id") to it.optString("why").take(140) }
            .filter { it.first in valid }.distinctBy { it.first }.take(PICKS)
            .ifEmpty { null }
    }
}
