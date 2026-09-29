package com.ashkanrafiee.librecontactsbackup;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;

/**
 * Delivers automatic backups on the schedule armed by {@link AlarmScheduler}.
 *
 * The next occurrence is re-armed <em>before</em> the backup work starts (see
 * {@link BackupManager#executeScheduledRun}), so even a run that is killed by
 * the system midway — broadcast timeout, low memory, an aggressive OEM — still
 * leaves the schedule intact for the following occurrence. A partial wake lock
 * keeps the CPU up while the run is in flight, and the blocking
 * DocumentsProvider folder check runs here on a background thread rather than
 * on the broadcast's main thread, so a slow provider cannot cause an ANR.
 */
public class BackupAlarmReceiver extends BroadcastReceiver {
    private static final long WAKE_LOCK_TIMEOUT_MS = 120_000;

    @Override public void onReceive(Context rawContext, Intent intent) {
        Context context = LocaleHelper.wrap(rawContext);
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) { AlarmScheduler.scheduleNext(context); return; }
        PendingResult pending = goAsync();
        PowerManager.WakeLock wakeLock = acquire(context);
        new Thread(() -> {
            try {
                BackupManager.executeScheduledRun(context);
            } finally {
                releaseQuietly(wakeLock);
                pending.finish();
            }
        }).start();
    }

    private static PowerManager.WakeLock acquire(Context context) {
        PowerManager.WakeLock wakeLock = ((PowerManager) context.getSystemService(Context.POWER_SERVICE))
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, context.getPackageName() + ":scheduled_backup");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
        return wakeLock;
    }

    private static void releaseQuietly(PowerManager.WakeLock wakeLock) {
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) { }
    }
}
