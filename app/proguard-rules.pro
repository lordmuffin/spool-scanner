# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.lifecycle.HiltViewModel

# kotlinx.serialization DTOs (Spoolman / labelito)
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions
-keep,includedescriptorclasses class com.bambu.nfc.**$$serializer { *; }
-keepclassmembers class com.bambu.nfc.** {
    *** Companion;
}
-keepclasseswithmembers class com.bambu.nfc.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Retrofit interfaces are proxied at runtime
-keep,allowobfuscation interface com.bambu.nfc.data.spoolman.SpoolmanApi
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Tink (via androidx.security:security-crypto) references compile-only errorprone annotations
-dontwarn com.google.errorprone.annotations.**
