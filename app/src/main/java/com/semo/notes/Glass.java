package com.semo.notes;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowManager;
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

    /** Blurs everything behind a dialog window (the iOS alert backdrop). No-op below Android 12 or when blur is off. */
    static void behind(Window w) {
        if (w == null || Build.VERSION.SDK_INT < 31) return;
        try {
            Context c = w.getContext();
            WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null || !wm.isCrossWindowBlurEnabled()) return;
            w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
            WindowManager.LayoutParams p = w.getAttributes();
            p.setBlurBehindRadius(Ui.dp(c, 18));
            p.dimAmount = 0.30f;
            w.setAttributes(p);
        } catch (Throwable ignored) {
        }
    }
}
