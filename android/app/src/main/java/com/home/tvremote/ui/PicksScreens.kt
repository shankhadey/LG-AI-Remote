package com.home.tvremote.ui

import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.key
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.ExperimentalMaterial3Api
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.home.tvremote.picks.Catalog
import com.home.tvremote.picks.Pick
import com.home.tvremote.picks.PICKS
import com.home.tvremote.picks.PicksController
import com.home.tvremote.picks.Step
import com.home.tvremote.voice.VoiceInput

/** Entry point on the remote, above Most used. */
/** The picker's entry on the remote: one slim line. */
@Composable
fun PicksLine(pc: PicksController) {
    Row(Modifier.fillMaxWidth().padding(top = 14.dp).card(RoundedCornerShape(16.dp)).clickable { pc.openPicker() }.padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(28.dp).clip(RoundedCornerShape(9.dp)).background(Palette.primary), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.AutoAwesome, null, tint = Palette.onPrimary, modifier = Modifier.size(15.dp))
        }
        Text("What should we watch?", color = Palette.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(start = 10.dp))
        Icon(Icons.Rounded.ChevronRight, null, tint = Palette.faint)
    }
}

/** Full-screen picker on the phone. */
@Composable
fun PicksScreen(pc: PicksController) {
    BackHandler { if (pc.step == Step.Results || pc.step == Step.Error) pc.back() else pc.close() }
    Box(Modifier.fillMaxSize().background(Palette.bg)) {
        // "On TV" ticked and the page is up: look at the TV, point and click with the phone.
        if (pc.tvPageOn && pc.step != Step.Setup) { TouchpadPicks(pc); return@Box }
        when (pc.step) {
            Step.Setup -> SetupPicks(pc)
            Step.Choose -> ChoosePicks(pc)
            Step.Loading -> Column(Modifier.fillMaxSize().padding(20.dp)) {
                PicksTop(pc, pc.summary) { pc.back() }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Palette.text, strokeWidth = 2.dp)
                        Text("Finding picks…", color = Palette.muted, fontSize = 15.sp, modifier = Modifier.padding(top = 16.dp))
                    }
                }
            }
            Step.Results -> ResultsPicks(pc)
            Step.Error -> Column(Modifier.fillMaxSize().padding(20.dp)) {
                PicksTop(pc, pc.summary) { pc.back() }
                Spacer(Modifier.height(80.dp))
                Text(pc.message, color = Palette.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(20.dp))
                PrimaryPill("Try something else") { pc.back() }
            }
        }
    }
}

@Composable
private fun PicksTop(pc: PicksController, label: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Palette.text)
        }
        Text(label, color = Palette.muted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 4.dp))
        ShowOnTv(pc)
    }
}

/** "On TV" as a quiet switch in the corner. */
@Composable
private fun ShowOnTv(pc: PicksController) {
    Row(Modifier.clip(RoundedCornerShape(10.dp)).clickable { pc.changeShowOnTv(!pc.showOnTv) }.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("On TV", color = Palette.muted, fontSize = 13.sp)
        Spacer(Modifier.width(8.dp))
        Switch(pc.showOnTv, { pc.changeShowOnTv(it) }, modifier = Modifier.height(24.dp),
            colors = SwitchDefaults.colors(checkedTrackColor = Palette.primary, checkedThumbColor = Palette.onPrimary))
    }
}

