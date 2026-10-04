package com.semo.notes;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

/**
 * Public doorway for other apps and launcher shortcuts: "Share -> My Notes", "New note", "New checklist".
 * It only passes a bounded amount of plain text to the (private) editor, so an outside app can never
 * open, read or overwrite an existing note.
 */
public class CaptureActivity extends Activity {
    private static final int MAX_TITLE = 300;
    private static final int MAX_BODY = 100_000;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent in = getIntent();
        if (!Prefs.isCloud(this)) {
            Toast.makeText(this, R.string.sign_in_first, Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }
        Intent out = new Intent(this, NoteEditorActivity.class);
        String action = in == null ? null : in.getAction();
        int kind = Note.KIND_TEXT;
        if (NoteEditorActivity.ACTION_NEW_LIST.equals(action)) kind = Note.KIND_LIST;
        if (Intent.ACTION_SEND.equals(action) && "text/plain".equals(in.getType())) {
            try {
                CharSequence t = in.getCharSequenceExtra(Intent.EXTRA_TEXT);
                CharSequence s = in.getCharSequenceExtra(Intent.EXTRA_SUBJECT);
                if (t != null) out.putExtra(NoteEditorActivity.EXTRA_INIT, clip(t.toString(), MAX_BODY));
                if (s != null) out.putExtra(NoteEditorActivity.EXTRA_TITLE, clip(s.toString(), MAX_TITLE));
            } catch (Exception ignored) {
                // a malformed extra from the sending app: open an empty note instead
            }
        }
        out.putExtra(NoteEditorActivity.EXTRA_KIND, kind);
        startActivity(out);
        finish();
    }

    private static String clip(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }
}
