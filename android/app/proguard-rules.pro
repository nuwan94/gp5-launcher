# GP5 Launcher: R8 rules. Hilt, Compose and DataStore ship their own consumer rules.

# Filament / gltfio / filament-utils: the JNI layer looks up these classes and members by name
-keep class com.google.android.filament.** { *; }
