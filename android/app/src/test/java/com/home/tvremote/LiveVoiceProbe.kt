package com.home.tvremote

import com.home.tvremote.picks.Tmdb
import com.home.tvremote.voice.CommandParser
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

class LiveVoiceProbe {
    @Test fun probe() = runBlocking {
        val p = CommandParser(emptyList())
        for (s in listOf("show me some adam sandler movies", "show me some adam sanfler movies", "can you show me some adam sandler movies")) println("PROBE parse0 '$s' -> ${p.parse(s)}")
        val key = System.getenv("TMDB_KEY"); assumeTrue(!key.isNullOrBlank())
        for (s in listOf("show me some adam sandler movies", "show me some adam sanfler movies", "Show me some Adam Sandler movies", "can you show me some adam sandler movies", "open stranger things", "hoping stranger things", "hope in stranger things", "open stranger thanks", "play barbie", "play movies with jennifer aniston"))
            println("PROBE parse '$s' -> ${p.parse(s)}")
        val t = Tmdb(key!!)
        println("PROBE person sanfler -> " + t.findPerson("adam sanfler"))
        println("PROBE search barbie -> " + t.search("barbie").map { "${it.name} (${it.year}) ${it.key}" })
        for (person in listOf("Jennifer Aniston", "Bryan Cranston")) {
            val c = t.personCredits(person)
            println("PROBE credits $person -> " + c.map { "${it.name} ${it.kind} v${it.votes}" })
        }
        val b = t.search("barbie").first()
        println("PROBE barbie providers -> " + t.providerNames(b))
    }
}
