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

The goal: present either a **side-by-side (SBS) stereo stream produced on the PC** or Artemis'
on-device Client SBS AI output stereoscopically on the headset. See
[docs/android-xr-sbs.md](docs/android-xr-sbs.md) for the design history and current contracts.
Read that document before touching any rendering / surface / stereo code —
and its **"Spatial UI learnings"** section before building any in-headset UI (key rule: put
controls as ordinary clickable `View`s in a *single* `PanelEntity`, like a toolbar, not one panel
per control — that's what gives the gaze highlight and native taps).

Implication: since every device running this build is an XR device, the XR route is the
**primary** experience. Non-XR phone/tablet paths are no longer the target. Keep the two SBS
producers distinct: Host SBS is decoded directly into SceneCore, while Client SBS runs the
selected depth/reprojection pipeline on the headset before SceneCore splits the packed result.

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
  activity is `com.limelight.PcView`. This is an update-install of
  `com.limelight.noirdebug` and preserves its preferences, certificates, pairings, and profiles.
  (The XR control bar only appears inside an active stream.)
- **Physical-headset data safety:** never run `connectedNonRoot_gameDebugAndroidTest`,
  `uninstallNonRoot_gameDebug`, `adb uninstall com.limelight.noirdebug`, or
  `pm clear com.limelight.noirdebug` on the user's Galaxy XR.
  Gradle's connected-test task uninstalls the target package after testing and erases all app
  data. Follow the update-install/manual-instrumentation procedure in
  [docs/client-sbs-evaluation.md](docs/client-sbs-evaluation.md), then uninstall only the
  `.test` package.
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
- `client_sbs_gpu` - lazily loaded native LiteRT 2.x GL/OpenCL bridge for Client SBS. It is
  deliberately separate from `moonlight-core`, so a depth-runtime failure cannot prevent normal
  streaming or app startup.
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

Client SBS model assets live only in the `nonRoot_game` flavor under
`app/src/nonRoot_game/assets/`: one solid XZ-compressed TAR per model family. The DA-V2 archive
contains three performance graphs (`322x182`, `350x154`, and `434x126`); the MiDaS v2.1 Small
archive contains three aspect buckets (`352x192`, `384x160`, and `448x128`). DA-V2 dimensions are
divisible by its 14-pixel patch size; MiDaS dimensions are divisible by 32 so its EfficientNet-Lite3
encoder and decoder skip pyramid remain aligned. Both
families directly resize the complete frame into the selected rectangle and use the same native
LiteRT/OpenCL pipeline with packed Float32 GL input/output. Inference streams, verifies, and stages
only the selected archive entry into code cache. At most one selected Client SBS model is compiled
at a time; it remains GPU-resident across mode switches until stream teardown. The matching LiteRT
runtime is likewise flavor-scoped under
`app/src/nonRoot_game/jniLibs/`; root APKs contain neither the models nor LiteRT. Other application
assets live under `app/src/main/assets/config/`.

## Current XR stereo pipeline (read before changing rendering)

`Game` creates one `StreamContainer`/`XrStreamPresenter` route. Presentation mode is selected from
the in-headset control bar and persisted per machine/app:

- **Normal**, **Host SBS Raw**, and **Host SBS AI** render MediaCodec directly into the SceneCore
  `SurfaceEntity`; the latter two use `StereoMode.SIDE_BY_SIDE`.
- **Client SBS AI** temporarily parks MediaCodec on a persistent dummy surface, hands the decoded
  stream to `Stereo3DRenderer` through an external-OES `SurfaceTexture`, and presents its packed
  `2W x H` output on the same XR entity.

Client SBS mirrors Apollo's fixed production depth/profile math. It has no user-facing strength,
convergence, balance, or movie-mode parameters; normalization, convergence, and pop compensation
are adaptive GPU state in `ClientSbsGpuDepthProcessor`. Production Client SBS is a single native
LiteRT path: packed Float32 GL tensors at the model boundary, OpenCL FP16 inference internally, and
GLES depth/profile/reprojection. There is no managed Java LiteRT, QNN, CPU, PBO-readback, or
result-worker fallback. If native GPU depth is unavailable, depth fails closed and presentation
remains usable as flat output; Normal and Host SBS modes remain independent. Do not reintroduce the
deleted legacy preference keys, shader uniforms, or managed fallback path.

Depth inference is readiness-driven and uncapped: no target FPS, thermal cadence reduction, or
forced completion idle is allowed. Keep the atomic single-flight claim and latest-frame coalescing;
they bound the queue to one exact color/depth transaction without imposing a time-based cap.

Key contract: **whoever owns presentation provides the current `Surface` to
`MediaCodecDecoderRenderer.setRenderTarget()`.** Mode switches are guarded asynchronous surface
handoffs; keep the decoder target, SceneCore surface size, and renderer generation synchronized.

## Conventions

- Match the surrounding code style; this is a long-lived fork — keep diffs minimal and
  localized so upstream-merge friction stays low. Some logs are in German (existing code).
- Strings live in `res/values/strings.xml`; there are many translated `values-*` dirs.
  `lint { disable 'MissingTranslation' }` is set, so you may add an English string without
  translating it everywhere, but keep the default `values/strings.xml` authoritative.
- New user-facing settings: add the field to `PreferenceConfiguration`, the key/default,
  the UI entry in `res/xml/preferences.xml`, and strings/arrays as needed.
- Don't commit or push unless asked. When asked, commit **directly to `moonlight-noir`** — this is
  a personal fork; no feature branches or PRs.
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
