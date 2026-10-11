# Aturan ProGuard untuk Uda Downloader (com.udadownloader.app)
-keepattributes *Annotation*,SourceFile,LineNumberTable

# Pertahankan entitas Room Database dan TypeConverters
-keep class com.example.data.** { *; }
-keep class * extends androidx.room.RoomDatabase {
    void <init>();
}

# OkHttp & Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
