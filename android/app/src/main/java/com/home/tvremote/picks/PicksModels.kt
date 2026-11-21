package com.home.tvremote.picks

import java.util.Calendar

/** Moods offered as one-tap buttons. Each maps to TMDB genres (movie, tv) to include and exclude. */
enum class Mood(val label: String, val hint: String, val movieGenres: String, val tvGenres: String, val without: String) {
    New("New", "Out this year", "", "", "27"),
    Light("Light & funny", "Easy, no thinking", "35", "35", "27|53|10752"),
    Cozy("Cozy", "Warm, comforting", "10751|10749|16|35", "10751|16|35", "27|53|80|10752"),
    Gripping("Gripping", "Can't look away", "53|80|9648|28", "80|9648|10759", "16|10751"),
    MindBending("Mind-bending", "Twists, big ideas", "878|9648|14", "10765|9648", "10751"),
    Any("Anything", "Surprise us", "", "", "27"),
}

enum class Kind { Movie, Tv }

/** A title from TMDB, as a candidate or a pick. [key] is "movie:123" / "tv:456". */
data class Title(
    val kind: Kind,
    val id: Int,
    val name: String,
    val year: String = "",
    val overview: String = "",
    val genres: List<Int> = emptyList(),
    val language: String = "",
    val rating: Double = 0.0,
    val votes: Int = 0,
    val popularity: Double = 0.0,
    val posterPath: String? = null,
    val runtimeMin: Int? = null,
    val service: String? = null,
    /** Came out (or had new episodes) recently. */
    val fresh: Boolean = false,
) {
    val key get() = "${if (kind == Kind.Movie) "movie" else "tv"}:$id"
}

data class Pick(val title: Title, val why: String)

/** One service the user can turn on, with how to find it in TMDB and on the TV. */
data class Service(val name: String, val tmdbNames: List<String>, val lgAppHints: List<String>)

object Catalog {
    /** Services offered in setup (US). TMDB provider IDs are looked up by name at runtime. */
    val SERVICES = listOf(
        Service("Netflix", listOf("Netflix"), listOf("netflix")),
        Service("Prime Video", listOf("Amazon Prime Video"), listOf("amazon")),
        Service("Disney+", listOf("Disney Plus"), listOf("com.disney.disneyplus-prod", "disney")),
        Service("Apple TV+", listOf("Apple TV Plus", "Apple TV+", "Apple TV"), listOf("com.apple.appletv", "appletv")),
        Service("Max", listOf("Max", "HBO Max"), listOf("hbomax", "com.hbo", "max")),
        Service("Hulu", listOf("Hulu"), listOf("hulu")),
        Service("hoichoi", listOf("Hoichoi", "hoichoi"), listOf("hoichoi")),
        Service("ZEE5", listOf("Zee5", "ZEE5"), listOf("zee5", "zee")),
        Service("Peacock", listOf("Peacock Premium", "Peacock"), listOf("peacock")),
        Service("Paramount+", listOf("Paramount Plus", "Paramount+"), listOf("paramount")),
    )

    /** Languages offered in setup: label → ISO 639-1 (TMDB original_language). */
    val LANGUAGES = linkedMapOf(
        "English" to "en", "Bengali" to "bn", "Hindi" to "hi", "Tamil" to "ta", "Telugu" to "te",
        "Spanish" to "es", "Korean" to "ko", "Japanese" to "ja", "French" to "fr",
    )

    fun languageLabel(code: String) = LANGUAGES.entries.firstOrNull { it.value == code }?.key ?: code.uppercase()

    const val TOGETHER = "Together"
}

/** Time-of-day / day-of-week context. Pure, so it's easy to test. */
object Moment {
    enum class Part { Morning, Afternoon, Evening, Late }

    fun part(hour: Int): Part = when (hour) {
        in 5..11 -> Part.Morning
        in 12..16 -> Part.Afternoon
        in 17..20 -> Part.Evening
        else -> Part.Late
    }

