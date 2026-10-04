package com.semo.notes;

/** Note colours: a soft dark tint for the card and a brighter dot for the colour picker. */
public final class Palette {
    private Palette() {
    }

    public static final int[] CARD = {
            0xFF303030, 0xFF4A2B2B, 0xFF4A3524, 0xFF453F1F, 0xFF24402E, 0xFF1F3350, 0xFF3B2A4F};
    public static final int[] DOT = {
            0xFFB4B4B4, 0xFFFF6B6B, 0xFFF59E4A, 0xFFF5D84A, 0xFF4ADE80, 0xFF6AA6FF, 0xFFB794F6};

    public static int card(int i) {
        return CARD[Math.max(0, Math.min(CARD.length - 1, i))];
    }

    public static int dot(int i) {
        return DOT[Math.max(0, Math.min(DOT.length - 1, i))];
    }
}
