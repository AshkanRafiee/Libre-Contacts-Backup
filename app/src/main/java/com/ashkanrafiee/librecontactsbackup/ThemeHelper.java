package com.ashkanrafiee.librecontactsbackup;

import android.content.Context;
import android.content.res.Configuration;

/** Persists and applies the app's appearance choice independently of the system theme. */
public final class ThemeHelper {
    public static final String SYSTEM = "";
    public static final String LIGHT = "light";
    public static final String DARK = "dark";

    private static final String PREFS = "librecontacts";
    private static final String KEY_THEME = "theme";

    private ThemeHelper() {}

    public static String currentTheme(Context context) {
        String theme = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_THEME, SYSTEM);
        return LIGHT.equals(theme) || DARK.equals(theme) ? theme : SYSTEM;
    }

    public static void setTheme(Context context, String theme) {
        String value = LIGHT.equals(theme) || DARK.equals(theme) ? theme : SYSTEM;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_THEME, value).apply();
    }

    /** Wraps a context with the requested night-mode configuration before the Activity is created. */
    public static Context wrap(Context base) {
        String theme = currentTheme(base);
        if (SYSTEM.equals(theme)) return base;

        Configuration configuration = new Configuration(base.getResources().getConfiguration());
        int nightMode = LIGHT.equals(theme)
                ? Configuration.UI_MODE_NIGHT_NO
                : Configuration.UI_MODE_NIGHT_YES;
        configuration.uiMode = (configuration.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | nightMode;
        return base.createConfigurationContext(configuration);
    }
}
