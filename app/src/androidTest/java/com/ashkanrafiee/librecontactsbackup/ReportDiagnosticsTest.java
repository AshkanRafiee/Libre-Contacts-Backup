package com.ashkanrafiee.librecontactsbackup;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Verifies diagnostic reports stay allow-listed and do not expose contact data or paths. */
public class ReportDiagnosticsTest {
    @Test public void diagnosticReport_containsOnlyTechnicalState() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String report = ReportDiagnostics.create(context);

        assertTrue(report.contains("report_format=2"));
        assertTrue(report.contains("app_version="));
        assertTrue(report.contains("android_version="));
        assertTrue(report.contains("android_api="));
        assertTrue(report.contains("device="));
        assertTrue(report.contains("rom="));
        assertTrue(report.contains("build_number="));
        assertTrue(report.contains("build_id="));
        assertTrue(report.contains("contacts_scope=full_provider"));
        assertTrue(report.contains("sim_contacts_scope=best_effort"));
        assertTrue(report.contains("contacts_read_permission="));
        assertTrue(report.contains("contacts_write_permission="));
        assertFalse(report.contains("content://"));
        assertFalse(report.contains("/data/"));
        assertFalse(report.contains("/storage/"));
        assertFalse(report.contains("lastRunMessage"));
    }

    @Test public void diagnosticReport_hasOnlyTheAllowListedFields() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Set<String> expected = new HashSet<>();
        String[] fields = {
                "report_format", "app_version", "android_version", "android_api", "device", "rom",
                "build_number", "build_id", "language", "contacts_scope", "sim_contacts_scope",
                "contacts_read_permission", "contacts_write_permission", "folder_configured",
                "encrypted_backups", "scheduled_backups",
                "retention_mode", "last_run_status"
        };
        for (String field : fields) expected.add(field);

        String[] lines = ReportDiagnostics.create(context).split("\\n");
        assertEquals(expected.size() + 1, lines.length); // heading plus one line per field
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            int separator = line.indexOf('=');
            assertTrue("diagnostic field has no separator: " + line, separator > 0);
            assertTrue("unexpected diagnostic field: " + line,
                    expected.remove(line.substring(0, separator)));
        }
        assertTrue("missing diagnostic field(s): " + expected, expected.isEmpty());
    }

    @Test public void diagnosticReport_normalizesUnknownRetentionMode() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences prefs = BackupManager.prefs(context);
        String oldMode = prefs.getString("retentionMode", null);
        try {
            prefs.edit().putString("retentionMode", "content://private-folder").commit();
            String report = ReportDiagnostics.create(context);
            assertTrue(report.contains("retention_mode=simple"));
            assertFalse(report.contains("content://private-folder"));
        } finally {
            if (oldMode == null) prefs.edit().remove("retentionMode").commit();
            else prefs.edit().putString("retentionMode", oldMode).commit();
        }
    }

    @Test public void diagnosticReport_doesNotExposeConfiguredFolderOrRunMessage() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences prefs = BackupManager.prefs(context);
        String oldFolder = prefs.getString("folder", null);
        String oldMessage = prefs.getString("lastRunMessage", null);
        boolean hadSuccess = prefs.contains("lastRunSuccess");
        boolean oldSuccess = prefs.getBoolean("lastRunSuccess", false);
        boolean hadRun = prefs.contains("lastRun");
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
            if (hadSuccess) restore.putBoolean("lastRunSuccess", oldSuccess); else restore.remove("lastRunSuccess");
            if (hadRun) restore.putLong("lastRun", oldRun); else restore.remove("lastRun");
            restore.commit();
        }
    }

    @Test public void reportTemplate_hasFillInSections() {
        String body = InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getString(R.string.report_issue_body);
        assertTrue(body.contains("\n1.\n2.\n3."));
        assertTrue(body.split("\n\n").length >= 4);
    }
}
