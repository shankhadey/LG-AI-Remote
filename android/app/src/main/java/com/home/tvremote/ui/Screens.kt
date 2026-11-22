package com.home.tvremote.ui

import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.launch
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.runtime.mutableLongStateOf
import com.home.tvremote.timer.TimerEngine
import com.home.tvremote.timer.TimerKind
import com.home.tvremote.timer.TimerState
import kotlinx.coroutines.delay
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
    val success = Color(0xFF30B95B)
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
    var showTimers by remember { mutableStateOf(false) }
    // Android 13+: the timer's countdown notification needs permission (the timer runs either way).
    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun startTimer(kind: TimerKind, minutes: Int) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        tv.startTimer(kind, minutes)
        showTimers = false
    }
    LaunchedEffect(TimerEngine.notice) { TimerEngine.notice?.let { tv.say(it, error = true); TimerEngine.notice = null } }

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

    fun mic() {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        if (hasMicPermission()) listen() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }
    if (tv.showSettings) { SettingsScreen(tv); return }
    if (tv.tvOff && tv.turnOnFailures > 0 && !tv.waking) {
        TurnOnHelpScreen(tv.brand, tv.turnOnFailures, onTryAgain = { tv.turnOn() }, onClose = { tv.closeTurnOnHelp() })
        return
    }
    if (tv.tvOff) {
        OffScreen(tv, onMic = ::mic)
        if (listening) ListeningPanel(heard, level, onDone = { voice.finish() }, onCancel = { voice.stop(); listening = false })
        return
    }
    if (!tv.connected && (tv.checking || tv.connecting)) { CheckingScreen(tv); return }

    // "Watching" = the TV says something is playing or paused (browsing an app's menus keeps the browsing layout).
    val watching = tv.mediaState != null
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Header(tv)
        if (watching) NowShowing(tv)
        else {
            PicksLine(tv.picks)
            if (tv.askUsage) UsageCard(tv)
            // Apps as icons only: the label is the brand.
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                for (app in tv.mostUsed()) AppTile(app) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); tv.launch(app) }
            }
        }
        NotItBar(tv.picks)
        if (tv.tvKeyboardOpen) KeyboardBar(tv)
        TimerEngine.state?.let { TimerPill(it) }
        Box(Modifier.fillMaxWidth().weight(1f).padding(top = 16.dp)) {
            Touchpad(onMove = tv::move, onClick = tv::tap, onScroll = tv::scroll, modifier = Modifier.fillMaxSize(),
                hint = if (watching) "Tap to pause · double-tap a side to skip" else if (tv.inPlayback) "Tap to pause/play · drag to point" else "Drag to point · tap to select",
                onSkip = if (watching) { forward -> tv.skip(forward) } else null)
            Feedback(tv, Modifier.align(Alignment.TopCenter).padding(top = 12.dp))
        }
        // Quiet glyphs rather than big buttons: the touchpad and the mic are the heroes.
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            Glyph(Icons.AutoMirrored.Rounded.ArrowBack, "Back") { tv.button("BACK") }
            Glyph(Icons.Rounded.Home, "Home") { tv.button("HOME") }
            Glyph(Icons.Rounded.Timer, "Timer") { showTimers = true }
            Glyph(Icons.AutoMirrored.Rounded.VolumeOff, "Mute") { tv.toggleMute() }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Rocker(if (tv.isLiveTv) "VOL" else "VOLUME", Icons.Rounded.Remove, Icons.Rounded.Add,
                { tv.volume(false) }, { tv.volume(true) }, Modifier.weight(1f))
            MicButton { mic() }
            // Channel buttons only make sense on Live TV (antenna/cable); streaming apps never show them.
            if (tv.isLiveTv) Rocker("CH", Icons.Rounded.KeyboardArrowDown, Icons.Rounded.KeyboardArrowUp,
                { tv.channel(false) }, { tv.channel(true) }, Modifier.weight(1f))
        }
    }
    if (tv.picks.open) { PicksScreen(tv.picks); return }
    if (showTimers) TimerSheet(onPick = ::startTimer, onClose = { showTimers = false })
    if (listening) ListeningPanel(heard, level, onDone = { voice.finish() }, onCancel = { voice.stop(); listening = false })
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Header(tv: TvController, showPower: Boolean = true) {
    var menu by remember { mutableStateOf(false) }
    var macDialog by remember { mutableStateOf(false) }
    var diag by remember { mutableStateOf(false) }
    var tapDialog by remember { mutableStateOf(false) }
    var linkDialog by remember { mutableStateOf(false) }
    var renameDialog by remember { mutableStateOf(false) }
    if (renameDialog) RenameDialog(tv) { renameDialog = false }
    if (macDialog) MacDialog(tv) { macDialog = false }
    if (diag) DiagnosticsDialog(tv) { diag = false }
    if (tapDialog) TapDialog(tv) { tapDialog = false }
    if (linkDialog) LinkDialog(tv) { linkDialog = false }

    Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            Column(Modifier.clip(RoundedCornerShape(10.dp)).clickable { tv.showSettings = true }.padding(vertical = 4.dp, horizontal = 2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RawText(tv.tvName, color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(Icons.Rounded.KeyboardArrowDown, "Settings", tint = Palette.faint, modifier = Modifier.size(18.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(
                        when { tv.connected -> Palette.ok; tv.tvOff || tv.checking || tv.connecting || tv.waking -> Palette.faint; else -> Palette.danger }))
                    Spacer(Modifier.width(6.dp))
                    Text(tv.nowShowing(), color = Palette.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (showPower) Box(Modifier.size(42.dp).card(CircleShape)
            .combinedClickable(onClick = { tv.power() }, onLongClick = { tv.forcePowerOn() }, onLongClickLabel = "Turn on"),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.PowerSettingsNew, "Power", tint = Palette.muted, modifier = Modifier.size(20.dp))
        }
    }
}

/** The first moment after opening: calm and quiet until we know whether the TV is on. */
@Composable
private fun CheckingScreen(tv: TvController) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Header(tv, showPower = false)
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Palette.faint, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
        }
    }
}

