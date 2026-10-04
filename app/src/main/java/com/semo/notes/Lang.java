package com.semo.notes;

import android.content.Context;
import android.content.res.Resources;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

/**
 * App language: Arabic (RTL), English (LTR) or follow the system. Changing it recreates the open screens
 * and flips the whole layout direction. A system language the app does not have falls back to English.
 */
public final class Lang {
    private Lang() {
    }

    public static final String SYSTEM = "";
    public static final String AR = "ar";
    public static final String EN = "en";

    /** Applies the saved choice. Call after the user changes it, and once at startup. */
    public static void apply(Context c) {
        String v = Prefs.language(c);
        LocaleListCompat list;
        if (AR.equals(v) || EN.equals(v)) {
            list = LocaleListCompat.forLanguageTags(v);
        } else {
            String sys = Resources.getSystem().getConfiguration().getLocales().get(0).getLanguage();
            list = AR.equals(sys) || EN.equals(sys)
                    ? LocaleListCompat.getEmptyLocaleList()
                    : LocaleListCompat.forLanguageTags(EN);
        }
        if (!AppCompatDelegate.getApplicationLocales().equals(list)) {
            AppCompatDelegate.setApplicationLocales(list);
        }
    }

    /** Label shown for the current choice. */
    public static String label(Context c) {
        String v = Prefs.language(c);
        if (AR.equals(v)) return c.getString(R.string.lang_ar);
        if (EN.equals(v)) return c.getString(R.string.lang_en);
        return c.getString(R.string.lang_system);
    }

    public static boolean isAr(Context c) {
        return c.getResources().getConfiguration().getLayoutDirection() == android.view.View.LAYOUT_DIRECTION_RTL;
    }

    /** Picker dialog (system / العربية / English). */
    public static void choose(final android.app.Activity a) {
        final String[] values = {SYSTEM, AR, EN};
        CharSequence[] labels = {a.getString(R.string.lang_system), a.getString(R.string.lang_ar),
                a.getString(R.string.lang_en)};
        new Dlg(a).setTitle(R.string.language)
                .setItems(labels, (d, which) -> {
                    Prefs.setLanguage(a, values[which]);
                    apply(a);
                })
                .show();
    }
}
