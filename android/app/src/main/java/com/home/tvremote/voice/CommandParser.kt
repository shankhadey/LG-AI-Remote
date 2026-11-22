package com.home.tvremote.voice

data class TvApp(val id: String, val title: String, val icon: String? = null, val launchExtras: org.json.JSONObject? = null)

sealed interface Command {
    data class Power(val on: Boolean) : Command
    data class VolumeStep(val up: Boolean, val steps: Int) : Command
    data class VolumeSet(val level: Int) : Command
    data class Mute(val mute: Boolean) : Command
    data class Media(val action: String) : Command            // play, pause, stop, rewind, fastForward
    /** "skip 5 minutes" (+300), "go back 30 seconds" (−30): sent as 10-second arrow presses. */
    data class Seek(val seconds: Int) : Command
    data class Button(val name: String) : Command             // UP, DOWN, LEFT, RIGHT, ENTER, BACK, HOME, MENU, INFO
    data class ChannelStep(val up: Boolean) : Command
    data class ChannelNumber(val number: String) : Command
    data class Input(val hdmi: Int) : Command
    data class OpenApp(val app: TvApp) : Command
    /** [title]: "play / watch / put on X" — X is probably a show or film, so look it up before searching. */
    data class Search(val query: String, val app: TvApp?, val title: Boolean = false) : Command
    /** "open X" where X isn't an app on the TV. */
    data class NoSuchApp(val name: String) : Command
    /** "open X" where X only loosely sounds like an app: check it isn't a show or film called X first. */
    data class MaybeApp(val app: TvApp, val spoken: String) : Command
    /** Turn the TV off after [minutes], with pop-up reminders. */
    data class SleepTimer(val minutes: Int) : Command
    /** Show a full-screen countdown on the TV. */
    data class ScreenTimer(val minutes: Int) : Command
    data object CancelTimer : Command
    /** Open "What should we watch?", optionally with what was asked for ("something funny"). */
    data class WhatToWatch(val text: String?) : Command
    data class ExtendTimer(val minutes: Int) : Command
}

/**
 * Turns what someone says or types ("open netflix", "louder", "find the office on youtube")
 * into a TV command. Runs on the phone, no network or account needed.
 */
class CommandParser(tvApps: List<TvApp>) {
    // The TV's own list first; well-known LG app IDs fill in if the TV didn't share its list.
    private val apps: List<TvApp> = tvApps + KNOWN_APPS.filter { k -> tvApps.none { it.id == k.id || key(it.title) == key(k.title) } }

