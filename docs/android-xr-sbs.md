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
- **Host SBS Raw** treats its selected `W x H` resolution as a per-eye quality, negotiates an
  untouched `2W x H` virtual desktop/stream from Apollo, and decodes that packed frame directly
  into the same entity with `StereoMode.SIDE_BY_SIDE`. It is available only for a
  virtual-display-backed launch, including Apollo's generated **Virtual Display** entry; a physical
  desktop would only be aspect-fitted into a wide encoder surface rather than rendered at `2W`.
  The logical per-eye width is limited to 4096 so the exact packed frame remains within the
  8192-pixel HEVC/AV1 transport limit.
- **Host SBS AI** uses the same direct stereo surface path; Apollo performs depth inference and SBS
  synthesis before encoding.
- **Client SBS AI** decodes into an external-OES `SurfaceTexture`, runs the native LiteRT/GLES
  pipeline on the headset, and presents its packed `2W x H` output through the SceneCore entity in
  side-by-side mode. `W x H` is the client request/output contract: Client SBS does not apply a hidden
  post-decode resolution cap. Choose a smaller stream resolution when a smaller GPU/compositor
  workload is required.

Client SBS requires a mono frame from the host application. Sending `SBS_MODE_OFF` disables
Apollo's Host SBS AI packing, but it cannot un-pack SBS pixels that the application itself already
rendered for Host SBS Raw. Such a frame would be processed as one mono Client SBS input. On legacy
hosts that negotiate the decoded stream below the client request, Client SBS still produces the
requested `W x H` matched-color/per-eye target by upscaling that lower-resolution input.

Normal and both Host SBS modes are direct MediaCodec-to-SceneCore paths. Do not insert a GL bridge,
copy, or Client SBS dependency into them.

Raw SBS uses a different negotiated base width from every other mode. Entering or leaving Raw must
commit the target mode and reconnect before changing SceneCore, MediaCodec, Client SBS ownership, or
Apollo's Host AI wire mode. This prevents Client AI from consuming an already-packed `2W` frame and
prevents Host AI from doubling an already-doubled base width. Live transitions among Normal, Host
SBS AI, and Client SBS AI retain their guarded surface-handoff behavior. After a live
`setOutputSurface()` handoff, reapply the requested surface frame rate because that metadata belongs
to the replacement `Surface`. Artemis rejects Raw on a physical-capture session and directs the user
to relaunch Apollo's Virtual Display instead of pretending that a wide aspect-fit is native `2W`
rendering.

The standard **Video frame pacing** list is the only decoder release-policy control. **Prefer lowest
latency** nonblockingly drains ready MediaCodec outputs, discards superseded buffers, and immediately
submits only the newest. **Balanced** alone uses the two-buffer Choreographer queue. The former LFR /
"Prefer lower delays" checkbox was an inverted duplicate and must not be reintroduced.

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
  -> SurfaceTexture crop/orientation transform
       |-> GLES SDR model-input render + fused tensor-pack/color-cut pass (slot 0 or 1)
       |    (one stream-selected static depth-model aspect bucket)
       |    -> GPU color-cut flag for that exact model input
       |    -> input-ready fence -> native LiteRT 2.x / validated OpenCL precision
       |    -> packed Float32 GL depth buffer (same slot)
       `-> full-resolution matched color texture (same slot)
  -> adopt the completed exact color/depth pair and retain its color-slot lease
  -> GLES depth statistics + temporal/profile processing
  -> capture/enqueue the newest frame into the other slot
  -> depth prefilter + 1x-depth RG16F shared two-eye inverse-warp map
  -> full-resolution warp lookup + matched-color sample directly into the default framebuffer
  -> one EGL swap for the newly adopted pair
  -> SceneCore retains that submitted buffer until the next exact pair is adopted
```

Production offers Depth Anything V2 Small and MiDaS v2.1 Small as explicit model families. Each
family has three fixed-shape aspect buckets with FP16-stored large weights and Float32 public
tensors. When the renderer is constructed, it chooses the graph with the smallest multiplicative
aspect error, `abs(log(bucketAspect / streamAspect))`, directly among the selected family's three
buckets. The selection is immutable until the next stream.

| DA-V2 target aspect | Input/output size | Logical model | SHA-256 |
| --- | --- | --- | --- |
| 16:9 | `322 x 182` | `depth-anything-v2-small-static-322x182-fp16weights.tflite.model` | `82f8594f4ee615ab82f968aa461a3960c4cd680293fd087cb65d8631b18e4271` |
| 21:9 | `350 x 154` | `depth-anything-v2-small-static-350x154-fp16weights.tflite.model` | `2739f306ce71b19a913cdc32c779226a620f7f81685a1946ac213fdbeeba67b0` |
| 32:9 | `434 x 126` | `depth-anything-v2-small-static-434x126-fp16weights.tflite.model` | `353eb80fd6b9c6f97552a20b7bd29f79466a9b05287dcd4bfd93baaa4c1730f5` |

| MiDaS target aspect | Input/output size | Asset | SHA-256 |
| --- | --- | --- | --- |
| 16:9 | `352 x 192` | `midas-v2-small-static-352x192-fp16weights.tflite.model` | `2a3ee0a1e818c4f785bcd0ceb10f5c81f08b3b91304f2f15d113c1089d3e524e` |
| 21:9 | `384 x 160` | `midas-v2-small-static-384x160-fp16weights.tflite.model` | `5a66ab484a888c3c9e1642580ac3086c7d6d3175a860ca1e82f30d7a58c532bd` |
| 32:9 | `448 x 128` | `midas-v2-small-static-448x128-fp16weights.tflite.model` | `060ec0e16fd4e20f2626d6ac51d80853a1bdf9b2f082c3d933099784cf9cabfb` |

