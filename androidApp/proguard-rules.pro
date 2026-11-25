# Preserve line numbers for readable crash stack traces
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# JSch (mwiede) resolves cipher, kex, MAC, signature, and auth classes via reflection
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**
-dontwarn com.sun.jna.**
-dontwarn org.bouncycastle.**
-dontwarn org.slf4j.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.ietf.jgss.**
-dontwarn java.lang.foreign.**
-dontwarn java.lang.invoke.**

# Keep Kotlinx Serialization descriptors / serializers across KMP modules
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keep,includedescriptorclasses class com.example.glassrack.**$$serializer { *; }
-keepclassmembers class com.example.glassrack.** {
    *** Companion;
}
-keepclasseswithmembers class com.example.glassrack.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Room 3 & Glance AppWidget receivers/workers instantiated by Android framework
-keep class com.example.glassrack.core.database.** { *; }
-keep class com.example.glassrack.widget.** { *; }
-keep class com.example.glassrack.worker.** { *; }
-keep class com.example.glassrack.service.** { *; }
