package com.cubit.game

import org.junit.Assert.assertEquals
import org.junit.Test

class ReminderTest {
    @Test fun titles() {
        assertEquals("Your 5-day streak ends tonight", reminderTitle(5, 19))
        assertEquals("Keep your 5-day streak going", reminderTitle(5, 8))
        assertEquals("Today's daily cube is ready", reminderTitle(0, 19))
        assertEquals("Advent door 3 is open too.", reminderText(0, 3))
    }
}
