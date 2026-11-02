package com.semo.notes;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;
import android.os.Debug;

import java.security.MessageDigest;
import java.util.Locale;

/**
 * Anti-tampering checks for release builds. They make repackaging or instrumenting the app harder:
 * the APK must be signed with the original key, must not be debuggable and must not have a debugger attached.
 * Client-side checks can never be absolute (a determined attacker can patch them out), which is why the
 * account data itself is protected on the server by the Firestore rules - these checks raise the cost of an attack.
 */
public final class Integrity {
    private Integrity() {
    }

    public static final int OK = 0, BAD_SIGNATURE = 1, DEBUGGABLE = 2, DEBUGGER = 3, HOOKED = 4;

    private static volatile int state = -1;

    /** Evaluates once per process (the debugger check is repeated on every call). */
    public static synchronized int state(Context c) {
        if (BuildConfig.DEBUG) return OK; // your own debug builds are never blocked
        if (state < 0) {
            int s = OK;
            ApplicationInfo ai = c.getApplicationInfo();
            if ((ai.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                s = DEBUGGABLE;
            } else {
                String expected = Secrets.certSha256();
                if (!expected.isEmpty()) {
                    String actual = signerHash(c, "SHA-256");
                    if (actual.isEmpty() || !expected.equalsIgnoreCase(actual)) s = BAD_SIGNATURE;
                }
                if (s == OK && hooked()) s = HOOKED;
            }
            state = s;
        }
        if (state > OK) return state;
        if (Debug.isDebuggerConnected() || Debug.waitingForDebugger()) return DEBUGGER;
        return OK;
    }

    /** True when an instrumentation framework (Frida, Xposed / LSPosed, Substrate) is loaded into this process. */
    private static boolean hooked() {
        try {
            Class.forName("de.robv.android.xposed.XposedBridge");
            return true;
        } catch (Throwable ignored) {
        }
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader("/proc/self/maps"))) {
            String l;
            while ((l = r.readLine()) != null) {
                String s = l.toLowerCase(Locale.US);
                if (s.contains("frida") || s.contains("gum-js") || s.contains("xposed") || s.contains("substrate")) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    public static boolean ok(Context c) {
        return state(c) == OK;
    }

    /**
     * Call first thing in every screen: when the app was modified, shows the "modified app" notice and returns
     * true so the caller stops. The checks also guard every network call (see Firebase).
     */
    public static boolean enforce(Activity a) {
        if (ok(a)) return false;
        a.startActivity(new Intent(a, TamperActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        a.finish();
        return true;
    }

    /** Hex digest (no separators, upper case) of the certificate this app is signed with. */
    @SuppressWarnings("deprecation")
    public static String signerHash(Context c, String algorithm) {
        try {
            PackageManager pm = c.getPackageManager();
            Signature[] sigs;
            if (Build.VERSION.SDK_INT >= 28) {
                PackageInfo p = pm.getPackageInfo(c.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
                SigningInfo si = p.signingInfo;
                if (si == null) return "";
                sigs = si.hasMultipleSigners() ? si.getApkContentsSigners() : si.getSigningCertificateHistory();
            } else {
                sigs = pm.getPackageInfo(c.getPackageName(), PackageManager.GET_SIGNATURES).signatures;
            }
            if (sigs == null || sigs.length == 0) return "";
            // the app's CURRENT certificate is the last in the history; for a single signer it is the only one
            byte[] der = sigs[sigs.length - 1].toByteArray();
            byte[] h = MessageDigest.getInstance(algorithm).digest(der);
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format(Locale.US, "%02X", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
