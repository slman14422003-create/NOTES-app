package com.semo.notes;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
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

    /** True when the platform can blur what is behind a window and the user has not switched the blur off. */
    static boolean behindSupported(Context c) {
        if (Build.VERSION.SDK_INT < 31 || Prefs.blurLevel(c) <= 0) return false;
        WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        return wm != null && wm.isCrossWindowBlurEnabled();
    }

    /** Blur radius in pixels for a 0..100 level (100 is about 50dp, the strength of an iOS context menu backdrop). */
    static int radiusPx(Context c, int level) {
        return Ui.dp(c, Math.round(level * 0.5f));
    }

    /**
     * Fades the blur and the dim behind a window in or out. Blur strength follows the level chosen in the settings;
     * where window blur is not available only the dim is animated.
     */
    static ValueAnimator animateBehind(final Window w, final float maxDim, final boolean in, final Runnable end) {
        final Context c = w.getContext();
        final boolean blur = behindSupported(c);
        final int maxR = blur ? radiusPx(c, Prefs.blurLevel(c)) : 0;
        if (blur) w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
        w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        final ValueAnimator a = ValueAnimator.ofFloat(in ? 0f : 1f, in ? 1f : 0f);
        a.setDuration(in ? 340 : 210);
        a.setInterpolator(new DecelerateInterpolator(1.6f));
        a.addUpdateListener(u -> {
            float f = (float) u.getAnimatedValue();
            try {
                WindowManager.LayoutParams p = w.getAttributes();
                p.dimAmount = maxDim * f;
                if (blur && Build.VERSION.SDK_INT >= 31) p.setBlurBehindRadius(Math.round(maxR * f));
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

    /** Blurs everything behind a dialog window (the iOS alert backdrop), fading in. */
    static void behind(Window w) {
        if (w == null || !behindSupported(w.getContext())) return;
        try {
            final ValueAnimator a = animateBehind(w, 0.30f, true, null);
            w.getDecorView().addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View v) {
                }

                @Override
                public void onViewDetachedFromWindow(View v) {
                    a.removeAllListeners();
                    a.cancel();
                }
            });
        } catch (Throwable ignored) {
        }
    }
}
