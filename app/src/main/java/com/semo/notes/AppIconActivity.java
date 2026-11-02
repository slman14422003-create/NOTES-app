package com.semo.notes;

import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Grid of launcher icon variants, iOS-style: tap one, the selection ring springs in and the icon switches. */
public class AppIconActivity extends SmoothActivity {
    private LinearLayout content;
    private final View[] rings = new View[IconKit.ALL.length];
    private final TextView[] labels = new TextView[IconKit.ALL.length];
    private int selected;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeKit.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        Glass.glassScreen(this);
        ((TextView) findViewById(R.id.title)).setText(R.string.app_icon);
        findViewById(R.id.subtitle).setVisibility(View.GONE);
        findViewById(R.id.btnBack).setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        Ui.press(this, findViewById(R.id.btnBack));
        findViewById(R.id.btnA1).setVisibility(View.GONE);
        findViewById(R.id.btnA2).setVisibility(View.GONE);
        findViewById(R.id.btnRefresh).setVisibility(View.INVISIBLE);
        content = findViewById(R.id.content);
        build();
    }

    private void build() {
        content.removeAllViews();
        IconKit.Variant cur = IconKit.current(this);
        for (int i = 0; i < IconKit.ALL.length; i++) if (IconKit.ALL[i] == cur) selected = i;

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        int pad = Ui.dp(this, 10);
        card.setPadding(pad, Ui.dp(this, 14), pad, Ui.dp(this, 8));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cl.setMargins(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 6));
        card.setLayoutParams(cl);

        final int cols = 3;
        LinearLayout row = null;
        for (int i = 0; i < IconKit.ALL.length; i++) {
            if (i % cols == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                card.addView(row, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            row.addView(cell(i), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        content.addView(card);

        TextView hint = new TextView(this);
        hint.setText(R.string.app_icon_note);
        hint.setTextSize(13);
        hint.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        hint.setTextColor(Ui.color(this, R.attr.n_text_secondary));
        hint.setPadding(Ui.dp(this, 24), Ui.dp(this, 8), Ui.dp(this, 24), Ui.dp(this, 8));
        content.addView(hint);
    }

    private View cell(final int index) {
        final IconKit.Variant v = IconKit.ALL[index];
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 8));

        int ringSize = Ui.dp(this, 84);
        FrameLayout holder = new FrameLayout(this);
        View ring = new View(this);
        GradientDrawable rg = new GradientDrawable();
        rg.setCornerRadius(Ui.dp(this, 26));
        rg.setColor(0);
        rg.setStroke(Ui.dp(this, 3), Ui.color(this, R.attr.n_accent_text));
        ring.setBackground(rg);
        boolean sel = index == selected;
        ring.setAlpha(sel ? 1f : 0f);
        rings[index] = ring;
        holder.addView(ring, new FrameLayout.LayoutParams(ringSize, ringSize, Gravity.CENTER));

        final int box = Ui.dp(this, 68);
        FrameLayout clip = new FrameLayout(this);
        clip.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), view.getWidth() * 0.30f);
            }
        });
        clip.setClipToOutline(true);
        ImageView img = new ImageView(this);
        LayerDrawable ld = new LayerDrawable(new android.graphics.drawable.Drawable[]{
                getDrawable(v.bg).mutate(), getDrawable(v.fg).mutate()});
        img.setImageDrawable(ld);
        img.setScaleType(ImageView.ScaleType.FIT_XY);
        int big = Math.round(box * 1.5f);
        clip.addView(img, new FrameLayout.LayoutParams(big, big, Gravity.CENTER));
        holder.addView(clip, new FrameLayout.LayoutParams(box, box, Gravity.CENTER));
        cell.addView(holder, new LinearLayout.LayoutParams(ringSize, ringSize));

        TextView name = new TextView(this);
        name.setText(v.name);
        name.setTextSize(14);
        name.setGravity(Gravity.CENTER);
        name.setSingleLine(true);
        name.setTextColor(Ui.color(this, sel ? R.attr.n_accent_text : R.attr.n_text_secondary));
        if (sel) name.setTypeface(name.getTypeface(), android.graphics.Typeface.BOLD);
        labels[index] = name;
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nl.topMargin = Ui.dp(this, 6);
        cell.addView(name, nl);

        Ui.press(this, cell);
        cell.setOnClickListener(x -> choose(index));
        return cell;
    }

    private void choose(int index) {
        if (index == selected) return;
        int old = selected;
        selected = index;
        Motion.tick(content);
        rings[old].animate().alpha(0f).setDuration(160).start();
        labels[old].setTextColor(Ui.color(this, R.attr.n_text_secondary));
        labels[old].setTypeface(null, android.graphics.Typeface.NORMAL);
        labels[index].setTextColor(Ui.color(this, R.attr.n_accent_text));
        labels[index].setTypeface(null, android.graphics.Typeface.BOLD);
        View ring = rings[index];
        ring.setAlpha(1f);
        ring.setScaleX(1.25f);
        ring.setScaleY(1.25f);
        Motion.spring(ring, androidx.dynamicanimation.animation.DynamicAnimation.SCALE_X, 1f, 380f, 0.55f);
        Motion.spring(ring, androidx.dynamicanimation.animation.DynamicAnimation.SCALE_Y, 1f, 380f, 0.55f);
        try {
            IconKit.apply(this, IconKit.ALL[index]);
        } catch (Throwable t) {
            android.widget.Toast.makeText(this, R.string.file_error, android.widget.Toast.LENGTH_SHORT).show();
        }
    }
}
