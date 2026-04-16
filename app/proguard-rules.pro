# Add project specific ProGuard rules here.
# Keep BouncyCastle
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# Keep Room entities
-keep class com.lockit.data.database.** { *; }

# Keep Kotlin serialization
-keepattributes *Annotation*, Signature, InnerClasses
-keep class * implements kotlinx.serialization.KSerializer
