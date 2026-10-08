package com.semo.notes;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Finds phone numbers and links inside note text so they can be highlighted, used as a title and opened with one tap. */
public final class Smart {
    private Smart() {
    }

    public static final int NONE = 0;
    public static final int PHONE = 1;
    public static final int LINK = 2;

    /** A first line this short (or shorter) that holds a phone number / link is shown as the note's title. */
    private static final int MAX_LEAD = 60;

    private static final Pattern URL = Pattern.compile(
            "(?i)(?<![@\\w.\\-])((?:https?://|www\\.)[^\\s<>\"']+"
                    + "|[a-z0-9][a-z0-9\\-]*(?:\\.[a-z0-9\\-]+)*\\.(?:com|net|org|io|app|me|co|info|dev|ly|tv|ai|gov|edu|sa|ae|jo|ps|eg|iq|lb|sy|kw|qa|om|bh|ly|ma|dz|tn)(?:/[^\\s<>\"']*)?)");

    private static final Pattern PHONE_P = Pattern.compile("(?<![\\w.])(\\(?\\+?\\d[\\d\\s().\\-]{5,18}\\d)(?!\\w)");
    private static final Pattern DATE_LIKE = Pattern.compile(
            "\\d{4}[\\-/.]\\d{1,2}[\\-/.]\\d{1,2}|\\d{1,2}[\\-/.]\\d{1,2}[\\-/.]\\d{2,4}");

    /** One phone number or link found in a text. */
    public static final class Hit {
        public final int kind;
        public final String text;   // as written in the note
        public final int start, end;

        Hit(int kind, String text, int start, int end) {
            this.kind = kind;
            this.text = text;
            this.start = start;
            this.end = end;
        }

        /** Number with only digits (and a leading +) for dialling; the link with a scheme for opening. */
        public String target() {
            if (kind == PHONE) {
                String n = latin(text);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < n.length(); i++) {
                    char c = n.charAt(i);
                    if (c >= '0' && c <= '9') sb.append(c);
                    else if (c == '+' && sb.length() == 0) sb.append(c);
                }
                return sb.toString();
            }
            String t = text.trim();
            return t.toLowerCase().startsWith("http://") || t.toLowerCase().startsWith("https://") ? t : "https://" + t;
        }

        /** Short label for a chip. */
        public String label() {
            String s = kind == PHONE ? text.trim() : text.trim().replaceFirst("(?i)^https?://", "").replaceFirst("(?i)^www\\.", "");
            if (kind == LINK && s.endsWith("/")) s = s.substring(0, s.length() - 1);
            return s.length() > 26 ? s.substring(0, 25) + "…" : s;
        }
    }

    /** The first line of a note when it is a phone number / link, plus what is left of the note after it. */
    public static final class Lead {
        public final String line;
        public final int kind;
        public final String rest;

        Lead(String line, int kind, String rest) {
            this.line = line;
            this.kind = kind;
            this.rest = rest;
        }
    }

    /** Arabic-Indic and Persian digits to 0-9; same length, so positions stay valid. */
    static String latin(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '\u0660' && c <= '\u0669') c = (char) ('0' + (c - '\u0660'));
            else if (c >= '\u06F0' && c <= '\u06F9') c = (char) ('0' + (c - '\u06F0'));
            sb.append(c);
        }
        return sb.toString();
    }

    /** All links first, then phone numbers that do not sit inside a link; sorted by position. */
    public static List<Hit> find(String text) {
        List<Hit> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        String t = latin(text);

        Matcher m = URL.matcher(t);
        while (m.find()) {
            int s = m.start(1), e = m.end(1);
            while (e > s && ".,;:!?)]}»،؛".indexOf(t.charAt(e - 1)) >= 0) e--;
            if (e - s < 4) continue;
            out.add(new Hit(LINK, text.substring(s, e), s, e));
        }

        m = PHONE_P.matcher(t);
        while (m.find()) {
            int s = m.start(1), e = m.end(1);
            String raw = t.substring(s, e);
            int digits = 0;
            for (int i = 0; i < raw.length(); i++) if (Character.isDigit(raw.charAt(i))) digits++;
            if (digits < 7 || digits > 15) continue;
            // a bare run of digits (no spaces / dashes) is a phone number only when it starts like one;
            // otherwise it is far more likely a price, a quantity or an id
            if (raw.matches("\\d+") && !raw.startsWith("0") && digits < 9) continue;
            if (DATE_LIKE.matcher(raw.trim()).matches()) continue;
            boolean inside = false;
            for (Hit h : out) if (h.kind == LINK && s < h.end && e > h.start) inside = true;
            if (inside) continue;
            out.add(new Hit(PHONE, text.substring(s, e), s, e));
        }
        java.util.Collections.sort(out, (a, b) -> Integer.compare(a.start, b.start));
        return out;
    }

    /** Kind of the first hit in a short text (a title), NONE when it has none. */
    public static int kindOf(String text) {
        if (text == null) return NONE;
        List<Hit> h = find(text);
        return h.isEmpty() ? NONE : h.get(0).kind;
    }

    /** Non-null when the first non-empty line of the text is short and holds a phone number or link. */
    public static Lead lead(String text) {
        if (text == null) return null;
        int i = 0, n = text.length();
        while (i < n && Character.isWhitespace(text.charAt(i))) i++;
        if (i >= n) return null;
        int nl = text.indexOf('\n', i);
        String line = (nl < 0 ? text.substring(i) : text.substring(i, nl)).trim();
        if (line.isEmpty() || line.length() > MAX_LEAD) return null;
        int k = kindOf(line);
        if (k == NONE) return null;
        String rest = nl < 0 ? "" : text.substring(nl + 1).trim();
        return new Lead(line, k, rest);
    }

    /** Dial pad for a number (no permission needed, the user presses call) or the browser for a link. */
    public static void open(Context c, Hit h) {
        try {
            Intent i = h.kind == PHONE
                    ? new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(h.target())))
                    : new Intent(Intent.ACTION_VIEW, Uri.parse(h.target()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(c, R.string.cant_open, Toast.LENGTH_SHORT).show();
        }
    }

    public static void copy(Context c, Hit h) {
        ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        cm.setPrimaryClip(ClipData.newPlainText("note", h.kind == PHONE ? h.target() : h.text.trim()));
        Toast.makeText(c, R.string.copied, Toast.LENGTH_SHORT).show();
    }
}
