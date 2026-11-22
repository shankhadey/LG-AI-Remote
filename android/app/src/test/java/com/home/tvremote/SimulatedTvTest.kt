package com.home.tvremote

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.home.tvremote.picks.DeepLinks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.Executors

/**
 * The whole app controller against a simulated LG TV, end to end and in real time: the same code as on the phone,
 * talking the TV's protocol over a real socket. Each test is a situation seen on the real TV.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SimulatedTvTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6)   // only for an Android Context

    private class App(base: Context) : Application() {
        private val prefs = HashMap<String, FakePrefs>()
        init { attachBaseContext(base) }
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs.getOrPut(name ?: "") { FakePrefs() }
        override fun getApplicationContext(): Context = this
    }

    private lateinit var sim: SimulatedLgTv
    private val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    @Before fun setUp() { Dispatchers.setMain(main); sim = SimulatedLgTv().start(); TvController.net = sim.net }
    @After fun tearDown() { TvController.net = TvController.RealNet; sim.stop(); Dispatchers.resetMain(); main.close() }

    /** A phone that has paired with the TV before (IP, MACs and pairing key saved), opening the app. */
    private fun openApp(): TvController {
        val app = App(paparazzi.context)
        app.getSharedPreferences("tv", 0).edit().putString("ip", "192.168.1.195").putString("mac", "f4:ca:e7:87:7e:1e")
            .putString("clientKey", "SIMKEY").putBoolean("nameCustom", true).apply()
        return onMain { TvController(app).also { it.appVisible = true } }
    }

    private fun openAppWithPrefs(): Pair<TvController, Application> {
        val app = App(paparazzi.context)
        app.getSharedPreferences("tv", 0).edit().putString("ip", "192.168.1.195").putString("mac", "f4:ca:e7:87:7e:1e")
            .putString("clientKey", "SIMKEY").putBoolean("nameCustom", true).putString("name", "Living Room").apply()
        return onMain { TvController(app).also { it.appVisible = true } } to app
    }

    private fun <T> onMain(block: () -> T): T = kotlinx.coroutines.runBlocking(main) { block() }

    /** Waits up to [ms] for [cond]; fails with [what] and the app's Diagnostics if it never happens. */
    private fun eventually(what: String, tv: TvController? = null, ms: Long = 6000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (onMain { cond() }) return; Thread.sleep(50) }
        throw AssertionError("$what (waited $ms ms)\n" + (tv?.let { onMain { it.diagnostics() } }?.take(3000) ?: ""))
    }
    private fun stays(what: String, tv: TvController, ms: Long, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (!onMain { cond() }) throw AssertionError("$what\n" + onMain { tv.diagnostics() }.take(3000)); Thread.sleep(100) }
    }

    // ---------------- power ----------------

    @Test fun tvOnWhenAppOpens_showsTheRemote() {
        val tv = openApp()
        eventually("connects", tv) { tv.connected && !tv.tvOff }
    }

    @Test fun quickStartStandby_appOpens_showsOff_thenTurnOnWorksAndStays() {
        // The 21 Nov bug: a standby TV accepts the connection but never answers pairing; the hung attempt later
        // failed and flipped the app back to "Turn on" after turning on had already worked.
        sim.power = "Active Standby"
        val tv = openApp()
        eventually("shows the Turn on screen for a TV in standby", tv, ms = 12_000) { tv.tvOff && !tv.connected }
        onMain { tv.turnOn() }
        eventually("turns on and shows the remote", tv, ms = 8000) { tv.connected && !tv.tvOff && !tv.waking }
        stays("must not flip back to Turn on", tv, 9000) { tv.connected && !tv.tvOff }
    }

    @Test fun tvTurnedOnAndOffWithItsOwnRemote_appFollows() {
        val tv = openApp()
        eventually("connects", tv) { tv.connected && !tv.tvOff }
        sim.turnedOffByRemote()
        eventually("notices the TV went off", tv) { tv.tvOff }
        sim.turnedOnByRemote()
        eventually("notices the TV came back on", tv) { !tv.tvOff }
    }

    @Test fun turnOffFromTheApp() {
        val tv = openApp()
        eventually("connects", tv) { tv.connected }
        onMain { tv.power() }
        eventually("sends turnOff and shows Turn on", tv) { sim.sent("system/turnOff").isNotEmpty() && tv.tvOff }
        onMain { tv.refresh() }
        stays("doesn't reconnect to the TV while it's shutting down", tv, 2000) { tv.tvOff }
    }

    @Test fun droppedConnection_reconnectsByItself() {
        val tv = openApp()
        eventually("connects", tv) { tv.connected }
        sim.dropsConnections()
        eventually("notices the drop", tv) { !tv.connected }
        eventually("reconnects without the user doing anything", tv, ms = 10_000) { tv.connected }
    }

    // ---------------- first launch and turn-on help ----------------

    @Test fun firstLaunch_showsWelcomeOnce_existingUsersNever() {
        val fresh = onMain { TvController(App(paparazzi.context)) }
        assertTrue(onMain { fresh.showWelcome })
        onMain { fresh.welcomeDone() }
        assertFalse(onMain { fresh.showWelcome })
        assertEquals(Screen.Setup, onMain { fresh.screen })
        val existing = openApp()
        assertFalse("paired before: no welcome", onMain { existing.showWelcome })
    }

    @Test fun turnOnFails_showsHelp_secondFailureMore_thenWorksAndResets() {
        sim.power = "Off"; sim.quickStart = false; sim.wakeWorks = false
        val tv = openApp()
        onMain { tv.wakeTotalMs = 2500 }
        eventually("shows Turn on", tv, ms = 8000) { tv.tvOff }
        assertEquals("no help before trying", 0, onMain { tv.turnOnFailures })
        onMain { tv.turnOn() }
        eventually("help after the first failure", tv, ms = 8000) { tv.turnOnFailures == 1 && !tv.waking }
        onMain { tv.turnOn() }
        eventually("more help after the second", tv, ms = 8000) { tv.turnOnFailures == 2 && !tv.waking }
        sim.wakeWorks = true
        onMain { tv.turnOn() }
        eventually("turns on, help gone", tv, ms = 10_000) { tv.connected && !tv.tvOff && tv.turnOnFailures == 0 }
    }

    @Test fun phoneNotOnWifi_saysSo_noWakeAttempt_noTvHelp() {
        sim.power = "Off"; sim.quickStart = false
        val tv = openApp()
        eventually("shows Turn on", tv, ms = 8000) { tv.tvOff }
        sim.phoneWifi = null
        onMain { tv.turnOn() }
        eventually("explains the phone isn't on Wi-Fi", tv) { tv.feedback?.startsWith("This phone isn't on Wi-Fi") == true }
        assertFalse("no wake loop", onMain { tv.waking })
        assertEquals("not the TV's fault: no turn-on help", 0, onMain { tv.turnOnFailures })
        assertTrue(onMain { tv.diagnostics() }.contains("WAKE phone not on Wi-Fi"))
    }

    @Test fun phoneOnAnotherNetwork_saysSo() {
        sim.power = "Off"; sim.quickStart = false
        val tv = openApp()
        eventually("shows Turn on", tv, ms = 8000) { tv.tvOff }
        sim.phoneWifi = "10.0.0.7" to 24   // e.g. a guest network
        onMain { tv.turnOn() }
        eventually("explains the different network", tv) { tv.feedback?.startsWith("This phone is on a different network") == true }
        assertEquals(0, onMain { tv.turnOnFailures })
    }

    @Test fun turnedOnWithTheTvRemoteWhileHelpShows_helpGoes() {
        sim.power = "Active Standby"
        val tv = openApp()
        eventually("shows Turn on", tv, ms = 12_000) { tv.tvOff }
        onMain { tv.turnOnFailures = 1 }
        sim.turnedOnByRemote(); onMain { tv.refresh() }
        eventually("TV on, help reset", tv, ms = 10_000) { !tv.tvOff && tv.turnOnFailures == 0 }
    }

    // ---------------- using a different TV ----------------

    @Test fun differentTv_backReturnsToTheCurrentTv() {
        // 22 Nov: "Use a different TV" forgot the TV at once and setup had no way back.
        val (tv, app) = openAppWithPrefs()
        eventually("connects", tv) { tv.connected }
        onMain { tv.findAnotherTv() }
        assertEquals(Screen.Setup, onMain { tv.screen })
        assertTrue("can go back", onMain { tv.canCancelSetup })
        assertEquals("nothing forgotten yet", "SIMKEY", app.getSharedPreferences("tv", 0).getString("clientKey", null))
        onMain { tv.cancelSetup() }
        eventually("back on the remote, connected to the same TV", tv) { tv.screen == Screen.Remote && tv.connected }
        assertEquals("192.168.1.195", app.getSharedPreferences("tv", 0).getString("ip", null))
        assertTrue("MAC kept", app.getSharedPreferences("tv", 0).getString("mac", null).orEmpty().contains("f4:ca:e7:87:7e:1e", ignoreCase = true))
    }

    @Test fun differentTv_oncePairedTheOldOneIsDropped() {
        val (tv, app) = openAppWithPrefs()
        eventually("connects", tv) { tv.connected }
        onMain { tv.findAnotherTv(); tv.choose("192.168.1.196", "Bedroom TV", "LG") }
        eventually("the new TV pairs", tv) { tv.connected && app.getSharedPreferences("tv", 0).getString("clientKey", null) == "SIMKEY" }
        eventually("no going back once paired, and on the remote", tv) { !tv.canCancelSetup && tv.screen == Screen.Remote }
        assertEquals("192.168.1.196", app.getSharedPreferences("tv", 0).getString("ip", null))
        assertTrue(app.getSharedPreferences("tv", 0).all.keys.none { it.startsWith("prev_") })
    }

    // ---------------- opening shows ----------------

    @Test fun netflixLinkIsSentAsTheTvExpects() {
        val tv = openApp()
        eventually("connects", tv) { tv.connected }
        onMain { tv.openTitle("netflix", "Netflix", DeepLinks.netflix("80025678", "A")) }
        eventually("launches Netflix with the title", tv) {
            sim.sent("system.launcher/launch").any { it.optJSONObject("payload")?.optString("contentId")?.contains("80025678") == true }
        }
        assertEquals("netflix", sim.foreground)
    }

    @Test fun primeAndDisneyLinksAreSentAsTheTvExpects() {
        val tv = openApp()
        eventually("connects", tv) { tv.connected }
        val gti = "amzn1.dv.gti.6aa9f790-371c-d77c-e4a2-2539f0bbcc92"
        onMain { tv.openTitle("amazon", "Prime Video", DeepLinks.prime(gti, "P1")) }
        eventually("launches Prime with the GTI as contentId", tv) {
            sim.sent("system.launcher/launch").any { it.optJSONObject("payload")?.let { p -> p.optString("id") == "amazon" && p.optString("contentId") == gti } == true }
        }
        onMain { tv.openTitle("com.disney.disneyplus-prod", "Disney+", DeepLinks.disney("entity-04c97b72-504b-47f2-9c6f-fe13d9aea82f", "D1")) }
        eventually("launches Disney+ with the title page", tv) {
            sim.sent("system.launcher/launch").any { it.optJSONObject("payload")?.optJSONObject("params")?.optString("contentTarget") == "https://www.disneyplus.com/browse/entity-04c97b72-504b-47f2-9c6f-fe13d9aea82f" }
        }
        assertEquals("com.disney.disneyplus-prod", sim.foreground)
    }

    @Test fun titleIsTypedWhenTheSearchBoxIsSelected() {
        val tv = openApp()
        eventually("connects and listens to the keyboard", tv) { tv.connected && sim.uris().any { it.endsWith("registerRemoteKeyboard") } }
        onMain { tv.openApp("amazon", "Prime Video"); tv.typeWhenSearchOpens("The Boys") }
        Thread.sleep(300)
        assertTrue("nothing typed before the box is selected", sim.sent("insertText").isEmpty())
        sim.keyboardOpens()
        eventually("types the title and presses Enter", tv) {
            sim.sent("ime/insertText").any { it.optJSONObject("payload")?.optString("text") == "The Boys" } && sim.sent("sendEnterKey").isNotEmpty()
        }
    }

    // ---------------- while watching ----------------

    @Test fun homeScreenBannerVideo_isNotWatching() {
        // 22 Nov, 1.2.1: the remote jumped to the "watching" view on the LG Home screen ("Home · playing").
        val tv = openApp()
        eventually("connects", tv) { tv.connected && sim.uris().any { it.endsWith("com.webos.media/getForegroundAppInfo") } }
        sim.homeScreenVideosPlay()
        eventually("sees the Home screen", tv) { tv.foregroundApp == "com.webos.app.home" }
        stays("stays on the normal remote", tv, 1500) { !tv.inPlayback }
        sim.startsPlaying("netflix")
        eventually("a real show playing still counts", tv) { tv.inPlayback }
    }

    @Test fun tapPausesButAPointedTapClicks_andSkipButtonsSendArrows() {
        val tv = openApp()
        eventually("connects", tv) { tv.connected && sim.uris().any { it.endsWith("com.webos.media/getForegroundAppInfo") } }
        sim.startsPlaying("netflix")
        eventually("knows something is playing", tv) { tv.inPlayback }
        onMain { tv.tap() }
        eventually("a plain tap pauses", tv) { sim.sent("media.controls/pause").isNotEmpty() }
        onMain { tv.move(10f, 4f); tv.tap() }
        eventually("a tap after pointing clicks where the pointer is (e.g. the timeline)", tv) { sim.pointer.any { it == "type:click" } }
        assertFalse("not OK, which Netflix treats as play/pause", sim.pointer.any { it.contains("name:ENTER") })
        onMain { tv.seek(30) }
        eventually("skip 30 s = three right arrows", tv) { sim.pointer.count { it == "type:button name:RIGHT" } == 3 }
    }
}
