package com.semo.notes;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.dynamicanimation.animation.DynamicAnimation;

import java.util.List;

/**
 * iOS-style context menu: on long press the screen behind blurs (strength from the settings), the pressed card lifts
 * on a spring and a rounded action menu pops out next to it.
 */
final class LiftMenu {
    private LiftMenu() {
    }

    static final class Item {
        final CharSequence label;
        final int icon;
        final boolean destructive;
        final Runnable action;

        Item(CharSequence label, int icon, boolean destructive, Runnable action) {
            this.label = label;
            this.icon = icon;
            this.destructive = destructive;
            this.action = action;
        }
    }

    static void show(final Activity a, final View anchor, final List<Item> items) {
        if (anchor == null || anchor.getWidth() == 0 || anchor.getHeight() == 0 || a.isFinishing() || items.isEmpty()) return;
        new Session(a, anchor, items).start();
    }

    private static final class Session {
        final Activity a;
        final View anchor;
        final List<Item> items;
        final Dialog dialog;
        final FrameLayout root;
        ImageView snap;
        LinearLayout menu;
        boolean closing;
        ValueAnimator backdrop;

        Session(Activity a, View anchor, List<Item> items) {
            this.a = a;
            this.anchor = anchor;
            this.items = items;
            this.dialog = new Dialog(a, android.R.style.Theme_Translucent_NoTitleBar) {
                @Override
                public void onBackPressed() {
                    close(null);
                }
            };
            this.root = new FrameLayout(a);
        }

        void start() {
            Window w = dialog.getWindow();
            if (w == null) return;
            root.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
            root.setOnClickListener(v -> close(null));
            dialog.setContentView(root, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            w.setBackgroundDrawable(new ColorDrawable(0));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            w.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
            w.setWindowAnimations(0);
            dialog.setCanceledOnTouchOutside(false);

            Bitmap bm = Bitmap.createBitmap(anchor.getWidth(), anchor.getHeight(), Bitmap.Config.ARGB_8888);
            anchor.draw(new Canvas(bm));
            snap = new ImageView(a);
            snap.setImageBitmap(bm);
            snap.setVisibility(View.INVISIBLE);
            final float radius = anchor.getHeight() < Ui.dp(a, 90) ? Ui.dp(a, 20) : Ui.dp(a, 28);
            snap.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View v, Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
                    o.setAlpha(0.9f);
                }
            });
            snap.setClipToOutline(true);
            snap.setElevation(Ui.dp(a, 26));
            root.addView(snap, new FrameLayout.LayoutParams(anchor.getWidth(), anchor.getHeight()));