    /** The "evening" a moment belongs to: 1 am Saturday still counts as Friday night. */
    fun eveningDay(cal: Calendar): Int {
        val c = cal.clone() as Calendar
        if (c.get(Calendar.HOUR_OF_DAY) < 5) c.add(Calendar.DAY_OF_MONTH, -1)
        return c.get(Calendar.DAY_OF_WEEK)
    }

    fun isWeekendNight(cal: Calendar) = eveningDay(cal).let { it == Calendar.FRIDAY || it == Calendar.SATURDAY }

    /** Slot used for learning, e.g. "weekday-late", "weekend-afternoon". */
    fun slot(cal: Calendar): String {
        val weekend = when (part(cal.get(Calendar.HOUR_OF_DAY))) {
            Part.Morning, Part.Afternoon -> cal.get(Calendar.DAY_OF_WEEK).let { it == Calendar.SATURDAY || it == Calendar.SUNDAY }
            else -> isWeekendNight(cal)
        }
        return "${if (weekend) "weekend" else "weekday"}-${part(cal.get(Calendar.HOUR_OF_DAY)).name.lowercase()}"
    }

    /**
     * Minutes until the usual bedtime for this evening. Bedtimes are minutes after midnight; ones before
     * noon (e.g. 00:30 = 30) mean "after midnight". Returns null when bedtime has passed.
     */
    fun minutesLeft(cal: Calendar, weekdayBed: Int, weekendBed: Int): Int? {
        val bed = if (isWeekendNight(cal)) weekendBed else weekdayBed
        val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        // Put "now" and bedtime on one timeline that runs from 5:00 to 29:00 (= 5:00 next day).
        fun line(m: Int) = if (m < 5 * 60) m + 24 * 60 else m
        val left = line(bed) - line(nowMin)
        return if (left > 0) left else null
    }

    /** "Tuesday evening · about 1 h 15 m free" */
    fun contextLine(cal: Calendar, minutesLeft: Int?): String {
        val day = cal.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, java.util.Locale.US)
        val part = when (part(cal.get(Calendar.HOUR_OF_DAY))) {
            Part.Morning -> "morning"; Part.Afternoon -> "afternoon"; Part.Evening -> "evening"; Part.Late -> "night"
        }
        val free = when {
            minutesLeft == null -> "past your usual bedtime"
            minutesLeft >= 240 -> "plenty of time"
            else -> "about ${duration(minutesLeft)} free"
        }
        return "$day $part · $free"
    }

    fun duration(min: Int): String = when {
        min < 60 -> "$min min"
        min % 60 == 0 -> "${min / 60} h"
        else -> "${min / 60} h ${min % 60} m"
    }

    /** Default mood order for a slot, before learning. */
    fun defaultMoodOrder(slot: String): List<Mood> = when {
        slot.endsWith("late") && slot.startsWith("weekday") -> listOf(Mood.Light, Mood.New, Mood.Cozy, Mood.Gripping, Mood.MindBending)
        slot.startsWith("weekend") && (slot.endsWith("evening") || slot.endsWith("late")) -> listOf(Mood.Gripping, Mood.New, Mood.MindBending, Mood.Light, Mood.Cozy)
        slot.endsWith("afternoon") || slot.endsWith("morning") -> listOf(Mood.Cozy, Mood.New, Mood.Light, Mood.Gripping, Mood.MindBending)
        else -> listOf(Mood.Light, Mood.New, Mood.Gripping, Mood.Cozy, Mood.MindBending)
    }

    /** Learned order: moods picked most in this slot first; ties keep the default order. */
    fun moodOrder(slot: String, counts: Map<String, Int>): List<Mood> {
        val base = defaultMoodOrder(slot)
        return base.sortedWith(compareByDescending<Mood> { counts[it.name] ?: 0 }.thenBy { base.indexOf(it) })
    }
}
