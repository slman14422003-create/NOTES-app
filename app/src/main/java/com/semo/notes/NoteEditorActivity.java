package com.semo.notes;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * Note editor: title + text or checklist, colour, pin. Everything is saved automatically a moment
 * after you stop typing and when you leave, then pushed to Firebase by {@link Sync}.
 */
public class NoteEditorActivity extends AppCompatActivity {
    public static final String EXTRA_ID = "id";
    public static final String EXTRA_KIND = "kind";
    public static final String EXTRA_INIT = "init";
    public static final String EXTRA_TITLE = "title";
    public static final String ACTION_NEW = "com.semo.notes.NEW";
    public static final String ACTION_NEW_LIST = "com.semo.notes.NEW_LIST";

    private static final int MAX_TITLE = 300;
    private static final int MAX_BODY = 100_000;

    private Db db;
    private Note note;
    private boolean persisted;
    private String lastSnap = "";
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable autosave = () -> saveNow(false);
    private boolean binding;
    private View rootView;
    private int pageColor;
    private boolean colorReady;

    private EditText titleEt, bodyEt;
    private LinearLayout page, itemsBox, palette;
    private TextView meta, addItem, btnKind;
    private ImageButton btnPin;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeKit.apply(this);
        super.onCreate(savedInstanceState);
        if (Integrity.enforce(this)) return;
        setContentView(R.layout.activity_editor);
        db = Db.get(this);

        titleEt = findViewById(R.id.noteTitle);
        bodyEt = findViewById(R.id.body);
        page = findViewById(R.id.page);
        rootView = findViewById(R.id.root);
        itemsBox = findViewById(R.id.items);
        palette = findViewById(R.id.palette);
        meta = findViewById(R.id.meta);
        addItem = findViewById(R.id.addItem);
        btnKind = findViewById(R.id.btnKind);
        titleEt.setFilters(new InputFilter[]{new InputFilter.LengthFilter(MAX_TITLE)});
        bodyEt.setFilters(new InputFilter[]{new InputFilter.LengthFilter(MAX_BODY)});

        // header: back | title | pin | more
        findViewById(R.id.btnBack).setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        btnPin = findViewById(R.id.btnA1);
        btnPin.setVisibility(View.VISIBLE);
        btnPin.setImageResource(R.drawable.ic_star);
        btnPin.setContentDescription(getString(R.string.pin));
        btnPin.setOnClickListener(v -> {
            syncFromUi();
            note.pinned = !note.pinned;
            if (note.pinned) note.archived = false;
            applyPinIcon();
            scheduleSave();
        });
        ImageButton more = findViewById(R.id.btnRefresh);
        more.setImageResource(R.drawable.ic_more);
        more.setContentDescription(getString(R.string.more));
        more.setOnClickListener(v -> {
            syncFromUi();
            saveNow(false);
            if (!persisted) return;
            NoteActions.menu(this, note, true, () -> {
                lastSnap = snapshot();
                finish();
            });
        });
        Ui.press(this, findViewById(R.id.btnBack));
        Ui.press(this, btnPin);
        Ui.press(this, more);
        Ui.press(this, btnKind);

        android.animation.LayoutTransition lt = new android.animation.LayoutTransition();
        lt.setDuration(220);
        lt.enableTransitionType(android.animation.LayoutTransition.CHANGING);
        itemsBox.setLayoutTransition(lt);

        loadNote(getIntent());
        bindAll();

