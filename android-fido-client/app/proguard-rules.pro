# Add project specific ProGuard rules here.
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class com.fidobridge.client.** {
    *** Companion;
}
-keepclasseswithmembers class com.fidobridge.client.** {
    kotlinx.serialization.KSerializer serializer(...);
}