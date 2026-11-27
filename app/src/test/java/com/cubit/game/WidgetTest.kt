package com.cubit.game

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetTest {
    @Test fun lines() {
        assertEquals("Daily done ✓" to "See you tomorrow for the next cube.", widgetLines(4, true, null))
        assertEquals("Daily cube waiting" to "Solve it to make it 5 days. Door 3 too.", widgetLines(4, false, 3))
        assertEquals("Today's daily cube" to "Same cube for everyone. Tap to play.", widgetLines(0, false, null))
    }
}
