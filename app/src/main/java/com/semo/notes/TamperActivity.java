package com.semo.notes;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Shown instead of the app when the APK was modified, re-signed or is being debugged. */
public class TamperActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.BLACK);
        int p = (int) (28 * getResources().getDisplayMetrics().density);
        root.setPadding(p, p, p, p);

        TextView t = new TextView(this);
        t.setText(R.string.tamper_title);
        t.setTextColor(Color.WHITE);
        t.setTextSize(24);
        t.setTypeface(Typeface.SERIF);
        t.setGravity(Gravity.CENTER);
        root.addView(t);

        TextView m = new TextView(this);
        m.setText(R.string.tamper_msg);
        m.setTextColor(0xFFB4B4B4);
        m.setTextSize(15);
        m.setGravity(Gravity.CENTER);
        m.setLineSpacing(0, 1.2f);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ml.topMargin = p / 2;
        root.addView(m, ml);

        Button b = new Button(this);
        b.setText(R.string.tamper_exit);
        b.setAllCaps(false);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bl.topMargin = p;
        b.setOnClickListener(v -> finishAffinity());
        root.addView(b, bl);
        setContentView(root);
    }
}