    fun parse(raw: String): Command? {
        val t = Portuguese.toEnglish(normalize(raw))
        if (t.isEmpty()) return null

        // Timers (before power, so "turn off the tv in 30 minutes" isn't "turn off now")
        val dur = "(an hour|half an hour|\\d+ (?:hours?|hrs?|minutes?|mins?))"
        Regex("(?:turn|switch|power|shut) (?:the )?(?:tv |television |it )?(?:off|down) (?:the )?(?:tv |television )?in $dur|(?:set )?(?:a |the )?sleep timer (?:for |to )?$dur|sleep in $dur|tv off in $dur")
            .matchEntire(t)?.let { m -> return Command.SleepTimer(minutes(m.groupValues.drop(1).first { it.isNotEmpty() })) }
        Regex("(?:set|start)? ?(?:a |the )?timer (?:for |of )?$dur|(?:set |start )?(?:a |an )?(\\d+) (?:minute|min) timer( on (?:the )?(?:tv|screen))?")
            .matchEntire(t)?.let { m ->
                val g = m.groupValues
                return Command.ScreenTimer(if (g[1].isNotEmpty()) minutes(g[1]) else g[2].toInt())
            }
        if (t.matches(Regex("(?:cancel|stop|clear|end) (?:the |my )?(?:sleep )?timer|no timer"))) return Command.CancelTimer
        Regex("(?:add|give me|extend (?:the )?timer by|plus) (\\d+) (?:more )?(?:minutes?|mins?)|(\\d+) more (?:minutes?|mins?)").matchEntire(t)?.let { m ->
            return Command.ExtendTimer((m.groupValues[1].ifEmpty { m.groupValues[2] }).toInt())
        }

        // Power
        if (t.matches(Regex("(turn|switch|power) (the )?(tv|television|it) off|(turn|switch|power) off( the)?( tv| television)?|tv off|shut (it )?down|go to sleep|goodnight|good night")))
            return Command.Power(false)
        if (t.matches(Regex("(turn|switch|power) (the )?(tv|television|it) on|(turn|switch|power) on( the)?( tv| television)?|tv on|wake up")))
            return Command.Power(true)

        // Volume
        Regex("(?:set )?(?:the )?volume (?:to |at )?(\\d+)(?: percent)?").matchEntire(t)?.let {
            return Command.VolumeSet(it.groupValues[1].toInt().coerceIn(0, 100))
        }
        Regex("(?:turn (?:it |the volume |the sound |the tv )?|volume |make it |a bit |little )?(up|down|louder|quieter|softer|lower|higher)(?: the volume)?(?: by (\\d+))?(?: a (lot|bit|little))?").matchEntire(t)?.let {
            val dir = it.groupValues[1]
            if (dir == "up" || dir == "down") {
                // bare "up"/"down" are arrow keys; only treat as volume with a volume word
                if (!t.contains("volume") && !t.contains("turn")) return@let
            }
            val up = dir in setOf("up", "louder", "higher")
            val steps = it.groupValues[2].toIntOrNull() ?: when (it.groupValues[3]) { "lot" -> 8; "bit", "little" -> 2; else -> 3 }
            return Command.VolumeStep(up, steps.coerceIn(1, 30))
        }
        if (t.matches(Regex("(mute|silence|quiet)( (it|the tv|the sound|sound|the volume|volume|the audio))?|shh+"))) return Command.Mute(true)
        if (t.matches(Regex("unmute( (it|the tv|the sound|the volume|the audio))?|sound on|turn (the )?sound (back )?on"))) return Command.Mute(false)

        // Looser volume phrasing: "reduce volume", "can you lower the sound", "it's too loud",
        // "turn the volume to 15". Any sentence about volume with one clear direction.
        val looksLikeSearch = Regex("^(search|find|look|play|watch|show me|put on)\\b").containsMatchIn(t)
        val aboutVolume = !looksLikeSearch && Regex("\\b(volume|sound|audio|loud|louder|quiet|quieter|softer)\\b").containsMatchIn(t)
        if (aboutVolume) {
            Regex("\\b(?:to|at)\\s(\\d+)").find(t)?.let { return Command.VolumeSet(it.groupValues[1].toInt().coerceIn(0, 100)) }
            val down = Regex("\\b(down|reduce|decrease|lower|less|quieter|softer|drop|cut|too loud)\\b").containsMatchIn(t)
            val up = Regex("\\b(up|increase|raise|louder|higher|more|boost|too quiet|cant hear|can t hear)\\b").containsMatchIn(t)
            if (up != down) {
                val n = Regex("\\bby (\\d+)").find(t)?.groupValues?.get(1)?.toIntOrNull()
                val steps = n ?: if (Regex("\\b(lot|way|much)\\b").containsMatchIn(t)) 8 else if (Regex("\\b(bit|little|slightly)\\b").containsMatchIn(t)) 2 else 3
                return Command.VolumeStep(up, steps.coerceIn(1, 30))
            }
        }

        // Looser playback phrasing: "pause the movie", "stop the video", "resume the show".
        Regex("(pause|stop|resume|unpause|continue)(?: (?:the|this) (?:show|movie|video|film|episode|music|song|tv|game|match))?(?: for a (?:second|sec|minute|bit))?").matchEntire(t)?.let {
            return Command.Media(when (it.groupValues[1]) { "pause" -> "pause"; "stop" -> "stop"; else -> "play" })
        }

        // Skipping by an amount: "skip 5 minutes", "go back 30 seconds", "forward 2 min", "rewind a minute"
        Regex("(skip ahead|skip forward|skip|fast forward|forward|go forward|jump ahead|ahead|go back|back|rewind|skip back|jump back)(?: by)? (\\d+|an?|one|two|three|four|five|ten|fifteen|twenty|thirty) ?(seconds?|secs?|minutes?|mins?)").matchEntire(t)?.let { m ->
            val n = when (val w = m.groupValues[2]) { "a", "an", "one" -> 1; "two" -> 2; "three" -> 3; "four" -> 4; "five" -> 5; "ten" -> 10
                "fifteen" -> 15; "twenty" -> 20; "thirty" -> 30; else -> w.toIntOrNull() ?: 1 }
            val secs = if (m.groupValues[3].startsWith("m")) n * 60 else n
            val back = m.groupValues[1].let { it.contains("back") || it == "rewind" }
            return Command.Seek(if (back) -secs else secs)
        }

        // Playback (bare words only — "play X" is a search)
        when (t) {
            "play", "resume", "continue", "unpause", "keep playing", "start" -> return Command.Media("play")
            "pause", "pause it", "hold on", "wait" -> return Command.Media("pause")
            "stop", "stop it" -> return Command.Media("stop")
            "rewind", "go back a bit", "back up" -> return Command.Media("rewind")
            "fast forward", "forward", "skip", "skip ahead" -> return Command.Media("fastForward")
        }

        // Navigation
        when (t) {
            "up", "go up", "move up" -> return Command.Button("UP")
            "down", "go down", "move down" -> return Command.Button("DOWN")
            "left", "go left", "move left" -> return Command.Button("LEFT")
            "right", "go right", "move right" -> return Command.Button("RIGHT")
            "ok", "okay", "select", "enter", "click", "choose", "choose that", "select that", "press ok" -> return Command.Button("ENTER")
            "back", "go back", "previous", "return" -> return Command.Button("BACK")
            "home", "go home", "home screen", "main menu" -> return Command.Button("HOME")
            "menu", "settings", "open settings" -> return Command.Button("MENU")
            "info", "information" -> return Command.Button("INFO")
        }

        // Channels / inputs
        if (t.matches(Regex("(next|channel up|change the channel|next channel)"))) return Command.ChannelStep(true)
        if (t.matches(Regex("(channel down|previous channel|last channel)"))) return Command.ChannelStep(false)
        Regex("(?:go to |switch to |put on |change to )?channel (\\d+(?:[ .-]\\d+)?)").matchEntire(t)?.let {
            return Command.ChannelNumber(it.groupValues[1].replace(Regex("[ .]"), "-"))
        }
        Regex("(?:switch to |change to |go to |use |input )?(?:the )?(?:hdmi|h d m i|input) ?(\\d)").matchEntire(t)?.let {
            return Command.Input(it.groupValues[1].toInt())
        }

        // "What should we watch?" / "find us something funny"
        if (t.matches(Regex("what (?:should|can|shall|do) (?:we|i) watch(?: (?:tonight|now|today))?|what to watch|decide for (?:us|me)|pick something(?: for (?:us|me))?")))
            return Command.WhatToWatch(null)
        if (t.matches(Regex("(?:find|recommend|suggest|pick|give) (?:us |me )?(?:something|a show|a movie|a film|a series|anything)(?: .+)?")))
            return Command.WhatToWatch(t)

        // Search: "search for X on Y", "find X", "play X on Y", "watch X", "look up X"
        Regex("(?:search(?: for)?|find|look up|look for|play|watch|show me|put on) (.+?)(?: (?:on|in|using) (.+))?").matchEntire(t)?.let { m ->
            val query = m.groupValues[1]
            val appName = m.groupValues[2]
            val app = if (appName.isNotEmpty()) matchApp(appName) ?: fuzzyApp(appName, lenient = true) else null
            // "put on netflix" / "watch youtube" → just open the app
            if (appName.isEmpty()) matchApp(query, exactOnly = true)?.let { return Command.OpenApp(it) }
            if (appName.isNotEmpty() && app == null) {
                // "on" was part of the title, e.g. "search for game on" — keep everything as the query
                return Command.Search(t.substringAfter(' ').removePrefix("for ").trim(), null)
            }
            val wantsTitle = Regex("^(play|watch|put on|show me) ").containsMatchIn(t)
            return Command.Search(query, app, title = wantsTitle)
        }

        // Apps: "open netflix", "launch prime video", or just "netflix"
        Regex("(?:open|launch|start|go to|switch to|run|load)(?: up)?(?: the)? (.+?)(?: app)?").matchEntire(t)?.let { m ->
            val name = m.groupValues[1]
            // They clearly asked for an app, so accept a looser sound-alike ("netflex", "nerfkex").
            matchApp(name, exactOnly = true)?.let { return Command.OpenApp(it) }
            return (matchApp(name) ?: fuzzyApp(name, lenient = true))?.let { Command.MaybeApp(it, name) } ?: Command.NoSuchApp(name)
        }
        matchApp(t, exactOnly = true)?.let { return Command.OpenApp(it) }
        fuzzyApp(t, lenient = false)?.let { return Command.OpenApp(it) }

        return null
    }

