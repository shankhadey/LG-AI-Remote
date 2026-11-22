package com.home.tvremote.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Trackpad for the TV's on-screen cursor: drag to move, tap to click, two fingers to scroll.
 * With [onSkip] (while something plays): double-tap the left/right half to skip back/forward, and keep tapping to
 * skip more, like Netflix and YouTube on phones. A single tap then waits a moment to make sure it isn't a double.
 */
@Composable
fun Touchpad(
    onMove: (Float, Float) -> Unit,
    onClick: () -> Unit,
    onScroll: (Float) -> Unit,
    modifier: Modifier = Modifier,
    hint: String = "Drag to point · tap to select",
    onSkip: ((forward: Boolean) -> Unit)? = null,
) {
    val haptic = LocalHapticFeedback.current
    val dotColor = Palette.dots
    val tapSlop = with(LocalDensity.current) { 14.dp.toPx() }
    val scope = rememberCoroutineScope()
    val skip by rememberUpdatedState(onSkip)
    val click by rememberUpdatedState(onClick)
    // Double-tap state, kept across gestures.
    var pendingTap by remember { mutableStateOf<Job?>(null) }
    var lastTap by remember { mutableStateOf(0L to 0f) }          // time, x
    var streak by remember { mutableStateOf(0) }                  // skips in the current run
    var streakForward by remember { mutableStateOf(true) }
    var streakUntil by remember { mutableStateOf(0L) }
    var ripple by remember { mutableStateOf<String?>(null) }
    var rippleJob by remember { mutableStateOf<Job?>(null) }

    Box(
        modifier
            .card(RoundedCornerShape(30.dp))
            .drawBehind {
                // A quiet dot grid so the surface reads as "touch here".
                val step = 18.dp.toPx(); val r = 1.dp.toPx(); val c = dotColor
                var y = step / 2
                while (y < size.height) { var x = step / 2; while (x < size.width) { drawCircle(c, r, Offset(x, y)); x += step }; y += step }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val start = System.currentTimeMillis()
                    var total = Offset.Zero
                    var moved = false
                    var fingers = 1
                    while (true) {
                        val ev = awaitPointerEvent()
                        val pressed = ev.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        fingers = maxOf(fingers, pressed.size)
                        val d = pressed.map { it.positionChange() }.reduce { a, b -> a + b } / pressed.size.toFloat()
                        ev.changes.forEach { it.consume() }
                        total += d
                        if (total.getDistance() > tapSlop) moved = true
                        if (fingers >= 2) onScroll(-d.y / 4f) else onMove(d.x * 1.4f, d.y * 1.4f)
                    }
                    val isTap = !moved && System.currentTimeMillis() - start < 400 && fingers == 1
                    if (isTap) {
                        val now = System.currentTimeMillis()
                        val forward = down.position.x > size.width / 2f
                        val s = skip
                        when {
                            s == null -> { haptic.performHapticFeedback(HapticFeedbackType.LongPress); click() }
                            // Keep tapping the same side to skip more.
                            now < streakUntil && forward == streakForward -> {
                                streak++; streakUntil = now + 700; s(forward)
                            }
                            // Second tap soon after the first, same side: a double tap — skip instead of pausing.
                            pendingTap?.isActive == true && now - lastTap.first < 300 && (lastTap.second > size.width / 2f) == forward -> {
                                pendingTap?.cancel(); pendingTap = null
                                streak = 1; streakForward = forward; streakUntil = now + 700; s(forward)
                            }
                            else -> {
                                lastTap = now to down.position.x
                                pendingTap = scope.launch { delay(260); haptic.performHapticFeedback(HapticFeedbackType.LongPress); click() }
                            }
                        }
                        if (s != null && streak > 0 && now < streakUntil) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            ripple = (if (streakForward) "+" else "−") + "${streak * 10} s"
                            rippleJob?.cancel(); rippleJob = scope.launch { delay(800); ripple = null; streak = 0 }
                        }
                    }
                    down.consume()
                }
            },
    ) {
        if (onSkip != null) {
            androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.Replay10, "Double-tap to go back 10 seconds", tint = Palette.faint,
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 16.dp).size(26.dp))
            androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.Forward10, "Double-tap to skip 10 seconds", tint = Palette.faint,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp).size(26.dp))
        }
        // The skip ripple on the side that was tapped.
        AnimatedVisibility(ripple != null, enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(if (streakForward) Alignment.CenterEnd else Alignment.CenterStart).fillMaxHeight(0.6f).fillMaxWidth(0.5f)) {
            Box(Modifier.clip(RoundedCornerShape(200.dp)).background(Palette.text.copy(alpha = 0.06f)), contentAlignment = Alignment.Center) {
                Text(ripple ?: "", color = Palette.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text(
            hint,
            color = Palette.faint, fontSize = 13.sp, textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
        )
    }
}

@Suppress("unused") private val keepColorImport = Color.Unspecified
