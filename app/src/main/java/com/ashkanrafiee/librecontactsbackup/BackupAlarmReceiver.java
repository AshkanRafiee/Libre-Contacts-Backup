package com.ashkanrafiee.librecontactsbackup;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BackupAlarmReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) { AlarmScheduler.restore(context); return; }
        PendingResult pending = goAsync(); new Thread(() -> { try { String result = BackupManager.runBackup(context, false); MainActivity.showScheduledNotification(context, result); } finally { AlarmScheduler.scheduleNext(context); pending.finish(); } }).start();
    }
}
