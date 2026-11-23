package com.home.tvremote

import android.content.SharedPreferences
import java.time.LocalDate

/**
 * When to ask for a Play review: only after the app has clearly worked — 3 successes (TV turned on from the phone,
 * a pick played) on at least 2 different days, and nothing just went wrong. Asked once; Play also limits how often
 * its review sheet appears, and shows nothing for copies not installed from Play.
 */
class Engagement(private val prefs: SharedPreferences, private val today: () -> LocalDate = { LocalDate.now() }) {
    /** Records a success; true when this is the moment to ask for a review. */
    fun success(troubleNow: Boolean): Boolean {
        val n = prefs.getInt("successes", 0) + 1
        val days = (prefs.getStringSet("success_days", emptySet()).orEmpty() + today().toString()).toSortedSet().toList().takeLast(5).toSet()
        prefs.edit().putInt("successes", n).putStringSet("success_days", days).apply()
        return n >= 3 && days.size >= 2 && !troubleNow && !prefs.getBoolean("review_asked", false)
    }

    fun reviewAsked() = prefs.edit().putBoolean("review_asked", true).apply()
}
