package com.home.tvremote.timer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.home.tvremote.MainActivity
import com.home.tvremote.R
import com.home.tvremote.lg.Discovery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

/** Keeps a timer alive with the phone locked; does the counting and talks to the TV. */
class TimerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null
    private var server: TimerServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLUS10 -> TimerEngine.extend(this, 10)
            ACTION_CANCEL -> TimerEngine.cancel(this)
        }
        val state = TimerEngine.state
        if (state == null || intent?.action == ACTION_STOP) {
            // Leave the page up a few seconds so it can show "Timer cancelled", then stop.
            goForeground(null)
            scope.launch { delay(4000); shutdown() }
            return START_NOT_STICKY
        }
        goForeground(state)
        if (intent?.action == ACTION_START && state.kind == TimerKind.OnScreen) openOnTv()
        if (loop?.isActive != true) loop = scope.launch { run() }
        return START_NOT_STICKY
    }

    private suspend fun run() {
        while (scope.isActive) {
            val s = TimerEngine.state ?: return
            val now = System.currentTimeMillis()
            if (s.kind == TimerKind.TurnOff) {
                TimerEngine.dueReminder(s.endsAt, now, s.remindersSent)?.let { (minutes, mark) ->
                    TimerEngine.state = TimerEngine.state?.let { cur -> cur.copy(remindersSent = cur.remindersSent + mark) }
                    toast(if (minutes == 1) "TV turns off in 1 minute" else "TV turns off in $minutes minutes")
                }
            }
            if (now >= s.endsAt) {
                if (s.kind == TimerKind.TurnOff) {
                    val ok = TimerEngine.withTv(this) { it.request("ssap://system/turnOff") }
                    if (ok) TimerEngine.onTvTurnedOff?.invoke()
                } else {
                    toast("Time's up")
                }
                TimerEngine.finish()
                delay(if (s.kind == TimerKind.OnScreen) 10_000 else 0) // let the page show "Time's up"
                shutdown()
                return
            }
            delay(1000)
        }
    }

    private fun toast(msg: String) = scope.launch {
        TimerEngine.withTv(this@TimerService) {
            it.request("ssap://system.notifications/createToast", JSONObject().put("message", msg))
        }
    }

    /** Starts the phone-side page server and asks the TV's browser to open it. */
    private fun openOnTv() {
        val srv = server ?: TimerServer { TimerEngine.state }.also { it.start(); server = it }
        scope.launch {
            val ip = Discovery.phoneWifiIp(this@TimerService)
            if (ip == null) { TimerEngine.say("The phone isn't on Wi-Fi, so the TV can't show the timer"); return@launch }
            val url = "http://$ip:${srv.port}/timer"
            var opened = false
            TimerEngine.withTv(this@TimerService) { tv ->
                opened = runCatching { tv.request("ssap://system.launcher/open", JSONObject().put("target", url)) }.isSuccess ||
                    runCatching {
                        tv.request("ssap://system.launcher/launch", JSONObject().put("id", "com.webos.app.browser")
                            .put("params", JSONObject().put("target", url)))
                    }.isSuccess
            }
            if (!opened) TimerEngine.say("The TV didn't open the timer screen (details in Diagnostics). The timer is still running on your phone.")
        }
    }

    private fun shutdown() {
        server?.stop(); server = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun goForeground(s: TimerState?) {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "TV timer", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        fun action(a: String, req: Int) = PendingIntent.getService(this, req, Intent(this, TimerService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE)
        val b = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this))
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setContentIntent(open)
            .setOngoing(true)
        if (s == null) {
            b.setContentTitle("Timer stopped")
        } else {
            val at = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(s.endsAt))
            b.setContentTitle(if (s.kind == TimerKind.TurnOff) "TV turns off at $at" else "Timer on TV · ends at $at")
                .setUsesChronometer(true).setWhen(s.endsAt).setShowWhen(true)
                .addAction(Notification.Action.Builder(null, "+10 min", action(ACTION_PLUS10, 1)).build())
                .addAction(Notification.Action.Builder(null, "Cancel", action(ACTION_CANCEL, 2)).build())
            if (Build.VERSION.SDK_INT >= 24) b.setChronometerCountDown(true)
        }
        val n = b.build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIFICATION_ID, n)
    }

    override fun onDestroy() { server?.stop(); scope.cancel() }

    companion object {
        const val ACTION_START = "start"
        const val ACTION_UPDATE = "update"
        const val ACTION_STOP = "stop"
        const val ACTION_PLUS10 = "plus10"
        const val ACTION_CANCEL = "cancel"
        private const val CHANNEL = "timer"
        private const val NOTIFICATION_ID = 7
    }
}
