# TGPix Proguard Rules

# 1. Keep JNI Native Bindings for TDLib (Critical)
-keep class org.drinkless.tdlib.** { *; }
-dontwarn org.drinkless.tdlib.**

# 2. Keep Room Entities and DAOs
-keep class androidx.room.** { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
-dontwarn androidx.room.introspection.**

# 3. Keep ML Kit and Google Play Services Code Scanner
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.ml.** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.internal.ml.**

# 4. Keep Kotlin Coroutines and StateFlow
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# 5. Keep Serialized / Model data classes (for db and JSON)
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class dev.ssjvirtually.tgpix.storage.** { *; }
-keep class dev.ssjvirtually.tgpix.telegram.** { *; }
-keep class dev.ssjvirtually.tgpix.ui.screens.SearchItem { *; }
-keep class dev.ssjvirtually.tgpix.ui.screens.FolderInfo { *; }

# 6. Sentry Exception tracking keep rules
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod
-dontwarn io.sentry.**
-keep class io.sentry.** { *; }
