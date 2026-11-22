package com.home.tvremote

import com.home.tvremote.analytics.Analytics
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Nothing is recorded before the user agrees; parameters are short categories. */
class AnalyticsTest {
    @After fun reset() { Analytics.consent = null; Analytics.recent.clear() }

    @Test fun nothingWithoutConsent() {
        Analytics.consent = null
        Analytics.log(Analytics.Event.PickPlayed, "service" to "Netflix")
        Analytics.consent = false
        Analytics.log(Analytics.Event.PickPlayed, "service" to "Netflix")
        assertEquals(0, Analytics.recent.size)
    }

    @Test fun recordedWithConsent_andParametersAreCapped() {
        Analytics.consent = true
        Analytics.log(Analytics.Event.PickPlayed, "service" to "Netflix", "open" to "direct_A", "skip" to null)
        Analytics.log(Analytics.Event.VoiceCommand, "kind" to "x".repeat(100))
        val (voice, pick) = Analytics.recent.toList()
        assertTrue(pick, pick.contains("pick_played service=Netflix open=direct_A") && "skip" !in pick)
        assertTrue(voice, voice.contains("kind=" + "x".repeat(36) + " "))   // capped, never free text
    }
}