    fun matchApp(spoken: String, exactOnly: Boolean = false): TvApp? {
        val s = key(ALIASES[normalize(spoken)] ?: spoken)
        if (s.isEmpty()) return null
        apps.firstOrNull { key(it.title) == s }?.let { return it }
        if (exactOnly) return null
        apps.firstOrNull { key(it.title).startsWith(s) }?.let { return it }
        apps.firstOrNull { s.length >= 3 && key(it.title).contains(s) }?.let { return it }
        return apps.firstOrNull { val k = key(it.title); k.length >= 3 && s.contains(k) }
    }

    /** Closest app by edit distance. Strict: 1 typo in 5+ letters. Lenient: ~45% of letters. */
    fun fuzzyApp(spoken: String, lenient: Boolean): TvApp? {
        val s = key(ALIASES[normalize(spoken)] ?: spoken)
        if (s.length < (if (lenient) 3 else 5)) return null
        val scored = apps.map { it to distance(s, key(it.title)) }.sortedBy { it.second }
        val best = scored.firstOrNull() ?: return null
        val longest = maxOf(s.length, key(best.first.title).length)
        val allowed = if (lenient) (longest * 0.45).toInt() else 1
        if (best.second > allowed) return null
        // Ambiguous between two apps: don't guess.
        if (scored.size > 1 && scored[1].second == best.second) return null
        return best.first
    }