        TextWatcher tw = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }

            @Override
            public void afterTextChanged(Editable s) {
                if (!binding) scheduleSave();
            }
        };
        titleEt.addTextChangedListener(tw);
        bodyEt.addTextChangedListener(tw);

        btnKind.setOnClickListener(v -> toggleKind());
        addItem.setOnClickListener(v -> addRow(itemsBox.getChildCount(), new Note.Item(false, ""), true));

        if (!persisted) {
            View target = note.kind == Note.KIND_LIST ? firstItemEdit() : bodyEt;
            if (note.title.isEmpty() && note.body.isEmpty()) target = titleEt;
            focus(target);
        }
    }

    // ------------------------------------------------------------------ load / bind

    private void loadNote(Intent in) {
        String id = in.getStringExtra(EXTRA_ID);
        if (id != null) {
            Note n = db.get(id);
            if (n != null && !n.purged) {
                note = n;
                persisted = true;
                lastSnap = snapshot();
                return;
            }
        }
        note = new Note();
        int kind = in.getIntExtra(EXTRA_KIND, Note.KIND_TEXT);
        note.kind = kind == Note.KIND_LIST ? Note.KIND_LIST : Note.KIND_TEXT;
        String title = in.getStringExtra(EXTRA_TITLE);
        if (title != null) note.title = title.length() > MAX_TITLE ? title.substring(0, MAX_TITLE) : title;
        String init = in.getStringExtra(EXTRA_INIT);
        if (init != null) {
            if (init.length() > MAX_BODY) init = init.substring(0, MAX_BODY);
            note.body = note.kind == Note.KIND_LIST ? Note.textToList(init) : init;
        }
    }

    private void bindAll() {
        binding = true;
        titleEt.setText(note.title);
        ((TextView) findViewById(R.id.title)).setText(note.kind == Note.KIND_LIST ? R.string.list : R.string.note);
        findViewById(R.id.subtitle).setVisibility(View.GONE);
        bindBody();
        applyColor();
        applyPinIcon();
        buildPalette();
        updateMeta();
        binding = false;
    }

    private void bindBody() {
        boolean list = note.kind == Note.KIND_LIST;
        bodyEt.setVisibility(list ? View.GONE : View.VISIBLE);
        itemsBox.setVisibility(list ? View.VISIBLE : View.GONE);
        addItem.setVisibility(list ? View.VISIBLE : View.GONE);
        btnKind.setText(list ? R.string.convert_to_text : R.string.convert_to_list);
        btnKind.setCompoundDrawablesRelativeWithIntrinsicBounds(
                list ? R.drawable.ic_note : R.drawable.ic_checklist, 0, 0, 0);
        if (list) {
            itemsBox.removeAllViews();
            List<Note.Item> items = Note.items(note.body);
            if (items.isEmpty()) items.add(new Note.Item(false, ""));
            for (int i = 0; i < items.size(); i++) addRow(i, items.get(i), false);
        } else {
            bodyEt.setText(note.body);
        }
    }

    /** The whole screen takes the note colour (the default note keeps the plain page colour). */
    private void applyColor() {
        final int to = note.color == 0 ? Ui.color(this, R.attr.n_bg) : Palette.card(this, note.color);
        if (!colorReady || !Motion.enabled(this) || to == pageColor) {
            rootView.setBackgroundColor(to);
            pageColor = to;
            colorReady = true;
            return;
        }
        final int from = pageColor;
        pageColor = to;
        android.animation.ValueAnimator va = android.animation.ValueAnimator.ofObject(
                new android.animation.ArgbEvaluator(), from, to);
        va.setDuration(320);
        va.setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f));
        va.addUpdateListener(an -> rootView.setBackgroundColor((int) an.getAnimatedValue()));
        va.start();
    }

    private void applyPinIcon() {
        btnPin.setImageTintList(ColorStateList.valueOf(Ui.color(this,
                note.pinned ? R.attr.n_accent_text : R.attr.n_text_primary)));
        btnPin.setContentDescription(getString(note.pinned ? R.string.unpin : R.string.pin));
    }

    private void buildPalette() {
        palette.removeAllViews();
        for (int i = 0; i < Palette.DOT.length; i++) {
            final int idx = i;
            FrameLayout cell = new FrameLayout(this);
            cell.setLayoutParams(new LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1f));
            View d = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(Palette.dot(i));
            boolean sel = i == note.color;
            if (sel) g.setStroke(Ui.dp(this, 3), Ui.color(this, R.attr.n_text_primary));
            else g.setStroke(Ui.dp(this, 1), Ui.color(this, R.attr.n_stroke));
            d.setBackground(g);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Ui.dp(this, 30), Ui.dp(this, 30), Gravity.CENTER);
            d.setLayoutParams(lp);
            d.setContentDescription(getString(R.string.color));
            if (sel) {
                d.setScaleX(0.8f);
                d.setScaleY(0.8f);
                Motion.spring(d, androidx.dynamicanimation.animation.DynamicAnimation.SCALE_X, 1.12f, 420f, 0.5f);
                Motion.spring(d, androidx.dynamicanimation.animation.DynamicAnimation.SCALE_Y, 1.12f, 420f, 0.5f);
            }
            Ui.press(this, cell);
            cell.setOnClickListener(v -> {
                if (idx == note.color) return;
                syncFromUi();
                note.color = idx;
                applyColor();
                buildPalette();
                scheduleSave();
            });
            cell.addView(d);
            palette.addView(cell);
        }
    }

    private void updateMeta() {
        String when = persisted
                ? getString(R.string.edited, DateUtils.formatDateTime(this, note.updatedAt,
                DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_ABBREV_MONTH))
                : getString(R.string.new_note);
        String text = note.plainText().trim();
        int words = text.isEmpty() ? 0 : text.split("\\s+").length;
        meta.setText(words > 0 ? when + "  ·  " + getString(R.string.words, words) : when);
    }

    // ------------------------------------------------------------------ checklist rows

    private void addRow(int index, Note.Item item, boolean focus) {
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        final CheckBox cb = new CheckBox(this);
        cb.setButtonTintList(ContextCompat.getColorStateList(this, R.color.check_tint));
        cb.setChecked(item.done);
        row.addView(cb, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));

        final EditText et = new EditText(this);
        et.setBackground(null);
        et.setText(item.text);
        et.setTextSize(16);
        et.setHint(R.string.item_hint);
        et.setHintTextColor(Ui.color(this, R.attr.n_text_hint));
        // alignment follows the language of the text itself, so Arabic and English notes both read naturally
        et.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        et.setTextAlignment(View.TEXT_ALIGNMENT_TEXT_START);
        et.setRawInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        et.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        et.setHorizontallyScrolling(false);
        et.setMaxLines(6);
        et.setPadding(Ui.dp(this, 4), Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 10));
        row.addView(et, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        styleItem(et, item.done);

        ImageButton del = new ImageButton(this);
        del.setBackground(null);
        del.setImageResource(R.drawable.ic_cancel);
        del.setImageTintList(ColorStateList.valueOf(Ui.color(this, R.attr.n_text_hint)));
        del.setContentDescription(getString(R.string.delete));
        row.addView(del, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));

        cb.setOnCheckedChangeListener((b, on) -> {
            styleItem(et, on);
            scheduleSave();
        });
        et.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }

            @Override
            public void afterTextChanged(Editable s) {
                if (!binding) scheduleSave();
            }
        });
        et.setOnEditorActionListener((v, action, e) -> {
            if (action == EditorInfo.IME_ACTION_NEXT) {
                addRow(itemsBox.indexOfChild(row) + 1, new Note.Item(false, ""), true);
                return true;
            }
            return false;
        });
        et.setOnKeyListener((v, code, e) -> {
            if (code == KeyEvent.KEYCODE_DEL && e.getAction() == KeyEvent.ACTION_DOWN
                    && et.getText().length() == 0 && itemsBox.getChildCount() > 1) {
                removeRow(row, true);
                return true;
            }
            return false;
        });
        del.setOnClickListener(v -> {
            if (itemsBox.getChildCount() > 1) removeRow(row, false);
            else et.setText("");
            scheduleSave();
        });

        itemsBox.addView(row, Math.max(0, Math.min(index, itemsBox.getChildCount())));
        if (focus) focus(et);
    }

    private void removeRow(View row, boolean focusPrevious) {
        int i = itemsBox.indexOfChild(row);
        itemsBox.removeView(row);
        if (focusPrevious && itemsBox.getChildCount() > 0) {
            View prev = itemsBox.getChildAt(Math.max(0, i - 1));
            EditText et = (EditText) ((LinearLayout) prev).getChildAt(1);
            et.requestFocus();
            et.setSelection(et.getText().length());
        }
        scheduleSave();
    }

    private void styleItem(EditText et, boolean done) {
        et.setTextColor(Ui.color(this, done ? R.attr.n_text_hint : R.attr.n_text_primary));
        if (done) et.setPaintFlags(et.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
        else et.setPaintFlags(et.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
    }

    private EditText firstItemEdit() {
        if (itemsBox.getChildCount() == 0) return titleEt;
        return (EditText) ((LinearLayout) itemsBox.getChildAt(itemsBox.getChildCount() - 1)).getChildAt(1);
    }

    private void focus(final View v) {
        v.requestFocus();
        v.postDelayed(() -> {
            InputMethodManager im = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (im != null) im.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT);
        }, 150);
    }

    // ------------------------------------------------------------------ text <-> checklist

    private void toggleKind() {
        syncFromUi();
        if (note.kind == Note.KIND_TEXT) {
            note.body = Note.textToList(note.body);
            note.kind = Note.KIND_LIST;
        } else {
            note.body = Note.listToText(note.body);
            note.kind = Note.KIND_TEXT;
        }
        binding = true;
        ((TextView) findViewById(R.id.title)).setText(note.kind == Note.KIND_LIST ? R.string.list : R.string.note);
        bindBody();
        binding = false;
        scheduleSave();
    }

    /** Copies what is on screen into the note object. */
    private void syncFromUi() {
        note.title = titleEt.getText().toString();
        if (note.kind == Note.KIND_LIST) {
            List<Note.Item> items = new ArrayList<>();
            for (int i = 0; i < itemsBox.getChildCount(); i++) {
                LinearLayout row = (LinearLayout) itemsBox.getChildAt(i);
                boolean done = ((CheckBox) row.getChildAt(0)).isChecked();
                String text = ((EditText) row.getChildAt(1)).getText().toString();
                items.add(new Note.Item(done, text));
            }
            note.body = Note.join(items);
        } else {
            note.body = bodyEt.getText().toString();
        }
    }

    // ------------------------------------------------------------------ saving

    private String snapshot() {
        return note.title + "\u0000" + note.body + "\u0000" + note.kind + note.color + note.pinned
                + note.archived + note.trashed;
    }

    private void scheduleSave() {
        h.removeCallbacks(autosave);
        h.postDelayed(autosave, 700);
    }

    /** {@code leaving}: the screen is closing - an empty note is not kept. */
    private void saveNow(boolean leaving) {
        h.removeCallbacks(autosave);
        syncFromUi();
        if (note.isEmpty()) {
            if (leaving && persisted) {
                db.purge(note.id);
                Sync.request(this, false);
            }
            return;
        }
        String snap = snapshot();
        if (persisted && snap.equals(lastSnap)) return;
        NoteActions.save(this, note);
        persisted = true;
        lastSnap = snap;
        updateMeta();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (titleEt == null) return; // blocked by the integrity check
        saveNow(isFinishing());
    }
}
