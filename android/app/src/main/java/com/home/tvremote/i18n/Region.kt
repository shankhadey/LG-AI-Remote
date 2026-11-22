package com.home.tvremote.i18n

/**
 * Countries the app is set up for. The country decides where-to-watch data (TMDB region), which streaming
 * services are offered, the content languages suggested at setup, the speech accent and the clock.
 */
data class Country(
    val code: String,                 // ISO 3166-1, also TMDB's watch_region
    val name: String,                 // English; translated for display
    val speech: String,               // speech recognizer locale for English (pt-BR when the app is in Portuguese)
    val languages: List<String>,      // content languages suggested at setup (ISO 639-1)
    val clock24: Boolean,
) {
    /** For sentences: "in the United States", "in Brazil". */
    val inName: String get() = if (code == "US" || code == "GB") "the $name" else name
}

object Regions {
    val ALL = listOf(
        Country("US", "United States", "en-US", listOf("en"), clock24 = false),
        Country("BR", "Brazil", "en-US", listOf("pt", "en"), clock24 = true),
        Country("IN", "India", "en-IN", listOf("en", "hi"), clock24 = false),
        Country("CA", "Canada", "en-CA", listOf("en", "fr"), clock24 = false),
        Country("GB", "United Kingdom", "en-GB", listOf("en"), clock24 = true),
        Country("AU", "Australia", "en-AU", listOf("en"), clock24 = false),
    )
    fun of(code: String?): Country = ALL.firstOrNull { it.code == code } ?: ALL.first()

    /** The phone's country if we support it, else the US. */
    fun fromPhone(locale: java.util.Locale = java.util.Locale.getDefault()): Country = of(locale.country.uppercase())

    /** App languages: "en" or "pt" (Brazilian Portuguese). "auto" follows the phone. */
    val LANGUAGES = listOf("auto" to "Automatic", "en" to "English", "pt" to "Português (Brasil)")
    fun resolve(setting: String, locale: java.util.Locale = java.util.Locale.getDefault()): String =
        when (setting) { "en", "pt" -> setting; else -> if (locale.language == "pt") "pt" else "en" }
}
