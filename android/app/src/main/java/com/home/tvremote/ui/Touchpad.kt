package com.home.tvremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

enum class PadMode { Swipe, Pointer }

/**
 * Swipe mode: swipe to move (keep sliding to keep moving), tap = OK.
 * Pointer mode: drag moves the TV's on-screen cursor, tap clicks, two fingers scroll.
 */
@Composable
fun Touchpad(
    mode: PadMode,
    onModeChange: (PadMode) -> Unit,
    onArrow: (String) -> Unit,
    onOk: () -> Unit,
    onMove: (Float, Float) -> Unit,
    onClick: () -> Unit,
    onScroll: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val step = with(LocalDensity.current) { 52.dp.toPx() }
    val tapSlop = with(LocalDensity.current) { 14.dp.toPx() }
    var flash by remember { mutableStateOf<String?>(null) }

    Box(
        modifier
            .clip(RoundedCornerShape(28.dp))
            .background(Palette.surface)
            .pointerInput(mode) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val start = System.currentTimeMillis()
                    var total = Offset.Zero
                    var acc = Offset.Zero
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
                        when (mode) {
                            PadMode.Swipe -> {
                                acc += d
                                // Emit one arrow per `step` of travel along the dominant axis.
                                while (abs(acc.x) >= step || abs(acc.y) >= step) {
                                    val dir = if (abs(acc.x) > abs(acc.y)) (if (acc.x > 0) "RIGHT" else "LEFT") else (if (acc.y > 0) "DOWN" else "UP")
                                    onArrow(dir); flash = dir
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    acc = when (dir) {
                                        "RIGHT" -> Offset(acc.x - step, 0f); "LEFT" -> Offset(acc.x + step, 0f)
                                        "DOWN" -> Offset(0f, acc.y - step); else -> Offset(0f, acc.y + step)
                                    }
                                }
                            }
                            PadMode.Pointer -> if (fingers >= 2) onScroll(-d.y / 4f) else onMove(d.x * 1.4f, d.y * 1.4f)
                        }
                    }
                    if (!moved && System.currentTimeMillis() - start < 400 && fingers == 1) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (mode == PadMode.Swipe) { onOk(); flash = "OK" } else onClick()
                    }
                    down.consume()
                }
            },
    ) {
        Row(Modifier.align(Alignment.TopCenter).padding(top = 12.dp).clip(RoundedCornerShape(50)).background(Palette.surface2).padding(3.dp)) {
            for (m in PadMode.entries) {
                Text(
                    if (m == PadMode.Swipe) "Swipe" else "Pointer",
                    color = if (m == mode) Palette.text else Palette.muted,
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(50))
                        .background(if (m == mode) Palette.surface else Palette.surface2)
                        .pointerInput(m) { awaitEachGesture { awaitFirstDown().consume(); onModeChange(m) } }
                        .padding(horizontal = 16.dp, vertical = 7.dp),
                )
            }
        }
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                when (flash) { "UP" -> "↑"; "DOWN" -> "↓"; "LEFT" -> "←"; "RIGHT" -> "→"; "OK" -> "OK"; else -> "" },
                color = Palette.accent, fontSize = 34.sp, fontWeight = FontWeight.Bold,
            )
            Text(
                if (mode == PadMode.Swipe) "Swipe to move · tap to select" else "Drag to point · tap to click\nTwo fingers to scroll",
                color = Palette.muted, fontSize = 14.sp, textAlign = TextAlign.Center,
            )
        }
    }
}
