package com.semo.notes;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal Firebase client over REST: Authentication (email/password + anonymous) and Cloud Firestore.
 * Notes live in  users/{uid}/notes/{noteId}  - the same path the security rules protect.
 */
public final class Firebase {
    private Firebase() {
    }

    public static final class FbException extends Exception {
        /** Firebase error code such as EMAIL_EXISTS, or PERMISSION_DENIED, or NETWORK. */
        public final String code;
        public final int http;

        FbException(String code, int http, String message) {
            super(message);
            this.code = code;
            this.http = http;
        }
    }

    private static final class Resp {
        int code;
        String text;
    }

    // ------------------------------------------------------------------ http

    private static Resp call(String method, String url, String body, String contentType, String bearer)
            throws FbException {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestMethod(method);
            c.setRequestProperty("Accept", "application/json");
            if (bearer != null) c.setRequestProperty("Authorization", "Bearer " + bearer);
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", contentType);
                byte[] data = body.getBytes(StandardCharsets.UTF_8);
                c.setFixedLengthStreamingMode(data.length);
                try (OutputStream o = c.getOutputStream()) {
                    o.write(data);
                }
            }
            Resp r = new Resp();
            r.code = c.getResponseCode();
            InputStream in = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
            r.text = in == null ? "" : readAll(in);
            return r;
        } catch (IOException e) {
            throw new FbException("NETWORK", 0, e.getMessage() == null ? "network" : e.getMessage());
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        try (InputStream i = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = i.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    private static FbException error(Resp r) {
        String code = "HTTP_" + r.code;
        String msg = r.text;
        try {
            JSONObject e = new JSONObject(r.text).optJSONObject("error");
            if (e != null) {
                String m = e.optString("message", "");
                String status = e.optString("status", "");
                String token = m.isEmpty() ? "" : m.split("[ :]")[0];
                if (token.matches("[A-Z_]{4,}")) code = token;   // Auth: EMAIL_EXISTS, INVALID_PASSWORD...
                else if (!status.isEmpty()) code = status;      // Firestore: PERMISSION_DENIED, UNAUTHENTICATED...
                if (!m.isEmpty()) msg = m;
            }
        } catch (JSONException ignored) {
        }
        return new FbException(code, r.code, msg);
    }

    private static JSONObject json(Resp r) throws FbException {
        if (r.code >= 400) throw error(r);
        try {
            return new JSONObject(r.text.isEmpty() ? "{}" : r.text);
        } catch (JSONException e) {
            throw new FbException("BAD_RESPONSE", r.code, e.getMessage());
        }
    }

    /** A short localized explanation for a Firebase error code. */
    public static String friendly(Context c, String code) {
        if (code == null) code = "";
        switch (code) {
            case "EMAIL_EXISTS":
                return c.getString(R.string.err_email_exists);
            case "INVALID_LOGIN_CREDENTIALS":
            case "INVALID_PASSWORD":
            case "EMAIL_NOT_FOUND":
                return c.getString(R.string.err_bad_login);
            case "WEAK_PASSWORD":
                return c.getString(R.string.err_weak_pw);
            case "CONFIGURATION_NOT_FOUND":
                return c.getString(R.string.err_config);
            case "INVALID_IDP_RESPONSE":
            case "INVALID_CREDENTIAL":
                return c.getString(R.string.err_google_config);
            case "OPERATION_NOT_ALLOWED":
            case "ADMIN_ONLY_OPERATION":
                return c.getString(R.string.err_not_allowed);
            case "INVALID_EMAIL":
            case "MISSING_EMAIL":
                return c.getString(R.string.err_invalid_email);
            case "TOO_MANY_ATTEMPTS_TRY_LATER":
                return c.getString(R.string.err_too_many);
            case "NETWORK":
                return c.getString(R.string.err_network);
            case "PERMISSION_DENIED":
                return c.getString(R.string.err_permission);
            case "UNAUTHENTICATED":
            case "NO_SESSION":
            case "TOKEN_EXPIRED":
            case "INVALID_REFRESH_TOKEN":
            case "USER_NOT_FOUND":
            case "USER_DISABLED":
                return c.getString(R.string.err_session);
            default:
                return c.getString(R.string.err_unknown, code);
        }
    }

    // ------------------------------------------------------------------ authentication

    public static final class Session {
        public String uid;
        public String email;
        public boolean anonymous;
        public String idToken;
        public String refreshToken;
        public long expiresIn;
    }

    private static Session authCall(String endpoint, JSONObject body, boolean anonymous) throws FbException {
        Resp r = call("POST", Cfg.AUTH + endpoint + "?key=" + Cfg.API_KEY, body.toString(), "application/json", null);
        JSONObject o = json(r);
        Session s = new Session();
        s.uid = o.optString("localId");
        s.email = o.optString("email", "");
        s.idToken = o.optString("idToken");
        s.refreshToken = o.optString("refreshToken");
        s.expiresIn = parseLong(o.optString("expiresIn", "3600"), 3600);
        s.anonymous = anonymous;
        if (s.uid.isEmpty() || s.idToken.isEmpty()) throw new FbException("BAD_RESPONSE", r.code, "no token");
        return s;
    }

    public static Session signUp(String email, String password) throws FbException {
        try {
            return authCall("signUp", new JSONObject().put("email", email).put("password", password)
                    .put("returnSecureToken", true), false);
        } catch (JSONException e) {
            throw new FbException("BAD_REQUEST", 0, e.getMessage());
        }
    }

    public static Session signIn(String email, String password) throws FbException {
        try {
            return authCall("signInWithPassword", new JSONObject().put("email", email).put("password", password)
                    .put("returnSecureToken", true), false);
        } catch (JSONException e) {
            throw new FbException("BAD_REQUEST", 0, e.getMessage());
        }
    }

    public static Session signInAnonymously() throws FbException {
        try {
            return authCall("signUp", new JSONObject().put("returnSecureToken", true), true);
        } catch (JSONException e) {
            throw new FbException("BAD_REQUEST", 0, e.getMessage());
        }
    }

    /** Exchanges a Google ID token (from Credential Manager) for a Firebase session. */
    public static Session signInWithGoogle(String googleIdToken) throws FbException {
        try {
            return authCall("signInWithIdp", new JSONObject()
                    .put("postBody", "id_token=" + enc(googleIdToken) + "&providerId=google.com")
                    .put("requestUri", "http://localhost")
                    .put("returnIdpCredential", true)
                    .put("returnSecureToken", true), false);
        } catch (JSONException e) {
            throw new FbException("BAD_REQUEST", 0, e.getMessage());
        }
    }

    public static void sendPasswordReset(String email) throws FbException {
        try {
            json(call("POST", Cfg.AUTH + "sendOobCode?key=" + Cfg.API_KEY,
                    new JSONObject().put("requestType", "PASSWORD_RESET").put("email", email).toString(),
                    "application/json", null));
        } catch (JSONException e) {
            throw new FbException("BAD_REQUEST", 0, e.getMessage());
        }
    }

    /** Returns a valid ID token, refreshing it when it is about to expire. */
    public static synchronized String validToken(Context c) throws FbException {
        if (!Prefs.idToken(c).isEmpty() && Prefs.expiresAt(c) - System.currentTimeMillis() > 90_000) {
            return Prefs.idToken(c);
        }
        return refresh(c);
    }

    private static synchronized String refresh(Context c) throws FbException {
        String rt = Prefs.refreshToken(c);
        if (rt.isEmpty()) throw new FbException("NO_SESSION", 401, "no refresh token");
        String form = "grant_type=refresh_token&refresh_token=" + enc(rt);
        Resp r = call("POST", Cfg.TOKEN + "?key=" + Cfg.API_KEY, form, "application/x-www-form-urlencoded", null);
        JSONObject o = json(r);
        String id = o.optString("id_token");
        if (id.isEmpty()) throw new FbException("BAD_RESPONSE", r.code, "no id_token");
        Prefs.updateTokens(c, id, o.optString("refresh_token", rt), parseLong(o.optString("expires_in", "3600"), 3600));
        return id;
    }

    // ------------------------------------------------------------------ firestore

    private static String notesUrl(String uid) {
        return Cfg.FS + "/users/" + enc(uid) + "/notes";
    }

    public static final class Changes {
        public final List<Note> notes = new ArrayList<>();
        /** Server timestamp of the newest document seen (RFC 3339), or "" when nothing came back. */
        public String cursor = "";
    }

    /**
     * Downloads the notes that changed on the server since {@code cursor} (all of them when the
     * cursor is empty). The cursor is the server-side {@code syncedAt} time, so a device with a wrong
     * clock can never make another device miss a change. Re-reading the boundary document is harmless.
     */
    public static Changes listChanged(String token, String uid, String cursor) throws FbException {
        Changes out = new Changes();
        String from = cursor == null ? "" : cursor;
        final int limit = 500;
        while (true) {
            int count = 0;
            String last = "";
            try {
                JSONObject q = new JSONObject()
                        .put("from", new JSONArray().put(new JSONObject().put("collectionId", "notes")))
                        .put("orderBy", new JSONArray().put(new JSONObject()
                                .put("field", new JSONObject().put("fieldPath", "syncedAt"))
                                .put("direction", "ASCENDING")))
                        .put("limit", limit);
                if (!from.isEmpty()) {
                    q.put("where", new JSONObject().put("fieldFilter", new JSONObject()
                            .put("field", new JSONObject().put("fieldPath", "syncedAt"))
                            .put("op", "GREATER_THAN_OR_EQUAL")
                            .put("value", new JSONObject().put("timestampValue", from))));
                }
                Resp r = call("POST", Cfg.FS + "/users/" + enc(uid) + ":runQuery",
                        new JSONObject().put("structuredQuery", q).toString(), "application/json", token);
                if (r.code >= 400) throw error(r);
                JSONArray arr = new JSONArray(r.text.isEmpty() ? "[]" : r.text);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject doc = arr.optJSONObject(i) == null ? null : arr.optJSONObject(i).optJSONObject("document");
                    if (doc == null) continue;
                    Note n = fromDoc(doc);
                    if (n == null) continue;
                    out.notes.add(n);
                    count++;
                    JSONObject f = doc.optJSONObject("fields");
                    JSONObject ts = f == null ? null : f.optJSONObject("syncedAt");
                    if (ts != null && !ts.optString("timestampValue", "").isEmpty()) last = ts.optString("timestampValue");
                }
            } catch (JSONException e) {
                throw new FbException("BAD_RESPONSE", 0, e.getMessage());
            }
            if (!last.isEmpty()) out.cursor = last;
            if (count < limit || last.isEmpty() || last.equals(from)) break;
            from = last;
        }
        return out;
    }

    /** Writes (creates or replaces) up to ~200 notes in one request (the Firestore limit is 500 writes). */
    public static void commit(String token, String uid, List<Note> notes) throws FbException {
        if (notes.isEmpty()) return;
        try {
            JSONArray writes = new JSONArray();
            for (Note n : notes) {
                JSONObject upd = new JSONObject()
                        .put("name", Cfg.DOC_ROOT + "/users/" + uid + "/notes/" + n.id)
                        .put("fields", toFields(n));
                // syncedAt = server time of the write; other devices use it as their pull cursor
                JSONArray tr = new JSONArray().put(new JSONObject()
                        .put("fieldPath", "syncedAt").put("setToServerValue", "REQUEST_TIME"));
                writes.put(new JSONObject().put("update", upd).put("updateTransforms", tr));
            }
            json(call("POST", Cfg.FS + ":commit", new JSONObject().put("writes", writes).toString(),
                    "application/json", token));
        } catch (JSONException e) {
            throw new FbException("BAD_REQUEST", 0, e.getMessage());
        }
    }

    private static JSONObject toFields(Note n) throws JSONException {
        return new JSONObject()
                .put("title", new JSONObject().put("stringValue", n.title))
                .put("body", new JSONObject().put("stringValue", n.body))
                .put("kind", new JSONObject().put("integerValue", String.valueOf(n.kind)))
                .put("color", new JSONObject().put("integerValue", String.valueOf(n.color)))
                .put("pinned", new JSONObject().put("booleanValue", n.pinned))
                .put("archived", new JSONObject().put("booleanValue", n.archived))
                .put("trashed", new JSONObject().put("booleanValue", n.trashed))
                .put("purged", new JSONObject().put("booleanValue", n.purged))
                .put("createdAt", new JSONObject().put("integerValue", String.valueOf(n.createdAt)))
                .put("updatedAt", new JSONObject().put("integerValue", String.valueOf(n.updatedAt)));
    }

    private static Note fromDoc(JSONObject d) {
        if (d == null) return null;
        JSONObject f = d.optJSONObject("fields");
        String name = d.optString("name", "");
        if (f == null || name.isEmpty()) return null;
        Note n = new Note();
        n.id = name.substring(name.lastIndexOf('/') + 1);
        n.title = str(f, "title");
        n.body = str(f, "body");
        n.kind = (int) num(f, "kind");
        n.color = (int) num(f, "color");
        n.pinned = bool(f, "pinned");
        n.archived = bool(f, "archived");
        n.trashed = bool(f, "trashed");
        n.purged = bool(f, "purged");
        n.createdAt = num(f, "createdAt");
        n.updatedAt = num(f, "updatedAt");
        n.dirty = false;
        return n;
    }

    private static String str(JSONObject f, String k) {
        JSONObject v = f.optJSONObject(k);
        return v == null ? "" : v.optString("stringValue", "");
    }

    private static long num(JSONObject f, String k) {
        JSONObject v = f.optJSONObject(k);
        return v == null ? 0 : parseLong(v.optString("integerValue", "0"), 0);
    }

    private static boolean bool(JSONObject f, String k) {
        JSONObject v = f.optJSONObject(k);
        return v != null && v.optBoolean("booleanValue", false);
    }

    private static long parseLong(String s, long def) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return s;
        }
    }
}
