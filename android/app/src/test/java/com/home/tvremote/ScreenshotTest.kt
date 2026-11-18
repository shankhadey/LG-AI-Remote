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
        foregroundApp = "netflix"
    }

    @Test fun remoteDark() { Palette.dark = true; val tv = remoteTv(); shot { RemoteScreen(tv) { true } } }
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

    @Test fun checkingDark() { Palette.dark = true; val tv = remoteTv().apply { connected = false; checking = true }; shot { RemoteScreen(tv) { true } } }
    @Test fun listeningDark() { Palette.dark = true; shot { ListeningPanel("Search YouTube for easy weeknight pasta recipes", 0.7f, {}, {}) } }
    @Test fun listeningLight() { Palette.dark = false; shot { ListeningPanel("Search YouTube for easy weeknight pasta recipes", 0.7f, {}, {}) } }

    @Test fun setup() {
        val tv = controller().apply { scanning = false; found.add(FoundTv("192.168.1.42", "[LG] webOS TV OLED55C1", "OLED55C1PUB")) }
        shot { SetupScreen(tv) }
    }

    @Test fun pairing() { shot { PairingScreen(controller()) } }
}
