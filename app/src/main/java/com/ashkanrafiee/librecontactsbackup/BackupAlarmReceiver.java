package com.ashkanrafiee.librecontactsbackup;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.util.Log;

/**
 * Delivers automatic backups on the schedule armed by {@link AlarmScheduler}.
 *
 * The next occurrence is re-armed synchronously here in {@code onReceive},
 * before any worker starts (and again at the start of
 * {@link BackupManager#executeScheduledRun} for older installs), so even a
 * run that is killed before its thread starts cannot take the schedule down
 * with it. An attempt marker is also persisted synchronously, so a later
 * kill still leaves proof the alarm was delivered. A partial wake lock keeps
 * the CPU up while the run is in flight, and the blocking DocumentsProvider
 * folder check runs on a background thread rather than on the broadcast's
 * main thread, so a slow provider cannot cause an ANR.
 */
public class BackupAlarmReceiver extends BroadcastReceiver {
    private static final long WAKE_LOCK_TIMEOUT_MS = 120_000;

    @Override public void onReceive(Context rawContext, Intent intent) {
        Context context = LocaleHelper.wrap(rawContext);
        String action = intent != null ? intent.getAction() : null;
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                || Intent.ACTION_TIME_CHANGED.equals(action) || Intent.ACTION_TIMEZONE_CHANGED.equals(action)) { AlarmScheduler.scheduleNext(context); return; }
        // Re-arm first, on this thread: the worker may never get to run.
        try { AlarmScheduler.scheduleNext(context); }
        catch (Exception e) { Log.w("LibreContactsBackup", "Early schedule re-arm failed; worker will retry", e); }
        try { BackupManager.recordScheduleAttempt(context); }
        catch (Exception e) { Log.w("LibreContactsBackup", "Could not record scheduled attempt", e); }
        PendingResult pending = goAsync();
        PowerManager.WakeLock wakeLock = null;
        try { wakeLock = acquire(context); }
        catch (RuntimeException e) { Log.w("LibreContactsBackup", "Could not acquire wake lock for the scheduled backup", e); }
        final PowerManager.WakeLock lock = wakeLock;
        new Thread(() -> {
            try {
                BackupManager.executeScheduledRun(context);
            } finally {
                releaseQuietly(lock);
                pending.finish();
            }
        }).start();
    }

    // The lock is reference-counted (the default): if a second alarm delivery
    // arrives while the first is still running, each delivery holds its own
    // reference and releases it on completion, so the first run to finish can
    // never cut the second run's wake lock short.
    private static PowerManager.WakeLock acquire(Context context) {
        PowerManager.WakeLock wakeLock = ((PowerManager) context.getSystemService(Context.POWER_SERVICE))
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, context.getPackageName() + ":scheduled_backup");
        wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
        return wakeLock;
    }

    private static void releaseQuietly(PowerManager.WakeLock wakeLock) {
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) { }
    }
}
