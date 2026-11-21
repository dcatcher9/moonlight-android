# Application.mk for Moonlight

# Matches minSdk 24 in app/build.gradle. Gradle's ndkBuild integration passes
# APP_PLATFORM=android-<minSdk> on the ndk-build command line, which takes precedence.
APP_PLATFORM := android-24

# We support 16KB pages
APP_SUPPORT_FLEXIBLE_PAGE_SIZES := true
