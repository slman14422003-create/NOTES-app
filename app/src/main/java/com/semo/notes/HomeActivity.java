package com.semo.notes;

import android.animation.ObjectAnimator;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;

import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Main screen: search, quick-access tiles, filters, pinned + recent notes and a quick-capture bar. */
public class HomeActivity extends SmoothActivity implements Sync.Listener {
    private static final int PAGE = 60;

    private Db db;
    private String filter = Db.F_ALL;
    private String query = "";
    private int limit = PAGE;
    private int reloadGen;
    private final java.util.Set<String> shown = new java.util.HashSet<>();
    private int scrollAcc;
    private View overlay, headerLine;
    private TextView titleView;
    private boolean composerHidden;
    private final ExecutorService dbEx = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable reloadRun = this::reload;
    private final Runnable hideUndo = () -> findViewById(R.id.undoBar).setVisibility(View.GONE);
    private SwipeRefreshLayout refresh;
    private android.widget.ScrollView scrollView;
    private String lastSig = "";      // what is currently drawn: an identical reload is skipped (no flicker, no jump)
    private String lastViewKey = "";  // filter + query of the last draw: the scroll position is kept only inside the same view
    private Button btnMore;

    private LinearLayout gridPinned, gridAll, chipRow;
    private TextView secPinned, secAll, empty, emptyTitle, subtitle;
    private View emptyBox;
    private ImageView emptyIcon;
    private EditText search, quickText;
    private Button btnEmptyTrash;
    private ImageButton btnSync;
    private ProgressBar loading;
    private ObjectAnimator spin;
    private final List<TextView> chips = new ArrayList<>();
    private static final String[] FILTERS = {Db.F_ALL, Db.F_PINNED, Db.F_LIST, Db.F_ARCHIVE, Db.F_TRASH};
    private static final int[] FILTER_LABELS = {R.string.f_all, R.string.f_pinned, R.string.f_lists,
            R.string.f_archive, R.string.f_trash};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeKit.apply(this);
        super.onCreate(savedInstanceState);
        if (Integrity.enforce(this)) return;
        setContentView(R.layout.activity_home);
        db = Db.get(this);

        ((TextView) findViewById(R.id.title)).setText(R.string.app_name);
        subtitle = findViewById(R.id.subtitle);
        loading = findViewById(R.id.loading);
        Ui.tint(this, loading);

