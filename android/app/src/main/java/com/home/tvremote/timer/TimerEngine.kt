package com.home.tvremote.timer

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.home.tvremote.lg.LgTv
import kotlinx.coroutines.withTimeout

enum class TimerKind { TurnOff, OnScreen }

data class TimerState(
    val kind: TimerKind,
    val endsAt: Long,
    val totalMs: Long,
    /** Reminders already shown, in minutes before the end (TurnOff only). */
    val remindersSent: Set<Int> = emptySet(),
)

/**
 * App-wide timer state. The countdown itself runs in [TimerService] (a foreground service), so it
 * keeps going with the phone locked or the app closed. The phone does the counting; the TV only
 * receives pop-ups, the timer page and the final "turn off".
 */
object TimerEngine {
    /** Reminder points for the turn-off timer, in minutes before the end. */
    val REMINDERS = listOf(10, 5, 1)
    val TURN_OFF_PRESETS = listOf(15, 30, 45, 60)
    val ON_SCREEN_PRESETS = listOf(5, 10, 15, 30)

    var state by mutableStateOf<TimerState?>(null)
        internal set

    /** A short message for the remote to show (e.g. the TV refused to open the timer page). */
    var notice by mutableStateOf<String?>(null)
        internal set

    /** Called when the timer has turned the TV off, so the remote can show its "Turn on" screen. */
    @Volatile var onTvTurnedOff: (() -> Unit)? = null

    fun start(context: Context, kind: TimerKind, minutes: Int) {
        val ms = minutes * 60_000L
        state = TimerState(kind, System.currentTimeMillis() + ms, ms)
        send(context, TimerService.ACTION_START)
    }

    fun extend(context: Context, minutes: Int) {
        val s = state ?: return
        val add = minutes * 60_000L
        val newEnd = s.endsAt + add
        val remaining = newEnd - System.currentTimeMillis()
        // Reminders that are now in the future again can fire again.
        state = s.copy(endsAt = newEnd, totalMs = s.totalMs + add,
            remindersSent = s.remindersSent.filter { remaining <= it * 60_000L }.toSet())
        send(context, TimerService.ACTION_UPDATE)
    }

    fun cancel(context: Context) {
        if (state == null) return
        state = null
        send(context, TimerService.ACTION_STOP)
    }

    internal fun finish() { state = null }

    internal fun say(msg: String) { notice = msg }

    private fun send(context: Context, action: String) {
        val i = Intent(context, TimerService::class.java).setAction(action)
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
    }

    /**
     * Which reminder to show now, if any: returns (minutes to announce, reminders to mark as sent).
     * If several are due at once (e.g. a timer started with 4 min left), only the most urgent is
     * announced and the others are skipped.
     */
    fun dueReminder(endsAt: Long, now: Long, sent: Set<Int>): Pair<Int, Set<Int>>? {
        val remaining = endsAt - now
        if (remaining <= 0) return null
        val due = REMINDERS.filter { it !in sent && remaining <= it * 60_000L }
        if (due.isEmpty()) return null
        return due.min() to due.toSet()
    }

    fun format(ms: Long): String {
        val total = ((ms + 999) / 1000).coerceAtLeast(0)
        val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /** Opens a short-lived connection to the saved TV (independent of the remote screen). */
    internal suspend fun withTv(context: Context, block: suspend (com.home.tvremote.tv.TvDriver) -> Unit): Boolean {
        val prefs = context.getSharedPreferences("tv", Context.MODE_PRIVATE)
        val ip = prefs.getString("ip", null) ?: return false
        val key = prefs.getString("clientKey", null) ?: return false
        val tv = com.home.tvremote.TvController.net.driver(prefs.getString("brand", "LG") ?: "LG", ip, key,
            onClientKey = { prefs.edit().putString("clientKey", it).apply() }, onDisconnect = {})
        return try {
            withTimeout(15_000) { tv.connect(); block(tv) }
            true
        } catch (e: Exception) {
            Log.w("TvRemote", "Timer couldn't reach the TV: ${e.message}")
            false
        } finally { tv.close() }
    }
}
