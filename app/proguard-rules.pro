# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class dev.vaayil.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class dev.vaayil.**$$serializer { *; }

# wireguard-android JNI
-keep class com.wireguard.android.backend.** { *; }
-keep class com.wireguard.crypto.** { *; }

# protobuf-javalite (pulled in by androidx.datastore): R8 full mode renames the
# generated message fields the schema addresses by name -> "Field ... not found"
# crash at first DataStore read (Application start).
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**

# androidx.datastore's generated protobuf-lite messages: protobuf resolves fields
# BY NAME from the embedded schema — R8 renaming them (full mode) throws
# "Field preferences_ ... not found" at the first DataStore read (app start).
-keepclassmembernames class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }
-keep class androidx.datastore.preferences.core.** { *; }

# datastore-preferences-proto RELOCATES protobuf into androidx.datastore.preferences.protobuf;
# the schema resolves generated message fields BY NAME, so renaming them (R8 full mode)
# throws "Field preferences_ ... not found" at the first DataStore read.
-keep class androidx.datastore.preferences.** { *; }
