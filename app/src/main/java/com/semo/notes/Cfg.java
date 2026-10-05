package com.semo.notes;

/**
 * Firebase configuration. The values are not kept as plain strings in the code: they are generated into
 * {@code Secrets} at build time (scrambled with a per-build key), see app/build.gradle.
 * They are identifiers, not secrets: what protects the data is the Firestore security rules
 * (firebase/firestore.rules), the signed-in Google account and the API-key restrictions
 * (see firebase/SECURITY.md).
 */
public final class Cfg {
    private Cfg() {
    }

    /** Fallback "owner/repo" for in-app updates when the APK was not built by GitHub Actions. */
    public static final String UPDATE_REPO_FALLBACK = "";

    public static String apiKey() {
        return Secrets.apiKey();
    }

    public static String projectId() {
        return Secrets.projectId();
    }

    /** OAuth "Web client ID" for Google sign-in (Firebase Console -> Authentication -> Google). */
    public static String webClientId() {
        return Secrets.webClientId().trim();
    }

    static String auth() {
        return "https://identitytoolkit.googleapis.com/v1/accounts:";
    }

    static String token() {
        return "https://securetoken.googleapis.com/v1/token";
    }

    static String docRoot() {
        return "projects/" + projectId() + "/databases/(default)/documents";
    }

    static String fs() {
        return "https://firestore.googleapis.com/v1/" + docRoot();
    }
}
