# Android XR — display PC SBS stream stereoscopically on Galaxy XR

This document is the implementation plan for letting Artemis Android show a **side-by-side
(SBS) stereo video stream coming from the PC** as true stereoscopic 3D on **Android XR**
devices (Samsung Galaxy XR / "Project Moohan", and the Android XR emulator).

Read [../CLAUDE.md](../CLAUDE.md) first for the overall architecture and the existing
surface/stereo pipeline. **Treat this as a plan, not finished design — verify each API
against the current Jetpack XR release before writing code, and confirm decisions with the
maintainer where this doc says "DECISION NEEDED".**

> **This is an XR-only build.** The app is distributed only to Android XR devices
> (`<uses-feature android:name="android.software.xr.api.spatial" android:required="true"/>`,
> `minSdk 24`). Every device running it is an XR device, so the XR SBS path is the **primary**
> experience — not an optional mode hidden on phones. Device-detection guards are therefore a
> safety net, not the main gate; favor making XR the default and 2D a fallback panel.

## Status — bring-up on the XR emulator (2026-06-23)

Verified on the Galaxy XR emulator updated to the **v3 spatial API** image
(`android.software.xr.api.spatial=3`, `openxr=65537`), built with the Android Studio
**Canary JBR (JDK 21)** (JDK 25 also works). `MODE_XR_SBS` now runs end-to-end up to the
compositor: the stream enters `full-space-managed`, the OpenXR session reaches `FOCUSED`,
`SPATIAL_3D_CONTENT` capability is `true`, the SBS `SurfaceEntity` is created/attached and
**visibly composites as a quad floating in 3D** (correct per-eye aspect), and HEVC decodes
at 1280×720×60.

**Remaining blocker (emulator-only, not a code bug):** the quad stays **black** because the
emulator's `c2.goldfish.hevc.decoder` cannot bind its output to the SurfaceEntity consumer —
`Codec2Client: setOutputSurface -- failed to set consumer usage (6/BAD_INDEX)`, alongside
`Native fences not supported on this device`. This is an emulated-GPU/codec limitation; the
decoded frames never reach the quad texture. **Validate the actual SBS visual on real Galaxy
XR hardware.**

### Jetpack XR dependency version matrix (do not "align" them)
The `androidx.xr.*` modules version **independently**. The matched set is **scenecore family
= alpha15** and **runtime + arcore family = alpha14** (scenecore:alpha15 `requires`
runtime:alpha14 / arcore:alpha14; runtime alpha15 dropped `XrExtensionsHolder`, which
scenecore-spatial-core:alpha15 loads via a provider). Declare those exact versions in
`app/build.gradle`; do **not** add a blanket `resolutionStrategy` force to one version.

### Minification (R8) keep rules — required
The XR libraries use JNI/ServiceLoader/reflection R8 can't trace, so minified builds strip
classes/members and crash at stream start (JNI `NoSuchMethodError` on `ViewCameraState`'s
ctor; `AbstractMethodError` on `com.android.extensions.xr...Consumer.accept`;
`NoClassDefFoundError XrExtensionsHolder`). `app/proguard-rules.pro` keeps the whole
`androidx.xr.**` tree. `minifyEnabled` stays **true** for both buildTypes; if the
`Consumer.accept AbstractMethodError` recurs in a minified build, that is the open item to
chase (it disappears with minify off, so it is an R8 artifact, not a device ABI skew).

## Goal & scope

- **In scope:** When running on an Android XR device and streaming SBS content (e.g. a 3D
  game, a 3D movie, or Apollo configured to emit a full-frame side-by-side image), present
  the left half of each frame to the left eye and the right half to the right eye, on a
  panel/quad floating in the user's space.
- **Out of scope (for v1):** Head-tracked reprojection, 6DoF world-locked placement beyond a
  simple positionable panel, controller raycast UI, and the existing MiDaS AI-3D path
  (that synthesizes 3D from 2D and is unrelated — leave it alone).

The PC already produces the stereo signal, so on-device we do **not** run depth inference.
We just need the XR runtime to split an SBS surface across the eyes — which Android XR's
`SurfaceEntity` supports natively via `StereoMode.SIDE_BY_SIDE`.

### Division of labor (important)

The 2D→3D conversion is being moved to the **PC/host side**, the opposite of the existing
on-device MiDaS feature. That means there are two independent codebases:

- **Host (PC) — separate repo, NOT here:** produces the SBS frame. This is either a natively
  3D game, or a 2D→3D depth conversion + SBS compositing done in the host's capture/encode
  pipeline. That work lives in **[Apollo](https://github.com/ClassicOldSong/Apollo)** (the
  Sunshine fork, C++), not in this Android repo. It is the larger effort.
