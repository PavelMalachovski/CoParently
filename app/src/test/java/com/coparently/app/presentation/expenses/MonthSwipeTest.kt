package com.coparently.app.presentation.expenses

import org.junit.Assert.assertEquals
import org.junit.Test

class MonthSwipeTest {

    private val threshold = 150f

    @Test
    fun `a drag shorter than the threshold changes nothing`() {
        assertEquals(MonthStep.NONE, MonthSwipe.resolve(dragPx = 40f, thresholdPx = threshold))
        assertEquals(MonthStep.NONE, MonthSwipe.resolve(dragPx = -40f, thresholdPx = threshold))
    }

    @Test
    fun `dragging left pages forward`() {
        assertEquals(MonthStep.NEXT, MonthSwipe.resolve(dragPx = -220f, thresholdPx = threshold))
    }

    @Test
    fun `dragging right pages back`() {
        assertEquals(MonthStep.PREVIOUS, MonthSwipe.resolve(dragPx = 220f, thresholdPx = threshold))
    }

    @Test
    fun `exactly at the threshold counts as a swipe`() {
        assertEquals(MonthStep.NEXT, MonthSwipe.resolve(dragPx = -150f, thresholdPx = threshold))
        assertEquals(MonthStep.PREVIOUS, MonthSwipe.resolve(dragPx = 150f, thresholdPx = threshold))
    }

    @Test
    fun `a tap with no movement changes nothing`() {
        assertEquals(MonthStep.NONE, MonthSwipe.resolve(dragPx = 0f, thresholdPx = threshold))
    }
}
