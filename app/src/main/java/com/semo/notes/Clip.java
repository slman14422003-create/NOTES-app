package com.semo.notes;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.text.TextUtils;

/** Clipboard writes for note text: marked sensitive (hidden in the paste preview) and cleared after a minute. */
final class Clip {
    private Clip() {
    }

    static void copy(Context ctx, CharSequence label, CharSequence text) {
        final ClipboardManager cm = (ClipboardManager) ctx.getApplicationContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        ClipData d = ClipData.newPlainText(label, text);
        try {
            PersistableBundle pb = new PersistableBundle();
            pb.putBoolean("android.content.extra.IS_SENSITIVE", true);
            d.getDescription().setExtras(pb);
        } catch (Throwable ignored) {
        }
        cm.setPrimaryClip(d);
        final String t = String.valueOf(text);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                ClipData cur = cm.getPrimaryClip();
                if (cur != null && cur.getItemCount() > 0 && TextUtils.equals(cur.getItemAt(0).getText(), t)) {
                    if (Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip();
                    else cm.setPrimaryClip(ClipData.newPlainText("", ""));
                }
            } catch (Throwable ignored) {
            }
        }, 60_000);
    }
}
