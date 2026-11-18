package com.home.tvremote.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.home.tvremote.TvController
import com.home.tvremote.voice.VoiceInput

object Palette {
    val bg = Color(0xFF0F1115)
    val surface = Color(0xFF1A1D24)
    val surface2 = Color(0xFF242833)
    val line = Color(0xFF2E3340)
    val text = Color(0xFFEEF0F4)
    val muted = Color(0xFF8B92A3)
    val accent = Color(0xFF4F8CFF)
    val danger = Color(0xFFFF5A5F)
    val ok = Color(0xFF3CCF7A)
}

// ============================================================ Remote

@Composable
fun RemoteScreen(tv: TvController, hasMicPermission: () -> Boolean) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var listening by remember { mutableStateOf(false) }
    var heard by remember { mutableStateOf("") }

    val voice = remember {
        VoiceInput(context,
            onPartial = { heard = it },
            onResult = { guesses -> heard = tv.handleSpoken(guesses) },
            onError = { tv.say(it, error = true) },
            onEnd = { listening = false })
    }
    DisposableEffect(Unit) { onDispose { voice.release() } }
    fun listen() {
        if (!voice.available) { tv.say("No speech recognizer on this phone (install the Google app)", error = true); return }
        heard = ""; listening = true; voice.hints = tv.voiceHints(); voice.start()
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) listen() else tv.say("Voice needs microphone permission", error = true)
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Header(tv)
        AskBar(tv)
        Feedback(tv)
        if (tv.apps.isNotEmpty()) AppStrip(tv)
        Touchpad(
            onMove = tv::move, onClick = tv::click, onScroll = tv::scroll,
            modifier = Modifier.fillMaxWidth().weight(1f).padding(vertical = 12.dp),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            RoundButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back") { tv.button("BACK") }
            RoundButton(Icons.Rounded.Home, "Home") { tv.button("HOME") }
            RoundButton(Icons.Rounded.PlayArrow, "Play / pause") { tv.togglePlayPause() }
            RoundButton(Icons.AutoMirrored.Rounded.VolumeOff, "Mute") { tv.toggleMute() }
        }
        Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Rocker("VOL", Icons.Rounded.Remove, Icons.Rounded.Add, { tv.volume(false) }, { tv.volume(true) }, Modifier.weight(1f))
            Spacer(Modifier.width(16.dp))
            MicButton(listening) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                when {
                    listening -> { voice.stop(); listening = false }
                    hasMicPermission() -> listen()
                    else -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
            Spacer(Modifier.width(16.dp))
            Rocker("CH", Icons.Rounded.KeyboardArrowDown, Icons.Rounded.KeyboardArrowUp,
                { tv.ssap("ssap://tv/channelDown") }, { tv.ssap("ssap://tv/channelUp") }, Modifier.weight(1f))
        }
    }
    if (listening) ListeningOverlay(heard) { voice.stop(); listening = false }
}

@Composable
private fun Header(tv: TvController) {
    var menu by remember { mutableStateOf(false) }
    var macDialog by remember { mutableStateOf(false) }
    if (macDialog) MacDialog(tv) { macDialog = false }
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(if (tv.connected) Palette.ok else if (tv.connecting) Palette.muted else Palette.danger))
        Spacer(Modifier.width(10.dp))
        Text(tv.tvName, color = Palette.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).clickable { if (!tv.connected) tv.connect() })
        Box(Modifier.size(44.dp).clip(CircleShape).background(Palette.surface).clickable { tv.power() }, contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.PowerSettingsNew, "Power", tint = Palette.danger)
        }
        Box {
            Box(Modifier.size(44.dp).clip(CircleShape).clickable { menu = true }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.MoreVert, "More", tint = Palette.muted)
            }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Reconnect") }, onClick = { menu = false; tv.connect() })
                DropdownMenuItem(text = { Text(if (tv.macKnown) "Power-on settings" else "Set up power on") }, onClick = { menu = false; macDialog = true })
                DropdownMenuItem(text = { Text("Choose a different TV") }, onClick = { menu = false; tv.forget() })
            }
        }
    }
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

