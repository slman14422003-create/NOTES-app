package com.semo.notes;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateUtils;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SettingsActivity extends SmoothActivity implements Sync.Listener {
    private LinearLayout content;
    private ProgressBar loading;
    private Db db;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private ActivityResultLauncher<String> exportLauncher;
    private ActivityResultLauncher<String[]> importLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeKit.apply(this);
        super.onCreate(savedInstanceState);
        if (Integrity.enforce(this)) return;
        setContentView(R.layout.activity_settings);
        db = Db.get(this);
        ((TextView) findViewById(R.id.title)).setText(R.string.settings);
        findViewById(R.id.subtitle).setVisibility(View.GONE);
        findViewById(R.id.btnBack).setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        Ui.press(this, findViewById(R.id.btnBack));
        findViewById(R.id.btnA1).setVisibility(View.GONE);
        findViewById(R.id.btnA2).setVisibility(View.GONE);
        findViewById(R.id.btnRefresh).setVisibility(View.INVISIBLE);
        loading = findViewById(R.id.loading);
        Ui.tint(this, loading);
        content = findViewById(R.id.content);
        Ui.autoGroup(this, content);

        exportLauncher = registerForActivityResult(new ActivityResultContracts.CreateDocument("application/json"),
                uri -> {
                    if (uri != null) doExport(uri);
                });
        importLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri != null) doImport(uri);
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (isFinishing()) return; // blocked by the integrity check
        Sync.listen(this);
        build();
    }

    @Override
    protected void onStop() {
        super.onStop();
        Sync.unlisten(this);
    }

    @Override
    public void onSync(int state, boolean changed, String error) {
        if (isFinishing() || isDestroyed()) return;
        loading.setVisibility(state == Sync.RUNNING ? View.VISIBLE : View.INVISIBLE);
        build();
        if (state == Sync.ERROR) Toast.makeText(this, Firebase.friendly(this, error), Toast.LENGTH_LONG).show();
    }

    // ------------------------------------------------------------------ screen

    private void build() {
        final boolean cloud = Prefs.isCloud(this);
        if (!Prefs.hasAccess(this)) {
            startActivity(new Intent(this, LoginActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            finish();
            return;
        }
        // the page is rebuilt on every sync tick: keep the scroll position so it never jumps
        final android.widget.ScrollView sv = findViewById(R.id.settingsScroll);
        final int keepY = sv == null ? 0 : sv.getScrollY();
        content.removeAllViews();

        // account
        content.addView(Ui.sectionTitle(this, getString(R.string.sec_account)));
        String accTitle, accSub;
        if (!cloud) {
            accTitle = getString(R.string.acct_local);
            accSub = getString(R.string.acct_local_sub);
        } else {
            accTitle = Prefs.anonymous(this) ? getString(R.string.guest)
                    : (Prefs.email(this).isEmpty() ? getString(R.string.signed_in) : Prefs.email(this));
            accSub = Prefs.anonymous(this) ? getString(R.string.guest_sub) : getString(R.string.signed_in);
        }
        content.addView(Ui.rowView(this, content, new Row(R.drawable.ic_user, false, accTitle, accSub, false, false), null));
        if (!cloud) {
            // local mode: one tap to sign in; the notes already on the device are uploaded to that account
            content.addView(Ui.rowView(this, content,
                    new Row(R.drawable.ic_cloud, true, getString(R.string.sign_in_sync), getString(R.string.sign_in_sync_sub),
                            false, true),
                    v -> startActivity(new Intent(this, LoginActivity.class).putExtra(LoginActivity.EXTRA_FROM_APP, true))));
        }

        // appearance
        content.addView(Ui.sectionTitle(this, getString(R.string.sec_appearance)));
        content.addView(appearanceCard());

        // language
        content.addView(Ui.sectionTitle(this, getString(R.string.sec_language)));
        Row lang = new Row(R.drawable.ic_language, false, getString(R.string.language), Lang.label(this), false, true);
        content.addView(Ui.rowView(this, content, lang, v -> Lang.choose(this)));

        // privacy
        content.addView(Ui.sectionTitle(this, getString(R.string.sec_privacy)));
        Ui.Toggle secure = Ui.toggle(this, content, R.string.privacy_screen, R.string.privacy_screen_sub, Prefs.secureScreen(this));
        secure.onChange((b, on) -> {
            Prefs.setSecureScreen(this, on);
            if (on) getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
            else getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        });
        content.addView(secure.view);

        Row wipe = new Row(R.drawable.ic_trash_sweep, false, getString(R.string.delete_all_data),
                getString(cloud ? R.string.delete_all_data_sub : R.string.delete_local_data_sub), false, false).tint(Ui.color(this, R.attr.n_bad));
        content.addView(Ui.rowView(this, content, wipe, v -> confirmDeleteAll()));

        // sync
        if (cloud) {
            content.addView(Ui.sectionTitle(this, getString(R.string.sec_sync)));
            Ui.Toggle auto = Ui.toggle(this, content, R.string.auto_sync, R.string.auto_sync_sub, Prefs.autoSync(this));
            auto.onChange((b, on) -> Prefs.setAutoSync(this, on));
            content.addView(auto.view);
            long last = Prefs.lastSync(this);
            String sub = last > 0
                    ? getString(R.string.status_synced, DateUtils.getRelativeTimeSpanString(last,
                    System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_ALL))
                    : getString(R.string.status_waiting);
            content.addView(Ui.rowView(this, content,
                    new Row(R.drawable.ic_refresh, false, getString(R.string.sync_now), sub, false, false),
                    v -> Sync.request(this, true)));
        }

        // backup file
        content.addView(Ui.sectionTitle(this, getString(R.string.sec_backup)));
        content.addView(Ui.rowView(this, content,
                new Row(R.drawable.ic_upload, false, getString(R.string.export_file), getString(R.string.export_file_sub),
                        false, false),
                v -> exportLauncher.launch("notes-backup-"
                        + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date()) + ".json")));
        content.addView(Ui.rowView(this, content,
                new Row(R.drawable.ic_download, false, getString(R.string.import_file), getString(R.string.import_file_sub),
                        false, false),
                v -> importLauncher.launch(new String[]{"application/json", "text/plain", "application/octet-stream"})));

        // data
        content.addView(Ui.sectionTitle(this, getString(R.string.sec_data)));
        int trashed = db.count(Db.F_TRASH);
        content.addView(Ui.rowView(this, content,
                new Row(R.drawable.ic_trash_sweep, false, getString(R.string.empty_trash),
                        getString(R.string.trash_count, trashed), false, false),
                v -> {
                    if (db.count(Db.F_TRASH) == 0) {
                        Toast.makeText(this, R.string.empty_trash_screen, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    new Dlg(this).setTitle(R.string.empty_trash).setMessage(R.string.empty_trash_msg)
                            .setPositiveButton(R.string.empty_trash, (d, w) -> {
                                db.emptyTrash();
                                Sync.request(this, false);
                                build();
                            })
                            .setNegativeButton(R.string.cancel, null).show();
                }));

        // about + sign out
        content.addView(Ui.sectionTitle(this, getString(R.string.sec_about)));
        content.addView(Ui.rowView(this, content,
                new Row(R.drawable.ic_info, false, getString(R.string.sec_about),
                        getString(R.string.app_name) + " · " + getString(R.string.version, Ui.ltr(BuildConfig.VERSION_NAME)),
                        false, true),
                v -> startActivity(new Intent(this, AboutActivity.class))));
        if (cloud) {
            Row out = new Row(R.drawable.ic_logout, false, getString(R.string.sign_out), null, false, false)
                    .tint(Ui.color(this, R.attr.n_bad));
            content.addView(Ui.rowView(this, content, out, v -> confirmSignOut()));
        }
        if (sv != null && keepY > 0) sv.post(() -> sv.scrollTo(0, keepY));
    }

    // ------------------------------------------------------------------ appearance

    /** Day / night / system chips and the five palette swatches (berry, green, blue, white, yellow). */
    private View appearanceCard() {
        final String mode = Prefs.themeMode(this);
        final String accent = Prefs.themeAccent(this);
        final boolean dark = ThemeKit.isDark(this);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        int pad = Ui.dp(this, 18);
        card.setPadding(pad, Ui.dp(this, 16), pad, Ui.dp(this, 14));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cl.setMargins(Ui.dp(this, 14), Ui.dp(this, 4), Ui.dp(this, 14), Ui.dp(this, 4));
        card.setLayoutParams(cl);

        card.addView(caption(R.string.th_mode));
        LinearLayout modes = new LinearLayout(this);
        modes.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < ThemeKit.MODES.length; i++) {
            final String m = ThemeKit.MODES[i];
            TextView chip = Ui.chip(this, getString(ThemeKit.MODE_NAMES[i]), m.equals(mode));
            chip.setOnClickListener(v -> {
                if (m.equals(Prefs.themeMode(this))) return;
                Prefs.setThemeMode(this, m);
                recreate();
            });
            modes.addView(chip);
        }
        card.addView(modes);

        View gap = new View(this);
        card.addView(gap, new LinearLayout.LayoutParams(1, Ui.dp(this, 16)));
        card.addView(caption(R.string.th_color));

        LinearLayout swatches = new LinearLayout(this);
        swatches.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < ThemeKit.ACCENTS.length; i++) {
            final String ac = ThemeKit.ACCENTS[i];
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 2));

            boolean sel = ac.equals(accent);
            View dot = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(ThemeKit.swatch(ac, dark));
            g.setStroke(Ui.dp(this, sel ? 3 : 1), sel ? Ui.color(this, R.attr.n_text_primary) : Ui.color(this, R.attr.n_stroke));
            dot.setBackground(g);
            cell.addView(dot, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));

            TextView name = new TextView(this);
            name.setText(ThemeKit.ACCENT_NAMES[i]);
            name.setTextSize(11.5f);
            name.setSingleLine(true);
            name.setGravity(Gravity.CENTER);
            name.setTextColor(Ui.color(this, sel ? R.attr.n_text_primary : R.attr.n_text_secondary));
            LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            nl.topMargin = Ui.dp(this, 6);
            cell.addView(name, nl);

            Ui.press(this, cell);
            cell.setOnClickListener(v -> {
                if (ac.equals(Prefs.themeAccent(this))) return;
                Prefs.setThemeAccent(this, ac);
                recreate();
            });
            swatches.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        card.addView(swatches);
        return card;
    }

    private TextView caption(int res) {
        TextView t = new TextView(this);
        t.setText(res);
        t.setTextSize(13);
        t.setTextColor(Ui.color(this, R.attr.n_text_secondary));
        t.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        t.setPadding(0, 0, 0, Ui.dp(this, 10));
        return t;
    }

    // ------------------------------------------------------------------ backup file

    private void doExport(final Uri uri) {
        loading.setVisibility(View.VISIBLE);
        io.execute(() -> {
            boolean ok = false;
            int count = 0;
            try {
                List<Note> all = db.allLive();
                JSONArray arr = new JSONArray();
                for (Note n : all) {
                    arr.put(new JSONObject().put("id", n.id).put("title", n.title).put("body", n.body)
                            .put("kind", n.kind).put("color", n.color).put("pinned", n.pinned)
                            .put("archived", n.archived).put("trashed", n.trashed)
                            .put("createdAt", n.createdAt).put("updatedAt", n.updatedAt));
                }
                JSONObject root = new JSONObject().put("app", "notes").put("version", 1)
                        .put("exportedAt", System.currentTimeMillis()).put("notes", arr);
                try (OutputStream o = getContentResolver().openOutputStream(uri, "wt")) {
                    if (o == null) throw new java.io.IOException("no stream");
                    o.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
                }
                count = all.size();
                ok = true;
            } catch (Exception ignored) {
            }
            final boolean fOk = ok;
            final int fCount = count;
            ui.post(() -> {
                loading.setVisibility(View.INVISIBLE);
                Toast.makeText(this, fOk ? getString(R.string.export_done, fCount) : getString(R.string.file_error),
                        Toast.LENGTH_LONG).show();
            });
        });
    }

    private void doImport(final Uri uri) {
        loading.setVisibility(View.VISIBLE);
        io.execute(() -> {
            int added = -1;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new java.io.IOException("no stream");
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) > 0) {
                    bo.write(buf, 0, r);
                    if (bo.size() > 50_000_000) throw new java.io.IOException("too big");
                }
                JSONObject root = new JSONObject(bo.toString("UTF-8"));
                JSONArray arr = root.getJSONArray("notes");
                added = 0;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    Note n = new Note();
                    String id = o.optString("id", "");
                    if (id.matches("[A-Za-z0-9_-]{1,64}")) n.id = id;
                    n.title = clip(o.optString("title", ""), 300);
                    n.body = clip(o.optString("body", ""), 100_000);
                    n.kind = o.optInt("kind", 0) == 1 ? 1 : 0;
                    n.color = Math.max(0, Math.min(Palette.COUNT - 1, o.optInt("color", 0)));
                    n.pinned = o.optBoolean("pinned", false);
                    n.archived = o.optBoolean("archived", false);
                    n.trashed = o.optBoolean("trashed", false);
                    n.createdAt = o.optLong("createdAt", System.currentTimeMillis());
                    n.updatedAt = o.optLong("updatedAt", n.createdAt);
                    if (n.createdAt <= 0) n.createdAt = System.currentTimeMillis();
                    if (n.updatedAt < n.createdAt) n.updatedAt = n.createdAt; // required by the cloud rules
                    if (n.isEmpty()) continue;
                    Note local = db.get(n.id);
                    if (local != null) n.createdAt = local.createdAt; // the cloud rules keep createdAt fixed
                    if (local == null || n.updatedAt > local.updatedAt) {
                        db.putImported(n);
                        added++;
                    }
                }
            } catch (Exception e) {
                added = -1;
            }
            final int fAdded = added;
            ui.post(() -> {
                loading.setVisibility(View.INVISIBLE);
                if (fAdded < 0) {
                    Toast.makeText(this, R.string.file_error, Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, getString(R.string.import_done, fAdded), Toast.LENGTH_LONG).show();
                    Sync.request(this, false);
                    build();
                }
            });
        });
    }

    private static String clip(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    // ------------------------------------------------------------------ sign out

    private void confirmSignOut() {
        new Dlg(this)
                .setTitle(R.string.sign_out)
                .setMessage(Prefs.anonymous(this) ? R.string.sign_out_guest_msg : R.string.sign_out_msg)
                .setPositiveButton(R.string.sign_out_wipe, (d, w) -> signOut(true))
                .setNegativeButton(R.string.sign_out_keep, (d, w) -> signOut(false))
                .setNeutralButton(R.string.cancel, null)
                .show();
    }

    /** Removes the cloud copy (and the Firebase account when possible) and everything stored on this device. */
    private void confirmDeleteAll() {
        final boolean cloud = Prefs.isCloud(this);
        new Dlg(this)
                .setTitle(R.string.delete_all_data)
                .setMessage(cloud ? R.string.delete_all_data_msg : R.string.delete_local_data_msg)
                .setPositiveButton(R.string.delete_all_data, (d, w) -> {
                    if (!cloud) { // local mode: there is no account and no cloud copy, only this device
                        db.wipeAll();
                        Prefs.setOwnerUid(this, "");
                        Toast.makeText(this, R.string.delete_all_done, Toast.LENGTH_LONG).show();
                        build();
                        return;
                    }
                    loading.setVisibility(View.VISIBLE);
                    final String uid = Prefs.uid(this);
                    io.execute(() -> {
                        String err = null;
                        try {
                            String token = Firebase.validToken(getApplicationContext());
                            Firebase.deleteAllNotes(token, uid);
                            try {
                                Firebase.deleteAccount(token);
                            } catch (Firebase.FbException ignored) {
                                // needs a very recent sign-in; the notes are already gone from the cloud
                            }
                        } catch (Firebase.FbException e) {
                            err = e.code;
                        }
                        final String fErr = err;
                        ui.post(() -> {
                            loading.setVisibility(View.INVISIBLE);
                            if (fErr != null) {
                                Toast.makeText(this, Firebase.friendly(this, fErr), Toast.LENGTH_LONG).show();
                                return;
                            }
                            db.wipeAll();
                            Prefs.setOwnerUid(this, "");
                            Prefs.clearSession(this);
                            Toast.makeText(this, R.string.delete_all_done, Toast.LENGTH_LONG).show();
                            Intent i = new Intent(this, LoginActivity.class);
                            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                            startActivity(i);
                            finish();
                        });
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void signOut(boolean wipe) {
        Prefs.clearSession(this);
        if (wipe) {
            db.wipeAll();
            Prefs.setOwnerUid(this, ""); // nothing on the device belongs to anyone now
        } else {
            db.markAllDirty();           // notes stay and remain owned by the account that just signed out
        }
        Intent i = new Intent(this, LoginActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdown();
    }
}
