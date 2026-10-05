package com.semo.notes;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Looks for a newer APK in the GitHub Releases of the app's own (public) repository, downloads it
 * inside the app, verifies it and hands it to the system installer.
 * The build workflow writes "versionCode: N" and the APK's SHA-256 into every release's notes.
 */
public final class Updater {
    private Updater() {
    }

    private static final long AUTO_INTERVAL_MS = 6L * 60L * 60L * 1000L;
    private static final ExecutorService EX = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public static final class Info {
        public String tag = "";
        public String name = "";
        public String body = "";
        public String htmlUrl = "";
        public String publishedAt = "";
        public boolean prerelease;
        public String assetUrl = "";
        public String assetName = "";
        public long assetSize;
        public long versionCode;
        public String sha256 = "";
        public boolean newer;
    }

    public static final class UpdateException extends Exception {
        public final int http;
        /** One of: NETWORK, NOT_FOUND, RATE_LIMIT, SIZE, HASH, SIGNATURE, PACKAGE, OTHER. */
        public final String kind;

        UpdateException(String kind, int http, String message) {
            super(message);
            this.kind = kind;
            this.http = http;
        }
    }

    public interface Progress {
        void onBytes(long done, long total);
    }

    // ------------------------------------------------------------------ source + installed version

    /** "owner/repo": the user's choice, else the repository this APK was built in. */
    public static String repo(Context c) {
        String v = Prefs.updateRepo(c);
        if (validSlug(v)) return v;
        v = Secrets.updateRepo().trim();
        if (validSlug(v)) return v;
        return Cfg.UPDATE_REPO_FALLBACK;
    }

    public static boolean validSlug(String s) {
        if (s == null) return false;
        int i = s.indexOf('/');
        return i > 0 && i < s.length() - 1 && s.indexOf('/', i + 1) < 0 && !s.contains(" ")
                && s.matches("[A-Za-z0-9_.\\-]+/[A-Za-z0-9_.\\-]+");
    }

    public static String installedName(Context c) {
        try {
            PackageInfo p = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return p.versionName == null ? "" : p.versionName;
        } catch (Exception e) {
            return "";
        }
    }

    @SuppressWarnings("deprecation")
    public static long installedCode(Context c) {
        try {
            PackageInfo p = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ version comparison

    static long[] parseVersion(String v) {
        if (v == null) return new long[0];
        Matcher m = Pattern.compile("\\d+").matcher(v);
        long[] tmp = new long[16];
        int n = 0;
        while (m.find() && n < tmp.length) {
            try {
                tmp[n++] = Long.parseLong(m.group());
            } catch (NumberFormatException e) {
                tmp[n++] = 0;
            }
        }
        long[] out = new long[n];
        System.arraycopy(tmp, 0, out, 0, n);
        return out;
    }

    static int compareVersions(String a, String b) {
        long[] x = parseVersion(a);
        long[] y = parseVersion(b);
        int n = Math.max(x.length, y.length);
        for (int i = 0; i < n; i++) {
            long p = i < x.length ? x[i] : 0;
            long q = i < y.length ? y[i] : 0;
            if (p != q) return p > q ? 1 : -1;
        }
        return 0;
    }

    // ------------------------------------------------------------------ http

    private static HttpURLConnection open(String url, String accept) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "SemoNotes-Updater");
        c.setRequestProperty("Accept", accept);
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        return c;
    }

    private static String readAll(InputStream in) throws IOException {
        try (InputStream i = in) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = i.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    private static String find(String text, String regex) {
        Matcher m = Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(text);
        return m.find() ? m.group(1) : "";
    }

    // ------------------------------------------------------------------ release lookup

    private static JSONObject pickApk(JSONArray assets) {
        if (assets == null) return null;
        JSONObject fallback = null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.optJSONObject(i);
            if (a == null) continue;
            String n = a.optString("name").toLowerCase(Locale.US);
            if (!n.endsWith(".apk") || !"uploaded".equals(a.optString("state", "uploaded"))) continue;
            if (n.contains("debug")) {
                if (fallback == null) fallback = a;
                continue;
            }
            return a;
        }
        return fallback;
    }

