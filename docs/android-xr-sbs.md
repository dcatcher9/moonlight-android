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
- **Host SBS Raw** treats its selected `W x H` resolution as the intended view aspect. Its
  **Per-Eye Resolution** choice defaults to **Full**, which negotiates an untouched `2W x H`
  virtual desktop/stream and preserves `W x H` encoded pixels per eye. **Half** keeps the packed
  desktop/stream at `W x H`, so each encoded eye is `W/2 x H` and SceneCore presents that same
  source on a matching `W/(2H)` physical quad to preserve per-eye proportions. Both decode directly
  into the same entity with `StereoMode.SIDE_BY_SIDE`. Raw is available only for a
  virtual-display-backed launch, including Apollo's generated **Virtual Display** entry; a physical
  desktop would only be aspect-fitted into the encoder surface rather than rendered at the selected
  geometry. The exact packed width must remain within the 8192-pixel HEVC/AV1 transport limit.
- **Host SBS AI** uses the same direct stereo surface path; Apollo performs depth inference and SBS
  synthesis before encoding. This mode is enabled only when the host advertises the Apollo-3D
  session/control extension; it is disabled on regular Sunshine and Apollo hosts.
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

Regular Sunshine and Apollo are first-class compatibility hosts for **Normal** and **Client SBS
AI**. **Host SBS Raw** is additionally available when that launch is explicitly backed by a
virtual display. Apollo-3D-only controls (Host SBS AI, host depth telemetry/debug dump, and live
video-mode changes) must be capability-gated and must never be sent speculatively to a standard
host.

Client near-identical reuse is fully local and adds no `serverinfo`, launch, RTSP, or control
message. It works unchanged with original Sunshine and Apollo. If host-assisted exact/damage reuse
is pursued later, it must be a distinct versioned capability advertised by the host; support must
never be inferred from `hostsessionid` or another unrelated extension. When that capability is
absent, malformed, or stale, Client SBS must continue with local decoded-pixel arbitration or full
inference without rejecting the session.

Only Raw Full owns a distinct negotiated transport: its `2W x H` stream requires a reconnect when
entering or leaving that transport, and changing Full/Half while Raw is live likewise reconnects.
Raw Half is the ordinary `W x H`, `sbs_mode 0` wire stream, so entering or leaving Raw Half is a
pure SceneCore presentation change when the selected quality tuple fits the live decoder envelope.
This prevents Client AI from consuming an already-packed Raw Full frame and prevents Host AI from
repacking it. Live transitions among Normal, Raw Half, Host SBS AI, and Client SBS AI retain their
guarded surface-handoff behavior. After a live
`setOutputSurface()` handoff, reapply the requested surface frame rate because that metadata belongs
to the replacement `Surface`. Artemis rejects Raw on a physical-capture session and directs the user
to relaunch Apollo's Virtual Display instead of pretending that a wide aspect-fit is native Raw
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
       |-> GLES SDR exact-area model-input render + fused tensor-pack/color-cut pass (slot 0 or 1)
       |    (one stream-selected static depth-model aspect bucket)
       |    -> GPU color-cut flag + client-local integrity-checked near-identical decision
       |    -> input-ready fence -> native arbitration
       |         |-> infer: LiteRT 2.x / validated OpenCL -> packed Float32 depth (same slot)
       |         `-> reuse: skip LiteRT and retain the last real depth/profile/warp
       `-> full-resolution matched color texture (same slot)
  -> adopt current color and retain its color-slot lease
  -> infer only: GLES raw mean + private P2/P98 cut analysis + coherent history commit
  -> reuse only: freeze every depth-derived and comparison-history field
  -> infer only: source-aligned raw R32F ZipDepth -> per-graph coordinate
  -> host V2 far/linear/near curve + fixed pop 1.75
  -> exact +/-0.04 fourth-root container
  -> vertical 2/W upper/lower envelopes with 0.75/0.25 share
  -> horizontal 0.5/W least majorant
  -> host-exact at-most-11-step fixed-point inverse in a 1x-depth RG16F two-eye seed map
  -> one fixed-point correction in a 2x-horizontal x 1x-depth RG16F refined cache
  -> full-resolution refined-warp lookup + matched-color sample directly into the default framebuffer
  -> one EGL swap for the newly adopted result
  -> SceneCore retains that submitted buffer until the next adoption