/** One box for everything: commands ("open netflix"), searches, and live typing into the TV. */
@Composable
private fun AskBar(tv: TvController) {
    var text by remember { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val live = tv.tvKeyboardOpen
    LaunchedEffect(live) { if (!live) text = "" }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.surface)
            .border(1.dp, if (live) Palette.accent else Palette.line, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (live) Icons.Rounded.Keyboard else Icons.Rounded.Search, null, tint = if (live) Palette.accent else Palette.muted, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (text.isEmpty()) Text(
                if (live) "Typing straight onto the TV…" else "Ask or search: \"open Netflix\", \"the office\"",
                color = Palette.muted, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            BasicTextField(
                value = text,
                onValueChange = { text = it; if (live) tv.typeOnTv(it) },
                singleLine = true,
                textStyle = TextStyle(color = Palette.text, fontSize = 15.sp),
                cursorBrush = SolidColor(Palette.accent),
                keyboardOptions = KeyboardOptions(imeAction = if (live) ImeAction.Done else ImeAction.Search, keyboardType = KeyboardType.Text),
                keyboardActions = KeyboardActions(
                    onSearch = { if (tv.handle(text)) text = ""; focus.clearFocus() },
                    onDone = { tv.enterOnTv(); text = ""; focus.clearFocus() },
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun Feedback(tv: TvController) {
    Box(Modifier.fillMaxWidth().height(34.dp), contentAlignment = Alignment.Center) {
        AnimatedVisibility(tv.feedback != null, enter = fadeIn(), exit = fadeOut()) {
            Text(tv.feedback ?: "", color = if (tv.feedbackIsError) Palette.danger else Palette.text, fontSize = 14.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Palette.surface2).padding(horizontal = 14.dp, vertical = 5.dp))
        }
    }
}

@Composable
private fun AppStrip(tv: TvController) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
        items(tv.apps, key = { it.id }) { app ->
            Column(Modifier.width(64.dp).clickable { tv.launch(app) }, horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(Palette.surface2), contentAlignment = Alignment.Center) {
                    if (app.icon != null) AsyncImage(model = app.icon, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    else Text(app.title.take(1), color = Palette.muted, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                }
                Text(app.title, color = Palette.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    Box(
        Modifier.size(64.dp).clip(CircleShape).background(Palette.surface)
            .clickable { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = Palette.text, modifier = Modifier.size(28.dp)) }
}

@Composable
private fun Rocker(label: String, downIcon: ImageVector, upIcon: ImageVector, onDown: () -> Unit, onUp: () -> Unit, modifier: Modifier) {
    val haptic = LocalHapticFeedback.current
    Row(modifier.height(56.dp).clip(RoundedCornerShape(28.dp)).background(Palette.surface), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(56.dp).clickable { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onDown() }, contentAlignment = Alignment.Center) {
            Icon(downIcon, "$label down", tint = Palette.text)
        }
        Text(label, color = Palette.muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Box(Modifier.weight(1f).height(56.dp).clickable { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onUp() }, contentAlignment = Alignment.Center) {
            Icon(upIcon, "$label up", tint = Palette.text)
        }
    }
}

@Composable
private fun MicButton(listening: Boolean, onClick: () -> Unit) {
    val pulse by rememberInfiniteTransition(label = "mic").animateFloat(1f, 1.12f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "pulse")
    Box(
        Modifier.size(76.dp).scale(if (listening) pulse else 1f).clip(CircleShape)
            .background(if (listening) Palette.danger else Palette.accent).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Rounded.Mic, "Talk", tint = Color.White, modifier = Modifier.size(34.dp)) }
}

@Composable
private fun ListeningOverlay(heard: String, onCancel: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xCC0F1115)).clickable(onClick = onCancel), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Text(heard.ifEmpty { "Listening…" }, color = Palette.text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            Text("Try \"open YouTube\", \"volume 20\", \"find cooking videos\", \"pause\"", color = Palette.muted, fontSize = 14.sp, textAlign = TextAlign.Center)
        }
    }
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
        if (tv.scanning) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Palette.accent) }
        for (f in tv.found) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.surface).clickable { tv.choose(f.ip, f.name) }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Tv, null, tint = Palette.accent)
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
                decorationBox = { inner -> Box { if (manualIp.isEmpty()) Text("192.168.1.50", color = Palette.muted); inner() } },
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(Palette.surface).padding(14.dp))
            Spacer(Modifier.width(10.dp))
            Box(Modifier.clip(RoundedCornerShape(14.dp)).background(Palette.surface2).clickable(enabled = manualIp.count { it == '.' } == 3) { tv.choose(manualIp, "LG TV") }.padding(14.dp)) {
                Text("Connect", color = Palette.text, fontWeight = FontWeight.SemiBold)
            }
        }
        tv.feedback?.let { Text(it, color = if (tv.feedbackIsError) Palette.danger else Palette.muted, fontSize = 14.sp) }
    }
}

@Composable
fun PairingScreen(tv: TvController) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.Tv, null, tint = Palette.accent, modifier = Modifier.size(96.dp))
        Spacer(Modifier.height(24.dp))
        Text("Look at your TV", color = Palette.text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text("A message is asking to allow \"TV Remote\". Select Accept with the LG remote — you only do this once.",
            color = Palette.muted, fontSize = 16.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(28.dp))
        CircularProgressIndicator(color = Palette.accent)
    }
}

@Composable
private fun PrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (enabled) Palette.accent else Palette.surface2)
        .clickable(enabled = enabled, onClick = onClick).padding(16.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    }
}
