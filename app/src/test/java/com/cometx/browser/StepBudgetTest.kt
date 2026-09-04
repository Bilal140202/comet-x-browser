package com.cometx.browser

import com.cometx.browser.engine.StepBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3 — adaptive step budget: extends only while the task progresses,
 * never beyond the hard ceiling, never for thrashing runs.
 */
class StepBudgetTest {

    private fun make(pref: Int = 24, goal: String = "find a hotel") =
        StepBudget.initialFor(pref, goal)

    // ------------------------------------------------------------- initial

    @Test fun `initial budget respects user preference`() {
        assertEquals(24, make(pref = 24).initial)
        assertEquals(24, make(pref = 24).budget)
    }

    @Test fun `initial budget clamps to bounds`() {
        assertEquals(4, StepBudget.initialFor(1, "x").initial)
        assertEquals(StepBudget.ABSOLUTE_MAX, StepBudget.initialFor(500, "x").initial)
    }

    @Test fun `complex goals start bigger`() {
        val short = StepBudget.initialFor(24, "buy milk")
        val long = StepBudget.initialFor(24, "first search for flights, then compare prices, then pick the cheapest and finally open it")
        assertTrue("long goal should get a bonus: ${long.initial} vs ${short.initial}", long.initial > short.initial)
        assertTrue(long.initial <= StepBudget.ABSOLUTE_MAX)
    }

    // ----------------------------------------------------------- accounting

    @Test fun `consume decrements remaining`() {
        val b = make(pref = 10)
        assertTrue(b.hasRemaining())
        b.consume()
        assertEquals(1, b.used)
        assertEquals(9, b.remaining())
        b.consume(); b.consume(); b.consume(); b.consume()
        b.consume(); b.consume(); b.consume(); b.consume(); b.consume()
        assertFalse(b.hasRemaining())
        assertEquals(0, b.remaining())
    }

    @Test fun `refund never goes below zero and restores the step`() {
        val b = make(pref = 6)
        b.consume()
        b.refund()
        assertEquals(0, b.used)
        b.refund() // extra refund is a no-op
        assertEquals(0, b.used)
    }

    // ------------------------------------------------------------ extension

    private fun exhaustWithProgress(b: StepBudget, navigationsEvery: Int = 3) {
        var i = 0
        while (b.hasRemaining()) {
            b.consume()
            i++
            b.record(actionSuccess = true, urlChanged = (i % navigationsEvery == 0), repeated = false)
        }
    }

    @Test fun `progressing task earns extensions`() {
        val b = make(pref = 24)
        exhaustWithProgress(b)
        assertTrue("should extend on progress", b.shouldExtend())
        val before = b.budget
        assertTrue(b.extend())
        assertEquals(before + 8, b.budget)   // max(6, 24/3)
        assertEquals(1, b.extensions)
    }

    @Test fun `extension never exceeds the hard ceiling`() {
        val b = StepBudget.initialFor(StepBudget.ABSOLUTE_MAX, "x")
        // already at ceiling
        assertFalse(b.shouldExtend())
        assertFalse(b.extend())
    }

    @Test fun `ceiling bound even from a high start`() {
        val b = StepBudget.initialFor(55, "x")
        exhaustWithProgress(b)
        assertTrue(b.extend())               // min(60, 55+18) = 60
        assertEquals(StepBudget.ABSOLUTE_MAX, b.budget)
        exhaustWithProgress(b)
        assertFalse("at ceiling — no more extensions", b.shouldExtend())
    }

    @Test fun `at most three extensions`() {
        val b = make(pref = 24)
        repeat(3) {
            exhaustWithProgress(b)
            assertTrue(b.extend())
        }
        exhaustWithProgress(b)
        assertFalse(b.shouldExtend())
        assertEquals(3, b.extensions)
        assertEquals(48, b.budget)           // 24 + 8*3, comfortably under 60
    }

    @Test fun `thrashing run gets no extension`() {
        val b = make(pref = 12)
        while (b.hasRemaining()) {
            b.consume()
            b.record(actionSuccess = false, urlChanged = false, repeated = false)
        }
        assertFalse("3+ consecutive failures must disqualify", b.shouldExtend())
    }

    @Test fun `repetitive run gets no extension`() {
        val b = make(pref = 12)
        while (b.hasRemaining()) {
            b.consume()
            b.record(actionSuccess = true, urlChanged = false, repeated = true)
        }
        assertFalse("repetition loop must disqualify", b.shouldExtend())
    }

    // -------------------------------------------------------------- scoring

    @Test fun `progress score is bounded`() {
        val b = make()
        b.record(true, true, false)
        assertTrue(b.progressScore() in 0.0..1.0)
        b.record(false, false, true)
        assertTrue(b.progressScore() in 0.0..1.0)
    }

    @Test fun `describe shows extension count when extended`() {
        val b = make(pref = 24)
        assertEquals("0/24", b.describe())
        repeat(24) { b.consume(); b.record(true, false, false) }
        b.extend()
        assertTrue(b.describe().contains("+1"))
    }
}
