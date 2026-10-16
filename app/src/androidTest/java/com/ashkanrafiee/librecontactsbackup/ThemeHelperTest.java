package com.ashkanrafiee.librecontactsbackup;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.content.res.Configuration;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Theme choices persist and produce the matching resource configuration. */
@RunWith(AndroidJUnit4.class)
public class ThemeHelperTest {
    @Test public void theme_choices_round_trip_and_invalid_values_follow_system() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String original = ThemeHelper.currentTheme(context);
        try {
            ThemeHelper.setTheme(context, ThemeHelper.DARK);
            assertEquals(ThemeHelper.DARK, ThemeHelper.currentTheme(context));
            ThemeHelper.setTheme(context, ThemeHelper.LIGHT);
            assertEquals(ThemeHelper.LIGHT, ThemeHelper.currentTheme(context));
            ThemeHelper.setTheme(context, "neon");
            assertEquals(ThemeHelper.SYSTEM, ThemeHelper.currentTheme(context));
        } finally {
            ThemeHelper.setTheme(context, original);
        }
    }

    @Test public void forced_themes_set_the_night_mode_configuration() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String original = ThemeHelper.currentTheme(context);
        try {
            ThemeHelper.setTheme(context, ThemeHelper.DARK);
            assertEquals(Configuration.UI_MODE_NIGHT_YES,
                    ThemeHelper.wrap(context).getResources().getConfiguration().uiMode
                            & Configuration.UI_MODE_NIGHT_MASK);
            ThemeHelper.setTheme(context, ThemeHelper.LIGHT);
            assertEquals(Configuration.UI_MODE_NIGHT_NO,
                    ThemeHelper.wrap(context).getResources().getConfiguration().uiMode
                            & Configuration.UI_MODE_NIGHT_MASK);
        } finally {
            ThemeHelper.setTheme(context, original);
        }
    }
}
