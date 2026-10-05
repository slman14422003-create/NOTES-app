package com.semo.notes;

import android.content.Context;
import android.content.SharedPreferences;

/** Small key-value store: sign-in session, sync switches, language, privacy and update settings. */
public final class Prefs {
    private Prefs() {
    }

    public static final String MODE_NONE = "";
    public static final String MODE_CLOUD = "cloud";

    // The short-lived ID token only lives in memory; the refresh token is stored encrypted (see Vault).
    private static volatile String memIdToken = "";
    private static volatile long memExpiresAt = 0;
    private static volatile String memRefresh = "";

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("notes_prefs", Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------ session

    public static String mode(Context c) {
        return sp(c).getString("mode", MODE_NONE);
    }

    public static boolean isCloud(Context c) {
        return MODE_CLOUD.equals(mode(c)) && !uid(c).isEmpty();
    }

    public static void setSession(Context c, String uid, String email, boolean anonymous,
                                  String idToken, String refreshToken, long expiresInSec) {
        memIdToken = idToken == null ? "" : idToken;
        memExpiresAt = System.currentTimeMillis() + expiresInSec * 1000L;
        memRefresh = refreshToken == null ? "" : refreshToken;
        sp(c).edit()
                .putString("mode", MODE_CLOUD)
                .putString("uid", uid)
                .putString("email", email == null ? "" : email)
                .putBoolean("anon", anonymous)
                .putString("rt", Vault.encrypt(memRefresh))
                .remove("refreshToken").remove("idToken").remove("expiresAt")
                .apply();
    }

    public static void updateTokens(Context c, String idToken, String refreshToken, long expiresInSec) {
        memIdToken = idToken == null ? "" : idToken;
        memExpiresAt = System.currentTimeMillis() + expiresInSec * 1000L;
        if (refreshToken != null && !refreshToken.isEmpty() && !refreshToken.equals(memRefresh)) {
            memRefresh = refreshToken;
            sp(c).edit().putString("rt", Vault.encrypt(refreshToken)).apply();
        }
    }

    /** Forces the next request to fetch a fresh ID token. */
    public static void expireIdToken() {
        memIdToken = "";
        memExpiresAt = 0;
    }

    public static void clearSession(Context c) {
        memIdToken = "";
        memExpiresAt = 0;
        memRefresh = "";
        sp(c).edit()
                .putString("mode", MODE_NONE)
                .remove("uid").remove("email").remove("anon").remove("rt")
                .remove("refreshToken").remove("idToken").remove("expiresAt")
                .remove("lastSync").remove("cursor")
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
        return memIdToken;
    }

    public static long expiresAt(Context c) {
        return memExpiresAt;
    }

    public static synchronized String refreshToken(Context c) {
        if (!memRefresh.isEmpty()) return memRefresh;
        SharedPreferences p = sp(c);
        String v = Vault.decrypt(p.getString("rt", ""));
        if (v.isEmpty()) {
            // sessions created by an older version kept the token in plain text: encrypt it once
            String legacy = p.getString("refreshToken", "");
            if (!legacy.isEmpty()) {
                v = legacy;
                p.edit().putString("rt", Vault.encrypt(legacy)).remove("refreshToken").apply();
            }
        }
        memRefresh = v;
        return v;
    }

    /**
     * The account the notes stored on this device belong to ("" = written before any account existed).
     * Used so one person's notes are never uploaded into another person's account.
     */
    public static String ownerUid(Context c) {
        return sp(c).getString("owner", "");
    }

    public static void setOwnerUid(Context c, String uid) {
        sp(c).edit().putString("owner", uid == null ? "" : uid).apply();
    }

    // ------------------------------------------------------------------ settings

    /** "" = follow the system, "ar" or "en". */
    public static String language(Context c) {
        return sp(c).getString("lang", "");
    }

    public static void setLanguage(Context c, String v) {
        sp(c).edit().putString("lang", v == null ? "" : v).apply();
    }

    /** "dark" (default), "light" or "system". */
    public static String themeMode(Context c) {
        return sp(c).getString("themeMode", "dark");
    }

    public static void setThemeMode(Context c, String v) {
        sp(c).edit().putString("themeMode", v).apply();
    }

    /** "mono" (default), "blue", "green", "berry" or "yellow". */
    public static String themeAccent(Context c) {
        return sp(c).getString("themeAccent", "mono");
    }

    public static void setThemeAccent(Context c, String v) {
        sp(c).edit().putString("themeAccent", v).apply();
    }

    /** Blocks screenshots and hides the app content in the recent-apps switcher. */
    public static boolean secureScreen(Context c) {
        return sp(c).getBoolean("secure", false);
    }

    public static void setSecureScreen(Context c, boolean on) {
        sp(c).edit().putBoolean("secure", on).apply();
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

    // ------------------------------------------------------------------ updates

    /** "owner/repo" the app updates itself from; "" = use the repository the APK was built in. */
    public static String updateRepo(Context c) {
        return sp(c).getString("updRepo", "");
    }

    public static void setUpdateRepo(Context c, String v) {
        sp(c).edit().putString("updRepo", v == null ? "" : v.trim()).apply();
    }

    public static boolean autoUpdate(Context c) {
        return sp(c).getBoolean("updAuto", true);
    }

    public static void setAutoUpdate(Context c, boolean on) {
        sp(c).edit().putBoolean("updAuto", on).apply();
    }

    public static boolean updatePre(Context c) {
        return sp(c).getBoolean("updPre", false);
    }

    public static void setUpdatePre(Context c, boolean on) {
        sp(c).edit().putBoolean("updPre", on).apply();
    }

    public static long lastUpdateCheck(Context c) {
        return sp(c).getLong("updLast", 0);
    }

    public static void setLastUpdateCheck(Context c, long t) {
        sp(c).edit().putLong("updLast", t).apply();
    }

    public static String skippedVersion(Context c) {
        return sp(c).getString("updSkip", "");
    }

    public static void setSkippedVersion(Context c, String v) {
        sp(c).edit().putString("updSkip", v == null ? "" : v).apply();
    }
}
