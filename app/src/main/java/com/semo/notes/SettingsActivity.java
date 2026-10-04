package com.semo.notes;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateUtils;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

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

public class SettingsActivity extends AppCompatActivity implements Sync.Listener {
    private LinearLayout content;
    private ProgressBar loading;
    private Db db;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private ActivityResultLauncher<String> exportLauncher;
    private ActivityResultLauncher<String[]> importLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
        loading.setVisibility(state == Sync.RUNNING ? View.VISIBLE : View.INVISIBLE);
        build();
        if (state == Sync.ERROR) Toast.makeText(this, Firebase.friendly(this, error), Toast.LENGTH_LONG).show();
    }

    // ------------------------------------------------------------------ screen

    private void build() {
        content.removeAllViews();
        final boolean cloud = Prefs.isCloud(this);
        if (!cloud) {
            startActivity(new Intent(this, LoginActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            finish();
            return;
        }

        // account
        content.addView(Ui.sectionTitle(this, getString(R.string.sec_account)));
        String title = Prefs.anonymous(this) ? getString(R.string.guest)
                : (Prefs.email(this).isEmpty() ? getString(R.string.signed_in) : Prefs.email(this));
        String sub = Prefs.anonymous(this) ? getString(R.string.guest_sub) : getString(R.string.signed_in);
        content.addView(Ui.rowView(this, content, new Row(R.drawable.ic_user, false, title, sub, false, false), null));

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

        // updates
        content.addView(Ui.sectionTitle(this, getString(R.string.sec_updates)));
        content.addView(Ui.rowView(this, content,
                new Row(R.drawable.ic_download, false, getString(R.string.upd_check),
                        getString(R.string.upd_check_sub, Updater.installedName(this), Updater.installedCode(this)),
                        false, true),
                v -> startActivity(new Intent(this, UpdateActivity.class))));

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
                new Row(R.drawable.ic_info, false, getString(R.string.app_name),
                        getString(R.string.version, BuildConfig.VERSION_NAME), false, false), null));
        if (cloud) {
            Row out = new Row(R.drawable.ic_logout, false, getString(R.string.sign_out), null, false, false)
                    .tint(Ui.color(this, R.color.bad));
            content.addView(Ui.rowView(this, content, out, v -> confirmSignOut()));
        }
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
                    n.color = Math.max(0, Math.min(Palette.CARD.length - 1, o.optInt("color", 0)));
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
