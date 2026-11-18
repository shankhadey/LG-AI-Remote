package com.home.tvremote.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.home.tvremote.TvController
import com.home.tvremote.voice.TvApp
import com.home.tvremote.voice.VoiceInput

/** Colours for both themes. [dark] follows the phone's setting (set in MainActivity). */
object Palette {
    var dark by mutableStateOf(true)
    val bg get() = if (dark) Color(0xFF0C0C0E) else Color(0xFFF6F6F8)
    val surface get() = if (dark) Color(0xFF18181B) else Color(0xFFFFFFFF)
    val surface2 get() = if (dark) Color(0xFF222226) else Color(0xFFEEEEF2)
    val line get() = if (dark) Color(0x0DFFFFFF) else Color(0x0F000000)
    val text get() = if (dark) Color(0xFFF2F2F4) else Color(0xFF111114)
    val muted get() = if (dark) Color(0xFF8E8E96) else Color(0xFF6E6E76)
    val faint get() = if (dark) Color(0xFF6A6A72) else Color(0xFF8E8E96)
    val dots get() = if (dark) Color(0x0DFFFFFF) else Color(0x12000000)
    val primary get() = if (dark) Color(0xFFF2F2F4) else Color(0xFF111114)
    val onPrimary get() = if (dark) Color(0xFF0C0C0E) else Color(0xFFFFFFFF)
    val accent = Color(0xFF4F8CFF)
    val danger get() = if (dark) Color(0xFFFF6B6F) else Color(0xFFD7263D)
    val ok = Color(0xFF34C46E)
}

/** A raised surface: flat in dark mode, a soft shadow in light mode. */
fun Modifier.card(shape: Shape): Modifier =
    (if (Palette.dark) this else this.shadow(5.dp, shape, ambientColor = Color(0x22000000), spotColor = Color(0x22000000)))
        .clip(shape).background(Palette.surface)

// ============================================================ Remote

