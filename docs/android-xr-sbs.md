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

## Status — working on Galaxy XR hardware (2026-06-24)

`MODE_XR_SBS` **works end-to-end on a real Galaxy XR** (SM-I610, Android 14, arm64-v8a): the
PC's packed side-by-side stream is decoded by the hardware HEVC decoder rendering **directly**
into a SceneCore `SurfaceEntity` (`StereoMode.SIDE_BY_SIDE`), and the headset fuses it into one
stereo image. No GL/intermediate pass is needed — the decoder feeds the entity surface directly,
keeping latency minimal.

The working sequence in `XrStreamPresenter.init()`:
1. `SurfaceEntity.create(session, pose, Quad, SIDE_BY_SIDE)` at ~2 m in front.
2. `setSurfacePixelDimensions(width, height)` so the L/R split lands on the half boundary.
3. **`setParent(scene.getActivitySpace())`** + `setEnabled(true)` + `setAlpha(1)`.
4. **`scene.getMainPanelEntity().setEnabled(false)`** to hide the activity's 2D panel.
5. Hand `surfaceEntity.getSurface()` straight to `decoderRenderer.setRenderTarget(...)`.

### What the long "black quad" investigation actually was (lessons)
This looked for a while like an emulator/codec buffer problem. It was not. The black quad had
two real causes, both fixed above:
- **Missing `setParent`** — without an explicit parent the entity isn't attached to the rendered
  scene graph, so the quad never appears even though its surface is being fed and consumed.
  This was the main bug.
- **The 2D main panel occluded the quad** — in full-space mode the activity window (which hosts
  `StreamContainer`'s placeholder `SurfaceView`, carrying no video) renders as an opaque panel
  in front of the quad. Hiding the main panel reveals the SBS quad.

Two misreads that cost significant time, recorded so they are not repeated:
- **`onWorkDone` is NOT a per-frame counter.** It's an infrequent CCodec debug log (output-delay
  updates, etc.) that sat at 2–4 whether the stream rendered or not. The "decoder stalls after a
  few frames" theory was based entirely on misreading it; the decoder never stalled. To gauge
  real frame flow, instrument the *consumer* (e.g. a `SurfaceTexture` `onFrameAvailable` counter),
  not `onWorkDone`.
- **`setOutputSurface ... failed to set consumer usage (6/BAD_INDEX)` is BENIGN** — it also
  appears in the working 2D `SurfaceView` path, so it is not a cause.

A GL-bridge workaround (decoder → our `SurfaceTexture` → GL blit → SurfaceEntity surface) was
built and did work — which proved SceneCore consumes our frames fine — but it became unnecessary
once parenting + panel-hide were in place, so it was removed. If per-eye recoloring/reshaping is
ever needed, that pattern is the reference: an EGL14 window surface on `getSurface()`, an OES
`SurfaceTexture` for the decoder, and **force opaque alpha** in the OES fragment shader (video
sampled via `samplerExternalOES` yields alpha 0, which composites transparent/black otherwise).

> The earlier emulator note (2026-06-23) attributed the black quad to the emulator's
> `c2.goldfish.hevc.decoder` and `setOutputSurface ... BAD_INDEX`. That conclusion was wrong —
> see above. (The emulator may still have its own codec/GPU limitations, but the black quad on
> both emulator and hardware was the parenting + panel occlusion.)

### Jetpack XR dependency version matrix (do not "align" them)
The `androidx.xr.*` modules version **independently**. The matched set is **scenecore family
= alpha15** and **runtime + arcore family = alpha14** (scenecore:alpha15 `requires`
runtime:alpha14 / arcore:alpha14; runtime alpha15 dropped `XrExtensionsHolder`, which
scenecore-spatial-core:alpha15 loads via a provider). Declare those exact versions in
`app/build.gradle`; do **not** add a blanket `resolutionStrategy` force to one version.