    /**
     * Newest release that carries an APK ({@link Info#newer} says whether it beats the installed app),
     * or null when the repository has no installable release.
     */
    public static Info check(Context c, String slug, boolean includePre) throws UpdateException {
        if (!validSlug(slug)) throw new UpdateException("NOT_FOUND", 404, "bad repo");
        HttpURLConnection con = null;
        try {
            con = open("https://api.github.com/repos/" + slug + "/releases?per_page=15",
                    "application/vnd.github+json");
            int code = con.getResponseCode();
            if (code == 404) throw new UpdateException("NOT_FOUND", code, "not found");
            if (code == 403 || code == 429) throw new UpdateException("RATE_LIMIT", code, "rate limit");
            if (code >= 400) throw new UpdateException("OTHER", code, "HTTP " + code);
            JSONArray rels = new JSONArray(readAll(con.getInputStream()));
            for (int i = 0; i < rels.length(); i++) {
                JSONObject r = rels.getJSONObject(i);
                if (r.optBoolean("draft")) continue;
                if (r.optBoolean("prerelease") && !includePre) continue;
                JSONObject apk = pickApk(r.optJSONArray("assets"));
                if (apk == null) continue;
                Info in = new Info();
                in.tag = r.optString("tag_name");
                in.name = r.optString("name");
                in.body = r.isNull("body") ? "" : r.optString("body");
                in.htmlUrl = r.optString("html_url");
                in.publishedAt = r.isNull("published_at") ? r.optString("created_at") : r.optString("published_at");
                in.prerelease = r.optBoolean("prerelease");
                in.assetUrl = apk.optString("browser_download_url");
                // only ever download from this repository's own release assets, over https
                String expect = ("https://github.com/" + slug + "/releases/download/").toLowerCase(Locale.US);
                if (!in.assetUrl.toLowerCase(Locale.US).startsWith(expect)) continue;
                in.assetName = apk.optString("name");
                in.assetSize = apk.optLong("size");
                String vc = find(in.body, "versionCode\\s*[:=]\\s*(\\d+)");
                if (!vc.isEmpty()) {
                    try {
                        in.versionCode = Long.parseLong(vc);
                    } catch (NumberFormatException ignored) {
                    }
                }
                // the workflow writes "SHA-256 (APK): <64 hex>"; the certificate fingerprint line is ignored
                in.sha256 = find(in.body, "SHA-?256\\s*\\(APK\\)[^0-9a-fA-F]{0,12}([0-9a-fA-F]{64})")
                        .toLowerCase(Locale.US);
                if (in.versionCode > 0) in.newer = in.versionCode > installedCode(c);
                else in.newer = compareVersions(in.tag, installedName(c)) > 0;
                return in;
            }
            return null;
        } catch (UpdateException e) {
            throw e;
        } catch (IOException e) {
            throw new UpdateException("NETWORK", 0, String.valueOf(e.getMessage()));
        } catch (Exception e) {
            throw new UpdateException("OTHER", 0, String.valueOf(e.getMessage()));
        } finally {
            if (con != null) con.disconnect();
        }
    }

    /** Release notes without the machine-readable footer lines added by the build workflow. */
    public static String cleanNotes(String body) {
        if (body == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String line : body.split("\r?\n")) {
            String t = line.trim().toLowerCase(Locale.US);
            if (t.contains("versioncode:") || t.contains("sha-256") || t.contains("sha256")
                    || t.startsWith("- **\u0627\u0644\u062a\u0648\u0642\u064a\u0639")) continue;
            sb.append(line).append('\n');
        }
        String s = sb.toString().trim();
        return s.length() > 1000 ? s.substring(0, 1000) + "\u2026" : s;
    }

    // ------------------------------------------------------------------ download + verify

