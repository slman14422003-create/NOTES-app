package com.semo.notes;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.EditText;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

/**
 * Spring-based motion: presses, entrances and show/hide use physics instead of fixed-duration curves, so they
 * feel continuous and can be interrupted mid-way without jumping. All of it is skipped when the user turned
 * animations off in the system settings.
 */
public final class Motion {
    private Motion() {
    }

    /** False when "Remove animations" / animator scale 0 is set in the system. */
    public static boolean enabled(Context c) {
        try {
            return Settings.Global.getFloat(c.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f;
        } catch (Exception e) {
            return true;
        }
    }

    public static SpringAnimation spring(View v, DynamicAnimation.ViewProperty p, float to, float stiffness, float damping) {
        SpringAnimation a = new SpringAnimation(v, p);
        a.setSpring(new SpringForce(to).setStiffness(stiffness).setDampingRatio(damping));
        a.start();
        return a;
    }

    // ------------------------------------------------------------------ press effect
    //
    // One effect for every tappable view of every screen, driven from SmoothActivity.dispatchTouchEvent instead of
    // per-view touch listeners. Why: a listener on a view that does not consume the touch (a row's inner card, a
    // list item) only ever saw ACTION_DOWN, never UP, so the view stayed shrunk "as if still held". Here the
    // effect always ends: on release, cancel, scrolling away, a long press (dialog opens) or losing focus.

    private static final Handler H = new Handler(Looper.getMainLooper());
    private static View pTarget;
    private static boolean pIn;
    private static float pX, pY;
    private static final Runnable PRESS_IN = Motion::pressIn;
    private static final Runnable RELEASE = Motion::release;

    /** Kept for old call sites: the effect itself is global now, this only clears the theme's old animator. */
    public static void pressable(final View v) {
        if (v == null) return;
        v.setStateListAnimator(null);
    }

    /** Called with every touch of a screen. */
    public static void track(android.app.Activity a, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                release();
                if (!enabled(a)) return;
                DisplayMetrics dm = a.getResources().getDisplayMetrics();
                View t = hit(a.getWindow().getDecorView(), (int) e.getRawX(), (int) e.getRawY(), dm);
                if (t == null) return;
                pTarget = t;
                pX = e.getRawX();
                pY = e.getRawY();
                H.postDelayed(PRESS_IN, 60); // a scroll that starts on a card never flashes the press
                if (t.isLongClickable()) {
                    H.postDelayed(RELEASE, ViewConfiguration.getLongPressTimeout() + 60L);
                }
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (pTarget == null) return;
                int slop = ViewConfiguration.get(pTarget.getContext()).getScaledTouchSlop();
                if (Math.abs(e.getRawX() - pX) > slop || Math.abs(e.getRawY() - pY) > slop) release();
                break;
            }
            case MotionEvent.ACTION_UP:
                if (pTarget == null) return;
                if (!pIn) { // a quick tap: still show the press, for just long enough to be seen
                    pressIn();
                    H.removeCallbacks(RELEASE);
                    H.postDelayed(RELEASE, 90);
                } else {
                    release();
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                release();
                break;
            default:
                break;
        }
    }

    private static void pressIn() {
        if (pTarget == null || pIn) return;
        pIn = true;
        squeeze(pTarget, depth(pTarget), 900f, 1f); // critically damped: sinks in softly, no wobble
    }

    /** Ends the press effect at once (also called when a dialog takes focus or the screen pauses). */
    public static void release() {
        H.removeCallbacks(PRESS_IN);
        H.removeCallbacks(RELEASE);
        View t = pTarget;
        boolean in = pIn;
        pTarget = null;
        pIn = false;
        if (t != null && in) squeeze(t, 1f, 480f, 0.74f); // springs back with the faintest settle
    }

    /** Small controls sink more than big cards, so every size reads as the same gentle press. */
    private static float depth(View v) {
        float d = v.getResources().getDisplayMetrics().density;
        float m = Math.min(v.getWidth(), v.getHeight()) / d;
        return m < 64f ? 0.88f : m < 110f ? 0.94f : 0.975f;
    }

    /** One reusable spring per axis: a new press or release continues from the current size and speed. */
    private static void squeeze(View v, float to, float stiffness, float damping) {
        SpringAnimation sx = scaleSpring(v, DynamicAnimation.SCALE_X, R.id.tag_spring_x);
        SpringAnimation sy = scaleSpring(v, DynamicAnimation.SCALE_Y, R.id.tag_spring_y);
        sx.getSpring().setStiffness(stiffness).setDampingRatio(damping);
        sy.getSpring().setStiffness(stiffness).setDampingRatio(damping);
        sx.animateToFinalPosition(to);
        sy.animateToFinalPosition(to);
    }

    private static SpringAnimation scaleSpring(View v, DynamicAnimation.ViewProperty p, int tag) {
        Object o = v.getTag(tag);
        if (o instanceof SpringAnimation) return (SpringAnimation) o;
        SpringAnimation a = new SpringAnimation(v, p, 1f);
        a.setSpring(new SpringForce(1f));
        v.setTag(tag, a);
        return a;
    }

    /** The deepest tappable view under the finger (a button inside a card wins over the card). */
    private static View hit(View v, int x, int y, DisplayMetrics dm) {
        if (v.getVisibility() != View.VISIBLE || v.getAlpha() < 0.05f) return null;
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        if (x < loc[0] || y < loc[1] || x >= loc[0] + v.getWidth() || y >= loc[1] + v.getHeight()) return null;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = g.getChildCount() - 1; i >= 0; i--) {
                View r = hit(g.getChildAt(i), x, y, dm);
                if (r != null) return r;
            }
        }
        return tappable(v, dm) ? v : null;
    }

    private static boolean tappable(View v, DisplayMetrics dm) {
        if (!v.isClickable() || !v.isEnabled()) return false;
        if (v instanceof EditText || v instanceof AbsListView) return false;
        if (v.getId() == android.R.id.content) return false;
        // a screen-sized clickable area (a scrim, a full page) must not shrink
        return !(v.getWidth() > dm.widthPixels * 0.9f && v.getHeight() > dm.heightPixels * 0.45f);
    }

    /** Fade + rise with a soft spring; {@code index} staggers a list. */
    public static void enter(final View v, int index) {
        if (v == null) return;
        if (!enabled(v.getContext())) {
            v.setAlpha(1f);
            v.setTranslationY(0f);
            return;
        }
        v.animate().cancel();
        v.setAlpha(0f);
        v.setTranslationY(Ui.dp(v.getContext(), 22));
        final long delay = Math.min(Math.max(index, 0), 8) * 38L;
        // while it moves, the card is drawn once into a GPU layer and only that texture is animated
        v.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        v.postDelayed(() -> {
            v.animate().alpha(1f).setDuration(260).start();
            SpringAnimation sa = spring(v, DynamicAnimation.TRANSLATION_Y, 0f, 260f, 0.78f);
            sa.addEndListener((anim, canceled, value, velocity) -> v.setLayerType(View.LAYER_TYPE_NONE, null));
        }, delay);
    }

    /** Slides a floating bar away (hide) or back (show). */
    public static void slideY(View v, boolean hide) {
        float to = hide ? v.getHeight() + Ui.dp(v.getContext(), 28) : 0f;
        if (!enabled(v.getContext())) {
            v.setTranslationY(to);
            return;
        }
        spring(v, DynamicAnimation.TRANSLATION_Y, to, 400f, hide ? 1f : 0.82f);
    }
}
