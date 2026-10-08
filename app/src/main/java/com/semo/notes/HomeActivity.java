package com.semo.notes;

import android.animation.ObjectAnimator;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
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
import android.widget.HorizontalScrollView;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
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
    static final int PAGE = 60;

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
    private boolean firstDraw = true; // the very first draw may come from the splash-time preload
    private String lastSig = "";      // what is currently drawn: an identical reload is skipped (no flicker, no jump)
    private String lastViewKey = "";  // filter + query of the last draw: the scroll position is kept only inside the same view
    private Button btnMore;
    private ImageButton btnView;
    private List<Note> curAll = new ArrayList<>(), curList = new ArrayList<>();
    private boolean curMore;

    private LinearLayout gridPinned, gridAll, chipRow;
    private TextView secPinned, secAll, empty, emptyTitle, subtitle;
    private View emptyBox;
    private ImageView emptyIcon;
    private EditText search, quickText;
    private Button btnEmptyTrash;
    private ImageButton btnSync;
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

        // header: settings (start), sync status (end)
        ImageButton settings = findViewById(R.id.btnBack);
        settings.setImageResource(R.drawable.ic_settings);
        settings.setContentDescription(getString(R.string.settings));
        settings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        btnView = findViewById(R.id.btnA1);
        btnView.setVisibility(View.VISIBLE);
        btnView.setOnClickListener(v -> pickViewMode());
        Ui.press(this, btnView);
        applyViewIcon();
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
        // automatic import + export on every start (skipped when the splash screen already did it)
        if (!Preload.takeFreshSync()) Sync.request(this, true);
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
        if (running && Motion.enabled(this)) {
            if (spin == null) {
                // the iPhone spinner: the icon turns in 12 steps of 30 degrees, about one turn per second
                spin = ObjectAnimator.ofFloat(btnSync, View.ROTATION, 0f, 360f);
                spin.setDuration(1000);
                spin.setRepeatCount(ObjectAnimator.INFINITE);
                spin.setInterpolator(t -> (float) Math.floor(t * 12f) / 12f);
            }
            if (!spin.isStarted()) spin.start();
        } else if (spin != null) {
            spin.cancel();
            spin = null;
            btnSync.setRotation(0f);
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

    /** Compact quick-access pill: round icon + label side by side (about half the height of the old tall tiles). */
    private void addTile(LinearLayout row, int icon, int label, View.OnClickListener l) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.HORIZONTAL);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(Ui.color(this, R.attr.n_surface));
        fill.setCornerRadius(Ui.dp(this, 100));
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(Ui.dp(this, 100));
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.color(this, R.attr.n_ripple)), fill, mask));

        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        iv.setImageTintList(ColorStateList.valueOf(Ui.color(this, R.attr.n_accent_text)));
        iv.setBackgroundResource(R.drawable.bg_circle_accent);
        int p = Ui.dp(this, 7);
        iv.setPadding(p, p, p, p);
        t.addView(iv, new LinearLayout.LayoutParams(Ui.dp(this, 34), Ui.dp(this, 34)));

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(13);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setGravity(Gravity.CENTER);
        tv.setTextColor(Ui.color(this, R.attr.n_text_primary));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tl.setMarginStart(Ui.dp(this, 6));
        t.addView(tv, tl);

        Ui.press(this, t);
        t.setOnClickListener(l);
        t.setContentDescription(getString(label));
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
        final int mode = Prefs.viewMode(this);
        if (firstDraw) {
            firstDraw = false;
            List<Note> pre = Db.F_ALL.equals(f) && q.isEmpty() && lim == PAGE ? Preload.takeFirstPage() : null;
            if (pre != null) {
                // already read from SQLite during the splash: draw at once; the normal read below only redraws on a change
                lastSig = signature(f, q, lim, pre, Prefs.viewMode(this));
                render(pre, pre.size() > lim ? pre.subList(0, lim) : pre, pre.size() > lim);
                maybeShowUndo();
            }
        }
        dbEx.execute(() -> {
            final List<Note> rows = db.list(f, q, lim + 1);
            final String sig = signature(f, q, lim, rows, mode);
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
    private static String signature(String f, String q, int lim, List<Note> rows, int mode) {
        StringBuilder sb = new StringBuilder(rows.size() * 24 + 64);
        sb.append(f).append('|').append(q).append('|').append(lim).append('|').append(mode).append('|')
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
        curAll = all;
        curList = list;
        curMore = hasMore;
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

    // ------------------------------------------------------------------ view mode

    private void applyViewIcon() {
        int m = Prefs.viewMode(this);
        btnView.setImageResource(m == Prefs.VIEW_LIST ? R.drawable.ic_cards
                : m == Prefs.VIEW_COMPACT ? R.drawable.ic_list : R.drawable.ic_grid);
        btnView.setContentDescription(getString(R.string.view_mode));
    }

    private void pickViewMode() {
        final CharSequence[] names = {getString(R.string.view_grid), getString(R.string.view_list),
                getString(R.string.view_compact)};
        new Dlg(this)
                .setTitle(R.string.view_mode)
                .setItems(names, (d, which) -> {
                    if (which == Prefs.viewMode(this)) return;
                    Prefs.setViewMode(this, which);
                    applyViewIcon();
                    shown.clear();          // the cards are new: let them animate in once
                    lastSig = "";
                    render(curAll, curList, curMore);
                })
                .show();
    }

    /** Masonry for the grid (two columns, every card goes to the shorter one); one column for the lists. */
    private void fill(LinearLayout host, List<Note> list) {
        host.removeAllViews();
        if (list.isEmpty()) return;
        final int mode = Prefs.viewMode(this);
        final int cols = mode == Prefs.VIEW_GRID ? 2 : 1;
        host.setBaselineAligned(false);
        LinearLayout[] col = new LinearLayout[cols];
        int[] h = new int[cols];
        for (int i = 0; i < cols; i++) {
            col[i] = new LinearLayout(this);
            col[i].setOrientation(LinearLayout.VERTICAL);
            host.addView(col[i], new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        int idx = 0;
        for (Note n : list) {
            int c = cols == 2 && h[0] > h[1] ? 1 : 0;
            View card = card(n, mode);
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

    /** What a card shows: a title (the note's own, or its first line when that is a phone number / link) and a preview. */
    private static final class Face {
        String title = "";
        String body = "";
        int kind = Smart.NONE; // what the title is: a phone number, a link or plain text
    }

    private static Face face(Note n) {
        Face f = new Face();
        f.title = n.title.trim();
        f.body = preview(n);
        if (!f.title.isEmpty()) {
            f.kind = Smart.kindOf(f.title);
        } else if (n.kind != Note.KIND_LIST) {
            Smart.Lead lead = Smart.lead(f.body);
            if (lead != null) {
                f.title = lead.line;
                f.kind = lead.kind;
                f.body = lead.rest;
            }
        }
        return f;
    }

    private Drawable kindIcon(int kind, int sizeDp, int colorAttr) {
        Drawable d = getDrawable(kind == Smart.PHONE ? R.drawable.ic_phone
                : kind == Smart.LINK ? R.drawable.ic_link
                : R.drawable.ic_note).mutate();
        d.setBounds(0, 0, Ui.dp(this, sizeDp), Ui.dp(this, sizeDp));
        d.setTint(Ui.color(this, colorAttr));
        return d;
    }

    private View card(final Note n, int mode) {
        final boolean compact = mode == Prefs.VIEW_COMPACT;
        final boolean wide = mode != Prefs.VIEW_GRID;
        final Face face = face(n);
        final boolean smartTitle = face.kind != Smart.NONE;

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(compact ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        if (compact) card.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Ui.dp(this, compact ? 14 : 18);
        card.setPadding(pad, compact ? Ui.dp(this, 12) : pad, pad, compact ? Ui.dp(this, 12) : Ui.dp(this, 14));
        float r = Ui.dp(this, compact ? 20 : 28);
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
        int m = Ui.dp(this, compact ? 3 : 5);
        lp.setMargins(Ui.dp(this, 5), m, Ui.dp(this, 5), m);
        card.setLayoutParams(lp);

        Ui.press(this, card);
        card.setOnClickListener(v -> startActivity(new Intent(this, NoteEditorActivity.class)
                .putExtra(NoteEditorActivity.EXTRA_ID, n.id)));
        card.setOnLongClickListener(v -> {
            NoteActions.menu(this, n, false, this::reload);
            return true;
        });

        if (compact) {
            buildCompact(card, n, face, smartTitle);
            return card;
        }

        // ---- title row: [icon] title [call / open button in the list view]
        List<Smart.Hit> titleHits = smartTitle ? Smart.find(face.title) : new ArrayList<>();
        if (!face.title.isEmpty()) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView t = new TextView(this);
            t.setText(face.title);
            t.setTypeface(Typeface.SERIF);
            t.setTextSize(wide ? 17 : 16);
            t.setMaxLines(wide ? 2 : 3);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
            t.setTextAlignment(View.TEXT_ALIGNMENT_TEXT_START);
            if (smartTitle) {
                t.setTextColor(Ui.color(this, R.attr.n_accent_text));
                t.setTypeface(Typeface.SERIF, Typeface.BOLD);
                t.setCompoundDrawablePadding(Ui.dp(this, 8));
                t.setCompoundDrawablesRelative(kindIcon(face.kind, 18, R.attr.n_accent_text), null, null, null);
            } else {
                t.setTextColor(Ui.color(this, R.attr.n_text_primary));
            }
            row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (wide && !titleHits.isEmpty()) row.addView(actionButton(titleHits.get(0)));
            card.addView(row);
        }

        // ---- preview
        if (!face.body.isEmpty()) {
            TextView b = new TextView(this);
            b.setText(face.body);
            b.setTextSize(wide ? 14 : 13.5f);
            b.setMaxLines(wide ? 6 : 8);
            b.setEllipsize(TextUtils.TruncateAt.END);
            b.setLineSpacing(0, 1.1f);
            b.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
            b.setTextAlignment(View.TEXT_ALIGNMENT_TEXT_START);
            b.setTextColor(Ui.color(this, face.title.isEmpty() ? R.attr.n_text_primary : R.attr.n_text_secondary));
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (!face.title.isEmpty()) bl.topMargin = Ui.dp(this, 6);
            card.addView(b, bl);
        }

        // ---- other numbers / links of the note as tappable chips (list view only: the grid cards are narrow)
        if (wide) {
            List<Smart.Hit> hits = Smart.find(n.plainText());
            LinearLayout chips = new LinearLayout(this);
            chips.setOrientation(LinearLayout.HORIZONTAL);
            int added = 0;
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (Smart.Hit h : titleHits) seen.add(h.target());
            for (Smart.Hit h : hits) {
                if (added >= 3) break;
                if (!seen.add(h.target())) continue;
                chips.addView(hitChip(h));
                added++;
            }
            if (added > 0) {
                HorizontalScrollView hs = new HorizontalScrollView(this);
                hs.setHorizontalScrollBarEnabled(false);
                hs.setOverScrollMode(View.OVER_SCROLL_NEVER);
                hs.addView(chips);
                LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                cl.topMargin = Ui.dp(this, 10);
                card.addView(hs, cl);
            }
        }

        // ---- footer: date, checklist progress, pin
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
            int[] p = progress(n);
            if (p[1] > 0) foot.addView(progressView(p));
        }
        if (n.pinned) foot.addView(smallIcon(R.drawable.ic_star, R.attr.n_accent_text));
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fl.topMargin = Ui.dp(this, 10);
        card.addView(foot, fl);
        return card;
    }

    /** One tidy row per note: kind icon, title + one line of preview, then date and pin. */
    private void buildCompact(LinearLayout card, Note n, Face face, boolean smartTitle) {
        String title = face.title;
        String sub = face.body;
        if (title.isEmpty() && !sub.isEmpty()) { // no title: the first line plays that role
            int nl = sub.indexOf('\n');
            title = nl < 0 ? sub : sub.substring(0, nl).trim();
            sub = nl < 0 ? "" : sub.substring(nl + 1).trim();
        }
        int kind = smartTitle ? face.kind : Smart.NONE;
        int iconRes = kind == Smart.PHONE ? R.drawable.ic_phone : kind == Smart.LINK ? R.drawable.ic_link
                : n.kind == Note.KIND_LIST ? R.drawable.ic_checklist : R.drawable.ic_note;
        ImageView ic = new ImageView(this);
        ic.setImageResource(iconRes);
        ic.setImageTintList(ColorStateList.valueOf(Ui.color(this,
                smartTitle ? R.attr.n_accent_text : R.attr.n_text_secondary)));
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(Ui.dp(this, 20), Ui.dp(this, 20));
        il.setMarginEnd(Ui.dp(this, 12));
        card.addView(ic, il);

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title.isEmpty() ? getString(R.string.new_note) : title);
        t.setTypeface(Typeface.SERIF, smartTitle ? Typeface.BOLD : Typeface.NORMAL);
        t.setTextSize(15.5f);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        t.setTextAlignment(View.TEXT_ALIGNMENT_TEXT_START);
        t.setTextColor(Ui.color(this, smartTitle ? R.attr.n_accent_text : R.attr.n_text_primary));
        mid.addView(t);
        if (!sub.isEmpty()) {
            TextView s = new TextView(this);
            s.setText(sub.replace('\n', ' '));
            s.setTextSize(12.5f);
            s.setSingleLine(true);
            s.setEllipsize(TextUtils.TruncateAt.END);
            s.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
            s.setTextAlignment(View.TEXT_ALIGNMENT_TEXT_START);
            s.setTextColor(Ui.color(this, R.attr.n_text_secondary));
            mid.addView(s);
        }
        card.addView(mid, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout end = new LinearLayout(this);
        end.setOrientation(LinearLayout.VERTICAL);
        end.setGravity(Gravity.END);
        TextView d = new TextView(this);
        d.setText(DateUtils.getRelativeTimeSpanString(n.updatedAt, System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_ALL));
        d.setTextSize(11);
        d.setSingleLine(true);
        d.setTextColor(Ui.color(this, R.attr.n_text_hint));
        end.addView(d);
        if (n.kind == Note.KIND_LIST) {
            int[] p = progress(n);
            if (p[1] > 0) end.addView(progressView(p));
        }
        LinearLayout.LayoutParams el = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        el.setMarginStart(Ui.dp(this, 10));
        card.addView(end, el);
        if (n.pinned) card.addView(smallIcon(R.drawable.ic_star, R.attr.n_accent_text));
    }

    private static int[] progress(Note n) {
        int total = 0, done = 0;
        for (Note.Item i : Note.items(n.body)) {
            if (i.text.trim().isEmpty()) continue;
            total++;
            if (i.done) done++;
        }
        return new int[]{done, total};
    }

    private TextView progressView(int[] p) {
        TextView v = new TextView(this);
        v.setText(getString(R.string.list_progress, p[0], p[1]));
        v.setTextSize(11);
        v.setSingleLine(true);
        v.setTextColor(Ui.color(this, p[0] == p[1] ? R.attr.n_ok : R.attr.n_text_hint));
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pl.setMarginStart(Ui.dp(this, 6));
        v.setLayoutParams(pl);
        return v;
    }

    /** Round call / open button shown at the end of the title row. */
    private ImageButton actionButton(final Smart.Hit h) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(h.kind == Smart.PHONE ? R.drawable.ic_phone : R.drawable.ic_link);
        b.setImageTintList(ColorStateList.valueOf(Ui.color(this, R.attr.n_accent_text)));
        b.setBackgroundResource(R.drawable.bg_circle_accent);
        b.setScaleType(ImageView.ScaleType.CENTER);
        b.setContentDescription(getString(h.kind == Smart.PHONE ? R.string.call_number : R.string.open_link));
        b.setOnClickListener(v -> Smart.open(this, h));
        b.setOnLongClickListener(v -> {
            Smart.copy(this, h);
            return true;
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40));
        lp.setMarginStart(Ui.dp(this, 10));
        b.setLayoutParams(lp);
        return b;
    }

    /** Small pill with a phone number or link: tap to call / open, long-press to copy. */
    private TextView hitChip(final Smart.Hit h) {
        TextView c = new TextView(this);
        c.setText(h.label());
        c.setTextSize(12.5f);
        c.setSingleLine(true);
        c.setTextDirection(View.TEXT_DIRECTION_LTR);
        c.setTextColor(Ui.color(this, R.attr.n_accent_text));
        c.setBackgroundResource(R.drawable.bg_pill_neutral);
        c.setCompoundDrawablePadding(Ui.dp(this, 6));
        c.setCompoundDrawablesRelative(kindIcon(h.kind, 14, R.attr.n_accent_text), null, null, null);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setPadding(Ui.dp(this, 12), Ui.dp(this, 7), Ui.dp(this, 12), Ui.dp(this, 7));
        c.setOnClickListener(v -> Smart.open(this, h));
        c.setOnLongClickListener(v -> {
            Smart.copy(this, h);
            return true;
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(Ui.dp(this, 8));
        c.setLayoutParams(lp);
        return c;
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