Any invalid raw field or conditioner, seed-map, refinement, or compose failure presents current
color flat; the live renderer has no seed-only or alternate Bestv2/probe geometry path.
```

Production uses original ZipDepth Base as the single Client SBS model family. It has three
fixed-shape short-side-384 aspect graphs with FP16-stored large weights and Float32 public tensors.
When the renderer is constructed, it chooses the graph with the smallest multiplicative aspect
error, `abs(log(bucketAspect / streamAspect))`. The graph is immutable until the next stream; this
is aspect routing within one model family, not a user-selectable model choice.

| ZipDepth target | Input/output size | Asset | SHA-256 |
| --- | --- | --- | --- |
| 16:9-nearest | `672 x 384` | `zipdepth-base-static-672x384-fp16weights.tflite.model` | `6296d5c2e4f857fd551d854ebf4dd2ab2462c0d7372d526bf0a7463718b8b6d1` |
| 21:9-nearest | `896 x 384` | `zipdepth-base-static-896x384-fp16weights.tflite.model` | `31467ab0cd187b74c65b3b20f4850973309d120519b587610e3dd3e27b72df4a` |
| ultrawide-nearest | `928 x 384` | `zipdepth-base-static-928x384-fp16weights.tflite.model` | `169d5e8802bea9aac839df6acb4a8dd8e92a53728ea6f4e39a4baca453fd34cc` |

The non-root flavor packages the three fixed-shape graphs in one standard solid family archive:

- `app/src/nonRoot_game/assets/client-sbs-zipdepth-models.tar.xz`

The archive is a standard TAR containing complete `.tflite.model` files under the exact logical
filenames in the table above. One XZ/LZMA2 stream compresses the complete TAR so the compressor can
exploit redundancy across the three static graphs. There is no base/delta encoding, XOR transform,
custom model representation, or model reconstruction step. The Java manifest maps each graph to
its TAR entry and records its expected SHA-256.

The deterministic TAR/XZ is 11,149,420 bytes (10.63 MiB), SHA-256
`0b737e7ff7d6717c9b376e2e6d195eb5ff4a54d49d862e3415f155d137c78558`. Android stores the
already-compressed XZ asset directly rather than adding a second compression layer. Retired DA-V2,
MiDaS, and DepthART archives remain outside Android source sets under
`tools/model-sources/retired-client-sbs-archives/`; they are not packaged in any APK.

As soon as the stream's renderer chooses its immutable aspect contract, a low-priority background
thread begins a CPU-only pre-stage: it scans the ZipDepth TAR/XZ stream, writes only that complete
TAR entry under `code_cache/client-sbs-model-assets`, and verifies its SHA-256. This helper is
separate from the native engine and loads no JNI/LiteRT library, creates no EGL context, and submits
no GPU work. Failure is nonfatal so the root flavor, which contains neither the ZipDepth archive nor
the LiteRT runtime, keeps its normal direct modes unchanged. Speculative staging does not prune a
different aspect bucket. Authoritative first-use initialization takes the same process lock,
revalidates a speculatively staged file once before trusting it, prunes other staged graphs, and
gives LiteRT the verified read-only file. Later authoritative reuse avoids repeating that digest.
XZ decompression is sequential: selecting a later TAR entry on a cold cache must
decompress the preceding stream even though those earlier files are not materialized. The verified
cache avoids that work on later use.

The selected LiteRT model and compiled GPU delegate remain resident for that stream session after
the first Client SBS activation. Normal, Raw, and Host SBS submit no Client SBS inference work, but
retain the idle engine so returning to Client SBS has no model reload or compilation stall. Full
stream teardown closes it. A process-wide ownership guard permits at most one Client SBS graph to
be compiling or GPU-resident, including during context recovery and deferred native teardown.

All three public contracts are packed Float32 NHWC RGB `[1, H, W, 3]` to packed Float32 BHWC depth
`[1, H, W, 1]` in shared GL buffers. ZipDepth uses the original `base` checkpoint and learned
convex upsampler, not `base_npu`. Its exact tail is lowered to standard operators, one grouped
convolution is densified, and its global-context
weighted reduction uses an algebraically equivalent 1024x/1024x scale to avoid Adreno FP16
flush-to-zero. Every graph has 163 operations. Embedded ImageNet normalization consumes the shared
raw `[0,1]` RGB input, and the output is nonnegative high-is-near relative inverse depth.

All three aspect graphs use the same native path. Downsampling integrates the exact source-cell
overlap of each model texel; when either axis is genuinely upscaled it uses pixel-center bilinear
sampling instead. Portrait aspect-fit resolves reflected padding per source cell. HDR conversion is
applied before spatial integration. The model-input render remains an RGBA8 staging texture before
packed Float32 GL input, LiteRT OpenCL
inference, packed Float32 GL depth output, and GLES depth/profile/reprojection. The packaged graphs
insert `DEQUANTIZE` nodes between FP16-stored
weights and the unchanged Float32 graph contract; the GPU delegate can fold those constants into
its internal representation. They use `AUTOMATIC_FP16` compute and report
`LITERT_OPENCL_FP16_GL_IO`. There is no CPU tensor copy or alternate inference backend.
Initialization rejects any packaged graph that is not completely delegated.

The static aspect-bucket design is based on physical Galaxy XR testing: the current Android GPU
delegates require static tensor shapes for complete acceleration. The earlier DA-V2, MiDaS, and
DepthART model-selection work, including rejected dynamic-shape and half-resolution experiments,
remains available as historical evidence in `docs/client-sbs-evaluation.md`,
`docs/client-sbs-dav2-fp16-bisect.md`, and `tools/model-sources/README.md`. Their archived model
families are not selectable, are not fallback backends, and are not present in the APK.

The original-Base ZipDepth graphs pass isolated Galaxy XR validation with 163/163
operations in one OpenCL partition, CL/GL interop, and finite structured output. Controlled
low-priority LiteRT median/p95 times were 10.089/10.310 ms at `672 x 384`, 12.991/13.189 ms at `896 x 384`, and
13.308/13.488 ms at `928 x 384`; output-ready medians were 10.670, 13.599, and 13.924 ms. These are
isolated model results, not sustained decode/reprojection or thermal qualification.

Native initialization extracts and SHA-verifies the selected complete archive entry, verifies its
fixed tensor layouts, compiles it once, and allocates two GL input/output slot pairs from those layouts.
Per-frame code reuses that graph and those allocations; it must never extract, resize, or recompile
in the render loop. LiteRT performs its packed-to-internal conversion on the GPU. Production keeps
the public renderer contract packed
NHWC: a debug-only half4 external-buffer probe was slightly faster but reproducibly left output
pixels unwritten after a fresh refill, so it failed completeness/parity and remains rejected.
ZipDepth uses automatic internal OpenCL storage. Its execution policy is included in the
compiler-cache namespace. The
renderer and inference worker
exchange GL fences
across shared EGL contexts; model tensors are not mapped into Java or staged through CPU memory.
Complete one-partition OpenCL delegation is mandatory; partial delegation or CPU execution is an
initialization failure. There is no alternate model selection, inference backend, or live geometry
fallback. The required raw-R32F V2 conditioner, exact 1x fixed-point seed, 2x-horizontal one-correction
refinement, and packed compose initialize as one strict route. If any of them is unavailable or
later fails, Client SBS duplicates current mono color instead of exposing the seed alone or an older
or differently normalized geometry.

There is no production managed Java LiteRT interpreter, QNN/HTP delegate, CPU inference path, PBO
tensor readback, or Java depth-result worker. Native initialization requires full GPU delegation
and OpenCL/OpenGL interoperability. If that contract fails, Client SBS marks the backend
`Unavailable` and duplicates the mono image; it does not select another inference backend. The app,
Normal mode, and Host SBS modes must remain usable.

The inference worker owns native LiteRT creation, invocation, and destruction. Do not destroy the
engine from the renderer thread. Renderer-side failures must signal the owner thread to stop, while
releasing every frame-slot lease, inference claim, and GL fence exactly once.

## Color/depth scheduling and bounded reuse

Every real Client SBS inference remains paired with the exact captured color slot that produced
it. The sole deliberate exception is Apollo-compatible near-identical reuse: after a GPU comparison
accepts a new model input, the renderer presents that current color with the cached depth, profile,
conditioned disparity, and warp derived from the last valid real inference. No other path may pair a color
frame with older depth.

Scheduling is readiness-driven rather than timer-driven:

- Depth inference has no FPS ceiling, thermal cadence reduction, or forced post-inference idle
  interval. Android thermal status remains telemetry only.
- Surface callbacks may coalesce to the newest decoded frame. The GL thread continues draining and
  latching pending `SurfaceTexture` frames while inference is busy so the decoder consumer does not
  back up.
- A monotonic source-step approximation advances on every accepted decoder callback, including
  callbacks coalesced before one `updateTexImage()` latch. The callback sequence assigned to a
  latch is protected through `updateTexImage()`, so the four-step reuse bound cannot silently omit
  coalesced frames.
- A single-flight inference claim prevents the worker queue from growing.
- There are exactly two native input/output tensor slots and exactly two matching full-resolution
  color slots. A capture, its public LiteRT tensors, and its eventual depth result always use the
  same slot index.
- The two slots allow one published color/depth state to remain active while the newest uncaptured
  frame is arbitrated. LiteRT invocation itself remains single-flight.
- The renderer submits the model-input fence before the full-resolution color copy. That lets the
  worker begin waiting on the same-slot decision while the renderer finishes capturing the matched
  color, without weakening their shared lease/generation identity.
- The fused model-input pack compares the exact packed Float32 input with the last valid real
  inference input on GPU. Apollo's bounds are literal: `16 x 16` tiles, medium absolute channel
  delta `>= 1/64`, strong delta `>= 0.20`, at most 10% medium pixels globally, at most 2.5% strong
  pixels globally, and no more than 75% strong pixels in any tile with at least 64 admitted pixels.
  Every expected input pixel must be finite and admitted; malformed or incomplete evidence forces
  inference.
- A comparison is eligible only in the same renderer generation, with presentable depth, for a
  cumulative callback-sequence gap of one through four and an owner age from zero through strictly
  less than 100 ms. Reused frames never advance either bound; both remain relative to the last real
  inference.
- The worker waits on the input fence and reads only a client-local, integrity-checked 32-byte
  decision record. Its final word classifies reuse, content rejection, owner frame-gap/age
  rejection, or invalid evidence without adding another map or synchronization point. This record
  never crosses the network.
  Buffer identity/allocation/range changes and map/unmap failures disable further decision reads
  for that engine lifetime and fail open to LiteRT. A stale token, malformed record, or explicit
  infer decision affects only the current frame, so later valid records remain eligible. This tiny
  CPU map is an Android implementation difference from Apollo's CUDA conditional graph and must be
  measured for stalls on Galaxy XR; no image or tensor is read back to CPU.
- On reuse, native skips LiteRT and returns a flushed fence through the normal per-slot ownership
  protocol. The renderer activates the current color but freezes model-input history, scene-cut
  history, normalization, temporal depth, profile, conditioned disparity, and cached warp at the last real
  inference.
- A real inference is adopted only after its output fence is ordered, depth/profile processing
  succeeds, and its exact model-input and scene-cut histories are committed. Only then is the
  single-flight claim released and the next callback-backed frame allowed to arbitrate. This makes
  the reuse owner unambiguous across renderer and inference contexts.
- Shared-context fences order input production, inference output, and GPU postprocessing without
  blocking the GL thread on normal operation.
- Each adopted real pair or accepted reuse is rendered directly into the EGL default framebuffer
  once, followed by one swap. There is no packed SBS offscreen texture or repeated packed-frame
  blit. Callback-driven drains with no adoption perform no draw or swap; SceneCore retains the last
  submitted buffer.
- That retention is presentation-bounded when inference stalls: because the decoded route has no
  authenticated host content clock, any successfully latched newer buffer conservatively counts as
  a changed source. The prior packed pair remains eligible through exactly 250 ms from its matched
  color capture; after that strict boundary a single deadline draw swaps current OES color duplicated
  flat, and later callbacks keep flat color live until a sufficiently fresh result is adopted. This
  fallback does not release slots or change depth, profile, cut, normalization, or temporal history.
- The active color-slot lease remains renderer-owned until the next adoption. The newer color takes
  ownership before the superseded lease is released; no offscreen SBS cache owns rendered pixels.

The client cannot reproduce Apollo's exact DDup admission because MediaCodec/SurfaceTexture does
not expose Desktop Duplication present IDs, dirty/move rectangles, or host route authority. Its
near-identical branch therefore authenticates decoded-pixel similarity plus strict callback-gap,
age, generation, and depth-owner bounds. Host DDup exact-copy/idle/route decisions could be followed
exactly only through the separately negotiated optional extension described above; legacy hosts
remain on the local path.

Busy claims, occupied mailboxes, or unavailable color slots drop capture opportunities while the
last submitted output remains retained; they must not create an unbounded queue or detach depth
from color.

Fence ownership is part of the slot contract:

1. The renderer packs model input and the client-local decision, then transfers a nonzero
   input-ready fence and any nonzero previous output-consumed fence for the same slot to native.
2. Native first waits on and deletes the current input-publication fence, then maps and authenticates
   that exact decision record. It waits on and deletes the prior-output/slot-reuse dependency only
   afterward; both dependencies must succeed before either bounded reuse or LiteRT inference. Native
   returns a new ready fence owned by the caller.
3. For real inference, the renderer orders the ready fence, dispatches output reads and history
   commit, then creates a new output-consumed fence. Reuse and unread/discarded results retain the
   ready fence itself because no renderer read of that output buffer was submitted.
4. Slot reuse transfers that consumed fence back to native. Shutdown transfers the newest final
   consumer fence for both slots to the inference worker for bounded teardown.

Never delete a transferred fence twice, reuse a slot without its consumer fence, or close the native
engine from the renderer thread.

## Fixed Client SBS Depth Coordinate V2

Client SBS and Host SBS AI expose no manual depth-tuning parameters. Client geometry consumes the
original ZipDepth raw high-is-near field directly; the former per-frame normalization, normalized
P2/P98 stretch, subject recentering, Bestv2 polynomial, shot-median anchor, and adaptive pop do not
participate in reprojection. Fixed pop is `1.75`. Do not reintroduce strength, convergence, balance,
movie-mode, zero-plane, or legacy geometry controls.

Original ZipDepth Base does not share the host hybrid model's output units. Each immutable graph
therefore carries one positive, offline-fitted raw-coordinate scale:

| ZipDepth graph | V2 raw-coordinate scale `s` |
|---|---:|
| `672 x 384` | `0.04864449` |
| `896 x 384` | `0.04707071` |
| `928 x 384` | `0.05421491` |

The scales were fitted independently from 192 paired frames across eight clips. For each clip, the
host hybrid prediction was exact-area resized to the ZipDepth graph, both producers were centered
on their first-frame arithmetic means, and a through-origin least-squares fit matched
`(zipRaw - zipMean) / s` to `(hostRaw - hostMean) / 2.25`. The manifest rejects a missing,
non-finite, or non-positive scale. `tools/calibrate-zipdepth-v2-scale.py` reproduces the fit and
rejects incomplete pairs, non-finite fields, unexpected shapes, or reversed polarity. These are
model/graph calibration constants, not permission to copy the host's `2.25` raw scale onto original
ZipDepth and not proof that the two model families produce identical depth.

The camera center is the arithmetic mean of every raw output texel, latched on the first accepted
depth of a shot. It remains fixed while objects move within that shot and is replaced only when a
new shot is accepted. Consequently, a hand moving toward the viewer changes
`rawDepth - shotMean`; it is not pulled back to the same plane by per-frame normalization. There is
no runtime percentile gain, per-frame offset, or slow gauge correction. A background-only slow
gauge correction is deferred unless whole-clip evidence later demonstrates that one is necessary.

For a finite raw sample `d`, shot mean `m`, and selected graph scale `s`, the pointwise V2 mapping is:

```text
c = (d - m) / s

