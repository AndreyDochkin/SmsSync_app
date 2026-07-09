# ============================================================
# SmsSync ProGuard / R8 Rules
# Version: 1.2.0
# ============================================================

# Keep our main app classes (entry points for manifest/reflection)
-keep class com.smstotelegram.** { *; }

# OkHttp — keep all for reflection usage by OkHttp itself
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# JSON — keep all for reflection
-keep class org.json.** { *; }
-dontwarn org.json.**

# Kotlin coroutines — keep internal dispatcher factories
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# AndroidX Security (EncryptedSharedPreferences)
-keep class androidx.security.crypto.** { *; }
-dontwarn androidx.security.crypto.**

# WorkManager
-keep class androidx.work.** { *; }
-dontwarn androidx.work.**

# Keep model classes used by JSON serialization
-keep class com.smstotelegram.MessageRetryQueue$QueuedMessage { *; }