@Composable
fun RemoteScreen(tv: TvController, hasMicPermission: () -> Boolean) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var listening by remember { mutableStateOf(false) }
    var heard by remember { mutableStateOf("") }
    var level by remember { mutableFloatStateOf(0f) }

    val voice = remember {
        VoiceInput(context,
            onPartial = { heard = it },
            onResult = { guesses -> heard = tv.handleSpoken(guesses) },
            onError = { tv.say(it, error = true) },
            onEnd = { listening = false },
            onLevel = { level = it })
    }
    DisposableEffect(Unit) { onDispose { voice.release() } }
    fun listen() {
        if (!voice.available) { tv.say("No speech recognizer on this phone (install the Google app)", error = true); return }
        heard = ""; level = 0f; listening = true; voice.hints = tv.voiceHints(); voice.start()
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) listen() else tv.say("Voice needs microphone permission", error = true)
    }

    if (tv.tvOff) { OffScreen(tv); return }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Header(tv)
        Text("MOST USED", color = Palette.faint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,
            modifier = Modifier.padding(top = 18.dp, bottom = 10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            for (app in tv.mostUsed()) AppTile(app) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); tv.launch(app) }
        }
        if (tv.tvKeyboardOpen) KeyboardBar(tv)
        Box(Modifier.fillMaxWidth().weight(1f).padding(top = 18.dp)) {
            Touchpad(onMove = tv::move, onClick = tv::click, onScroll = tv::scroll, modifier = Modifier.fillMaxSize())
            Feedback(tv, Modifier.align(Alignment.TopCenter).padding(top = 12.dp))
        }
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            RoundButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back") { tv.button("BACK") }
            RoundButton(Icons.Rounded.Home, "Home") { tv.button("HOME") }
            RoundButton(Icons.Rounded.PlayArrow, "Play / pause") { tv.togglePlayPause() }
            RoundButton(Icons.AutoMirrored.Rounded.VolumeOff, "Mute") { tv.toggleMute() }
        }
        Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Rocker(if (tv.isLiveTv) "VOL" else "VOLUME", Icons.Rounded.Remove, Icons.Rounded.Add,
                { tv.volume(false) }, { tv.volume(true) }, Modifier.weight(1f))
            MicButton {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                if (hasMicPermission()) listen() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
            // Channel buttons only make sense on Live TV (antenna/cable); streaming apps never show them.
            if (tv.isLiveTv) Rocker("CH", Icons.Rounded.KeyboardArrowDown, Icons.Rounded.KeyboardArrowUp,
                { tv.ssap("ssap://tv/channelDown") }, { tv.ssap("ssap://tv/channelUp") }, Modifier.weight(1f))
        }
    }
    if (listening) ListeningPanel(heard, level, onDone = { voice.finish() }, onCancel = { voice.stop(); listening = false })
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Header(tv: TvController) {
    var menu by remember { mutableStateOf(false) }
    var macDialog by remember { mutableStateOf(false) }
    var diag by remember { mutableStateOf(false) }
    var tapDialog by remember { mutableStateOf(false) }
    if (macDialog) MacDialog(tv) { macDialog = false }
    if (diag) DiagnosticsDialog(tv) { diag = false }
    if (tapDialog) TapDialog(tv) { tapDialog = false }

    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            Column(Modifier.clip(RoundedCornerShape(10.dp)).clickable { menu = true }.padding(vertical = 4.dp, horizontal = 2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tv.tvName, color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(Icons.Rounded.KeyboardArrowDown, "Settings", tint = Palette.faint, modifier = Modifier.size(18.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(
                        when { tv.connected -> Palette.ok; tv.connecting || tv.waking -> Palette.faint; else -> Palette.danger }))
                    Spacer(Modifier.width(6.dp))
                    Text(tv.nowShowing(), color = Palette.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Reconnect") }, onClick = { menu = false; tv.connect() })
                DropdownMenuItem(text = { Text(if (tv.macKnown) "Power-on settings" else "Set up power on") }, onClick = { menu = false; macDialog = true })
                DropdownMenuItem(text = { Text("Fix touchpad tap") }, onClick = { menu = false; tapDialog = true })
                DropdownMenuItem(text = { Text("Diagnostics") }, onClick = { menu = false; diag = true })
                DropdownMenuItem(text = { Text("Choose a different TV") }, onClick = { menu = false; tv.forget() })
            }
        }
        Box(Modifier.size(42.dp).card(CircleShape)
            .combinedClickable(onClick = { tv.power() }, onLongClick = { tv.forcePowerOn() }, onLongClickLabel = "Turn on"),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.PowerSettingsNew, "Power", tint = Palette.muted, modifier = Modifier.size(20.dp))
        }
    }
}

/** When the TV is off, the whole screen is one button. */
@Composable
private fun OffScreen(tv: TvController) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Header(tv)
        Column(Modifier.fillMaxWidth().weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(Modifier.size(176.dp).card(CircleShape).clickable(enabled = !tv.waking) { tv.turnOn() }, contentAlignment = Alignment.Center) {
                if (tv.waking) CircularProgressIndicator(color = Palette.text, strokeWidth = 2.dp, modifier = Modifier.size(56.dp))
                else Icon(Icons.Rounded.PowerSettingsNew, "Turn on", tint = Palette.text, modifier = Modifier.size(60.dp))
            }
            Spacer(Modifier.height(24.dp))
            Text(if (tv.waking) "Turning on…" else "Turn on", color = Palette.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(16.dp))
            Feedback(tv, Modifier.padding(horizontal = 8.dp))
        }
    }
}

