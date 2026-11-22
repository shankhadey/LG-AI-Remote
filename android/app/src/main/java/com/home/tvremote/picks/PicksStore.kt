package com.home.tvremote.picks

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** One "What should we watch?" session, kept for learning and for Diagnostics. */
data class Session(
    val openedAt: Long,
    var who: String = "",
    var slot: String = "",
    var how: String = "",          // mood name, "voice: …", "surprise", "keep", "tv"
    var language: String = "any",
    var shown: Int = 0,
    var notNow: Int = 0,
    var never: Int = 0,
    var playedKey: String? = null,
    var playedName: String? = null,
    var service: String? = null,
    var appId: String? = null,
    var playedAt: Long? = null,
    /** Minutes until the user left the app, if within 30 min (a "quick-back"); null = stayed or unknown. */
    var leftAfterMin: Int? = null,
    var outcome: String = "open",  // open · played · watched · quick-back · abandoned
    var engine: String = "",       // Groq · On-phone · Rules
) {
    val secondsToPlay: Long? get() = playedAt?.let { (it - openedAt) / 1000 }

    fun toJson(): JSONObject = JSONObject().put("openedAt", openedAt).put("who", who).put("slot", slot).put("how", how)
        .put("language", language).put("shown", shown).put("notNow", notNow).put("never", never)
        .put("playedKey", playedKey).put("playedName", playedName).put("service", service).put("appId", appId)
        .put("playedAt", playedAt).put("leftAfterMin", leftAfterMin).put("outcome", outcome).put("engine", engine)

    companion object {
        fun from(j: JSONObject) = Session(
            openedAt = j.getLong("openedAt"), who = j.optString("who"), slot = j.optString("slot"), how = j.optString("how"),
            language = j.optString("language", "any"), shown = j.optInt("shown"), notNow = j.optInt("notNow"), never = j.optInt("never"),
            playedKey = j.optString("playedKey").ifEmpty { null }.takeIf { j.has("playedKey") && !j.isNull("playedKey") },
            playedName = if (j.isNull("playedName")) null else j.optString("playedName"),
            service = if (j.isNull("service")) null else j.optString("service"),
            appId = if (j.isNull("appId")) null else j.optString("appId"),
            playedAt = if (j.isNull("playedAt") || !j.has("playedAt")) null else j.getLong("playedAt"),
            leftAfterMin = if (j.isNull("leftAfterMin") || !j.has("leftAfterMin")) null else j.getInt("leftAfterMin"),
            outcome = j.optString("outcome", "open"),
            engine = j.optString("engine"),
        )
    }
}

/**
 * Settings and learning for picks, in SharedPreferences. Everything stays on the phone.
 * Learning is per person ("who"), with "Together" as its own profile.
 */
class PicksStore(private val prefs: SharedPreferences) {

    // ---- settings ----------------------------------------------------------
    var people: List<String>
        get() = strings("people")
        set(v) = putStrings("people", v.map { it.trim() }.filter { it.isNotEmpty() }.distinct())
    var services: List<String>
        get() = strings("services")
        set(v) = putStrings("services", v)
    var languages: List<String>
        get() = strings("languages").ifEmpty { listOf("en") }
        set(v) = putStrings("languages", v)
    /** Minutes after midnight; values before noon mean after midnight (00:30 = 30). */
    var weekdayBedtime: Int
        get() = prefs.getInt("bedWeekday", 23 * 60)
        set(v) = prefs.edit().putInt("bedWeekday", v).apply()
    var weekendBedtime: Int
        get() = prefs.getInt("bedWeekend", 30)
        set(v) = prefs.edit().putInt("bedWeekend", v).apply()
    var tmdbKey: String
        get() = prefs.getString("tmdbKey", "")!!
        set(v) = prefs.edit().putString("tmdbKey", v.trim()).apply()
    var groqKey: String
        get() = prefs.getString("groqKey", "")!!
        set(v) = prefs.edit().putString("groqKey", v.trim()).apply()
    var groqModel: String
        get() = prefs.getString("groqModel", "").orEmpty().ifEmpty { DEFAULT_MODEL }
        set(v) = prefs.edit().putString("groqModel", v.trim()).apply()
    /** Lean-back mode: the whole chooser on the TV, operable with the pointer. Off = only the picks go to the TV. */
    var leanBack: Boolean
        get() = prefs.getBoolean("leanBack", true)
        set(v) = prefs.edit().putBoolean("leanBack", v).apply()
    /**
     * Who makes the picks: "phone" (on-phone AI), "groq", or "rules". If the chosen AI isn't available
     * right now, the other one is tried (when set up), then rules, so picks always work.
     */
    var aiChoice: String
        get() = prefs.getString("aiChoice", null) ?: if (groqKey.isNotBlank()) "groq" else "phone"
        set(v) = prefs.edit().putString("aiChoice", v).apply()
    /** Which Netflix link format this TV accepts (see DeepLinks), chosen with "Test opening a show". */
    var netflixLink: String
        get() = prefs.getString("netflixLink", "A") ?: "A"
        set(v) = prefs.edit().putString("netflixLink", v).apply()