The non-root flavor packages the six models in two standard solid family archives:

- `app/src/nonRoot_game/assets/client-sbs-dav2-models.tar.xz`
- `app/src/nonRoot_game/assets/client-sbs-midas-models.tar.xz`

Each archive is a standard TAR containing its family's three complete `.tflite.model` files under
the exact logical filenames in the tables above. One XZ/LZMA2 stream compresses the complete TAR,
so the compressor can exploit redundancy across all three static graphs. There is no base/delta
encoding, XOR transform, custom model representation, or model reconstruction step. The Java model
manifest maps each logical model to its family archive and TAR entry and records its expected
SHA-256.

The deterministic DA-V2 TAR/XZ is 44,429,612 bytes (42.37 MiB), SHA-256
`3f9892624253e5d7301d6b0eb28acc7ef30ac2cf3131acbc7a8c1f59696ad148`. The MiDaS TAR/XZ is
29,947,928 bytes (28.56 MiB), SHA-256
`166be90ec3866dfeae61ce7163df49414840b6d054466d79dbe153ea3ebc8b94`. Together they are
74,377,540 bytes (70.93 MiB). Android stores the already-compressed XZ assets directly rather than
adding a second compression layer. Measure the current APK from the requested build output; its
total size is intentionally not pinned here because application code and build metadata change it.

On the first Client SBS activation in a stream session, the loader selects one model contract and
scans the family TAR/XZ stream,
writes only that complete TAR entry under `code_cache/client-sbs-model-assets`, verifies its
SHA-256, and gives LiteRT the verified read-only file. XZ decompression is sequential: selecting a
later TAR entry on a cold cache must decompress the preceding stream even though those earlier files
are not materialized. The verified cache avoids that work on later use. The loader prunes other
staged models, while a later stream may extract a different selection. The root flavor contains
neither family archive nor the LiteRT runtime.

The selected LiteRT model and compiled GPU delegate remain resident for that stream session after
the first Client SBS activation. Normal, Raw, and Host SBS submit no Client SBS inference work, but
retain the idle engine so returning to Client SBS has no model reload or compilation stall. Full
stream teardown closes it. A process-wide ownership guard permits at most one Client SBS model to
be compiling or GPU-resident, including during context recovery and deferred native teardown.

All six public contracts are packed Float32 NHWC RGB `[1, H, W, 3]` to packed Float32 BHWC depth
`[1, H, W, 1]` in shared GL buffers. DA-V2 dimensions are divisible by its 14-pixel patch size and
use 58,604, 53,900, and 54,684 pixels. Their patch grids are `23 x 13`, `25 x 11`, and `31 x 9`;
including CLS, they produce 300, 276, and 280 exactly C4-aligned tokens. Static specialization folds
the runtime shape graph, the transformer attention rewrite replaces rank-5 Q/K/V `GATHER` paths
with rank-4 operations, and every graph replaces each private 24-operation expanded GELU chain with
one builtin exact GELU. Each DA-V2 core contains 683 builtin-v1 operators; FP16 weight storage adds
82 constant `DEQUANTIZE` operators for 765 serialized operations. None has dynamic tensors or
Flex/custom operators.

MiDaS v2.1 Small uses an EfficientNet-Lite3 encoder and a four-level decoder refinement pyramid,
so both spatial dimensions must be divisible by 32 to keep every skip connection aligned. Its
three graphs specialize the verified Qualcomm Float32 model's input, output, and five decoder
resize targets, then use guarded FP16 storage for the large convolution weights. Small biases stay
Float32. They deliberately use `352 x 192`, `384 x 160`, and `448 x 128` rather than DA-V2's
14-aligned dimensions.

After model selection, both families use the identical native path: direct full-frame GLES resize,
packed Float32 GL input, LiteRT OpenCL inference, packed Float32 GL depth output, and GLES
depth/profile/reprojection. The packaged models insert `DEQUANTIZE` nodes between FP16-stored
weights and the unchanged Float32 graph contract; the GPU delegate can fold those constants into
its internal representation. Both use `AUTOMATIC_FP16` compute and report
`LITERT_OPENCL_FP16_GL_IO`. Neither family uses a CPU tensor copy or a separate inference backend.
MiDaS similarly grows from a 138-operation core to 234 serialized operators through 96 constant
dequantizations. Initialization still rejects any packaged graph that is not completely delegated.

This static path is the result of testing the intended single-model dynamic-shape design, not an
assumption about LiteRT. The LiteRT runtime exposes `LiteRtCompiledModelResizeInputTensor()`, and
CPU execution can resize the exact dynamic DA-V2 model. The current Android OpenCL and OpenGL GPU
delegates, however, advertise static-tensor support only. The normal compiled-GPU path rejected the
graph at its dynamic-tensor guard. Forcing the full-delegation hint past that guard delegated only
64 of 1,366 nodes before failing on unsupported `CAST` from `INT64`, `FILL`, and rank-5 `GATHER`
operations. Resizing and allocating before applying the classic GPU delegate failed for the same
underlying reason. A runtime resize API therefore does not make this graph dynamically delegable
on the current Galaxy XR GPU stack.

The allowed static fallback condition was therefore satisfied on the physical Galaxy XR. The
dynamic asset and the larger `350 x 196`, `392 x 168`, and `490 x 140` Quality graphs were removed
from production. Legacy dynamic, static-350, and Quality model IDs migrate to the canonical
three-bucket DA-V2 setting so an upgrade cannot remain pointed at a missing contract.

