package com.home.tvremote.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Trackpad for the TV's on-screen cursor: drag to move, tap to click, two fingers to scroll. */
@Composable
fun Touchpad(
    onMove: (Float, Float) -> Unit,
    onClick: () -> Unit,
    onScroll: (Float) -> Unit,
    modifier: Modifier = Modifier,
    hint: String = "Drag to point · tap to select",
) {
    val haptic = LocalHapticFeedback.current
    val dotColor = Palette.dots
    val tapSlop = with(LocalDensity.current) { 14.dp.toPx() }

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
                    if (!moved && System.currentTimeMillis() - start < 400 && fingers == 1) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onClick()
                    }
                    down.consume()
                }
            },
    ) {
        Text(
            hint,
            color = Palette.faint, fontSize = 13.sp, textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
        )
    }
}
