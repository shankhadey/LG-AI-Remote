package com.home.tvremote

import com.home.tvremote.voice.Command
import com.home.tvremote.voice.CommandParser
import com.home.tvremote.voice.TvApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommandParserTest {
    private val netflix = TvApp("netflix", "Netflix")
    private val youtube = TvApp("youtube.leanback.v4", "YouTube")
    private val prime = TvApp("amazon", "Prime Video")
    private val disney = TvApp("com.disney.disneyplus-prod", "Disney+")
    private val p = CommandParser(listOf(netflix, youtube, prime, disney, TvApp("com.webos.app.livetv", "Live TV")))

    private fun check(said: String, expected: Command?) = assertEquals(said, expected, p.parse(said))

    @Test fun power() {
        check("Turn off the TV", Command.Power(false))
        check("turn the tv off please", Command.Power(false))
        check("good night", Command.Power(false))
        check("turn on the TV", Command.Power(true))
    }

    @Test fun volume() {
        check("volume up", Command.VolumeStep(true, 3))
        check("turn it down", Command.VolumeStep(false, 3))
        check("turn up the volume", Command.VolumeStep(true, 3))
        check("louder", Command.VolumeStep(true, 3))
        check("a bit quieter", Command.VolumeStep(false, 3))
        check("volume up by 5", Command.VolumeStep(true, 5))
        check("louder a lot", Command.VolumeStep(true, 8))
        check("set the volume to 20", Command.VolumeSet(20))
        check("volume twenty five", Command.VolumeSet(25))
        check("Volume 150", Command.VolumeSet(100))
        check("mute", Command.Mute(true))
        check("unmute the tv", Command.Mute(false))
    }

    @Test fun playbackAndNavigation() {
        check("pause", Command.Media("pause"))
        check("Play.", Command.Media("play"))
        check("resume", Command.Media("play"))
        check("fast forward", Command.Media("fastForward"))
        check("up", Command.Button("UP"))
        check("go left", Command.Button("LEFT"))
        check("select", Command.Button("ENTER"))
        check("go back", Command.Button("BACK"))
        check("go home", Command.Button("HOME"))
    }

    @Test fun channelsAndInputs() {
        check("channel up", Command.ChannelStep(true))
        check("go to channel 7", Command.ChannelNumber("7"))
        check("channel 5.1", Command.ChannelNumber("5-1"))
        check("switch to HDMI 2", Command.Input(2))
        check("hdmi one", Command.Input(1))
    }

    @Test fun apps() {
        check("open Netflix", Command.OpenApp(netflix))
        check("launch prime video", Command.OpenApp(prime))
        check("open amazon", Command.OpenApp(prime))
        check("put on disney plus", Command.OpenApp(disney))
        check("YouTube", Command.OpenApp(youtube))
        check("hey tv can you open the youtube app", Command.OpenApp(youtube))
        check("watch netflix", Command.OpenApp(netflix))
    }

    @Test fun search() {
        check("search for stranger things on netflix", Command.Search("stranger things", netflix))
        check("find cooking videos", Command.Search("cooking videos", null))
        check("play taylor swift on youtube", Command.Search("taylor swift", youtube))
        check("watch the office", Command.Search("the office", null))
        check("look up how to tie a tie", Command.Search("how to tie a tie", null))
    }

    @Test fun misheardAppNames() {
        check("open netflex", Command.OpenApp(netflix))      // what the phone actually heard
        check("open nerfkex", Command.OpenApp(netflix))      // what was typed in the bug report
        check("Netflex", Command.OpenApp(netflix))
        check("launch you tube", Command.OpenApp(youtube))
        check("search for the crown on netflex", Command.Search("the crown", netflix))
        check("open zebra cakes", Command.NoSuchApp("zebra cakes"))
    }

    @Test fun knownAppsWorkWhenTvDoesNotShareItsList() {
        val bare = CommandParser(emptyList())
        assertEquals(Command.OpenApp(TvApp("netflix", "Netflix")), bare.parse("open netflix"))
        assertEquals(Command.OpenApp(TvApp("youtube.leanback.v4", "YouTube")), bare.parse("open youtube"))
        assertEquals(Command.OpenApp(TvApp("netflix", "Netflix")), bare.parse("open netflex"))
    }

    @Test fun nonsenseIsNotACommand() {
        assertNull(p.parse(""))
        assertNull(p.parse("the quick brown fox"))
    }
}