/** When the TV is off, the whole screen is one button. */
@Composable
private fun OffScreen(tv: TvController, onMic: () -> Unit = {}) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Header(tv, showPower = false)
        Column(Modifier.fillMaxWidth().weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(Modifier.size(184.dp), contentAlignment = Alignment.Center) {
                // While waking: a thin ring around the same button, so the screen doesn't change shape.
                if (tv.waking) CircularProgressIndicator(color = Palette.text, strokeWidth = 2.dp, modifier = Modifier.fillMaxSize())
                Box(Modifier.size(168.dp).card(CircleShape).clickable(enabled = !tv.waking) { tv.turnOn() }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.PowerSettingsNew, "Turn on", tint = if (tv.waking) Palette.muted else Palette.text, modifier = Modifier.size(60.dp))
                }
            }
            Spacer(Modifier.height(24.dp))
            Text(if (tv.waking) "Turning on…" else "Turn on", color = Palette.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(
                when {
                    !tv.waking -> " "
                    tv.wakeSeconds < 6 -> "Waking the TV"
                    else -> "Still waking the TV · ${tv.wakeSeconds} s"
                },
                color = Palette.muted, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp),
            )
            if (!tv.waking) Text("or just say “turn on the TV”", color = Palette.faint, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
            Spacer(Modifier.height(16.dp))
            // Only problems show here; progress is the ring and the line above.
            if (tv.feedbackIsError) Feedback(tv, Modifier.padding(horizontal = 8.dp))
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), horizontalArrangement = Arrangement.End) { MicButton(onMic) }
    }
}

