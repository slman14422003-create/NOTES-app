package com.semo.notes;

/** The Firestore security rules (same text as firebase/firestore.rules) so they can be copied from Settings. */
public final class Rules {
    private Rules() {
    }

    public static final String FIRESTORE =
            "rules_version = '2';\n" +
            "\n" +
            "// Notes app - Cloud Firestore security rules.\n" +
            "// Every note lives at  users/{uid}/notes/{noteId}  and can only be read or written by the signed-in\n" +
            "// user whose uid matches {uid}. Everything else in the database is closed.\n" +
            "service cloud.firestore {\n" +
            "  match /databases/{database}/documents {\n" +
            "\n" +
            "    function isOwner(uid) {\n" +
            "      return request.auth != null && request.auth.uid == uid;\n" +
            "    }\n" +
            "\n" +
            "    // Shape of a note document: exactly these fields, with these types and sizes.\n" +
            "    function validNote(d) {\n" +
            "      return d.keys().hasOnly(['title', 'body', 'kind', 'color', 'pinned', 'archived', 'trashed',\n" +
            "                               'purged', 'createdAt', 'updatedAt', 'syncedAt'])\n" +
            "          && d.keys().hasAll(['title', 'body', 'kind', 'color', 'pinned', 'archived', 'trashed',\n" +
            "                              'purged', 'createdAt', 'updatedAt', 'syncedAt'])\n" +
            "          && d.title is string && d.title.size() <= 500\n" +
            "          && d.body is string && d.body.size() <= 200000\n" +
            "          && d.kind is int && d.kind >= 0 && d.kind <= 1\n" +
            "          && d.color is int && d.color >= 0 && d.color <= 20\n" +
            "          && d.pinned is bool && d.archived is bool && d.trashed is bool && d.purged is bool\n" +
            "          && d.createdAt is int && d.updatedAt is int\n" +
            "          && d.syncedAt == request.time;   // server time, set by the app with a REQUEST_TIME transform\n" +
            "    }\n" +
            "\n" +
            "    match /users/{uid}/notes/{noteId} {\n" +
            "      allow read: if isOwner(uid);\n" +
            "      allow create, update: if isOwner(uid) && noteId.size() <= 64 && validNote(request.resource.data);\n" +
            "      allow delete: if isOwner(uid);\n" +
            "    }\n" +
            "\n" +
            "    // Anything else: closed.\n" +
            "    match /{document=**} {\n" +
            "      allow read, write: if false;\n" +
            "    }\n" +
            "  }\n" +
            "}\n";
}
