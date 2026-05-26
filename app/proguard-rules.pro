# ============================================
# OpenDroid — Production ProGuard/R8 Rules
# ============================================

# ── App classes ──
-keep class com.opendroid.ai.core.llm.** { *; }
-keep class com.opendroid.ai.data.models.** { *; }
-keep class com.opendroid.ai.data.db.entities.** { *; }
-keep class com.opendroid.ai.data.db.dao.** { *; }
-keep class com.opendroid.ai.accessibility.** { *; }

# Keep action schema (used by reflection/serialization)
-keep class com.opendroid.ai.actions.** { *; }

# ── Room Database ──
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao class *
-dontwarn androidx.room.paging.**

# ── Hilt / Dagger ──
-dontwarn com.google.dagger.hilt.processor.**
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }

# ── Retrofit + OkHttp ──
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepattributes Signature, InnerClasses, EnclosingMethod, AnnotationDefault
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-keep class okhttp3.** { *; }

# ── Gson ──
-keepattributes *Annotation*
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ── Kotlin Serialization ──
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    *** serializer(...);
}
-keep,includedescriptorclasses class com.opendroid.ai.**$$serializer { *; }

# ── Kotlin Coroutines ──
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# ── Compose ──
-dontwarn androidx.compose.**
-keep class androidx.compose.** { *; }

# ── Coil ──
-dontwarn coil.**

# ── Lottie ──
-dontwarn com.airbnb.lottie.**
-keep class com.airbnb.lottie.** { *; }

# ── Security Crypto ──
-keep class androidx.security.crypto.** { *; }

# ── General ──
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.accessibilityservice.AccessibilityService

# R8 fix for missing annotations in dependencies (e.g. Tink)
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi

# ── Accompanist ──
-dontwarn com.google.accompanist.**
-keep class com.google.accompanist.** { *; }

# ── Strip debug/verbose logs in release builds ──
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}
