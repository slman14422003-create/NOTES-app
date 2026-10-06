package com.semo.notes;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/**
 * The app's loading indicator: the iPhone-style activity spinner (12 rounded spokes that fade around the circle,
 * the bright one steps clockwise) on a small round surface. Colours come from the active theme, so it follows the
 * palette and day / night mode. It only ticks while it is really visible on screen and stays still when the system
 * animations are turned off.
 */
public class IosSpinner extends View {
    private static final int SPOKES = 12;
    private static final long STEP_MS = 83; // one step = 30 degrees, a full turn takes about one second

    private Paint spoke, disc, ring;
    private Runnable tick;
    private float[] cos, sin;
    private int spokeColor;
    private int step;
    private boolean running;

    public IosSpinner(Context c) {
        super(c);
        init(c);
    }

    public IosSpinner(Context c, AttributeSet a) {
        super(c, a);
        init(c);
    }

    public IosSpinner(Context c, AttributeSet a, int defStyle) {
        super(c, a, defStyle);
        init(c);
    }

    private void init(Context c) {
        spoke = new Paint(Paint.ANTI_ALIAS_FLAG);
        spoke.setStyle(Paint.Style.STROKE);
        spoke.setStrokeCap(Paint.Cap.ROUND);
        disc = new Paint(Paint.ANTI_ALIAS_FLAG);
        disc.setStyle(Paint.Style.FILL);
        ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(Ui.dp(c, 1));
        spokeColor = Ui.color(c, R.attr.n_accent_text);
        disc.setColor(Ui.color(c, R.attr.n_surface));
        ring.setColor(Ui.color(c, R.attr.n_stroke_soft));
        cos = new float[SPOKES];
        sin = new float[SPOKES];
        for (int i = 0; i < SPOKES; i++) {
            double a = Math.toRadians(i * 30.0 - 90.0); // spoke 0 points up, then clockwise
            cos[i] = (float) Math.cos(a);
            sin[i] = (float) Math.sin(a);
        }
        tick = new Runnable() {
            @Override
            public void run() {
                step = (step + 1) % SPOKES;
                invalidate();
                if (running) postDelayed(this, STEP_MS);
            }
        };
        sync();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int d = Ui.dp(getContext(), 34);
        setMeasuredDimension(resolveSize(d, widthSpec), resolveSize(d, heightSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f;
        float r = Math.min(w, h) / 2f;
        float edge = r - ring.getStrokeWidth() / 2f;
        canvas.drawCircle(cx, cy, edge, disc);
        canvas.drawCircle(cx, cy, edge, ring);
        float outer = r * 0.60f, inner = r * 0.28f;
        spoke.setStrokeWidth(r * 0.12f);
        for (int i = 0; i < SPOKES; i++) {
            int age = (step - i + SPOKES) % SPOKES; // 0 = the bright head, older spokes fade behind it
            float a = 1f - 0.84f * age / (SPOKES - 1f);
            spoke.setColor(spokeColor);
            spoke.setAlpha(Math.round(Color.alpha(spokeColor) * a));
            canvas.drawLine(cx + cos[i] * inner, cy + sin[i] * inner,
                    cx + cos[i] * outer, cy + sin[i] * outer, spoke);
        }
    }

    /** Starts or stops the ticking depending on whether the view is on screen. */
    private void sync() {
        if (tick == null) return; // called by the View constructor before init() ran
        boolean want = isShown() && Motion.enabled(getContext());
        if (want == running) return;
        running = want;
        removeCallbacks(tick);
        if (running) postDelayed(tick, STEP_MS);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        sync();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        running = false;
        removeCallbacks(tick);
    }

    @Override
    protected void onVisibilityChanged(View changed, int visibility) {
        super.onVisibilityChanged(changed, visibility);
        sync();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        sync();
    }
}