@Composable
private fun TouchpadPicks(pc: PicksController) {
    val context = LocalContext.current
    var heard by remember { mutableStateOf("") }
    var listening by remember { mutableStateOf(false) }
    val voice = remember {
        VoiceInput(context, onPartial = { heard = it }, onResult = { g -> heard = ""; pc.askSpoken(g) },
            onError = { listening = false; heard = "" }, onEnd = { listening = false })
    }
    DisposableEffect(Unit) { onDispose { voice.release() } }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 8.dp)) {
        PicksTop(pc, if (pc.step == Step.Choose) pc.title else pc.summary) { if (pc.step == Step.Choose) pc.close() else pc.back() }
        Row(Modifier.padding(top = 10.dp, bottom = 12.dp, start = 4.dp)) {
            when {
                listening -> Text(heard.ifEmpty { "Listening…" }, color = Palette.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                pc.pointingAt == "-" -> Text("Not on a button", color = Palette.faint, fontSize = 14.sp)
                pc.pointingAt.isNotEmpty() -> {
                    Text("Pointing at: ", color = Palette.faint, fontSize = 14.sp)
                    Text(pc.pointingAt, color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                else -> Text("Look at the TV", color = Palette.faint, fontSize = 14.sp)
            }
        }
        Touchpad(onMove = pc::move, onClick = pc::tap, onScroll = pc::scroll, modifier = Modifier.fillMaxWidth().weight(1f),
            hint = "Move to point · tap to click")
        Box(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(58.dp).clip(CircleShape).background(if (listening) Palette.danger else Palette.primary).clickable {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED && voice.available) {
                    if (listening) voice.finish() else { listening = true; heard = ""; voice.start() }
                } else pc.noteMicNeeded()
            }, contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Mic, "Say it", tint = Palette.onPrimary, modifier = Modifier.size(24.dp)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoosePicks(pc: PicksController) {
    var editing by remember { mutableStateOf<String?>(null) }
    editing?.let { person -> FavouritesEditor(pc, person) { editing = null }; return }
    val context = LocalContext.current
    var heard by remember { mutableStateOf("") }
    var listening by remember { mutableStateOf(false) }
    val voice = remember {
        VoiceInput(context, onPartial = { heard = it }, onResult = { g -> heard = g.first(); pc.askSpoken(g) },
            onError = { listening = false }, onEnd = { listening = false })
    }
    DisposableEffect(Unit) { onDispose { voice.release() } }
    fun mic() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED && voice.available) {
            if (listening) voice.finish() else { listening = true; heard = ""; voice.start() }
        } else pc.noteMicNeeded()
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).clickable { pc.close() }, contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Palette.text)
            }
            Spacer(Modifier.weight(1f))
            ShowOnTv(pc)
        }
        Text(pc.title, color = Palette.text, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
        Text(if (listening) heard.ifEmpty { "Listening…" } else pc.contextLine, color = if (listening) Palette.text else Palette.muted,
            fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))

        Label("Who's watching")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (p in pc.profiles()) Chip(p, p == pc.who) { pc.selectWho(p) }
        }
        pc.favVersion.let { }   // redraw when favourites change
        if (pc.wantsFavourites()) {
            Column(Modifier.fillMaxWidth().padding(top = 14.dp).card(RoundedCornerShape(18.dp)).padding(14.dp)) {
                Text("Picks get better when I know ${pc.who}'s taste", color = Palette.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("Name 3–5 shows or movies ${pc.who} loved.", color = Palette.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Add favourites", color = Palette.onPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(Palette.primary).clickable { editing = pc.who }.padding(horizontal = 14.dp, vertical = 8.dp))
                    Text("Later", color = Palette.muted, fontSize = 13.sp,
                        modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(Palette.surface2).clickable { pc.favouritesLater() }.padding(horizontal = 14.dp, vertical = 8.dp))
                }
            }
        }
        if (pc.store.languages.size > 1) {
            Label("Language")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Any", pc.language == null) { pc.selectLanguage(null) }
                for (c in pc.store.languages) Chip(Catalog.languageLabel(c), pc.language == c) { pc.selectLanguage(c) }
            }
        }
        pc.keep?.let { k ->
            Label("Keep watching")
            Row(Modifier.fillMaxWidth().card(RoundedCornerShape(18.dp)).clickable { pc.playKeepWatching() }.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.PlayArrow, null, tint = Palette.text, modifier = Modifier.size(20.dp))
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(k.playedName ?: "", color = Palette.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(k.service ?: "", color = Palette.muted, fontSize = 12.sp)
                }
                // Dismiss: never suggested again for this person.
                Box(Modifier.size(40.dp).clip(CircleShape).clickable { pc.dismissKeep() }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Close, "Don't suggest this", tint = Palette.faint, modifier = Modifier.size(18.dp))
                }
            }
        }
        Label("Mood")
        // Six even tiles: Surprise is the sixth.
        val tiles: List<Pair<Pair<String, String>, () -> Unit>> = pc.moods().map { m -> (m.label to m.hint) to { pc.chooseMood(m) } } +
            listOf(("Surprise ${if (pc.who == Catalog.TOGETHER) "us" else "me"}" to "Anything good") to { pc.surprise() })
        for ((r, row) in tiles.chunked(2).withIndex()) {
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for ((idx, t) in row.withIndex()) {
                    val dark = r * 2 + idx == tiles.lastIndex
                    Column(Modifier.weight(1f).height(64.dp).then(if (dark) Modifier.clip(RoundedCornerShape(18.dp)).background(Palette.primary) else Modifier.card(RoundedCornerShape(18.dp)))
                        .clickable(onClick = t.second).padding(horizontal = 14.dp), verticalArrangement = Arrangement.Center) {
                        Text(t.first.first, color = if (dark) Palette.onPrimary else Palette.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(t.first.second, color = if (dark) Palette.onPrimary.copy(alpha = 0.7f) else Palette.muted, fontSize = 12.sp)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        // Or say it: the same mic as the remote.
        Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 20.dp).clip(RoundedCornerShape(24.dp)).clickable { mic() }.padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Mic, null, tint = if (listening) Palette.danger else Palette.muted, modifier = Modifier.size(18.dp))
            Text(if (listening) "  Listening… tap to stop" else "  or say it — “something like Severance”", color = Palette.muted, fontSize = 13.sp)
        }
    }
}