    /** Which Netflix search-link format this TV accepts. */
    var netflixSearch: String
        get() = prefs.getString("netflixSearch", "S1") ?: "S1"
        set(v) = prefs.edit().putString("netflixSearch", v).apply()

    var showOnTv: Boolean
        get() = prefs.getBoolean("showOnTv", false)
        set(v) = prefs.edit().putBoolean("showOnTv", v).apply()

    val isSetUp get() = services.isNotEmpty() && tmdbKey.isNotBlank()

    /** Profiles to choose from: each person, plus Together when there's more than one. */
    fun profiles(): List<String> = people.let { if (it.size > 1) it + Catalog.TOGETHER else it.ifEmpty { listOf("Me") } }

    // ---- learning ----------------------------------------------------------
    private fun learning(): JSONObject = runCatching { JSONObject(prefs.getString("learning", "{}")!!) }.getOrDefault(JSONObject())
    private fun save(j: JSONObject) = prefs.edit().putString("learning", j.toString()).apply()

    private fun obj(j: JSONObject, name: String): JSONObject = j.optJSONObject(name) ?: JSONObject().also { j.put(name, it) }

    fun never(who: String): Set<String> = learning().optJSONObject("never")?.optJSONArray(who).toSet()
    fun addNever(who: String, key: String) = edit { j -> obj(j, "never").put(who, JSONArray(never(who) + key)) }

    /** Titles skipped with "Not now" in this time slot within the last 21 days (other slots unaffected). */
    fun notNow(who: String, slot: String, now: Long = System.currentTimeMillis()): Set<String> {
        val arr = learning().optJSONArray("notNow") ?: return emptySet()
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { it.optString("who") == who && it.optString("slot") == slot && now - it.optLong("at") < 21 * DAY }
            .map { it.optString("key") }.toSet()
    }
    fun addNotNow(who: String, slot: String, key: String, now: Long = System.currentTimeMillis()) = edit { j ->
        val arr = j.optJSONArray("notNow") ?: JSONArray().also { j.put("notNow", it) }
        arr.put(JSONObject().put("who", who).put("slot", slot).put("key", key).put("at", now))
        trim(j, "notNow", 300)
    }

    /** Titles played and not quickly abandoned: the taste signal ("liked"). Newest last. */
    fun liked(who: String): List<Pair<String, String>> {
        val arr = learning().optJSONObject("liked")?.optJSONArray(who) ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map { it.optString("key") to it.optString("name") }
    }
    fun addLiked(who: String, key: String, name: String) = edit { j ->
        val arr = obj(j, "liked").optJSONArray(who) ?: JSONArray().also { obj(j, "liked").put(who, it) }
        arr.put(JSONObject().put("key", key).put("name", name))
    }
    /** Shows/movies a person named as loved ("favourites"). "Together" = everyone's. */
    fun favourites(who: String): List<Pair<String, String>> {
        if (who == Catalog.TOGETHER) return people.flatMap { favourites(it) }.distinctBy { it.first }
        val arr = learning().optJSONObject("favourites")?.optJSONArray(who) ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map { it.optString("key") to it.optString("name") }
    }
    fun setFavourites(who: String, list: List<Pair<String, String>>) = edit { j ->
        obj(j, "favourites").put(who, JSONArray(list.map { JSONObject().put("key", it.first).put("name", it.second) }))
    }
    /** "Later" on the favourites card: hidden for a week. */
    fun favouritesSnoozed(who: String, now: Long = System.currentTimeMillis()) = now - prefs.getLong("favLater:$who", 0) < 7 * DAY
    fun snoozeFavourites(who: String, now: Long = System.currentTimeMillis()) = prefs.edit().putLong("favLater:$who", now).apply()

