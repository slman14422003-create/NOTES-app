package com.semo.notes;

import android.content.Context;

/** Note colours: the card tint comes from the active theme (day/night), the dot in the picker is a fixed bright colour. */
public final class Palette {
    private Palette() {
    }

    public static final int COUNT = 7;

    private static final int[] CARD_ATTR = {
            R.attr.n_surface, R.attr.n_note1, R.attr.n_note2, R.attr.n_note3,
            R.attr.n_note4, R.attr.n_note5, R.attr.n_note6};
    public static final int[] DOT = {
            0xFFB4B4B4, 0xFFFF6B6B, 0xFFF59E4A, 0xFFF5D84A, 0xFF4ADE80, 0xFF6AA6FF, 0xFFB794F6};

    public static int card(Context c, int i) {
        return Ui.color(c, CARD_ATTR[Math.max(0, Math.min(COUNT - 1, i))]);
    }

    public static int dot(int i) {
        return DOT[Math.max(0, Math.min(COUNT - 1, i))];
    }
}