/** A brand-coloured tile for well-known apps, a neutral initial for the rest. */
@Composable
private fun AppTile(app: TvApp, onClick: () -> Unit) {
    val (bg, fg, glyph) = tileStyle(app.id, app.title)
    Column(Modifier.clip(RoundedCornerShape(18.dp)).semantics { contentDescription = app.title }.clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(60.dp).then(if (bg == null) Modifier.card(RoundedCornerShape(18.dp)) else
            Modifier.clip(RoundedCornerShape(18.dp)).background(bg)), contentAlignment = Alignment.Center) {
            if (glyph == "▶") {
                Box(Modifier.size(width = 34.dp, height = 24.dp).clip(RoundedCornerShape(7.dp)).background(Color(0xFFE62117)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(20.dp))
                }
            } else Text(glyph, color = fg ?: Palette.text, fontSize = if (glyph.length > 1) 20.sp else 24.sp, fontWeight = FontWeight.SemiBold)
        }
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
/** After a show is opened: "Not it?" walks through the fallbacks (search page, other formats, type it). */
@Composable
private fun NotItBar(pc: com.home.tvremote.picks.PicksController) {
    val o = pc.lastOpened ?: return
    val label = pc.fixLabel() ?: return
    Row(Modifier.fillMaxWidth().padding(top = 12.dp).card(RoundedCornerShape(16.dp)).padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Opened ${o.name}", color = Palette.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(label, color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { pc.tryNextFix() }.padding(vertical = 4.dp))
        }
        Box(Modifier.size(36.dp).clip(CircleShape).clickable { pc.dismissOpened() }, contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Close, "Dismiss", tint = Palette.faint, modifier = Modifier.size(18.dp))
        }
    }
}

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
        Row(Modifier.card(RoundedCornerShape(14.dp)).background(Palette.surface2).padding(start = 14.dp, end = if (tv.feedbackAction != null) 6.dp else 14.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(tv.feedback ?: "", color = if (tv.feedbackIsError) Palette.danger else Palette.text, fontSize = 13.sp,
                textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            tv.feedbackAction?.let { (label, act) ->
                Text(label, color = Palette.text, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(10.dp)).clickable { tv.feedback = null; act() }.padding(horizontal = 8.dp, vertical = 4.dp))
            }
        }
    }
}

// ============================================================ Settings