    /** Quick-backs under 15 min: avoided for this person for two weeks. */
    fun recentBails(who: String, now: Long = System.currentTimeMillis()): Set<String> =
        sessions().filter { it.who == who && it.outcome == "quick-back" && (it.leftAfterMin ?: 99) < 15 && now - it.openedAt < 14 * DAY }
            .mapNotNull { it.playedKey }.toSet()

    /** Counts of choices per time slot: kind = "who" | "mood" | "lang". */
    fun slotCounts(slot: String, kind: String): Map<String, Int> {
        val o = learning().optJSONObject("slots")?.optJSONObject(slot)?.optJSONObject(kind) ?: return emptyMap()
        return o.keys().asSequence().associateWith { o.getInt(it) }
    }
    fun countSlot(slot: String, kind: String, value: String) = edit { j ->
        val k = obj(obj(obj(j, "slots"), slot), kind)
        k.put(value, k.optInt(value) + 1)
    }

    fun sessions(): List<Session> {
        val arr = learning().optJSONArray("sessions") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { runCatching { Session.from(arr.getJSONObject(it)) }.getOrNull() }
    }
    fun saveSession(s: Session) = edit { j ->
        val arr = j.optJSONArray("sessions") ?: JSONArray().also { j.put("sessions", it) }
        val idx = (0 until arr.length()).firstOrNull { arr.getJSONObject(it).getLong("openedAt") == s.openedAt }
        if (idx != null) arr.put(idx, s.toJson()) else arr.put(s.toJson())
        trim(j, "sessions", 200)
    }

    /** Latest liked TV series for this person within 14 days: "Keep watching". */
    fun keepWatching(who: String, now: Long = System.currentTimeMillis()): Session? {
        val dismissed = keepDismissed(who)
        return sessions().lastOrNull { it.who == who && it.playedKey?.startsWith("tv:") == true && it.playedKey !in dismissed &&
            it.outcome in setOf("played", "watched") && now - (it.playedAt ?: 0) < 14 * DAY }
    }
    /** "Keep watching" suggestions dismissed with ✕ (per person; they don't come back). */
    fun keepDismissed(who: String): Set<String> = prefs.getStringSet("keepDismissed:$who", emptySet()) ?: emptySet()
    fun dismissKeep(who: String, key: String) = prefs.edit().putStringSet("keepDismissed:$who", keepDismissed(who) + key).apply()

    private fun edit(block: (JSONObject) -> Unit) { val j = learning(); block(j); save(j) }
    private fun trim(j: JSONObject, name: String, max: Int) {
        val arr = j.optJSONArray(name) ?: return
        if (arr.length() <= max) return
        val keep = JSONArray(); for (i in arr.length() - max until arr.length()) keep.put(arr.get(i)); j.put(name, keep)
    }
    private fun JSONArray?.toSet(): Set<String> = if (this == null) emptySet() else (0 until length()).map { getString(it) }.toSet()
    private fun strings(k: String): List<String> = runCatching { JSONArray(prefs.getString(k, "[]")).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrDefault(emptyList())
    private fun putStrings(k: String, v: List<String>) = prefs.edit().putString(k, JSONArray(v).toString()).apply()

    companion object {
        const val DAY = 24 * 3600 * 1000L
        const val DEFAULT_MODEL = "llama-3.3-70b-versatile"
    }
}
