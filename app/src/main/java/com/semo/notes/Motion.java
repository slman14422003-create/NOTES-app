package com.semo.notes;

import android.app.Activity;
import android.content.Intent;
import android.content.res.TypedArray;
import android.content.Context;
import android.os.Build;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import java.lang.ref.WeakReference;

final class Motion {
    private static WeakReference<View> pressed;

    private Motion() {
    }

    static boolean enabled(Context c) {
        try {
            return Settings.Global.getFloat(c.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f;
        } catch (Throwable t) {
            return true;
        }
    }

    static void pressable(View v) {
        if (v == null) return;
        v.setOnTouchListener((view, e) -> {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) {
                if (enabled(view.getContext())) {
                    pressed = new WeakReference<>(view);
                    view.animate().cancel();
                    view.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90).start();
                }
            } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                back(view);
            }
            return false;
        });
    }

    private static void back(View v) {
        v.animate().cancel();
        SpringAnimation x = new SpringAnimation(v, DynamicAnimation.SCALE_X, 1f);
        SpringAnimation y = new SpringAnimation(v, DynamicAnimation.SCALE_Y, 1f);
        x.getSpring().setStiffness(SpringForce.STIFFNESS_MEDIUM).setDampingRatio(0.55f);
        y.getSpring().setStiffness(SpringForce.STIFFNESS_MEDIUM).setDampingRatio(0.55f);
        x.start();
        y.start();
    }

    static void track(Activity a, MotionEvent e) {
        int act = e.getActionMasked();
        if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) release();
    }

    static void release() {
        View v = pressed == null ? null : pressed.get();
        pressed = null;
        if (v != null) back(v);
    }

    static void enter(View v, int index) {
        if (v == null || !enabled(v.getContext())) return;
        float d = v.getResources().getDisplayMetrics().density;
        v.animate().cancel();
        v.setAlpha(0f);
        v.setTranslationY(14f * d);
        v.animate().alpha(1f).translationY(0f).setStartDelay(60L * Math.max(0, index)).setDuration(380)
                .setInterpolator(new DecelerateInterpolator(2f)).start();
    }

    static void slideY(View v, boolean hide) {
        if (v == null) return;
        float d = v.getResources().getDisplayMetrics().density;
        float to = hide ? v.getHeight() + 40f * d : 0f;
        v.animate().cancel();
        if (!enabled(v.getContext())) {
            v.setTranslationY(to);
            return;
        }
        v.animate().translationY(to).setDuration(hide ? 220 : 300).setInterpolator(new DecelerateInterpolator(1.8f)).start();
    }

    static void spring(View v, DynamicAnimation.ViewProperty prop, float target, float stiffness, float damping) {
        if (v == null) return;
        if (!enabled(v.getContext())) {
            prop.setValue(v, target);
            return;
        }
        SpringAnimation sa = new SpringAnimation(v, prop, target);
        sa.getSpring().setStiffness(stiffness).setDampingRatio(damping);
        sa.start();
    }

    static void install(Activity a) {
        if (Build.VERSION.SDK_INT < 34) return;
        if ((a.getIntent().getFlags() & Intent.FLAG_ACTIVITY_NO_ANIMATION) != 0) return;
        TypedArray t = a.getTheme().obtainStyledAttributes(new int[]{android.R.attr.windowIsFloating, android.R.attr.windowIsTranslucent});
        boolean skip = t.getBoolean(0, false) || t.getBoolean(1, false);
        t.recycle();
        if (skip) return;
        a.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, R.anim.act_open_enter, R.anim.act_open_exit);
        a.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, R.anim.act_close_enter, R.anim.act_close_exit);
    }
}
