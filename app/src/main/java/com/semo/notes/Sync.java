package com.semo.notes;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Automatic two-way sync with Cloud Firestore.
 *  - import: every note that is new or newer in the cloud is saved to the device;
 *  - export: every note that was created/edited on the device is uploaded;
 *  - conflicts: the most recently edited copy wins (per note).
 * Runs on app start, after edits (debounced) and from the sync button.
 */
public final class Sync {
    private Sync() {
    }

    public static final int IDLE = 0, RUNNING = 1, OK = 2, ERROR = 3;

    public interface Listener {
        /** Always called on the main thread. {@code changed} = notes on the device were modified by an import. */
        void onSync(int state, boolean changed, String error);
    }

    private static final ExecutorService EX = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile int state = IDLE;
    private static volatile String lastError = "";
    private static Runnable pending;

    public static int state() {
        return state;
    }

    public static String lastError() {
        return lastError;
    }

    public static void listen(Listener l) {
        if (!LISTENERS.contains(l)) LISTENERS.add(l);
    }

    public static void unlisten(Listener l) {
        LISTENERS.remove(l);
    }

    /** True when the device has a validated internet connection. */
    public static boolean online(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return true;
            Network n = cm.getActiveNetwork();
            if (n == null) return false;
            NetworkCapabilities nc = cm.getNetworkCapabilities(n);
            return nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Exception e) {
            return true;
        }
    }

    /** Schedules a sync. {@code now=false} waits a moment so a burst of edits is uploaded once. */
    public static void request(Context ctx, boolean now) {
        final Context app = ctx.getApplicationContext();
        if (!Prefs.isCloud(app)) return;
        if (!now && !Prefs.autoSync(app)) return;
        if (!online(app)) {
            // offline: notes stay safe on the device and are uploaded when the connection returns
            if (now) publish(ERROR, false, "NETWORK");
            return;
        }
        synchronized (Sync.class) {
            if (pending != null) MAIN.removeCallbacks(pending);
            pending = () -> EX.execute(() -> run(app));
            MAIN.postDelayed(pending, now ? 0 : 1500);
        }
    }

    private static void publish(final int s, final boolean changed, final String err) {
        state = s;
        lastError = err == null ? "" : err;
        MAIN.post(() -> {
            for (Listener l : LISTENERS) l.onSync(s, changed, err);
        });
    }

    private static void run(Context c) {
        if (!Prefs.isCloud(c)) return;
        publish(RUNNING, false, null);
        try {
            boolean changed = syncOnce(c);
            Prefs.setLastSync(c, System.currentTimeMillis());
            publish(OK, changed, null);
        } catch (Firebase.FbException e) {
            // an expired ID token is refreshed once and the whole sync is retried
            if (e.http == 401 || "UNAUTHENTICATED".equals(e.code)) {
                try {
                    Prefs.expireIdToken();
                    boolean changed = syncOnce(c);
                    Prefs.setLastSync(c, System.currentTimeMillis());
                    publish(OK, changed, null);
                    return;
                } catch (Firebase.FbException e2) {
                    e = e2;
                }
            }
            if (sessionDead(e.code)) {
                // revoked / disabled / unreadable session: sign out (notes stay on the device) and ask to sign in again
                Prefs.clearSession(c);
                publish(ERROR, false, "SESSION_ENDED");
                return;
            }
            publish(ERROR, false, e.code);
        } catch (Exception e) {
            publish(ERROR, false, "UNKNOWN");
        }
    }

    private static boolean sessionDead(String code) {
        if (code == null) return false;
        switch (code) {
            case "NO_SESSION":
            case "TOKEN_EXPIRED":
            case "INVALID_REFRESH_TOKEN":
            case "USER_NOT_FOUND":
            case "USER_DISABLED":
            case "INVALID_GRANT_TYPE":
            case "MISSING_REFRESH_TOKEN":
            case "UNAUTHENTICATED":
                return true;
            default:
                return false;
        }
    }

    /** Called once from the Application: uploads pending notes as soon as the connection comes back. */
    public static void watchNetwork(final Context ctx) {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;
            cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    request(ctx, false);
                }
            });
        } catch (Exception ignored) {
            // no network-state permission or restricted device: manual and on-start sync still work
        }
    }

    private static boolean syncOnce(Context c) throws Firebase.FbException {
        Db db = Db.get(c);
        String uid = Prefs.uid(c);
        if (Prefs.ownerUid(c).isEmpty()) Prefs.setOwnerUid(c, uid); // sessions created before owners were tracked
        String token = Firebase.validToken(c);

        // 1) import: only what changed on the server since the last sync -------------------------
        Firebase.Changes ch = Firebase.listChanged(token, uid, Prefs.cursor(c));
        Map<String, Note> remoteById = new HashMap<>();
        boolean changed = false;
        for (Note r : ch.notes) {
            remoteById.put(r.id, r);
            Note l = db.get(r.id);
            if (l == null) {
                db.putClean(r);
                if (!r.purged) changed = true;
            } else if (r.updatedAt > l.updatedAt) {
                db.putClean(r);
                changed = true;
            }
        }
        if (!ch.cursor.isEmpty()) Prefs.setCursor(c, ch.cursor);

        // 2) export: notes created or edited on this device --------------------------------------
        List<Note> push = new ArrayList<>();
        for (Note l : db.dirty()) {
            Note r = remoteById.get(l.id);
            if (r == null || l.updatedAt >= r.updatedAt) push.add(l);
            else db.markClean(l.id, l.updatedAt); // the cloud copy is newer and was imported above
        }
        for (int i = 0; i < push.size(); i += 200) {
            List<Note> chunk = push.subList(i, Math.min(push.size(), i + 200));
            Firebase.commit(token, uid, chunk);
            for (Note n : chunk) db.markClean(n.id, n.updatedAt);
        }
        return changed;
    }
}
