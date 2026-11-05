# Keep Google API Client and YouTube models
-keep class com.google.api.services.youtube.** { *; }
-keep class com.google.api.client.** { *; }
-dontwarn com.google.api.client.**
-dontwarn com.google.common.**

# Keep FFmpegKit
-keep class com.arthenica.ffmpegkit.** { *; }

# Keep OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