    companion object {
        /** "an hour" → 60, "half an hour" → 30, "2 hours" → 120, "25 minutes" → 25. */
        fun minutes(d: String): Int = when {
            d == "an hour" -> 60
            d == "half an hour" -> 30
            d.contains("h") -> d.substringBefore(' ').toInt() * 60
            else -> d.substringBefore(' ').toInt()
        }.coerceIn(1, 600)

        /** Common LG webOS app IDs, used when the TV doesn't share its installed-app list. */
        val KNOWN_APPS = listOf(
            TvApp("netflix", "Netflix"),
            TvApp("youtube.leanback.v4", "YouTube"),
            TvApp("amazon", "Prime Video"),
            TvApp("com.disney.disneyplus-prod", "Disney+"),
            TvApp("hulu", "Hulu"),
            TvApp("com.apple.appletv", "Apple TV"),
            TvApp("spotify-beehive", "Spotify"),
            TvApp("com.webos.app.livetv", "Live TV"),
            TvApp("com.webos.app.browser", "Web Browser"),
        )

        /** Common app IDs for the brand, used when the TV doesn't share its installed-app list. */
        fun knownApps(brand: String): List<TvApp> =
            if (brand == "Samsung") com.home.tvremote.samsung.SamsungApps.COMMON else KNOWN_APPS

        /**
         * Short power commands get mangled by speech recognition ("turn on TV" heard as "Tom TV | on TV | turn TV |
         * tongue TV", 22 Nov). When no single guess is a command but the guesses together say "TV" + a power word,
         * return on (true) / off (false); with neither word, the opposite of [tvIsOff]'s state. Null = not a power request.
         */
        fun powerFromGuesses(guesses: List<String>, tvIsOff: Boolean): Boolean? {
            val gs = guesses.map { normalize(it) }.filter { it.isNotEmpty() && it.split(' ').size <= 4 }
            if (gs.isEmpty()) return null
            val tv = gs.any { Regex("\\b(tv|tele|television|televisao)\\b").containsMatchIn(it) }
            val verb = gs.any { Regex("\\b(turn|switch|power|ligar|liga|ligue|desligar|desliga|desligue)\\b").containsMatchIn(it) }
            if (!tv || !verb) return null
            return when {
                gs.any { Regex("\\b(off|desligar|desliga|desligue)\\b").containsMatchIn(it) } -> false
                gs.any { Regex("\\b(on|ligar|liga|ligue)\\b").containsMatchIn(it) } -> true
                else -> tvIsOff
            }
        }

        fun distance(a: String, b: String): Int {
            val dp = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                var prev = dp[0]; dp[0] = i
                for (j in 1..b.length) {
                    val tmp = dp[j]
                    dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                    prev = tmp
                }
            }
            return dp[b.length]
        }

        private val ALIASES = mapOf(
            "amazon" to "prime video", "prime" to "prime video", "amazon prime" to "prime video",
            "disney" to "disney+", "disney plus" to "disney+", "hbo" to "max", "hbo max" to "max",
            "apple tv plus" to "apple tv", "apple tv+" to "apple tv", "paramount plus" to "paramount+",
            "you tube" to "youtube", "the tube" to "youtube", "live tv" to "live tv", "tv" to "live tv",
            "browser" to "web browser", "internet" to "web browser", "spotify music" to "spotify",
        )
        private val NUMBERS = mapOf(
            "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
            "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14,
            "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19, "twenty" to 20,
            "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90,
            "hundred" to 100,
        )
        private val FILLER = Regex("^(hey tv |ok tv |tv |please |can you |could you |would you |i want to |i wanna |let's |lets )+|( please| for me| now| thanks| thank you)+$")

        fun normalize(s: String): String {
            // Accents folded first so Portuguese survives ("próximo" → "proximo"); English is unchanged.
            val folded = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            var t = folded.replace(Regex("[^a-z0-9+ ]"), " ").replace(Regex("\\s+"), " ").trim()
            repeat(2) { t = t.replace(FILLER, "").trim() }
            // "twenty five" -> 25, "ten" -> 10
            t = Regex("\\b(twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety) (one|two|three|four|five|six|seven|eight|nine)\\b")
                .replace(t) { (NUMBERS[it.groupValues[1]]!! + NUMBERS[it.groupValues[2]]!!).toString() }
            t = Regex("\\b(${NUMBERS.keys.joinToString("|")})\\b").replace(t) { NUMBERS[it.value].toString() }
            return t
        }

        private fun key(s: String) = s.lowercase().replace("+", "plus").filter { it.isLetterOrDigit() }
    }
}
