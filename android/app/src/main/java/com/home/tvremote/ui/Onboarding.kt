package com.home.tvremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Onboarding proposals (22 Nov) — designs for approval, not yet wired into the app.
 * 1. Welcome: what the app is, one action.  2. After pairing: switch on "Turn on via Wi-Fi", test it.
 * 3. Nothing found: a checklist instead of a technical report.
 */

@Composable
fun WelcomeScreen(onStart: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Palette.bg).padding(horizontal = 24.dp, vertical = 28.dp)) {
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(76.dp).clip(RoundedCornerShape(22.dp)).background(Palette.primary), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Tv, null, tint = Palette.onPrimary, modifier = Modifier.size(40.dp))
        }
        Text("Your phone is the remote", color = Palette.text, fontSize = 30.sp, fontWeight = FontWeight.Bold, lineHeight = 36.sp,
            modifier = Modifier.padding(top = 24.dp))
        Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Point(Icons.Rounded.Wifi, "Controls your LG or Samsung TV over Wi-Fi", "Point, tap, type and turn it on or off.")
            Point(Icons.Rounded.Mic, "Just say it", "“Open Netflix”, “volume 20”, “turn off in 30 minutes”.")
            Point(Icons.Rounded.AutoAwesome, "Knows what to watch", "Five picks from your services, in seconds.")
        }
        Spacer(Modifier.weight(1.2f))
        PrimaryButton("Find my TV", onClick = onStart)
        Text("The TV and this phone need to be on the same Wi-Fi.", color = Palette.faint, fontSize = 13.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
    }
}

@Composable
private fun Point(icon: ImageVector, title: String, body: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(Palette.surface2), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Palette.text, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.padding(start = 14.dp)) {
            Text(title, color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(body, color = Palette.muted, fontSize = 14.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** Right after pairing: the one TV setting that makes "turn on from the phone" work. */
@Composable
fun TurnOnSetupScreen(brand: String, tvName: String, onTest: () -> Unit, onSkip: () -> Unit) {
    val path = if (brand == "Samsung") "Settings › General › Network › Expert Settings › Power On with Mobile"
        else "Settings › General › Devices › External Devices › TV On With Mobile › Turn on via Wi-Fi"
    Column(Modifier.fillMaxSize().background(Palette.bg).padding(horizontal = 24.dp, vertical = 28.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.CheckCircle, null, tint = Palette.success, modifier = Modifier.size(22.dp))
            RawText(tvName, color = Palette.muted, fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp))
            Text(" connected", color = Palette.muted, fontSize = 14.sp)
        }
        Text("One more thing", color = Palette.text, fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 28.dp))
        Text("To turn the TV on from your phone, switch on one setting on the TV. It takes a minute, and you only do it once.",
            color = Palette.muted, fontSize = 15.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 8.dp))
        Column(Modifier.fillMaxWidth().padding(top = 24.dp).card(RoundedCornerShape(18.dp)).padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.PowerSettingsNew, null, tint = Palette.text, modifier = Modifier.size(20.dp))
                Text("On the TV, with its remote", color = Palette.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 10.dp))
            }
            Text(path, color = Palette.text, fontSize = 15.sp, lineHeight = 22.sp, modifier = Modifier.padding(top = 10.dp))
            Text("Turn it on. Menu names vary a little by TV year.", color = Palette.faint, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        }
        Spacer(Modifier.weight(1f))
        PrimaryButton("Test it: turn the TV off and on", onClick = onTest)
        Text("Later", color = Palette.muted, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onSkip).padding(12.dp))
    }
}

/** Search finished with nothing: what to check, in plain words. Details for whoever is helping stay one tap away. */
@Composable
fun NothingFoundScreen(onSearch: () -> Unit, onEnterIp: () -> Unit, onDetails: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Palette.bg).padding(horizontal = 24.dp, vertical = 28.dp)) {
        Text("No TV found yet", color = Palette.text, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text("Check these, then search again:", color = Palette.muted, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))
        Column(Modifier.fillMaxWidth().padding(top = 20.dp).card(RoundedCornerShape(18.dp)).padding(vertical = 6.dp)) {
            Check("The TV is on", "Not in standby: the picture is showing.")
            Check("Same Wi-Fi", "This phone and the TV are on the same network, not a guest network.")
            Check("Devices can see each other", "Some routers block this (\"AP isolation\" or \"client isolation\"). Turning it off helps.")
        }
        Spacer(Modifier.weight(1f))
        PrimaryButton("Search again", onClick = onSearch)
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Enter the TV's IP address", color = Palette.muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onEnterIp).padding(12.dp))
            Text("Details", color = Palette.faint, fontSize = 14.sp,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onDetails).padding(12.dp))
        }
    }
}

@Composable
private fun Check(title: String, body: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Rounded.RadioButtonUnchecked, null, tint = Palette.faint, modifier = Modifier.size(22.dp))
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(body, color = Palette.muted, fontSize = 14.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 2.dp))
        }
    }
}
