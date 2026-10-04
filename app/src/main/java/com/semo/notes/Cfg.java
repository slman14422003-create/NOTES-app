package com.semo.notes;

/**
 * Firebase web configuration (Project settings -> General -> Your apps).
 * The app talks to Firebase through its public REST APIs, so no google-services.json and no
 * Firebase SDK are needed. These values are identifiers, not secrets: what protects the data is
 * the Firestore security rules (firebase/firestore.rules) and the signed-in user.
 */
public final class Cfg {
    private Cfg() {
    }

    public static final String API_KEY = "AIzaSyCvXS6nZ2md4xRVyUDtPNOMgL5ShUCADXI";
    public static final String PROJECT_ID = "semo-7770a";

    /**
     * OAuth "Web client ID" used for Google sign-in (Firebase Console -> Authentication -> Sign-in method
     * -> Google -> Web SDK configuration). Either paste it here or pass it to the build as the
     * WEB_CLIENT_ID environment variable / GitHub secret.
     */
    private static final String WEB_CLIENT_ID_FALLBACK = "";

    public static String webClientId() {
        String v = BuildConfig.WEB_CLIENT_ID;
        return v != null && !v.trim().isEmpty() ? v.trim() : WEB_CLIENT_ID_FALLBACK;
    }

    static final String AUTH = "https://identitytoolkit.googleapis.com/v1/accounts:";
    static final String TOKEN = "https://securetoken.googleapis.com/v1/token";
    static final String DOC_ROOT = "projects/" + PROJECT_ID + "/databases/(default)/documents";
    static final String FS = "https://firestore.googleapis.com/v1/" + DOC_ROOT;
}
