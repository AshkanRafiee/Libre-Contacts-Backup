package com.ashkanrafiee.librecontactsbackup.retention;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Decides which stored backup sets a retention policy keeps. Pure computation
 * with no Android dependencies, so it can be exercised deterministically.
 *
 * SIMPLE mode keeps the newest {@code simpleKeep} sets (or all of them when
 * the policy says "keep all").
 *
 * PERIODIC mode implements the classic Grandfather–Father–Son scheme: going
 * newest to oldest, it keeps one set per day for the most recent
 * {@code dailyKeep} days, one set per week for the most recent
 * {@code weeklyKeep} weeks, and one set per month for the most recent
 * {@code monthlyKeep} months. A set can satisfy more than one tier, so the
 * number kept is at most the sum of the three counts. The result is a dense
 * recent history that thins out toward the past — the "backups of increasing
 * age" a user expects. If every tier is disabled, the newest set is kept as a
 * safety floor so an all-zero configuration can never erase every backup.
 */
public final class RetentionDecider {

    public static Set<String> decide(List<StoredBackup> backups, RetentionPolicy policy, LocalDateTime now) {
        if (policy.mode == RetentionPolicy.Mode.PERIODIC) return periodic(backups, policy, now.toLocalDate());
        return simple(backups, policy);
    }

    private static Set<String> simple(List<StoredBackup> backups, RetentionPolicy policy) {
        Set<String> keep = new HashSet<>();
        if (policy.keepAll()) {
            for (StoredBackup backup : backups) keep.add(backup.id);
            return keep;
        }
        List<StoredBackup> sorted = newestFirst(backups);
        for (int i = 0; i < sorted.size() && i < policy.simpleKeep; i++) keep.add(sorted.get(i).id);
        return keep;
    }

    private static Set<String> periodic(List<StoredBackup> backups, RetentionPolicy policy, LocalDate now) {
        List<StoredBackup> sorted = newestFirst(backups);
        if (sorted.isEmpty()) return new HashSet<>();
        if (policy.dailyKeep + policy.weeklyKeep + policy.monthlyKeep == 0) {
            Set<String> safetyFloor = new HashSet<>();
            safetyFloor.add(sorted.get(0).id);
            return safetyFloor;
        }
        Set<String> keep = new HashSet<>();
        if (policy.dailyKeep > 0) pickByFrame(sorted, keep, now, policy.dailyKeep, Frame.DAY);
        if (policy.weeklyKeep > 0) pickByFrame(sorted, keep, now, policy.weeklyKeep, Frame.WEEK);
        if (policy.monthlyKeep > 0) pickByFrame(sorted, keep, now, policy.monthlyKeep, Frame.MONTH);
        return keep;
    }

    private enum Frame { DAY, WEEK, MONTH }

    /** Adds the newest backup of each frame in [0, count) to {@code keep}. */
    private static void pickByFrame(List<StoredBackup> sorted, Set<String> keep, LocalDate now, int count, Frame frame) {
        Set<Long> seen = new HashSet<>();
        for (StoredBackup backup : sorted) {
            long index = indexOf(backup.at.toLocalDate(), now, frame);
            if (index < 0) {
                // Backups dated strictly after "now" (clock moved forward) are
                // kept outright — they never displace the real newest backups
                // of the current frame, and they are never deleted.
                keep.add(backup.id);
                continue;
            }
            if (index >= count) continue;
            if (seen.add(index)) keep.add(backup.id);
        }
    }

    // 0 = today / current week / current month, 1 = the previous one, and so
    // on; negative when the backup is dated after "now".
    private static long indexOf(LocalDate backup, LocalDate now, Frame frame) {
        switch (frame) {
            case WEEK: return ChronoUnit.WEEKS.between(weekStart(backup), weekStart(now));
            case MONTH: return ChronoUnit.MONTHS.between(YearMonth.from(backup), YearMonth.from(now));
            default: return ChronoUnit.DAYS.between(backup, now);
        }
    }

    private static LocalDate weekStart(LocalDate date) { return date.with(DayOfWeek.MONDAY); }

    private static List<StoredBackup> newestFirst(List<StoredBackup> backups) {
        List<StoredBackup> sorted = new ArrayList<>(backups);
        sorted.sort((a, b) -> {
            int byTime = b.at.compareTo(a.at);
            return byTime != 0 ? byTime : a.id.compareTo(b.id);
        });
        return sorted;
    }
}