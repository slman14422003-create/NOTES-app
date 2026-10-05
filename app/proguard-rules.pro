# Keep readable stack traces in crash reports (mapping.txt is uploaded by the workflow)
# line numbers are kept for mapping.txt only; the original file names are replaced
-keepattributes LineNumberTable
-renamesourcefileattribute x

# Hardening: remove all debug/verbose logging and flatten package names
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
-repackageclasses ''
-allowaccessmodification

# Hardening (build-time obfuscation): rename everything, no source file names, drop the remaining debug logs
-assumenosideeffects class android.util.Log {
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
}
-dontnote **
