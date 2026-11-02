package com.semo.notes;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

/** Launcher icon variants: every variant is an activity-alias in the manifest and exactly one is enabled. */
final class IconKit {
    private IconKit() {
    }

    static final class Variant {
        final String alias;
        final int name;
        final int bg;
        final int fg;
        final boolean manifestDefault;

        Variant(String alias, int name, int bg, int fg, boolean manifestDefault) {
            this.alias = alias;
            this.name = name;
            this.bg = bg;
            this.fg = fg;
            this.manifestDefault = manifestDefault;
        }
    }

    static final Variant[] ALL = {
            new Variant("IconBlue", R.string.icon_blue, R.drawable.ic_launcher_background, R.drawable.ic_launcher_foreground, true),
            new Variant("IconPurple", R.string.icon_purple, R.drawable.ic_launcher_bg_purple, R.drawable.ic_launcher_fg_purple, false),
            new Variant("IconGreen", R.string.icon_green, R.drawable.ic_launcher_bg_green, R.drawable.ic_launcher_fg_green, false),
            new Variant("IconPink", R.string.icon_pink, R.drawable.ic_launcher_bg_pink, R.drawable.ic_launcher_fg_pink, false),
            new Variant("IconRed", R.string.icon_red, R.drawable.ic_launcher_bg_red, R.drawable.ic_launcher_fg_red, false),
            new Variant("IconOrange", R.string.icon_orange, R.drawable.ic_launcher_bg_orange, R.drawable.ic_launcher_fg_orange, false),
            new Variant("IconGold", R.string.icon_gold, R.drawable.ic_launcher_bg_gold, R.drawable.ic_launcher_fg_gold, false),
            new Variant("IconNight", R.string.icon_night, R.drawable.ic_launcher_bg_night, R.drawable.ic_launcher_fg_night, false),
            new Variant("IconGraphite", R.string.icon_graphite, R.drawable.ic_launcher_bg_graphite, R.drawable.ic_launcher_fg_graphite, false),
    };

    private static ComponentName component(Context c, Variant v) {
        return new ComponentName(c.getPackageName(), "com.semo.notes." + v.alias);
    }

    private static boolean enabled(Context c, Variant v) {
        int s = c.getPackageManager().getComponentEnabledSetting(component(c, v));
        if (s == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) return true;
        return s == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && v.manifestDefault;
    }

    static Variant current(Context c) {
        for (Variant v : ALL) if (enabled(c, v)) return v;
        return ALL[0];
    }

    /** Enables the chosen alias first and only then disables the others, so there is always a launcher entry. */
    static void apply(Context c, Variant target) {
        PackageManager pm = c.getPackageManager();
        pm.setComponentEnabledSetting(component(c, target), PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP);
        for (Variant v : ALL) {
            if (v == target) continue;
            pm.setComponentEnabledSetting(component(c, v), PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
        }
    }
}
