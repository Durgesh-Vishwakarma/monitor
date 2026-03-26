# Add project specific ProGuard rules here.

# Keep OkHttp
-keepattributes Signature
-keepattributes *Annotation*
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# Keep app classes
-keep class com.micmonitor.app.** { *; }

# Keep WebRTC
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# Keep Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.**

# Keep JSON parsing
-keep class org.json.** { *; }

# Keep Android settings provider
-keep class android.provider.Settings { *; }
-keep class android.provider.Settings$Secure { *; }
