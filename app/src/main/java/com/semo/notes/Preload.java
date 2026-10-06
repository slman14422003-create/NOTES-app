package com.semo.notes;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs while the splash screen is on: opens the database and reads the first page of notes, loads the settings
 * (and the decrypted session token, a slow Keystore call) into memory, warms the resource caches of the heavy
 * screens and, when there is an account, syncs. The home screen then draws from memory right away.
 * Everything here is best effort: any failure just means the screen loads the normal way.
 */
public final class Preload {
    private Preload() {
    }

    private static final ExecutorService EX = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final long FRESH_MS = 20_000;

    private static boolean started;
    private static volatile boolean pageReady;
    private static volatile boolean syncWaiting;
    private static volatile long syncOkAt;
    private static volatile List<Note> firstPage;
    private static Sync.Listener syncListener;

    /** Call once at launch, on the main thread, after the integrity check. */
    public static synchronized void start(Context ctx) {
        if (started) return;
        started = true;
        final Context app = ctx.getApplicationContext();
        final boolean cloud = Prefs.isCloud(app);
        if (cloud) startSync(app);
        EX.execute(() -> {
            try {
                Prefs.warm(app);
                if (cloud) Prefs.refreshToken(app); // decrypts through the Keystore once; kept in memory afterwards
            } catch (Throwable ignored) {
            }
            loadPage(app);
            pageReady = true;
            warmLayouts(app);
        });
    }

    private static void loadPage(Context app) {
        try {
            firstPage = Db.get(app).list(Db.F_ALL, "", HomeActivity.PAGE + 1);
        } catch (Throwable ignored) {
            firstPage = null;
        }
    }

    private static void startSync(final Context app) {
        syncWaiting = true;
        syncListener = (state, changed, error) -> {
            if (state == Sync.RUNNING) return;
            Sync.unlisten(syncListener);
            if (state == Sync.OK) syncOkAt = SystemClock.uptimeMillis();
            if (state == Sync.OK && changed) {
                // the import changed the notes: refresh the cached page before the home screen reads it
                EX.execute(() -> {
                    loadPage(app);
                    syncWaiting = false;
                });
            } else {
                syncWaiting = false;
            }
        };
        Sync.listen(syncListener);
        Sync.request(app, true);
    }

    /** Inflates the heavy screens once on a background thread so their XML and drawables are cached. */
    private static void warmLayouts(final Context app) {
        Thread t = new Thread(() -> {
            try {
                if (Looper.myLooper() == null) Looper.prepare();
                Context themed = new ContextThemeWrapper(app, ThemeKit.themeRes(app));
                LayoutInflater li = LayoutInflater.from(themed);
                int[] layouts = {R.layout.activity_home, R.layout.item_row, R.layout.activity_editor,
                        R.layout.activity_settings, R.layout.item_toggle};
                for (int l : layouts) {
                    try {
                        li.inflate(l, null, false);
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }
        }, "preload-layouts");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    /** True while the splash should still be held: the first page is not read yet or the account sync is running. */
    public static boolean busy() {
        return started && (!pageReady || syncWaiting);
    }

    /** The cached first page (newest notes, all filter), or null. Only usable for the very first draw. */
    public static List<Note> takeFirstPage() {
        List<Note> p = firstPage;
        firstPage = null;
        return p;
    }

    /** True when the splash already ran (or is still running) the start-up sync, so the home screen must not start another. */
    public static boolean takeFreshSync() {
        if (syncWaiting) return true;
        long at = syncOkAt;
        syncOkAt = 0;
        return at != 0 && SystemClock.uptimeMillis() - at < FRESH_MS;
    }
}