Every canonical DA-V2 graph delegates its 683-operation core in one OpenCL partition, passes the
edge-rich FP16-vs-FP32 full-output parity gate, and returns finite, non-flat depth. Idle-device,
thermal-status-0 FP16 means were 16.416 ms at `322 x 182`, 15.664 ms at `350 x 154`, and 15.830 ms
at `434 x 126`; corresponding transformed-FP32 means were 28.177 ms, 26.782 ms, and 26.920 ms.
FP16-versus-FP32 NRMSE was 0.008812, 0.003951, and 0.007030 with cosine at least 0.999968471.
Candidate FP32 versus its original FP32 graph had NRMSE 0.001675, 0.001440, and 0.000633 with
cosine at least 0.999999754.

The retired non-C4 Quality graphs remain historical bisect evidence: their original FP16 path
worked on the old smooth gradient but collapsed to the final learned bias on a modest edge-rich
input. The later tail-padding/GELU-corrected Quality graphs measured 18.724 ms at `350 x 196`,
18.439 ms at `392 x 168`, and 18.558 ms at `490 x 140`, but are no longer selectable or stored in
`client-sbs-dav2-models.tar.xz`. Their safe FP32 and scheduling/fence-tail measurements are not the
production baseline. The loose historical assets are not retained in the working tree or APK.
Their hashes and pinned
reproduction inputs remain documented; stage any regenerated copies only under the ignored client
`build/` tree or system temporary storage.

The naturally aligned canonical graphs were 12.33%, 15.05%, and 14.70% faster than those retired
16:9, 21:9, and 32:9 Quality baselines. Profiles recorded 438 model kernels for each graph:
`322 x 182` used 14.715 ms model / 16.395 ms total delegate work, `350 x 154` used 14.073 ms model
/ 0.946 ms upload/bind / 0.606 ms download / 15.625 ms total, and `434 x 126` used 14.118 ms model
/ 1.018 ms upload/bind / 0.345 ms download / 15.481 ms total. Direct full-frame resize into
`350 x 154` and `434 x 126` introduces -2.60% and -3.125% aspect distortion, respectively; these
trade-offs remain part of live visual acceptance even though the graphs are now canonical.

A decoder/head experiment kept the `350 x 196` input but produced exact `175 x 98` depth. The
generated graph, SHA-256
`1a0df67bd9d2b6524ae51649f7c332420f64fa4f9a8ebdb812c51eec9b553b26`, delegated 707/707 in one
partition and measured 18.050 ms FP16, 33.194 ms FP32, 462 model kernels / 16.154 ms, and 17.516 ms
total delegate work. FP16 versus FP32 was NRMSE 0.008534, maximum absolute error 0.056952, and
cosine 0.999967916. Despite profiling about 9.8 ms across the decoder/head's 1x1 and 3x3
convolutions, moving only the final full-resolution work saved about 0.67 ms. Edge-aware GLES
reconstruction would likely consume that saving, so neither this graph nor an upscaling renderer
path is promoted. More aggressive decoder pruning damaged depth quality and is rejected. Generated
experiment models remain under this client checkout's `build` or temporary storage, never Apollo-3D.

Recorded Galaxy XR validation also covers every rectangular MiDaS
graph: each delegates 138/138 OpenCL nodes in one partition, reports complete acceleration and
OpenCL/OpenGL interop, and returns finite, non-flat, repeatable output. LiteRT call-wall samples
with automatic internal storage were 16.84–21.06 ms for `352 x 192`, 15.68–16.44 ms for
`384 x 160`, and 15.31–16.27 ms for `448 x 128`. Corresponding invoke-to-output-ready ranges were
17.17–21.55 ms, 15.94–16.81 ms, and 15.59–16.74 ms. The previous 10.79–18.37 ms measurement belongs
only to the retired `256 x 256` graph and is historical.

Native initialization extracts and SHA-verifies the selected complete archive entry, verifies its fixed
tensor layouts, compiles it once, and allocates two GL input/output slot pairs from those layouts.
Per-frame code reuses that graph and those allocations; it must never extract, resize, or recompile
in the render loop. LiteRT performs its packed-to-internal conversion on the GPU. Production keeps
the public renderer contract packed
NHWC: a debug-only half4 external-buffer probe was slightly faster but reproducibly left output
pixels unwritten after a fresh refill, so it failed completeness/parity and remains rejected. Both
families use automatic internal OpenCL storage.
Their execution policies are included in the compiler-cache namespace. The
renderer and inference worker
exchange GL fences
across shared EGL contexts; model tensors are not mapped into Java or staged through CPU memory.
Complete one-partition OpenCL delegation is mandatory; partial delegation or CPU execution is
initialization failure. MiDaS v2.1 Small remains an explicit user-selected A/B family, not an
automatic failure fallback.

There is no production managed Java LiteRT interpreter, QNN/HTP delegate, CPU inference path, PBO
tensor readback, or Java depth-result worker. Native initialization requires full GPU delegation
and OpenCL/OpenGL interoperability. If that contract fails, Client SBS marks the backend
`Unavailable` and duplicates the mono image; it does not select another inference backend. The app,
Normal mode, and Host SBS modes must remain usable.

The inference worker owns native LiteRT creation, invocation, and destruction. Do not destroy the
engine from the renderer thread. Renderer-side failures must signal the owner thread to stop, while
releasing every frame-slot lease, inference claim, and GL fence exactly once.

## Matched color/depth scheduling

Client SBS deliberately behaves like Apollo's delayed host path: a depth result remains paired with
the exact captured color slot that produced it. Presentation may be delayed by inference, but a new
depth map must never warp a different color frame.

