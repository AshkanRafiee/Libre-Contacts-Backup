package com.ashkanrafiee.librecontactsbackup;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.content.Intent;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.URI;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** The About screen exposes a user-controlled report action and email destination. */
@RunWith(AndroidJUnit4.class)
public class AboutReportTest {
    @Test public void contact_address_is_mailto() throws Exception {
        URI uri = new URI(AboutActivity.CONTACT);
        assertEquals("mailto", uri.getScheme());
        assertEquals(AboutActivity.CONTACT_EMAIL, uri.getSchemeSpecificPart());
    }

    @Test public void about_screen_shows_report_action() {
        try (ActivityScenario<AboutActivity> scenario = ActivityScenario.launch(AboutActivity.class)) {
            scenario.onActivity(activity -> {
                TextView report = findText(activity.getWindow().getDecorView(),
                        activity.getString(R.string.about_report_issue_label));
                assertNotNull("report action is missing", report);
                assertTrue("report action is not clickable", report.hasOnClickListeners());
            });
        }
    }

    @Test public void report_intent_without_diagnostics_contains_only_template() {
        try (ActivityScenario<AboutActivity> scenario = ActivityScenario.launch(AboutActivity.class)) {
            scenario.onActivity(activity -> {
                Intent report = activity.buildShareIntent(false);
                assertEquals(Intent.ACTION_SEND, report.getAction());
                assertEquals("text/plain", report.getType());
                assertEquals(AboutActivity.CONTACT_EMAIL,
                        report.getStringArrayExtra(Intent.EXTRA_EMAIL)[0]);
                assertEquals(activity.getString(R.string.report_issue_subject),
                        report.getStringExtra(Intent.EXTRA_SUBJECT));
                assertEquals(activity.getString(R.string.report_issue_body),
                        report.getStringExtra(Intent.EXTRA_TEXT));
            });
        }
    }

    @Test public void report_intent_with_diagnostics_appends_diagnostics() {
        try (ActivityScenario<AboutActivity> scenario = ActivityScenario.launch(AboutActivity.class)) {
            scenario.onActivity(activity -> {
                String body = activity.buildShareIntent(true).getStringExtra(Intent.EXTRA_TEXT);
                assertTrue(body.startsWith(activity.getString(R.string.report_issue_body)));
                assertTrue(body.contains("--- Anonymized diagnostics ---"));
                assertTrue(body.contains("report_format=2"));
            });
        }
    }

    @Test public void email_fallback_uses_the_same_report_content() {
        try (ActivityScenario<AboutActivity> scenario = ActivityScenario.launch(AboutActivity.class)) {
            scenario.onActivity(activity -> {
                Intent email = activity.buildEmailIntent(false);
                assertEquals(Intent.ACTION_SENDTO, email.getAction());
                assertEquals("mailto", email.getData().getScheme());
                assertEquals(AboutActivity.CONTACT_EMAIL, email.getData().getSchemeSpecificPart());
                assertEquals(activity.getString(R.string.report_issue_body),
                        email.getStringExtra(Intent.EXTRA_TEXT));
            });
        }
    }

    private TextView findText(View view, String value) {
        if (view instanceof TextView && value.equals(((TextView) view).getText().toString())) {
            return (TextView) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = findText(group.getChildAt(i), value);
                if (found != null) return found;
            }
        }
        return null;
    }
}
