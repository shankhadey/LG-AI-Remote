package com.home.tvremote.lg

import kotlinx.coroutines.delay

/**
 * Turning the TV on: keep sending the wake signal, notice quickly when the TV's network is back, and
 * keep trying to connect until the TV says it's awake. Kept separate from the UI so it can be tested
 * with a simulated TV and a virtual clock.
 *
 * Every step is a function, so the loop never "hands off" halfway (that bug left the app showing
 * "Turn on" while a slow, single-try connection carried on in the background).
 */
class WakeLoop(
    private val sendWake: suspend () -> Int,
    private val reachable: suspend () -> Boolean,
    /** One short connection attempt; true when connected and paired. */
    private val connectOnce: suspend () -> Boolean,
    /** The TV's power state once connected ("Active", "Active Standby", …), or null if it won't say. */
    private val powerState: suspend () -> String?,
    private val now: () -> Long,
    private val log: (String) -> Unit = {},
    private val onTick: (Int) -> Unit = {},
    private val totalMs: Long = 45_000,
    private val resendMs: Long = 2_500,
    private val pollMs: Long = 500,
    private val retryMs: Long = 600,
) {
    sealed interface Result {
        data class On(val seconds: Double) : Result
        data class GaveUp(val packetsSent: Int, val lastError: Throwable?) : Result
    }

    suspend fun run(): Result {
        val start = now()
        fun secs() = (now() - start) / 100 / 10.0
        var lastSend = Long.MIN_VALUE / 2
        var sent = 0
        var lastErr: Throwable? = null
        var upLogged = false
        log("WAKE start")
        while (now() - start < totalMs) {
            onTick(((now() - start) / 1000).toInt())
            if (now() - lastSend >= resendMs) {
                runCatching { sent += sendWake() }.onFailure { lastErr = it }
                lastSend = now()
            }
            if (reachable()) {
                if (!upLogged) { log("WAKE TV network up after ${secs()} s"); upLogged = true }
                if (runCatching { connectOnce() }.getOrDefault(false)) {
                    // Right after "off" the TV answers for a few seconds while shutting down;
                    // only call it on once it reports itself awake.
                    val state = runCatching { powerState() }.getOrNull()
                    if (state == null || state.isEmpty() || state == "Active") {
                        log("WAKE connected after ${secs()} s (state ${state ?: "?"})")
                        return Result.On(secs())
                    }
                    log("WAKE connected but TV says $state (${secs()} s)")
                }
                delay(retryMs)
            } else delay(pollMs)
        }
        log("WAKE gave up after ${secs()} s, $sent packets sent")
        return Result.GaveUp(sent, lastErr)
    }
}