Scheduling is readiness-driven rather than timer-driven:

- Depth inference has no FPS ceiling, thermal cadence reduction, or forced post-inference idle
  interval. Android thermal status remains telemetry only.
- Surface callbacks may coalesce to the newest decoded frame. The GL thread continues draining and
  latching pending `SurfaceTexture` frames while inference is busy so the decoder consumer does not
  back up.
- A single-flight inference claim prevents the worker queue from growing.
- There are exactly two native input/output tensor slots and exactly two matching full-resolution
  color slots. A capture, its public LiteRT tensors, and its eventual depth result always use the
  same slot index.
- The two slots allow one exact color/depth pair to remain active while the newest uncaptured frame
  is captured and inferred. LiteRT invocation itself remains single-flight.
- The renderer submits the model-input fence before the full-resolution color copy. That lets the
  worker start the same-slot inference while the renderer finishes capturing the matched color,
  without weakening their shared lease/generation identity.
- After result N becomes ready, the renderer makes color N active while explicitly invalidating
  depth, releases the superseded active slot, and captures/enqueues frame N+1 into that slot before
  submitting N's depth/profile work. Per-slot GPU scene-cut words preserve N's evidence while the
  detector advances N+1. The renderer then publishes depth N and prefilters, warps, and submits the
  exact pair. This overlaps OpenCL inference N+1 with both depth postprocessing and compose N
  instead of creating a post-swap inference bubble or pairing color N with depth N-1.
- Shared-context fences order input production, inference output, and GPU postprocessing without
  blocking the GL thread on normal operation.
- Each newly adopted exact pair is processed and rendered directly into the EGL default framebuffer
  once, followed by one swap. There is no packed SBS offscreen texture or repeated packed-frame
  blit.
- Once at least one matched output has been submitted, callback-driven drains that do not adopt a
  newer pair perform no SBS draw and no EGL swap. SceneCore retains and presents its last submitted
  buffer.
- The active exact color-slot lease remains owned by the renderer until the next exact pair is
  adopted. The newer pair takes ownership before the superseded lease is released; no offscreen SBS
  cache takes ownership of the rendered pixels.

Busy claims, occupied mailboxes, or unavailable color slots drop capture opportunities while the
last submitted output remains retained; they must not create an unbounded queue or detach depth
from color.

Fence ownership is part of the slot contract:

1. The renderer packs model input, then transfers a nonzero input-ready fence and any nonzero
   previous output-consumed fence for the same slot to native code.
2. Native GPU-waits and deletes each nonzero transferred fence, invokes LiteRT for that slot, and
   returns a new output-ready fence owned by the caller.
3. The renderer polls that fence without blocking. After it dispatches all reads of the output
   buffer, it creates a new output-consumed fence for the slot. An unread/discarded output may reuse
   the output-ready fence because no renderer read was submitted.
4. Slot reuse transfers that consumed fence back to native. Shutdown transfers the newest final
   consumer fence for both slots to the inference worker for bounded teardown.

Never delete a transferred fence twice, reuse a slot without its consumer fence, or close the native
engine from the renderer thread.

## Fixed Client SBS profile

Client SBS and Host SBS AI expose no manual depth-tuning parameters. Client normalization,
convergence, and pop compensation are adaptive GPU state in `ClientSbsGpuDepthProcessor`.
Reprojection mirrors the host's fixed legacy zero plane: half of the tracked subject shift is used
as the anchor and the host convergence bias is retained. Do not reintroduce the removed strength,
convergence, balance, movie-mode, zero-plane, or legacy shader parameters.

The depth model never runs on the full decoded frame. DA-V2 directly bilinear-resizes the entire
frame to the selected canonical rectangle (`322 x 182`, `350 x 154`, or `434 x 126`); MiDaS does the same for
its selected `352 x 192`, `384 x 160`, or `448 x 128` rectangle. Neither family crops, adds square
padding, or uses a reflected border. The matched color texture remains at the client-requested `W x
H` output resolution for reprojection.

The preferred compose path solves the Bestv2 inverse field once per newly adopted depth/profile
pair. The probe count is compiled once from the stream aspect: 32 for 16:9, 24 for 21:9, and 16 for
32:9. These are the same
aspect-scaled budgets used by Apollo,
without a per-frame branch. The pass stores small signed left- and right-eye source displacements in
red and green of an `RG16F` map at the source-aligned depth resolution. Signed displacements retain
more useful half-float precision than absolute normalized source coordinates, while linear sampling
reconstructs the field at presentation resolution. Final output uses one full-width packed-SBS draw.
The shader applies a half-open split at packed X `0.5`, derives an eye-local X without `fract()`
wrapping, selects the matching RG channel, and performs one warp-map lookup plus one
request-resolution matched-color sample per output pixel. The `RG16F` warp map is the only reusable
compose intermediate; there is no reusable packed SBS image. If the driver cannot compile, render,
or sample that RG16F target, the renderer may use the equivalent direct GLES shader with the same
stream-fixed probe count and two half-width draws as a render-compatibility path. That path is not a
model, managed-runtime, or CPU inference fallback. Logs and the `Stereo compose path` stats row
identify `RG16F 1x-depth warp map, packed single draw (N-probe)` versus `Direct GLES N-probe` so
performance comparisons are not mixed.

## Scene cuts and depth health

