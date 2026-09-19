package com.zuoqirun.lyricscompanion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Calendar;

import org.junit.Test;

/** The dark window behind the scheduled theme (issue #34). */
public class DayNightScheduleTest {
    private static final int EVENING = 19 * 60;
    private static final int MORNING = 7 * 60;

    @Test public void windowAcrossMidnightIsDarkInTheEveningAndBeforeDawn() {
        assertTrue(DayNightSchedule.isDarkMinute(19 * 60, EVENING, MORNING));
        assertTrue(DayNightSchedule.isDarkMinute(23 * 60 + 59, EVENING, MORNING));
        assertTrue(DayNightSchedule.isDarkMinute(0, EVENING, MORNING));
        assertTrue(DayNightSchedule.isDarkMinute(6 * 60 + 59, EVENING, MORNING));
        assertFalse(DayNightSchedule.isDarkMinute(7 * 60, EVENING, MORNING));
        assertFalse(DayNightSchedule.isDarkMinute(12 * 60, EVENING, MORNING));
        assertFalse(DayNightSchedule.isDarkMinute(18 * 60 + 59, EVENING, MORNING));
    }

    @Test public void windowInsideOneDayDoesNotWrap() {
        int start = 13 * 60;
        int end = 15 * 60;
        assertFalse(DayNightSchedule.isDarkMinute(12 * 60 + 59, start, end));
        assertTrue(DayNightSchedule.isDarkMinute(13 * 60, start, end));
        assertTrue(DayNightSchedule.isDarkMinute(14 * 60 + 59, start, end));
        assertFalse(DayNightSchedule.isDarkMinute(15 * 60, start, end));
        assertFalse(DayNightSchedule.isDarkMinute(2 * 60, start, end));
    }

    /** start == end is how the schedule is switched off without losing the two times. */
    @Test public void anEmptyWindowIsNeverDark() {
        assertFalse(DayNightSchedule.isDarkMinute(0, 0, 0));
        assertFalse(DayNightSchedule.isDarkMinute(12 * 60, 9 * 60, 9 * 60));
        assertFalse(DayNightSchedule.isDarkMinute(23 * 60, 9 * 60, 9 * 60));
    }

    @Test public void minuteOfDayUsesTheLocalClock() {
        Calendar calendar = Calendar.getInstance();
        calendar.set(2026, Calendar.SEPTEMBER, 19, 21, 30, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        assertEquals(21 * 60 + 30, DayNightSchedule.minuteOfDay(calendar.getTimeInMillis()));
    }

    @Test public void outOfRangeMinutesWrapIntoTheDay() {
        assertEquals(0, DayNightSchedule.wrap(24 * 60));
        assertEquals(23 * 60 + 30, DayNightSchedule.wrap(-30));
        assertTrue(DayNightSchedule.isDarkMinute(23 * 60, EVENING + 24 * 60, MORNING));
    }
}
