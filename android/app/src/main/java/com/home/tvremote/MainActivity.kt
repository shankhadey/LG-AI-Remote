package com.home.tvremote

import android.Manifest
import android.content.pm.PackageManager
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
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Palette.accent, background = Palette.bg, surface = Palette.surface)) {
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

    override fun onResume() {
        super.onResume()
        if (tv.screen == Screen.Remote && !tv.connected) tv.connect()
    }

    // The phone's own volume buttons control the TV's volume.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (tv.screen == Screen.Remote && (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            if (event.action == KeyEvent.ACTION_DOWN) tv.volume(event.keyCode == KeyEvent.KEYCODE_VOLUME_UP)
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
