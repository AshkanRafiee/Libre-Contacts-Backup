package com.ashkanrafiee.librecontactsbackup;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Verifies diagnostic reports stay allow-listed and do not expose contact data or paths. */
public class ReportDiagnosticsTest {
    @Test public void diagnosticReport_containsOnlyTechnicalState() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String report = ReportDiagnostics.create(context);

        assertTrue(report.contains("report_format=1"));
        assertTrue(report.contains("app_version="));
        assertTrue(report.contains("android_version="));
        assertTrue(report.contains("android_api="));
        assertTrue(report.contains("device="));
        assertTrue(report.contains("rom="));
        assertTrue(report.contains("build_number="));
        assertTrue(report.contains("build_id="));
        assertTrue(report.contains("contacts_scope=full_provider"));
        assertFalse(report.contains("content://"));
        assertFalse(report.contains("/data/"));
        assertFalse(report.contains("/storage/"));
        assertFalse(report.contains("lastRunMessage"));
    }

    @Test public void diagnosticReport_doesNotExposeConfiguredFolderOrRunMessage() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences prefs = BackupManager.prefs(context);
        String oldFolder = prefs.getString("folder", null);
        String oldMessage = prefs.getString("lastRunMessage", null);
        boolean oldSuccess = prefs.getBoolean("lastRunSuccess", false);
        long oldRun = prefs.getLong("lastRun", 0);
        try {
            prefs.edit().putString("folder", "content://contacts/private-folder")
                    .putString("lastRunMessage", "Alice Example +1 555-0100")
                    .putBoolean("lastRunSuccess", false)
                    .putLong("lastRun", 1)
                    .commit();
            String report = ReportDiagnostics.create(context);
            assertFalse(report.contains("content://contacts/private-folder"));
            assertFalse(report.contains("Alice Example"));
            assertFalse(report.contains("555-0100"));
        } finally {
            SharedPreferences.Editor restore = prefs.edit();
            if (oldFolder == null) restore.remove("folder"); else restore.putString("folder", oldFolder);
            if (oldMessage == null) restore.remove("lastRunMessage"); else restore.putString("lastRunMessage", oldMessage);
            restore.putBoolean("lastRunSuccess", oldSuccess).putLong("lastRun", oldRun).commit();
        }
    }

    @Test public void reportTemplate_hasFillInSections() {
        String body = InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getString(R.string.report_issue_body);
        assertTrue(body.contains("Problem description:"));
        assertTrue(body.contains("Steps to reproduce:"));
        assertTrue(body.contains("Additional details:"));
    }
}
