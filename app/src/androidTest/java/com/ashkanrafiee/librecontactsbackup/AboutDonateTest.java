package com.ashkanrafiee.librecontactsbackup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/** The About screen must render the donation row linking to the website's donate section. */
@RunWith(AndroidJUnit4.class)
public class AboutDonateTest {

    private static final String DONATE_URL_ROW = "librecontactsbackup.ashkanrafiee.com/#donate";

    @Test public void about_screen_shows_the_donate_row_linking_to_the_donate_section() {
        try (ActivityScenario<AboutActivity> scenario = ActivityScenario.launch(AboutActivity.class)) {
            scenario.onActivity(activity -> {
                View donateRow = findDonateRow(activity.getWindow().getDecorView(), activity.getString(R.string.about_donate_label));
                assertTrue("donate row is missing", donateRow instanceof ViewGroup);
                TextView value = (TextView) ((ViewGroup) donateRow).getChildAt(1);
                assertEquals("donate row shows the wrong URL", DONATE_URL_ROW, value.getText().toString());
                assertTrue("donate row is not clickable", donateRow.hasOnClickListeners());
            });
        }
    }

    private View findDonateRow(View view, String label) {
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            if (group.getChildCount() == 2 && group.getChildAt(0) instanceof TextView
                    && label.equals(((TextView) group.getChildAt(0)).getText().toString())) {
                return group;
            }
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findDonateRow(group.getChildAt(i), label);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}