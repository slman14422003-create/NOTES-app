package com.semo.notes;

import android.content.Intent;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

/**
 * First launch only: a short terms + privacy summary. "Agree" continues to the sign-in / home screen,
 * "Exit" (or the back button) closes the app. Nothing else in the app opens until this is accepted.
 */
public class TermsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeKit.apply(this);
        super.onCreate(savedInstanceState);
        if (Integrity.enforce(this)) return;
        setContentView(R.layout.activity_terms);

        buildLangRow();
        TextView accept = findViewById(R.id.btnAccept);
        TextView exit = findViewById(R.id.btnExit);
        Ui.press(this, accept);
        Ui.press(this, exit);
        accept.setOnClickListener(v -> {
            Prefs.setTermsAccepted(this, true);
            startActivity(new Intent(this, LoginActivity.class));
            finish();
        });
        exit.setOnClickListener(v -> finishAffinity());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                finishAffinity();
            }
        });
    }

    /** Same two pills as the sign-in screen: العربية | English. */
    private void buildLangRow() {
        LinearLayout row = findViewById(R.id.langRow);
        row.removeAllViews();
        String cur = Prefs.language(this);
        if (cur.isEmpty()) cur = Lang.isAr(this) ? Lang.AR : Lang.EN;
        final String[] vals = {Lang.AR, Lang.EN};
        final int[] names = {R.string.lang_ar, R.string.lang_en};
        for (int i = 0; i < 2; i++) {
            final String v = vals[i];
            TextView c = Ui.chip(this, getString(names[i]), v.equals(cur));
            c.setOnClickListener(x -> {
                Prefs.setLanguage(this, v);
                Lang.apply(this);
            });
            row.addView(c);
        }
    }
}
