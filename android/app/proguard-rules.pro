# Room, CameraX, Coil and the AndroidX libraries all ship their own consumer rules, so this
# file only needs what is specific to this app.
#
# Deliberately not here: a blanket -keep on the data package. Room resolves entities and
# columns at compile time, not by reflection, so obfuscating them is safe — and keeping them
# would only make the APK larger while hiding real problems.

# Keep the crash stack traces readable enough to act on.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
