package com.home.tvremote

import android.app.Application
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.home.tvremote.lg.FoundTv
import com.home.tvremote.ui.Palette
import com.home.tvremote.ui.PairingScreen
import com.home.tvremote.ui.RemoteScreen
import com.home.tvremote.ui.ListeningPanel
import com.home.tvremote.ui.TimerSheet
import com.home.tvremote.timer.TimerEngine
import com.home.tvremote.timer.TimerKind
import com.home.tvremote.timer.TimerState
import com.home.tvremote.ui.SetupScreen
import com.home.tvremote.voice.TvApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Renders the real screens on the JVM so the layout can be checked without a phone. */
class ScreenshotTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6, showSystemUi = false)

    private class FakeApp(base: Context, val prefs: FakePrefs = FakePrefs()) : Application() {
        init { attachBaseContext(base) }
        override fun getSharedPreferences(name: String?, mode: Int) = prefs
        override fun getApplicationContext(): Context = this
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Before fun main() { Dispatchers.setMain(UnconfinedTestDispatcher()) }

    private val registryOwner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {}
        }
    }

    private fun controller() = TvController(FakeApp(paparazzi.context)).apply { scanning = true }

    private fun shot(content: @Composable () -> Unit) = paparazzi.snapshot {
        MaterialTheme(colorScheme = darkColorScheme(primary = Palette.accent, background = Palette.bg)) {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registryOwner) {
                Box(Modifier.fillMaxSize().background(Palette.bg)) { content() }
            }
        }
    }

    private fun remoteTv() = controller().apply {
        screen = Screen.Remote; tvName = "Living Room"; connected = true; scanning = false
        foregroundApp = "netflix"; usageConsent = false   // answered; the usage* shots show the question
    }

    @Test fun remoteDark() { Palette.dark = true; val tv = remoteTv(); shot { RemoteScreen(tv) { true } } }
    @Test fun remoteNotItLight() { Palette.dark = false; val tv = remoteTv().apply {
        picks.lastOpened = com.home.tvremote.picks.PicksController.Opened("tv:1", "The Crown", "Netflix", "netflix", "80025678", listOf("search", "format:C", "type")) }
        shot { RemoteScreen(tv) { true } } }
    @Test fun watchingLight() { Palette.dark = false; val tv = remoteTv().apply { foregroundApp = "netflix" }; shot { RemoteScreen(tv) { true } } }
    @Test fun browsingLight() { Palette.dark = false; val tv = remoteTv().apply { foregroundApp = "com.webos.app.home" }; shot { RemoteScreen(tv) { true } } }
    @Test fun playingLight() { Palette.dark = false; val tv = remoteTv().apply { foregroundApp = "netflix"; mediaState = "playing"; picks.nowShowing = "Dumplin'" to "9:42"; picks.nowShowingApp = "netflix" }; shot { RemoteScreen(tv) { true } } }
    @Test fun settingsLight() { Palette.dark = false; val tv = remoteTv().apply { showSettings = true }; shot { RemoteScreen(tv) { true } } }
    @Test fun helpLight() { Palette.dark = false; val tv = remoteTv().apply { showSettings = true; showHelp = true }; shot { RemoteScreen(tv) { true } } }
    @Test fun feedbackWithFixLight() { Palette.dark = false; val tv = remoteTv().apply { say("Barbie is on Max — not one of your services", true, "Add" to {}) }; shot { RemoteScreen(tv) { true } } }
    // ---- usage sharing (asked once) ----
    @Test fun usageCardLight() { Palette.dark = false; val tv = remoteTv().apply { foregroundApp = "com.webos.app.home"; usageConsent = null; forceUsageUi = true }; shot { RemoteScreen(tv) { true } } }
    @Test fun usageCardDark() { Palette.dark = true; val tv = remoteTv().apply { foregroundApp = "com.webos.app.home"; usageConsent = null; forceUsageUi = true }; shot { RemoteScreen(tv) { true } } }
    @Test fun ptUsageCardLight() = pt { Palette.dark = false; val tv = ptTv().apply { foregroundApp = "com.webos.app.home"; usageConsent = null; forceUsageUi = true }; shot { RemoteScreen(tv) { true } } }

    // ---- Portuguese (Brazil) ----
    private fun pt(block: () -> Unit) { com.home.tvremote.i18n.I18n.lang = "pt"; com.home.tvremote.i18n.I18n.country = "BR"; try { block() } finally { com.home.tvremote.i18n.I18n.lang = "en"; com.home.tvremote.i18n.I18n.country = "US" } }
    /** A controller set to Portuguese and Brazil from Settings, as the user would. */
    private fun ptTv() = remoteTv().also { it.changeAppLanguage("pt"); it.changeCountry("BR") }
    @Test fun ptBrowsingLight() = pt { Palette.dark = false; val tv = ptTv().apply { foregroundApp = "com.webos.app.home" }; shot { RemoteScreen(tv) { true } } }
    @Test fun ptPlayingLight() = pt { Palette.dark = false; val tv = ptTv().apply { foregroundApp = "netflix"; mediaState = "playing"; picks.nowShowing = "Round 6" to "21:42"; picks.nowShowingApp = "netflix" }; shot { RemoteScreen(tv) { true } } }
    @Test fun ptSettingsLight() = pt { Palette.dark = false; val tv = ptTv().apply { showSettings = true }; shot { RemoteScreen(tv) { true } } }
    @Test fun ptFeedbackLight() = pt { Palette.dark = false; val tv = ptTv().apply { say("Opening Back to the Future on Netflix") }; shot { RemoteScreen(tv) { true } } }
    @Test fun ptOffDark() = pt { Palette.dark = true; val tv = ptTv().apply { connected = false; tvOff = true }; shot { RemoteScreen(tv) { true } } }
    @Test fun ptTimerSheetLight() = pt { Palette.dark = false; shot { TimerSheet({ _, _ -> }, {}) } }
    @Test fun ptPicksChooseLight() = pt { Palette.dark = false; val pc = picks(com.home.tvremote.picks.Step.Choose); shot { com.home.tvremote.ui.PicksScreen(pc) } }
    @Test fun ptPicksResultsLight() = pt { Palette.dark = false; val pc = picks(com.home.tvremote.picks.Step.Results); shot { com.home.tvremote.ui.PicksScreen(pc) } }
    @Test fun ptPicksSetupLight() = pt { Palette.dark = false; val pc = picks(com.home.tvremote.picks.Step.Setup); shot { com.home.tvremote.ui.PicksScreen(pc) } }

    @Test fun remoteLight() { Palette.dark = false; val tv = remoteTv(); shot { RemoteScreen(tv) { true } } }
    @Test fun liveTvDark() { Palette.dark = true; val tv = remoteTv().apply { foregroundApp = "com.webos.app.livetv" }; shot { RemoteScreen(tv) { true } } }
    @Test fun liveTvLight() { Palette.dark = false; val tv = remoteTv().apply { foregroundApp = "com.webos.app.livetv" }; shot { RemoteScreen(tv) { true } } }
    @Test fun typingOnTv() { Palette.dark = true; val tv = remoteTv().apply { tvKeyboardOpen = true; foregroundApp = "youtube.leanback.v4" }; shot { RemoteScreen(tv) { true } } }
    @Test fun offDark() { Palette.dark = true; val tv = remoteTv().apply { connected = false; tvOff = true }; shot { RemoteScreen(tv) { true } } }
    /** Regression: v0.3.1 crashed on launch whenever a TV was already saved. */
    @Test fun startsWithSavedTvWithoutCrashing() {
        Palette.dark = true
        val saved = FakePrefs(mutableMapOf("ip" to "192.0.2.1", "clientKey" to "k", "name" to "Living Room"))
        val tv = TvController(FakeApp(paparazzi.context, saved))   // runs init → refresh()
        shot { RemoteScreen(tv) { true } }
    }

    /** The launcher icon as phones mask it: round, rounded-square, and themed (tinted, background dropped). */
    @Test fun launcherIcon() = paparazzi.snapshot {
        androidx.compose.foundation.layout.Row(
            Modifier.background(androidx.compose.ui.graphics.Color(0xFFE4E2DE)).padding(24.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(24.dp)) {
            for (shape in listOf(androidx.compose.foundation.shape.CircleShape, androidx.compose.foundation.shape.RoundedCornerShape(34))) {
                Box(Modifier.size(120.dp).clip(shape)) {
                    androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.ic_launcher_background), null, Modifier.fillMaxSize())
                    androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.ic_launcher_foreground), null, Modifier.fillMaxSize())
                }
            }
            Box(Modifier.size(120.dp).clip(androidx.compose.foundation.shape.CircleShape).background(androidx.compose.ui.graphics.Color(0xFFDDE3D3))) {
                androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.ic_launcher_foreground), null, Modifier.fillMaxSize(),
                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(androidx.compose.ui.graphics.Color(0xFF3B4630)))
            }
        }
    }

    @Test fun timerSheetDark() { Palette.dark = true; shot { TimerSheet({ _, _ -> }, {}) } }
    @Test fun timerSheetLight() { Palette.dark = false; shot { TimerSheet({ _, _ -> }, {}) } }
    @Test fun timerRunningDark() {
        Palette.dark = true
        TimerEngine.state = TimerState(TimerKind.TurnOff, System.currentTimeMillis() + 23 * 60_000L + 14_000L, 30 * 60_000L)
        try { val tv = remoteTv(); shot { RemoteScreen(tv) { true } } } finally { TimerEngine.state = null }
    }

    private class NoTv : com.home.tvremote.picks.TvActions {
        override fun openApp(appId: String, title: String) {}
        override fun knownAppIds() = emptySet<String>()
        override fun openOnTvBrowser(url: String) {}
        override fun toastOnTv(message: String) {}
        override fun goHome() {}
        override val foreground: String? = null
        override fun phoneIp() = null
        override val tvIp: String? = null
        override fun note(message: String, error: Boolean) {}
    }

    private fun picks(step: com.home.tvremote.picks.Step) = com.home.tvremote.picks.PicksController(
        com.home.tvremote.picks.PicksStore(FakePrefs()).apply {
            people = listOf("Alex", "Sam"); services = listOf("Netflix", "Prime Video", "Hulu", "Apple TV+", "hoichoi", "ZEE5")
            languages = listOf("en", "bn", "hi"); tmdbKey = "k"
        },
        NoTv(), kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.test.StandardTestDispatcher()),
        clock = { java.util.Calendar.getInstance().apply { set(2026, java.util.Calendar.NOVEMBER, 17, 21, 30) } },
        onDevice = null,
    ).apply {
        openPicker()
        if (step == com.home.tvremote.picks.Step.Results) {
            summary = "Light & funny · Together"
            picks += listOf(
                com.home.tvremote.picks.Pick(com.home.tvremote.picks.Title(com.home.tvremote.picks.Kind.Tv, 1, "Ted Lasso", language = "en", rating = 8.4, runtimeMin = 30, service = "Apple TV+"), "Warm and very funny. Fans of Schitt's Creek also loved it."),
                com.home.tvremote.picks.Pick(com.home.tvremote.picks.Title(com.home.tvremote.picks.Kind.Tv, 2, "Panchayat", language = "hi", rating = 8.9, runtimeMin = 35, service = "Prime Video"), "Gentle village comedy — short episodes, very easy to watch."),
                com.home.tvremote.picks.Pick(com.home.tvremote.picks.Title(com.home.tvremote.picks.Kind.Movie, 3, "The Grand Budapest Hotel", language = "en", rating = 8.0, runtimeMin = 100, service = "Hulu"), "Playful and quick, if you have a bit longer."),
                com.home.tvremote.picks.Pick(com.home.tvremote.picks.Title(com.home.tvremote.picks.Kind.Tv, 4, "The Studio", language = "en", rating = 8.0, runtimeMin = 30, service = "Apple TV+", fresh = true), "Hollywood satire; fans of Curb love it."),
                com.home.tvremote.picks.Pick(com.home.tvremote.picks.Title(com.home.tvremote.picks.Kind.Movie, 5, "Palm Springs", language = "en", rating = 7.4, runtimeMin = 90, service = "Hulu"), "Clever time-loop comedy, light but smart."),
            )
        }
        this.step = step
    }

    @Test fun picksChooseDark() { Palette.dark = true; val pc = picks(com.home.tvremote.picks.Step.Choose); shot { com.home.tvremote.ui.PicksScreen(pc) } }
    @Test fun picksFavouritesCardLight() { Palette.dark = false; val pc = picks(com.home.tvremote.picks.Step.Choose).apply { who = "Sam" }; shot { com.home.tvremote.ui.PicksScreen(pc) } }
    @Test fun picksChooseLight() { Palette.dark = false; val pc = picks(com.home.tvremote.picks.Step.Choose); shot { com.home.tvremote.ui.PicksScreen(pc) } }
    @Test fun picksResultsDark() { Palette.dark = true; val pc = picks(com.home.tvremote.picks.Step.Results); shot { com.home.tvremote.ui.PicksScreen(pc) } }
    @Test fun picksOnTvTouchpadLight() { Palette.dark = false; val pc = picks(com.home.tvremote.picks.Step.Results).apply { showOnTv = true; tvPageOn = true; pointingAt = "Seinfeld · Play" }; shot { com.home.tvremote.ui.PicksScreen(pc) } }
    @Test fun picksOnTvTouchpadDark() { Palette.dark = true; val pc = picks(com.home.tvremote.picks.Step.Choose).apply { showOnTv = true; tvPageOn = true; pointingAt = "Light & funny" }; shot { com.home.tvremote.ui.PicksScreen(pc) } }
    @Test fun picksResultsLight() { Palette.dark = false; val pc = picks(com.home.tvremote.picks.Step.Results); shot { com.home.tvremote.ui.PicksScreen(pc) } }
    @Test fun picksSetupDark() { Palette.dark = true; val pc = picks(com.home.tvremote.picks.Step.Setup); shot { com.home.tvremote.ui.PicksScreen(pc) } }

    @Test fun offWakingDark() { Palette.dark = true; val tv = remoteTv().apply { connected = false; tvOff = true; waking = true; wakeSeconds = 8 }; shot { RemoteScreen(tv) { true } } }
    @Test fun picksAiSectionLight() {
        Palette.dark = false
        val pc = picks(com.home.tvremote.picks.Step.Setup).apply { onDeviceStatus = "Downloading to this phone (Android does this in the background)" }
        shot { androidx.compose.foundation.layout.Box(Modifier.padding(20.dp)) { com.home.tvremote.ui.PicksAiSection(pc, "abc", {}, "", {}, "groq") {} } }
    }

    @Test fun checkingDark() { Palette.dark = true; val tv = remoteTv().apply { connected = false; checking = true }; shot { RemoteScreen(tv) { true } } }
    @Test fun listeningDark() { Palette.dark = true; shot { ListeningPanel("Search YouTube for easy weeknight pasta recipes", 0.7f, {}, {}) } }
    @Test fun listeningLight() { Palette.dark = false; shot { ListeningPanel("Search YouTube for easy weeknight pasta recipes", 0.7f, {}, {}) } }

    @Test fun setup() {
        val tv = controller().apply { scanning = false; found.add(FoundTv("192.168.1.42", "[LG] webOS TV OLED55C1", "OLED55C1PUB")) }
        shot { SetupScreen(tv) }
    }

    @Test fun pairing() { shot { PairingScreen(controller()) } }
}
