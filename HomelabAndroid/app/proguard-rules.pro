# Hilt, Room, Retrofit, OkHttp and kotlinx.serialization ship their own consumer
# rules, so R8 already keeps their reflective entry points. These rules cover what
# the app itself relies on reflectively.

# Keep class names: stored settings, backups and serializers resolve types by name.
-dontobfuscate

# kotlinx.serialization looks up generated serializers by name.
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class com.homelab.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class com.homelab.app.** {
    *** Companion;
}

# Enum constants are read back by name from preferences and backup files.
-keepclassmembers enum com.homelab.app.** { *; }
