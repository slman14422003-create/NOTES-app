package com.semo.notes;

import android.app.KeyguardManager;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

/** Opaque lock screen: fingerprint / face / PIN / pattern of the phone. Back leaves the app. */
public class LockActivity extends AppCompatActivity {
    private static final int RC = 7301;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private CancellationSignal cancel;
    private boolean busy, auto = true;
    private android.widget.Button btnUnlock;

    @Override
    protected void onCreate(Bundle b) {
        ThemeKit.apply(this);
        super.onCreate(b);
        if (Integrity.enforce(this)) return;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        AppLock.shown(true);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                finishAffinity();
            }
        });

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Ui.color(this, R.attr.n_bg));
        int p = Ui.dp(this, 32);
        root.setPadding(p, p, p, p);

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_lock);
        icon.setImageTintList(ColorStateList.valueOf(Ui.color(this, R.attr.n_accent_text)));
        icon.setBackgroundResource(R.drawable.bg_circle_accent);
        int ip = Ui.dp(this, 22);
        icon.setPadding(ip, ip, ip, ip);
        root.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 88), Ui.dp(this, 88)));

        TextView t = new TextView(this);
        t.setText(R.string.lock_title);
        t.setTextColor(Ui.color(this, R.attr.n_text_primary));
        t.setTextSize(24);
        t.setTypeface(Typeface.SERIF);
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, -2);
        tl.topMargin = Ui.dp(this, 20);
        root.addView(t, tl);

        TextView m = new TextView(this);
        m.setText(R.string.lock_msg);
        m.setTextColor(Ui.color(this, R.attr.n_text_secondary));
        m.setTextSize(15);
        m.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-1, -2);
        ml.topMargin = Ui.dp(this, 8);
        root.addView(m, ml);

        btnUnlock = Ui.button(this, R.string.unlock, true);
        btnUnlock.setOnClickListener(v -> {
            auto = true;
            prompt();
        });
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, -2);
        bl.topMargin = Ui.dp(this, 28);
        root.addView(btnUnlock, bl);

        android.widget.Button exit = Ui.button(this, R.string.lock_exit, false);
        exit.setOnClickListener(v -> finishAffinity());
        LinearLayout.LayoutParams el = new LinearLayout.LayoutParams(-1, -2);
        el.topMargin = Ui.dp(this, 10);
        root.addView(exit, el);

        setContentView(root, new ViewGroup.LayoutParams(-1, -1));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (auto && !busy) ui.postDelayed(this::prompt, 120);
    }

    private void prompt() {
        if (busy || isFinishing() || isDestroyed()) return;
        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (km == null || !km.isDeviceSecure()) {
            Prefs.setAppLock(this, false); // the phone lock was removed: nothing to check against
            done();
            return;
        }
        busy = true;
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                BiometricPrompt bp = new BiometricPrompt.Builder(this)
                        .setTitle(getString(R.string.lock_title))
                        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK
                                | BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                        .build();
                cancel = new CancellationSignal();
                bp.authenticate(cancel, getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult r) {
                        busy = false;
                        done();
                    }

                    @Override
                    public void onAuthenticationError(int code, CharSequence msg) {
                        busy = false;
                        if (code == 10 || code == 13) auto = false; // user cancelled / negative button
                    }
                });
                return;
            } catch (Throwable ignored) {
                // fall back to the system credential screen below
            }
        }
        Intent i = km.createConfirmDeviceCredentialIntent(getString(R.string.lock_title), getString(R.string.lock_msg));
        if (i == null) {
            busy = false;
            done();
            return;
        }
        startActivityForResult(i, RC);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != RC) return;
        busy = false;
        if (res == RESULT_OK) done();
        else auto = false;
    }

    private void done() {
        AppLock.unlock();
        finish();
        overridePendingTransition(0, 0);
    }

    @Override
    protected void onDestroy() {
        if (cancel != null) {
            try {
                cancel.cancel();
            } catch (Throwable ignored) {
            }
        }
        ui.removeCallbacksAndMessages(null);
        AppLock.shown(false);
        super.onDestroy();
    }
}
