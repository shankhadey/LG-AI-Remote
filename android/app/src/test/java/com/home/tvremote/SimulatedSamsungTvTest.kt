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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.Executors

/** The whole app controller against a simulated Samsung TV, end to end and in real time (see [SimulatedSamsungTv]). */
@OptIn(ExperimentalCoroutinesApi::class)
class SimulatedSamsungTvTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6)   // only for an Android Context

    private class App(base: Context) : Application() {
        private val prefs = HashMap<String, FakePrefs>()
        init { attachBaseContext(base) }
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs.getOrPut(name ?: "") { FakePrefs() }
        override fun getApplicationContext(): Context = this
    }

    private lateinit var sim: SimulatedSamsungTv
    private val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    @Before fun setUp() { Dispatchers.setMain(main); sim = SimulatedSamsungTv().start(); TvController.net = sim.net }
    private val controllers = mutableListOf<TvController>()
    @After fun tearDown() {
        // Stop this test's controller (its connection and polling) so nothing of it runs into the next test.
        onMain { controllers.forEach { c -> TvController::class.java.getDeclaredMethod("onCleared").apply { isAccessible = true }.invoke(c) } }
        TvController.net = TvController.RealNet; sim.stop(); Dispatchers.resetMain(); main.close()
    }

    private fun openApp(paired: Boolean = true): Pair<TvController, App> {
        val app = App(paparazzi.context)
        // Paired before: brand, address, MAC and token saved. First time: nothing saved (the setup screen).
        if (paired) app.getSharedPreferences("tv", 0).edit().putString("brand", "Samsung").putString("ip", "192.168.1.60")
            .putString("mac", "70:2a:d5:11:22:33").putBoolean("nameCustom", true).putString("clientKey", sim.token).apply()
        return onMain { TvController(app).also { it.appVisible = true; controllers += it } } to app
    }
    private fun <T> onMain(block: () -> T): T = kotlinx.coroutines.runBlocking(main) { block() }
    private fun eventually(what: String, tv: TvController? = null, ms: Long = 6000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (onMain { cond() }) return; Thread.sleep(50) }
        throw AssertionError("$what (waited $ms ms)\n" + (tv?.let { onMain { it.diagnostics() } }?.take(3000) ?: ""))
    }
    private fun stays(what: String, tv: TvController, ms: Long, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (!onMain { cond() }) throw AssertionError("$what\n" + onMain { tv.diagnostics() }.take(3000)); Thread.sleep(100) }
    }

    // ---------------- pairing and power ----------------

    @Test fun firstTime_pairsWhenAllowedOnTheTv_andKeepsTheToken() {
        val (tv, app) = openApp(paired = false)
        onMain { tv.choose("192.168.1.60", "Samsung Q80", "Samsung") }
        eventually("pairs after Allow and shows the remote", tv, ms = 8000) { tv.connected && !tv.tvOff }
        assertEquals(sim.token, app.getSharedPreferences("tv", 0).getString("clientKey", null))
        assertEquals("Samsung", app.getSharedPreferences("tv", 0).getString("brand", null))
    }

    @Test fun tvOn_connectsAndListsItsApps_underTheSharedNames() {
        val (tv, _) = openApp()
        eventually("connects", tv) { tv.connected && !tv.tvOff }
        eventually("installed apps arrive, with Netflix as \"netflix\"", tv) { tv.apps.any { it.id == "netflix" && it.title == "Netflix" } }
        assertTrue("connected with the saved token", sim.connectUrls.last().contains("token=${sim.token}"))
    }

    @Test fun standbyWhenAppOpens_showsOff_thenTurnOnWorksAndStays() {
        sim.power = "standby"
        val (tv, _) = openApp()
        eventually("shows Turn on for a TV in standby", tv, ms = 12_000) { tv.tvOff && !tv.connected }
        onMain { tv.turnOn() }
        eventually("turns on and shows the remote", tv, ms = 10_000) { tv.connected && !tv.tvOff && !tv.waking }
        stays("must not flip back to Turn on", tv, 5000) { tv.connected && !tv.tvOff }
    }

    @Test fun turnOffFromTheApp_sendsPower_andShowsOff() {
        val (tv, _) = openApp()
        eventually("connects", tv) { tv.connected }
        onMain { tv.power() }
        eventually("KEY_POWER sent and Turn on shown", tv) { "KEY_POWER" in sim.keys() && tv.tvOff }
        stays("stays off", tv, 2000) { tv.tvOff }
    }

    @Test fun tvTurnedOffAndOnWithItsOwnRemote_appFollows() {
        val (tv, _) = openApp()
        eventually("connects", tv) { tv.connected && !tv.tvOff }
        sim.turnedOffByRemote()
        eventually("notices the TV went off", tv, ms = 8000) { tv.tvOff }
        sim.turnedOnByRemote()
        onMain { tv.refresh() }
        eventually("notices the TV came back on", tv, ms = 10_000) { !tv.tvOff && tv.connected }
    }

    @Test fun droppedConnection_reconnectsByItself() {
        val (tv, _) = openApp()
        eventually("connects", tv) { tv.connected }
        sim.dropsConnections()
        eventually("notices the drop", tv) { !tv.connected }
        eventually("reconnects without the user doing anything", tv, ms = 10_000) { tv.connected }
    }

    // ---------------- opening shows ----------------

    @Test fun netflixLink_isSentAsADeepLinkToTheTizenApp_andTheAppInFrontIsFollowed() {
        val (tv, _) = openApp()
        eventually("connects", tv) { tv.connected }
        onMain { tv.openTitle("netflix", "Netflix", DeepLinks.netflix("80025678", "A")) }
        eventually("ed.apps.launch to Netflix's Tizen ID with the title", tv) {
            sim.launches().any { it.optString("appId") == "3201907018807" && it.optString("action_type") == "DEEP_LINK" && it.optString("metaTag").contains("80025678") }
        }
        eventually("the app sees Netflix in front (as \"netflix\")", tv) { tv.foregroundApp == "netflix" }
    }

    @Test fun titleIsTypedWhenTheTvKeyboardOpens() {
        val (tv, _) = openApp()
        eventually("connects", tv) { tv.connected }
        onMain { tv.openApp("amazon", "Prime Video"); tv.typeWhenSearchOpens("The Boys") }
        eventually("Prime opened by its Tizen ID", tv) { sim.launches().any { it.optString("appId") == "3201910019365" } }
        Thread.sleep(300)
        assertTrue("nothing typed before the box is selected", sim.typed().isEmpty())
        sim.keyboardOpens()
        eventually("types the title and confirms", tv) { sim.typed() == listOf("The Boys") && sim.messages.any { it.optJSONObject("params")?.optString("TypeOfRemote") == "SendInputEnd" } }
    }

    // ---------------- remote ----------------

    @Test fun touchpad_noPointerOnSamsung_dragsBecomeArrows_tapIsOk() {
        val (tv, _) = openApp()
        eventually("connects", tv) { tv.connected }
        onMain { repeat(4) { tv.move(40f, 2f) } }      // 160 px right → two RIGHT presses
        onMain { tv.move(0f, -70f) }                     // up
        onMain { tv.tap() }
        eventually("arrows then OK", tv) { sim.keys().takeLast(4) == listOf("KEY_RIGHT", "KEY_RIGHT", "KEY_UP", "KEY_ENTER") }
        onMain { tv.button("BACK"); tv.button("HOME") }
        eventually("back and home keys", tv) { sim.keys().takeLast(2) == listOf("KEY_RETURN", "KEY_HOME") }
    }
}
