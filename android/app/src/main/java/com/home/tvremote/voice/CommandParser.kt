package com.home.tvremote.voice

data class TvApp(val id: String, val title: String, val icon: String? = null)

sealed interface Command {
    data class Power(val on: Boolean) : Command
    data class VolumeStep(val up: Boolean, val steps: Int) : Command
    data class VolumeSet(val level: Int) : Command
    data class Mute(val mute: Boolean) : Command
    data class Media(val action: String) : Command            // play, pause, stop, rewind, fastForward
    data class Button(val name: String) : Command             // UP, DOWN, LEFT, RIGHT, ENTER, BACK, HOME, MENU, INFO
    data class ChannelStep(val up: Boolean) : Command
    data class ChannelNumber(val number: String) : Command
    data class Input(val hdmi: Int) : Command
    data class OpenApp(val app: TvApp) : Command
    data class Search(val query: String, val app: TvApp?) : Command
    /** "open X" where X isn't an app on the TV. */
    data class NoSuchApp(val name: String) : Command
}

/**
 * Turns what someone says or types ("open netflix", "louder", "find the office on youtube")
 * into a TV command. Runs on the phone, no network or account needed.
 */
class CommandParser(tvApps: List<TvApp>) {
    // The TV's own list first; well-known LG app IDs fill in if the TV didn't share its list.
    private val apps: List<TvApp> = tvApps + KNOWN_APPS.filter { k -> tvApps.none { it.id == k.id || key(it.title) == key(k.title) } }

    fun parse(raw: String): Command? {
        val t = normalize(raw)
        if (t.isEmpty()) return null

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
        if (t.matches(Regex("(mute|silence|quiet)( (it|the tv|the sound|sound))?|shh+"))) return Command.Mute(true)
        if (t.matches(Regex("unmute( (it|the tv|the sound))?|sound on"))) return Command.Mute(false)

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
            return Command.Search(query, app)
        }

        // Apps: "open netflix", "launch prime video", or just "netflix"
        Regex("(?:open|launch|start|go to|switch to|run|load)(?: up)?(?: the)? (.+?)(?: app)?").matchEntire(t)?.let { m ->
            val name = m.groupValues[1]
            // They clearly asked for an app, so accept a looser sound-alike ("netflex", "nerfkex").
            return (matchApp(name) ?: fuzzyApp(name, lenient = true))?.let { Command.OpenApp(it) } ?: Command.NoSuchApp(name)
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
            var t = s.lowercase().replace(Regex("[^a-z0-9+ ]"), " ").replace(Regex("\\s+"), " ").trim()
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
