package com.ashkanrafiee.librecontactsbackup;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Verifies that automatic backup runs record their outcome in shared
 * preferences, so the app can surface skipped/failed runs to the user instead
 * of letting them fail silently. These tests deliberately exercise the
 * deterministic, hermetic failure path (no folder configured), which needs no
 * SAF permission or contact data: a scheduled run that cannot complete must
 * never pass without leaving a trace again.
 *
 * Every preference this test reads or writes (the folder plus the last-run
 * outcome keys) is snapshotted before and restored after, so the suite leaves
 * the device exactly as it found it. The restore also uses commit() — like a
 * real backup run, the recorded outcome must survive a process kill, and
 * waiting for the write keeps the next test from racing an apply() flush.
 */
@RunWith(AndroidJUnit4.class)
public class ScheduledBackupStatusTest {

    private static final String[] TOUCHED_KEYS = {"folder", "lastRun", "lastRunSuccess", "lastRunMessage"};

    private Context target;
    private SharedPreferences prefs;
    private final Map<String, Object> savedValues = new HashMap<>();

    @Before public void setUp() {
        target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        prefs = BackupManager.prefs(target);
        SharedPreferences.Editor clear = prefs.edit();
        for (String key : TOUCHED_KEYS) {
            if (prefs.contains(key)) savedValues.put(key, prefs.getAll().get(key));
            clear.remove(key);
        }
        assertTrue("Preparing the test must not lose the device state", clear.commit());
    }

    @After public void tearDown() {
        SharedPreferences.Editor restore = prefs.edit();
        for (String key : TOUCHED_KEYS) {
            if (savedValues.containsKey(key)) {
                Object value = savedValues.get(key);
                if (value instanceof Boolean) restore.putBoolean(key, (Boolean) value);
                else if (value instanceof Long) restore.putLong(key, (Long) value);
                else restore.putString(key, (String) value);
            } else {
                restore.remove(key);
            }
        }
        assertTrue("The device state must be restored even on failure", restore.commit());
        savedValues.clear();
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
        assertTrue("The skip reason must contain the not-configured issue",
                message.contains(target.getString(R.string.issue_folder_not_configured)));
    }
}