Scene-cut detection is GPU-only and paired with the exact SDR model-input frame. GLES reduces the
selected rectangular input through bounds-safe 16 x 16 workgroups to persistent integer-luma
history and combines spatially broad, mean-compensated structural change with coarse histogram
change. Its grids and GL resources are derived once from the selected stream shape. It writes one
uint32 cut word to an SSBO; the following depth/profile dispatch consumes that word directly on the
same GL queue. No per-frame flag crosses through Java or CPU memory.

The depth processor also derives cut evidence from depth change. NaN, infinity, and finite negative
model values are excluded from statistics, and invalid pixels retain the previous valid temporal
depth rather than poisoning the profile. Hard color cuts and strong depth evidence reset temporal
history promptly; ordinary object motion should not.

Depth-health stats are diagnostics, not part of inference or reprojection. The renderer samples the
GPU profile state through a nonblocking readback only while Stats or explicit performance logging is
active. The lean health summary exposes valid-depth fraction, effective range width, pop strength,
and whether the range is collapsed. A temporarily missing health sample must not stall or disable
depth.

## Surface and lifecycle ownership

The central contract is:

> Whoever owns presentation supplies the current `Surface` to
> `MediaCodecDecoderRenderer.setRenderTarget()`.

Mode switches are guarded asynchronous surface handoffs. Keep the decoder target, SceneCore surface
size/stereo mode, renderer generation, and entity visibility synchronized. A stale callback from a
previous generation must not retarget the decoder or publish a depth result.

Crossing Client SBS ownership or the live Host SBS AI packed-size boundary changes the decoder
target or encoded dimensions. Before those transitions, close the compressed-frame gate and flush
MediaCodec through its all-thread recovery barrier. After the replacement surface is bound, request
a new IDR and admit only a serial-newer IDR before reopening the gate. Raw SBS does not use this live
path: every transition across its `2W x H` transport boundary reconnects first.

On destroy or mode exit:

- Stop new Client SBS captures.
- Let the inference owner thread close native LiteRT.
- Perform terminal worker joining and retained-engine close retries on a background coordinator;
  never block the Activity/UI thread on `awaitTermination()` or a cleanup-thread join. Post the
  dependent SceneCore/surface release and reconnect callback to main only after native cleanup
  succeeds.
- Release pending and active color leases and delete owned fences/GL resources.
- Detach/release the old decoder surface only after the replacement target is ready.
- Cancel delayed render retries so they cannot resurrect a destroyed renderer.

## Reconnect and saved-view contract

Apollo's `/serverinfo` response is the authority for whether a stream session exists. Artemis does
not duplicate Apollo's disconnect grace period with a client timer.

- A genuinely new host session starts in **Normal** and inherits Global Settings.
- Resuming the same host session/app, including the in-place restart after **Apply & reconnect**,
  starts with the last successfully applied presentation mode and that mode's saved stream-quality
  tuple. A live mode switch becomes durable only after its surface handoff (and transition IDR when
  required) succeeds.
- Panel height is durable per machine and is restored independently of presentation mode.
- Apply snapshots the live quad before SceneCore teardown and transiently hands its effective
  height plus real-world pose to the replacement Activity. This preserves both physical size and
  apparent size from the user's chosen distance; pose is not made a durable cross-session setting.
- Transport, authentication, and pre-frame startup failures preserve the last successful mode;
  fresh launches still start Normal, and only a host-confirmed resume restores it.

The host's current running-app identity must travel explicitly through the Game intent; elapsed
client time is not a resume decision.

Artemis stores exactly one current-session record per PC. A new host app replaces that record;
resuming the same host app preserves it. The record contains shared stream overrides, per-mode
overrides, the last proven presentation mode, and a local generation ID that rejects stale panel
writes. Global Settings remain the inheritance source across PCs and sessions; a current-session
override is stored only while it differs from its global value.

Each of the four presentation modes owns an independent stream-quality tuple: **resolution, frame
rate, and bitrate**. Changing one mode's tuple never changes another mode. After a presentation
handoff succeeds, selecting a mode whose saved or newly staged tuple differs from the tuple backing
the live decoder automatically commits the complete staged session record and reconnects into the
selected tuple. Committing the whole record ensures that shared or other-mode edits cannot be lost
when the Activity is recreated. A same-tuple switch among non-Raw modes remains live; entering or
leaving Raw always reconnects because its logical `W x H` tuple maps to a `2W x H` transport.
**Apply & reconnect** remains the explicit action when no mode-quality or transport change already
requires a restart. The Client SBS model is also mode-specific, and its aspect bucket is derived
from the pending Client SBS resolution.

The settings truly shared by all four modes are **codec, video frame pacing, HDR, Full/Limited video
range, audio layout, and play audio on the host PC**. The Session Settings pane edits only this
shared set. Global Settings provide the cross-session defaults for both the shared set and the
quality baseline inherited independently by each mode.

Fresh installs and reset modes use the verified Galaxy XR baseline: **3840 x 2160 at 90 FPS,
130 Mbps, HEVC, HDR, Full range, and latency pacing**, with stereo audio, host audio off, and
Depth Anything V2 as the Client SBS model. Existing explicit global or per-session choices remain
unchanged.

**Apply & reconnect** commits every staged shared setting, every per-mode quality tuple, the Client
SBS model, and the selected startup mode as one guarded record replacement. It then waits for
decoder and deferred GPU/XR cleanup before recreating the singleTask `Game` activity in place. The
old Activity's ordinary no-history stop path must not finish this intentional replacement, so the
stream resumes immediately instead of exposing the application grid. A stale panel generation
cannot write into a replacement session. Legacy records that stored quality as shared values are
read compatibly and are expanded into all four mode scopes on the next atomic commit.

## HDR and color range

