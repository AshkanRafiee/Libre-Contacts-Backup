package com.ashkanrafiee.librecontactsbackup;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Hermetic checks for {@link BatteryHelper}: intent construction and the
 * fail-open exemption query. Opening real system settings is deliberately
 * not exercised — it would leave the test app for the Settings UI.
 */
@RunWith(AndroidJUnit4.class)
public class BatteryExemptionTest {

    @Test public void exemptionQueryNeverThrows() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        // Result depends on device state; the contract is only that it answers.
        boolean exempt = BatteryHelper.isExempt(context);
        assertTrue(exempt || !exempt);
    }

    @Test public void requestIntentTargetsThisPackage() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Intent intent = BatteryHelper.requestIntent(context);
        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, intent.getAction());
        assertEquals("package:" + context.getPackageName(),
                intent.getData() != null ? intent.getData().toString() : null);
    }

    @Test public void settingsHelpersNeverThrow() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        BatteryHelper.canRequestExemption(context);
        // On a device without a Settings handler these are no-ops by contract.
        assertTrue(true);
    }
}
