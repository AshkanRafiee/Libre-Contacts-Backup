package com.ashkanrafiee.librecontactsbackup;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

/**
 * Scrollable screens keep working while their scrollbar stays hidden — no
 * track or thumb should ever show, even when the content genuinely needs a
 * scroll. The restore-selection dialog is the deliberate exception: it keeps
 * its always-visible bar to signal that more categories sit below.
 */
@RunWith(AndroidJUnit4.class)
public class HiddenScrollbarTest {

    @Test public void main_screen_scrolls_without_showing_any_scrollbar() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity ->
                    assertAllScrollViewsHideTheirBars(activity.getWindow().getDecorView()));
        }
    }

    @Test public void about_screen_scrolls_without_showing_any_scrollbar() {
        try (ActivityScenario<AboutActivity> scenario = ActivityScenario.launch(AboutActivity.class)) {
            scenario.onActivity(activity ->
                    assertAllScrollViewsHideTheirBars(activity.getWindow().getDecorView()));
        }
    }

    private void assertAllScrollViewsHideTheirBars(View root) {
        List<ScrollView> scrollViews = new ArrayList<>();
        collectScrollViews(root, scrollViews);
        assertTrue("screen should be scrollable", !scrollViews.isEmpty());
        for (ScrollView scroll : scrollViews) {
            assertFalse("vertical scrollbar shown on a scrolling screen", scroll.isVerticalScrollBarEnabled());
            assertTrue("hiding the scrollbar must never disable scrolling", scroll.isScrollContainer());
        }
    }

    private void collectScrollViews(View view, List<ScrollView> out) {
        if (view instanceof ScrollView) out.add((ScrollView) view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collectScrollViews(group.getChildAt(i), out);
        }
    }
}