Normal and Host SBS are direct decoder paths. Leave the `SurfaceEntity` content color metadata
unset so SceneCore consumes the decoded `HardwareBuffer` dataspace, HDR transfer, and source range.

Client SBS is a new RGB producer after OES sampling. Apply the `SurfaceTexture` transform matrix to
all OES samples so crop/orientation metadata is identical for the model input and matched color.
The rectangular model input is always SDR and tonemaps PQ before DA-V2 or MiDaS inference. That does
not require the full-resolution presentation path to become SDR.

HDR presentation is negotiated and verified at runtime:

- Prefer a 10/10/10/2 EGL window, with an 8/8/8/8 window as the supported SDR choice.
- Verify the actual default-framebuffer channel precision; do not infer it from the requested EGL
  config.
- For the two matched-color targets, try framebuffer-complete `RGB10_A2`, then `RGBA16F` when
  renderable, then `RGBA8`.
- Advertise BT.2020/ST2084/FULL to SceneCore only when the stream is HDR, the actual window is
  10-bit, and the selected matched-color targets retain HDR precision end to end.
- Otherwise tonemap the presentation to BT.709/sRGB/FULL. SDR input uses BT.709/SDR/FULL.

An SDR/PQ change in Client SBS is a guarded frame boundary, not an immediate global shader toggle.
Hide the video entity, invalidate old-transfer color/depth work, gate decoder input/output to a
fresh IDR, install the target SceneCore metadata while hidden, and reveal only after GL swaps its
first new-transfer packed output. Direct modes continue to follow per-buffer MediaCodec metadata.

Reusing the source YUV limited/full flag would apply range interpretation twice after OES has
already produced normalized RGB. Clear explicit Client SBS metadata before returning to a direct
mode so SceneCore again follows the decoded `HardwareBuffer` metadata.

Force output alpha to one. External-OES video may sample with alpha zero, which otherwise makes the
SceneCore quad transparent or black.

## In-headset controls and stats

### Single-PC home

Optimize Home for the usual one-PC LAN. Exactly one discovered PC uses a centered 760 x 250 dp hero
card whose whole surface opens that PC's application library. The card exposes only useful session
context: connection state, the active LAN address, the headset's negotiated Wi-Fi download/upload
link rates, virtual-display readiness, current-session readiness, and a short next-action cue. Wake
or Pair remains an explicit primary action when needed;
secondary machine actions stay behind the compact `+`. If a second PC appears, Home automatically
falls back to the compact multi-machine grid.

### Spatial control layout

A passive glance strip sits above the video and never intercepts input. It keeps the PC/application
identity, active presentation mode, live stream tuple, and reconnect/status cue visible without
requiring a pane. The main dock remains level at its fixed pose beneath the video. Opening or closing
another surface must not move that dock.

The contextual mode panel is anchored directly below the dock and pitches upward toward the
viewer while leaving the dock pose unchanged. Session Settings opens to the **left** of the video;
its inner edge remains anchored outside the video and the panel yaws inward toward the viewer's
face. **Stats** uses the **right** side as a compact, single-column panel whose
inner edge is anchored just beyond the video's right edge. It yaws inward around local Y so its
outer edge wraps toward the current head position, with a clearance limit preventing it from
approaching the viewer too closely. Recompute side-panel poses when they open, on video resize/mode
change, after screen movement/Cinema View, and on the existing slow Stats refresh. Never poll head
pose from the video frame loop or while the associated side panel is hidden.

Presentation modes form one single-select group. Navigation/disconnect actions remain separate
one-shot controls. A new session highlights Normal; a resumed/restarted session highlights its
restored mode only after that mode is actually active.

The four mode tiles live in one level toolbar `PanelEntity` and share one contextual
`PanelEntity` directly beneath it. An inactive mode tile switches modes on its first tap; tapping
the active tile again toggles that mode's row. A passive down/up chevron with a conventional aspect
ratio sits centered against the lower edge of the tile and communicates the expandable state
without a small nested "Options" target. It does not change the fixed dock/tile geometry. Every
mode row owns that mode's
resolution/FPS/bitrate tuple. Resolution uses visual cards, FPS uses a compact segmented control,
and bitrate uses a bandwidth meter, discrete slider, exact value, and direct minus/plus steps. The
row identifies Global versus Current Session inheritance, shows the tuple currently backing the
live decoder, and offers the same atomic **Apply & reconnect** action whenever any scoped change
requires it.

Normal and Host SBS rows also show their presentation/source status. Client SBS adds only its model
choice, resolution-derived fixed aspect bucket, and live GPU backend status; it has no strength,
convergence, balance, movie-mode, or depth-inference cadence controls. Restoring global defaults is
scoped: the shared pane resets only shared values, while a mode row resets only that mode's quality
tuple (and the Client SBS model for the Client row).

The Settings tile opens the left side panel for values shared by every mode in the current PC
session. Its six controls use two short semantic columns: Video (HDR, range, codec) and Delivery
(pacing, audio layout, host audio), with large XR-readable labels, choice targets, and status text.
Each setting is a distinct raised card under a strong semantic heading; mode options likewise group
resolution, motion, bandwidth, live state, and Client SBS depth details into visually separate
surfaces rather than one undifferentiated row.