@Composable
private fun ResultsPicks(pc: PicksController) {
    var confirmNever by remember { mutableStateOf<Int?>(null) }
    confirmNever?.let { i ->
        val name = pc.picks.getOrNull(i)?.title?.name ?: ""
        androidx.compose.material3.AlertDialog(onDismissRequest = { confirmNever = null },
            title = { Text("Never suggest $name?") },
            text = { Text("It won't come up again for ${pc.who}.") },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { pc.never(i); confirmNever = null }) { Text("Never") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmNever = null }) { Text("Cancel") } },
            containerColor = Palette.surface, titleContentColor = Palette.text, textContentColor = Palette.muted)
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 8.dp)) {
        PicksTop(pc, pc.summary) { pc.back() }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 6.dp)) {
            pc.picks.forEachIndexed { i, p -> key(p.title.key) { PickRow(pc, p, i, onNever = { confirmNever = i }) } }
        }
        Text("Tap to play · swipe left for “not now” · hold for “never”", color = Palette.faint, fontSize = 12.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp))
        Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f).height(48.dp).card(RoundedCornerShape(24.dp)).clickable { pc.more() }, contentAlignment = Alignment.Center) {
                Text("$PICKS more", color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            Box(Modifier.weight(1f).height(48.dp).card(RoundedCornerShape(24.dp)).clickable { pc.back() }, contentAlignment = Alignment.Center) {
                Text("Other mood", color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Text("Data from TMDB · availability may vary", color = Palette.faint, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}

/** A pick: tap to play, swipe left for Not now (the next one slides in), hold for Never (asks first). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun PickRow(pc: PicksController, p: Pick, i: Int, onNever: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
        if (v == SwipeToDismissBoxValue.EndToStart) { haptic.performHapticFeedback(HapticFeedbackType.LongPress); pc.notNow(i) }
        false   // the list itself replaces the pick
    })
    SwipeToDismissBox(state, enableDismissFromStartToEnd = false, backgroundContent = {
        Box(Modifier.fillMaxSize().padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(Palette.surface2).padding(end = 18.dp),
            contentAlignment = Alignment.CenterEnd) { Text("Not now", color = Palette.muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
    }) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(Palette.bg)
            .combinedClickable(onClick = { pc.play(i) }, onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onNever() })
            .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(56.dp).height(84.dp).clip(RoundedCornerShape(9.dp)).background(Color(android.graphics.Color.parseColor(pc.color(p.title.name))))) {
                val url = pc.posterUrl(p.title)
                if (url != null) AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Text(p.title.name, color = Color.White, fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold, maxLines = 4,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.BottomStart).padding(5.dp))
            }
            Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.title.name, color = Palette.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (p.title.fresh) Text("NEW", color = Palette.onPrimary, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(4.dp)).background(Palette.primary).padding(horizontal = 4.dp, vertical = 1.dp))
                }
                Text(pc.meta(p.title), color = Palette.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(p.why, color = Palette.text, fontSize = 12.5.sp, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Rounded.PlayArrow, "Play", tint = Palette.faint, modifier = Modifier.padding(start = 6.dp).size(22.dp))
        }
    }
}

// ---- favourites ----------------------------------------------------------------

/** "Shows you loved" for one person: search TMDB, tap + to add, ✕ to remove. Saved on Done. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FavouritesEditor(pc: PicksController, person: String, onDone: () -> Unit) {
    BackHandler(onBack = onDone)
    val chosen = remember { mutableStateListOf<Pair<String, String>>().apply { addAll(pc.store.favourites(person)) } }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<com.home.tvremote.picks.Title>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(query) {
        if (query.trim().length < 2) { results = emptyList(); return@LaunchedEffect }
        kotlinx.coroutines.delay(350)   // wait for typing to pause
        searching = true; results = pc.searchTitles(query.trim()); searching = false
    }
    fun finish() { pc.saveFavourites(person, chosen.toList()); onDone() }
    Column(Modifier.fillMaxSize().background(Palette.bg).padding(horizontal = 20.dp, vertical = 8.dp)) {
        Box(Modifier.size(40.dp).clip(CircleShape).clickable { finish() }, contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Palette.text)
        }
        Text("$person's favourites", color = Palette.text, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
        Text("Shows or movies $person loved. 3 is enough.", color = Palette.muted, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
        Box(Modifier.fillMaxWidth().padding(top = 14.dp).card(RoundedCornerShape(24.dp)).padding(horizontal = 18.dp, vertical = 14.dp)) {
            if (query.isEmpty()) Text("Type a title…", color = Palette.faint, fontSize = 15.sp)
            BasicTextField(query, { query = it }, singleLine = true, textStyle = TextStyle(color = Palette.text, fontSize = 15.sp),
                cursorBrush = SolidColor(Palette.accent), modifier = Modifier.fillMaxWidth())
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 10.dp)) {
            if (searching && results.isEmpty()) Text("Searching…", color = Palette.faint, fontSize = 13.sp)
            if (!searching && query.trim().length >= 2 && results.isEmpty()) Text("Nothing found. Check the spelling, or the TMDB key in Picks settings.", color = Palette.faint, fontSize = 13.sp)
            for (t in results) {
                val added = chosen.any { it.first == t.key }
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).card(RoundedCornerShape(14.dp))
                    .clickable { if (added) chosen.removeAll { it.first == t.key } else chosen += t.key to t.name }.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(40.dp).height(60.dp).clip(RoundedCornerShape(6.dp)).background(Color(android.graphics.Color.parseColor(pc.color(t.name))))) {
                        pc.posterUrl(t)?.let { AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(t.name, color = Palette.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOf(if (t.kind == com.home.tvremote.picks.Kind.Tv) "Series" else "Movie", t.year).filter { it.isNotEmpty() }.joinToString(" · "),
                            color = Palette.muted, fontSize = 12.sp)
                    }
                    Box(Modifier.size(32.dp).clip(CircleShape).background(if (added) Palette.primary else Palette.surface2), contentAlignment = Alignment.Center) {
                        if (added) Icon(Icons.Rounded.Check, "Added", tint = Palette.onPrimary, modifier = Modifier.size(18.dp))
                        else Text("+", color = Palette.muted, fontSize = 20.sp)
                    }
                }
            }
            if (chosen.isNotEmpty()) {
                Label("Added")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (c in chosen.toList()) Chip("${c.second}  ✕", true) { chosen.remove(c) }
                }
            }
        }
        Box(Modifier.fillMaxWidth().padding(vertical = 10.dp).height(50.dp).clip(RoundedCornerShape(25.dp)).background(Palette.primary).clickable { finish() },
            contentAlignment = Alignment.Center) {
            Text(if (chosen.isEmpty()) "Done" else "Done · ${chosen.size} added", color = Palette.onPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ---- setup -------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SetupPicks(pc: PicksController) {
    var editingFav by remember { mutableStateOf<String?>(null) }
    val s = pc.store
    val people = remember { mutableStateListOf<String>().apply { addAll(s.people) } }
    val services = remember { mutableStateListOf<String>().apply { addAll(s.services) } }
    val langs = remember { mutableStateListOf<String>().apply { addAll(s.languages) } }
    var newPerson by remember { mutableStateOf("") }
    var weekday by remember { mutableStateOf(s.weekdayBedtime) }
    var weekend by remember { mutableStateOf(s.weekendBedtime) }
    var tmdb by remember { mutableStateOf(s.tmdbKey) }
    var groq by remember { mutableStateOf(s.groqKey) }
    var leanBack by remember { mutableStateOf(s.leanBack) }
    var aiChoice by remember { mutableStateOf(s.aiChoice) }
    androidx.compose.runtime.LaunchedEffect(Unit) { pc.refreshOnDevice() }
    var error by remember { mutableStateOf("") }
    // Editing someone's favourites covers the settings without losing what's been typed here.
    editingFav?.let { person -> FavouritesEditor(pc, person) { editingFav = null }; return }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
        Box(Modifier.size(40.dp).clip(CircleShape).clickable { pc.close() }, contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Palette.text)
        }
        Text("Set up picks", color = Palette.text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text("Change any time from the TV name menu.", color = Palette.muted, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))

        Label("Who uses this remote?")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (p in people.toList()) Chip("$p  ✕", false) { people.remove(p) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp).card(RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                if (newPerson.isEmpty()) Text("Add a person (e.g. a first name)", color = Palette.faint, fontSize = 15.sp)
                BasicTextField(newPerson, { newPerson = it }, singleLine = true, textStyle = TextStyle(color = Palette.text, fontSize = 15.sp),
                    cursorBrush = SolidColor(Palette.accent), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (newPerson.isNotBlank()) { people += newPerson.trim(); newPerson = "" } }), modifier = Modifier.fillMaxWidth())
            }
            Text("Add", color = Palette.text, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { if (newPerson.isNotBlank()) { people += newPerson.trim(); newPerson = "" } })
        }

        Label("Your services")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (svc in Catalog.SERVICES) Chip(svc.name, svc.name in services) { if (svc.name in services) services.remove(svc.name) else services += svc.name }
        }
        Label("Languages you watch")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((label, code) in Catalog.LANGUAGES) Chip(label, code in langs) { if (code in langs) { if (langs.size > 1) langs.remove(code) } else langs += code }
        }
        Label("Usually in bed by")
        BedtimeRow("Sun–Thu", weekday) { weekday = it }
        BedtimeRow("Fri–Sat", weekend) { weekend = it }

        if (people.isNotEmpty()) {
            Label("Favourites")
            pc.favVersion.let { }
            Column(Modifier.fillMaxWidth().card(RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 6.dp)) {
                for (p in people.toList()) Row(Modifier.fillMaxWidth().clickable { editingFav = p }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p, color = Palette.text, fontSize = 15.sp)
                        Text(s.favourites(p).joinToString { it.second }.ifEmpty { "None yet: tap to add" }, color = Palette.muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(Icons.Rounded.ChevronRight, null, tint = Palette.faint)
                }
            }
        }

        PicksAiSection(pc, tmdb, { tmdb = it }, groq, { groq = it }, aiChoice) { aiChoice = it }

        if (error.isNotEmpty()) Text(error, color = Palette.danger, fontSize = 14.sp, modifier = Modifier.padding(top = 14.dp))
        Box(Modifier.padding(top = 16.dp, bottom = 24.dp)) {
            PrimaryPill("Done", fill = true) {
                if (newPerson.isNotBlank()) { people += newPerson.trim(); newPerson = "" }
                error = when {
                    services.isEmpty() -> "Choose at least one service"
                    tmdb.isBlank() -> "A TMDB key is needed to find shows"
                    aiChoice == "groq" && groq.isBlank() -> "Paste your Groq key, or choose another option"
                    else -> ""
                }
                if (error.isEmpty()) {
                    s.people = people.toList(); s.services = services.toList(); s.languages = langs.toList()
                    s.weekdayBedtime = weekday; s.weekendBedtime = weekend; s.tmdbKey = tmdb; s.groqKey = groq; s.leanBack = leanBack
                    s.aiChoice = aiChoice
                    pc.finishSetup()
                }
            }
        }
    }
}

/** "Finding shows" (TMDB key) and "Who makes the picks?" — part of setup. */
@Composable
internal fun PicksAiSection(
    pc: PicksController, tmdb: String, onTmdb: (String) -> Unit, groq: String, onGroq: (String) -> Unit,
    aiChoice: String, onChoice: (String) -> Unit,
) {
    Column {
        Label("Finding shows")
        KeyField("TMDB key", "Needed to find shows and where they're streaming. Free at themoviedb.org › Settings › API.", tmdb) { onTmdb(it) }

        Label("Who makes the picks?")
        Choice("This phone's AI", "Private and free — nothing leaves your phone. " + pc.onDeviceStatus.ifEmpty { "Checking…" },
            aiChoice == "phone") { onChoice("phone") }
        Choice("Groq (online AI)", "A bigger, cleverer AI. Needs a free key from console.groq.com.", aiChoice == "groq") { onChoice("groq") }
        if (aiChoice == "groq") KeyField("Groq key", "Paste the key from console.groq.com › API Keys.", groq) { onGroq(it) }
        Choice("No AI", "Simple rules: popular, well-rated shows that fit your mood and time.", aiChoice == "rules") { onChoice("rules") }
        Text("If your choice isn't available at that moment, the app uses the next option automatically, so you always get picks. Keys stay on this phone.",
            color = Palette.faint, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))

    }
}

@Composable
private fun Choice(title: String, sub: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).card(RoundedCornerShape(14.dp))
        .then(if (selected) Modifier.border(2.dp, Palette.text, RoundedCornerShape(14.dp)) else Modifier)
        .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(22.dp).clip(CircleShape).border(2.dp, if (selected) Palette.text else Palette.faint, CircleShape), contentAlignment = Alignment.Center) {
            if (selected) Box(Modifier.size(12.dp).clip(CircleShape).background(Palette.text))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Palette.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(sub, color = Palette.muted, fontSize = 12.sp, lineHeight = 16.sp)
        }
    }
}

