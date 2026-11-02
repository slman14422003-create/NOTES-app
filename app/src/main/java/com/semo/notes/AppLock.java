package com.semo.notes;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.view.View;

/** App lock state: locked on a cold start and after the app stayed in the background for the chosen time. */
final class AppLock {
    private AppLock() {
    }

    private static final long GRACE_MS = 1200; // a screen rotation / theme recreate never locks
    private static volatile boolean locked = true;
    private static volatile boolean showing;
    private static long bgAt;
    private static int started;

    static boolean enabled(Context c) {
        return Prefs.appLock(c);
    }

    static boolean needs(Context c) {
        return locked && enabled(c);
    }

    static void unlock() {
        locked = false;
    }

    static void shown(boolean on) {
        showing = on;
    }

    static void started(Context c) {
        if (started++ == 0 && enabled(c)) {
            long away = SystemClock.elapsedRealtime() - bgAt;
            if (bgAt == 0 || away >= Math.max(GRACE_MS, Prefs.lockDelay(c))) locked = true;
        }
    }

    static void stopped() {
        if (--started <= 0) {
            started = 0;
            bgAt = SystemClock.elapsedRealtime();
        }
    }

    /** Hides the screen content and opens the lock screen when a lock is pending. */
    static void guard(Activity a) {
        if (a instanceof LockActivity || !needs(a)) return;
        View content = a.findViewById(android.R.id.content);
        if (content != null) content.setVisibility(View.INVISIBLE);
        if (showing) return;
        showing = true;
        a.startActivity(new Intent(a, LockActivity.class).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION));
        a.overridePendingTransition(0, 0);
    }

    static void reveal(Activity a) {
        if (a instanceof LockActivity || needs(a)) return;
        View content = a.findViewById(android.R.id.content);
        if (content != null && content.getVisibility() == View.INVISIBLE) content.setVisibility(View.VISIBLE);
    }
}
