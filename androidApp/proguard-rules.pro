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
-keep,includedescriptorclasses class com.raulshma.grack.**$$serializer { *; }
-keepclassmembers class com.raulshma.grack.** {
    *** Companion;
}
-keepclasseswithmembers class com.raulshma.grack.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Room 3 & Glance AppWidget receivers/workers instantiated by Android framework
-keep class com.raulshma.grack.core.database.** { *; }
-keep class com.raulshma.grack.widget.** { *; }
-keep class com.raulshma.grack.worker.** { *; }
-keep class com.raulshma.grack.service.** { *; }