    private static String sha256Hex(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format(Locale.US, "%02x", b & 0xff));
        return sb.toString();
    }

    /** Removes leftovers of an earlier download (called at app start). */
    public static void cleanup(Context c) {
        try {
            File[] f = new File(c.getCacheDir(), "apk").listFiles();
            if (f != null) for (File x : f) if (System.currentTimeMillis() - x.lastModified() > 3600_000L) x.delete();
        } catch (Exception ignored) {
        }
    }

    public static File apkFile(Context c) {
        File dir = new File(c.getCacheDir(), "apk");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, "update.apk");
    }

    /** Downloads and verifies the APK (size, SHA-256 when published, same signing key). Blocking. */
    public static File download(Context c, Info in, Progress cb) throws UpdateException {
        File apk = apkFile(c);
        File[] old = apk.getParentFile().listFiles();
        if (old != null) for (File o : old) o.delete();
        HttpURLConnection con = null;
        try {
            con = open(in.assetUrl, "application/octet-stream");
            int code = con.getResponseCode();
            if (code >= 400) throw new UpdateException(code == 404 ? "NOT_FOUND" : "OTHER", code, "HTTP " + code);
            long total = con.getContentLengthLong();
            if (total <= 0) total = in.assetSize;
            try (InputStream is = con.getInputStream(); OutputStream os = new FileOutputStream(apk)) {
                byte[] buf = new byte[64 * 1024];
                long done = 0, lastReport = 0;
                int n;
                while ((n = is.read(buf)) > 0) {
                    os.write(buf, 0, n);
                    done += n;
                    if (cb != null && done - lastReport > 128 * 1024) {
                        lastReport = done;
                        cb.onBytes(done, total);
                    }
                }
                if (cb != null) cb.onBytes(done, total);
            }
            if (in.assetSize > 0 && apk.length() != in.assetSize) {
                apk.delete();
                throw new UpdateException("SIZE", 0, "size");
            }
            if (!in.sha256.isEmpty() && !sha256Hex(apk).equalsIgnoreCase(in.sha256)) {
                apk.delete();
                throw new UpdateException("HASH", 0, "hash");
            }
            String bad = signerProblem(c, apk);
            if (bad != null) {
                apk.delete();
                throw new UpdateException(bad, 0, bad);
            }
            return apk;
        } catch (UpdateException e) {
            throw e;
        } catch (IOException e) {
            apk.delete();
            throw new UpdateException("NETWORK", 0, String.valueOf(e.getMessage()));
        } catch (Exception e) {
            apk.delete();
            throw new UpdateException("OTHER", 0, String.valueOf(e.getMessage()));
        } finally {
            if (con != null) con.disconnect();
        }
    }

    /** null when the APK is this app signed with the installed key; otherwise "PACKAGE" or "SIGNATURE". */
    @SuppressWarnings("deprecation")
    private static String signerProblem(Context c, File apk) {
        try {
            PackageManager pm = c.getPackageManager();
            int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES
                    : PackageManager.GET_SIGNATURES;
            PackageInfo archive = pm.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
            if (archive == null) return "PACKAGE";
            if (!c.getPackageName().equals(archive.packageName)) return "PACKAGE";
            PackageInfo installed = pm.getPackageInfo(c.getPackageName(), flags);
            long newCode = Build.VERSION.SDK_INT >= 28 ? archive.getLongVersionCode() : archive.versionCode;
            long curCode = Build.VERSION.SDK_INT >= 28 ? installed.getLongVersionCode() : installed.versionCode;
            if (newCode <= curCode) return "DOWNGRADE"; // never install an older or identical build
            Set<String> a = certs(archive);
            Set<String> b = certs(installed);
            if (a.isEmpty() || b.isEmpty()) {
                // Android 7-8 cannot always read a package's certificates; the system installer still enforces them
                return Build.VERSION.SDK_INT < 28 ? null : "SIGNATURE";
            }
            for (String x : a) if (b.contains(x)) return null;
            return "SIGNATURE";
        } catch (Exception e) {
            return "SIGNATURE"; // cannot verify -> do not install
        }
    }

    @SuppressWarnings("deprecation")
    private static Set<String> certs(PackageInfo p) {
        Set<String> out = new HashSet<>();
        Signature[] sigs = null;
        if (Build.VERSION.SDK_INT >= 28) {
            SigningInfo si = p.signingInfo;
            if (si != null) sigs = si.hasMultipleSigners() ? si.getApkContentsSigners() : si.getSigningCertificateHistory();
        } else {
            sigs = p.signatures;
        }
        if (sigs != null) for (Signature s : sigs) out.add(s.toCharsString());
        return out;
    }

    // ------------------------------------------------------------------ install

    /** Starts the system installer. Returns false when the "install unknown apps" permission is missing. */
    public static boolean install(Activity a, File apk) {
        if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(a, R.string.upd_allow_install, Toast.LENGTH_LONG).show();
            a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + a.getPackageName())));
            return false;
        }
        Uri uri = FileProvider.getUriForFile(a, a.getPackageName() + ".files", apk);
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        a.startActivity(i);
        return true;
    }

    /** Friendly text for a failure. */
    public static String message(Context c, UpdateException e, String slug) {
        switch (e.kind) {
            case "NETWORK":
                return c.getString(R.string.err_network);
            case "NOT_FOUND":
                return c.getString(R.string.upd_repo_404, slug);
            case "RATE_LIMIT":
                return c.getString(R.string.upd_rate_limit);
            case "SIZE":
                return c.getString(R.string.upd_size_bad);
            case "HASH":
                return c.getString(R.string.upd_hash_bad);
            case "SIGNATURE":
                return c.getString(R.string.upd_sig_bad);
            case "PACKAGE":
                return c.getString(R.string.upd_pkg_bad);
            case "DOWNGRADE":
                return c.getString(R.string.upd_downgrade);
            default:
                return c.getString(R.string.upd_error, String.valueOf(e.getMessage()));
        }
    }

    // ------------------------------------------------------------------ silent check on launch

    /** At most every few hours: checks quietly and offers the update in a dialog when there is one. */
    public static void autoCheck(final Activity a) {
        if (!Prefs.autoUpdate(a)) return;
        final long now = System.currentTimeMillis();
        if (now - Prefs.lastUpdateCheck(a) < AUTO_INTERVAL_MS) return;
        final String slug = repo(a);
        if (!validSlug(slug)) return;
        final boolean pre = Prefs.updatePre(a);
        final Context app = a.getApplicationContext();
        EX.execute(() -> {
            try {
                Info in = check(app, slug, pre);
                Prefs.setLastUpdateCheck(app, now);
                if (in != null && in.newer && !in.tag.equals(Prefs.skippedVersion(app))) {
                    MAIN.post(() -> {
                        if (!a.isFinishing() && !a.isDestroyed()) prompt(a, in);
                    });
                }
            } catch (Exception ignored) {
                // offline / private repo: stay silent, the manual check explains the problem
            }
        });
    }

    private static void prompt(final Activity a, final Info in) {
        String msg = a.getString(R.string.upd_prompt_msg, in.tag, installedName(a));
        String notes = cleanNotes(in.body);
        if (!notes.isEmpty()) msg = msg + "\n\n" + notes;
        new Dlg(a)
                .setTitle(R.string.upd_available)
                .setMessage(msg)
                .setPositiveButton(R.string.upd_update_now, (d, w) ->
                        a.startActivity(new Intent(a, UpdateActivity.class).putExtra(UpdateActivity.EXTRA_AUTOSTART, true)))
                .setNeutralButton(R.string.upd_skip_version, (d, w) -> Prefs.setSkippedVersion(a, in.tag))
                .setNegativeButton(R.string.upd_later, null)
                .show();
    }
}
