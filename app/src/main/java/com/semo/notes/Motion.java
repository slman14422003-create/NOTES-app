package com.semo.notes;

import android.annotation.SuppressLint;
import android.content.Context;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;

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

    /** Slightly shrinks the view while it is pressed and bounces back when released. */
    @SuppressLint("ClickableViewAccessibility")
    public static void pressable(final View v) {
        if (v == null) return;
        v.setStateListAnimator(null);
        v.setOnTouchListener((view, e) -> {
            if (!enabled(view.getContext())) return false;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    scale(view, 0.95f, SpringForce.STIFFNESS_MEDIUM, SpringForce.DAMPING_RATIO_NO_BOUNCY);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    scale(view, 1f, 420f, 0.5f);
                    break;
                default:
                    break;
            }
            return false; // never consume: clicks, long presses and ripples keep working
        });
    }

    private static void scale(View v, float to, float stiffness, float damping) {
        spring(v, DynamicAnimation.SCALE_X, to, stiffness, damping);
        spring(v, DynamicAnimation.SCALE_Y, to, stiffness, damping);
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
        v.postDelayed(() -> {
            v.animate().alpha(1f).setDuration(260).start();
            spring(v, DynamicAnimation.TRANSLATION_Y, 0f, 260f, 0.78f);
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
