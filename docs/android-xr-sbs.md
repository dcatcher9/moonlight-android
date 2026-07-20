# Android XR SBS architecture

This document records the current stereoscopic presentation contracts for Artemis on Samsung
Galaxy XR. It is a current implementation guide, not a proposal. Read it before changing surface
routing, SceneCore entities, Client SBS inference, or in-headset controls.

Artemis is an XR-only build. The manifest requires
`android.software.xr.api.spatial`, and the supported hardware target is arm64 Galaxy XR. The
x86_64 split remains useful for disposable Android XR emulator work, but it does not reproduce the
headset's codec, Adreno, OpenCL, or SceneCore performance.

## Presentation modes

`Game`, `StreamContainer`, and `XrStreamPresenter` maintain one active presentation owner:

- **Normal** decodes directly into the SceneCore `SurfaceEntity` in mono mode.
- **Host SBS Raw** decodes the host's packed SBS stream directly into the same entity with
  `StereoMode.SIDE_BY_SIDE`.
- **Host SBS AI** uses the same direct stereo surface path; Apollo performs depth inference and SBS
  synthesis before encoding.
- **Client SBS AI** decodes into an external-OES `SurfaceTexture`, runs the native LiteRT/GLES
  pipeline on the headset, and presents its packed `2W x H` output through the SceneCore entity in
  side-by-side mode.

Normal and both Host SBS modes are direct MediaCodec-to-SceneCore paths. Do not insert a GL bridge,
copy, or Client SBS dependency into them.

## Direct SceneCore path

The working Galaxy XR sequence is:

1. Create a `SurfaceEntity` with the appropriate mono or side-by-side stereo mode.
2. Set its surface pixel dimensions so the SBS split lands on the exact half-frame boundary.
3. Parent it to `scene.getActivitySpace()`, enable it, and set alpha to one.
4. Hide the activity's main 2D panel while immersive presentation is active.
5. Give `surfaceEntity.getSurface()` to
   `MediaCodecDecoderRenderer.setRenderTarget(Surface)`.

Two historical pitfalls remain important:

- An unparented entity is not part of the rendered scene graph and appears as a black/missing quad.
- The activity's opaque main panel can occlude an otherwise working entity.

`CCodec`'s `onWorkDone` message is not a frame counter, and
`setOutputSurface ... failed to set consumer usage (6/BAD_INDEX)` also appears on working paths.
Use the stream/stat counters and actual consumer callbacks to diagnose flow.

## Client SBS native GPU path

Client SBS has one production inference path:

```text
MediaCodec
  -> external-OES SurfaceTexture
  -> matched color texture slot
  -> GLES model-input render + packed Float32 GL input buffer
  -> native LiteRT 2.x / OpenCL FP16 inference
  -> packed Float32 GL depth buffer
  -> GLES depth statistics + temporal/profile processing
  -> depth prefilter + two-eye reprojection
  -> packed SBS SceneCore surface
```

The bundled model is `midas-midas-v2-float.tflite` with Float32 NHWC public tensors at
`256 x 256`. LiteRT performs its packed-to-PHWC4 conversion internally on the GPU. The renderer and
inference worker exchange GL fences across shared EGL contexts; model tensors are not mapped into
Java or staged through CPU memory.

There is no production managed Java LiteRT interpreter, QNN/HTP delegate, CPU inference fallback,
PBO readback path, or Java depth-result worker. Native initialization requires full GPU delegation
and OpenCL/OpenGL interoperability. If that contract fails, Client SBS depth becomes `Unavailable`
and presentation fails closed to flat output. The app, Normal mode, and Host SBS modes must remain
usable.

The inference worker owns native LiteRT creation, invocation, and destruction. Do not destroy the
engine from the renderer thread. Renderer-side failures must signal the owner thread to stop, while
releasing every frame-slot lease, inference claim, and GL fence exactly once.

## Matched color/depth scheduling

Client SBS deliberately behaves like Apollo's delayed host path: a depth result remains paired with
the exact captured color slot that produced it. Presentation may be delayed by inference, but a new
depth map must never warp a different color frame.

Scheduling is readiness-driven rather than timer-driven:

- Surface callbacks may coalesce to the newest decoded frame.
- A single-flight inference claim prevents the worker queue from growing.
- Two color slots allow one exact pair to remain active while the newest eligible frame is captured.
- Shared-context fences order input production, inference output, and GPU postprocessing without
  blocking the GL thread on normal operation.
- The packed SBS cache may be reused until a newer matched pair is ready.

Busy claims, occupied mailboxes, or unavailable color slots drop/reuse work; they must not create an
unbounded queue or detach depth from color.

## Fixed Client SBS profile

Client SBS and Host SBS AI are single-tap modes with no parameter subpanels. Client normalization,
subject plane, convergence, and pop compensation are adaptive GPU state in
`ClientSbsGpuDepthProcessor`. Do not reintroduce the removed strength, convergence, balance,
movie-mode, or legacy shader parameters.

The depth model runs on a 256 x 256 aspect-preserving inference image, not the full decoded frame.
Reflected padding is removed when the source-aligned depth texture is produced. Reprojection then
uses the full-resolution matched color texture.

## Surface and lifecycle ownership

The central contract is:

> Whoever owns presentation supplies the current `Surface` to
> `MediaCodecDecoderRenderer.setRenderTarget()`.

