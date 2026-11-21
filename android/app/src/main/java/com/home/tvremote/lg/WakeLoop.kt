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
    /** The TV's power state once connected ("Active", "Active Standby", …), or null if it won't say. Throws if the request fails. */
    private val powerState: suspend () -> String?,
    /** Drop the connection so the next attempt starts fresh. */
    private val reset: () -> Unit = {},
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
        var stateFailures = 0
        var attempts = 0
        log("WAKE start")
        while (now() - start < totalMs) {
            onTick(((now() - start) / 1000).toInt())
            if (now() - lastSend >= resendMs) {
                runCatching { sent += sendWake() }.onFailure { lastErr = it }
                lastSend = now()
            }
            if (reachable()) {
                if (!upLogged) { log("WAKE TV network up after ${secs()} s"); upLogged = true }
                val tried = now()
                val attempt = runCatching { connectOnce() }
                if (attempt.isFailure || attempt.getOrNull() == false) {
                    // Logged so Diagnostics shows whether the gap between "network up" and "connected" is the TV
                    // (refusing / not answering while it boots) or the app.
                    attempts++
                    if (attempts <= 6) log("WAKE connect attempt $attempts failed at ${secs()} s after ${now() - tried} ms: " +
                        (attempt.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }?.take(90) ?: "not paired"))
                }
                if (attempt.getOrDefault(false)) {
                    // Right after "off" the TV answers for a few seconds while shutting down;
                    // only call it on once it reports itself awake.
                    val state = runCatching { powerState() }
                    val s = state.getOrNull()
                    when {
                        // A failed request means the connection isn't really working yet (or is a stale one): retry.
                        // After 3 in a row on fresh connections, assume this TV just won't answer that question.
                        state.isFailure && ++stateFailures < 3 -> {
                            log("WAKE connected but no answer (${state.exceptionOrNull()?.message?.take(80)}) at ${secs()} s, retrying")
                            reset()
                        }
                        s == null || s.isEmpty() || s == "Active" || state.isFailure -> {
                            log("WAKE connected after ${secs()} s (state ${s ?: "?"})")
                            return Result.On(secs())
                        }
                        else -> log("WAKE connected but TV says $s (${secs()} s)")
                    }
                }
                delay(retryMs)
            } else delay(pollMs)
        }
        log("WAKE gave up after ${secs()} s, $sent packets sent")
        return Result.GaveUp(sent, lastErr)
    }
}
