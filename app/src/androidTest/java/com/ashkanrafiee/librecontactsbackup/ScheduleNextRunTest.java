package com.ashkanrafiee.librecontactsbackup;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Calendar;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Deterministic tests for the schedule next-run computation in
 * {@link AlarmScheduler}. The pure static helpers take an explicit "now" and
 * use the device's default timezone, so each expected value is derived from a
 * Calendar built the same way — making these tests timezone-independent.
 */
@RunWith(AndroidJUnit4.class)
public class ScheduleNextRunTest {

    private static long at(int year, int month, int day, int hour, int minute) {
        Calendar c = Calendar.getInstance();
        c.set(year, month, day, hour, minute, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private static int weekdayOf(int year, int month, int day) {
        Calendar c = Calendar.getInstance();
        c.set(year, month, day, 12, 0, 0);
        return c.get(Calendar.DAY_OF_WEEK);
    }

    @Test public void dailyPicksTodayWhenTimeStillAhead() {
        long now = at(2026, Calendar.SEPTEMBER, 11, 7, 0);
        assertEquals(at(2026, Calendar.SEPTEMBER, 11, 9, 30), AlarmScheduler.nextDailyRun(now, 9, 30));
    }

    @Test public void dailyRollsToTomorrowOnceTimePassed() {
        long now = at(2026, Calendar.SEPTEMBER, 11, 12, 0);
        assertEquals(at(2026, Calendar.SEPTEMBER, 12, 9, 30), AlarmScheduler.nextDailyRun(now, 9, 30));
    }

    @Test public void weeklyFiresLaterTodayWhenTimeStillAhead() {
        long now = at(2026, Calendar.SEPTEMBER, 11, 7, 0);
        int today = weekdayOf(2026, Calendar.SEPTEMBER, 11);
        assertEquals(at(2026, Calendar.SEPTEMBER, 11, 9, 30),
                AlarmScheduler.nextWeeklyRun(now, today, 9, 30));
    }

    @Test public void weeklyRollsToNextWeekOnceTimePassed() {
        long now = at(2026, Calendar.SEPTEMBER, 11, 12, 0);
        int today = weekdayOf(2026, Calendar.SEPTEMBER, 11);
        assertEquals(at(2026, Calendar.SEPTEMBER, 18, 9, 30),
                AlarmScheduler.nextWeeklyRun(now, today, 9, 30));
    }

    @Test public void weeklyPicksRequestedWeekday() {
        long now = at(2026, Calendar.SEPTEMBER, 11, 7, 0);
        int nextDay = weekdayOf(2026, Calendar.SEPTEMBER, 11) % 7 + 1;
        assertEquals(at(2026, Calendar.SEPTEMBER, 12, 9, 30),
                AlarmScheduler.nextWeeklyRun(now, nextDay, 9, 30));
    }

    @Test public void weeklyWrapsAcrossYearBoundary() {
        long now = at(2026, Calendar.DECEMBER, 31, 12, 0);
        int today = weekdayOf(2026, Calendar.DECEMBER, 31);
        assertEquals(at(2027, Calendar.JANUARY, 7, 9, 30),
                AlarmScheduler.nextWeeklyRun(now, today, 9, 30));
    }

    @Test public void weeklyResultIsOnRequestedWeekday() {
        long now = at(2026, Calendar.SEPTEMBER, 11, 7, 0);
        for (int weekday = Calendar.SUNDAY; weekday <= Calendar.SATURDAY; weekday++) {
            long result = AlarmScheduler.nextWeeklyRun(now, weekday, 9, 30);
            assertTrue(result > now);
            Calendar when = Calendar.getInstance();
            when.setTimeInMillis(result);
            assertEquals("day-of-week mismatch for " + weekday,
                    weekday, when.get(Calendar.DAY_OF_WEEK));
        }
    }

    @Test public void monthlyStaysInSameMonthWhenStillAhead() {
        long now = at(2026, Calendar.SEPTEMBER, 11, 12, 0);
        assertEquals(at(2026, Calendar.SEPTEMBER, 15, 9, 30),
                AlarmScheduler.nextMonthlyRun(now, 15, 9, 30));
    }

    @Test public void monthlyRollsToNextMonthOnceDayPassed() {
        long now = at(2026, Calendar.SEPTEMBER, 11, 7, 0);
        assertEquals(at(2026, Calendar.OCTOBER, 10, 9, 30),
                AlarmScheduler.nextMonthlyRun(now, 10, 9, 30));
    }

    @Test public void monthlyClampsShorterCurrentMonth() {
        // February 2026 has 28 days; a monthly 31st runs on the 28th.
        long now = at(2026, Calendar.FEBRUARY, 10, 12, 0);
        assertEquals(at(2026, Calendar.FEBRUARY, 28, 9, 30),
                AlarmScheduler.nextMonthlyRun(now, 31, 9, 30));
    }

    @Test public void monthlyClampIsPerMonthNotSticky() {
        // After February's clamped run passes, the next run is March 31st again.
        long now = at(2026, Calendar.FEBRUARY, 28, 12, 0);
        assertEquals(at(2026, Calendar.MARCH, 31, 9, 30),
                AlarmScheduler.nextMonthlyRun(now, 31, 9, 30));
    }

    @Test public void monthlyClampsShorterNextMonth() {
        // April 2026 has 30 days; the following monthly 31st clamps to April 30th.
        long now = at(2026, Calendar.MARCH, 31, 12, 0);
        assertEquals(at(2026, Calendar.APRIL, 30, 9, 30),
                AlarmScheduler.nextMonthlyRun(now, 31, 9, 30));
    }

    @Test public void monthlyClampHonorsLeapYears() {
        // 2028 is a leap year: February has 29 days.
        long now = at(2028, Calendar.FEBRUARY, 10, 12, 0);
        assertEquals(at(2028, Calendar.FEBRUARY, 29, 9, 30),
                AlarmScheduler.nextMonthlyRun(now, 31, 9, 30));
    }

    @Test public void monthlyResultIsStrictlyFuture() {
        long now = at(2026, Calendar.SEPTEMBER, 11, 12, 0);
        for (int day = 1; day <= 31; day++) {
            long result = AlarmScheduler.nextMonthlyRun(now, day, 9, 30);
            assertTrue("monthly result must be in the future", result > now);
        }
    }

    @Test public void weekdayNameIsLocalizedButNeverEmpty() {
        String name = AlarmScheduler.weekdayName(
                InstrumentationRegistry.getInstrumentation().getTargetContext(),
                Calendar.MONDAY);
        assertFalse(name.isEmpty());
        assertTrue(name.equals(new java.text.DateFormatSymbols(Locale.getDefault()).getWeekdays()[Calendar.MONDAY]));
    }
}