Keep the four modes, Settings, Cinema, Library, Stats, and a compact half-width `+` in the permanent
dock. Library returns directly to the current PC's application library without an intermediate
machine-selection step, and Stats is a direct one-tap toggle; Stats visibility
is independent, so it stays open while left-side Settings or the lower mode row opens. The `+`
widens the same dock inline to reveal **Dump 3D** and **End session**, then becomes `-`; tapping `-`
hides those secondary tiles and restores the compact width. The right edge stays anchored so the
`+`/`-` gaze target never moves and a newly revealed destructive action can never replace it under
the pointer. Expansion never opens another pane or
masks Stats. The Stats choice is persisted so an in-place
reconnect or activity recreation cannot silently clear it. All controls remain ordinary clickable
Android `View`s grouped within their respective panel; never create one entity per control.

Enum values in both Global Settings and the current-session panel are ordinary buttons in one
connected segmented surface, not radio dialogs or cycle-only rows. Compact choices use equal-width,
single-line horizontal segments with an 80 dp minimum gaze-target height. If every localized label
cannot fit, the entire control becomes a
full-width connected vertical stack with up to two lines per choice; never produce a ragged wrap or
make the user scroll an enum sideways. Numeric values use an inline slider with direct step buttons.
The Client SBS model is selected from the same kind of two-button group inside its existing Options
row, without opening another panel. Tapping the running application card resumes it directly; a
compact close button in its top-right corner ends the session. More stays in the bottom-right and is
reserved for secondary actions such as details, hiding, and shortcut/export tools. The compact card
aspect fits one complete row inside the Galaxy XR library viewport even while the current-session
banner is visible, so a single row never creates a pointless vertical scroll range.

After the first decoded frame, the dock may **soft-collapse** after eight seconds of true idle. This
does not disable or move the dock `PanelEntity`: it hides only the full control row, dims the passive
glance strip, and leaves a centered, gazeable reveal pill showing the active mode and current status.
Hovering, focusing, or activating a full-dock control reveals the row and restarts the timer. While
collapsed, passive hover alone keeps the pill stable; the first focus/press generated by an explicit
pinch reveals the row (with click activation retained for keyboard/controller input), so a newly
exposed mode tile can never replace the target beneath the pointer or require a second pinch.

Auto-collapse is allowed only while session controls are enabled, no dock child is hovered or
focused, secondary session tools are collapsed, no Settings/Stats/mode-options pane is open, no reconnect-required change is pending,
and no mode switch, decoder handoff/IDR gate, or depth-engine transition is active. If any guard
becomes active, cancel the timer and keep the full dock visible so work and Apply actions cannot be
hidden. This is a soft visibility policy only; it must not alter the dock pose or presentation mode.

### Stats content and telemetry

The visible pane is a lean optimization summary: negotiated codec/profile, exact Android decoder
component, whether that component is dedicated to low latency, whether low-latency format options
were requested, the separate Artemis output-pacing policy, sender / receive FPS, decoder output /
release / surface FPS, network and host/decode latency, app CPU as core-equivalent load, device GPU
busy/clock, and Android thermal status. Client SBS adds the selected model/backend/input shape,
latch/depth/output FPS, LiteRT call-wall average/maximum, matched depth-age average/maximum, and four
separately labelled true GL GPU averages: model-input resize/color-cut/pack, matched-color copy,
depth normalization/profile, and stereo prefilter/warp/draw. The device GPU percentage remains a
system-wide total: GL stages can overlap each other and OpenCL, so their durations must not be
summed into a synthetic utilization percentage. Show XR composition as unavailable because
SceneCore exposes no compositor timing. The only exceptional counters are occupied color slots
(`color_busy`) and flat SBS outputs (`flat`). Depth health is intentionally limited to valid
fraction, effective range width, pop strength, and collapsed-range state.

The depth policy row must say `Uncapped | one in flight | newest frame when free`; Android thermal
status is reported separately so it is not mistaken for a hidden throttle. Do not add expected
latest-frame skips, callback coalescing, retained-output drains, or ordinary single-flight busy
events as counters: those are normal scheduling behavior rather than faults.

Surface callbacks and GL latches may follow the decoded stream while inference, exact-pair
adoption, SBS composition, and EGL swaps run at the lower workload-limited depth cadence. This is
not an FPS cap. After the first valid pair, that difference is expected: a drain with no adoption
retains the SceneCore buffer instead of submitting duplicate pixels. New-pair composition and swap
cadences should remain approximately one-to-one with adoption; repeated output blits or swaps at the
latch rate are a regression.

Do not show managed/PBO/free-CPU-buffer/result-worker stages, CPU command-submission/invoke/
dependency timings, or custom CPU/GPU temperature probes. Android 14 has no trustworthy public
per-app NPU-utilization API, so the pane does not probe or display NPU usage. The active Client SBS
backend is the GPU; Android thermal status comes from the platform thermal API.

Keep timing domains explicit. `LiteRT run call wall (not pure GPU)` brackets
`LiteRtRunCompiledModel()` and includes runtime overhead plus any blocking visible to that API;
LiteRT does not expose the OpenCL event needed to isolate accelerator execution. Matched depth age is
an end-to-end freshness measurement, not a GPU timer.

Actual GLES completion timing is backed by nonblocking `GL_EXT_disjoint_timer_query` rings. It
reports averages only for model render + color cut + pack, matched-color copy, depth
normalization/profile, and depth blur + packed single-draw SBS warp. Poll availability rather than
waiting, discard samples from disjoint clock intervals, and show the timers as unavailable when the
driver cannot provide reliable queries. GLES queries cannot bracket LiteRT's OpenCL work, and
SceneCore exposes no final compositor-present timestamp. Record the active warp path alongside these
timings; full-resolution compatibility results are not comparable to the RG16F warp-map path.

