package com.home.tvremote

import com.home.tvremote.lg.WakeLoop
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The wake loop against a simulated TV, on a virtual clock. */
@OptIn(ExperimentalCoroutinesApi::class)
class WakeLoopTest {

    /** A TV whose network comes up at [upAtMs], refuses [refusals] connections, then reports [states] in order. */
    private class FakeTv(val upAtMs: Long, var refusals: Int = 0, val states: MutableList<String?> = mutableListOf("Active")) {
        var connectAttempts = 0
        var packets = 0
    }

    private fun TestScope.loop(tv: FakeTv, log: MutableList<String>, totalMs: Long = 45_000) = WakeLoop(
        sendWake = { tv.packets += 4; 4 },
        reachable = { currentTime >= tv.upAtMs },
        connectOnce = {
            tv.connectAttempts++
            if (tv.refusals > 0) { tv.refusals--; error("connection refused") }
            true
        },
        powerState = { if (tv.states.size > 1) tv.states.removeAt(0) else tv.states.first() },
        now = { currentTime },
        log = { log += it },
        totalMs = totalMs,
    )

    @Test fun coldBootConnectsSoonAfterNetworkComesUpWithoutHandingOff() = runTest {
        // Like the real diagnostics: network up after ~17 s, the control service not ready at first.
        val tv = FakeTv(upAtMs = 17_200, refusals = 2)
        val log = mutableListOf<String>()
        val r = loop(tv, log).run()
        assertTrue(r is WakeLoop.Result.On)
        val secs = (r as WakeLoop.Result.On).seconds
        val up = Regex("network up after ([0-9.]+) s").find(log.joinToString())!!.groupValues[1].toDouble()
        assertTrue("up at $up s, connected at $secs s", secs - up < 2.5)   // was ~11 s later with the hand-off bug
        assertEquals(3, tv.connectAttempts)
        assertTrue(log.last().startsWith("WAKE connected after"))
        assertTrue("wake keeps being resent while waiting", tv.packets >= 4 * 7)
    }

    @Test fun waitsWhileTheTvIsStillShuttingDown() = runTest {
        val tv = FakeTv(upAtMs = 0, states = mutableListOf("Active Standby", "Active Standby", "Active"))
        val log = mutableListOf<String>()
        val r = loop(tv, log).run()
        assertTrue(r is WakeLoop.Result.On)
        assertEquals(2, log.count { it.contains("TV says Active Standby") })
    }

    @Test fun aStaleConnectionFromBeforeTurnOffIsNotMistakenForOn() = runTest {
        // Seen on the real TV: connect "succeeded" instantly on the old socket, then the power check failed.
        var stale = true
        var resets = 0
        val log = mutableListOf<String>()
        val r = WakeLoop(
            sendWake = { 4 },
            reachable = { currentTime >= 18_000 },
            connectOnce = { true },
            powerState = { if (stale) error("Lost connection to the TV") else "Active" },
            reset = { resets++; stale = false },
            now = { currentTime },
            log = { log += it },
        ).run()
        assertTrue(r is WakeLoop.Result.On)
        assertEquals(1, resets)
        assertTrue(log.any { it.contains("no answer") })
        assertTrue(log.last().contains("state Active"))
    }

    @Test fun aTvThatNeverAnswersThePowerCheckStillCountsAsOnAfterThreeTries() = runTest {
        val log = mutableListOf<String>()
        val r = WakeLoop(
            sendWake = { 4 }, reachable = { true }, connectOnce = { true },
            powerState = { error("unsupported") }, now = { currentTime }, log = { log += it },
        ).run()
        assertTrue(r is WakeLoop.Result.On)
        assertEquals(2, log.count { it.contains("no answer") })
    }

    @Test fun givesUpWhenTheTvNeverAnswers() = runTest {
        val tv = FakeTv(upAtMs = Long.MAX_VALUE)
        val log = mutableListOf<String>()
        val r = loop(tv, log, totalMs = 10_000).run()
        assertTrue(r is WakeLoop.Result.GaveUp)
        assertTrue((r as WakeLoop.Result.GaveUp).packetsSent > 0)
        assertEquals(0, tv.connectAttempts)
        assertTrue(log.last().startsWith("WAKE gave up"))
    }
}
