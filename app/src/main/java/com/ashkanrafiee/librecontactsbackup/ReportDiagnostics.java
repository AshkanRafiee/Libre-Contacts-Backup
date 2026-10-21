package com.ashkanrafiee.librecontactsbackup;

import android.Manifest;
import android.content.Context;
import android.os.Build;

import java.util.Locale;

/** Creates allow-listed diagnostics without exposing contact data or storage paths. */
final class ReportDiagnostics {
    private ReportDiagnostics() {}

    static String create(Context context) {
        StringBuilder report = new StringBuilder();
        report.append("Libre Contacts Backup diagnostic report\n");
        report.append("report_format=1\n");
        report.append("app_version=").append(safe(appVersion(context))).append('\n');
        report.append("android_version=").append(safe(Build.VERSION.RELEASE)).append('\n');
        report.append("android_api=").append(Build.VERSION.SDK_INT).append('\n');
        report.append("device=").append(safe(deviceName())).append('\n');
        report.append("rom=").append(safe(romName())).append('\n');
        report.append("build_number=").append(safe(Build.DISPLAY)).append('\n');
        report.append("build_id=").append(safe(Build.ID)).append('\n');
        report.append("language=").append(safe(Locale.getDefault().toLanguageTag())).append('\n');
        report.append("contacts_scope=full_provider\n");
        report.append("sim_contacts_scope=enabled\n");
        report.append("contacts_permission=")
                .append(context.checkSelfPermission(Manifest.permission.READ_CONTACTS)
                        == android.content.pm.PackageManager.PERMISSION_GRANTED)
                .append('\n');
        report.append("folder_configured=").append(!BackupManager.folder(context).isEmpty()).append('\n');
        report.append("encrypted_backups=")
                .append(BackupManager.prefs(context).getBoolean("encrypted", false)).append('\n');
        report.append("scheduled_backups=")
                .append(BackupManager.prefs(context).getBoolean("scheduleEnabled", false)).append('\n');
        report.append("retention_mode=")
                .append(safe(BackupManager.prefs(context).getString("retentionMode", "simple"))).append('\n');
        report.append("last_run_status=").append(lastRunStatus(context)).append('\n');
        return report.toString();
    }

    private static String lastRunStatus(Context context) {
        android.content.SharedPreferences prefs = BackupManager.prefs(context);
        if (!prefs.contains("lastRun")) return "none";
        return prefs.getBoolean("lastRunSuccess", false) ? "success" : "failed";
    }

    private static String appVersion(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception ignored) {
            return "unknown";
        }
    }

    private static String deviceName() {
        return ((Build.MANUFACTURER == null ? "" : Build.MANUFACTURER) + " "
                + (Build.MODEL == null ? "" : Build.MODEL)).trim();
    }

    private static String romName() {
        String[][] properties = {
                {"ro.mi.os.version.name", "HyperOS"},
                {"ro.miui.ui.version.name", "MIUI"},
                {"ro.build.version.emui", "EMUI/HarmonyOS"},
                {"ro.build.version.oneui", "One UI"},
                {"ro.vendor.build.version.sem_oneui", "One UI"},
                {"ro.oxygen.version", "OxygenOS"},
                {"ro.build.version.oplusrom", "ColorOS"},
                {"ro.build.version.coloros", "ColorOS"},
                {"ro.build.version.realmeui", "Realme UI"},
                {"ro.xos.version", "XOS"},
                {"ro.hios.version", "HiOS"},
                {"ro.vivo.os.build.display.id", "FunTouch OS"},
                {"ro.lineage.version", "LineageOS"}
        };
        for (String[] property : properties) {
            String value = systemProperty(property[0]);
            if (value != null && !value.trim().isEmpty()) return property[1] + " " + value.trim();
        }
        return "unknown";
    }

    private static String systemProperty(String name) {
        try {
            java.lang.reflect.Method get = Class.forName("android.os.SystemProperties")
                    .getMethod("get", String.class);
            return (String) get.invoke(null, name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String safe(String value) {
        if (value == null || value.isEmpty()) return "none";
        String cleaned = value.replaceAll("[\\p{Cntrl}\\r\\n]+", " ").trim();
        if (cleaned.length() > 200) cleaned = cleaned.substring(0, 200);
        return cleaned.isEmpty() ? "none" : cleaned;
    }
}
