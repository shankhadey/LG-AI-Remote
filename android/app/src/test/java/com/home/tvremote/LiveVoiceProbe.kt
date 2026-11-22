package com.home.tvremote

import com.home.tvremote.picks.Tmdb
import com.home.tvremote.voice.CommandParser
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

class LiveVoiceProbe {
    @Test fun probe() = runBlocking {
        val key = System.getenv("TMDB_KEY"); assumeTrue(!key.isNullOrBlank())
        val p = CommandParser(emptyList())
        for (s in listOf("open stranger things", "hoping stranger things", "hope in stranger things", "open stranger thanks", "play barbie", "play movies with jennifer aniston"))
            println("PROBE parse '$s' -> ${p.parse(s)}")
        val t = Tmdb(key!!)
        println("PROBE search barbie -> " + t.search("barbie").map { "${it.name} (${it.year}) ${it.key}" })
        for (person in listOf("Jennifer Aniston", "Bryan Cranston")) {
            val c = t.personCredits(person)
            println("PROBE credits $person -> " + c.map { "${it.name} ${it.kind} v${it.votes}" })
        }
        val b = t.search("barbie").first()
        println("PROBE barbie providers -> " + t.providerNames(b))
    }
}