Mode switches are guarded asynchronous surface handoffs. Keep the decoder target, SceneCore surface
size/stereo mode, renderer generation, and entity visibility synchronized. A stale callback from a
previous generation must not retarget the decoder or publish a depth result.

On destroy or mode exit:

- Stop new Client SBS captures.
- Let the inference owner thread close native LiteRT.
- Release pending and active color leases and delete owned fences/GL resources.
- Detach/release the old decoder surface only after the replacement target is ready.
- Cancel delayed render retries so they cannot resurrect a destroyed renderer.

## Reconnect and saved-view contract

Apollo's `/serverinfo` response is the authority for whether a stream session exists. Artemis does
not duplicate Apollo's disconnect grace period with a client timer.

- A fresh host connection starts in **Normal**.
- Resuming the same host session/app restores the last successfully rendered presentation mode.
- Panel height is durable per machine/app and is restored independently of presentation mode.
- A confirmed incompatible startup resets the saved mode to Normal.

The host's current running-app identity must travel explicitly through the Game intent; elapsed
client time is not a resume decision.

## HDR and color range

Normal and Host SBS are direct decoder paths. Leave the `SurfaceEntity` content color metadata
unset so SceneCore consumes the decoded `HardwareBuffer` dataspace, HDR transfer, and source range.

Client SBS is a new RGB producer after OES sampling. It tonemaps HDR only for the SDR depth model
while preserving the presentation color path, and it supplies explicit BT.2020/ST2084 metadata for
HDR output. Its RGB surface advertises full range; reusing the source YUV limited/full flag would
apply range interpretation twice. Clear explicit metadata before returning to a direct mode.

Force output alpha to one. External-OES video may sample with alpha zero, which otherwise makes the
SceneCore quad transparent or black.

## In-headset controls and stats

The control bar stays at a fixed location beneath the video. Controls remain above the stats panel;
opening Stats must not move the controls. The stats panel extends below the controls and favors a
wide, low layout so the rightmost values remain readable.

Presentation modes form one single-select group. Navigation/disconnect actions remain separate
one-shot controls. A fresh connection highlights Normal; a resumed session highlights its restored
mode only after that mode is actually active.

Stats must describe the active native path:

- Stream receive/decode/release stages.
- Surface callback and GL latch rates.
- Matched color capture and GPU input preprocessing.
- Inference queue/completion latency and cadence.
- Output-fence/adoption delay, GPU depth postprocessing, and matched depth age.
- SBS composition, final GL submission, cache reuse, and backpressure counters.
- App CPU plus readable device GPU/NPU counters, clearly marked with their scope.

Do not show managed/PBO/free-CPU-buffer/result-worker stages. Android 14 has no public trustworthy
per-app NPU utilization API. A readable vendor NPU counter is device-wide and does not imply Client
SBS uses it; the active Client SBS backend is the GPU.

### Spatial UI learnings

These rules are verified on Galaxy XR with SceneCore alpha16:

- Host several clickable Android `View`s inside one `PanelEntity`, like a toolbar. Separate panels
  per tile do not receive the same native child-view gaze highlight.
- A clickable hosted view's `OnClickListener` receives gaze/pinch input. Per-control
  `InteractableComponent`s are unnecessary and suppress the view's own hover highlight.
- Use `InteractableComponent` only for genuine entity-level input. Runtime hover action objects do
  not reliably compare by identity with the SDK hover constants.
- For icon-over-label tiles, use a vertical `LinearLayout` with an `ImageView` and `TextView` rather
  than a Button compound drawable.
- Panel contents scale with the entity's physical meter size. Tune meter dimensions together with
  child dp/sp sizes, padding, and margins.
- Immersive screens need explicit in-app navigation because the hidden main panel provides no 2D
  system back affordance.

## Jetpack XR dependencies and minification

The working DP4 matrix pins SceneCore, runtime, runtime-openxr, ARCore, and arcore-openxr to
`1.0.0-alpha16`. Keep the five artifacts aligned; mixed Java and native OpenXR versions can crash
`ViewCameraState` construction.

Debug minification remains disabled. R8 keep rules avoid several reflection/JNI failures, but
hardware still reproduces an `AbstractMethodError` in the device-provided XR `Consumer` interface
when the debug build is minified. Release minification remains a separate unresolved task.

## Testing

Use [client-sbs-evaluation.md](client-sbs-evaluation.md) for exact unit, assemble, native GPU smoke,
update-install, log, and sustained-stream procedures.

On the user's physical Galaxy XR, never run Gradle's connected Android test task: it uninstalls the
target application afterward and erases preferences, certificates, pairings, and profiles. Install
the main and test APKs with `adb install -r`, invoke instrumentation manually, and uninstall only
`com.limelight.noirdebug.test`.

For every mode/surface change, test:

- Fresh connection starts Normal; resume restores the prior active mode.
- Normal and Host SBS remain direct and work when Client SBS initialization fails.
- Client SBS reports `LITERT_GPU_GL_FP16`, produces non-flat depth, and preserves color/depth pairing.
- Left/right eyes are not swapped and the packed split is centered exactly.
- SDR/HDR metadata is cleared/applied correctly when switching modes.
- Controls do not move when Stats opens, and the wide lower stats panel is fully readable.
- Repeated disconnect/resume/mode switches do not leak surfaces, entities, EGL contexts, leases, or
  fences and do not recreate LiteRT during a stable stream.
