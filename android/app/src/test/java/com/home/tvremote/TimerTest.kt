package com.home.tvremote

import com.home.tvremote.timer.TimerEngine
import com.home.tvremote.timer.TimerKind
import com.home.tvremote.timer.TimerServer
import com.home.tvremote.timer.TimerState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class TimerTest {
    private val min = 60_000L

    @Test fun remindersFireAtTenFiveOne() {
        val end = 30 * min
        assertNull(TimerEngine.dueReminder(end, 0, emptySet()))                       // 30 min left
        assertEquals(10 to setOf(10), TimerEngine.dueReminder(end, 20 * min, emptySet()))
        assertNull(TimerEngine.dueReminder(end, 21 * min, setOf(10)))                   // already sent
        assertEquals(5 to setOf(5), TimerEngine.dueReminder(end, 25 * min, setOf(10)))
        assertEquals(1 to setOf(1), TimerEngine.dueReminder(end, 29 * min, setOf(10, 5)))
        assertNull(TimerEngine.dueReminder(end, end, setOf(10, 5, 1)))
    }

    @Test fun lateStartAnnouncesOnlyTheMostUrgent() {
        // Started with 4 minutes left: say "5 min" once, skip "10 min".
        assertEquals(5 to setOf(10, 5), TimerEngine.dueReminder(4 * min, 0, emptySet()))
    }

    @Test fun formatsCountdown() {
        assertEquals("30:00", TimerEngine.format(30 * min))
        assertEquals("0:59", TimerEngine.format(58_200))
        assertEquals("1:00:00", TimerEngine.format(60 * min))
        assertEquals("0:00", TimerEngine.format(-5))
    }

    @Test fun phoneServesTimerPageAndState() {
        val state = TimerState(TimerKind.OnScreen, System.currentTimeMillis() + 10 * min, 10 * min)
        val server = TimerServer { state }
        server.start(preferredPort = 0)
        try {
            fun get(path: String): Pair<Int, String> = (URL("http://127.0.0.1:${server.port}$path").openConnection() as HttpURLConnection)
                .run { responseCode to inputStream.bufferedReader().readText() }
            val (code, page) = get("/timer")
            assertEquals(200, code)
            assertTrue(page.contains("Time's up"))
            val j = JSONObject(get("/state").second)
            assertTrue(j.getBoolean("running"))
            assertEquals(state.endsAt, j.getLong("endsAt"))
            assertEquals(10 * min, j.getLong("total"))
        } finally { server.stop() }
    }

    @Test fun turnOffTimerIsNotShownOnScreen() {
        val server = TimerServer { TimerState(TimerKind.TurnOff, 1, 1) }
        assertTrue(!JSONObject(server.stateJson()).getBoolean("running"))
    }
}
