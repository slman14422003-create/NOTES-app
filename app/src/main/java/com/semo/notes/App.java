package com.semo.notes;

import android.app.Activity;
import android.app.Application;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * Android 15+ (and every app targeting API 35/36) draws edge-to-edge and the old opt-out is gone on
 * Android 16. This keeps every screen clear of the status bar, navigation bar, cutout and keyboard.
 */
public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Lang.apply(this);
        Firebase.init(this);
        Updater.cleanup(this);
        Sync.watchNetwork(this);
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(Activity a, Bundle b) {
                Motion.install(a);
            }

            @Override
            public void onActivityStarted(Activity a) {
                View root = a.getWindow().getDecorView();
                // ignore touches while another window (an overlay) covers the app: tapjacking protection
                root.setFilterTouchesWhenObscured(true);
                AppLock.started(a);
                if (Build.VERSION.SDK_INT >= 31) {
                    try {
                        a.getWindow().setHideOverlayWindows(true); // other apps can not draw over the notes
                    } catch (Throwable ignored) {
                    }
                }
                // optional privacy mode: no screenshots, blank preview in the recent-apps list
                boolean lock = AppLock.enabled(a);
                boolean secure = Prefs.secureScreen(a) || (lock && Build.VERSION.SDK_INT < 33);
                if (secure) a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
                else a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
                if (Build.VERSION.SDK_INT >= 33) a.setRecentsScreenshotEnabled(!lock);
                if (Build.VERSION.SDK_INT < 35) return;
                View content = a.findViewById(android.R.id.content);
                if (content == null || content.getTag(R.id.tag_insets) != null) return;
                content.setTag(R.id.tag_insets, Boolean.TRUE);
                ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
                    Insets b = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                            | WindowInsetsCompat.Type.displayCutout() | WindowInsetsCompat.Type.ime());
                    v.setPadding(b.left, b.top, b.right, b.bottom);
                    return WindowInsetsCompat.CONSUMED;
                });
                ViewCompat.requestApplyInsets(content);
            }

            @Override public void onActivityResumed(Activity a) {
                ThemeKit.refreshIfChanged(a); // palette / day-night changed while this screen was in the back
            }
            @Override public void onActivityPaused(Activity a) { }
            @Override public void onActivityStopped(Activity a) { AppLock.stopped(); }
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
            @Override public void onActivityDestroyed(Activity a) { }
        });
    }
}
