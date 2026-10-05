package com.semo.notes;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Local-first storage. Every screen reads from here; Firebase is only synchronised in the background. */
public final class Db extends SQLiteOpenHelper {
    public static final String F_ALL = "all";
    public static final String F_PINNED = "pinned";
    public static final String F_LIST = "list";
    public static final String F_ARCHIVE = "archive";
    public static final String F_TRASH = "trash";

    private static Db inst;

    private final Context ctx;

    public static synchronized Db get(Context c) {
        if (inst == null) {
            inst = new Db(c.getApplicationContext());
            Vault.dataKey(inst.ctx);
            if (Vault.dataKeyReset) {
                // the key that encrypted the local notes is gone: drop them, the cloud copy is imported again
                Vault.dataKeyReset = false;
                inst.wipeAll();
                Prefs.setCursor(inst.ctx, "");
            }
        }
        return inst;
    }

    private Db(Context c) {
        super(c, "notes.db", null, 3);
        ctx = c;
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.setForeignKeyConstraintsEnabled(false);
        setWriteAheadLoggingEnabled(true); // readers never block the writer: smoother list while syncing
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE notes (id TEXT PRIMARY KEY, title TEXT NOT NULL DEFAULT '', "
                + "body TEXT NOT NULL DEFAULT '', kind INTEGER NOT NULL DEFAULT 0, color INTEGER NOT NULL DEFAULT 0, "
                + "pinned INTEGER NOT NULL DEFAULT 0, archived INTEGER NOT NULL DEFAULT 0, "
                + "trashed INTEGER NOT NULL DEFAULT 0, purged INTEGER NOT NULL DEFAULT 0, "
                + "created INTEGER NOT NULL, updated INTEGER NOT NULL, dirty INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX idx_notes_updated ON notes(updated)");
        db.execSQL("CREATE INDEX idx_notes_state ON notes(purged, archived, trashed, pinned, updated)");
        db.execSQL("CREATE INDEX idx_notes_dirty ON notes(dirty)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        if (oldV < 3) encryptExisting(db); // notes written by older versions were stored as plain text
        if (oldV < 2) {
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_notes_state ON notes(purged, archived, trashed, pinned, updated)");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_notes_dirty ON notes(dirty)");
        }
    }

    /** One-time upgrade: encrypts every title and body that is still stored as plain text. */
    private void encryptExisting(SQLiteDatabase db) {
        List<String[]> rows = new ArrayList<>();
        try (Cursor c = db.rawQuery("SELECT id,title,body FROM notes", null)) {
            while (c.moveToNext()) rows.add(new String[]{c.getString(0), c.getString(1), c.getString(2)});
        }
        for (String[] r : rows) {
            if (Vault.isSealed(r[1]) && Vault.isSealed(r[2])) continue;
            ContentValues v = new ContentValues();
            v.put("title", Vault.isSealed(r[1]) ? r[1] : Vault.seal(ctx, r[1]));
            v.put("body", Vault.isSealed(r[2]) ? r[2] : Vault.seal(ctx, r[2]));
            db.update("notes", v, "id=?", new String[]{r[0]});
        }
    }

    private static final String COLS = "id,title,body,kind,color,pinned,archived,trashed,purged,created,updated,dirty";

    private Note read(Cursor c) {
        Note n = new Note();
        n.id = c.getString(0);
        n.title = Vault.open(ctx, c.getString(1));
        n.body = Vault.open(ctx, c.getString(2));
        n.kind = c.getInt(3);
        n.color = c.getInt(4);
        n.pinned = c.getInt(5) == 1;
        n.archived = c.getInt(6) == 1;
        n.trashed = c.getInt(7) == 1;
        n.purged = c.getInt(8) == 1;
        n.createdAt = c.getLong(9);
        n.updatedAt = c.getLong(10);
        n.dirty = c.getInt(11) == 1;
        return n;
    }

    private ContentValues values(Note n) {
        ContentValues v = new ContentValues();
        v.put("id", n.id);
        v.put("title", Vault.seal(ctx, n.title));
        v.put("body", Vault.seal(ctx, n.body));
        v.put("kind", n.kind);
        v.put("color", n.color);
        v.put("pinned", n.pinned ? 1 : 0);
        v.put("archived", n.archived ? 1 : 0);
        v.put("trashed", n.trashed ? 1 : 0);
        v.put("purged", n.purged ? 1 : 0);
        v.put("created", n.createdAt);
        v.put("updated", n.updatedAt);
        v.put("dirty", n.dirty ? 1 : 0);
        return v;
    }