            menu = buildMenu();
            menu.setVisibility(View.INVISIBLE);
            root.addView(menu, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            dialog.show();
            Motion.thud(snap);
            backdrop = Glass.animateBehind(w, 0.28f, true, null);
            root.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                boolean done;

                @Override
                public void onGlobalLayout() {
                    if (done || root.getWidth() == 0) return;
                    done = true;
                    root.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                    place();
                }
            });
        }

        LinearLayout buildMenu() {
            LinearLayout m = new LinearLayout(a);
            m.setOrientation(LinearLayout.VERTICAL);
            m.setLayoutDirection(a.getResources().getConfiguration().getLayoutDirection());
            GradientDrawable bg = new GradientDrawable();
            bg.setColor((Ui.color(a, R.attr.n_surface) & 0x00FFFFFF) | 0xF4000000);
            bg.setCornerRadius(Ui.dp(a, 22));
            bg.setStroke(Math.max(1, Ui.dp(a, 1) / 2), Ui.color(a, R.attr.n_stroke_soft));
            m.setBackground(bg);
            m.setClipToOutline(true);
            m.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
            m.setElevation(Ui.dp(a, 18));
            m.setClickable(true);
            for (int i = 0; i < items.size(); i++) {
                final Item it = items.get(i);
                if (i > 0) {
                    View line = new View(a);
                    line.setBackgroundColor(Ui.color(a, R.attr.n_stroke_soft));
                    m.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            Math.max(1, Ui.dp(a, 1) / 2)));
                }
                LinearLayout row = new LinearLayout(a);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setMinimumHeight(Ui.dp(a, 52));
                row.setPaddingRelative(Ui.dp(a, 18), 0, Ui.dp(a, 16), 0);
                row.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.color(a, R.attr.n_ripple)), null, null));
                int col = Ui.color(a, it.destructive ? R.attr.n_bad : R.attr.n_text_primary);

                TextView t = new TextView(a);
                t.setText(it.label);
                t.setTextSize(16);
                t.setSingleLine(true);
                t.setTextColor(col);
                t.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
                t.setTypeface(Typeface.DEFAULT);
                row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                if (it.icon != 0) {
                    ImageView iv = new ImageView(a);
                    iv.setImageResource(it.icon);
                    iv.setImageTintList(ColorStateList.valueOf(col));
                    LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(Ui.dp(a, 22), Ui.dp(a, 22));
                    ip.setMarginStart(Ui.dp(a, 18));
                    row.addView(iv, ip);
                }
                row.setOnClickListener(v -> {
                    Motion.tick(v);
                    close(it.action);
                });
                m.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            return m;
        }

        @SuppressWarnings("deprecation")
        void place() {
            int[] loc = new int[2];
            anchor.getLocationOnScreen(loc);
            int[] ro = new int[2];
            root.getLocationOnScreen(ro);
            final int ax = loc[0] - ro[0];
            final int ay = loc[1] - ro[1];
            final int aw = anchor.getWidth();
            final int ah = anchor.getHeight();
            final int sw = root.getWidth();
            final int sh = root.getHeight();
            final int margin = Ui.dp(a, 14);
            final int gap = Ui.dp(a, 12);
            WindowInsets ins = root.getRootWindowInsets();
            final int topInset = ins == null ? Ui.dp(a, 24) : ins.getSystemWindowInsetTop();
            final int bottomInset = ins == null ? Ui.dp(a, 16) : ins.getSystemWindowInsetBottom();

            int maxW = Math.min(Ui.dp(a, 260), sw - 2 * margin);
            menu.measure(View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            final int mw = Math.max(menu.getMeasuredWidth(), Math.min(Ui.dp(a, 220), maxW));
            final int mh = menu.getMeasuredHeight();

            final int limitBottom = sh - bottomInset - margin;
            final int need = mh + gap;
            float shift = 0f;
            if (ay + ah + need > limitBottom) {
                float want = ay + ah + need - limitBottom;
                float maxUp = Math.max(0, ay - (topInset + margin));
                shift = -Math.min(want, maxUp);
            }
            final float fShift = shift;
            boolean below = ay + ah + shift + need <= limitBottom + 1;
            int menuTop;
            if (below) {
                menuTop = Math.round(ay + ah + shift + gap);
            } else if (ay + shift - gap - mh >= topInset + margin) {
                menuTop = Math.round(ay + shift - gap - mh);
            } else {
                menuTop = limitBottom - mh;
                below = true;
            }
            final boolean fBelow = below;
            boolean left = ax + aw / 2f < sw / 2f;
            int menuLeft = left ? ax : ax + aw - mw;
            menuLeft = Math.max(margin, Math.min(menuLeft, sw - margin - mw));

            FrameLayout.LayoutParams sp = (FrameLayout.LayoutParams) snap.getLayoutParams();
            sp.gravity = Gravity.TOP | Gravity.LEFT;
            sp.leftMargin = ax;
            sp.topMargin = ay;
            snap.setLayoutParams(sp);

            FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(mw, mh);
            mp.gravity = Gravity.TOP | Gravity.LEFT;
            mp.leftMargin = menuLeft;
            mp.topMargin = menuTop;
            menu.setLayoutParams(mp);

            snap.setPivotX(aw / 2f);
            snap.setPivotY(ah / 2f);
            snap.setScaleX(0.96f);
            snap.setScaleY(0.96f);
            snap.setVisibility(View.VISIBLE);
            anchor.setVisibility(View.INVISIBLE);

            Motion.spring(snap, DynamicAnimation.SCALE_X, 1.04f, 320f, 0.55f);
            Motion.spring(snap, DynamicAnimation.SCALE_Y, 1.04f, 320f, 0.55f);
            if (fShift != 0f) Motion.spring(snap, DynamicAnimation.TRANSLATION_Y, fShift, 300f, 0.8f);

            menu.setPivotX(left ? Math.min(mw, aw / 2f) : Math.max(0, mw - aw / 2f));
            menu.setPivotY(fBelow ? 0f : mh);
            menu.setAlpha(0f);
            menu.setScaleX(0.35f);
            menu.setScaleY(0.35f);
            menu.setVisibility(View.VISIBLE);
            menu.animate().alpha(1f).setStartDelay(30).setDuration(170).start();
            menu.postDelayed(() -> {
                Motion.spring(menu, DynamicAnimation.SCALE_X, 1f, 420f, 0.66f);
                Motion.spring(menu, DynamicAnimation.SCALE_Y, 1f, 420f, 0.66f);
            }, 30);
        }

        void close(final Runnable after) {
            if (closing) return;
            closing = true;
            if (backdrop != null) {
                backdrop.removeAllListeners();
                backdrop.cancel();
            }
            Window w = dialog.getWindow();
            if (snap != null) {
                Motion.spring(snap, DynamicAnimation.SCALE_X, 1f, 500f, 0.8f);
                Motion.spring(snap, DynamicAnimation.SCALE_Y, 1f, 500f, 0.8f);
                Motion.spring(snap, DynamicAnimation.TRANSLATION_Y, 0f, 500f, 0.8f);
            }
            if (menu != null) {
                menu.animate().alpha(0f).scaleX(0.85f).scaleY(0.85f).setStartDelay(0).setDuration(150).start();
            }
            final Runnable finish = () -> {
                anchor.setVisibility(View.VISIBLE);
                try {
                    dialog.dismiss();
                } catch (Throwable ignored) {
                }
                if (after != null) after.run();
            };
            if (w == null) {
                finish.run();
                return;
            }
            Glass.animateBehind(w, 0.28f, false, finish);
        }
    }
}