@Composable
private fun BedtimeRow(label: String, minutes: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).card(RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Palette.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text("–", color = Palette.text, fontSize = 22.sp, modifier = Modifier.clip(CircleShape).clickable { onChange(Math.floorMod(minutes - 15, 1440)) }.padding(horizontal = 14.dp, vertical = 6.dp))
        Text(clock(minutes), color = Palette.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(76.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Text("+", color = Palette.text, fontSize = 22.sp, modifier = Modifier.clip(CircleShape).clickable { onChange(Math.floorMod(minutes + 15, 1440)) }.padding(horizontal = 14.dp, vertical = 6.dp))
    }
}

private fun clock(min: Int): String {
    val h = min / 60; val m = min % 60
    return "%d:%02d %s".format(if (h % 12 == 0) 12 else h % 12, m, if (h < 12) "am" else "pm")
}

@Composable
private fun KeyField(title: String, help: String, value: String, onChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp).card(RoundedCornerShape(14.dp)).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Palette.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (value.isNotBlank()) Text("Saved ✓", color = Palette.muted, fontSize = 12.sp)
        }
        Text(help, color = Palette.muted, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Palette.surface2).padding(horizontal = 12.dp, vertical = 10.dp)) {
            if (value.isEmpty()) Text("Paste key", color = Palette.faint, fontSize = 14.sp)
            BasicTextField(value, onChange, singleLine = true, textStyle = TextStyle(color = Palette.text, fontSize = 14.sp),
                cursorBrush = SolidColor(Palette.accent), visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        }
    }
}