- **This repo (Android client):** receives a normal video stream that happens to carry
  side-by-side content and presents it stereoscopically on Android XR. **The client code is
  identical regardless of how the host produced the SBS** — it does not know or care whether
  the source was a 3D game or a PC-side depth conversion.

Everything below concerns only the Android client. No protocol/codec change is needed on the
client for v1, because an SBS frame is just an ordinary video frame to the decoder.

## Why this is mostly a "surface routing" change

The decoder already renders into whatever `Surface` it's handed
(`MediaCodecDecoderRenderer.setRenderTarget(Surface)`), and `StreamContainer` already
chooses the presentation path based on `prefConfig.renderMode`. The XR path is a **new
presentation owner** that:
1. Creates an XR scene with a stereo surface entity.
2. Hands that entity's `Surface` to the decoder as the render target.
3. Tells the entity the content is `SIDE_BY_SIDE` so the compositor does the eye split.

No changes to the native core, the protocol, or `MediaCodec` configuration are required for
a first version. (If we later want the PC to send a *higher* resolution because each eye
only gets half the width, that's a `StreamConfiguration` tweak — see "Resolution" below.)

## Key Android XR APIs (verify versions before use)

Android XR apps use **Jetpack XR (SceneCore)**. The relevant pieces:

- `androidx.xr.scenecore` — `Session`, `SurfaceEntity` (a quad whose content is fed by a
  `Surface`), with `StereoMode.MONO | SIDE_BY_SIDE | TOP_BOTTOM`. This is the core API for
  this feature: create a `SurfaceEntity` in `SIDE_BY_SIDE` mode, get its `Surface`, route
  the decoder to it.
- `androidx.xr.compose` (optional) — Compose-based spatial UI (`Subspace`, `SpatialPanel`).
  We likely don't need full Compose; a `SurfaceEntity` plus a small 2D panel for controls
  may suffice. **DECISION NEEDED:** Compose spatial UI vs. minimal SceneCore-only.
- The device is detected via the XR feature / `PackageManager` system feature
  (e.g. `android.software.xr.*`) and/or by successfully creating an XR `Session`.

> ⚠️ The Jetpack XR libraries are evolving. Before coding, look up the **current** artifact
> coordinates and class/enum names (the `StereoMode` location and `SurfaceEntity` factory
> signature have changed across alphas). Do not trust the exact symbol names in this doc —
> trust the pattern. Use the official Android XR developer docs / release notes.

## Proposed implementation

### 1. Gradle / manifest

- Add Jetpack XR dependencies to `app/build.gradle` (scenecore, runtime, and the openxr
  runtime impl). The app's `minSdk` is **24**, which satisfies the XR libraries' declared
  minSdk (23/24), so no manifest `overrideLibrary` is required. XR APIs are still only
  exercised on actual XR devices (API 34+), so **all XR code must be runtime-guarded** behind
  device detection — both for correctness on non-XR devices and to avoid loading XR classes
  there.
- `AndroidManifest.xml`: declare XR support as **optional** so the app still installs on
  phones/tablets/TV:
  ```xml
  <uses-feature android:name="android.software.xr.immersive" android:required="false" />
  ```
  (Confirm the exact feature string for Galaxy XR.) Add any `<property>`/`<meta-data>` the XR
  runtime requires to opt the activity into spatial mode.

### 2. Device detection — `utils/XrUtils.java` (new)

A small helper: `boolean isAndroidXrDevice(Context)` (system-feature check) and
`boolean isXrAvailable()` (Jetpack XR present and a `Session` creatable). Everything XR must
be behind this guard. Keep it dependency-light so it can be called from `PreferenceConfiguration`
and `Game` without pulling XR classes onto non-XR devices (use reflection-safe / `try`-guarded
access, or isolate XR classes so they're only class-loaded on XR devices).

### 3. New render mode — `MODE_XR_SBS`

- Add `MODE_XR_SBS` to `StreamContainer.StreamMode` (and a new value `3` to
  `render_mode_values` / a new `render_mode_xr_sbs` string in `arrays.xml`/`strings.xml`).
- Only offer this option in the settings UI when `XrUtils.isAndroidXrDevice()` is true
  (filter the `ListPreference` entries at runtime in the settings fragment).
- **DECISION NEEDED:** auto-select XR mode on Galaxy XR vs. leave it user-selectable. A good
  default: on an XR device, default `renderMode` to `MODE_XR_SBS`, still overridable.

### 4. XR presentation owner — `ui/XrStreamPresenter.java` (new)

Mirror the role `Stereo3DRenderer` plays, but backed by SceneCore instead of GL:
- On init: create/obtain the SceneCore `Session`, create a `SurfaceEntity` with
  `StereoMode.SIDE_BY_SIDE`, size/position it (a comfortable forward-facing panel; make the
  canvas aspect ratio match a *single eye*, i.e. half the stream width × full height).
- Expose `Surface getVideoSurface()` and an `onSurfaceReady` callback — the same contract
  `Stereo3DRenderer` exposes — so `StreamContainer` can wire it identically:
  `decoderRenderer.setRenderTarget(presenter.getVideoSurface())`.
- On destroy: tear down the entity and session, mirroring
  `Stereo3DRenderer.onSurfaceDestroyed()` and `StreamContainer.onDestroy()`.

### 5. `StreamContainer` wiring

In `StreamContainer.init(...)`, add a branch for `MODE_XR_SBS` parallel to the existing
GLSurfaceView branch: instead of creating a `GLSurfaceView`, create the `XrStreamPresenter`,
and when its surface is ready, call `notifySurfaceReady()` / `onStereo3DSurfaceReady(surface)`
so the existing `Game.setOnSurfaceAvailable` path sets the decoder render target unchanged.

Keep `Game.java` changes minimal — ideally none beyond what already exists, since the
surface-ready callback already drives `decoderRenderer.setRenderTarget(...)`. Verify the
`renderMode != 0` special-casing in `Game.java` (around lines 400–420: it forces
`STRETCH` scale mode and disables floating buttons for 3D) is appropriate for XR too, or add
an explicit `MODE_XR_SBS` check.

### 6. Input & overlay

In immersive XR, the on-screen touch OSC / floating buttons won't make sense. For v1, route
gameplay input from a controller/gamepad (already supported via `binding/input/`) and hide
the 2D OSC overlays when in `MODE_XR_SBS`. **DECISION NEEDED:** how the user opens the game
menu / disconnects in XR (e.g. a small spatial panel or a controller button).

### 7. Resolution (optional refinement)

In a packed full-SBS frame, each eye gets half the horizontal resolution. If we want crisp
per-eye resolution we can request a wider stream from the host (e.g. double-width) via
`StreamConfiguration` so each eye still gets full width. For v1, ship with the host's normal
SBS output and revisit after measuring quality. Note `Game.java` already avoids
resolution-inverting for `renderMode != 0`.

## Files to touch (summary)

| File | Change |
| --- | --- |
| `app/build.gradle` | Add Jetpack XR deps (guarded) |
| `app/src/main/AndroidManifest.xml` | Optional XR `uses-feature` + any required props |
| `app/src/main/java/com/limelight/utils/XrUtils.java` | **new** — device detection |
| `app/src/main/java/com/limelight/ui/XrStreamPresenter.java` | **new** — SceneCore SBS presenter |
| `app/src/main/java/com/limelight/ui/StreamContainer.java` | Add `MODE_XR_SBS` branch |
| `app/src/main/java/com/limelight/preferences/PreferenceConfiguration.java` | Recognize new render mode |
| `app/src/main/res/values/arrays.xml`, `strings.xml` | New render-mode entry + strings |
| `app/src/main/res/xml/preferences.xml` | Surface the option (XR-gated) |
| `app/src/main/java/com/limelight/Game.java` | Minimal: confirm `MODE_XR_SBS` handled like other non-2D modes |

## Testing

- **Android XR emulator** (Android Studio AVD with the XR system image) is the primary dev
  target before hardware; it supports `SurfaceEntity` stereo rendering.
- Verify on phone/tablet (API 24+) that the app still builds and runs and that `MODE_XR_SBS`
  is hidden / harmless (no XR classes loaded, no crash) — the key non-XR regression check.
- Test SBS source: a known SBS video or a host configured to output side-by-side. Confirm
  left/right eyes are not swapped (add a swap toggle if uncertain).
- Run `./gradlew test` — keep the existing unit tests green; `MoonBridge` is shadowed.

## Open questions (resolve with maintainer)

1. Confirm the exact Galaxy XR system-feature string and minimum API for XR APIs.
2. Compose spatial UI vs. SceneCore-only for the panel + controls.
3. Default render mode on XR devices (auto vs. manual).
4. In-game menu / disconnect affordance in immersive mode.
5. Whether to request a higher host resolution to compensate for the per-eye width halving.
6. Handling of host SBS that is **top-bottom** instead of side-by-side
   (`StereoMode.TOP_BOTTOM`) — expose as a sub-option?