### Minification (R8) — debug currently builds with minify OFF
The XR libraries use JNI/ServiceLoader/reflection R8 can't trace. `app/proguard-rules.pro` keeps
the whole `androidx.xr.**` tree, which fixes the JNI `NoSuchMethodError` on `ViewCameraState`'s
ctor and the `NoClassDefFoundError XrExtensionsHolder`. But one crash the keep rules do **not**
fix: `AbstractMethodError` on `com.android.extensions.xr.function.Consumer.accept` at
`XrExtensions.lambda$setSpatialStateCallback$7`, confirmed to reproduce **on hardware** with
minify on. R8 desugars the runtime's `setSpatialStateCallback` lambda into a class implementing
the device-provided `Consumer` interface (which isn't in the APK), leaving `accept()` abstract.
So **`minifyEnabled` is `false` for the `debug` buildType** as the current workaround. **Open
item:** make `release` builds work with minify on (the `androidx.xr.**` keep rules alone are
insufficient).

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
the 2D OSC overlays when in `MODE_XR_SBS`.

**Resolved (control bar):** `XrStreamPresenter` floats a spatial control bar beneath the video
quad — **one `PanelEntity` hosting a horizontal `LinearLayout` of clickable icon-over-label
tiles** (a normal toolbar). Tiles are data-driven (`BarItem` list) so the bar is easy to extend.
A vertical divider splits it into a single-select mode group and one-shot actions. Current tiles:
- **Normal** / **SBS** — a single-select group that flips the `SurfaceEntity` `StereoMode`
  (`MONO` &harr; `SIDE_BY_SIDE`) live. Because the surface always carries the same packed frame,
  switching also reshapes the quad to the matching aspect (full-frame for MONO, half-width per-eye
  for SBS), keeping the **height** constant and varying the width. Default is **MONO** (flat). The
  active mode tile is shown with an accent fill.
- **Machines** — ends the stream and returns to the host list (`PcView`) via
  `FLAG_ACTIVITY_CLEAR_TOP | FLAG_ACTIVITY_SINGLE_TOP`.
- **Disconnect** — ends the stream (`activity.finish()`).

Each tile handles its own tap through its `View.OnClickListener`; there is **no per-tile
`InteractableComponent`**. See "Spatial UI learnings" below for why the bar is one panel.

> **Navigation:** in immersive (full-space) XR the activity's main panel is hidden, so there is no
> system back affordance on the 2D screens either. The host list (`PcView`), app grid (`AppView`),
> profiles, and settings (`StreamSettings`) screens therefore each carry an explicit in-app back
> button (a FAB) — without them you can enter a screen and have no way back on the headset.

#### Spatial UI learnings (Galaxy XR, scenecore alpha15)

Hard-won, verified on hardware — read before building any in-headset UI here:

- **One panel, many views — not one panel per control.** Model controls as an ordinary Android
  `View` hierarchy inside a *single* `PanelEntity` (like a toolbar), exactly as you would a 2D
  screen. The platform draws the **gaze hover highlight** on each clickable child view in a single
  panel (the same way several FABs on a 2D screen each highlight). It does **not** highlight
  separate interactable child `PanelEntity`s — a row of one-panel-per-tile looked correct but no
  tile ever highlighted. The single-panel toolbar both highlights *and* is far less code.
- **Hosted-view input is native in a single panel.** A clickable view's `OnClickListener` fires
  from gaze+pinch, and its hover/pressed visuals render live — no `InteractableComponent` needed
  for per-control taps. (The original lone Disconnect button used an `InteractableComponent` only
  because it predated this understanding.) Dynamic restyling of a hosted view (e.g. the active-mode
  accent) also renders live in a single panel.
- **`InteractableComponent` intercepts gaze input.** Attaching one to a panel routes gaze/pinch to
  the entity-level callback instead of the hosted view, which suppresses the view's own highlight.
  Use it only when you genuinely need entity-level input and no per-view highlight.
- **`InputEvent.Action` equality gotcha.** If you *do* use `InteractableComponent`, the runtime
  delivers **hover** actions (`HOVER_ENTER`/`HOVER_MOVE`/`HOVER_EXIT`) as different instances than
  the SDK's `InputEvent.Action` constants, so `==` never matches them — while *pointer* actions
  (`UP`/`DOWN`/`MOVE`) do reuse the constants and compare fine. `Action` exposes no value getter or
  `equals()`, so there's no clean way to match hover by identity; prefer the hosted-view route above.
- **Icon-over-label needs a real layout.** A `Button` with a `drawableTop` compound drawable
  centers only the *text* and hangs the icon above it (icon-rides-to-top). Use a vertical
  `LinearLayout` (`ImageView` over `TextView`, `gravity=center`) for a properly centered pair.
- **Panel content scales with the panel's meter size.** Sizes are tuned in meters
  (`BAR_WIDTH_METERS`/`BAR_HEIGHT_METERS`) plus `dp`/`sp` for the child views; shrinking the panel
  shrinks everything, so to make content larger *and* the bar smaller, raise the `dp`/`sp` and
  trim padding/margins rather than only changing the meters.

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
| `app/src/main/java/com/limelight/Game.java` | Forwards host depth status and advertised SBS profiles to the XR presenter |

### Dynamic host profiles

Apollo sends the current profile followed by all available profile names on encrypted control
packet `0x3007`. Tapping **Host SBS AI** opens a scrollable chooser directly beneath that mode
tile; selecting a host-advertised name sends it on `0x3005` and closes the chooser. No model or
profile names are hard-coded in Artemis. `0x3006` independently reports only the depth-engine
idle/loading/ready phase; the loading UI uses the selected profile name. Selection is per stream
and can be changed while Host SBS AI is live.

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
4. ~~In-game menu / disconnect affordance in immersive mode.~~ **Resolved** — single-panel spatial
   control bar (Normal/SBS modes, divider, Machines, Disconnect) beneath the quad, plus in-app back
   buttons on the 2D screens; see "Input & overlay" and "Spatial UI learnings" above.
5. Whether to request a higher host resolution to compensate for the per-eye width halving.
6. Handling of host SBS that is **top-bottom** instead of side-by-side
   (`StereoMode.TOP_BOTTOM`) — expose as a sub-option?
