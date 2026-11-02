package com.semo.notes;

/** The Firestore security rules (same text as firebase/firestore.rules) so they can be copied from Settings. */
public final class Rules {
    private Rules() {
    }

    public static final String FIRESTORE =
            "rules_version = '2';\n" +
            "\n" +
            "// Notes app - Cloud Firestore security rules.\n" +
            "// Every note lives at  users/{uid}/notes/{noteId}. Only the signed-in owner (uid == {uid}), signed in\n" +
            "// with Google and with a verified e-mail, can touch it. Everything else in the database is closed.\n" +
            "service cloud.firestore {\n" +
            "  match /databases/{database}/documents {\n" +
            "\n" +
            "    // The caller is the owner of this user path and came in through Google sign-in.\n" +
            "    function isOwner(uid) {\n" +
            "      return request.auth != null\n" +
            "          && request.auth.uid == uid\n" +
            "          && request.auth.token.firebase.sign_in_provider == 'google.com'\n" +
            "          && request.auth.token.email_verified == true;\n" +
            "    }\n" +
            "\n" +
            "    // A timestamp in epoch milliseconds that is not further than one day ahead of the server clock.\n" +
            "    function sane(ms) {\n" +
            "      return ms is int && ms > 0 && ms <= request.time.toMillis() + 86400000;\n" +
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
            "          && sane(d.createdAt)\n" +
            "          && sane(d.updatedAt) && d.updatedAt >= d.createdAt\n" +
            "          // a permanently deleted note is an empty tombstone: no content can hide in it\n" +
            "          && (d.purged == false || (d.title == '' && d.body == '' && d.pinned == false))\n" +
            "          && d.syncedAt == request.time;   // server time, set by the app with a REQUEST_TIME transform\n" +
            "    }\n" +
            "\n" +
            "    match /users/{uid}/notes/{noteId} {\n" +
            "      allow get: if isOwner(uid);\n" +
            "\n" +
            "      // listing is allowed only with an explicit page size, so one request can never pull an unbounded result\n" +
            "      allow list: if isOwner(uid)\n" +
            "                  && request.query.limit <= 500;\n" +
            "\n" +
            "      allow create: if isOwner(uid)\n" +
            "                    && noteId.matches('[A-Za-z0-9_-]{1,64}')\n" +
            "                    && validNote(request.resource.data);\n" +
            "\n" +
            "      // creation time can never be rewritten, a change must move updatedAt forward,\n" +
            "      // and a tombstone can not be turned back into a live note\n" +
            "      allow update: if isOwner(uid)\n" +
            "                    && validNote(request.resource.data)\n" +
            "                    && request.resource.data.createdAt == resource.data.createdAt\n" +
            "                    && request.resource.data.updatedAt >= resource.data.updatedAt\n" +
            "                    && (resource.data.purged == false || request.resource.data.purged == true);\n" +
            "\n" +
            "      // only used by \"Delete all my data\"; normal deletes are written as empty \"purged\" tombstones\n" +
            "      allow delete: if isOwner(uid);\n" +
            "    }\n" +
            "\n" +
            "    // Anything else: closed (no other collections, no listing of other users).\n" +
            "    match /{document=**} {\n" +
            "      allow read, write: if false;\n" +
            "    }\n" +
            "  }\n" +
            "}\n";
}
