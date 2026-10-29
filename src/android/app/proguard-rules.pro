-keepattributes Signature,InnerClasses,EnclosingMethod

# Shizuku constructs the user service in a separate shell process by class name.
-keep class dev.smartphonelink.android.features.clipboard.ClipboardShizukuService { *; }
-keep class dev.smartphonelink.android.features.clipboard.IClipboardShizuku$Stub { *; }

# WebRTC exposes Java classes and callbacks to native code by name.
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# OkHttp and its TLS platform adapters include optional runtime integrations.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
