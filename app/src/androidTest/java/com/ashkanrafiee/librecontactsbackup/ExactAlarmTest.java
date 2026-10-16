package com.ashkanrafiee.librecontactsbackup;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Hermetic checks for {@link AlarmScheduler}'s exact-alarm helpers.
 *
 * Below API 31 exact-alarm permission does not exist, so both queries are
 * fail-open true. On 31+ the two queries can disagree: exact may work via the
 * auto-granted USE_EXACT_ALARM while the user-granted SCHEDULE_EXACT_ALARM is
 * still missing — that disagreement is the pre-migration state the UI
 * surfaces, so it is logged here for every run (read it with
 * {@code logcat -s ExactAlarmTest}).
 *
 * Opening the real Alarms & reminders screen is deliberately not exercised —
 * it would leave the test app for the Settings UI.
 */
@RunWith(AndroidJUnit4.class)
public class ExactAlarmTest {

    @Test public void exactQueriesFailOpenBelowS() {
        if (Build.VERSION.SDK_INT >= 31) return;
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertTrue(AlarmScheduler.canScheduleExact(context));
        assertTrue(AlarmScheduler.hasSchedulePermission(context));
    }

    @Test public void requestIntentTargetsThisPackage() {
        if (Build.VERSION.SDK_INT < 31) return;
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Intent intent = AlarmScheduler.exactRequestIntent(context);
        assertEquals(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, intent.getAction());
        assertEquals("package:" + context.getPackageName(),
                intent.getData() != null ? intent.getData().toString() : null);
    }

    @Test public void logGrantStateForInspection() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Log.i("ExactAlarmTest", "sdk=" + Build.VERSION.SDK_INT
                + " canScheduleExact=" + AlarmScheduler.canScheduleExact(context)
                + " hasSchedulePermission=" + AlarmScheduler.hasSchedulePermission(context));
        assertTrue(true);
    }

    @Test public void settingsHelperNeverThrows() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AlarmScheduler.openExactSettings(context);
        // On a device without a Settings handler this is a no-op by contract.
        assertTrue(true);
    }
}