    public synchronized Note get(String id) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT " + COLS + " FROM notes WHERE id=?", new String[]{id})) {
            return c.moveToFirst() ? read(c) : null;
        }
    }

    /** A local edit: stamps the time and marks the note as waiting for the next sync. */
    public synchronized void saveLocal(Note n) {
        n.updatedAt = Math.max(System.currentTimeMillis(), n.updatedAt + 1);
        n.dirty = true;
        getWritableDatabase().insertWithOnConflict("notes", null, values(n), SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** A note that came from Firebase / an import and matches the cloud copy. */
    public synchronized void putClean(Note n) {
        n.dirty = false;
        getWritableDatabase().insertWithOnConflict("notes", null, values(n), SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** A note read from a backup file: keeps its timestamps, waits for the next sync. */
    public synchronized void putImported(Note n) {
        n.dirty = true;
        getWritableDatabase().insertWithOnConflict("notes", null, values(n), SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** Clears the dirty flag only if the note was not edited again while it was being uploaded. */
    public synchronized void markClean(String id, long updated) {
        getWritableDatabase().execSQL("UPDATE notes SET dirty=0 WHERE id=? AND updated=?", new Object[]{id, updated});
    }

    /** Permanent delete: wipes the content but keeps a tombstone so the deletion syncs. */
    public synchronized void purge(String id) {
        Note n = get(id);
        if (n == null) return;
        n.title = "";
        n.body = "";
        n.purged = true;
        n.pinned = false;
        saveLocal(n);
    }

    public synchronized int emptyTrash() {
        List<Note> l = list(F_TRASH, null);
        for (Note n : l) purge(n.id);
        return l.size();
    }

    /** After signing out but keeping the notes: they are uploaded to whichever account signs in next. */
    public synchronized void markAllDirty() {
        getWritableDatabase().execSQL("UPDATE notes SET dirty=1 WHERE purged=0");
    }

    public synchronized void wipeAll() {
        getWritableDatabase().delete("notes", null, null);
    }

    public synchronized List<Note> dirty() {
        return query("dirty=1", null, "updated ASC");
    }

    /** Every note that is not a tombstone (used by export). */
    public synchronized List<Note> allLive() {
        return query("purged=0", null, "updated DESC");
    }

    public synchronized List<Note> allRows() {
        return query(null, null, "updated DESC");
    }

    private static String where(String filter) {
        StringBuilder w = new StringBuilder("purged=0 AND ");
        switch (filter == null ? F_ALL : filter) {
            case F_PINNED:
                w.append("pinned=1 AND archived=0 AND trashed=0");
                break;
            case F_LIST:
                w.append("kind=1 AND archived=0 AND trashed=0");
                break;
            case F_ARCHIVE:
                w.append("archived=1 AND trashed=0");
                break;
            case F_TRASH:
                w.append("trashed=1");
                break;
            default:
                w.append("archived=0 AND trashed=0");
        }
        return w.toString();
    }

    public List<Note> list(String filter, String q) {
        return list(filter, q, 0);
    }

    /** {@code limit <= 0} = no limit. A search query is matched in memory because titles and bodies are encrypted. */
    public synchronized List<Note> list(String filter, String q, int limit) {
        String order = "pinned DESC, updated DESC";
        String w = where(filter);
        if (q == null || q.trim().isEmpty()) {
            return query(w, null, order + (limit > 0 ? " LIMIT " + limit : ""));
        }
        String needle = q.trim().toLowerCase(Locale.ROOT);
        List<Note> out = new ArrayList<>();
        for (Note n : query(w, null, order)) {
            if (n.title.toLowerCase(Locale.ROOT).contains(needle)
                    || n.plainText().toLowerCase(Locale.ROOT).contains(needle)) {
                out.add(n);
                if (limit > 0 && out.size() >= limit) break;
            }
        }
        return out;
    }

    public synchronized int count(String filter) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM notes WHERE " + where(filter), null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    private List<Note> query(String where, String[] args, String order) {
        List<Note> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT " + COLS + " FROM notes"
                + (where == null ? "" : " WHERE " + where) + " ORDER BY " + order, args)) {
            while (c.moveToNext()) out.add(read(c));
        }
        return out;
    }
}
