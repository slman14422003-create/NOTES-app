package com.semo.notes;

import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Base class of every screen. It adds three small stability / smoothness layers:
 * <ol>
 *   <li>Navigation guard: the same screen can not be opened twice by a double tap or by two events that
 *       arrive within a fraction of a second (no stacked duplicate screens, no stuttering transitions).</li>
 *   <li>Soft first frame: the content fades in instead of popping in after the window animation starts.</li>
 *   <li>{@link #alive()}: late callbacks (database, sync, handlers) can check that the screen still exists
 *       before touching its views.</li>
 * </ol>
 */
public abstract class SmoothActivity extends AppCompatActivity {
    private static final long NAV_GUARD_MS = 550;

    // shared by every screen: a tap on screen A and the resulting event on screen B count as one navigation
    private static String lastNavKey = "";
    private static long lastNavAt = 0;

    @Override
    public void startActivityForResult(Intent intent, int requestCode, Bundle options) {
        // only plain startActivity() calls are guarded; pickers and other result requests pass through
        if (requestCode < 0 && intent != null && isDuplicateNavigation(intent)) return;
        super.startActivityForResult(intent, requestCode, options);
    }

    private static synchronized boolean isDuplicateNavigation(Intent i) {
        String key = String.valueOf(i.getComponent()) + "|" + i.getAction() + "|" + i.getDataString()
                + "|" + i.getStringExtra(NoteEditorActivity.EXTRA_ID);
        long now = SystemClock.uptimeMillis();
        boolean dup = key.equals(lastNavKey) && now - lastNavAt < NAV_GUARD_MS;
        if (!dup) {
            lastNavKey = key;
            lastNavAt = now;
        }
        return dup;
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        // fresh launches only: a recreate (theme or language change) must not flash
        if (savedInstanceState != null || !Motion.enabled(this)) return;
        View content = findViewById(android.R.id.content);
        if (content == null) return;
        content.setAlpha(0f);
        content.animate().alpha(1f).setDuration(170).start();
    }

    /** True while the screen can still be drawn on. */
    protected boolean alive() {
        return !isFinishing() && !isDestroyed();
    }
}
