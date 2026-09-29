package com.ashkanrafiee.librecontactsbackup;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Verifies that automatic backup runs record their outcome in shared
 * preferences, so the app can surface skipped/failed runs to the user instead
 * of letting them fail silently. These tests deliberately exercise the
 * deterministic, hermetic failure path (no folder configured), which needs no
 * SAF permission or contact data: a scheduled run that cannot complete must
 * never pass without leaving a trace again.
 */
@RunWith(AndroidJUnit4.class)
public class ScheduledBackupStatusTest {

    private Context target;
    private SharedPreferences prefs;
    private String savedFolder;

    @Before public void setUp() {
        target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        prefs = BackupManager.prefs(target);
        savedFolder = prefs.getString("folder", "");
        prefs.edit()
                .remove("folder")
                .remove("lastRun")
                .remove("lastRunSuccess")
                .remove("lastRunMessage")
                .remove("last")
                .apply();
    }

    @After public void tearDown() {
        prefs.edit().putString("folder", savedFolder).apply();
    }

    @Test public void runBackupWithoutFolderFailsLoudlyAndIsRecorded() {
        BackupManager.BackupOutcome outcome = BackupManager.runBackup(target, false);
        assertFalse("A backup without a folder cannot succeed", outcome.success);
        assertFalse("The failure must carry a message", outcome.message.isEmpty());
        assertTrue("A failed run must be recorded", prefs.getLong("lastRun", 0) > 0);
        assertFalse("A failed run must not be marked successful", prefs.getBoolean("lastRunSuccess", true));
        assertFalse("A failed run must carry its message", prefs.getString("lastRunMessage", "").isEmpty());
    }

    @Test public void scheduledRunWithoutFolderIsSkippedAndRecorded() {
        BackupManager.executeScheduledRun(target);
        assertTrue("A skipped scheduled run must be recorded", prefs.getLong("lastRun", 0) > 0);
        assertFalse("A skipped run must not be marked successful", prefs.getBoolean("lastRunSuccess", true));
        String message = prefs.getString("lastRunMessage", "");
        String template = target.getString(R.string.scheduled_backup_skipped);
        String prefix = template.substring(0, template.indexOf("%1$s"));
        assertTrue("The skip reason must be surfaced to the user", message.startsWith(prefix));
    }
}