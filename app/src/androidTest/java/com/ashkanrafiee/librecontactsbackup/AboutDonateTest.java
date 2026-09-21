package com.ashkanrafiee.librecontactsbackup;

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

    @Test public void about_screen_shows_the_donate_row() {
        try (ActivityScenario<AboutActivity> scenario = ActivityScenario.launch(AboutActivity.class)) {
            scenario.onActivity(activity -> {
                View root = activity.getWindow().getDecorView();
                assertTrue("donate label is missing",
                    hasText(root, activity.getString(R.string.about_donate_label)));
            });
        }
    }

    private boolean hasText(View view, String expected) {
        if (view instanceof TextView && expected.equals(((TextView) view).getText().toString())) {
            return true;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (hasText(group.getChildAt(i), expected)) {
                    return true;
                }
            }
        }
        return false;
    }
}