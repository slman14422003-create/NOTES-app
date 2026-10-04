package com.semo.notes;

import android.content.Context;
import android.content.SharedPreferences;

/** Small key-value store: sign-in session, sync switches and the last sync time. */
public final class Prefs {
    private Prefs() {
    }

    public static final String MODE_NONE = "";
    public static final String MODE_CLOUD = "cloud";
    public static final String MODE_LOCAL = "local";

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("notes_prefs", Context.MODE_PRIVATE);
    }

    /** "" = first launch, "cloud" = signed in to Firebase, "local" = notes stay on the device. */
    public static String mode(Context c) {
        return sp(c).getString("mode", MODE_NONE);
    }

    public static boolean isCloud(Context c) {
        return MODE_CLOUD.equals(mode(c)) && !uid(c).isEmpty();
    }

    public static void setLocalOnly(Context c) {
        sp(c).edit().putString("mode", MODE_LOCAL).apply();
    }

    public static void setSession(Context c, String uid, String email, boolean anonymous,
                                  String idToken, String refreshToken, long expiresInSec) {
        sp(c).edit()
                .putString("mode", MODE_CLOUD)
                .putString("uid", uid)
                .putString("email", email == null ? "" : email)
                .putBoolean("anon", anonymous)
                .putString("idToken", idToken)
                .putString("refreshToken", refreshToken)
                .putLong("expiresAt", System.currentTimeMillis() + expiresInSec * 1000L)
                .apply();
    }

    public static void updateTokens(Context c, String idToken, String refreshToken, long expiresInSec) {
        sp(c).edit()
                .putString("idToken", idToken)
                .putString("refreshToken", refreshToken)
                .putLong("expiresAt", System.currentTimeMillis() + expiresInSec * 1000L)
                .apply();
    }

    public static void clearSession(Context c) {
        sp(c).edit()
                .putString("mode", MODE_NONE)
                .remove("uid").remove("email").remove("anon")
                .remove("idToken").remove("refreshToken").remove("expiresAt").remove("lastSync").remove("cursor")
                .apply();
    }

    public static String uid(Context c) {
        return sp(c).getString("uid", "");
    }

    public static String email(Context c) {
        return sp(c).getString("email", "");
    }

    public static boolean anonymous(Context c) {
        return sp(c).getBoolean("anon", false);
    }

    public static String idToken(Context c) {
        return sp(c).getString("idToken", "");
    }

    public static String refreshToken(Context c) {
        return sp(c).getString("refreshToken", "");
    }

    public static long expiresAt(Context c) {
        return sp(c).getLong("expiresAt", 0);
    }

    public static boolean autoSync(Context c) {
        return sp(c).getBoolean("autoSync", true);
    }

    public static void setAutoSync(Context c, boolean on) {
        sp(c).edit().putBoolean("autoSync", on).apply();
    }

    /** Server timestamp of the newest cloud change already imported ("" = import everything). */
    public static String cursor(Context c) {
        return sp(c).getString("cursor", "");
    }

    public static void setCursor(Context c, String v) {
        sp(c).edit().putString("cursor", v).apply();
    }

    public static long lastSync(Context c) {
        return sp(c).getLong("lastSync", 0);
    }

    public static void setLastSync(Context c, long t) {
        sp(c).edit().putLong("lastSync", t).apply();
    }
}
