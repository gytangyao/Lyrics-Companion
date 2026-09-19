package com.zuoqirun.lyricscompanion;

import java.util.Calendar;

/**
 * The clock behind the scheduled light/dark theme (issue #34).
 *
 * <p>The app could only follow the system's night mode, which a car head unit often leaves on
 * "always light". A dark window that the user sets themselves — 19:00 to 07:00 by default — lets
 * the lyrics change with the time of day instead.
 *
 * <p>Kept free of Android types so the rule itself is unit-testable; the caller passes the wall
 * clock in.
 */
final class DayNightSchedule {
    static final int MINUTES_PER_DAY = 24 * 60;

    private DayNightSchedule() {}

    /** Minutes since midnight of {@code epochMillis}, in the device's own time zone. */
    static int minuteOfDay(long epochMillis) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(epochMillis);
        return calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE);
    }

    /**
     * True while {@code minuteOfDay} is inside the dark window.
     *
     * <p>A window that runs past midnight is the normal case here (19:00 → 07:00), so the test
     * wraps around instead of being a plain interval check. An empty window ({@code start ==
     * end}) never turns dark, which is how the user switches the schedule off without losing the
     * two times they picked.
     */
    static boolean isDarkMinute(int minuteOfDay, int startMinute, int endMinute) {
        int start = wrap(startMinute);
        int end = wrap(endMinute);
        int now = wrap(minuteOfDay);
        if (start == end) return false;
        return start < end ? now >= start && now < end : now >= start || now < end;
    }

    /** Minutes of the day clamped into {@code [0, 1440)}. */
    static int wrap(int minute) {
        int wrapped = minute % MINUTES_PER_DAY;
        return wrapped < 0 ? wrapped + MINUTES_PER_DAY : wrapped;
    }
}
