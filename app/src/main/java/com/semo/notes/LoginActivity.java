package com.semo.notes;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.splashscreen.SplashScreen;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Launcher. First run: choose how to keep notes (Firebase account, guest or local only).
 * Afterwards it goes straight to the home screen.
 */
public class LoginActivity extends AppCompatActivity {
    /** Opened from inside the app (settings / sync button): do not skip, and just close when done. */
    public static final String EXTRA_FROM_APP = "from_app";
    private static final long SPLASH_MS = 700;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private EditText email, password;
    private TextView error;
    private Button btnLogin, btnSignUp, btnGuest, btnLocal;
    private boolean fromApp;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SplashScreen splash = SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);
        fromApp = getIntent().getBooleanExtra(EXTRA_FROM_APP, false);

        if (!fromApp && !Prefs.MODE_NONE.equals(Prefs.mode(this))) {
            final long start = SystemClock.uptimeMillis();
            splash.setKeepOnScreenCondition(() -> SystemClock.uptimeMillis() - start < SPLASH_MS);
            ui.postDelayed(() -> {
                if (isFinishing()) return;
                startActivity(new Intent(this, HomeActivity.class));
                finish();
            }, SPLASH_MS);
            return;
        }
        final long start = SystemClock.uptimeMillis();
        splash.setKeepOnScreenCondition(() -> SystemClock.uptimeMillis() - start < SPLASH_MS);
        splash.setOnExitAnimationListener(p -> p.getView().animate().alpha(0f).scaleX(0.92f).scaleY(0.92f)
                .setDuration(220).withEndAction(p::remove).start());

        setContentView(R.layout.activity_login);
        email = findViewById(R.id.email);
        password = findViewById(R.id.password);
        error = findViewById(R.id.error);
        btnLogin = findViewById(R.id.btnLogin);
        btnSignUp = findViewById(R.id.btnSignUp);
        btnGuest = findViewById(R.id.btnGuest);
        btnLocal = findViewById(R.id.btnLocal);
        if (fromApp) btnLocal.setVisibility(View.GONE);

        btnLogin.setOnClickListener(v -> auth(false));
        btnSignUp.setOnClickListener(v -> auth(true));
        btnGuest.setOnClickListener(v -> guest());
        btnLocal.setOnClickListener(v -> {
            Prefs.setLocalOnly(this);
            done();
        });
        findViewById(R.id.btnForgot).setOnClickListener(v -> forgot());
    }

    private void setBusy(boolean busy) {
        btnLogin.setEnabled(!busy);
        btnSignUp.setEnabled(!busy);
        btnGuest.setEnabled(!busy);
        btnLocal.setEnabled(!busy);
        if (busy) error.setVisibility(View.GONE);
    }

    private void showError(String m) {
        error.setText(m);
        error.setVisibility(View.VISIBLE);
    }

    private void auth(final boolean create) {
        final String e = email.getText().toString().trim();
        final String p = password.getText().toString();
        if (e.isEmpty() || p.isEmpty()) {
            showError(getString(R.string.err_fill_fields));
            return;
        }
        setBusy(true);
        io.execute(() -> {
            try {
                Firebase.Session s = create ? Firebase.signUp(e, p) : Firebase.signIn(e, p);
                ui.post(() -> signedIn(s));
            } catch (Firebase.FbException ex) {
                ui.post(() -> {
                    setBusy(false);
                    showError(Firebase.friendly(this, ex.code));
                });
            }
        });
    }

    private void guest() {
        setBusy(true);
        io.execute(() -> {
            try {
                Firebase.Session s = Firebase.signInAnonymously();
                ui.post(() -> signedIn(s));
            } catch (Firebase.FbException ex) {
                ui.post(() -> {
                    setBusy(false);
                    showError(Firebase.friendly(this, ex.code));
                });
            }
        });
    }

    private void forgot() {
        final String e = email.getText().toString().trim();
        if (e.isEmpty()) {
            showError(getString(R.string.err_invalid_email));
            return;
        }
        setBusy(true);
        io.execute(() -> {
            try {
                Firebase.sendPasswordReset(e);
                ui.post(() -> {
                    setBusy(false);
                    Toast.makeText(this, R.string.reset_sent, Toast.LENGTH_LONG).show();
                });
            } catch (Firebase.FbException ex) {
                ui.post(() -> {
                    setBusy(false);
                    showError(Firebase.friendly(this, ex.code));
                });
            }
        });
    }

    private void signedIn(Firebase.Session s) {
        Prefs.setSession(this, s.uid, s.email, s.anonymous, s.idToken, s.refreshToken, s.expiresIn);
        Prefs.setCursor(this, "");
        Db.get(this).markAllDirty(); // notes written before signing in are uploaded to this account
        Sync.request(this, true);
        done();
    }

    private void done() {
        if (!fromApp) startActivity(new Intent(this, HomeActivity.class));
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdown();
    }
}
