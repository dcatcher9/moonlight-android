# Don't obfuscate code
-dontobfuscate

# Our code
-keep class com.limelight.binding.input.evdev.* {*;}

# KeyMapper - keep all VK_* fields for reflection
-keep class com.limelight.utils.KeyMapper {*;}

# KeyConfigHelper - keep classes and fields for Gson
-keep class com.limelight.utils.KeyConfigHelper {*;}
-keep class com.limelight.utils.KeyConfigHelper$ShortcutFile {*;}
-keep class com.limelight.utils.KeyConfigHelper$Shortcut {*;}

# Keep TensorFlow Lite GPU delegate classes that R8 might incorrectly remove
-keep class org.tensorflow.lite.gpu.** { *; }
-keep class org.opencv.** { *; }

# Profiles
-keep class com.limelight.profiles.ProfilesManager$ProfilesData {*;}
-keep class com.limelight.profiles.SettingsProfile {*;}

# Moonlight common
-keep class com.limelight.nvstream.jni.* {*;}

# Okio
-keep class sun.misc.Unsafe {*;}
-dontwarn java.nio.file.*
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn okio.**

# BouncyCastle
-keep class org.bouncycastle.jcajce.provider.asymmetric.* {*;}
-keep class org.bouncycastle.jcajce.provider.asymmetric.util.* {*;}
-keep class org.bouncycastle.jcajce.provider.asymmetric.rsa.* {*;}
-keep class org.bouncycastle.jcajce.provider.digest.** {*;}
-keep class org.bouncycastle.jcajce.provider.symmetric.** {*;}
-keep class org.bouncycastle.jcajce.spec.* {*;}
-keep class org.bouncycastle.jce.** {*;}
-dontwarn javax.naming.**

# jMDNS
-dontwarn javax.jmdns.impl.DNSCache
-dontwarn org.slf4j.**

# MPAndroidChart
-keep class com.github.mikephil.charting.** { *; }
-dontwarn com.github.mikephil.charting.**
# Android XR (Jetpack XR / SceneCore)
# The com.android.extensions.xr.* classes are part of the Android XR platform
# extensions, provided by the device's XR system image at runtime, so they are
# not present on the build classpath. They are only ever invoked on XR devices.
-dontwarn com.android.extensions.xr.**
# The Jetpack XR libraries rely heavily on JNI callbacks, ServiceLoader/provider lookups,
# and reflective class resolution that R8 cannot trace statically. With minification on,
# R8 strips members/classes that are only reached through those paths, which surfaces at
# runtime as a cascade of failures when an XR Session is created / a stream starts:
#   - JNI NoSuchMethodError: arcore.openxr's native lib does NewObject on
#     ViewCameraState(Pose, FieldOfView) (its ctor was tree-shaken).
#   - NoClassDefFoundError: scenecore's SpatialCoreXrExtensionsHolderProvider resolves
#     androidx.xr.runtime.XrExtensionsHolder via a provider (the class was tree-shaken).
# Since this is an XR-only app where these libraries are central, keep the whole tree.
-keep class androidx.xr.** { *; }
-keep interface androidx.xr.** { *; }
