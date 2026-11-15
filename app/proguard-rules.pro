# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class com.forbiddenstreams.vod.** {
    *** Companion;
}
-keepclasseswithmembers class com.forbiddenstreams.vod.** {
    kotlinx.serialization.KSerializer serializer(...);
}
