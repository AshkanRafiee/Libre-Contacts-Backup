package com.ashkanrafiee.librecontactsbackup;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.DocumentsContract;

public class BackupAlarmReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context rawContext, Intent intent) {
        Context context = LocaleHelper.wrap(rawContext);
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) { AlarmScheduler.scheduleNext(context); return; }
        java.util.List<String> issues = new java.util.ArrayList<>();
        java.util.List<String> actions = new java.util.ArrayList<>();
        String folder = BackupManager.folder(context);
        if (folder.isEmpty()) { issues.add(context.getString(R.string.issue_folder_not_configured)); actions.add("folder_missing"); }
        else if (!isFolderAccessible(context, folder)) { issues.add(context.getString(R.string.issue_folder_not_accessible)); actions.add("folder_revoked"); }
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) { issues.add(context.getString(R.string.issue_permission_not_granted)); actions.add("permission_missing"); }
        if (!issues.isEmpty()) {
            String msg = context.getString(R.string.scheduled_backup_skipped, String.join(", ", issues));
            MainActivity.showScheduledNotification(context, msg, false, String.join(",", actions));
            AlarmScheduler.scheduleNext(context);
            return;
        }
        PendingResult pending = goAsync(); new Thread(() -> { try { BackupManager.BackupOutcome result = BackupManager.runBackup(context, false); MainActivity.showScheduledNotification(context, result.message, result.success); } finally { AlarmScheduler.scheduleNext(context); pending.finish(); } }).start();
    }
    private static boolean isFolderAccessible(Context context, String folder) {
        try {
            Uri tree = Uri.parse(folder);
            Uri document = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
            try (android.database.Cursor cursor = context.getContentResolver().query(document,
                    new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
                return true;
            }
        } catch (Exception e) { return false; }
    }
}
