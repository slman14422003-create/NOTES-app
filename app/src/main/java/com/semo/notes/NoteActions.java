package com.semo.notes;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Actions shared by the home screen and the editor. Every change is saved locally and queued for sync. */
public final class NoteActions {
    private NoteActions() {
    }

    /** Last "moved to trash / archived" action, offered as an Undo bar on the home screen for a few seconds. */
    public static String undoId;
    public static int undoKind; // 0 = trash, 1 = archive
    public static long undoAt;
    public static boolean undoShown;

    private static void markUndo(Note n, int kind) {
        undoId = n.id;
        undoKind = kind;
        undoAt = System.currentTimeMillis();
        undoShown = false;
    }

    public static void undo(Context c) {
        if (undoId == null) return;
        Note n = Db.get(c).get(undoId);
        undoId = null;
        if (n == null || n.purged) return;
        if (undoKind == 0) n.trashed = false;
        else n.archived = false;
        save(c, n);
    }

    public static void save(Context c, Note n) {
        Db.get(c).saveLocal(n);
        Sync.request(c, false);
    }

    public static void togglePin(Context c, Note n) {
        n.pinned = !n.pinned;
        if (n.pinned) n.archived = false;
        save(c, n);
    }

    public static void toggleArchive(Context c, Note n) {
        n.archived = !n.archived;
        if (n.archived) {
            n.pinned = false;
            markUndo(n, 1);
        }
        save(c, n);
    }

    public static void trash(Context c, Note n) {
        n.trashed = true;
        n.pinned = false;
        markUndo(n, 0);
        save(c, n);
    }

    public static void restore(Context c, Note n) {
        n.trashed = false;
        save(c, n);
    }

    public static void deleteForever(Context c, Note n) {
        Db.get(c).purge(n.id);
        Sync.request(c, false);
    }

    public static String shareText(Note n) {
        String t = n.title.trim();
        String b = n.body;
        return t.isEmpty() ? b : (b.isEmpty() ? t : t + "\n\n" + b);
    }

    public static void copy(Context c, Note n) {
        Clip.copy(c, n.title, shareText(n));
        Toast.makeText(c, R.string.copied, Toast.LENGTH_SHORT).show();
    }

    public static void share(Context c, Note n) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_SUBJECT, n.title);
        i.putExtra(Intent.EXTRA_TEXT, shareText(n));
        c.startActivity(Intent.createChooser(i, c.getString(R.string.share)));
    }

    public static void confirmDeleteForever(Context c, Note n, Runnable after) {
        new Dlg(c)
                .setTitle(R.string.delete_forever)
                .setMessage(R.string.delete_forever_msg)
                .setPositiveButton(R.string.delete_forever, (d, w) -> {
                    deleteForever(c, n);
                    if (after != null) after.run();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** The actions offered for a note, with their icons; shared by the lift menu and the plain dialog. */
    public static List<LiftMenu.Item> items(Activity a, Note n, boolean inEditor, Runnable after) {
        List<LiftMenu.Item> out = new ArrayList<>();
        Runnable done = () -> {
            if (after != null) after.run();
        };
        if (n.trashed) {
            out.add(new LiftMenu.Item(a.getString(R.string.restore), R.drawable.ic_undo, false, () -> {
                restore(a, n);
                done.run();
            }));
            out.add(new LiftMenu.Item(a.getString(R.string.delete_forever), R.drawable.ic_delete, true,
                    () -> confirmDeleteForever(a, n, done)));
        } else {
            if (!inEditor) {
                out.add(new LiftMenu.Item(a.getString(n.pinned ? R.string.unpin : R.string.pin), R.drawable.ic_pin, false, () -> {
                    togglePin(a, n);
                    done.run();
                }));
            }
            out.add(new LiftMenu.Item(a.getString(n.archived ? R.string.unarchive : R.string.archive), R.drawable.ic_archive, false, () -> {
                toggleArchive(a, n);
                done.run();
            }));
            out.add(new LiftMenu.Item(a.getString(R.string.copy), R.drawable.ic_copy, false, () -> copy(a, n)));
            out.add(new LiftMenu.Item(a.getString(R.string.share), R.drawable.ic_share, false, () -> share(a, n)));
            out.add(new LiftMenu.Item(a.getString(R.string.move_to_trash), R.drawable.ic_delete, true, () -> {
                trash(a, n);
                Toast.makeText(a, R.string.moved_to_trash, Toast.LENGTH_SHORT).show();
                done.run();
            }));
        }
        return out;
    }

    /** The options menu of a note in the editor ("more" button): a plain list dialog. */
    public static void menu(Activity a, Note n, boolean inEditor, Runnable after) {
        final List<LiftMenu.Item> items = items(a, n, inEditor, after);
        CharSequence[] labels = new CharSequence[items.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = items.get(i).label;
        new Dlg(a).setItems(labels, (d, which) -> items.get(which).action.run()).show();
    }

    /** Long press on a card: blur the page, lift the card and show the action menu next to it. */
    public static void lift(Activity a, View card, Note n, Runnable after) {
        LiftMenu.show(a, card, items(a, n, false, after));
    }
}
