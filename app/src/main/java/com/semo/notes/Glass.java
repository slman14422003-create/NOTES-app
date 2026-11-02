package com.semo.notes;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ContextWrapper;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.ScrollView;

import eightbitlab.com.blurview.BlurTarget;
import eightbitlab.com.blurview.BlurView;

/** iOS-style frosted glass on top of the BlurView library: blur of what is behind + tint + hairline border. */
final class Glass {
    private Glass() {
    }

    private static final float RADIUS = 16f;
    static final int BAR_ALPHA = 190;
    static final int PILL_ALPHA = 175;

    /** colorAttr: R.attr.n_* used as tint; alpha 0..255; cornerDp 0 = square bar; outline = hairline border. */
    static void setup(BlurView v, BlurTarget target, int colorAttr, int alpha, int cornerDp, boolean outline) {
        Context c = v.getContext();
        int col = Ui.color(c, colorAttr);
        v.setupWith(target)
                .setFrameClearDrawable(new ColorDrawable(Ui.color(c, R.attr.n_bg) | 0xFF000000))
                .setBlurRadius(RADIUS)
                .setOverlayColor((col & 0x00FFFFFF) | (alpha << 24));
        if (cornerDp > 0 || outline) {
            GradientDrawable d = new GradientDrawable();
            d.setColor(Color.TRANSPARENT);
            d.setCornerRadius(Ui.dp(c, cornerDp));
            if (outline) {
                int stroke = (Ui.color(c, R.attr.n_stroke) & 0x00FFFFFF) | (0x99 << 24);
                d.setStroke(Math.max(1, Math.round(c.getResources().getDisplayMetrics().density * 0.6f)), stroke);
            }
            v.setBackground(d);
            v.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
            v.setClipToOutline(true);
        }
    }

    /** The page colour behind the glass changed (the editor's note colour). */
    static void base(BlurView v, int rgb, int alpha) {
        v.setBlurRadius(RADIUS).setFrameClearDrawable(new ColorDrawable(rgb | 0xFF000000));
        v.setOverlayColor((rgb & 0x00FFFFFF) | (alpha << 24));
    }

    /** Glass bar at the top: the scroll view's top padding follows the bar's height so the first row starts below it. */
    static void bindTop(BlurView bar, BlurTarget target, ScrollView sv) {
        setup(bar, target, R.attr.n_bg, BAR_ALPHA, 0, false);
        bar.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int h = b - t;
            if (h > 0 && sv.getPaddingTop() != h) sv.setPadding(0, h, 0, sv.getPaddingBottom());
        });
    }

    static void bindBottom(BlurView bar, BlurTarget target, ScrollView sv) {
        setup(bar, target, R.attr.n_bg, BAR_ALPHA, 0, false);
        bar.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int h = b - t;
            if (h > 0 && sv.getPaddingBottom() != h) sv.setPadding(0, sv.getPaddingTop(), 0, h);
        });
    }

    /** Settings / About: glass header over the scrolling page. */
    static void glassScreen(android.app.Activity a) {
        BlurView bar = a.findViewById(R.id.topBar);
        BlurTarget target = a.findViewById(R.id.blurTarget);
        ScrollView sv = a.findViewById(R.id.settingsScroll);
        if (bar == null || target == null || sv == null) return;
        bindTop(bar, target, sv);
    }

    /** Blur radius in pixels for a 0..100 level; 100 is about 30dp. */
    static float contentRadiusPx(Context c, int level) {
        return Ui.dp(c, 1) * level * 0.3f;
    }

    static Activity activityOf(Context c) {
        while (c instanceof ContextWrapper) {
            if (c instanceof Activity) return (Activity) c;
            c = ((ContextWrapper) c).getBaseContext();
        }
        return null;
    }

    /** Removes the blur from the screen content. */
    static void clearContent(Activity a) {
        if (a == null || Build.VERSION.SDK_INT < 31) return;
        contentFraction = 0f;
        contentStep = -1;
        a.getWindow().getDecorView().setRenderEffect(null);
    }

    /**
     * Blurs the whole screen of an activity inside the app (a render effect on its content), so it does not depend
     * on the system's cross-window blur setting. Strength follows the level chosen in the settings.
     * Below Android 12 nothing is blurred.
     */
    static ValueAnimator animateContent(final Activity a, final boolean in, final Runnable end) {
        final int level = a == null ? 0 : Prefs.blurLevel(a);
        if (a == null || Build.VERSION.SDK_INT < 31 || level <= 0) {
            if (end != null) end.run();
            return null;
        }
        final ValueAnimator va = ValueAnimator.ofFloat(in ? 0f : 1f, in ? 1f : 0f);
        va.setDuration(in ? 340 : 210);
        va.setInterpolator(new DecelerateInterpolator(1.6f));
        va.addUpdateListener(u -> setContentFraction(a, (float) u.getAnimatedValue()));
        va.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (!in) clearContent(a);
                if (end != null) end.run();
            }
        });
        va.start();
        return va;
    }

    private static float contentFraction;
    private static int contentStep = -1;

    /** Current blur amount of the screen content, 0..1. */
    static float contentFraction() {
        return contentFraction;
    }

    /** Sets the blur of the screen content to a fraction (0..1) of the chosen strength; the radius is stepped to avoid rebuilding the effect every frame. */
    static void setContentFraction(Activity a, float f) {
        if (a == null || Build.VERSION.SDK_INT < 31) return;
        contentFraction = f;
        float r = contentRadiusPx(a, Prefs.blurLevel(a)) * f;
        int step = Math.round(r / 1.5f);
        if (step == contentStep) return;
        contentStep = step;
        View v = a.getWindow().getDecorView();
        if (step <= 0) {
            v.setRenderEffect(null);
        } else {
            float rr = step * 1.5f;
            v.setRenderEffect(RenderEffect.createBlurEffect(rr, rr, Shader.TileMode.CLAMP));
        }
    }

    /** Fades the dim behind a window in or out. */
    static ValueAnimator animateBehind(final Window w, final float maxDim, final boolean in, final Runnable end) {
        w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        final ValueAnimator a = ValueAnimator.ofFloat(in ? 0f : 1f, in ? 1f : 0f);
        a.setDuration(in ? 340 : 210);
        a.setInterpolator(new DecelerateInterpolator(1.6f));
        a.addUpdateListener(u -> {
            try {
                WindowManager.LayoutParams p = w.getAttributes();
                p.dimAmount = maxDim * (float) u.getAnimatedValue();
                w.setAttributes(p);
            } catch (Throwable t) {
                a.cancel();
            }
        });
        a.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (end != null) end.run();
            }
        });
        a.start();
        return a;
    }

    /** Blurs and dims everything behind a dialog (the iOS alert backdrop), fading in; cleared when the dialog goes. */
    static void behind(Window w) {
        if (w == null || Prefs.blurLevel(w.getContext()) <= 0) return;
        try {
            final Activity act = activityOf(w.getContext());
            final ValueAnimator dim = animateBehind(w, 0.30f, true, null);
            final ValueAnimator blur = animateContent(act, true, null);
            w.getDecorView().addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View v) {
                }

                @Override
                public void onViewDetachedFromWindow(View v) {
                    dim.removeAllListeners();
                    dim.cancel();
                    if (blur != null) {
                        blur.removeAllListeners();
                        blur.cancel();
                    }
                    clearContent(act);
                }
            });
        } catch (Throwable ignored) {
        }
    }
}
