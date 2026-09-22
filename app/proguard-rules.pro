# kotlinx.serialization keeps generated serializers off the shrink list.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class com.hostu404.trilliontracker.data.** {
    *** Companion;
}
-keepclasseswithmembers class com.hostu404.trilliontracker.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}