/** One place for settings (replaces the menu under the TV name). Engineering tools live under Help. */
@Composable
private fun SettingsScreen(tv: TvController) {
    BackHandler { if (tv.showHelp) tv.showHelp = false else tv.showSettings = false }
    var renameDialog by remember { mutableStateOf(false) }
    var macDialog by remember { mutableStateOf(false) }
    var diag by remember { mutableStateOf(false) }
    var tapDialog by remember { mutableStateOf(false) }
    var linkDialog by remember { mutableStateOf(false) }
    var langDialog by remember { mutableStateOf(false) }
    var usageDialog by remember { mutableStateOf(false) }
    var confirmNewTv by remember { mutableStateOf(false) }
    var about by remember { mutableStateOf(false) }
    if (about) AlertDialog(
        onDismissRequest = { about = false },
        title = { RawText("TV Remote ${com.home.tvremote.BuildConfig.VERSION_NAME}") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("This product uses the TMDB API but is not endorsed or certified by TMDB.", fontSize = 14.sp)
            Text("Where-to-watch data by JustWatch.", fontSize = 14.sp)
            Text("Not affiliated with LG, Samsung or any streaming service.", fontSize = 14.sp)
        } },
        confirmButton = { TextButton(onClick = { about = false }) { Text("Close") } },
        containerColor = Palette.surface, titleContentColor = Palette.text, textContentColor = Palette.muted,
    )
    if (confirmNewTv) AlertDialog(
        onDismissRequest = { confirmNewTv = false },
        title = { Text("Use a different TV?") },
        text = { Text("The remote looks for TVs on your Wi-Fi. ${tv.tvName} stays set up until the new TV is connected, and Back returns to it.") },
        confirmButton = { TextButton(onClick = { confirmNewTv = false; tv.showSettings = false; tv.showHelp = false; tv.findAnotherTv() }) { Text("Find TVs") } },
        dismissButton = { TextButton(onClick = { confirmNewTv = false }) { Text("Cancel") } },
        containerColor = Palette.surface, titleContentColor = Palette.text, textContentColor = Palette.muted,
    )
    if (usageDialog) ChoiceDialog("Share anonymous usage", listOf("on" to "Share", "off" to "Don't share"),
        when (tv.usageConsent) { true -> "on"; false -> "off"; null -> "" }, USAGE_NOTE) { it?.let { v -> tv.changeUsageConsent(v == "on") }; usageDialog = false }
    var countryDialog by remember { mutableStateOf(false) }
    if (langDialog) ChoiceDialog("App language", com.home.tvremote.i18n.Regions.LANGUAGES, tv.appLanguage, "Follows the phone's language.") { it?.let(tv::changeAppLanguage); langDialog = false }
    if (countryDialog) ChoiceDialog("Country", com.home.tvremote.i18n.Regions.ALL.map { it.code to it.name }, tv.country,
        "Where you watch: sets the streaming services, where-to-watch info and the clock.") { it?.let(tv::changeCountry); countryDialog = false }
    if (renameDialog) RenameDialog(tv) { renameDialog = false }
    if (macDialog) MacDialog(tv) { macDialog = false }
    if (diag) DiagnosticsDialog(tv) { diag = false }
    if (tapDialog) TapDialog(tv) { tapDialog = false }
    if (linkDialog) LinkDialog(tv) { linkDialog = false }
    val s = tv.picks.store
    Column(Modifier.fillMaxSize().background(Palette.bg).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
        Box(Modifier.size(40.dp).clip(CircleShape).clickable { if (tv.showHelp) tv.showHelp = false else tv.showSettings = false }, contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Palette.text)
        }
        if (!tv.showHelp) {
            Text("Settings", color = Palette.text, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
            Section("This TV")
            SettingRow("Name", tv.tvName, raw = true) { renameDialog = true }
            SettingRow("Turn on from the phone", if (tv.macKnown) "Ready" else "Set up") { macDialog = true }
            SettingRow("After turning on", if (tv.homeOnWake) "Home screen" else "Last input") { tv.changeHomeOnWake(!tv.homeOnWake) }
            Section("Picks")
            SettingRow("People", s.people.joinToString().ifEmpty { "Add" }) { tv.openPicksSettings() }
            SettingRow("Favourites", s.people.joinToString(" · ") { "$it ${s.favourites(it).size}" }.ifEmpty { "—" }, raw = true) { tv.openPicksSettings() }
            SettingRow("Services", "${s.services.size}") { tv.openPicksSettings() }
            SettingRow("Bedtimes", "${hhmm(s.weekdayBedtime)} · Fri–Sat ${hhmm(s.weekendBedtime)}") { tv.openPicksSettings() }
            Section("Language & country")
            SettingRow("Language", com.home.tvremote.i18n.Regions.LANGUAGES.first { it.first == tv.appLanguage }.second) { langDialog = true }
            SettingRow("Country", com.home.tvremote.i18n.Regions.of(tv.country).name) { countryDialog = true }
            if (tv.usageAvailable) Section("Privacy")
            if (tv.usageAvailable) SettingRow("Share anonymous usage", when (tv.usageConsent) { true -> "Yes"; false -> "No"; null -> "Not set" }) { usageDialog = true }
            Section("More")
            SettingRow("Help & diagnostics", "") { tv.showHelp = true }
            SettingRow("Advanced", "keys, AI") { tv.openPicksSettings() }
            SettingRow("About", com.home.tvremote.BuildConfig.VERSION_NAME, raw = true) { about = true }
        } else {
            Text("Help & diagnostics", color = Palette.text, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
            Section("If something isn't right")
            SettingRow("Taps don't select on the TV", "Fix touchpad tap") { tapDialog = true }
            SettingRow("Shows open on the app's home", "Test opening a show") { linkDialog = true }
            SettingRow("The remote lost the TV", "Reconnect") { tv.connect(); tv.showSettings = false; tv.showHelp = false }
            SettingRow("Something went wrong", "Share diagnostics") { diag = true }
            Section("This phone")
            SettingRow("Use a different TV", "") { confirmNewTv = true }
        }
    }
}

private fun hhmm(min: Int) = (if (com.home.tvremote.i18n.Regions.of(com.home.tvremote.i18n.I18n.country).clock24) "%02d:%02d" else "%d:%02d").format((min / 60) % 24, min % 60)

@Composable
private fun Section(text: String) = RawText(tr(text).uppercase(), color = Palette.faint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
    letterSpacing = 1.sp, modifier = Modifier.padding(top = 22.dp, bottom = 4.dp))

@Composable
private fun SettingRow(title: String, value: String, raw: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 14.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Palette.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
        if (value.isNotEmpty()) (if (raw) RawText(value, color = Palette.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End,
            modifier = Modifier.padding(start = 12.dp).widthIn(max = 170.dp)) else Text(value, color = Palette.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End,
            modifier = Modifier.padding(start = 12.dp).widthIn(max = 170.dp)))
        Icon(Icons.Rounded.ChevronRight, null, tint = Palette.faint, modifier = Modifier.size(18.dp))
    }
}

