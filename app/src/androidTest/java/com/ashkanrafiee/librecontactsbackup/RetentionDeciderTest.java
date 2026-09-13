package com.ashkanrafiee.librecontactsbackup;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.ashkanrafiee.librecontactsbackup.retention.RetentionDecider;
import com.ashkanrafiee.librecontactsbackup.retention.RetentionPolicy;
import com.ashkanrafiee.librecontactsbackup.retention.StoredBackup;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Deterministic tests for {@link RetentionDecider}. The decider is pure and
 * timezone-free (all times are wall-clock LocalDateTimes, exactly what the
 * backup filenames encode), so the expected values are computed directly.
 */
@RunWith(AndroidJUnit4.class)
public class RetentionDeciderTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 13, 12, 0);

    private static StoredBackup stored(String id, int month, int day, int hour) {
        return new StoredBackup(id, LocalDateTime.of(2026, month, day, hour, 0));
    }

    private static Set<String> ids(StoredBackup... backups) {
        Set<String> result = new HashSet<>();
        for (StoredBackup backup : backups) result.add(backup.id);
        return result;
    }

    private static Set<String> decide(List<StoredBackup> backups, RetentionPolicy policy) {
        return RetentionDecider.decide(backups, policy, NOW);
    }

    private static void assertKeptExactly(List<StoredBackup> backups, RetentionPolicy policy, Set<String> expected) {
        assertEquals(expected, decide(backups, policy));
    }

    @Test public void simpleKeepsNewestSets() {
        List<StoredBackup> backups = Arrays.asList(
                stored("old", 9, 9, 10), stored("mid", 9, 10, 10),
                stored("newer", 9, 11, 10), stored("newest", 9, 13, 10));
        RetentionPolicy policy = new RetentionPolicy(RetentionPolicy.Mode.SIMPLE, 2, 0, 0, 0);
        assertKeptExactly(backups, policy, ids(stored("newest", 9, 13, 10), stored("newer", 9, 11, 10)));
    }

    @Test public void simpleKeepAllKeepsEverything() {
        List<StoredBackup> backups = Arrays.asList(
                stored("old", 9, 9, 10), stored("newest", 9, 13, 10));
        RetentionPolicy policy = new RetentionPolicy(RetentionPolicy.Mode.SIMPLE, RetentionPolicy.KEEP_ALL, 0, 0, 0);
        assertKeptExactly(backups, policy, ids(stored("old", 9, 9, 10), stored("newest", 9, 13, 10)));
    }

    @Test public void allTiersDisabledKeepsNewestOnly() {
        // A zeroed-out periodic config must never erase every backup.
        List<StoredBackup> backups = Arrays.asList(
                stored("old", 9, 9, 10), stored("newest", 9, 13, 10));
        RetentionPolicy policy = new RetentionPolicy(RetentionPolicy.Mode.PERIODIC, 5, 0, 0, 0);
        assertKeptExactly(backups, policy, ids(stored("newest", 9, 13, 10)));
    }

    @Test public void periodicDailyKeepsNewestPerDay() {
        List<StoredBackup> backups = Arrays.asList(
                stored("today", 9, 13, 22), stored("yesterday_late", 9, 12, 16), stored("yesterday_early", 9, 12, 9),
                stored("too_old", 9, 11, 10));
        RetentionPolicy policy = new RetentionPolicy(RetentionPolicy.Mode.PERIODIC, 5, 2, 0, 0);
        // Frames 0 and 1 cover today and yesterday; only the newest per day survives.
        assertKeptExactly(backups, policy, ids(stored("today", 9, 13, 22), stored("yesterday_late", 9, 12, 16)));
    }

    @Test public void periodicWeeklyKeepsNewestPerWeek() {
        // 2026-09-13 is a Sunday; weeks run Monday to Sunday.
        List<StoredBackup> backups = Arrays.asList(
                stored("current_week_newer", 9, 13, 22), stored("current_week_older", 9, 7, 9),
                stored("last_week", 9, 6, 10), stored("two_weeks_newer", 8, 30, 15),
                stored("two_weeks_older", 8, 29, 9), stored("three_weeks", 8, 23, 10));
        RetentionPolicy policy = new RetentionPolicy(RetentionPolicy.Mode.PERIODIC, 5, 0, 3, 0);
        // Frames 0..2 = the three most recent weeks; oldest survivor from frame 2 wins.
        assertKeptExactly(backups, policy, ids(
                stored("current_week_newer", 9, 13, 22), stored("last_week", 9, 6, 10),
                stored("two_weeks_newer", 8, 30, 15)));
    }

    @Test public void periodicMonthlyKeepsNewestPerMonth() {
        List<StoredBackup> backups = Arrays.asList(
                stored("sep", 9, 1, 10), stored("aug", 8, 20, 10),
                stored("jul", 7, 10, 10), stored("jun", 6, 5, 10));
        RetentionPolicy policy = new RetentionPolicy(RetentionPolicy.Mode.PERIODIC, 5, 0, 0, 2);
        assertKeptExactly(backups, policy, ids(stored("sep", 9, 1, 10), stored("aug", 8, 20, 10)));
    }

    @Test public void periodicCombinesAllTiers() {
        // daily=2 covers today + yesterday; weekly=2 adds the newest of the
        // previous week; monthly=2 adds the newest of last month.
        List<StoredBackup> backups = Arrays.asList(
                stored("today", 9, 13, 22),
                stored("yesterday_newer", 9, 12, 16), stored("yesterday_older", 9, 12, 8),
                stored("two_days", 9, 11, 10),
                stored("last_week", 9, 6, 10),
                stored("last_month", 8, 25, 10),
                stored("old", 7, 1, 10));
        RetentionPolicy policy = new RetentionPolicy(RetentionPolicy.Mode.PERIODIC, 5, 2, 2, 2);
        assertKeptExactly(backups, policy, ids(
                stored("today", 9, 13, 22), stored("yesterday_newer", 9, 12, 16),
                stored("last_week", 9, 6, 10), stored("last_month", 8, 25, 10)));
    }

    @Test public void periodicCountIsBoundedByTierSum() {
        List<StoredBackup> backups = new java.util.ArrayList<>();
        for (int month = 6; month <= 9; month++) {
            for (int day = 5; day <= 13; day++) {
                backups.add(stored("m" + month + "_d" + day, month, day, 10));
            }
        }
        RetentionPolicy policy = new RetentionPolicy(RetentionPolicy.Mode.PERIODIC, 5, 2, 2, 2);
        assertTrue(decide(backups, policy).size() <= 6);
    }

    @Test public void futureDatedBackupsAreAlwaysKept() {
        List<StoredBackup> backups = Arrays.asList(
                stored("today", 9, 13, 10), stored("tomorrow", 9, 14, 8));
        RetentionPolicy policy = new RetentionPolicy(RetentionPolicy.Mode.PERIODIC, 5, 1, 0, 0);
        // A forward clock must neither delete the future backup nor the real one.
        assertKeptExactly(backups, policy, ids(stored("today", 9, 13, 10), stored("tomorrow", 9, 14, 8)));
    }

    @Test public void periodicKeepAllKeepsEverything() {
        List<StoredBackup> backups = new java.util.ArrayList<>();
        for (int month = 1; month <= 9; month++) {
            for (int day = 5; day <= 13; day++) {
                backups.add(stored("m" + month + "_d" + day, month, day, 10));
            }
        }
        RetentionPolicy policy = new RetentionPolicy(RetentionPolicy.Mode.PERIODIC, 5, RetentionPolicy.KEEP_ALL, 0, 0);
        // The "everything" preset must never delete even a single old set.
        assertKeptExactly(backups, policy, new java.util.HashSet<>(backups.stream().map(b -> b.id).collect(java.util.stream.Collectors.toList())));
    }

    @Test public void simpleKeepAllDoesNotLeakIntoPeriodicMode() {
        // "Keep all" chosen in SIMPLE mode must not disable retention once the
        // user switches to an increasing-age configuration.
        List<StoredBackup> backups = Arrays.asList(
                stored("old", 9, 1, 10), stored("mid", 9, 10, 10), stored("newest", 9, 13, 10));
        RetentionPolicy policy = new RetentionPolicy(RetentionPolicy.Mode.PERIODIC, RetentionPolicy.KEEP_ALL, 7, 0, 0);
        // The stale simpleKeep of KEEP_ALL must not leak in: the September
        // backlog outside the daily window is still trimmed.
        assertKeptExactly(backups, policy, ids(stored("mid", 9, 10, 10), stored("newest", 9, 13, 10)));
    }

    @Test public void emptySetKeepsNothing() {
        RetentionPolicy policy = new RetentionPolicy(RetentionPolicy.Mode.PERIODIC, 5, 7, 4, 3);
        assertTrue(decide(java.util.Collections.emptyList(), policy).isEmpty());
    }
}