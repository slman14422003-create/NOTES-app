package com.semo.notes;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class Note {
    public static final int KIND_TEXT = 0;
    public static final int KIND_LIST = 1;

    public String id = UUID.randomUUID().toString();
    public String title = "";
    /** Plain text, or for checklists one item per line written as "[ ] text" / "[x] text". */
    public String body = "";
    public int kind = KIND_TEXT;
    public int color = 0;
    public boolean pinned;
    public boolean archived;
    public boolean trashed;
    /** Permanently deleted: kept as an empty tombstone so other devices delete it too. */
    public boolean purged;
    public long createdAt = System.currentTimeMillis();
    public long updatedAt = createdAt;
    /** Local only: changed on this device and not pushed to Firebase yet. */
    public boolean dirty;

    public boolean isEmpty() {
        if (!title.trim().isEmpty()) return false;
        if (kind == KIND_LIST) {
            for (Item i : items(body)) if (!i.text.trim().isEmpty()) return false;
            return true;
        }
        return body.trim().isEmpty();
    }

    public static final class Item {
        public boolean done;
        public String text;

        public Item(boolean done, String text) {
            this.done = done;
            this.text = text;
        }
    }

    public static List<Item> items(String body) {
        List<Item> out = new ArrayList<>();
        if (body == null || body.isEmpty()) return out;
        for (String line : body.split("\n", -1)) {
            if (line.startsWith("[x] ") || line.startsWith("[X] ")) out.add(new Item(true, line.substring(4)));
            else if (line.startsWith("[ ] ")) out.add(new Item(false, line.substring(4)));
            else if (line.equals("[x]") || line.equals("[X]")) out.add(new Item(true, ""));
            else if (line.equals("[ ]")) out.add(new Item(false, ""));
            else out.add(new Item(false, line));
        }
        return out;
    }

    public static String join(List<Item> items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append('\n');
            Item it = items.get(i);
            sb.append(it.done ? "[x] " : "[ ] ").append(it.text.replace("\n", " "));
        }
        return sb.toString();
    }

    /** Text -> checklist: every non-empty line becomes an item. */
    public static String textToList(String text) {
        List<Item> l = new ArrayList<>();
        for (String line : text.split("\n")) if (!line.trim().isEmpty()) l.add(new Item(false, line.trim()));
        return join(l);
    }

    /** Checklist -> text: one line per item. */
    public static String listToText(String body) {
        StringBuilder sb = new StringBuilder();
        for (Item i : items(body)) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(i.text);
        }
        return sb.toString();
    }

    /** Text that is shown in lists and used for sharing / search. */
    public String plainText() {
        return kind == KIND_LIST ? listToText(body) : body;
    }
}