/** A quiet, borderless icon button (Back, Home, Timer, Mute). */
@Composable
private fun Glyph(icon: ImageVector, label: String, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    Box(Modifier.size(56.dp).clip(CircleShape).clickable { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() },
        contentAlignment = Alignment.Center) { Icon(icon, label, tint = Palette.muted, modifier = Modifier.size(26.dp)) }
}

/** While watching: what's on (when we opened it) and when it ends, in place of the apps and the picks line. */
@Composable
private fun NowShowing(tv: TvController) {
    val app = tv.foregroundApp?.let { tv.appTitle(it) }
    val (title, ends) = tv.picks.nowShowing ?: (app to null)
    Column(Modifier.fillMaxWidth().padding(top = 14.dp, start = 2.dp)) {
        if (title == null) Text("Watching", color = Palette.text, fontSize = 22.sp, fontWeight = FontWeight.Bold) else RawText(title, color = Palette.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(listOfNotNull(app.takeIf { tv.picks.nowShowing != null }, tv.mediaState, ends?.let { "ends $it" }).joinToString(" · ").ifEmpty { " " },
            color = Palette.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
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
            Icon(downIcon, tr("$label down"), tint = Palette.text, modifier = Modifier.size(22.dp))
        }
        Text(label, color = Palette.faint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
        Box(Modifier.weight(1f).height(58.dp).clickable { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onUp() }, contentAlignment = Alignment.Center) {
            Icon(upIcon, tr("$label up"), tint = Palette.text, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun MicButton(onClick: () -> Unit) {
    Box(Modifier.size(72.dp).clip(CircleShape).background(Palette.primary).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.Mic, "Talk", tint = Palette.onPrimary, modifier = Modifier.size(30.dp))
    }
}

/** Shown while a timer runs: what it'll do, the countdown, +10 min and cancel. */
@Composable
private fun TimerPill(state: TimerState) {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.endsAt) { while (true) { now = System.currentTimeMillis(); delay(500) } }
    val label = if (state.kind == TimerKind.TurnOff) "TV off in" else "Timer on TV"
    Row(Modifier.fillMaxWidth().padding(top = 16.dp).card(RoundedCornerShape(16.dp)).padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Timer, null, tint = Palette.text, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, color = Palette.muted, fontSize = 14.sp)
        Spacer(Modifier.width(6.dp))
        Text(TimerEngine.format(state.endsAt - now), color = Palette.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text("+10 min", color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { TimerEngine.extend(context, 10) }.padding(horizontal = 10.dp, vertical = 10.dp))
        Box(Modifier.size(40.dp).clip(CircleShape).clickable { TimerEngine.cancel(context) }, contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Close, "Cancel timer", tint = Palette.muted, modifier = Modifier.size(20.dp))
        }
    }
}

/** One-tap timers: turn the TV off later (with pop-up reminders), or show a countdown on the TV. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TimerSheet(onPick: (TimerKind, Int) -> Unit, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0x99000000))
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        contentAlignment = Alignment.BottomCenter) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(Palette.bg)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 28.dp)) {
            Box(Modifier.align(Alignment.CenterHorizontally).width(40.dp).height(5.dp).clip(RoundedCornerShape(3.dp)).background(Palette.surface2))
            TimerRow("Turn TV off in…", "Reminders pop up on the TV at 10, 5 and 1 min.", TimerEngine.TURN_OFF_PRESETS) { onPick(TimerKind.TurnOff, it) }
            TimerRow("Show a timer on TV", "Full-screen countdown. Back on the remote returns to what you were watching.", TimerEngine.ON_SCREEN_PRESETS) { onPick(TimerKind.OnScreen, it) }
            Text("Or just say it: “turn off in 40 minutes”, “timer 10 minutes”", color = Palette.faint, fontSize = 13.sp, modifier = Modifier.padding(top = 20.dp))
        }
    }
}

@Composable
private fun TimerRow(title: String, sub: String, presets: List<Int>, onPick: (Int) -> Unit) {
    Text(title, color = Palette.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 22.dp))
    Text(sub, color = Palette.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for (m in presets) {
            Column(Modifier.weight(1f).height(64.dp).card(RoundedCornerShape(18.dp)).clickable { onPick(m) },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("$m", color = Palette.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text("min", color = Palette.muted, fontSize = 12.sp)
            }
        }
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
            for (c in listOf("“Open Netflix”", "“Volume 20”", "“Turn off in 30 minutes”", "“Pause”")) {
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
/** Finds which way of opening a title each app on this TV accepts (Netflix, Prime Video, Disney+). The one tried last is used. */
@Composable
private fun LinkDialog(tv: TvController, onClose: () -> Unit) {
    val dl = com.home.tvremote.picks.DeepLinks
    var service by remember { mutableStateOf("Netflix") }
    var current by remember(service) { mutableStateOf(tv.picks.store.linkVariant(service)) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Open shows directly") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (sv in dl.DIRECT) {
                        val on = sv == service
                        Text(sv, color = if (on) Color.White else Palette.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) Palette.accent else Palette.surface2)
                                .clickable { service = sv }.padding(horizontal = 12.dp, vertical = 8.dp))
                    }
                }
                Text("Each try opens a different title's own page in $service. Press Home on the TV between tries and note which ones land on that title. The last one you tap is used.", fontSize = 14.sp)
                for ((i, v) in dl.variants(service).withIndex()) {
                    val selected = current == v
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(if (selected) Palette.accent else Palette.surface2)
                            .clickable { current = v; tv.testLink(service, v) }
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Try ${i + 1} · ${dl.tests(service).getValue(v).second}", color = if (selected) Color.White else Palette.text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
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

private const val USAGE_NOTE = "Helps us see which features get used and in which countries. Never names, show titles, what you say or your TV's address. You can change this any time."

/** Asked once, after the TV first connects: share anonymous usage? Same choice as Settings › Privacy. */
@Composable
private fun UsageCard(tv: TvController) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp).card(RoundedCornerShape(18.dp)).padding(16.dp)) {
        Text("Help improve TV Remote?", color = Palette.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text("Share anonymous usage: which features get used, and in which country. Never names, titles or what you say.",
            color = Palette.muted, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp))
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(Palette.surface2).clickable { tv.changeUsageConsent(false) }.padding(vertical = 11.dp),
                contentAlignment = Alignment.Center) { Text("No thanks", color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
            Box(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(Palette.primary).clickable { tv.changeUsageConsent(true) }.padding(vertical = 11.dp),
                contentAlignment = Alignment.Center) { Text("Share", color = Palette.onPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

/** Pick one of [options] (value to English label). Returns the value, or null when dismissed. */
@Composable
private fun ChoiceDialog(title: String, options: List<Pair<String, String>>, current: String, note: String, onDone: (String?) -> Unit) {
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(note, fontSize = 14.sp)
                for ((value, label) in options) {
                    val selected = value == current
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (selected) Palette.accent else Palette.surface2)
                        .clickable { onDone(value) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, color = if (selected) Color.White else Palette.text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(null) }) { Text("Close") } },
        containerColor = Palette.surface, titleContentColor = Palette.text, textContentColor = Palette.muted,
    )
}

@Composable
private fun RenameDialog(tv: TvController, onClose: () -> Unit) {
    var name by remember { mutableStateOf(tv.tvName) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Name this TV") },
        text = {
            BasicTextField(name, { name = it }, singleLine = true,
                textStyle = TextStyle(color = Palette.text, fontSize = 17.sp), cursorBrush = SolidColor(Palette.accent),
                decorationBox = { inner -> Box { if (name.isEmpty()) Text("e.g. Living Room", color = Palette.faint, fontSize = 17.sp); inner() } },
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.surface2).padding(14.dp))
        },
        confirmButton = { TextButton(onClick = { tv.rename(name); onClose() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
        containerColor = Palette.surface, titleContentColor = Palette.text, textContentColor = Palette.muted,
    )
}

@Composable
private fun DiagnosticsDialog(tv: TvController, onClose: () -> Unit) {
    var text by remember { mutableStateOf(tv.diagnostics()) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Diagnostics") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text("What the app asked the TV, and what it answered (newest first). Copy and send this to whoever is helping you.", fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                RawText(text, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Palette.text)
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { clipboard.setText(AnnotatedString(text)); tv.say("Copied") }) { Text("Copy") }
                TextButton(onClick = {
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(android.content.Intent.EXTRA_SUBJECT, "TV Remote diagnostics").putExtra(android.content.Intent.EXTRA_TEXT, text)
                    context.startActivity(android.content.Intent.createChooser(send, "Share diagnostics"))
                }) { Text("Share") }
            }
        },
        dismissButton = {
            Row {
                // Before a test: empty the log so the next Share is only that session.
                TextButton(onClick = { tv.clearDiagnostics(); text = tv.diagnostics(); tv.say("Diagnostics cleared — test, then Share") }) { Text("Start fresh") }
                TextButton(onClick = onClose) { Text("Close") }
            }
        },
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
    var typeIp by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (tv.found.isEmpty()) tv.scan() }
    BackHandler(enabled = tv.canCancelSetup || typeIp) { if (typeIp) typeIp = false else tv.cancelSetup() }
    if (details) AlertDialog(onDismissRequest = { details = false }, title = { Text("Details") },
        text = { Text(tv.scanReport.orEmpty(), fontSize = 13.sp) }, confirmButton = { TextButton(onClick = { details = false }) { Text("Close") } },
        containerColor = Palette.surface, titleContentColor = Palette.text, textContentColor = Palette.muted)
    // Searched and found nothing: plain checks first; typing the IP and the technical details are a tap away.
    if (!tv.scanning && tv.found.isEmpty() && tv.scanReport != null && !typeIp) {
        Box {
            NothingFoundScreen(onSearch = { tv.scan() }, onEnterIp = { typeIp = true }, onDetails = { details = true })
            if (tv.canCancelSetup) Box(Modifier.padding(start = 12.dp, top = 12.dp).size(40.dp).clip(CircleShape).clickable { tv.cancelSetup() },
                contentAlignment = Alignment.Center) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, tr("Back"), tint = Palette.text) }
        }
        return
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (tv.canCancelSetup) Box(Modifier.size(40.dp).clip(CircleShape).clickable { tv.cancelSetup() }, contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, tr("Back"), tint = Palette.text)
        }
        Text("Find your TV", color = Palette.text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("Turn the TV on and make sure this phone is on the same Wi-Fi.", color = Palette.muted, fontSize = 15.sp)
        PrimaryButton(if (tv.scanning) "Searching your Wi-Fi…" else "Search again", enabled = !tv.scanning) { tv.scan() }
        if (tv.scanning) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Palette.text) }
        for (f in tv.found) {
            Row(Modifier.fillMaxWidth().card(RoundedCornerShape(16.dp)).clickable { tv.choose(f.ip, f.name, f.brand) }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Tv, null, tint = Palette.text)
                Spacer(Modifier.width(14.dp))
                Column {
                    RawText(f.name, color = Palette.text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text(listOf(f.model, f.ip).filter { it.isNotEmpty() }.joinToString(" · "), color = Palette.muted, fontSize = 13.sp)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Or enter its IP address (TV Settings › Network › Wi-Fi › Advanced)", color = Palette.muted, fontSize = 13.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(manualIp, { manualIp = it.filter { c -> c.isDigit() || c == '.' } }, singleLine = true,
                textStyle = TextStyle(color = Palette.text, fontSize = 16.sp), cursorBrush = SolidColor(Palette.accent),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                decorationBox = { inner -> Box { if (manualIp.isEmpty()) Text("192.168.1.50", color = Palette.faint); inner() } },
                modifier = Modifier.weight(1f).card(RoundedCornerShape(14.dp)).padding(14.dp))
            Spacer(Modifier.width(10.dp))
            Box(Modifier.card(RoundedCornerShape(14.dp)).clickable(enabled = manualIp.count { it == '.' } == 3) { tv.choose(manualIp, "TV") }.padding(14.dp)) {
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
        Text(if (tv.brand == "Samsung") "A message is asking to allow \"Phone remote\". Select Allow with the TV's own remote — you only do this once."
            else "A message is asking to allow \"TV Remote\". Select Accept with the LG remote — you only do this once.",
            color = Palette.muted, fontSize = 16.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(28.dp))
        CircularProgressIndicator(color = Palette.text)
    }
}

@Composable
internal fun PrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (enabled) Palette.primary else Palette.surface2)
        .clickable(enabled = enabled, onClick = onClick).padding(16.dp), contentAlignment = Alignment.Center) {
        Text(text, color = if (enabled) Palette.onPrimary else Palette.muted, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    }
}
