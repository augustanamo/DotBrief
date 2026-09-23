# 保持 kotlinx.serialization 生成的序列化器
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.briefwidget.**$$serializer { *; }
-keepclasseswithmembers class com.briefwidget.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Retrofit / OkHttp
-dontwarn okhttp3.**
-dontwarn retrofit2.**
-keepattributes Signature, Exceptions

# Room
-keep class * extends androidx.room.RoomDatabase { <init>(); }
