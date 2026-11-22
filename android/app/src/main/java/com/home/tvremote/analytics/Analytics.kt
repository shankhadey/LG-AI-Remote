package com.home.tvremote.analytics

import android.content.Context
import android.os.Bundle

/**
 * Anonymous usage analytics: how many installs, in which countries, and which features get used.
 *
 * - Off until the user agrees (Settings › Privacy, or the one-time question). Saying no is remembered.
 * - Anonymous: Firebase's per-install ID only. No names, no titles, no IP or TV address, no advertising ID.
 * - Events are a fixed, small list ([Event]); parameters are categories (brand, service, kind), never free text.
 * - Firebase is only active when the build has the project's google-services.json; otherwise everything is a no-op,
 *   and events are still listed in Diagnostics ("would send") so what's collected can be checked.
 */
object Analytics {
    enum class Event(val id: String) {
        TvConnected("tv_connected"),        // brand, model
        PicksOpened("picks_opened"),        // how: mood / voice / surprise / keep
        PickPlayed("pick_played"),          // service, direct (link) / typed
        QuickBack("quick_back"),            // service (left the show within minutes)
        NotIt("not_it"),                    // service, step (format / type)
        VoiceCommand("voice_command"),      // kind (power, volume, open_app, search, picks…)
        TurnOnFromPhone("turn_on"),         // result: on / gave_up
    }

    /** null = not asked yet; true / false = the user's answer. */
    @Volatile var consent: Boolean? = null
        internal set
    private var firebase: Any? = null   // FirebaseAnalytics, held loosely so tests and config-less builds never touch it
    /** The last events, for Diagnostics (newest first). */
    val recent = java.util.concurrent.ConcurrentLinkedDeque<String>()

    fun init(context: Context) {
        val prefs = context.getSharedPreferences("analytics", Context.MODE_PRIVATE)
        consent = if (prefs.contains("consent")) prefs.getBoolean("consent", false) else null
        firebase = runCatching {
            if (com.google.firebase.FirebaseApp.getApps(context).isEmpty()) null
            else com.google.firebase.analytics.FirebaseAnalytics.getInstance(context)
        }.getOrNull()
        applyConsent()
    }

    val active get() = firebase != null

    fun setConsent(context: Context, on: Boolean) {
        consent = on
        context.getSharedPreferences("analytics", Context.MODE_PRIVATE).edit().putBoolean("consent", on).apply()
        applyConsent()
    }

    private fun applyConsent() {
        val fa = firebase as? com.google.firebase.analytics.FirebaseAnalytics ?: return
        runCatching { fa.setAnalyticsCollectionEnabled(consent == true) }
    }

    /** Settings that segment usage: the household's country and the app language. */
    fun setProfile(country: String, language: String) {
        val fa = firebase as? com.google.firebase.analytics.FirebaseAnalytics
        if (consent == true) runCatching { fa?.setUserProperty("home_country", country); fa?.setUserProperty("app_language", language) }
    }

    fun log(event: Event, vararg params: Pair<String, String?>) {
        if (consent != true) return
        val clean = params.filter { it.second != null }.map { it.first to it.second!!.take(36) }
        recent.addFirst(java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date()) + " " +
            event.id + clean.joinToString("") { " ${it.first}=${it.second}" } + if (active) "" else " (not sent: analytics not set up in this build)")
        while (recent.size > 20) recent.pollLast()
        val fa = firebase as? com.google.firebase.analytics.FirebaseAnalytics ?: return
        runCatching { fa.logEvent(event.id, Bundle().apply { clean.forEach { (k, v) -> putString(k, v) } }) }
    }
}
