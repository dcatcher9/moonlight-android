# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this project is

**Artemis Android** (formerly Moonlight Noir) is an Android game-streaming client
for [Apollo](https://github.com/ClassicOldSong/Apollo) / [Sunshine](https://github.com/LizardByte/Sunshine).
It is a fork of [moonlight-android](https://github.com/moonlight-stream/moonlight-android)
with many extra features. The Java package is `com.limelight` (historical name).

The active branch is `moonlight-noir`. Application IDs: release `com.limelight.noir`,
debug `com.limelight.noirdebug` (release flavor app name "Artemis", debug "Diana").

### Current focus: Android XR (Galaxy XR) SBS support — XR-ONLY build

This fork is being retargeted as an **Android XR-only app**: it is built to run **only on
Android XR devices (Samsung Galaxy XR)**, not phones/tablets/TV. The manifest declares
`<uses-feature android:name="android.software.xr.api.spatial" android:required="true" />`,
which restricts Play Store distribution/install to XR devices. `minSdk = 24` (the Jetpack XR
floor per Google's guidance — do NOT raise to 34).

The goal: display a **side-by-side (SBS) stereo stream produced on the PC** stereoscopically
on the headset. See [docs/android-xr-sbs.md](docs/android-xr-sbs.md) for the design and
implementation plan. Read that document before touching any rendering / surface / stereo code —
and its **"Spatial UI learnings"** section before building any in-headset UI (key rule: put
controls as ordinary clickable `View`s in a *single* `PanelEntity`, like a toolbar, not one panel
per control — that's what gives the gaze highlight and native taps).

Implication: since every device running this build is an XR device, the XR SBS path should be
the **primary** experience. Non-XR code paths (the legacy phone/tablet OSC, the AI-depth
modes) still exist but are no longer the target; don't invest in non-XR regressions.

> Note: there is already an *unrelated* "SBS 3D" feature that uses an on-device AI
> depth model (MiDaS) to synthesize 3D from a flat 2D stream. The XR work is different:
> the PC sends a **real** SBS frame and the XR compositor presents it to each eye.
> Do not confuse the two. See "Existing stereo pipeline" below.

## Build & test

- **Toolchain:** Gradle `9.6.0` (wrapper) + AGP `9.2.0`, run on **JDK 25** (the current
  build JDK). Gradle 9.6 accepts JDK 17–25, so an older 17/21 JDK also works if needed, but
  JDK 25 is the standard here. JDK 26 is too new for Gradle and will be rejected — point
  `JAVA_HOME` at JDK 25.
- Android NDK `27.0.12077973` (declared in `app/build.gradle`); AGP auto-provisions it if
  `cmdline-tools`/`sdkmanager` is available, otherwise install via Android Studio's SDK Manager.
- First checkout: `git submodule update --init --recursive` (pulls `moonlight-common-c`).
- Create `local.properties` with `sdk.dir=<path>` (and `ndk.dir=` if the NDK isn't auto-found).
- Build: `JAVA_HOME=<jdk> ./gradlew :app:assembleNonRoot_gameDebug` (Windows: `gradlew.bat`).
  The Bash tool here runs Git Bash; the `gradlew` shell script works from it.
- Install/run on a connected device: `./gradlew :app:installNonRoot_gameDebug`. The launcher
  activity is `com.limelight.PcView`. (The XR control bar only appears inside an active stream.)
- Unit tests (JVM/Robolectric, no emulator): `./gradlew test` aggregates all `*UnitTest` tasks.
  Per flavor: `:app:testNonRoot_gameDebugUnitTest` / `:app:testRootDebugUnitTest`. Single test:
  `./gradlew :app:testNonRoot_gameDebugUnitTest --tests "com.limelight.<pkg>.<Class>"`. Tests live
  in `app/src/test/java/com/limelight/...`. Native/platform classes **must** be shadowed or the
  JVM throws `UnsatisfiedLinkError` — `ShadowMoonBridge` stubs the `System.loadLibrary("moonbr")`
  init and `ShadowGameManager` avoids a `ServiceManager` lookup; add more shadows the same way.
  See [android_test_setup.md](android_test_setup.md) for the full Robolectric/shadow/mock recipe.
- `compileSdk = 36`, `targetSdk = 34`, `minSdk = 24` (raised from 21 to satisfy the Jetpack
  XR libraries' minSdk without a manifest override). Java 11 source/target.
- Product flavors: `root` (maxSdk 25) and `nonRoot_game`. ABI splits enabled.
- **AGP 9 notes:** uses `proguard-android-optimize.txt` (the non-optimize default was removed),
  and `buildFeatures { resValues = true }` is required because the build uses `resValue`.

## Architecture map

Native streaming core (C) lives under `app/src/main/jni/`:
- `moonlight-core/moonlight-common-c` — git submodule, the shared Moonlight protocol/RTSP/
  decoder-feed engine. Built via `Android.mk` (ndkBuild).
- `evdev_reader` — raw input for the rooted flavor.
- Bridged into Java through `com.limelight.nvstream.jni.MoonBridge` (JNI).

Java/Android client under `app/src/main/java/com/limelight/`:
- `nvstream/` — connection orchestration (`NvConnection`), `StreamConfiguration`,
  `jni/MoonBridge`. This is where stream parameters (resolution, fps, codec, color)
  are assembled and handed to the native core.
- `binding/video/` — `MediaCodecDecoderRenderer`: wraps `MediaCodec`, picks the codec,
  and renders decoded frames into a target `Surface` (`setRenderTarget(Surface)`).
- `binding/audio/`, `binding/input/`, `binding/crypto/` — audio render, controllers/keyboard, pairing crypto.
- `Game.java` — the streaming Activity. Owns lifecycle, input handling, the decoder,
  and wires the decode `Surface` to the connection. **Large file (~3800 lines); read the
  region you need rather than the whole thing.**
- `ui/StreamContainer.java` — the `FrameLayout` that owns the display surface and chooses
  the render path (2D vs. stereo). Implements aspect-ratio measuring and input callbacks.
- `utils/Stereo3DRenderer.java` — the existing AI-depth SBS GL renderer (see below).
- `preferences/PreferenceConfiguration.java` — central settings struct; reads
  `SharedPreferences` into typed fields. UI in `res/xml/preferences.xml`.
- `PcView.java` / `AppView.java` / `grid/` — host list and app grid (pre-stream UI).
- `computers/`, `discovery/` — host pairing, mDNS discovery.

Assets: `app/src/main/assets/midas-midas-v2-w8a8.tflite` (depth model),
`app/src/main/assets/config/`.

## Existing stereo pipeline (read before changing rendering)

The video surface flow:
1. `Game` creates `StreamContainer` and calls `streamContainer.init(this, prefConfig)`.
2. `StreamContainer` branches on `prefConfig.renderMode`
   (`render_mode_list` pref, values `0/1/2`):
   - `MODE_2D` (0): a plain `SurfaceView`; the decoder renders directly to its surface.
   - `MODE_AI_3D` (1) / `MODE_AI_3D_MOVIE` (2): a `GLSurfaceView` with `Stereo3DRenderer`.
     The decoder renders into an internal `Surface` backed by a `SurfaceTexture`
     (external OES texture); the renderer runs MiDaS depth inference and DIBR to draw a
     synthesized left/right SBS image onto the GLSurfaceView.
3. When the surface is ready, `StreamContainer` calls back and `Game` does
   `decoderRenderer.setRenderTarget(streamContainer.getSurface())` then starts `conn`.

Key contract: **whoever owns the on-screen presentation provides a `Surface` to
`MediaCodecDecoderRenderer.setRenderTarget()`.** The XR feature plugs in here by
providing an XR-compositor-backed surface instead of a `SurfaceView`/`GLSurfaceView`.

Stereo-related preferences: `renderMode`, `parallax_depth`, `convergence_ratio`,
`balance_shift` (see `PreferenceConfiguration.java`). Render-mode strings/arrays in
`res/values/arrays.xml` (`render_mode_names`/`render_mode_values`) and `res/values/strings.xml`.

## Conventions

- Match the surrounding code style; this is a long-lived fork — keep diffs minimal and
  localized so upstream-merge friction stays low. Some logs are in German (existing code).
- Strings live in `res/values/strings.xml`; there are many translated `values-*` dirs.
  `lint { disable 'MissingTranslation' }` is set, so you may add an English string without
  translating it everywhere, but keep the default `values/strings.xml` authoritative.
- New user-facing settings: add the field to `PreferenceConfiguration`, the key/default,
  the UI entry in `res/xml/preferences.xml`, and strings/arrays as needed.
- Don't commit or push unless asked. If asked, branch off `moonlight-noir` first.
- Don't edit the `moonlight-common-c` submodule unless explicitly required.

## Gotchas

- `Game.java` and `Stereo3DRenderer.java` are large and performance-sensitive (real-time
  video). Avoid allocations on the render/draw path.
- The `release` build keeps the official-looking applicationId suffix logic — read the long
  comment in `app/build.gradle` before changing `applicationId`/signing.
- GL work must happen on the GLSurfaceView thread (`queueEvent`). Surface lifecycle is
  driven by `SurfaceHolder.Callback` / `OnSurfaceReadyListener`; respect create/destroy ordering.

## Reference docs

- [docs/android-xr-sbs.md](docs/android-xr-sbs.md) — the Android XR SBS design/plan **and**
  "Spatial UI learnings" (read before any rendering/surface/stereo or in-headset UI work).
- [android_test_setup.md](android_test_setup.md) — Robolectric unit-test recipe (shadows for
  native/platform classes, singleton/prefs resets, Activity testing, mocks).
- [README.md](README.md) — the fork's feature list and project identity/background.
- [.github/CONTRIBUTING.md](.github/CONTRIBUTING.md) — upstream Moonlight contribution guidance.
