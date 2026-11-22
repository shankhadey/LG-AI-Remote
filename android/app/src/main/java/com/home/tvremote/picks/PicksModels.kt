package com.home.tvremote.picks

import java.util.Calendar

/** TV genres that are never picks: kids, news, reality, soap, talk. */
private const val TV_JUNK = "10762|10763|10764|10766|10767"

/**
 * Moods offered as one-tap buttons: TMDB genres to include (any of) and exclude, separately for films and series.
 * Kept narrow so each mood gives a clearly different set (live tests showed broad "Mystery" made
 * Mind-bending return crime procedurals, and comedy pulled in mostly cartoons).
 */
enum class Mood(val label: String, val hint: String, val movieGenres: String, val tvGenres: String, val movieWithout: String, val tvWithout: String) {
    New("New", "Out this year", "", "", "27|10770", TV_JUNK),
    Light("Light & funny", "Easy, no thinking", "35", "35", "27|53|10752|10770", "80|$TV_JUNK"),
    Cozy("Cozy", "Warm, comforting", "10751|10749|35", "35|10751", "27|53|80|10752|10770", "80|10765|10759|$TV_JUNK"),
    Gripping("Gripping", "Can't look away", "53|80|9648", "80|9648", "27|16|10751|10770", "16|10751|35|$TV_JUNK"),
    MindBending("Mind-bending", "Twists, big ideas", "878|14", "10765", "16|10751|10770", "16|10751|35|$TV_JUNK"),
    Any("Anything", "Surprise us", "", "", "27|10770", TV_JUNK),
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
    /** Released / first aired within the last two years. */
    val fresh: Boolean = false,
    /** Position in TMDB's trending-this-week list (0 = top), or -1. */
    val trendRank: Int = -1,
) {
    val key get() = "${if (kind == Kind.Movie) "movie" else "tv"}:$id"
}

data class Pick(val title: Title, val why: String)

/** One service the user can turn on, with how to find it in TMDB and on the TV. */
/** A streaming service: its TMDB names, hints to find its app on the TV, and the countries it's offered in at setup. */
data class Service(val name: String, val tmdbNames: List<String>, val lgAppHints: List<String>,
                   val countries: Set<String> = setOf("US", "BR", "IN", "CA", "GB", "AU"))

object Catalog {
    /** Services offered in setup (US). TMDB provider IDs are looked up by name at runtime. */
    val SERVICES = listOf(
        Service("Netflix", listOf("Netflix"), listOf("netflix")),
        Service("Prime Video", listOf("Amazon Prime Video"), listOf("amazon")),
        Service("Disney+", listOf("Disney Plus"), listOf("com.disney.disneyplus-prod", "disney"), setOf("US", "BR", "CA", "GB", "AU")),
        Service("Apple TV+", listOf("Apple TV Plus", "Apple TV+", "Apple TV"), listOf("com.apple.appletv", "appletv")),
        Service("Max", listOf("Max", "HBO Max"), listOf("com.wbd.stream", "hbomax", "com.hbo", "max"), setOf("US", "BR")),   // com.wbd.stream seen on our 2023 LG
        Service("Hulu", listOf("Hulu"), listOf("hulu"), setOf("US")),
        Service("Peacock", listOf("Peacock Premium", "Peacock"), listOf("peacock"), setOf("US")),
        Service("Paramount+", listOf("Paramount Plus", "Paramount+"), listOf("paramount"), setOf("US", "BR", "CA", "GB", "AU")),
        // Brazil
        Service("Globoplay", listOf("Globoplay"), listOf("globoplay", "globo"), setOf("BR")),
        // India
        Service("JioHotstar", listOf("JioHotstar", "Hotstar", "Disney Plus Hotstar"), listOf("hotstar", "jio"), setOf("IN")),
        Service("ZEE5", listOf("Zee5", "ZEE5"), listOf("zee5", "zee"), setOf("US", "IN")),
        Service("Sony LIV", listOf("Sony Liv", "SonyLIV"), listOf("sonyliv", "sony"), setOf("IN")),
        Service("hoichoi", listOf("Hoichoi", "hoichoi"), listOf("hoichoi"), setOf("US", "IN", "BR")),
        // Canada
        Service("Crave", listOf("Crave"), listOf("crave", "bellmedia"), setOf("CA")),
        // United Kingdom
        Service("BBC iPlayer", listOf("BBC iPlayer"), listOf("iplayer", "bbc"), setOf("GB")),
        Service("ITVX", listOf("ITVX", "ITV Hub"), listOf("itvx", "itv"), setOf("GB")),
        Service("Channel 4", listOf("Channel 4"), listOf("channel4", "all4"), setOf("GB")),
        Service("NOW", listOf("Now TV", "NOW", "Now TV Cinema"), listOf("nowtv"), setOf("GB")),
        // Australia
        Service("Stan", listOf("Stan"), listOf("stan"), setOf("AU")),
        Service("Binge", listOf("BINGE", "Binge"), listOf("binge"), setOf("AU")),
        Service("ABC iview", listOf("ABC iview"), listOf("iview", "abc"), setOf("AU")),
        Service("SBS On Demand", listOf("SBS On Demand"), listOf("sbs"), setOf("AU")),
    )

    /** Services offered at setup in [country] (the full list stays valid for lookups). */
    fun servicesFor(country: String) = SERVICES.filter { country in it.countries }

    /** Languages offered in setup: label → ISO 639-1 (TMDB original_language). */
    val LANGUAGES = linkedMapOf(
        "English" to "en", "Bengali" to "bn", "Hindi" to "hi", "Tamil" to "ta", "Telugu" to "te",
        "Spanish" to "es", "Korean" to "ko", "Japanese" to "ja", "French" to "fr", "Portuguese" to "pt", "Malayalam" to "ml",
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