// ---- small pieces ----------------------------------------------------------------

@Composable
private fun Label(text: String) = Text(text.uppercase(), color = Palette.faint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
    letterSpacing = 1.sp, modifier = Modifier.padding(top = 20.dp, bottom = 10.dp))

@Composable
private fun Chip(text: String, on: Boolean, onClick: () -> Unit) {
    Text(text, color = if (on) Palette.onPrimary else Palette.text, fontSize = 14.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.then(if (on) Modifier.clip(RoundedCornerShape(50)).background(Palette.primary) else Modifier.card(RoundedCornerShape(50)))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp))
}

@Composable
private fun SmallPrimary(text: String, icon: ImageVector, onClick: () -> Unit) {
    Row(Modifier.height(34.dp).clip(RoundedCornerShape(17.dp)).background(Palette.primary).clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Palette.onPrimary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, color = Palette.onPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Ghost(text: String, icon: ImageVector? = null, onClick: () -> Unit) {
    Row(Modifier.height(34.dp).clip(RoundedCornerShape(17.dp)).background(Palette.surface2).clickable(onClick = onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { Icon(icon, null, tint = Palette.muted, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)) }
        Text(text, color = Palette.muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun PrimaryPill(text: String, fill: Boolean = false, onClick: () -> Unit) {
    Box(Modifier.then(if (fill) Modifier.fillMaxWidth() else Modifier).height(48.dp).clip(RoundedCornerShape(24.dp)).background(Palette.primary)
        .clickable(onClick = onClick).padding(horizontal = 22.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Palette.onPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}
