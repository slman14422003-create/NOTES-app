package com.semo.notes;

import android.content.Intent;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.core.splashscreen.SplashScreen;
import androidx.credentials.Credential;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.CustomCredential;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.GetCredentialCancellationException;
import androidx.credentials.exceptions.GetCredentialException;
import androidx.credentials.exceptions.NoCredentialException;

import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Launcher. Signed in (or already continuing without an account) -> straight to the home screen. Otherwise two ways in:
 * "Continue with Google" (the Google ID token is exchanged for a Firebase session, every account gets its own
 * private notes and they sync) or "Continue without signing in" (notes stay on this device only).
 */
public class LoginActivity extends SmoothActivity {
    /** Opened from inside the app: just close when done instead of starting the home screen. */
    public static final String EXTRA_FROM_APP = "from_app";
    private static final long SPLASH_MS = 1500;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView error;
    private Button btnGoogle, btnLocal;
    private IosSpinner busyBar;
    private boolean fromApp;
    private CancellationSignal cancel;
    private android.animation.ObjectAnimator breathe;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SplashScreen splash = SplashScreen.installSplashScreen(this);
        ThemeKit.apply(this);
        super.onCreate(savedInstanceState);
        if (Integrity.enforce(this)) return;
        fromApp = getIntent().getBooleanExtra(EXTRA_FROM_APP, false);
        // first launch: terms + privacy must be accepted before anything else opens
        if (!fromApp && !Prefs.termsAccepted(this)) {
            startActivity(new Intent(this, TermsActivity.class));
            finish();
            return;
        }
        // ChatGPT-style exit: the mark holds, then fades out while growing very slightly
        splash.setOnExitAnimationListener(p -> {
            View v = p.getView();
            v.animate().alpha(0f).scaleX(1.06f).scaleY(1.06f).setDuration(320)
                    .setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator())
                    .withEndAction(p::remove).start();
        });
        final long start = SystemClock.uptimeMillis();
        splash.setKeepOnScreenCondition(() -> !fromApp && SystemClock.uptimeMillis() - start < SPLASH_MS);

        if (!fromApp && Prefs.hasAccess(this)) {
            ui.postDelayed(() -> {
                if (isFinishing()) return;
                startActivity(new Intent(this, HomeActivity.class));
                finish();
            }, SPLASH_MS);
            return;
        }
        setContentView(R.layout.activity_login);
        error = findViewById(R.id.error);
        btnGoogle = findViewById(R.id.btnGoogle);
        btnLocal = findViewById(R.id.btnLocal);
        busyBar = findViewById(R.id.busy);
        Ui.press(this, btnGoogle);
        btnGoogle.setOnClickListener(v -> google());
        Ui.press(this, btnLocal);
        btnLocal.setOnClickListener(v -> continueLocal());
        // opened from Settings / the home screen to turn on sync: the user already is in local mode
        if (fromApp) btnLocal.setVisibility(View.GONE);
        buildLangRow();
        animateIn();
    }

    /** Two pills at the top: العربية | English (the current one is highlighted). */
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

    /** Soft accent glow behind the logo that slowly breathes, then the content rises in one after another. */
    private void animateIn() {
        View glow = findViewById(R.id.glow);
        int soft = Ui.color(this, R.attr.n_accent_soft);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                new int[]{soft, soft & 0x00FFFFFF});
        g.setGradientType(android.graphics.drawable.GradientDrawable.RADIAL_GRADIENT);
        g.setGradientRadius(Ui.dp(this, 95));
        g.setGradientCenter(0.5f, 0.5f);
        glow.setBackground(g);
        if (!Motion.enabled(this)) return;
        breathe = android.animation.ObjectAnimator.ofPropertyValuesHolder(glow,
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 0.9f, 1.12f),
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.9f, 1.12f),
                android.animation.PropertyValuesHolder.ofFloat(View.ALPHA, 0.65f, 1f));
        breathe.setDuration(2600);
        breathe.setRepeatCount(android.animation.ObjectAnimator.INFINITE);
        breathe.setRepeatMode(android.animation.ObjectAnimator.REVERSE);
        breathe.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        breathe.start();
        Motion.enter(findViewById(R.id.logoBox), 0);
        Motion.enter(findViewById(R.id.loginTitle), 1);
        Motion.enter(findViewById(R.id.loginSub), 2);
        Motion.enter(btnGoogle, 4);
        if (btnLocal.getVisibility() == View.VISIBLE) Motion.enter(btnLocal, 5);
        Motion.enter(findViewById(R.id.loginPrivacy), 6);
    }

    private void setBusy(boolean busy) {
        btnGoogle.setEnabled(!busy);
        btnLocal.setEnabled(!busy);
        busyBar.setVisibility(busy ? View.VISIBLE : View.INVISIBLE);
        if (busy) error.setVisibility(View.GONE);
    }

    private void showError(String m) {
        error.setText(m);
        error.setVisibility(View.VISIBLE);
    }

    /** Google account picker (Credential Manager) -> Google ID token -> Firebase session. */
    private void google() {
        final String webId = Cfg.webClientId();
        if (webId.isEmpty()) {
            showError(getString(R.string.err_google_missing));
            return;
        }
        if (!Sync.online(this)) {
            showError(getString(R.string.err_network));
            return;
        }
        setBusy(true);
        GetSignInWithGoogleOption option = new GetSignInWithGoogleOption.Builder(webId).build();
        GetCredentialRequest request = new GetCredentialRequest.Builder().addCredentialOption(option).build();
        cancel = new CancellationSignal();
        CredentialManager.create(this).getCredentialAsync(this, request, cancel,
                ContextCompat.getMainExecutor(this),
                new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                    @Override
                    public void onResult(GetCredentialResponse result) {
                        Credential cred = result.getCredential();
                        if (cred instanceof CustomCredential && GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                                .equals(cred.getType())) {
                            final String idToken;
                            try {
                                idToken = GoogleIdTokenCredential.createFrom(cred.getData()).getIdToken();
                            } catch (Exception e) {
                                setBusy(false);
                                showError(getString(R.string.err_google_failed, "TOKEN"));
                                return;
                            }
                            io.execute(() -> {
                                try {
                                    Firebase.Session s = Firebase.signInWithGoogle(idToken);
                                    ui.post(() -> signedIn(s));
                                } catch (Firebase.FbException ex) {
                                    ui.post(() -> {
                                        setBusy(false);
                                        showError(Firebase.friendly(LoginActivity.this, ex.code));
                                    });
                                }
                            });
                        } else {
                            setBusy(false);
                            showError(getString(R.string.err_google_failed, "TYPE"));
                        }
                    }

                    @Override
                    public void onError(GetCredentialException e) {
                        setBusy(false);
                        if (e instanceof GetCredentialCancellationException) return; // the user closed the picker
                        if (e instanceof NoCredentialException) {
                            showError(getString(R.string.err_google_none));
                        } else {
                            showError(getString(R.string.err_google_failed, String.valueOf(e.getType())));
                        }
                    }
                });
    }

    /** No account: the notes stay on this device only. Signing in later (Settings) uploads them. */
    private void continueLocal() {
        if (cancel != null) cancel.cancel();
        Prefs.setLocalMode(this);
        startActivity(new Intent(this, HomeActivity.class));
        finish();
    }

    private void signedIn(Firebase.Session s) {
        Prefs.setSession(this, s.uid, s.email, s.anonymous, s.idToken, s.refreshToken, s.expiresIn);
        Prefs.setCursor(this, "");
        String owner = Prefs.ownerUid(this);
        Db db = Db.get(this);
        if (owner.isEmpty() || owner.equals(s.uid)) {
            // notes written before any account existed, or this same account coming back: upload them
            db.markAllDirty();
        } else {
            // a different account than the one the notes on this device belong to: never mix them
            db.wipeAll();
            Toast.makeText(this, R.string.acct_switched, Toast.LENGTH_LONG).show();
        }
        Prefs.setOwnerUid(this, s.uid);
        Sync.request(this, true);
        if (!fromApp) startActivity(new Intent(this, HomeActivity.class));
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cancel != null) cancel.cancel();
        if (breathe != null) breathe.cancel();
        io.shutdown();
    }
}
