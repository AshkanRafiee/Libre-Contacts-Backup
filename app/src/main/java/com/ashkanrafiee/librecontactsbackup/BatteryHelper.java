package com.ashkanrafiee.librecontactsbackup;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

/**
 * Battery-optimization exemption for the automatic backup schedule.
 *
 * <p>Doze and App Standby can defer or drop the {@link AlarmManager} alarm
 * behind a scheduled backup — the intermittent missed-day pattern where
 * manual backups work but automatic ones silently never run. An app the
 * system is optimizing is subject to that deferral; an app the user exempted
 * ("Not optimized") is not.
 *
 * <p>Two distinct states are surfaced, because they need different fixes:
 * <ul>
 *   <li>Under battery optimization ({@link #isExempt} false) — fixed from
 *       the system battery settings, opened via {@link #openExemptionSettings}.</li>
 *   <li>Background-restricted by the user (see
 *       {@code MainActivity.isBackgroundRestricted}) — fixed from the app's
 *       App info screen, opened via {@link #openAppDetails}.</li>
 * </ul>
 */
public final class BatteryHelper {
    private BatteryHelper() {}

    /**
     * Whether the system will not subject this app to battery optimization.
     * Fail-open (true) below API 23 — where the API does not exist — and on
     * any unexpected failure, so the UI never nags about an unknown state.
     */
    public static boolean isExempt(Context context) {
        if (Build.VERSION.SDK_INT < 23) return true;
        try {
            PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            return power == null || power.isIgnoringBatteryOptimizations(context.getPackageName());
        } catch (Exception ignored) {
            return true;
        }
    }

    /** Whether the one-tap system exemption prompt can be opened on this device. */
    public static boolean canRequestExemption(Context context) {
        try {
            return requestIntent(context).resolveActivity(context.getPackageManager()) != null;
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Opens the system battery-optimization exemption flow: the one-tap
     * per-app prompt where available, otherwise the optimization list the
     * user picks this app from. Never throws.
     */
    public static void openExemptionSettings(Context context) {
        try {
            Intent direct = requestIntent(context);
            if (direct.resolveActivity(context.getPackageManager()) != null) {
                direct.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(direct);
                return;
            }
        } catch (Exception ignored) {
            // Fall through to the optimization list below.
        }
        try {
            Intent list = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            list.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (list.resolveActivity(context.getPackageManager()) != null) context.startActivity(list);
        } catch (Exception ignored) {
        }
    }

    /** Opens this app's App info screen, where a background restriction is lifted. Never throws. */
    public static void openAppDetails(Context context) {
        try {
            Intent details = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + context.getPackageName()));
            details.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (details.resolveActivity(context.getPackageManager()) != null) context.startActivity(details);
        } catch (Exception ignored) {
        }
    }

    static Intent requestIntent(Context context) {
        return new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + context.getPackageName()));
    }
}