/** A brand-coloured tile for well-known apps, a neutral initial for the rest. */
@Composable
private fun AppTile(app: TvApp, onClick: () -> Unit) {
    val (bg, fg, glyph) = tileStyle(app.id, app.title)
    Column(Modifier.width(72.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp).then(if (bg == null) Modifier.card(RoundedCornerShape(18.dp)) else
            Modifier.clip(RoundedCornerShape(18.dp)).background(bg)), contentAlignment = Alignment.Center) {
            if (glyph == "▶") {
                Box(Modifier.size(width = 34.dp, height = 24.dp).clip(RoundedCornerShape(7.dp)).background(Color(0xFFE62117)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(20.dp))
                }
            } else Text(glyph, color = fg ?: Palette.text, fontSize = if (glyph.length > 1) 20.sp else 24.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(app.title, color = Palette.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
    }
}

private fun tileStyle(id: String, title: String): Triple<Color?, Color?, String> = when {
    id == "netflix" -> Triple(Color(0xFFB1060F), Color.White, "N")
    id.startsWith("youtube") -> Triple(null, null, "▶")
    id == "amazon" -> Triple(Color(0xFF0F2A4A), Color.White, "P")
    id.startsWith("com.disney") -> Triple(Color(0xFF0E1B4D), Color.White, "D+")
    id == "hulu" -> Triple(Color(0xFF1CE783), Color(0xFF0B0C0F), "h")
    id == "com.apple.appletv" -> Triple(Color(0xFF111114), Color.White, "tv")
    id.startsWith("spotify") -> Triple(Color(0xFF1DB954), Color(0xFF0B0C0F), "S")
    id == "com.webos.app.livetv" -> Triple(Color(0xFF2A2A2F), Color.White, "TV")
    else -> Triple(null, null, title.take(1).uppercase())
}

/** Appears only while a text box is focused on the TV: typing goes straight onto the TV. */
@Composable
private fun KeyboardBar(tv: TvController) {
    var text by remember { mutableStateOf("") }
    val focus = LocalFocusManager.current
    Row(Modifier.fillMaxWidth().padding(top = 16.dp).card(RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Keyboard, null, tint = Palette.accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (text.isEmpty()) Text("Type on the TV…", color = Palette.faint, fontSize = 15.sp)
            BasicTextField(text, { text = it; tv.typeOnTv(it) }, singleLine = true,
                textStyle = TextStyle(color = Palette.text, fontSize = 15.sp), cursorBrush = SolidColor(Palette.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, keyboardType = KeyboardType.Text),
                keyboardActions = KeyboardActions(onDone = { tv.enterOnTv(); text = ""; focus.clearFocus() }),
                modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun Feedback(tv: TvController, modifier: Modifier = Modifier) {
    AnimatedVisibility(tv.feedback != null, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        Text(tv.feedback ?: "", color = if (tv.feedbackIsError) Palette.danger else Palette.text, fontSize = 13.sp,
            textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.card(RoundedCornerShape(14.dp)).background(Palette.surface2).padding(horizontal = 14.dp, vertical = 8.dp))
    }
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    Box(Modifier.size(64.dp).card(CircleShape).clickable { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() },
        contentAlignment = Alignment.Center) { Icon(icon, label, tint = Palette.text, modifier = Modifier.size(26.dp)) }
}

@Composable
private fun Rocker(label: String, downIcon: ImageVector, upIcon: ImageVector, onDown: () -> Unit, onUp: () -> Unit, modifier: Modifier) {
    val haptic = LocalHapticFeedback.current
    Row(modifier.height(58.dp).card(RoundedCornerShape(29.dp)), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(58.dp).clickable { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onDown() }, contentAlignment = Alignment.Center) {
            Icon(downIcon, "$label down", tint = Palette.text, modifier = Modifier.size(22.dp))
        }
        Text(label, color = Palette.faint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
        Box(Modifier.weight(1f).height(58.dp).clickable { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onUp() }, contentAlignment = Alignment.Center) {
            Icon(upIcon, "$label up", tint = Palette.text, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun MicButton(onClick: () -> Unit) {
    Box(Modifier.size(72.dp).clip(CircleShape).background(Palette.primary).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.Mic, "Talk", tint = Palette.onPrimary, modifier = Modifier.size(30.dp))
    }
}

/** Tap-to-talk: words appear as you speak; sends when you pause or tap Done; tap elsewhere to cancel. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ListeningPanel(heard: String, level: Float, onDone: () -> Unit, onCancel: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Palette.bg)
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onCancel)
        .padding(horizontal = 28.dp, vertical = 24.dp)) {
        Text("Listening", color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
        Text("Stops when you pause · tap anywhere to cancel", color = Palette.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.height(96.dp))
        Text(heard.ifEmpty { "Say something…" }, color = if (heard.isEmpty()) Palette.faint else Palette.text,
            fontSize = 30.sp, lineHeight = 37.sp, fontWeight = FontWeight.SemiBold)
        Row(Modifier.padding(top = 26.dp).height(40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            val shape = listOf(0.3f, 0.6f, 1f, 0.5f, 0.8f, 0.35f, 0.7f, 0.25f)
            for (s in shape) {
                val h by animateFloatAsState(6f + 34f * s * (0.25f + 0.75f * level), tween(120), label = "bar")
                Box(Modifier.width(5.dp).height(h.dp).clip(RoundedCornerShape(3.dp)).background(Palette.text))
            }
        }
        Text("Long requests are fine — take your time.", color = Palette.muted, fontSize = 14.sp, modifier = Modifier.padding(top = 16.dp))
        Spacer(Modifier.weight(1f))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (c in listOf("“Open Netflix”", "“Volume 20”", "“Switch to HDMI 2”", "“Pause”")) {
                Text(c, color = Palette.muted, fontSize = 13.sp, modifier = Modifier.card(RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 8.dp))
            }
        }
        Box(Modifier.padding(top = 20.dp).fillMaxWidth().height(58.dp).clip(RoundedCornerShape(29.dp)).background(Palette.primary).clickable(onClick = onDone),
            contentAlignment = Alignment.Center) {
            Text("Done", color = Palette.onPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ============================================================ Dialogs (opened from the TV name)

/** Lets the user find which tap method their TV responds to, by trying each on a real tile. */
@Composable
private fun TapDialog(tv: TvController, onClose: () -> Unit) {
    val options = listOf(
        "ok" to "A · Remote's OK button (default)",
        "click" to "B · Pointer click",
        "enter" to "C · Keyboard Enter",
    )
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Make taps select") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("1. Close this, drag the cursor onto an app tile on the TV, then reopen it (tap the TV name › Fix touchpad tap).", fontSize = 14.sp)
                Text("2. Tap A, B and C in turn. Stop at the one that opens the tile — it's saved as what a tap does.", fontSize = 14.sp)
                for ((id, label) in options) {
                    val selected = tv.tapMethod == id
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(if (selected) Palette.accent else Palette.surface2)
                            .clickable { tv.setTap(id); tv.sendTap(id); tv.say("Sent $label — did the tile open?") }
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(label, color = if (selected) Color.White else Palette.text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        if (selected) Text("in use", color = Color.White, fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Done") } },
        containerColor = Palette.surface, titleContentColor = Palette.text, textContentColor = Palette.muted,
    )
}

@Composable
private fun DiagnosticsDialog(tv: TvController, onClose: () -> Unit) {
    val text = remember { tv.diagnostics() }
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Diagnostics") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text("What the app asked the TV, and what it answered (newest first). Copy and send this to whoever is helping you.", fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                Text(text, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Palette.text)
            }
        },
        confirmButton = { TextButton(onClick = { clipboard.setText(AnnotatedString(text)); tv.say("Copied") }) { Text("Copy") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Close") } },
        containerColor = Palette.surface, titleContentColor = Palette.text, textContentColor = Palette.muted,
    )
}

@Composable
private fun MacDialog(tv: TvController, onClose: () -> Unit) {
    var mac by remember { mutableStateOf(tv.currentMac()) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Turning the TV on") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("1. On the TV, turn on Settings › General › Devices › External Devices › TV On With Mobile › Turn on via Wi-Fi.", fontSize = 14.sp)
                Text("If it still won't wake: TVs on Wi-Fi can switch Wi-Fi fully off in standby, and some routers and mesh systems don't pass the wake-up signal. An Ethernet cable to the TV is the most reliable fix.", fontSize = 14.sp)
                Text("2. The app wakes the TV using its MAC addresses. A TV on Wi-Fi needs its Wi-Fi address; on a cable, its wired one. Both are filled in automatically when the TV allows it — or enter them yourself, separated by a comma. They're on the TV under Settings › General › About This TV (or Network › Wi-Fi › Advanced).", fontSize = 14.sp)
                BasicTextField(mac, { mac = it }, singleLine = false, maxLines = 3,
                    textStyle = TextStyle(color = Palette.text, fontSize = 16.sp), cursorBrush = SolidColor(Palette.accent),
                    decorationBox = { inner -> Box { if (mac.isEmpty()) Text("Wi-Fi MAC, wired MAC", color = Palette.muted); inner() } },
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.surface2).padding(12.dp))
            }
        },
        confirmButton = { TextButton(onClick = { tv.setMac(mac); onClose() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
        containerColor = Palette.surface, titleContentColor = Palette.text, textContentColor = Palette.muted,
    )
}

// ============================================================ Setup & pairing

@Composable
fun SetupScreen(tv: TvController) {
    var manualIp by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { if (tv.found.isEmpty()) tv.scan() }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Find your LG TV", color = Palette.text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("Turn the TV on and make sure this phone is on the same Wi-Fi.", color = Palette.muted, fontSize = 15.sp)
        PrimaryButton(if (tv.scanning) "Searching your Wi-Fi…" else "Search again", enabled = !tv.scanning) { tv.scan() }
        if (tv.scanning) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Palette.text) }
        for (f in tv.found) {
            Row(Modifier.fillMaxWidth().card(RoundedCornerShape(16.dp)).clickable { tv.choose(f.ip, f.name) }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Tv, null, tint = Palette.text)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(f.name, color = Palette.text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text(listOf(f.model, f.ip).filter { it.isNotEmpty() }.joinToString(" · "), color = Palette.muted, fontSize = 13.sp)
                }
            }
        }
        tv.scanReport?.let { if (!tv.scanning && tv.found.isEmpty()) Text(it, color = Palette.muted, fontSize = 14.sp) }
        Spacer(Modifier.height(8.dp))
        Text("Or enter its IP address (TV Settings › Network › Wi-Fi › Advanced)", color = Palette.muted, fontSize = 13.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(manualIp, { manualIp = it.filter { c -> c.isDigit() || c == '.' } }, singleLine = true,
                textStyle = TextStyle(color = Palette.text, fontSize = 16.sp), cursorBrush = SolidColor(Palette.accent),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                decorationBox = { inner -> Box { if (manualIp.isEmpty()) Text("192.168.1.50", color = Palette.faint); inner() } },
                modifier = Modifier.weight(1f).card(RoundedCornerShape(14.dp)).padding(14.dp))
            Spacer(Modifier.width(10.dp))
            Box(Modifier.card(RoundedCornerShape(14.dp)).clickable(enabled = manualIp.count { it == '.' } == 3) { tv.choose(manualIp, "LG TV") }.padding(14.dp)) {
                Text("Connect", color = Palette.text, fontWeight = FontWeight.SemiBold)
            }
        }
        tv.feedback?.let { Text(it, color = if (tv.feedbackIsError) Palette.danger else Palette.muted, fontSize = 14.sp) }
    }
}

@Composable
fun PairingScreen(tv: TvController) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.Tv, null, tint = Palette.text, modifier = Modifier.size(96.dp))
        Spacer(Modifier.height(24.dp))
        Text("Look at your TV", color = Palette.text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text("A message is asking to allow \"TV Remote\". Select Accept with the LG remote — you only do this once.",
            color = Palette.muted, fontSize = 16.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(28.dp))
        CircularProgressIndicator(color = Palette.text)
    }
}

@Composable
private fun PrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (enabled) Palette.primary else Palette.surface2)
        .clickable(enabled = enabled, onClick = onClick).padding(16.dp), contentAlignment = Alignment.Center) {
        Text(text, color = if (enabled) Palette.onPrimary else Palette.muted, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    }
}