        // header: settings (start), sync status (end)
        ImageButton settings = findViewById(R.id.btnBack);
        settings.setImageResource(R.drawable.ic_settings);
        settings.setContentDescription(getString(R.string.settings));
        settings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.btnA1).setVisibility(View.GONE);
        btnSync = findViewById(R.id.btnRefresh);
        btnSync.setOnClickListener(v -> {
            if (Prefs.isCloud(this)) Sync.request(this, true);
            else startActivity(new Intent(this, LoginActivity.class).putExtra(LoginActivity.EXTRA_FROM_APP, true));
        });
        Ui.press(this, settings);
        Ui.press(this, btnSync);

        refresh = findViewById(R.id.refresh);
        refresh.setProgressBackgroundColorSchemeColor(Ui.color(this, R.attr.n_surface));
        refresh.setColorSchemeColors(Ui.color(this, R.attr.n_accent_text));
        refresh.setOnRefreshListener(() -> {
            if (Prefs.isCloud(this) && Sync.online(this)) Sync.request(this, true);
            else refresh.setRefreshing(false);
        });
        btnMore = findViewById(R.id.btnMore);
        btnMore.setOnClickListener(v -> {
            limit += PAGE;
            reload();
        });
        findViewById(R.id.undoBtn).setOnClickListener(v -> {
            NoteActions.undo(this);
            ui.removeCallbacks(hideUndo);
            hideUndo.run();
            reload();
        });

        overlay = findViewById(R.id.overlay);
        headerLine = findViewById(R.id.headerLine);
        titleView = findViewById(R.id.title);
        final float collapse = Ui.dp(this, 70);
        scrollView = findViewById(R.id.scroll);
        scrollView.setOnScrollChangeListener(
                (v, x, y, ox, oy) -> {
                    float t = Math.min(1f, Math.max(0f, y / collapse));
                    boolean rtl = Lang.isAr(this);
                    titleView.setPivotX(rtl ? titleView.getWidth() : 0f);
                    titleView.setPivotY(titleView.getHeight() / 2f);
                    float s = 1f - 0.22f * t;
                    titleView.setScaleX(s);
                    titleView.setScaleY(s);
                    subtitle.setAlpha(1f - t);
                    headerLine.setAlpha(t);
                    int dy = y - oy;
                    scrollAcc = (dy > 0) == (scrollAcc > 0) ? scrollAcc + dy : dy; // direction change resets
                    boolean typing = quickText.hasFocus();
                    if (!typing && scrollAcc > Ui.dp(this, 24) && y > Ui.dp(this, 90) && !composerHidden) {
                        composerHidden = true;
                        Motion.slideY(overlay, true);
                    } else if ((scrollAcc < -Ui.dp(this, 12) || y < Ui.dp(this, 40) || typing) && composerHidden) {
                        composerHidden = false;
                        Motion.slideY(overlay, false);
                    }
                });

        gridPinned = findViewById(R.id.gridPinned);
        gridAll = findViewById(R.id.gridAll);
        chipRow = findViewById(R.id.chipRow);
        secPinned = findViewById(R.id.secPinned);
        secAll = findViewById(R.id.secAll);
        empty = findViewById(R.id.empty);
        emptyBox = findViewById(R.id.emptyBox);
        emptyTitle = findViewById(R.id.emptyTitle);
        emptyIcon = findViewById(R.id.emptyIcon);
        btnEmptyTrash = findViewById(R.id.btnEmptyTrash);
        search = findViewById(R.id.search);
        quickText = findViewById(R.id.quickText);

        buildQuickTiles();
        buildChips();

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }

            @Override
            public void afterTextChanged(Editable s) {
                query = s.toString().trim();
                limit = PAGE;
                shown.clear();
                ui.removeCallbacks(reloadRun);
                ui.postDelayed(reloadRun, 160); // wait for a pause in typing before querying
            }
        });
        search.setOnEditorActionListener((v, action, e) -> {
            if (action == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard(v);
                return true;
            }
            return false;
        });

        findViewById(R.id.btnSend).setOnClickListener(v -> quickSave());
        Ui.press(this, findViewById(R.id.btnSend));
        ImageButton btnList = findViewById(R.id.btnList);
        Ui.press(this, btnList);
        btnList.setOnClickListener(v -> {
            String t = quickText.getText().toString().trim();
            quickText.setText("");
            openNew(true, t);
        });

        btnEmptyTrash.setOnClickListener(v -> new Dlg(this)
                .setTitle(R.string.empty_trash)
                .setMessage(R.string.empty_trash_msg)
                .setPositiveButton(R.string.empty_trash, (d, w) -> {
                    db.emptyTrash();
                    Sync.request(this, false);
                    reload();
                })
                .setNegativeButton(R.string.cancel, null)
                .show());

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (!search.getText().toString().isEmpty()) {
                    search.setText("");
                } else if (!Db.F_ALL.equals(filter)) {
                    setFilter(Db.F_ALL);
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (isFinishing()) return; // blocked by the integrity check
        if (!Prefs.hasAccess(this)) { // signed out or the session ended: back to the sign-in screen
            startActivity(new Intent(this, LoginActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            finish();
            return;
        }
        Sync.listen(this);
        Sync.request(this, true); // automatic import + export on every start
        reload();
        refreshSyncUi();
        Updater.autoCheck(this); // quiet check for a newer release on GitHub
    }

    @Override
    protected void onStop() {
        super.onStop();
        Sync.unlisten(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
        dbEx.shutdownNow();
    }

    @Override
    public void onSync(int state, boolean changed, String error) {
        if (state != Sync.RUNNING) refresh.setRefreshing(false);
        if ("SESSION_ENDED".equals(error)) {
            Toast.makeText(this, R.string.err_session, Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, LoginActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            finish();
            return;
        }
        refreshSyncUi();
        if (changed) reload();
    }

    // ------------------------------------------------------------------ sync status

    private void refreshSyncUi() {
        boolean cloud = Prefs.isCloud(this);
        btnSync.setImageResource(cloud ? R.drawable.ic_cloud : R.drawable.ic_cloud_off);
        btnSync.setContentDescription(getString(cloud ? R.string.sync_now : R.string.sign_in));
        int st = Sync.state();
        boolean running = cloud && st == Sync.RUNNING;
        loading.setVisibility(running ? View.VISIBLE : View.INVISIBLE);
        if (running && Motion.enabled(this)) {
            if (spin == null) {
                spin = ObjectAnimator.ofPropertyValuesHolder(btnSync,
                        android.animation.PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0.35f),
                        android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 0.9f),
                        android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 0.9f));
                spin.setDuration(700);
                spin.setRepeatCount(ObjectAnimator.INFINITE);
                spin.setRepeatMode(ObjectAnimator.REVERSE);
                spin.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
            }
            if (!spin.isStarted()) spin.start();
        } else if (spin != null) {
            spin.cancel();
            spin = null;
            btnSync.setAlpha(1f);
            btnSync.setScaleX(1f);
            btnSync.setScaleY(1f);
        }
        String text;
        if (!cloud) text = getString(R.string.status_local);
        else if (running) text = getString(R.string.status_syncing);
        else if (st == Sync.ERROR) text = Firebase.friendly(this, Sync.lastError());
        else if (Prefs.lastSync(this) > 0) {
            long ago = System.currentTimeMillis() - Prefs.lastSync(this);
            text = ago < 60_000L ? getString(R.string.status_synced_now)
                    : getString(R.string.status_synced, DateUtils.getRelativeTimeSpanString(Prefs.lastSync(this),
                    System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_ALL));
        } else text = getString(R.string.status_waiting);
        subtitle.setText(text);
        subtitle.setTextColor(Ui.color(this, cloud && st == Sync.ERROR && !running ? R.attr.n_bad : R.attr.n_text_secondary));
    }

    // ------------------------------------------------------------------ quick access

    private void buildQuickTiles() {
        LinearLayout row = findViewById(R.id.quick);
        row.removeAllViews();
        addTile(row, R.drawable.ic_add, R.string.tile_note, v -> openNew(false, ""));
        addTile(row, R.drawable.ic_checklist, R.string.tile_list, v -> openNew(true, ""));
        addTile(row, R.drawable.ic_clipboard, R.string.tile_paste, v -> pasteNote());
    }

    private void addTile(LinearLayout row, int icon, int label, View.OnClickListener l) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dp(this, 6), Ui.dp(this, 14), Ui.dp(this, 6), Ui.dp(this, 12));
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(Ui.color(this, R.attr.n_surface));
        fill.setCornerRadius(Ui.dp(this, 28));
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(Ui.dp(this, 28));
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.color(this, R.attr.n_ripple)), fill, mask));

        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        iv.setImageTintList(ColorStateList.valueOf(Ui.color(this, R.attr.n_accent_text)));
        iv.setBackgroundResource(R.drawable.bg_circle_accent);
        int p = Ui.dp(this, 10);
        iv.setPadding(p, p, p, p);
        t.addView(iv, new LinearLayout.LayoutParams(Ui.dp(this, 46), Ui.dp(this, 46)));

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(12);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setGravity(Gravity.CENTER);
        tv.setTextColor(Ui.color(this, R.attr.n_text_secondary));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.topMargin = Ui.dp(this, 8);
        t.addView(tv, tl);

        Ui.press(this, t);
        t.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(Ui.dp(this, 5), 0, Ui.dp(this, 5), 0);
        row.addView(t, lp);
    }

    private void openNew(boolean list, String initial) {
        Intent i = new Intent(this, NoteEditorActivity.class);
        i.putExtra(NoteEditorActivity.EXTRA_KIND, list ? Note.KIND_LIST : Note.KIND_TEXT);
        if (initial != null && !initial.isEmpty()) i.putExtra(NoteEditorActivity.EXTRA_INIT, initial);
        startActivity(i);
    }

    private void pasteNote() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        CharSequence text = null;
        if (cm != null && cm.hasPrimaryClip()) {
            ClipData d = cm.getPrimaryClip();
            if (d != null && d.getItemCount() > 0) text = d.getItemAt(0).coerceToText(this);
        }
        if (text == null || text.toString().trim().isEmpty()) {
            Toast.makeText(this, R.string.clipboard_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        openNew(false, text.toString());
    }

    private void quickSave() {
        String t = quickText.getText().toString().trim();
        if (t.isEmpty()) {
            openNew(false, "");
            return;
        }
        Note n = new Note();
        n.body = t;
        NoteActions.save(this, n);
        quickText.setText("");
        hideKeyboard(quickText);
        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
        setFilter(Db.F_ALL);
    }

    private void hideKeyboard(View v) {
        InputMethodManager im = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (im != null) im.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }

    // ------------------------------------------------------------------ filters

    private void buildChips() {
        chipRow.removeAllViews();
        chips.clear();
        for (int i = 0; i < FILTERS.length; i++) {
            final String f = FILTERS[i];
            TextView c = Ui.chip(this, getString(FILTER_LABELS[i]), f.equals(filter));
            c.setOnClickListener(v -> setFilter(f));
            chips.add(c);
            chipRow.addView(c);
        }
    }

    private void setFilter(String f) {
        filter = f;
        limit = PAGE;
        shown.clear(); // a new list: let its cards animate in
        for (int i = 0; i < chips.size(); i++) Ui.animateChip(this, chips.get(i), FILTERS[i].equals(filter));
        reload();
    }

    // ------------------------------------------------------------------ list

    /** Reads from SQLite on a background thread and draws the result only if it is still the latest request. */
    private void reload() {
        final int gen = ++reloadGen;
        final String f = filter, q = query;
        final int lim = limit;
        dbEx.execute(() -> {
            final List<Note> rows = db.list(f, q, lim + 1);
            final String sig = signature(f, q, lim, rows);
            ui.post(() -> {
                if (gen != reloadGen || !alive()) return;
                // nothing changed since the last draw (typical on every start / sync tick): keep the screen as it is
                if (!sig.equals(lastSig)) {
                    lastSig = sig;
                    render(rows, rows.size() > lim ? rows.subList(0, lim) : rows, rows.size() > lim);
                }
                maybeShowUndo();
            });
        });
    }

    /** Cheap fingerprint of what the list shows; the minute bucket keeps the relative dates ("5 min ago") fresh. */
    private static String signature(String f, String q, int lim, List<Note> rows) {
        StringBuilder sb = new StringBuilder(rows.size() * 24 + 64);
        sb.append(f).append('|').append(q).append('|').append(lim).append('|')
                .append(System.currentTimeMillis() / 60_000L);
        for (Note n : rows) {
            sb.append('|').append(n.id).append(':').append(n.updatedAt)
                    .append(n.pinned ? 'p' : '-').append(n.color).append(n.kind);
        }
        return sb.toString();
    }

    private void maybeShowUndo() {
        if (NoteActions.undoId == null || NoteActions.undoShown) return;
        if (System.currentTimeMillis() - NoteActions.undoAt > 6000) {
            NoteActions.undoId = null;
            return;
        }
        NoteActions.undoShown = true;
        ((TextView) findViewById(R.id.undoMsg)).setText(NoteActions.undoKind == 0
                ? R.string.undo_trash : R.string.undo_archive);
        findViewById(R.id.undoBar).setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideUndo);
        ui.postDelayed(hideUndo, 5000);
    }

    private void render(List<Note> all, List<Note> list, boolean hasMore) {
        final String viewKey = filter + "|" + query;
        final boolean sameView = viewKey.equals(lastViewKey);
        lastViewKey = viewKey;
        final int keepY = scrollView.getScrollY();
        boolean grouped = Db.F_ALL.equals(filter) && query.isEmpty();
        List<Note> pinned = new ArrayList<>();
        List<Note> others = new ArrayList<>();
        for (Note n : list) {
            if (grouped && n.pinned) pinned.add(n);
            else others.add(n);
        }
        btnMore.setVisibility(hasMore ? View.VISIBLE : View.GONE);
        secPinned.setVisibility(pinned.isEmpty() ? View.GONE : View.VISIBLE);
        gridPinned.setVisibility(pinned.isEmpty() ? View.GONE : View.VISIBLE);
        fill(gridPinned, pinned);

        int title;
        if (!query.isEmpty()) title = R.string.sec_results;
        else if (Db.F_PINNED.equals(filter)) title = R.string.f_pinned;
        else if (Db.F_LIST.equals(filter)) title = R.string.f_lists;
        else if (Db.F_ARCHIVE.equals(filter)) title = R.string.f_archive;
        else if (Db.F_TRASH.equals(filter)) title = R.string.f_trash;
        else title = pinned.isEmpty() ? R.string.sec_notes : R.string.sec_recent;
        secAll.setText(getString(title) + (others.isEmpty() ? "" : "  ·  " + others.size() + (hasMore ? "+" : "")));
        secAll.setVisibility(others.isEmpty() ? View.GONE : View.VISIBLE);
        gridAll.setVisibility(others.isEmpty() ? View.GONE : View.VISIBLE);
        fill(gridAll, others);

        btnEmptyTrash.setVisibility(Db.F_TRASH.equals(filter) && !others.isEmpty() ? View.VISIBLE : View.GONE);

        boolean none = list.isEmpty();
        emptyBox.setVisibility(none ? View.VISIBLE : View.GONE);
        if (none) {
            int icon = R.drawable.ic_note, ttl = R.string.empty_title, sub = R.string.empty_notes_sub;
            if (!query.isEmpty()) {
                icon = R.drawable.ic_search;
                ttl = R.string.empty_search;
                sub = 0;
            } else if (Db.F_TRASH.equals(filter)) {
                icon = R.drawable.ic_trash_sweep;
                ttl = R.string.empty_trash_screen;
                sub = 0;
            } else if (Db.F_ARCHIVE.equals(filter)) {
                icon = R.drawable.ic_archive;
                ttl = R.string.empty_archive;
                sub = 0;
            } else if (Db.F_PINNED.equals(filter)) {
                icon = R.drawable.ic_star;
                ttl = R.string.f_pinned;
                sub = 0;
            } else if (Db.F_LIST.equals(filter)) {
                icon = R.drawable.ic_checklist;
                ttl = R.string.f_lists;
                sub = 0;
            }
            emptyIcon.setImageResource(icon);
            if (Motion.enabled(this) && emptyIcon.getTag() == null) {
                emptyIcon.setTag("bob");
                ObjectAnimator bob = ObjectAnimator.ofFloat(emptyIcon, View.TRANSLATION_Y, 0f, -Ui.dp(this, 8));
                bob.setDuration(1800);
                bob.setRepeatCount(ObjectAnimator.INFINITE);
                bob.setRepeatMode(ObjectAnimator.REVERSE);
                bob.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
                bob.start();
            }
            emptyTitle.setText(ttl);
            empty.setText(sub == 0 ? "" : getString(sub));
            empty.setVisibility(sub == 0 ? View.GONE : View.VISIBLE);
        }
        // the lists were rebuilt: put the scroll position back so a sync never makes the page jump
        if (sameView && keepY > 0) scrollView.post(() -> scrollView.scrollTo(0, keepY));
    }

    /** Two-column masonry: every card goes to the shorter column. */
    private void fill(LinearLayout host, List<Note> list) {
        host.removeAllViews();
        if (list.isEmpty()) return;
        host.setBaselineAligned(false);
        LinearLayout[] col = new LinearLayout[2];
        int[] h = new int[2];
        for (int i = 0; i < 2; i++) {
            col[i] = new LinearLayout(this);
            col[i].setOrientation(LinearLayout.VERTICAL);
            host.addView(col[i], new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        int idx = 0;
        for (Note n : list) {
            int c = h[0] <= h[1] ? 0 : 1;
            View card = card(n);
            col[c].addView(card);
            if (!shown.contains(n.id)) {
                shown.add(n.id);
                if (idx < 10) Ui.enter(card, idx); // cards that were already on screen do not re-animate on refresh
            }
            idx++;
            h[c] += estimate(n);
        }
    }

    private static int estimate(Note n) {
        int e = 60;
        if (!n.title.isEmpty()) e += 26 * Math.min(3, 1 + n.title.length() / 18);
        int lines;
        if (n.kind == Note.KIND_LIST) {
            lines = Math.min(8, Note.items(n.body).size());
        } else {
            lines = 0;
            for (String l : n.body.split("\n")) lines += 1 + l.length() / 24;
            lines = Math.min(8, lines);
        }
        return e + lines * 19;
    }

    private View card(final Note n) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 18);
        card.setPadding(pad, pad, pad, Ui.dp(this, 14));
        float r = Ui.dp(this, 28);
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(Palette.card(this, n.color));
        fill.setCornerRadius(r);
        if (n.color == 0) fill.setStroke(Ui.dp(this, 1), Ui.color(this, R.attr.n_stroke_soft));
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(r);
        card.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.color(this, R.attr.n_ripple)), fill, mask));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(Ui.dp(this, 5), Ui.dp(this, 5), Ui.dp(this, 5), Ui.dp(this, 5));
        card.setLayoutParams(lp);

        if (!n.title.trim().isEmpty()) {
            TextView t = new TextView(this);
            t.setText(n.title.trim());
            t.setTypeface(Typeface.SERIF);
            t.setTextSize(16);
            t.setMaxLines(3);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
            t.setTextAlignment(View.TEXT_ALIGNMENT_TEXT_START);
            t.setTextColor(Ui.color(this, R.attr.n_text_primary));
            card.addView(t);
        }
        String preview = preview(n);
        if (!preview.isEmpty()) {
            TextView b = new TextView(this);
            b.setText(preview);
            b.setTextSize(13.5f);
            b.setMaxLines(8);
            b.setEllipsize(TextUtils.TruncateAt.END);
            b.setLineSpacing(0, 1.1f);
            b.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
            b.setTextAlignment(View.TEXT_ALIGNMENT_TEXT_START);
            b.setTextColor(Ui.color(this, n.title.trim().isEmpty() ? R.attr.n_text_primary : R.attr.n_text_secondary));
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (!n.title.trim().isEmpty()) bl.topMargin = Ui.dp(this, 6);
            card.addView(b, bl);
        }

        LinearLayout foot = new LinearLayout(this);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        TextView d = new TextView(this);
        d.setText(DateUtils.getRelativeTimeSpanString(n.updatedAt, System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_ALL));
        d.setTextSize(11);
        d.setSingleLine(true);
        d.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        d.setTextColor(Ui.color(this, R.attr.n_text_hint));
        foot.addView(d, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (n.kind == Note.KIND_LIST) {
            int total = 0, done = 0;
            for (Note.Item i : Note.items(n.body)) {
                if (i.text.trim().isEmpty()) continue;
                total++;
                if (i.done) done++;
            }
            if (total > 0) {
                TextView p = new TextView(this);
                p.setText(getString(R.string.list_progress, done, total));
                p.setTextSize(11);
                p.setSingleLine(true);
                boolean all = done == total;
                p.setTextColor(Ui.color(this, all ? R.attr.n_ok : R.attr.n_text_hint));
                LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                pl.setMarginStart(Ui.dp(this, 6));
                foot.addView(p, pl);
            }
        }
        if (n.pinned) foot.addView(smallIcon(R.drawable.ic_star, R.attr.n_accent_text));
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fl.topMargin = Ui.dp(this, 10);
        card.addView(foot, fl);

        Ui.press(this, card);
        card.setOnClickListener(v -> startActivity(new Intent(this, NoteEditorActivity.class)
                .putExtra(NoteEditorActivity.EXTRA_ID, n.id)));
        card.setOnLongClickListener(v -> {
            NoteActions.menu(this, n, false, this::reload);
            return true;
        });
        return card;
    }

    private ImageView smallIcon(int res, int color) {
        ImageView v = new ImageView(this);
        v.setImageResource(res);
        v.setImageTintList(ColorStateList.valueOf(Ui.color(this, color)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 15), Ui.dp(this, 15));
        lp.setMarginStart(Ui.dp(this, 6));
        v.setLayoutParams(lp);
        return v;
    }

    private static String preview(Note n) {
        if (n.kind != Note.KIND_LIST) return n.body.trim();
        StringBuilder sb = new StringBuilder();
        for (Note.Item i : Note.items(n.body)) {
            if (i.text.trim().isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(i.done ? "✓  " : "○  ").append(i.text.trim());
        }
        return sb.toString();
    }
}