Performance logging is enabled by default and can be disabled in preferences. While enabled, the
typed window is written to logcat at approximately the two-second stats cadence, never per frame.
Normal and Host SBS write one
`DecoderPerf` line with sender sequence, receive, decoder output, release, surface-presentation, and
decode latency. Client SBS writes one consolidated `ClientSbsPerf` line with those same stream
boundaries plus the selected model/backend/input, latch/depth/output FPS, LiteRT wall
average/maximum, depth-age average/maximum, the four GLES completion averages, exceptional
`color_busy`/`flat` counts, minimal depth health, app CPU core-equivalent load, GPU busy/clock, and
Android thermal status. The former five-second renderer debug lines and separate depth line are
intentionally omitted to avoid duplicate logging. Use these lines for repeatable A/B captures; they
add no per-frame logging or GPU synchronization.
When Stats is hidden and explicit performance logging is disabled, Client SBS disables timer
queries plus the health-readback copy producer and poller, bypasses its detailed synchronized/atomic
counter updates, and skips typed stats-table/log formatting. Opening Stats
resets the CPU and Client-SBS sampling baselines; timer and health-readback state are created/reset
only on the renderer thread with its EGL context current, so in-flight diagnostics never cross a
visibility window.

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
- Host depth-preparation status is a transient `PanelEntity` centered on the video and offset
  slightly toward the viewer. Phase 1 means process-wide engine preparation; phase 3 means
  per-stream GPU-pipeline setup for the already resident model.
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
target application afterward and erases global defaults, current-session settings, certificates,
and pairings. Install
the main and test APKs with `adb install -r`, invoke instrumentation manually, and uninstall only
`com.limelight.noirdebug.test`.

For every mode/surface change, test:

- A new session starts Normal with inherited global defaults; host-confirmed resume and the
  Apply-triggered restart restore the last successful mode with that mode's saved quality tuple.
- Stage distinct resolution/FPS/bitrate tuples for all four modes and confirm they remain isolated.
  A successfully selected mode whose tuple differs from the live decoder must reconnect into that
  tuple automatically. Same-tuple non-Raw switches stay live, while every transition into or out
  of Raw reconnects and negotiates the exact `2W x H` transport. Any other staged edits must be
  committed in the same atomic record before that automatic Activity recreation.
- Normal and Host SBS remain direct and work when Client SBS initialization fails.
- Test all six selectable models separately on Galaxy XR: the three canonical DA-V2 entries from
  `client-sbs-dav2-models.tar.xz` and the three MiDaS entries from
  `client-sbs-midas-models.tar.xz`. Both
  families must report
  `LITERT_OPENCL_FP16_GL_IO`. The smoke test enforces
  `LiteRtCompiledModelIsFullyAccelerated() == true`; confirm exactly one OpenCL partition from the
  accompanying LiteRT delegate log, together with OpenCL/OpenGL interoperability, the expected
  fixed tensor layouts, and finite non-flat depth. Every DA-V2 graph must retain 683/683 core
  coverage; record the actual delegated/total
  count for each MiDaS graph rather than assuming it. Record
  archive extraction/SHA verification, compile/init, and warm invocation latency for every
  logical model.
- DA-V2 acceptance requires the edge-rich CPU-golden fixture; the old smooth gradient is not a
  sufficient FP16 correctness gate. Current FP16-stored-weight isolated means are 16.552 ms at
  `322 x 182`, 15.725 ms at `350 x 154`, and 15.864 ms at `434 x 126`; compare full output against
  FP32, not just range or a few sampled pixels. Treat partial
  delegation, another partition count, CPU execution, CPU-golden mismatch, zero/flat output, or
  loss of CL/GL interop as failure.
- All three rectangular FP16-stored MiDaS buckets have passed the physical-device graph gate as
  complete 234-operation graphs in one OpenCL partition with CL/GL interop and repeatable non-flat
  output. Controlled LiteRT median/p95 times are 10.293/10.473 ms for `352 x 192`,
  9.703/9.872 ms for `384 x 160`, and 9.430/9.593 ms for `448 x 128`. These are not end-to-end
  stream latencies; live visual and sustained-cadence testing remains required.
- Exercise representative 16:9, 21:9, and 32:9 streams and bucket boundaries for both model
  families. Confirm the nearest bucket is selected directly within the active setting and that
  LiteRT is not recreated during a stable stream.
- A/B DA-V2 and MiDaS on the same moving 16:9, 21:9, and 32:9 content. Record exact-output cadence,
  thermals, depth-detail/pop, and visible geometry. DA-V2's 21:9 and 32:9 buckets carry -2.60% and
  -3.125% direct-resize aspect distortion, so live visual acceptance remains required.
- Left/right eyes are not swapped and the packed split is centered exactly.
- HDR input shows either verified preserved HDR with a high-precision target or the explicit
  BT.709/sRGB tonemap path; SDR shows BT.709/SDR. Direct modes clear Client SBS metadata.
- A hard edit resets temporal depth without a long-lived flat frame; ordinary motion does not
  continuously trigger cuts.
- The four GL GPU averages receive non-disjoint samples without stalling and remain distinct from
  LiteRT call-wall latency.
- The glance strip remains passive; the level dock does not move when the upward-pitched mode pane,
  inward-yawed left Settings pane, or wrapped right Stats pane opens. Expanding secondary session
  tools changes only the dock width.
- After eight idle seconds the dock leaves its reveal/status pill, then expands on the first
  explicit press/pinch.
  It must remain expanded while a pane, inline session tools, pending Apply, depth preparation,
  mode/decoder transition, or focused/hovered control is active.
- Repeated disconnect/resume/mode switches do not leak surfaces, entities, EGL contexts, leases, or
  fences and do not recreate LiteRT during a stable stream.
