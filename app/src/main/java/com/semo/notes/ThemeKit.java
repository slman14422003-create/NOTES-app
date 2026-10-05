package com.semo.notes;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Day/night mode + colour palette. Every colour of the UI is a theme attribute (?attr/n_*), so choosing a
 * palette just switches the activity theme: all screens, dialogs, icons, cards and buttons follow at once.
 */
public final class ThemeKit {
    private ThemeKit() {
    }

    public static final String MODE_SYSTEM = "system", MODE_DARK = "dark", MODE_LIGHT = "light";
    public static final String[] MODES = {MODE_SYSTEM, MODE_DARK, MODE_LIGHT};
    public static final String[] ACCENTS = {"mono", "blue", "green", "berry", "yellow"};
    public static final int[] ACCENT_NAMES = {R.string.th_mono, R.string.th_blue, R.string.th_green,
            R.string.th_berry, R.string.th_yellow};
    public static final int[] MODE_NAMES = {R.string.th_system, R.string.th_dark, R.string.th_light};

    /** Preview colour of each palette in the picker: {dark, light}. */
    private static final int[][] SWATCH = {
            {0xFFFFFFFF, 0xFF111111},
            {0xFF4C86FF, 0xFF2F6BFF},
            {0xFF3DD57F, 0xFF15803D},
            {0xFFDB4A93, 0xFFB0307A},
            {0xFFFFD23F, 0xFFF2B705}};

    private static final Map<Activity, String> APPLIED = new WeakHashMap<>();

    public static boolean isDark(Context c) {
        String m = Prefs.themeMode(c);
        if (MODE_LIGHT.equals(m)) return false;
        if (MODE_DARK.equals(m)) return true;
        return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    /** Identifies the active look; screens recreate themselves when it changes. */
    public static String key(Context c) {
        return (isDark(c) ? "d" : "l") + Prefs.themeAccent(c);
    }

    public static int swatch(String accent, boolean dark) {
        for (int i = 0; i < ACCENTS.length; i++) if (ACCENTS[i].equals(accent)) return SWATCH[i][dark ? 0 : 1];
        return SWATCH[0][dark ? 0 : 1];
    }

    private static int style(boolean dark, String accent) {
        switch (accent) {
            case "blue":
                return dark ? R.style.Theme_N_Dark_Blue : R.style.Theme_N_Light_Blue;
            case "green":
                return dark ? R.style.Theme_N_Dark_Green : R.style.Theme_N_Light_Green;
            case "berry":
                return dark ? R.style.Theme_N_Dark_Berry : R.style.Theme_N_Light_Berry;
            case "yellow":
                return dark ? R.style.Theme_N_Dark_Yellow : R.style.Theme_N_Light_Yellow;
            default:
                return dark ? R.style.Theme_N_Dark_Mono : R.style.Theme_N_Light_Mono;
        }
    }

    /** Call at the start of every screen's onCreate (before setContentView). */
    public static void apply(Activity a) {
        a.setTheme(style(isDark(a), Prefs.themeAccent(a)));
        synchronized (APPLIED) {
            APPLIED.put(a, key(a));
        }
    }

    /** Called when a screen comes back to the front: recreate it if the look was changed meanwhile. */
    public static void refreshIfChanged(Activity a) {
        String k;
        synchronized (APPLIED) {
            k = APPLIED.get(a);
        }
        if (k != null && !k.equals(key(a))) a.recreate();
    }
}
