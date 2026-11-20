package com.home.tvremote

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import com.home.tvremote.ui.Palette
import com.home.tvremote.ui.PairingScreen
import com.home.tvremote.ui.RemoteScreen
import com.home.tvremote.ui.SetupScreen

class MainActivity : ComponentActivity() {
    private val tv: TvController by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleShortcut(intent)
        setContent {
            Palette.dark = isSystemInDarkTheme()
            val scheme = if (Palette.dark) darkColorScheme(primary = Palette.text, background = Palette.bg, surface = Palette.surface)
                else lightColorScheme(primary = Palette.text, background = Palette.bg, surface = Palette.surface)
            MaterialTheme(colorScheme = scheme) {
                Box(Modifier.fillMaxSize().background(Palette.bg).safeDrawingPadding().imePadding()) {
                    when (tv.screen) {
                        Screen.Setup -> SetupScreen(tv)
                        Screen.Pairing -> PairingScreen(tv)
                        Screen.Remote -> RemoteScreen(tv, hasMicPermission = {
                            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                        })
                    }
                }
            }
        }
    }

    /** The app-icon shortcut "What should we watch?" opens the picker directly. */
    private fun handleShortcut(intent: Intent?) {
        if (intent?.action == ACTION_PICKS && tv.screen == Screen.Remote) tv.picks.openPicker()
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleShortcut(intent) }

    override fun onResume() {
        super.onResume()
        if (tv.screen == Screen.Remote && !tv.connected) tv.refresh()
    }

    // The phone's own volume buttons control the TV's volume.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (tv.screen == Screen.Remote && (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            if (event.action == KeyEvent.ACTION_DOWN) tv.volume(event.keyCode == KeyEvent.KEYCODE_VOLUME_UP)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    companion object { const val ACTION_PICKS = "com.home.tvremote.PICKS" }
}
