package com.home.tvremote

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class EngagementTest {

    @Test fun asksOnceAfterThreeSuccessesOnTwoDaysWithNothingWrong() {
        var day = LocalDate.of(2026, 11, 23)
        val e = Engagement(FakePrefs(), today = { day })
        assertEquals(listOf(false, false, false), List(3) { e.success(troubleNow = false) })   // 3, but one day
        day = day.plusDays(1)
        assertEquals(false, e.success(troubleNow = true))   // something just went wrong: not now
        assertEquals(true, e.success(troubleNow = false))
        e.reviewAsked()
        assertEquals(false, e.success(troubleNow = false))  // never again
    }
}
