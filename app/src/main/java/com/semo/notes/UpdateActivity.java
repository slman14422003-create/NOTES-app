package com.semo.notes;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Checks the app's GitHub Releases for a newer APK, downloads it in the app and starts the installer. */
public class UpdateActivity extends AppCompatActivity {
    public static final String EXTRA_AUTOSTART = "autostart";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout content;
    private ProgressBar loading;
    private TextView subtitle;
    private Updater.Info info;
    private String errorText;
    private boolean checked, busy, autostart;
    private File ready;       // verified APK waiting for the installer (e.g. after granting "install unknown apps")

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Integrity.enforce(this)) return;
        setContentView(R.layout.activity_settings);
        ((TextView) findViewById(R.id.title)).setText(R.string.upd_title);
        subtitle = findViewById(R.id.subtitle);
        findViewById(R.id.btnBack).setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        findViewById(R.id.btnA1).setVisibility(View.GONE);
        findViewById(R.id.btnA2).setVisibility(View.GONE);
        android.widget.ImageButton refresh = findViewById(R.id.btnRefresh);
        refresh.setOnClickListener(v -> check());
        Ui.press(this, findViewById(R.id.btnBack));
        Ui.press(this, refresh);
        loading = findViewById(R.id.loading);
        Ui.tint(this, loading);
        content = findViewById(R.id.content);
        Ui.autoGroup(this, content);
        autostart = getIntent().getBooleanExtra(EXTRA_AUTOSTART, false);
        subtitle.setText(Updater.repo(this));
        render();
        check();
    }

    private String slug() {
        return Updater.repo(this);
    }

    // ------------------------------------------------------------------ check

    private void check() {
        if (busy) return;
        final String slug = slug();
        if (!Updater.validSlug(slug)) {
            checked = true;
            info = null;
            errorText = getString(R.string.upd_no_source);
            render();
            return;
        }
        busy = true;
        spinner(true);
        errorText = null;
        final boolean pre = Prefs.updatePre(this);
        io.execute(() -> {
            try {
                final Updater.Info in = Updater.check(getApplicationContext(), slug, pre);
                Prefs.setLastUpdateCheck(this, System.currentTimeMillis());
                ui.post(() -> {
                    busy = false;
                    spinner(false);
                    info = in;
                    checked = true;
                    render();
                    if (autostart && in != null && in.newer) {
                        autostart = false;
                        download();
                    }
                });
            } catch (Updater.UpdateException e) {
                ui.post(() -> {
                    busy = false;
                    spinner(false);
                    checked = true;
                    info = null;
                    errorText = Updater.message(this, e, slug);
                    render();
                });
            }
        });
    }

    private void spinner(boolean on) {
        loading.setIndeterminate(true);
        loading.setVisibility(on ? View.VISIBLE : View.INVISIBLE);
    }

    // ------------------------------------------------------------------ screen

    private void render() {
        content.removeAllViews();

        content.addView(Ui.sectionTitle(this, getString(R.string.upd_version_section)));
        add(new Row(R.drawable.ic_info, false, getString(R.string.upd_current),
                getString(R.string.upd_installed, Updater.installedName(this), Updater.installedCode(this)),
                false, false), null);

        if (!checked) {
            add(new Row(R.drawable.ic_refresh, false, getString(R.string.upd_latest),
                    getString(R.string.upd_checking), false, false), null);
        } else if (errorText != null) {
            content.addView(Ui.noteCard(this, errorText, R.color.bad));
        } else if (info == null) {
            content.addView(Ui.noteCard(this, getString(R.string.upd_none_found), R.color.text_secondary));
        } else {
            renderRelease();
        }

        content.addView(Ui.sectionTitle(this, getString(R.string.upd_settings)));
        add(new Row(R.drawable.ic_cloud, false, getString(R.string.upd_source), slug().isEmpty()
                ? getString(R.string.upd_source_hint) : slug(), false, true), v -> editSource());

        final Ui.Toggle auto = Ui.toggle(this, content, R.string.upd_auto, R.string.upd_auto_sub, Prefs.autoUpdate(this));
        auto.onChange((b, on) -> Prefs.setAutoUpdate(this, on));
        content.addView(auto.view);

        final Ui.Toggle pre = Ui.toggle(this, content, R.string.upd_pre, 0, Prefs.updatePre(this));
        pre.onChange((b, on) -> {
            Prefs.setUpdatePre(this, on);
            check();
        });
        content.addView(pre.view);
    }

    private void renderRelease() {
        String title = info.name.isEmpty() ? info.tag : info.name;
        if (!info.name.isEmpty() && !info.name.equals(info.tag)) title = title + "  (" + info.tag + ")";
        StringBuilder sub = new StringBuilder(size(info.assetSize));
        String ago = ago(info.publishedAt);
        if (!ago.isEmpty()) sub.append(" · ").append(getString(R.string.upd_published, ago));
        Row latest = new Row(R.drawable.ic_download, false, title, sub.toString(), false, false);
        if (info.newer) latest.badge(getString(R.string.upd_badge_new), Ui.color(this, R.color.accent_text));
        else latest.badge(getString(R.string.upd_badge_current), Ui.color(this, R.color.ok));
        add(latest, null);

        if (!info.newer) {
            content.addView(Ui.noteCard(this, getString(R.string.upd_up_to_date), R.color.text_secondary));
        } else {
            Button install = Ui.block(this, Ui.button(this,
                    ready != null ? R.string.upd_install : R.string.upd_download_install, true));
            ((LinearLayout.LayoutParams) install.getLayoutParams()).topMargin = Ui.dp(this, 16);
            install.setEnabled(!busy);
            install.setOnClickListener(v -> {
                if (ready != null && ready.exists()) Updater.install(this, ready);
                else download();
            });
            content.addView(install);

            add(new Row(R.drawable.ic_cancel, false, getString(R.string.upd_skip_version), info.tag, false, false), v -> {
                Prefs.setSkippedVersion(this, info.tag);
                Toast.makeText(this, getString(R.string.upd_skipped, info.tag), Toast.LENGTH_SHORT).show();
            });
        }
        if (!info.htmlUrl.isEmpty()) {
            add(new Row(R.drawable.ic_share, false, getString(R.string.upd_view_release), null, false, true), v -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(info.htmlUrl)));
                } catch (Exception ignored) {
                }
            });
        }
        String notes = Updater.cleanNotes(info.body);
        if (!notes.isEmpty()) {
            content.addView(Ui.sectionTitle(this, getString(R.string.upd_notes)));
            content.addView(Ui.noteCard(this, notes, R.color.text_primary));
        }
    }

    private void add(Row r, View.OnClickListener l) {
        content.addView(Ui.rowView(this, content, r, l));
    }

    private void editSource() {
        LinearLayout box = Ui.box(this);
        final EditText src = Ui.edit(this, getString(R.string.upd_source_hint), slug());
        src.setTextDirection(View.TEXT_DIRECTION_LTR);
        src.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        box.addView(Ui.label(this, getString(R.string.upd_source_hint)));
        box.addView(src);
        new Dlg(this)
                .setTitle(R.string.upd_source)
                .setView(box)
                .setPositiveButton(R.string.save, (d, w) -> {
                    String v = src.getText().toString().trim();
                    if (!Updater.validSlug(v)) {
                        Toast.makeText(this, R.string.upd_source_bad, Toast.LENGTH_LONG).show();
                        return;
                    }
                    Prefs.setUpdateRepo(this, v);
                    Prefs.setSkippedVersion(this, "");
                    subtitle.setText(v);
                    checked = false;
                    info = null;
                    ready = null;
                    render();
                    check();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ------------------------------------------------------------------ download + install

    private void download() {
        final Updater.Info in = info;
        if (in == null || busy) return;
        busy = true;
        render();
        loading.setIndeterminate(false);
        loading.setMax(100);
        loading.setProgress(0);
        loading.setVisibility(View.VISIBLE);
        subtitle.setText(R.string.upd_downloading_start);
        io.execute(() -> {
            try {
                final File apk = Updater.download(getApplicationContext(), in, (done, total) -> ui.post(() -> {
                    if (total > 0) {
                        int pct = (int) Math.min(100, done * 100 / total);
                        loading.setProgress(pct);
                        subtitle.setText(getString(R.string.upd_downloading, pct, size(done), size(total)));
                    }
                }));
                ui.post(() -> {
                    busy = false;
                    ready = apk;
                    spinner(false);
                    subtitle.setText(slug());
                    render();
                    Updater.install(this, apk);
                });
            } catch (Updater.UpdateException e) {
                ui.post(() -> {
                    busy = false;
                    spinner(false);
                    subtitle.setText(slug());
                    errorText = Updater.message(this, e, slug());
                    render();
                });
            }
        });
    }

    // ------------------------------------------------------------------ helpers

    private String size(long b) {
        if (b <= 0) return "";
        if (b < 1024 * 1024) return String.format(Locale.US, "%.0f KB", b / 1024.0);
        return String.format(Locale.US, "%.1f MB", b / 1048576.0);
    }

    private String ago(String iso) {
        if (iso == null || iso.length() < 19) return "";
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date d = f.parse(iso.substring(0, 19));
            return d == null ? "" : DateUtils.getRelativeTimeSpanString(d.getTime(), System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString();
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdown();
    }
}
