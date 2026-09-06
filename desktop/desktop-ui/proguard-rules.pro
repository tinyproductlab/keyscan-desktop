# Optional platform integrations referenced by Compose/JNA/Guava are absent from the
# Windows desktop runtime. They are guarded by the libraries themselves.
-dontwarn java.lang.invoke.**
-dontwarn android.os.**
-dontwarn com.google.appengine.**
-dontwarn com.google.apphosting.**
-dontwarn com.jetbrains.SharedTextures
