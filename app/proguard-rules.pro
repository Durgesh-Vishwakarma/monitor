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

# Keep WebRTC (CRITICAL for native libs)
-keepclassmembers class org.webrtc.** { *; }
-keep class org.webrtc.** { *; }
-keepclasseswithmembers class * { native <methods>; }
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

# Keep all JNI methods
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# Keep Parcelable implementations
-keep class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