curve(c) = 0.75 * expm1(c / 0.75)                    when c < 0
           c                                         when 0 <= c <= 1
           1 + 0.5 * log1p((c - 1) / 0.5)           when c > 1

requested = 1.75 * 0.00375 * curve(c)
p = requested / fourth_root(1 + (requested / 0.04)^4)
```

The last expression is evaluated in a stable odd form and remains strictly within `+/-0.04`.
The source-aligned `R32F` ZipDepth output feeds the raw V2 conditioner directly. Four GLES 3.1
serial-line compute passes produce a linearly sampled `R32F` signed-parallax field. There is no
client-only spatial prefilter or half-float staging target before subtraction against the `R32F`
shot mean and coordinate conversion, matching the host's raw-depth geometry contract.

Vertical upper/lower envelopes use a step of `2/W` and combine as `0.75 * upper + 0.25 * lower`.
The horizontal result is the least majorant `max_s(v(s) - 0.5 * |x-s| / W)`. This bound makes each
eye mapping contractive. Starting from `x0 = u`, the map shader executes at most 11 updates: left
`x[n+1] = u - p(x[n])`, right `x[n+1] = u + p(x[n])`. It mirrors the host's exact-settle exit when
both paired-eye next coordinates equal their current coordinates; there is no epsilon convergence
shortcut, and a non-settled sample still executes all 11 updates.

The seed pass follows that exact host iteration schedule at one texel per source-aligned depth texel
and stores small signed left- and right-eye source displacements in red and green of a linearly
sampled `RG16F` map. A second `RG16F` target is 2x in the horizontal axis and 1x in the vertical
axis. Each refined texel bilinearly samples the exact 1x seed, reconstructs both source positions,
and performs one more paired-eye fixed-point correction against the conditioned `R32F` parallax
field. The full-width packed-SBS draw then consumes the refined cache and request-resolution matched
color. Its steady per-output cost remains one warp-map lookup plus one color lookup; refinement runs
only when a new real depth is adopted, and near-identical reuse freezes both caches. The live
renderer compiles no seed-only, Bestv2, or frontmost-probe geometry fallback. Direct/full-resolution
`R32F` inversion and a blind 2x-by-2x 11-step map remain deferred experiments.

For the `672x384` graph, the exact seed is 258,048 fragments, 0.98 MiB, and at most 5,677,056
conditioned-parallax samples per changed depth. Its `1344x384` refined target is 516,096 fragments
and 1.97 MiB. The one-correction pass adds 516,096 `RG16F` seed lookups plus 1,032,192 `R32F`
parallax lookups, or 1,548,288 logical texture samples. Both maps together occupy 2.95 MiB, and the
strict build performs at most 6,709,248 parallax samples plus those seed lookups. This is far below
the 22,708,224 parallax samples of a blind 2x-by-2x 11-step solve, while halving the horizontal
interpolation interval where warped knots cause serration. These are analytical shader costs, not a
claim of proportional latency: the September 4 4K trace already reached thermal status 4 with
69-98% GPU-busy windows, so the refined route still requires same-clip sustained device validation.
`RG16F` itself is not the likely visible serration source: within the `+/-0.04` container its worst
rounding error is below 0.06 source pixel at 3840-pixel eye width.

Every raw output texel must be finite and nonnegative, the raw field's population standard deviation
must exceed Apollo's `1e-6` collapse floor, the shot center and graph scale must be valid, and every
conditioner/seed/refinement/compose stage must succeed. A violation publishes current mono color
duplicated flat and never applies the 1x seed alone, old geometry to the failed frame, or a legacy
mapping. A no-cut collapsed result retains the existing shot camera; a cut landing on a collapsed result clears it,
and the next usable result reacquires it. The private normalized cut history can still advance on a
finite collapsed result, matching the host's independent cut bridge. Near-identical reuse is the
sole explicit current-color/cached-geometry exception and is bounded by the owner rules above. A
collapsed result may update that private cut/color history, but explicitly invalidates the cached
geometry owner so the next accepted candidate must run real inference again.

Raw outer-edge P2/P98 bounds and their private normalized temporal field remain only for geometry
change, cut analysis, and health diagnostics. They do not scale, center, stretch, or otherwise feed
the V2 disparity field. Subtitle and foreground-ROI plane conditioning are deferred and absent from
the client conditioner.

## Scene cuts and depth health

Scene-cut detection is GPU-only and paired with the exact SDR model-input frame. Each bounds-safe
16 x 16 workgroup stores average integer Rec.709 luma for the broad raw-change gate and a separate
structural value: the median `max(R,G,B)` of a fixed 3 x 3 sample lattice. The latter is an order
statistic of an exposure-equivariant scalar, so an identical global monotone RGB exposure curve
(gain, offset, gamma, clamp, and rounding) can preserve an ordering or collapse it into a tie but
cannot reverse it. On the small persistent block grid the comparison pass checks all ten pairwise
orderings in a center/left/right/up/down stencil. A relation votes only if it differs by at least
four codes in both frames; a changed site needs at least four common relations, two reversals, and
a reversal majority. The comparison separately counts sites with at least four reliable current
relations and sites with at least four relations reliable in both frames. A proposal requires at
least 15% changed sites plus the existing spatially broad raw-change gates. Five percent support is
enough to classify a frame-level relation; this stays below the measured support of the committed
preserved-structure exposure fixtures.

A transition from supported history to a current frame without that support is suppressed for one
update regardless of raw color distance; a flat value can match the preceding scene's dominant
color, so raw delta cannot qualify structure loss. On commit, a GPU history bridge copies the last
supported ordinal grid into the pending ping-pong texture and preserves its histogram. Two metadata
bits in the block-count word encode normal, one-update hold, and accepted persistent-low history
without growing the SSBO. The second accepted low-support update advances history and enters the
persistent-low state; later low-support updates stay there without a timer or repeated event.
Concretely, state 0 has no accepted history, state 1 advances normally, state 2 holds one update,
and state 3 advances accepted persistent-low history.
Only that first structureless update holds the reliable comparison tuple and geometry baseline.
The live P2/P98 range and immediate temporal depth still update on every complete valid inference,
matching the host's separate ownership domains. The second low-support update is classified as
persistent-low, advances the reliable tuple, and cannot inherit a stale exposure classification.

The first supported frame after a one-update hold is exposure-like only for a strict endpoint
match: quiet ordinal change, at most two average luma codes per block, and fewer than 1% moderate
block deltas. Thus `A -> saturated black/white -> A` cannot relatch on either edge, while a
quiet-color but different-depth `B` retains standalone geometry authority. A different supported
scene `B` is compared directly with `A`; structural replacement proposes a cut, while supported
`B` with insufficient common ordering remains ambiguous and also leaves strong depth geometry
authoritative.
Startup flat history followed by supported content likewise leaves geometry authoritative. This
preserves saturated-flash rejection without making a structureless frame the sole reference for the
next real scene. Preserved-structure monotone exposure remains vetoed.

This scope deliberately excludes codec noise, color matrices, and local tone mapping. Histogram L1
remains diagnostic, not cut authority: exposure can move a histogram, while real same-histogram
edits exist. Historical multi-model fixtures established the current structural policy:
`scene_cut` pairs measured 0.433–0.571, `flat_transition` measured 0.201–0.266, and the largest
adjacent non-cut was 0.062. Those retired-family measurements remain regression evidence rather
than a claim that their graphs ship. The nonlinear clipped-plaid adversary produces zero ordinal
reversals.
The old brightness-only `uniformHardTransition` path is absent. Its grids and GL resources are
derived once from the selected stream shape. It writes one 32-byte structural-evidence record to an
SSBO: the published proposal, block count, raw-change count/sum, structural-change count, current
and common structural support, and detector reason bits. The following depth/profile dispatch
consumes that record directly on the same GL queue. No per-frame flag crosses through Java or CPU
memory.

The detector is an optional cut-quality/reuse component, not a depth-pipeline readiness gate. If
it cannot be created or fails at runtime, Client SBS continues full inference and reprojection,
disables near-identical reuse and color-based appearance/exposure authority, and uses bounded
two-valid-observation depth-only confirmation. The stats pane identifies pending, accepted, and
rejected fallback decisions explicitly.

The depth processor separately derives geometry evidence from its private P2/P98-normalized
analysis field. Immediate temporal depth resets only on the first valid field after processor reset;
cut proposals and accepted cuts do not reset it. Only an accepted cut relatches the raw
arithmetic-mean camera center used by V2 geometry. A qualified appearance
proposal may cut immediately when moderate depth evidence corroborates it (`change >= 0.18`, or
`>= 0.10` with range shift `>= 0.06`). Standalone ordinary geometry evidence starts a confirmation
on its first qualifying update (`change >= 0.58`, or `>= 0.42` with range shift `>= 0.10`) and is
accepted only if the next valid update still qualifies. Matching Apollo, every ordinary geometry
candidate also requires independent ordinal structural change of at least `0.005`; a persistent
structureless transition or a structureless history reference waives that test because such a
reference has no reliable ordinal relation to reverse. A pending confirmation additionally requires
reliable current structure. On startup, both branches remain blocked through source-frame age 8,
and arming happens after that update's decision.

The first update of an ordinary geometry-only candidate freezes the raw camera center, geometry
baseline, reliable normalized depth, model-input owner, and scene-cut history as one coherent
comparison tuple. Its live P2/P98 range and immediate temporal depth continue to update, and its
finite noncollapsed current raw field still publishes geometry through the existing shot camera.
Confirmation compares the second update with the unchanged reliable owner. A confirmed cut
coherently advances the tuple and latches the new raw arithmetic mean; a failed confirmation clears
the pending state without mixing reliable histories. On Android, the scalar decision is published
at this final resolver and the exact normalized-depth texture is promoted at the beginning of the
next actual inference, before that inference compares. Reuse dispatches nothing and cannot promote
it. This preserves the host-visible dependency order without a seventh full-grid pass. Qualified
appearance cuts remain immediate because the independent structural evidence already supplies the
second authority.

When color history enters accepted persistent-low state, a typed event sets the reserved
`cutStateCounters.y` marker. The first later supported update gets exactly one absolute standalone
geometry decision independent of normal arming and refractory state, then clears the marker whether
or not it cuts. Persistent low-support updates do not retrigger it. The mailbox uses bit 0 for an
appearance proposal, bit 1 for the exposure veto, bit 2 for persistent-low start, and bit 3 for
supported return. Evidence belongs to the exact color/depth transaction; an invalid inference is
counted for health telemetry but its classification is not applied to a later valid field.

Every accepted cut latches both evidence sources, but they rearm independently: geometry needs two
consecutive valid depth updates below `0.08`, while appearance needs two consecutive valid updates
without a qualified structural proposal. Invalid results do not satisfy those counters. Ordinary
preserved exposure and a strict same-scene return set a one-valid-update recovery bit; the next
non-appearance update vetoes geometry and freezes only its novelty baseline, then clears the bit.
A genuine appearance proposal bypasses that tail. Persistent evidence therefore cannot repeatedly
move shot state, and persistent appearance cannot starve a later standalone geometry cut. While geometry
remains latched, a genuinely new spike may start the same two-update confirmation on or after
source-frame age 8:
change must be at least `0.30` and exceed its per-update EMA by `0.20` or by `2x`. The EMA uses
alpha `0.125` and resets to the current fraction on each accepted cut, so a sustained high fraction
converges instead of pulsing repeatedly.

Cut age follows the protected decoder callback/source-step delta attached to each complete valid
inference, not wall time and not merely one increment per sparse depth result. It resets on
initialization or an accepted cut. Reuse and invalid depth do not advance it. Reliable history is
held only for the first structureless update and the first geometry-confirmation observation;
ordinary exposure advances normally. This keeps source timing distinct from reliable
model-input/scene-cut/depth ownership when callbacks coalesce while LiteRT is busy.

These depth fractions are calibrated for the client's model grid; they intentionally differ from
Apollo's capture-grid thresholds. ZipDepth's complete raw field is the cut-analysis validity unit:
if any output texel is NaN, infinite, or negative, the transaction does not advance reliable
history. Raw V2 renderer validity additionally requires population standard deviation above
`1e-6`; a finite collapsed result remains available to private cut analysis but presents current
color flat and cannot acquire a shot camera.

An invalid depth transaction has no cut authority. Its exact color classification may be retained
only in asynchronous diagnostics; it is never carried onto a later valid depth field. It cannot
authorize a new reliable owner, though its opening min/max pass may finish the deferred texture copy
already authorized by the preceding valid result. Range, immediate temporal value, raw shot camera,
cut baselines, recovery state, and cut FSM otherwise remain unchanged until a complete valid
inference arrives.

Depth-health stats are diagnostics, not geometry inputs. A tiny asynchronous GPU state copy
continues at a 30-real-inference background cadence even while Stats and explicit performance
logging are off, and sharpens to every 5 real inferences while Stats is visible. Its poll is
nonblocking. Append-only state fields preserve the prior byte offsets while exposing appearance
proposal count; exclusive accepted appearance, geometry, and structureless-return cut counts; the
latest raw/structural/support evidence; depth change/range shift; and causal reason bits. This uses
the existing state copy, fence, and poll—no extra readback, dispatch, or synchronization. A
temporarily missing health sample must not stall or disable depth.
Accepted cuts, appearance proposals/vetoes, geometry triggers, and failed geometry confirmations
retain their exact color/depth evidence plus a monotonic event sequence until a later notable
decision replaces them. Ordinary frames do not erase this latch, so the sparse health-copy cadence
cannot turn an intervening event into `reason=none`; repeated publications of the same sequence are
the same event, not another cut.

## Surface and lifecycle ownership

The central contract is:

> Whoever owns presentation supplies the initial `Surface` through
> `MediaCodecDecoderRenderer.setRenderTarget()` before codec setup, and supplies every live
> replacement through the guarded `MediaCodecDecoderRenderer.setOutputSurface()` transaction.

Mode switches are guarded asynchronous surface handoffs. Keep the decoder target, SceneCore surface
size/stereo mode, renderer generation, and entity visibility synchronized. A stale callback from a
previous generation must not retarget the decoder or publish a depth result.

On crossings into or out of Client SBS, keep SceneCore's last submitted picture and its current
stereo/shape interpretation visible while MediaCodec crosses its recovery barrier, parks on the
persistent dummy, and binds the replacement producer. A failed park therefore leaves the old
picture visible. Entering Client SBS commits the new SceneCore interpretation only after a fresh
decoder IDR has produced a packed EGL buffer that survives the renderer's swap proof; leaving
Client SBS commits it at the fresh direct-output edge. Direct producer size changes retain their
existing eager hide because they do not have the Client SBS producer-ownership gap.
When an inactive Client mode's saved quality differs from the live stream, fuse that request into
the entry: retain the old picture and interpretation, await the authoritative `0x3008` ACK, then
perform exactly one decoder/surface handoff at the ACKed geometry and the same packed-swap proof.
Publish the ACKed quality before the mode so the settings callback cannot start a second request.
A Client tuple that cannot apply live reconnects before any presentation handoff, including on
regular Sunshine/Apollo hosts.
The Client-entry GL thread holds its mandatory initial draw until that fresh decoder callback, so
GLSurfaceView cannot submit an empty first buffer over the retained SceneCore picture.
The packed-swap watchdog starts only at that decoder-output edge; time spent waiting inside the
decoder's own bounded fresh-IDR transaction does not consume the renderer's proof budget.

Crossing Client SBS ownership or the live Host SBS AI packed-size boundary changes the decoder
target or encoded dimensions. Before those transitions, close the compressed-frame gate and flush
MediaCodec through its all-thread recovery barrier. After the replacement surface is bound, request
a new IDR and admit only a serial-newer IDR before reopening the gate. Raw SBS does not use this live
path: every transition across its packed transport boundary reconnects first.

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

The host's `/serverinfo` response is the authority for whether a stream session exists. Artemis
does not duplicate the host's disconnect grace period with a client timer. Apollo-3D advertises a
generation-scoped `hostsessionid` element (including value zero while idle); its presence is the
capability bit and its nonzero value remains mandatory for exact resume/cancel protection. Regular
Sunshine and Apollo omit that element, so they use the standard GameStream running-app identity for
resume and the standard tokenless cancel request. Absence is not equivalent to an advertised zero.

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

Live-quality state remains logical `W x H` in the client. On an extension-capable Apollo-3D host,
at the `0x3007`/`0x3008` boundary only,
Raw Full maps that tuple to and from its already-packed `2W x H` desktop. Raw Half and Host SBS AI
keep base `W x H` control geometry (Host SBS AI performs its doubling inside Apollo). MediaCodec
recovery state instead retains the actual encoded dimensions: packed Raw Full and packed/capped
Host SBS AI geometry, but ordinary `W x H` for Raw Half, Normal, and Client SBS.
On a regular Sunshine or Apollo host, every stream-quality change follows the standard
commit-and-reconnect path, and automatic headset-panel-rate following is disabled because there is
no live video-mode control/ack contract.

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
when the Activity is recreated. A same-tuple switch remains live unless it enters or leaves Raw
Full's distinct `2W x H` transport. Raw Half uses the ordinary `W x H` mono transport, so entering
or leaving it remains live; changing Raw's Full/Half choice while Raw is live still reconnects.
**Apply & reconnect** remains the explicit action when no mode-quality or transport change already
requires a restart. The Client SBS ZipDepth aspect graph is derived from the pending Client SBS
resolution and is not an independent setting. Raw's Full/Half choice is mode-specific, persists
with the current session, and inherits its default from Global Settings.

The resolution ladder keeps its six established landscape choices first and then exposes one
explicit portrait counterpart for each by swapping `W` and `H`. Those portrait IDs are literal
host/virtual-display requests; they do not toggle Android's resolution-inversion option or rotate
the XR activity. Landscape/portrait crossings reconnect so the decoder can use a real
orientation-specific adaptive envelope rather than a synthetic `5120 x 5120` maximum. Resizes
within the current orientation remain live when that envelope and the active presentation
pipeline allow them. Client SBS aspect-fits portrait color into its landscape model with reflected
side padding and crops the matching padding from depth, avoiding a nonuniform portrait stretch;
that immutable crop/shader contract participates in the reconnect decision. The model-input area
filter divides its source-to-model ratio by the occupied `contentSize`, so a 9:16 frame is filtered
over the real portrait-content grid rather than the wider padded tensor grid. Every footprint tap
is reflected before the decoder transform; mirroring only the center is incorrect where a
footprint crosses a padding fold.

The settings truly shared by all four modes are **codec, video frame pacing, HDR, Full/Limited video
range, audio layout, and play audio on the host PC**. The Session Settings pane edits only this
shared set. Global Settings provide the cross-session defaults for both the shared set and the
quality baseline inherited independently by each mode.

The factory baseline for a fresh install is **3840 x 2160 at 90 FPS, 200 Mbps, HEVC, HDR, Full
range, and latency pacing**, with stereo audio, host audio off, and Full as Raw SBS per-eye
resolution. Client SBS always uses ZipDepth. In-session **Use global defaults** inherits the
values currently saved in Global Settings rather than forcing this factory baseline. A mode row's
**Use session settings** discards staged edits and restores that mode's durable current-session
values, falling back to its current global values where no session override exists.

Normal, Raw Host SBS, and Host SBS AI therefore begin with a durable **90 FPS ceiling**. Client SBS
keeps its intentional 30 FPS mode default. A headset panel/thermal transition may temporarily lower
the effective on-wire rate to an offered rung, but it never rewrites the selected ceiling; the host
automatically follows the panel back upward, at most to that ceiling, when the panel recovers. The
SceneCore presentation Surface advertises the durable ceiling rather than the temporary effective
rate so a recreated output swapchain cannot pin the panel at a throttled mode. In Client SBS the
decoder writes to the renderer's offscreen `SurfaceTexture`; that input is not the display-rate
authority. The actual `SurfaceEntity` output receives the durable vote after every `getSurface()`
replacement and after every successful explicit ceiling change. The paused, hidden GLSurfaceView
holder remains neutral on XR; ordinary non-XR presentation holders retain their legacy vote.

An ACK may clamp a panel-follow request below both its temporary rung and the durable ceiling—for
example ceiling 90, request 72, applied 60. That 60 is the effective on-wire rate only; dynamic
panel/host throttling never ratchets the explicit 90 ceiling downward. ACK-clamped geometry and the
requested total wire bitrate remain durable. Every explicit FPS selection is likewise a maximum:
an ACK below a direct 90 request or a direct lower ceiling such as 60 changes only the effective
on-wire rate and never lowers that durable ceiling. A missing application ACK proves no applied
tuple, even when a matching fresh-IDR proves the requested geometry: the final FPS/bitrate clamps
remain unknown. The same fail-closed rule covers an unknown/future ACK status, `APPLIED` with an
unusable tuple, failure to adopt the host-authoritative geometry on the client, or any client resize
failure after the reliable host request was already queued. No decoder/surface resize transition
begins before a resolution request's valid `APPLIED` ACK. After receiving the authoritative,
possibly clamped geometry, the client starts exactly one post-ACK confirmation transition, adopts
that geometry behind its closed decoder gate, and opens the gate for a fresh IDR. Client SBS keeps
its previous packed SceneCore picture visible through this proof; direct producers retain their
established hidden boundary.
Its watchdog may issue bounded IDR retries, but only matching output from that generation may settle
and reveal the new geometry. A failed rearm, timeout, or post-ACK output whose dimensions
contradict the ACK follows the same hidden
mandatory-resync path, not the generic decoder-failure dialog. None may publish the previous tuple
as a rollback or settle the requested tuple as a live success.
An explicit `NEEDS_RECONNECT` response to a user-origin Client SBS resolution request has no local
surface rollback: the client keeps the old picture visible, commits the staged target through the
normal guarded settings path, and reconnects immediately. `INVALID`, `FAILED`,
automatic panel-follow, and non-Client-SBS refusals retain their established rollback handling.
Client SBS bounds its local EGL detach and exact-attach stages independently. Its packed-output
swap wait retains a longer fail-closed fallback while the reliable host outcome and decoder
transition are outstanding. A matching post-ACK decoder output then re-arms a fresh short window
and explicitly nudges the renderer for the same-generation, same-attachment two-draw presentation
proof; time spent waiting for the host ACK can never consume that proof budget. After draw one arms
the candidate, its second render request is queued behind the current EGL-swap iteration so an
in-draw dirty request cannot be coalesced before the first swap returns. When that authoritative
host/decoder boundary arrives during first-use model verification or delegate compilation, the
short proof watchdog polls while the backend remains explicitly `Initializing`, up to one
30-second initialization ceiling. Observing that cold backend become ready grants exactly one new
2-second packed-presentation proof window and nudges the renderer again; the combined cold path is
therefore bounded to 32 seconds. Already-ready, unavailable, and ordinary warm paths retain the
short watchdog, so the exception cannot hide a broken EGL/presentation transition.
Before a persistent Client SBS decoder `SurfaceTexture` crosses any live-resize generation, the GL
thread unconditionally acquires and discards its latest queued image under the frame lock, clears
the matching callback state, and only then advances the generation. Merely clearing the Java
callback flag is forbidden because it can consume BufferQueue's notification edge without
releasing the pending image, leaving MediaCodec apparently productive while Client SBS latching is
starved.
Fast user changes, automatic panel-follow changes, and resolution changes therefore all fail closed
to reconnect. The client clears local transition ownership but neither claims success nor restores
an unacknowledged previous tuple. Fast paths and a resolution path with matching decoder output may
reveal the quad while reconnect starts; unresolved/mismatched resolution geometry remains hidden.
For a user-origin ambiguous result, the reconnect path may commit that user's staged target.
Panel-follow recovery never commits staged UI edits: it reconnects the last durable ceiling and may
reapply the observed lower rung afterward. If a user-origin staged commit lost its generation race,
mandatory resynchronization still reconnects the last durable record; a stale-settings warning must
never leave an ambiguous live stream running.

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
The rectangular model input is always SDR and tonemaps PQ before ZipDepth inference. That does not
require the full-resolution presentation path to become SDR.

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
viewer while leaving the dock pose unchanged. When fitting mode content between its 0.52 m
baseline and 0.90 m cap, resize both the hosted Android
raster with `setSizeInPixels()` and the physical quad with `setSize()`. Derive both from the original
raster/metre pair so repeated mode refreshes cannot accumulate rounding drift; retain the whole-pane
`ScrollView` beyond the cap. Session Settings opens to the **left** of the video;
its inner edge remains anchored outside the video and the panel yaws inward toward the viewer's
face. **Stats** uses the **right** side as a compact, single-column panel whose
inner edge is anchored just beyond the video's right edge. It yaws inward around local Y so its
outer edge wraps toward the current head position, with a clearance limit preventing it from
approaching the viewer too closely. Its Android raster and physical height grow together with the
visible rows from the authored 1920 x 1440 / 1.05 m baseline to the deterministic 2538 px / 1.85 m
cap; only content beyond that cap uses the bounded vertical `ScrollView`. Recompute side-panel
poses when they open, on video resize/mode change, after screen movement/Cinema View, and on the
existing slow Stats refresh. Never poll head pose from the video frame loop or while the associated
side panel is hidden.

Presentation modes form one single-select group. Navigation/disconnect actions remain separate
one-shot controls. A new session highlights Normal; a resumed/restarted session highlights its
restored mode only after that mode is actually active.

The Host SBS AI tile and host debug action are disabled when `/serverinfo` does not advertise the
Apollo-3D session/control extension. Normal and Client SBS remain available; Raw SBS keeps its
separate virtual-display requirement.

The four mode tiles live in one level toolbar `PanelEntity` and share one contextual
`PanelEntity` directly beneath it. An inactive mode tile switches modes on its first tap; tapping
the active tile again toggles that mode's row. A passive down/up chevron with a conventional aspect
ratio sits centered against the lower edge of the tile and communicates the expandable state
without a small nested "Options" target. It does not change the fixed dock/tile geometry. Every
mode row owns that mode's resolution/FPS/bitrate tuple. Resolution uses visual cards with every landscape choice in the
first group and every portrait choice beginning on the row below; either group may wrap further
when the panel is narrow. FPS uses a compact segmented control, and bitrate uses a connected
six-rung segmented ladder at **50 / 70 / 100 / 140 / 200 / 300 Mbps**, with the stream-shape
recommendation marked on its rung. The
row identifies Global versus Current Session inheritance, shows the tuple currently backing the
live decoder, and offers the same atomic **Apply & reconnect** action whenever any scoped change
requires it.

Normal and Host SBS rows also show their presentation/source status. Client SBS adds only its fixed
ZipDepth identity, resolution-derived aspect bucket, and live GPU backend status; it has no model
selector, strength, convergence, balance, movie-mode, or depth-inference cadence controls. Restoring
values is scoped:
the shared pane's **Use global defaults** stages the currently saved global shared values, while a
mode row's **Use session settings** restores only that mode's durable quality tuple (plus the Raw
Full/Half choice for the Raw SBS row).

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
make the user scroll an enum sideways. Bitrate follows the same direct-manipulation rule with its
six-rung connected segmented ladder; do not regress it to an inline slider or bandwidth meter.
Client SBS has no model selector: its Options row configures stream quality while Stats reports the
active ZipDepth aspect graph. Raw SBS uses a direct two-button **Full / Half** group labeled
**Per-Eye Resolution**; it also shows the derived encoded-per-eye and packed-stream
dimensions so the choice is visible rather than merely numeric. Tapping the running application card resumes it directly; a
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
busy/clock, and Android thermal status. Client SBS adds the model/backend/input shape,
latch/inference/reuse/output FPS, reuse acceptance ratio and rejection reasons, 32-byte decision-read
wall average/maximum, LiteRT call-wall average/maximum, real-inference result-age average/maximum,
and four separately labelled true GL GPU averages: model-input resize/color-cut/pack, matched-color
copy, raw-V2/cut-state processing, and stereo conditioner/inverse-map/packed draw. The last region is
the only live V2 geometry route. The device GPU percentage remains a
system-wide total: GL stages can overlap each other and OpenCL, so their durations must not be
summed into a synthetic utilization percentage. Show XR composition as unavailable because
SceneCore exposes no compositor timing. The fault row keeps occupied color slots (`color_busy`), flat
SBS outputs (`flat`), invalid raw transactions, and collapsed diagnostic cut ranges visible. Depth
health is a compact scalar set:
renderer-ready/current-field-valid/history-advance-or-hold, fixed pop with shot/current raw means,
the last latched depth and appearance cut evidence, its causal decision, and accepted-cut counts. A valid held
field must read `ready yes` with `history hold`; holding the reliable comparison-history tuple does
not make its geometry unrenderable. Do not show the retired normalized
stretch/recenter/subject/Bestv2 anchor or
adaptive-pop classifier as live V2 state. Host SBS likewise labels current protocol data as raw V2,
fixed pop, validity, cut evidence/events, and faults rather than as a median zero-plane profile.
Trend plots use fixed oldest-left/newest-right sample-slot spacing. While a history is still short,
its samples occupy only the newest slots at the right instead of stretching across the entire
width; once full, each new sample scrolls the oldest point off the left edge. This is not a fixed
wall-clock scale because the producer cadence varies. The 120 retained Host SBS points represent
about 12 seconds while Stats requests focused 100 ms publications and about 60 seconds at the
background 500 ms cadence; after opening Stats, the ring temporarily contains both cadences until
the older background points age out. Every distinct accepted host publication enters the history
at delivery even though the stats table repaints more slowly. Repeated heartbeat publications
refresh liveness without adding duplicate chart points. Client SBS sample spacing likewise follows
its visible five-real-inference and background 30-real-inference health-copy cadences; reuse freezes
depth-health history.

The depth policy row must say `Uncapped | one in flight | newest frame when free`; Android thermal
status is reported separately so it is not mistaken for a hidden throttle. Do not add expected
latest-frame skips, callback coalescing, retained-output drains, or ordinary single-flight busy
events as counters: those are normal scheduling behavior rather than faults.

Surface callbacks and GL latches may follow the decoded stream while inference/reuse arbitration,
adoption, SBS composition, and EGL swaps run at the lower workload-limited cadence. This is not an
FPS cap. After the first valid pair, that difference is expected: a drain with no adoption retains
the SceneCore buffer instead of submitting duplicate pixels. Composition and swap cadence should
track real-inference plus reuse adoption; repeated output blits or swaps with no adoption are a
regression.

Do not show managed/PBO/free-CPU-buffer/result-worker stages, CPU command-submission/invoke/
dependency timings, or custom CPU/GPU temperature probes. Android 14 has no trustworthy public
per-app NPU-utilization API, so the pane does not probe or display NPU usage. The active Client SBS
backend is the GPU; Android thermal status comes from the platform thermal API.

Keep timing domains explicit. `LiteRT run call wall (not pure GPU)` brackets
`LiteRtRunCompiledModel()` and includes runtime overhead plus any blocking visible to that API;
LiteRT does not expose the OpenCL event needed to isolate accelerator execution. Reuse is excluded
from this timing. Depth result age is the real inference pair's capture-to-adoption latency, not a
GPU timer; reused depth ownership is separately hard-bounded to less than 100 ms.
`Decision read avg / max` measures CPU wall time around validation plus the authenticated 32-byte
map/copy/unmap. Immutable object/allocation/range checks are cached after their first success, so
steady-state time primarily exposes the cross-context synchronization cost on the device.

Actual GLES completion timing is backed by nonblocking `GL_EXT_disjoint_timer_query` rings. It
reports averages only for model render + color cut + pack, matched-color copy, raw-V2
depth/cut-state publication, and raw V2 disparity conditioning followed by inverse-map and
packed SBS draw. Poll availability rather than
waiting, discard samples from disjoint clock intervals, and show the timers as unavailable when the
driver cannot provide reliable queries. GLES queries cannot bracket LiteRT's OpenCL work, and
SceneCore exposes no final compositor-present timestamp. Record the active warp path alongside these
timings. Any path other than the strict exact-1x-seed plus 2x-horizontal one-correction `RG16F` V2
cache is flat output, not a comparable geometry fallback.

Performance logging is enabled by default and can be disabled in preferences. While enabled, the
typed window is written to logcat at approximately the two-second stats cadence, never per frame.
Normal and Host SBS write one
`DecoderPerf` line with sender sequence, receive, decoder output, release, surface-presentation, and
decode latency. Client SBS writes one consolidated `ClientSbsPerf` line with those same stream
boundaries plus the model/backend/input, latch/inference/reuse/output FPS, reuse acceptance
ratio, content/frame-gap/owner-age/invalid reuse-rejection counts, decision-read wall
average/maximum, LiteRT wall average/maximum, real-inference result-age
average/maximum, the four GLES completion averages, exceptional `color_busy`/`flat` counts, and
causal depth health. `proposals` counts appearance-detector proposals, not accepted cuts;
`cuts_app`, `cuts_geom`, and `cuts_low` partition accepted cuts by reason; `cuts_low` specifically
counts the two-observation transition into persistent low structure. The latest raw,
structural/support, depth/range, detector-bit, and decision-bit fields explain the current sample.
The line also includes app CPU core-equivalent load, GPU busy/clock, and Android thermal status. The former
five-second renderer debug lines and separate depth line are intentionally omitted to avoid duplicate
logging. Use these lines for repeatable A/B captures; they add no per-frame logging or additional GPU
synchronization.
When Stats is hidden and explicit performance logging is disabled, Client SBS disables timer
queries, bypasses its detailed synchronized/atomic performance-counter updates, and skips typed
stats-table/log formatting. The cheap 30-frame health-copy producer and nonblocking poller remain
active so the 120-sample diagnostic rings already contain pre-open context; opening Stats raises
that copy cadence to 5 frames. Opening Stats resets only the CPU and detailed Client-SBS performance
sampling baselines. Timer state is created/reset only on the renderer thread with its EGL context
current.

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

SceneCore, runtime, runtime-openxr, ARCore, and arcore-openxr are pinned together to
`1.0.0-beta02` (2026-08-12; the alpha16 DP4 matrix and beta01 preceded it). Keep the five artifacts
aligned; mixed Java and native OpenXR versions can crash `ViewCameraState` construction.

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
`com.limelight.moonlight3ddebug.test`.

For every mode/surface change, test:

- Pair, list apps, launch, disconnect-without-ending, resume, and end a session against current
  regular Sunshine, regular Apollo, and Apollo-3D. Verify the two standard hosts never receive
  Apollo-3D control messages, use app-identity resume/tokenless cancel, and reconnect for quality
  changes; verify Apollo-3D retains exact generation-token checks and live controls.
- A new session starts Normal with inherited global defaults; host-confirmed resume and the
  Apply-triggered restart restore the last successful mode with that mode's saved quality tuple.
- Stage distinct resolution/FPS/bitrate tuples for all four modes and confirm they remain isolated.
  A successfully selected mode whose tuple differs from the live decoder must reconnect into that
  tuple automatically. Same-tuple switches stay live when they retain the ordinary `W x H`
  transport, including transitions into or out of Raw Half. Entering or leaving Raw Full
  reconnects to cross its `2W x H` transport boundary, and changing Full/Half during Raw also
  reconnects. Any other staged edits must be
  committed in the same atomic record before that automatic Activity recreation.
- Normal and Host SBS remain direct and work when Client SBS initialization fails.
- Test all three original-Base ZipDepth aspect graphs from
  `client-sbs-zipdepth-models.tar.xz` on Galaxy XR. Every graph must report
  `LITERT_OPENCL_FP16_GL_IO`. The smoke test enforces
  `LiteRtCompiledModelIsFullyAccelerated() == true`; confirm exactly one OpenCL partition from the
  accompanying LiteRT delegate log, together with OpenCL/OpenGL interoperability, the expected
  fixed tensor layouts, finite non-flat depth, and 163/163-operation coverage. Record archive
  extraction/SHA verification, compile/init, and warm invocation latency for every aspect graph.
- All three original-Base ZipDepth graphs have passed the isolated physical-device graph gate in
  one OpenCL partition with CL/GL interop and finite structured output. Controlled low-priority
  LiteRT median/p95 times are 10.089/10.310 ms for `672 x 384`, 12.991/13.189 ms for `896 x 384`, and
  13.308/13.488 ms for `928 x 384`. These are not end-to-end stream or thermal results.
- The SM-I610 disparity hardware gate has compiled and dispatched all three four-pass conditioner
  shapes without a GL error. Mean repeated-dispatch wall time over 20 dispatches plus a final
  `glFinish()` was 1.336, 1.579, and 1.620 ms for `672 x 384`, `896 x 384`, and `928 x 384`.
  This excludes the inverse-map render, packed draw, LiteRT, decode, and XR
  composition; it is a driver gate, not an end-to-end timing result.
- The SM-I610 raw V2 state gate has verified arithmetic mean publication, first-shot latching,
  ordinary current-mean motion without camera drift, and strict rejection of a field containing one
  NaN. The rejected `32 x 32` field reported `1023 / 1024` valid samples and published neither
  current geometry readiness nor a history advance.
- The SM-I610 offscreen render gate must compile and execute the production exact 1x inverse seed,
  2x-horizontal one-correction refinement, and packed-compose fragments through both `RG16F`
  targets. Its synthetic draw must return the expected left/right gradient samples with no GL error.
- Exercise representative 16:9, 21:9, and 32:9 streams and every bucket boundary. Confirm the
  nearest ZipDepth graph is selected and LiteRT is not recreated during a stable stream. Record
  exact-output cadence, reuse, thermals, depth detail/pop, and visible geometry.
- On every graph, require `Client SBS contractive disparity: R32F WxH`, an initialization log with
  `seed=WxH refined=2WxH`, distinct exact-seed and seeded-refinement validation logs, and the exact
  active path `RG16F 1x 11-iteration seed + 2x-horizontal x1 refinement, packed single draw`.
  Any conditioner, seed-map, refinement-target, correction, or packed-compose failure must draw
  current color flat; a live seed-only, `legacy inverse probe`, cached-probe, or direct-probe path is
  a regression. Before live
  qualification, run
  `com.limelight.sbs.ClientSbsGpuDepthProcessorInstrumentedTest`,
  `com.limelight.sbs.ClientSbsGpuDisparityProcessorInstrumentedTest` and
  `com.limelight.utils.ClientSbsContractiveRenderInstrumentedTest` on Galaxy XR; all conditioner
  shapes and both production fragment stages must execute without a GL error. Exercise moving silhouettes,
  hair/thin diagonals, and high-contrast object boundaries, then
  run at least 15 minutes while recording compose time, output cadence, GPU clock, and thermal
  status.
- Validate that the graph-specific raw scales are exactly `0.04864449`, `0.04707071`, and
  `0.05421491`; the first accepted frame and each accepted cut latch the arithmetic raw mean.
  Ordinary geometry-only cuts require two qualifying updates with no intervening history advance,
  while a qualified appearance cut is immediate. Inject one invalid raw texel and verify the
  current frame is flat and every reliable history owner remains unchanged.
- At 1080p and 4K, compare the exact-area model input against an offline area reference on thin
  diagonals and one-pixel edges. Bilinear sampling is expected only when either source axis is
  genuinely upscaled into the selected model grid.
- Retired DA-V2, MiDaS, and DepthART comparisons remain offline historical evidence in
  `docs/client-sbs-evaluation.md`; their archives must be absent from the assembled APK.
- Left/right eyes are not swapped and the packed split is centered exactly.
- HDR input shows either verified preserved HDR with a high-precision target or the explicit
  BT.709/sRGB tonemap path; SDR shows BT.709/SDR. Direct modes clear Client SBS metadata.
- A hard edit relatches the raw shot camera without resetting immediate temporal depth; ordinary
  motion does not continuously trigger cuts.
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
