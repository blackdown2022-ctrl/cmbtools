# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class ir.cmbtools.ledger.** { *** Companion; }
-keepclasseswithmembers class ir.cmbtools.ledger.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class ir.cmbtools.ledger.**$$serializer { *; }

# Room entities
-keep class ir.cmbtools.ledger.data.** { *; }

# Retrofit/OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-keepattributes Signature, Exceptions
