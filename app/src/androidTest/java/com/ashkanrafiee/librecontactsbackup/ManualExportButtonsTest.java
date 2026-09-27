package com.ashkanrafiee.librecontactsbackup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * The manual export row offers exactly the two formats the app really writes,
 * a CSV table and a vCard file. A third "Excel" button is not offered: what it
 * produced was SpreadsheetML XML under a .xls name, holding the same table the
 * CSV already holds, and many tools refuse to open it.
 */
@RunWith(AndroidJUnit4.class)
public class ManualExportButtonsTest {

    @Test public void export_row_offers_csv_and_vcf_only() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                View decor = activity.getWindow().getDecorView();

                Button csv = findButton(decor, activity.getString(R.string.export_csv));
                assertNotNull("the CSV export button is missing", csv);
                Button vcf = findButton(decor, activity.getString(R.string.export_vcf));
                assertNotNull("the vCard export button is missing", vcf);
                assertTrue("the CSV export button does nothing", csv.hasOnClickListeners());
                assertTrue("the vCard export button does nothing", vcf.hasOnClickListeners());

                assertEquals("the export row must offer the CSV and vCard exports and nothing else",
                        2, ((ViewGroup) csv.getParent()).getChildCount());
            });
        }
    }

    private Button findButton(View view, String label) {
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child instanceof Button && label.contentEquals(((Button) child).getText())) {
                    return (Button) child;
                }
                Button found = findButton(child, label);
                if (found != null) return found;
            }
        }
        return null;
    }
}
