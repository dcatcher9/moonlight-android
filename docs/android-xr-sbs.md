# Android XR SBS architecture

This document records the current stereoscopic presentation contracts for Artemis on Samsung
Galaxy XR. It is a current implementation guide, not a proposal. Read it before changing surface
routing, SceneCore entities, Client SBS inference, or in-headset controls.

Artemis is an XR-only build. The manifest requires
`android.software.xr.api.spatial`, and the supported hardware target is arm64 Galaxy XR. The
x86_64 split remains useful for disposable Android XR emulator work, but it does not reproduce the
headset's codec, Adreno, OpenCL, or SceneCore performance.

## Presentation modes

The dock has five choices: **2D**, **Host AI 3D**, **Client AI 3D**, **Game 3D**, and **Movie 3D**.
Host means the streaming computer; Client means this device. These names do not assume a Windows
host, headset, phone operating system or vendor.
This document and implementation cover the Android XR client; these names do not imply that an
iPhone or non-XR Android client has been changed.

`PresentationMode` records the user's intent and owns its saved quality tuple. `Game`,
`StreamContainer`, and `XrStreamPresenter` still maintain one active presentation owner, while
`AuthoredStereoModeState` keeps Movie picture interpretation separate from persisted intent:

- **2D** (`NORMAL`) decodes directly into the SceneCore `SurfaceEntity` in mono mode.
- **Host AI 3D** (`HOST_SBS_AI`, also called Host SBS AI in the algorithm/protocol sections) uses
  the direct stereo surface path; Apollo performs depth inference and SBS
  synthesis before encoding. Apollo fits the packed `2W x H` raster inside both encoder axes with
  one even, aspect-preserving scale; the client's pre-ACK fallback mirrors the conservative codec
  limits, while the applied-state ACK remains authoritative for runtime-discovered limits. This
  mode is enabled only when the host advertises the Apollo-3D session/control extension; it is
  disabled on regular Sunshine and Apollo hosts.
- **Client AI 3D** (`CLIENT_SBS_AI`, also called Client SBS AI below) decodes into an external-OES
  `SurfaceTexture`, runs the native LiteRT/GLES
  pipeline on the headset, and presents its packed `2W x H` output through the SceneCore entity in
  side-by-side mode. `W x H` is the client request/output contract: Client SBS does not apply a hidden
  post-decode resolution cap. Choose a smaller stream resolution when a smaller GPU/compositor
  workload is required.
- **Game 3D** (`GAME_3D`) is the source-neutral entry for ReShade or a compatible game-stereo
  provider. On a host with GameProviderV1 and atomic presentation v2, it enters and resumes with
  `SBS_MODE_GAME_MONO` (`2`), using ordinary mono encoding at the selected `W x H` source size.
  A host-confirmed source permits a client-requested transition to `SBS_MODE_GAME_SBS` (`3`),
  whose encoded raster must be exactly `2W x H`. The game desktop and saved quality remain
  `W x H`; only the packed stream widens. ReShade is the first implemented provider. Selecting
  the mode, a Host AI depth-ready phase, a wide frame, or a saved mode does not prove stereo.
- **Movie 3D** (`MOVIE_3D`) also enters on the ordinary `W x H` stream in 2D. Its manual picture
  choices are **2D**, **Half SBS**, and **Full SBS**. Half SBS splits the frame into eyes and
  preserves the normal logical display aspect, restoring horizontally squeezed eyes. Full SBS
  splits the same captured frame and halves the physical display aspect. Neither choice changes
  the desktop, encoded dimensions, resolution setting or bitrate. They interpret the pixels
  already present; they cannot recover detail lost when a player scaled a movie into its desktop.
  There is no automatic Movie detector or Auto control. The format is volatile: a new presenter,
  reconnect, or re-entry into Movie from another mode resets it to 2D. Movie quality remains saved
  independently of this transient format.

Client SBS requires a mono frame from the host application. Sending `SBS_MODE_OFF` disables
Apollo's Host SBS AI packing, but it cannot un-pack SBS pixels that the application itself already
rendered as SBS. Such a frame would be processed as one mono Client SBS input. On legacy
hosts that negotiate the decoded stream below the client request, Client SBS still produces the
requested `W x H` matched-color/per-eye target by upscaling that lower-resolution input.

2D, Host AI 3D, Game 3D and Movie 3D are direct MediaCodec-to-SceneCore paths. Do not insert a GL bridge,
copy, or Client SBS dependency into them.

Regular Sunshine and Apollo are first-class compatibility hosts for **2D**, **Client AI 3D**, and
manual **Movie 3D** interpretation. Game 3D remains the ordinary mono stream on these hosts too.
Apollo-3D-only controls (Host AI 3D, host depth telemetry/debug dump, and live
video-mode changes) must be capability-gated and must never be sent speculatively to a standard
host. Game source status and modes `2`/`3` additionally require GameProviderV1. Without it, Game
uses `SBS_MODE_OFF` and the source card explains that automatic connection is unavailable.

Client near-identical reuse remains local and works with original Sunshine and Apollo. A separate,
explicitly negotiated host source-identity capability can identify repeated encoder input. Its wire
contract is owned by the companion host's
[Client exact-repeat transport](https://github.com/dcatcher9/Apollo-3D/blob/master/docs/host-sbs.md#client-exact-repeat-transport).
Neither `hostsessionid`, callback counts, zero latency, nor similar decoded pixels imply support.
Missing, malformed, ambiguous or retired metadata falls back to the ordinary local pipeline.

Selecting Game or Movie does not double the capture width. Game waits for explicit source proof
before requesting a packed transport; Movie never requests one.
Quality changes can still require the ordinary guarded live transaction or reconnect. Transitions
to and from the two AI producers retain their existing surface-handoff behavior. After a live
`setOutputSurface()` handoff, reapply the requested surface frame rate because that metadata belongs
to the replacement `Surface`.

### Guarded Game source integration

GameProviderV1 is advertised as authenticated serverinfo `GameProviderV1Supported=1`, host feature
`0x02000000`, and client feature `0x80`; it requires atomic presentation v2 on both ends. Connection
setup freshly gates the initial mode: it may launch Game mono (`2`), never packed Game (`3`). The
presenter issues a guarded mono request after its first frame to establish an acknowledged
presentation generation rather than treating launch state as source authority.

Reliable source status `0x300B` has exactly 20 little-endian bytes:
`{u8 version=1, u8 state, u8 provider, u8 flags=0, u32 presentation_generation,
u32 source_revision, u16 source_width, u16 source_height, u16 packed_width, u16 packed_height}`.
States are waiting `0`, ready `1`, and unsupported `2`; providers are none `0` and ReShade `1`.
Generation and revision are nonzero; every state carries even source dimensions and exact
`2W x H` packed dimensions. A ready status requires a validated provider. Java accepts only the
currently confirmed presentation generation and logical source dimensions, with unsigned
serial-newer revision ordering. Stale, duplicate, malformed, or wrong-generation status cannot
activate stereo. The host repeats current status about once per second, so a status dropped before
ACK/fresh-frame completion can establish proof when its unchanged heartbeat arrives afterward.

An independent host capture/encoder rebuild can advance that presentation generation without a
client request. A valid same-source status with a strictly newer generation triggers one guarded
request to reconfirm the current Game wire mode (`2` to `2`, or `3` to `3`). It does not accept the
new status as stereo proof. Source proof is cleared before posting the request, coalescing repeated
observations; pending transactions ignore observations until their ACK/fresh-frame gate completes.
Stale/equal/ambiguous generations, malformed status and wrong source geometry cannot trigger this
recovery. The existing failure latch also blocks it. Once reconfirmed, the host heartbeat can
establish readiness normally. This keeps source recovery separate from an automatic widening retry.
Failure of a same-mode reconfirmation does not set the widening latch: an ordinary guarded
reconnect starts mono and may establish fresh source proof before widening again. Only a failed
`2` to `3` attempt establishes that latch.

`GameStereoSourceState` keeps source proof separate from intent, actual wire mode, and the
decoder's geometry. Ready in mono requests mode `3` at the same resolution/FPS/bandwidth. The
client closes the decoder gate before sending, validates the correlated ACK's exact packed
raster, then parks, resizes and rebinds the direct surface even when logical `W x H` is unchanged.
Only a fresh matching decoder frame commits SBS interpretation. Ordinary mono encoder fitting is
allowed in mode `2`; an unsupported source remains mono. Mode `3` must never apply Host AI's
codec-fit scaling to authored stereo. Unexpected applied modes or packed rasters fail closed.

Once packed, source loss retains mode `3`; the host fills both eyes with its current mono capture
and the card changes to **Showing 2D**. Valid source recovery changes the card to **3D active**
without another resize. Quality transactions invalidate source proof once the request is queued
and require status for the new confirmed generation. A local decoder-gate or send failure keeps
the unchanged stream's proof and widening-failure latch. ACK and source-status callbacks run on
the same main thread, so queued requests retire the old proof before callbacks can process it.
Exiting Game uses the same ACK and fresh-frame barrier when switching
to OFF or AI. Host AI remains the AI producer even when the host's separate local AR setting
selects ReShade; streamed Game has no global provider toggle or host-restart requirement.

A failed automatic widening attempt latches until explicit Game re-entry or a deliberate relevant
quality change. The latch survives source/receiver/presentation replacement and a same-quality
automatic reconnect; automatic refresh-rate following cannot clear it. A failed ACK may recover
mono only when it proves retained mono state and does not require reconnect. An explicit
`REJECTED_NEEDS_RECONNECT` always reconnects. No failure path repeatedly widens merely because the
host rebuilt its encoder or sent another readiness revision.

The reusable Game subpane retains its independent Resolution/FPS/Bandwidth controls and shows
**Waiting for game**, **3D active**, or **Showing 2D** above them. It does not expose a provider
selector or manual Ready switch. JVM tests cover status ordering, latch behavior, exact raster
validation, visible status changes, and transitions with the decoder/SceneCore boundary mocked.
Physical ReShade-to-Galaxy-XR acceptance remains required. Movie detection is still unimplemented;
its manual format choices are independent of this Game protocol.

### Historical Raw transport compatibility

`HOST_SBS_RAW` and its arithmetic helpers remain only for old data and compatibility tests. Raw is
absent from the dock and Global Settings, and an old last-mode selection restores **2D**. Valid
legacy resolution/FPS/bitrate overrides are copied once into Movie when it has no explicit tuple;
the original Raw data, shared settings and session identity remain preserved. Raw's Full/Half
field is not copied into Movie or used as readiness evidence.

Historically, Raw **Full** treated the selected `W x H` as per-eye dimensions and negotiated a
`2W x H` virtual desktop/stream; **Half** kept a `W x H` packed desktop with `W/2 x H` eyes and a
matching `W/(2H)` physical quad. Full's exact packed width was limited to 8192 pixels; entering or
leaving Full, or changing Full/Half while Raw was live, required reconnecting. That workflow required
a virtual-display-backed launch because physical capture only aspect-fitted the desktop. These
retained legacy semantics do not describe the current Movie format buttons and must not be applied
to Game or Movie merely because a mode is selected.

### Shared stream behavior

The standard **Video frame pacing** list is the only decoder release-policy control. **Prefer lowest
latency** nonblockingly drains ready MediaCodec outputs, discards superseded buffers, and immediately
submits only the newest. **Balanced** alone uses the two-buffer Choreographer queue. The former LFR /
"Prefer lower delays" checkbox was an inverted duplicate and must not be reintroduced.

Stopping suppresses MediaCodec frame-rendered callback work immediately but keeps its callback
looper alive until cleanup unregisters the listener and releases the codec. Only then is the looper
asked to quit asynchronously, so late native events cannot target an already stopped handler.

JNI video storage holds one global byte-array reference across callbacks. Allocation and growth
release their temporary local references immediately because the native video thread remains
attached between frames. Failed growth preserves the existing global buffer and requests an IDR;
initial allocation failure cleans up the Java renderer and fails setup. Frames fitting the current
buffer add no allocation or reference operations.

Playback callback threads request Android AUDIO priority for PCM and DISPLAY priority for decoder
input once on their actual native thread; a denied priority request preserves playback. AudioTrack
writes submit complete interleaved frames nonblockingly, yield for 1 ms on zero progress, and stop
retrying at the 40 ms write/native-backlog bound. Stop, focus transitions, and track replacement
invalidate the packet's playback generation so pre-flush PCM cannot resume on a later retry.
When native pending audio reaches 40 ms, recovery discards stale audio until the queue reaches one
actual device output-buffer duration, capped at 20 ms. Audio focus authorizes writes but does not
start an empty track: current-generation PCM primes the existing device start threshold before
playback begins. Focus loss, stop, and track replacement reset that priming. This avoids playing
through the native startup resync/discard interval. Queue and AudioTrack allocation sizes are unchanged.
Client gain/limiting always applies. Temporary per-sample peak/RMS metering and five-second level
logs have been removed; bounded backlog-recovery warnings remain operational logs. A recovery
warning requires a complete PCM write on the same playback generation; reaching the native queue
target alone does not clear accumulated drops. The warning labels the queue duration sampled before
that write. Partial/zero-progress attempts retain their drop totals until a complete write succeeds.

AudioTrack and focus requests retain `USAGE_GAME` and identify the decoded audiovisual soundtrack
as `CONTENT_TYPE_MOVIE`, which lets Android XR preserve stereo/surround channel rendering.
Spatialization retains the platform default; incoming PCM is not labelled already spatialized.
An earlier stereo opt-out did not change the Galaxy XR's enforced deep-buffer speaker route and
was removed after live qualification. Setup and replacement report requested settings separately from the granted
`AudioTrack.getPerformanceMode()`, actual buffer size and existing start threshold; playback does
not poll these properties. See [XR stereo/surround audio](https://developer.android.com/develop/xr/jetpack-xr-sdk/add-spatial-audio#add-stereo-and-surround-sound-to-your-app)
for the media-content classification contract.

## Direct SceneCore path

Mode entry, HDR changes, and live Client SBS resize share one renderer presentation-completion
transaction. It binds the owning operation to the renderer generation and validated EGL
attachment, and commits once only after a second draw on that same attachment proves the first
swap succeeded. A queued EGL-owner event only requests the confirmation draw; it is not itself
proof of a successful swap. Generation/attachment replacement and owner cancellation
invalidate the proof. The enclosing decoder/resize owner retains its existing fresh-IDR and
cold-backend deadlines; the common proof adds no new timer or inference cadence.

Connection startup retains a cancellable HTTP call scope through response-body consumption.
Stopping cancels that scope before joining the start worker, including established sockets which
do not wake on a Java thread interrupt. Launch, resume, and quit have a 30-second total call
deadline; PIN-entry pairing keeps its separate user-interaction policy. The global connection
permit is released after the startup worker, native stop, and optional bounded quit finish, so
replacement sessions cannot overlap local teardown.

The working Galaxy XR sequence is:

1. Create a `SurfaceEntity` with the appropriate mono or side-by-side stereo mode and request
   `MediaBlendingMode.OPAQUE` before publishing its surface. All five modes present opaque video.
2. Set its surface pixel dimensions so the SBS split lands on the exact half-frame boundary.
3. Parent it to `scene.getActivitySpace()`, enable it, and set alpha to one.
4. Hide the activity's main 2D panel while immersive presentation is active.
5. Give `surfaceEntity.getSurface()` to
   `MediaCodecDecoderRenderer.setRenderTarget(Surface)`.

`ClientSbsRenderSurface` uses a plain Android `SurfaceView` for input/layout and a separate,
single EGL owner for the external SceneCore window. Pause, resume, draw requests, and terminal
close are asynchronous requests. No request lock is held during EGL or renderer calls. Android
holder creation, resizing, destruction, and View detachment never join the EGL owner, so even a
blocked driver call cannot stop the main-thread transition deadline. Exact factory destruction
acknowledges detach; exact renderer attachment validation and the existing two-draw proof
acknowledge entry/resize. A late callback cannot revive a timed-out or destroyed generation.
Resize and HDR entry publish a draw/transfer gate without taking the GL callback lock. An already
running draw may finish with the renderer's old, privately owned dimensions; geometry replacement
and frame retirement still happen after acknowledged EGL detach. Failed resize or Client SBS
surface handoff closes presentation and invalidates its EGL attachment immediately, without
waiting for an in-flight frame drain or GPU allocation. Only successful handoffs use the ordinary
renderer enable/disable setter; failed owners retain the existing stop/reconnect cleanup order.
The EGL context survives normal pause/resume. Context loss rebuilds it on that same owner and uses
the renderer's existing decoder-parking recovery. Events queued from a draw run after its swap;
render requests remain coalesced. Terminal teardown first finishes native renderer cleanup, then
waits asynchronously for EGL release before disposing SceneCore or allowing reconnect completion.
The existing background cleanup coordinator waits for initialization/draw barriers and releases
context-independent decoder objects; the UI never waits for those locks.
If presentation fails while its EGL context remains current, a specific queued finish operation
can still drain renderer writes for native cleanup. It checks that exact context before issuing
`glFinish`; an absent or replacement context cannot acknowledge the old renderer's completion.

2D, Host AI 3D, Game 3D and Movie 3D keep the Android input/layout holder INVISIBLE; it has no
producer on these direct paths and must not leave an empty BLAST layer for the system compositor.
Client SBS shows the holder only after decoder parking, before resuming EGL, and hides it only
after acknowledged EGL detach and direct decoder rebind. Intentional holder removal does not own
stream shutdown. Unexpected holder loss while Client SBS owns it still stops streaming before
renderer cleanup. StreamContainer retains its measured input bounds, and Game activity stop/destroy
continues to own session teardown independently of the holder.

Two historical pitfalls remain important:

- An unparented entity is not part of the rendered scene graph and appears as a black/missing quad.
- The activity's opaque main panel can occlude an otherwise working entity.

Media blending and entity visibility have separate ownership. Preserve entity alpha zero/one
around presentation and HDR transitions; opaque video does not authorize exposing an unfinished
frame. The beta02 media-blending accessors are Java-public but library-restricted, so one narrow
adapter contains the lint suppression and handles unavailable runtime support without aborting
streaming. The adapter does not read the SDK's cached property or report a boolean success: neither
can establish native compositor acceptance or a GPU saving. The retained entity keeps the request across mode changes. The Galaxy
XR runtime in the September 7 capture explicitly reports blending-mode control unsupported on its
API version 3 even while that SDK getter retains OPAQUE. There is no public per-feature support
query in beta02; preserve the best-effort request without claiming native blending is disabled.

`CCodec`'s `onWorkDone` message is not a frame counter, and
`setOutputSurface ... failed to set consumer usage (6/BAD_INDEX)` also appears on working paths.
Use the stream/stat counters and actual consumer callbacks to diagnose flow.

## Client SBS native GPU path

Client SBS has one production inference path:

```text
MediaCodec
  -> external-OES SurfaceTexture
  -> SurfaceTexture crop/orientation transform
       |-> GLES SDR exact-area RGBA8 model-input render + color-cut/reuse classification
       |    (one stream-selected static depth-model aspect bucket)
       |    -> GPU color-cut flag + client-local integrity-checked near-reuse decision
       |    -> input-ready fence -> native arbitration
       |         |-> infer: native Float32 pack -> LiteRT/OpenCL -> depth + raw-output memo
       |         `-> near: retain the committed depth/profile/warp without postprocessing
       `-> full-resolution matched color texture (same slot)
  -> adopt current color and retain its color-slot lease
  -> infer: GLES raw mean + private P2/P98 cut analysis + coherent history commit
  -> near only: freeze every depth-derived and comparison-history field
  -> infer: source-aligned raw R32F ZipDepth -> per-graph coordinate
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

The XR distribution packages the three fixed-shape graphs in one standard solid family archive:

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
no GPU work. Failure is nonfatal so Normal and Host SBS keep their direct paths available even when
Client SBS initialization fails. Speculative staging does not prune a
different aspect bucket. UI-side admission and request deduplication use a separate short lock;
they never acquire the cache-integrity lock held through extraction, hashing, and publication.
Authoritative first-use initialization takes that worker-side cache lock,
revalidates a speculatively staged file once before trusting it, prunes obsolete or incomplete
staged graphs, and gives LiteRT the verified read-only file. The three current manifest-qualified
aspect files remain on disk after they have been extracted; selecting A, then B, then A does not
delete A merely because another bucket was selected. Each current file is 12,345,768 bytes, so all
three occupy 37,037,304 bytes (35.3 MiB). Retention is a filename/manifest whitelist, not permission
to trust unverified content: existing digest validation and atomic publication still apply.
Later authoritative reuse avoids repeating an already completed digest check.
XZ decompression is sequential: selecting a later TAR entry on a cold cache must
decompress the preceding stream even though those earlier files are not materialized. The verified
cache avoids that work on later use.

The selected LiteRT model and compiled GPU delegate remain resident for that stream session after
the first Client SBS activation. 2D, Host AI 3D, Game 3D and Movie 3D submit no Client SBS inference work, but
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
pixels unwritten after a fresh refill, so it failed completeness/parity and its temporary runtime
probe has been removed. Debug and release both use the fixed Low GPU-priority hint; the former
ADB override and async runtime probe are also retired.
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

## Color/depth scheduling and input reuse

Negotiated host source tokens are carried through exact MediaCodec input PTS, output release
timestamps and the latched `SurfaceTexture` timestamp using two bounded primitive metadata rings.
They are copied with each of the existing two color-slot leases. Decoder resets, failed vendor
calls and surface handoffs retire the metadata epoch; renderer generation, output attachment,
transfer and the OES crop/orientation matrix must also agree before reusing presentation.
No decoder output is discarded by this optimization: compressed references and SurfaceTexture
drains continue normally. Only after a valid stereo presentation, with no inference or presentation
transition pending, may a newer proven source repeat skip model rendering/classification, native
decision mapping, matched-color copying and composition/swap. SceneCore retains the submitted buffer.

An identical encoder input can still have improving lossy reconstructions. For each new source,
the initial 500 ms after its first stereo presentation therefore continue ordinary color adoption
before buffer retention becomes eligible. This is a bounded quality heuristic, not proof of decoded
pixel equality; later codec-only refinement is held until changed input or a new IDR invalidates
the source token. Once settled, valid static retention has no expiry and reads no settling clock.
Neither settling nor retention changes the real inference/comparison owner or Near thresholds.
Retention also requires one successful GPU-authenticated Near adoption for the current depth owner.
Every real inference or owner reset clears that confirmation. Allocated textures are insufficient:
rejected raw depth and geometry-confirmation holds must continue ordinary GPU arbitration until
valid comparison history exists. A missing or reset-pending detector disables this shortcut.
The Stats row `Host-proven repeats` counts this work avoidance separately from local Near decisions.
Transport-token wrap is rejected when the unsigned frame distance cannot exclude a full token cycle.

Every real Client SBS inference remains paired with the captured color slot that produced it.
Near-identical reuse presents current color with the committed depth, profile, conditioned disparity,
and warp while freezing all downstream history. It always compares with the retained real-inference
input, never the preceding reused frame. Content, valid ownership, and source ordering control the
reuse lifetime; there is no elapsed-time or callback-gap refresh. This prevents a series of individually
small changes from silently replacing the reference. A candidate outside the unchanged pixel-change
thresholds runs inference again.

The separate Exact-input/raw-model memoization tier has been removed. There is no equality-only
image/history reduction, retained native raw-output alias, or Exact Stats counter. Historical
measurements of that tier remain in [the evaluation guide](client-sbs-evaluation.md); they do not
qualify the current content-driven Near policy. Sustained moving-content quality and performance
must be evaluated with the current implementation.

Scheduling is readiness-driven rather than timer-driven:

- Depth inference has no FPS ceiling, thermal cadence reduction, or forced post-inference idle
  interval. Android thermal status remains telemetry only.
- Surface callbacks may coalesce to the newest decoded frame. The GL thread continues draining and
  latching pending `SurfaceTexture` frames while inference is busy so the decoder consumer does not
  back up.
- Both renderer-owned `SurfaceTexture` generations register on one urgent-display callback
  `HandlerThread`, never the creating thread's implicit Looper. Each registration carries an
  immutable token checked before and after the frame-state lock; surface replacement, live resize,
  HDR transfer commit, and terminal teardown invalidate older tokens. The callback only publishes
  the newest-frame metadata and queues GL work. Terminal teardown requests Looper exit without
  joining it on the UI thread. The thread is created once after renderer contract construction so
  its handler is final across context recovery; it remains idle in Normal/Host modes. This small
  idle-thread cost avoids a lazy-start race with surface registration and terminal teardown.
- A monotonic source-step approximation advances on every accepted decoder callback, including
  callbacks coalesced before one `updateTexImage()` latch. The callback sequence assigned to a
  latch is protected through `updateTexImage()`, preserving monotonic source identity across
  coalesced frames. Callback count is not a reuse-expiry clock.
- Decoder-drain admission yields to every requested presentation draw, including a ready result
  and the stale-depth deadline. Any already-queued drain returns without latching, callbacks retain
  only their newest metadata, and admission reopens at draw entry. Invalid surfaces still reject
  drains. This bounds progress
  to the running drain plus at most one queued drain even when callbacks keep arriving; a bounded
  queue alone would not provide fairness in the EGL owner's event-first loop. Surface invalidation
  closes admission, so drains cannot starve pause/resize work before the next draw.
- A single-flight inference claim prevents the worker queue from growing.
- There are exactly two native input/output tensor slots and exactly two matching full-resolution
  color slots. A capture, its public LiteRT tensors, and its eventual depth result always use the
  same slot index. Reuse adds no raw-output cache, concurrent inference, or result queue.
- The two slots allow one published color/depth state to remain active while the newest uncaptured
  frame is arbitrated. LiteRT invocation itself remains single-flight.
- The renderer submits the model-input fence before the full-resolution color copy. That lets the
  worker begin waiting on the same-slot decision while the renderer finishes capturing the matched
  color, without weakening their shared lease/generation identity.
- The fused classifier compares clamped model RGB with the qualified near owner's Float32 input
  history. Packing, color evidence and Near comparison share the same fetched and clamped RGB
  values. The native lazy-pack path neither writes nor reads the current tensor slot. Apollo's near bounds are
  literal: `16 x 16` tiles, medium absolute channel
  delta `>= 1/64`, strong delta `>= 0.20`, at most 10% medium pixels globally, at most 2.5% strong
  pixels globally, and no more than 75% strong pixels in any tile with at least 64 admitted pixels.
  Every expected input pixel must be finite and admitted; malformed or incomplete evidence forces
  inference.
- A near comparison is eligible only in the same renderer generation, with presentable depth,
  a positive real-owner source sequence, a strictly newer positive candidate sequence, and a
  candidate capture timestamp that does not precede the owner's. Neither elapsed age nor the
  number of intervening callbacks forces inference. Reuse never commits new model-input history,
  renews ownership, or advances the reliable scene/cut tuple.
- Generation/model/input-contract reset, an invalid real raw field, a collapsed V2 field, or a
  reliable-history hold invalidates the reuse owner. Only an accepted valid real-inference
  transaction with advancing reliable history can restore it.
- Java applies a negative-only owner-order check using the newest real-inference capture. It
  cannot authorize reuse or infer GPU owner validity from Java history presence. Native still
  authenticates a fresh decision for every candidate; an old token cannot authorize another input.
- The worker waits on the input fence and reads only a client-local, integrity-checked 32-byte
  decision record. Its final word classifies reuse, content rejection, invalid ownership, or
  invalid evidence without adding another map or synchronization point. Retired Exact tag 3 and
  retired reason values 3, 4, and 10 cannot authorize reuse. This record
  never crosses the network.
  Buffer identity/allocation/range changes and map/unmap failures disable further decision reads
  for that engine lifetime and fail open to LiteRT. A stale token, malformed record, or explicit
  infer decision affects only the current frame, so later valid records remain eligible. This tiny
  CPU map is an Android implementation difference from Apollo's CUDA conditional graph and must be
  measured for stalls on Galaxy XR; no image or tensor is read back to CPU.
- On near reuse, native skips LiteRT and returns a flushed fence through the normal per-slot ownership
  protocol. The renderer activates the current color but freezes model-input history, scene-cut
  history, normalization, temporal depth, profile, conditioned disparity, and cached warp at the last real
  inference.
- Native compiles an optional pack shader once. When available with the classifier, it packs only
  on INFER, including malformed/stale decisions, map/unmap failure, content rejection, and invalid
  ownership. It clamps RGB to `[0,1]` and flips GL row `y` to tensor row `H-1-y`, exactly like the
  eager packer. If lazy packing or the classifier is unavailable, the renderer keeps eager packing.
  The shared model texture stays immutable through worker completion and history commit/discard.
  Cut work remains eager; no extra CPU arbitration round trip was added.
- An inferred result is adopted only after its output fence is ordered,
  depth/profile processing succeeds, and the authorized histories are committed. Only then is the
  single-flight claim released and the next callback-backed frame allowed to arbitrate. This makes
  the reuse owner unambiguous across renderer and inference contexts.
- After an adoption, the renderer queues the next capture from a GL continuation validated against
  both generation and output attachment. The adopted pair therefore returns through its EGL swap
  before the next full-resolution color copy/model submission begins. Callback-driven no-swap
  drains still latch and capture when no newly adopted pair is waiting for presentation.
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
near-identical branch therefore authenticates decoded-pixel similarity, monotonic source identity,
generation, and valid retained depth ownership. Host DDup exact-copy/idle/route decisions could be followed
exactly only through the separately negotiated optional extension described above; legacy hosts
remain on the local path.

Busy claims, occupied mailboxes, or unavailable color slots drop capture opportunities while the
last submitted output remains retained; they must not create an unbounded queue or detach depth
from color.

Fence ownership is part of the slot contract:

1. The renderer renders model input and publishes the client-local decision (also packing eagerly
   when native lazy packing is unavailable), then transfers a nonzero
   input-ready fence and any nonzero previous output-consumed fence for the same slot to native.
2. Native first waits on and deletes the current input-publication fence, then maps and authenticates
   that exact decision record. It waits on and deletes the prior-output/slot-reuse dependency only
   afterward; both dependencies must succeed before either reuse or LiteRT inference. Native
   returns a new ready fence owned by the caller.
3. For real inference, the renderer orders the ready fence, dispatches output
   reads and history commit, then creates a new output-consumed fence. Near reuse and unread/discarded results retain the
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

The vertical forward pass retains its RGBA32F upper/lower/raw envelope, and vertical finish writes
the separate R32F vertical field. Horizontal forward writes the already allocated final R32F
texture; horizontal reverse reads and writes that image in place, with each invocation owning an
entire row. Interpass image/texture barriers remain. This reduces nominal horizontal-forward writes
by 12 bytes per texel (2.95 MiB at `672x384`); it removes neither an allocation nor any of the four
dispatches, and does not establish a measured speedup.

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
only when a real inferred raw result is postprocessed, and near-identical reuse freezes both caches.
The live
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
whole-geometry freeze exception and remains subject to the fixed-owner content and validity checks
above. A
collapsed result may update that private cut/color history, but explicitly invalidates the cached
geometry owner so the next accepted candidate must run real inference again.

Raw outer-edge P2/P98 bounds and their private normalized temporal field remain only for geometry
change, cut analysis, and health diagnostics. They do not scale, center, stretch, or otherwise feed
the V2 disparity field. Subtitle and foreground-ROI plane conditioning are deferred and absent from
the client conditioner.

## Scene cuts and depth health

Scene-cut detection is GPU-only and paired with the exact model-input frame. The existing model
texture keeps area-resized, tone-mapped SDR RGB unchanged. Its private alpha channel carries
`max(R,G,B)` from one fixed decoded source texel before area resizing or tone mapping, using a
separate nearest/clamp sampler and the same SurfaceTexture transform. The ordinal remains in
encoded SDR or PQ codes; it is never model input or presentation alpha. Only the nine consumed
anchor positions in each 16x16 model tile perform the source lookup; other alpha texels are zero.
The mask uses `floor(gl_FragCoord.xy)` and the actual model extent. Each clipped tile uses axis
positions `{0, floor((validExtent-1)/2), validExtent-1}`, including repeated edge anchors. At
`672x384`, this reduces logical ordinal-fetch opportunities from 258,048 to 9,072 without changing
the nine inputs to each median, model RGB, or the number of GPU passes. Actual execution savings
depend on the GPU/compiler. The model draw disables and restores
GL dithering so the private ordinal uses deterministic RGBA8 quantization; area/tone RGB arithmetic
is unchanged. The GLES regression compares both paths with dithering disabled, rather than claiming
byte identity with an implementation-dependent former dither pattern.

Each bounds-safe 16 x 16 workgroup stores average integer Rec.709 model-RGB luma for the broad
raw-change gate, plus the median of a fixed 3 x 3 lattice of source-point ordinals. That order
statistic commutes with a shared global monotone RGB exposure curve (gain, offset, gamma, clamp,
and rounding). Spatial averaging before the ordinal would violate this property: regions with
different texture distributions can reverse their averages under a nonlinear exposure change.
On the small persistent block grid the comparison pass checks all ten pairwise
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
disables both reuse tiers and color-based appearance/exposure authority, and uses bounded
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
baseline, reliable normalized depth, reliable model-input reference, and scene-cut history as one coherent
comparison tuple. Its live P2/P98 range and immediate temporal depth continue to update, and its
finite noncollapsed current raw field still publishes geometry through the existing shot camera.
Confirmation compares the second update with the unchanged reliable owner. A confirmed cut
coherently advances the tuple and latches the new raw arithmetic mean; a failed confirmation clears
the pending state without mixing reliable histories. On Android, the scalar decision is published
at this final resolver and the exact normalized-depth texture is promoted at the beginning of the
next real raw observation, before its depth comparison. Near reuse dispatches nothing and cannot
promote it. A held reliable comparison tuple invalidates Near ownership until a valid real
inference advances the tuple. This preserves the dependency
order without a seventh full-grid pass. Qualified
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
postprocessed real-inference observation, not wall time and not merely
one increment per sparse depth result. It resets on initialization or an accepted cut. Near reuse
and invalid depth do not advance it. Reliable history is
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

Each private depth/disparity processor uploads its immutable sampler bindings, tensor/output
dimensions, packed FP32 stride, coordinate calibration, and spatial scale once when its programs
are created. Per-frame uploads retain source offsets, current evidence, and time-dependent
coefficients. Temporal resets preserve the immutable uniforms; context recreation creates and
initializes new programs.

Depth-health Stats are observations, not geometry inputs. While Stats is visible, a tiny
asynchronous GPU state copy runs every five postprocessed raw observations. When Stats is hidden,
neither the copy nor its GPU-to-CPU map runs. Its enabled poll is
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
> replacement through the guarded `MediaCodecDecoderRenderer.setOutputSurfaceAsync()` transaction.

Mode switches are guarded asynchronous surface handoffs. Keep the decoder target, SceneCore surface
size/stereo mode, renderer generation, and entity visibility synchronized. A stale callback from a
previous generation must not retarget the decoder or publish a depth result.
Register the mode owner before validating its target or closing the decoder gate. A failure before
the host request is queued releases that owner, preserving the current mode and allowing another tap.
Ordinary live binds run on one decoder-owned serial worker, never the UI thread or the urgent
frame-rendered callback thread. Each decoder handoff is bounded at two seconds, and the two-bind
Host resize shares one two-second end-to-end deadline. Timeout invalidates the decoder request
epoch, and an uncancellable native call that returns late restores the retained previous target
while it still owns the codec-recovery lock. The worker checks that absolute owner deadline after
the native call returns and before publishing the new Java render target, so a delayed main Looper
cannot convert a late vendor success into an unowned surface switch. Initial pre-configure
surface selection remains synchronous because `MediaCodec.configure()` needs its final target.
Unexpected GL context recovery is the other deliberate synchronous exception: it parks and
reasserts the persistent dummy before the GL thread releases the retired BufferQueue, while
lifecycle cleanup serializes through the same codec-recovery lock before releasing either target.

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
regular Sunshine/Apollo hosts. The same pre-transition decision applies to every target mode: a
Normal/Host AI switch must not ACK an interim current-mode tuple and overwrite the target mode's
saved resolution before reconnecting into it.
The Client-entry GL thread holds its mandatory initial draw until that fresh decoder callback, so
the EGL owner cannot submit an empty first buffer over the retained SceneCore picture.
The packed-swap watchdog starts only at that decoder-output edge; time spent waiting inside the
decoder's own bounded fresh-IDR transaction does not consume the renderer's proof budget.

Crossing Client SBS ownership or a live Host AI/Game packed-size boundary changes the decoder
target or encoded dimensions. Before those transitions, close the compressed-frame gate and flush
MediaCodec through its all-thread recovery barrier. After the replacement surface is bound, request
a new IDR and admit only a serial-newer IDR before reopening the gate. Game retains ordinary
capture dimensions while its validated packed stream may be wider; Movie's picture-format choice changes only SceneCore
interpretation and aspect.

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

- A genuinely new host session starts in **2D** (`NORMAL`) and inherits Global Settings.
- Resuming the same host session/app, including the in-place restart after **Apply & reconnect**,
  starts with the last successfully applied presentation intent and that mode's saved stream-quality
  tuple. A live mode switch becomes durable only after its surface handoff (and transition IDR when
  required) succeeds.
- Restored Game intent starts mono and requires fresh source proof before widening; restored Movie
  intent starts with its format reset to 2D.
  Neither saved identity proves current stereo content. Legacy Raw restores Normal.
- Panel height is durable per machine and is restored independently of presentation mode.
- Apply snapshots the live quad before SceneCore teardown and transiently hands its effective
  height plus real-world pose to the replacement Activity. This preserves both physical size and
  apparent size from the user's chosen distance; pose is not made a durable cross-session setting.
- Transport, authentication, and pre-frame startup failures preserve the last successful mode;
  fresh launches still start Normal, and only a host-confirmed resume restores it.

An unexpected control-transport disconnect or loss of video traffic during an established stream
automatically resumes the same session while the Activity remains foreground. Recovery reuses the
ordinary native-stop, asynchronous XR/surface-release, and in-place Activity recreation path. It
keeps the established Resume request, saved mode/quality, and captured view pose; it does not apply
staged settings, cancel the host application, or fall back to a fresh launch. Restored Game and Movie
still follow the source-proof and 2D-interpretation rules above. The existing HTTP startup retry
loop runs first; subsequent transient transport/time-out or HTTP 503 failures may schedule another
attempt with 1/2/4/8/8-second backoff, up to five attempts. The budget resets only after a decoded
frame followed by ten seconds of connected foreground playback. This is a retry budget, not a
decision that the host session has expired; fresh serverinfo remains authoritative. A changed or
ended session, authentication refusal, graceful host termination, protected content, or decoder/
conversion failure remains terminal. The dock's Disconnect action and the startup spinner's Cancel
remain available. Explicit exit, backgrounding, or destruction cancels pending recovery; lifecycle
events belonging to the actual recreate operation cannot finish its replacement. No internet
validation requirement is imposed on local-network hosts.

The host's current running-app identity must travel explicitly through the Game intent; elapsed
client time is not a resume decision.
A typed `HostSessionLaunchRequest` carries Start, Resume, or explicitly confirmed Replace authority
from the library to the connection worker. Resume and Replace validate the original app identity
and, for Apollo-3D, the original token against fresh serverinfo before mutating the host. Start never
sends `/cancel`: on the custom host advertising the token contract, `/launch` atomically rejects
active streams and pending handshakes while permitting replacement of an idle retained session.
Standard Sunshine/Apollo still require idle app state before Start. Resume rejects a changed or
expired session instead of silently launching with old preferences. Replace cancels only the
captured session; after successful cancel, its retry authority becomes Start. Standard hosts use
app identity and tokenless cancel. Their protocol cannot detect a same-app generation
replacement without a token. Successful establishment replaces the Activity's launch request with
Resume so Apply and Activity recreation cannot replay replacement authority.

`PresentationMode` is the shared saved intent identity; current Movie picture interpretation is
separate transient presenter state. The current-session record owns the successfully applied intent;
`XrViewStateStore` stores only per-PC panel height. Existing Normal/Host AI/Client AI identities and
height keys remain unchanged. Game and Movie add independent identities. Legacy Raw is retained for
data compatibility but normalized to Normal on startup, including startup overrides and reconnect.
Migration copies only compatible Raw quality into an absent Movie profile, preserves the old Raw
map and all session/resume identity, and records `raw_mode_migrated` on the next atomic write so a
later Movie reset cannot resurrect old quality. This does not reset preferences or pairings.

Live-quality state remains logical `W x H` in the client. On an extension-capable Apollo-3D host,
the `0x3007`/`0x3008` boundary carries base `W x H` control geometry. Host SBS AI performs its
packing inside Apollo; Game and Movie do not inherit legacy Raw's desktop width multiplier.
MediaCodec recovery state instead retains the actual encoded dimensions: packed/capped Host AI,
exact `2W x H` for packed Game, and ordinary mono encoding for 2D, waiting Game, Movie and Client AI.
On a regular Sunshine or Apollo host, every stream-quality change follows the standard
commit-and-reconnect path, and automatic headset-panel-rate following is disabled because there is
no live video-mode control/ack contract.

Atomic presentation protocol v2 reuses reliable control types `0x3007`/`0x3008` only when the
client advertises Moonlight feature `0x08` and Apollo advertises host feature `0x20000000`. Its
little-endian request body is exactly 20 bytes:
`{u8 version=2, u8 desired_mode, u16 flags=0, u32 request_id, u16 source_width,
u16 source_height, u32 fps_x100, u32 total_wire_bitrate_kbps}`. Its ACK body is exactly 28 bytes:
`{u8 version=2, u8 status, u8 applied_mode, u8 flags=0, u32 request_id,
u32 state_generation, u16 applied_source_width, u16 applied_source_height,
u16 exact_encoded_width, u16 exact_encoded_height, u32 fps_x100,
u32 effective_encoder_bitrate_kbps}`. A mode/geometry-changing request closes the decoded-output
gate before it is sent. The client commits it only after a matching, newer applied generation and
a fresh frame at the ACK's exact encoded raster (plus the packed-swap proof for Client SBS). An
FPS/bitrate-only request may settle directly from that ACK, without a new decoded-frame proof, only
when there is no mode transition or logical source-geometry change and the ACK raster exactly
matches the decoder's current output. Failure or ambiguity reconnects without a fire-and-forget
rollback packet.

Compatibility is deliberately asymmetric:

| Client / host | Presentation-control behavior |
|---|---|
| Current Moonlight 3D / current Apollo-3D | Atomic v2 for every live quality request and OFF/AI wire-mode crossing; negotiated GameProviderV1 adds guarded Game mono/packed modes |
| Current Moonlight 3D / upstream/original Apollo or Sunshine | No proprietary presentation controls; stream-quality changes use the standard reconnect path |

Older Moonlight 3D clients and pre-v2 Apollo-3D hosts are outside this compatibility contract.
There is no `0x3003` presentation command and no 12-byte request or 14-byte ACK fallback; live
presentation control is available only after mutual atomic-v2 feature negotiation.

This live presentation transaction is independent of offline whole-clip conversion. In particular,
the offline/online no-lookahead policy does not change these control packets or their ordering.

Artemis stores exactly one current-session record per PC. A new host app replaces that record;
resuming the same host app preserves it. The record contains shared stream overrides, per-mode
overrides, the last proven presentation mode, and a local generation ID that rejects stale panel
writes. Global Settings remain the inheritance source across PCs and sessions; a current-session
override is stored only while it differs from its global value.

Each of the five presentation modes owns an independent stream-quality tuple: **resolution, frame
rate, and bitrate**. Changing one mode's tuple never changes another mode. Selecting a mode whose
saved or newly staged tuple cannot apply live commits the complete staged session record and
reconnects into that tuple before any host presentation request or surface handoff. This also applies
when any staged setting requires a reconnect, even if the target quality tuple already matches the
live stream. An interim mode ACK must not persist a new HDR/codec choice before the stream adopts it.
Committing the
whole record ensures that shared or other-mode edits cannot be lost when the Activity is recreated.
Live-compatible quality changes retain the guarded ACK and first-frame completion paths. Without
staged reconnect-only work, a same-tuple Game/Movie/2D switch remains live. Entering Game starts
mono; leaving packed Game uses a guarded transition back to the ordinary stream. The AI modes
retain their guarded wire-mode and decoder-ownership transitions.
**Apply & reconnect** remains the explicit action when no mode-quality or transport change already
requires a restart. The Client SBS ZipDepth aspect graph is derived from the pending Client SBS
resolution and is not an independent setting. Movie's manual format is not a saved preference and
is not committed with quality or used to change stream geometry.

The resolution ladder keeps its six established landscape choices first, then adds twelve common
phone/tablet source sizes. One explicit portrait counterpart for each of the eighteen landscape
choices follows by swapping `W` and `H`, for 36 choices in Global Settings. Each mode's compact
in-session subpane presents the original six landscape choices and their six portrait counterparts;
phone/tablet presets remain global choices rather than filling the contextual pane. If a mode
inherits one of those presets or another non-subpane size, the pane preserves it as the selected
Custom card until the user chooses a compact preset. `XrResolutionOptions` owns both orderings and
their IDs; the Android resource arrays mirror the Global Settings ladder. These are source/virtual-
desktop dimensions displayed in XR, not additional Android device targets. The compact
phone/tablet labels map to these exact landscape requests:

| Label | Source dimensions |
| --- | --- |
| Phone 18:9 | `2160 x 1080` |
| Phone 19.5:9 | `2340 x 1080` |
| Phone 20:9 | `2400 x 1080` |
| Phone wide | `2424 x 1080` |
| Tablet 1200p | `1920 x 1200` |
| Tablet 1600p | `2560 x 1600` |
| Tablet 4:3 | `2048 x 1536` |
| Tablet 4:3+ | `2732 x 2048` |
| Tablet 3:2 | `2160 x 1440` |
| Tablet 1640p | `2360 x 1640` |
| Tablet 1668p | `2388 x 1668` |
| Tablet 1668p+ | `2420 x 1668` |

The phone/tablet entries retain the existing Client SBS aspect handling: landscape input selects
the nearest packaged landscape graph and directly resizes to it, while portrait input uses the
reflected aspect-fit path described below. They do not add Client SBS tensor shapes. Host SBS
source-to-tensor routing is defined separately in the companion host's
[Host SBS contract](https://github.com/dcatcher9/Apollo-3D/blob/master/docs/host-sbs.md).

All portrait IDs are literal host/virtual-display requests; they do not toggle Android's
resolution-inversion option or rotate
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

The settings truly shared by all five modes are **codec, video frame pacing, HDR, Full/Limited video
range, audio layout, and play audio on the host PC**. The Session Settings pane edits only this
shared set. Global Settings provide the cross-session defaults for both the shared set and the
quality baseline inherited independently by each mode.

The factory baseline for a fresh install is **3840 x 2160 at 90 FPS, 200 Mbps, HEVC, HDR, Full
range, and latency pacing**, with stereo audio and host audio off. There is no global Raw packing
picker. Client SBS always uses ZipDepth. In-session **Use global defaults** inherits the
values currently saved in Global Settings rather than forcing this factory baseline. A mode row's
**Use session settings** discards staged edits and restores that mode's durable current-session
values, falling back to its current global values where no session override exists.

2D, Game, Movie and Host AI 3D therefore begin with a durable **90 FPS ceiling**. Client SBS
defaults to **1920 x 1080 at 30 FPS with a 72 Hz panel preference**. A headset panel/thermal
transition may temporarily lower the effective on-wire rate to an offered rung, but it never
rewrites the selected ceiling; the host
automatically follows the panel back upward, at most to that ceiling, when the panel recovers. The
SceneCore presentation Surface advertises the durable ceiling rather than the temporary effective
rate so a recreated output swapchain cannot pin the panel at a throttled mode. Client SBS votes
`max(72, durable FPS)` and, while its durable ceiling is at most 72, also requests the advertised
72 Hz window mode at the current physical resolution. The window preference uses that mode's
actual rate (`72.00001` on Galaxy XR). The headset's display service rejected an unadvertised 60 Hz
refresh-only request, so the client uses an advertised mode and does not request a hidden mode ID
or an unadvertised window rate. If no matching 72 Hz mode is advertised, the previous window hints
remain in effect while SceneCore retains its 72 Hz Surface vote. This panel preference does not
raise the 30 FPS stream or inference cadence. Android XR
retains final control of the physical refresh rate. The scoped window preference restores the
previous window hints when leaving Client SBS, committing a higher durable ceiling, or destroying
the presenter; a temporarily capped ACK cannot keep a higher user ceiling pinned to 72. In Client
SBS the decoder writes to the renderer's offscreen `SurfaceTexture`; that input is not the
display-rate authority. The actual `SurfaceEntity` output receives the mode-aware durable vote
after every `getSurface()` replacement, committed presentation-mode change, and successful explicit
ceiling change. The input/layout holder remains neutral; only the external SceneCore output
receives the vote.

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

**Apply & reconnect** commits every staged shared setting, every per-mode quality tuple, and the
selected startup intent as one guarded record replacement. The Client SBS model is fixed; Movie
packing remains transient. It then waits for
decoder and deferred GPU/XR cleanup before recreating the singleTask `Game` activity in place. The
old Activity's ordinary no-history stop path must not finish this intentional replacement, so the
stream resumes immediately instead of exposing the application grid. A stale panel generation
cannot write into a replacement session. Legacy records that stored quality as shared values are
read compatibly and are expanded into all mode scopes on the next atomic commit.

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

Force presentation output alpha to one. External-OES video may sample with alpha zero, which
otherwise makes the SceneCore quad transparent or black. The private model-input texture instead
uses alpha for its independent source-point scene-cut ordinal; RGB tensor packing ignores alpha.

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

All seven in-stream panel root canvases fill their complete bounds with an opaque background.
Panel corner radii are zero and local alpha is one; hidden/loading panels retain their existing
visibility gates. Collapsing the dock no longer dims the glance strip. The collapsed dock crops
both its raster and physical quad around the reveal pill at the same metres per pixel, restoring
the original raster on reveal, so a solid root does not expose an empty full-width slab. These
changes guarantee solid panel content; SceneCore does not expose the hosted panel surface's
compositor blending flag. In beta02, panel metres and raster dimensions are coupled through the
runtime pixel density: `setSize()` converts back to `setSizeInPixels()`. Entity scale is the
separate multiplier. The dock uses one pixel-size request, preserving scale, rather than a second
metre-size request that would overwrite its cropped raster.

A passive glance strip sits above the video and never intercepts input. It keeps the PC/application
identity, active presentation mode, live stream tuple, and reconnect/status cue visible without
requiring a pane. The main dock remains level at its fixed pose beneath the video. Opening or closing
another surface must not move that dock.

The contextual mode panel is anchored directly below the dock and pitches upward toward the
viewer while leaving the dock pose unchanged. When fitting mode content between its 0.52 m
baseline and 0.90 m cap, keep the hosted Android raster and physical quad consistent with the
runtime pixel density and entity scale. Derive target dimensions from the original raster/metre
pair so repeated mode refreshes cannot accumulate rounding drift; retain the whole-pane
`ScrollView` beyond the cap. Session Settings opens to the **left** of the video;
its inner edge remains anchored outside the video and the panel yaws inward toward the viewer's
face. **Stats** uses the **right** side as a compact, single-column panel whose
inner edge is anchored just beyond the video's right edge. It yaws inward around local Y so its
outer edge wraps toward the current head position, with a clearance limit preventing it from
approaching the viewer too closely. Its Android raster and physical height grow together with the
visible rows from the authored 1920 x 1440 / 1.05 m baseline to the authored 2538 px / 1.85 m
cap; actual SDK raster dimensions remain subject to runtime density and integer quantization.
Only content beyond that cap uses the bounded vertical `ScrollView`. Recompute side-panel
poses when they open, on video resize/mode change, after screen movement/Cinema View, and on the
existing slow Stats refresh. Never poll head pose from the video frame loop or while the associated
side panel is hidden.

Presentation intents form one single-select group. Navigation/disconnect actions remain separate
one-shot controls. A new session highlights 2D; a resumed/restarted session highlights its
restored intent only after that mode is active. Highlighting Game or Movie does not assert stereo:
their picture state begins in 2D, and Game stays mono until the negotiated source and frame gates pass.

The Host AI 3D tile and host debug action are disabled when `/serverinfo` does not advertise the
Apollo-3D session/control extension. 2D, Client AI 3D and manual Movie interpretation remain
available. Game remains available on all hosts; without GameProviderV1 it stays mono and shows
automatic source connection as unavailable. No host capability is inferred from its operating system.

The five mode tiles live in one level toolbar `PanelEntity` and share one contextual
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

2D and Host AI rows also show their presentation/source status. Client AI adds only its fixed
ZipDepth identity, resolution-derived aspect bucket, and live GPU backend status; it has no model
selector, strength, convergence, balance, or depth-inference cadence controls. Game's card identifies
ReShade or a compatible provider and reflects host-confirmed source status, without a provider
selector, Ready toggle, or image heuristic. Movie's card exposes only the implemented 2D/Half SBS/Full SBS interpretation
buttons, with no Auto choice. Restoring
values is scoped:
the shared pane's **Use global defaults** stages the currently saved global shared values, while a
mode row's **Use session settings** restores only that mode's durable quality tuple. It does not
restore transient Movie packing or reinterpret legacy Raw's Full/Half field.

The Settings tile opens the left side panel for values shared by every mode in the current PC
session. Its six controls use two short semantic columns: Video (HDR, range, codec) and Delivery
(pacing, audio layout, host audio), with large XR-readable labels, choice targets, and status text.
Each setting is a distinct raised card under a strong semantic heading; mode options likewise group
resolution, motion, bandwidth, live state, and Client SBS depth details into visually separate
surfaces rather than one undifferentiated row.

**Use virtual display only while streaming** defaults to on in Global Settings → Streaming
defaults. It is a global reconnect-time preference rather than an in-session setting. The client
trusts `VirtualDisplayOnlySupported=1` only from authenticated `/serverinfo` and sends
`virtualDisplayOnly=1|0` on `/launch` and `/resume` only to a supporting host. The client sends the
choice independently of the legacy `virtualDisplay` launch flag because Apollo's generated
Virtual Display tile is classified by the host. The host applies the choice only to a
virtual-display-backed session; physical-display streams are unaffected. Older hosts receive no
new parameter and retain their existing behavior. When an approved AR-glasses output must remain
active, the host automatically keeps the shared Windows cursor on the virtual display. The owning
host behavior and protocol are in
[Virtual desktop interaction](https://github.com/dcatcher9/Apollo-3D/blob/master/docs/virtual-desktop.md).
This client/host feature still requires live Galaxy XR verification.

Keep **2D**, **Host AI 3D**, **Client AI 3D**, **Game 3D**, **Movie 3D**, Settings, Cinema, Stats,
and **Disconnect** visible in the dock. Host AI describes processing on the streaming computer;
Client AI describes processing on this device. These are separate buttons with separate saved
quality, not a combined AI tile with a second processor-selection step.
Debug builds append **Dump 3D** immediately after **Disconnect**. All tiles have the same width;
the panel width follows the actual button count, with no secondary-action expander.
Disconnect stops streaming and returns directly to the current PC's application library without
an intermediate machine-selection step or `/cancel`. The explicit dock action cancels reconnect
and starts the existing asynchronous connection shutdown before navigating, so Android's activity
transition cannot postpone the host disconnect. Later lifecycle callbacks reuse the idempotent stop;
native shutdown still precedes decoder/EGL/XR resource destruction. The host retains the session for
its resume grace window. There is no separate Library or End session tile in the immersive dock; explicit
End session remains available in the application library. Stats is a direct one-tap toggle.
Ordinary host controls require an active connection and a Game activity that is neither finishing
nor destroyed. This closes the interval between Disconnect's `finish()` and `onStop()`: late panel
refresh observations and queued quality changes cannot reconfigure the retained host session.
Queued first-frame, decoder-transition and packed-swap completions follow the same guard, so they
cannot commit a mode or quality, or initiate recovery, after Disconnect. Teardown still owns their
generation invalidation; rejected late callbacks do not roll back host state. A valid first frame
may arrive while Game is connecting, before `connectionStarted()`, and remains eligible to commit.
Initial panel observations are still retained until the stream becomes ready.
Stats visibility is independent,
so it stays open while left-side Settings or the lower mode row opens. The Stats choice is
persisted so an in-place
reconnect or activity recreation cannot silently clear it. All controls remain ordinary clickable
Android `View`s grouped within their respective panel; never create one entity per control.

**Cinema** follows the mode-tile interaction: the first tap enters its screen size/pose preset using
the saved environment; tapping the active tile toggles its contextual row. The same passive chevron,
header/card styling, connected choices, scrollable panel and fitting policy are reused. Cinema and
mode options occupy the same lower `PanelEntity`; Settings remains mutually exclusive and Stats
remains independent. **Exit Cinema** restores the screen and closes the Cinema row.

The environment choices are **Black** (default), **System environment**, and **Passthrough**.
`list_cinema_environment` persists this selection and is also available in Global Settings.
Changing it during Cinema applies the background immediately without changing screen size/pose.
Black requests an empty non-null app environment and zero passthrough; System environment clears
the app environment preference and requests zero passthrough, using the environment selected in
the headset settings. Full passthrough requests opacity one and leaves the environment preference
untouched. The public [SceneCore environment API](https://developer.android.com/reference/kotlin/androidx/xr/scenecore/SpatialEnvironment)
does not expose the headset vendor's named environment catalog.

Video decoding and publication continue normally. Each override saves and later restores only the
preferences it owns, preserving newer external preferences. It releases them before activity stop,
disconnect and scene teardown; a same-activity foreground return reapplies the saved choice if
Cinema remains active. Black/System require environment and passthrough control; Passthrough needs
only passthrough control. If the required capabilities are unavailable, the screen preset remains
usable. Runtime application is asynchronous. Temporary shell-controlled scene overrides remain
removed.

Enum values in both Global Settings and the current-session panel are ordinary buttons in one
connected segmented surface, not radio dialogs or cycle-only rows. Compact choices use equal-width,
single-line horizontal segments with an 80 dp minimum gaze-target height. If every localized label
cannot fit, the entire control becomes a
full-width connected vertical stack with up to two lines per choice; never produce a ragged wrap or
make the user scroll an enum sideways. Bitrate follows the same direct-manipulation rule with its
six-rung connected segmented ladder; do not regress it to an inline slider or bandwidth meter.
Client AI has no model selector: its Options row configures stream quality while Stats reports the
active ZipDepth aspect graph. Movie uses a direct **2D / Half SBS / Full SBS** picture-format group;
this describes the existing picture and does not advertise a newly negotiated per-eye resolution.
The obsolete Raw **Per-Eye Resolution** picker is removed from the dock and Global Settings.
Tapping the running application card resumes it directly; a
compact close button in its top-right corner ends the session. More stays in the bottom-right and is
reserved for secondary actions such as details, hiding, and shortcut/export tools. The compact card
aspect fits one complete row inside the Galaxy XR library viewport even while the current-session
banner is visible, so a single row never creates a pointless vertical scroll range.

After the first decoded frame, the dock may **soft-collapse** after eight seconds of true idle. This
does not disable or move the dock `PanelEntity`: it hides only the full control row and leaves a
centered, gazeable reveal pill showing the active mode and current status. The passive glance strip
keeps its normal brightness. The pill has a 320 dp minimum width, the 80 dp choice-control minimum
height, title-sized text, and a visible accent outline that brightens on hover/focus/press. Its
actual clickable View supplies the larger target; the collapsed panel crops around that measured
View at the existing scale and remains bounded by the full dock raster.
Hovering, focusing, or activating a full-dock control reveals the row and restarts the timer. While
collapsed, passive hover alone keeps the pill stable; the first focus/press generated by an explicit
pinch reveals the row (with click activation retained for keyboard/controller input), so a newly
exposed mode tile can never replace the target beneath the pointer or require a second pinch.

Auto-collapse is allowed only while session controls are enabled, no dock child is hovered or
focused, no Settings/Stats/mode-options pane is open, no reconnect-required change is pending,
and no mode switch, decoder handoff/IDR gate, or depth-engine transition is active. If any guard
becomes active, cancel the timer and keep the full dock visible so work and Apply actions cannot be
hidden. This is a soft visibility policy only; it must not alter the dock pose or presentation mode.

### Stats content and telemetry

Stats remains available in every presentation mode. The removed performance-logging switch no
longer enables background sampling or periodic `ClientSbsPerf` / `DecoderPerf` log lines. Startup,
capability, transition, failure, and bounded playback-recovery logs remain available. Closing Stats
stops its device/CPU sampling, detailed Client-SBS counters, GL timer queries, and asynchronous
health-copy polling; reopening starts fresh sample windows rather than including hidden time.
Host SBS depth-health telemetry follows the same visibility rule: hiding Stats unsubscribes,
cancels subscription retries, and clears its chart history; reopening requests fresh samples at
100 ms. Late replies cannot restore a hidden subscription. Operational host depth/mode status and
the host's independent diagnostics switch remain active under their own policies.
Host Stats uses the current 240-byte telemetry v2 extension. Original Sunshine/Apollo hosts
without this extension remain supported and report Host telemetry as unsupported. The pane
reports publication sequence/receive age and GPU-copy sequence/receive age separately: a
transport heartbeat keeps the connection live without making an old GPU observation fresh.
With host diagnostics enabled, consecutive cumulative GPU outcome copies provide infer/reuse/
invalid rates using their host copy timestamps, independent of network delivery jitter. Reuse
percentage excludes invalid outcomes. Output warp/packed-repeat/flat rates have their own sample
clock and denominator. Duplicate copies do not manufacture zero rates; absent, stale, reset,
regressed, or ambiguously wrapped baselines require two fresh samples. Hiding Stats clears these windows.
Host stage means are explicitly labelled averages since mode change, with sample counts and
unavailable stages shown as not sampled. CPU conversion/encode wall time, source content age, and
GPU stage durations overlap and are not a sum of end-to-end latency. Without host diagnostics,
the pane explains how to enable performance sampling while retaining basic depth health.
Hidden decoder windows retain aggregate stream/loss accounting without allocating completed-window
snapshots or histogram copies, and do not sample a pruning clock for absent timing records. Visible
Stats retain unchanged row text and share one pending panel-sizing callback, cancelled when the
panel is hidden or destroyed.

The pane shows negotiated codec/profile and Android decoder, low-latency component/options,
output-pacing policy, sender/receive/output/release/surface FPS, network/host/decode latency, app
CPU core-equivalent load, device GPU busy/clock, and Android thermal status. Client SBS adds
model/backend/input shape, latch/inference/reuse/output FPS, candidate-map bypasses, reuse ratio,
content/invalid rejection counts, decision-read wall, LiteRT call wall, real result age, and four
separate GL GPU averages. Exact reuse and age/frame-gap rejection counters are retired.
On updated Apollo-3D hosts, host processing latency measures the current conversion pass through
packet publication; retained encoder-input repeats carry no processing sample. Accurate source
content age remains a separate host diagnostic measurement, not part of this processing average.

The four GPU regions are model render + color-cut/classification, matched-color copy, raw-V2/cut
state, and disparity conditioning + inverse maps + packed draw. Nonblocking
`GL_EXT_disjoint_timer_query` rings poll only ready results and discard clock-disjoint samples;
unavailable measurements must not be represented as measured zero. The model-input region includes
eager tensor packing; native deferred packing runs in the worker context outside that query.
These are sampled GLES durations, not per-process utilization. They cannot time LiteRT's OpenCL
execution or SceneCore composition and must not be summed into device GPU busy.

`LiteRT run call wall (not pure GPU)` includes runtime overhead and blocking within the LiteRT
call and excludes reuse. `Decision read avg / max` brackets validation and the authenticated
32-byte map/copy/unmap; immutable object/range checks are cached after first success. The map can
wait for pending GPU work despite the earlier server-side fence wait, so its wall time overlaps
the input/classifier dependency and is not additive with the GPU stage duration. Real depth-result
age measures capture-to-adoption latency. It is neither a Near expiry clock nor final XR latency.

The depth policy remains `Uncapped | one in flight | newest frame when free`; thermal status is
telemetry, not a hidden throttle. Near reuse compares current model pixels with the retained real
inference owner and can continue indefinitely while content and ownership remain valid. It does
not renew its owner from the preceding reused frame. A static source need not produce identical
lossy decoded pixels; reuse remains a thresholded content decision.

The fault row retains occupied color slots, flat outputs, invalid raw transactions and collapsed
cut ranges. Expected callback coalescing, latest-frame replacement and single-flight busy events
are scheduling behavior rather than faults. A valid held depth field must show `ready yes` and
`history hold`; holding the reliable comparison tuple does not itself make geometry unrenderable.
Raw means, fixed pop, current validity, cut evidence/causes and accepted-cut counts remain the
compact health state. Retired stretch/recenter/subject/Bestv2 and adaptive-pop state is not live V2.

Health copies contain the 224-byte depth state only; the Exact evidence tail is gone. Copies,
fences and maps remain asynchronous, failures back off without disabling valid depth, and recovery
requires a fresh completed sample. Near reuse freezes postprocess health. Client history advances
with visible five-observation sampling. Host trends retain their 120 samples at the visible
100 ms cadence. All plots use oldest-left/newest-right sample slots, not an invented
fixed wall-clock axis; repeated host heartbeat publications do not duplicate event points.

GL latches may outpace inference/reuse adoption while the transaction is occupied. Composition
and swaps follow real inference plus reuse adoption; drains without adoption retain the existing
SceneCore buffer. Repeated packed draws without adoption are a regression. There is no managed
CPU tensor path or Java postprocess worker. SceneCore final presentation timing and per-app NPU
utilization are unavailable; custom CPU/GPU temperature probes are not part of Stats.

### Spatial UI learnings

These rules come from Galaxy XR observations across SceneCore alpha16 through beta02:

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
- Hiding a `PanelEntity` does not hide its hosted Android view or stop indeterminate drawables.
  Synchronize animated panels with their Android root visibility, hidden before attachment and
  whenever the panel is inactive; use `INVISIBLE` when its measured raster bounds must survive.
  The depth-status spinner is visible only during its delayed loading indicator, and ready, idle,
  mode reset, and destruction hide the Android root to stop its animation.
- Host depth-preparation status is a transient `PanelEntity` centered on the video and offset
  slightly toward the viewer. Phase 1 means process-wide engine preparation; phase 3 means
  per-stream GPU-pipeline setup for the already resident model.
- Immersive screens need explicit in-app navigation because the hidden main panel provides no 2D
  system back affordance.

## Jetpack XR dependencies and minification

SceneCore, runtime, runtime-openxr, ARCore, and arcore-openxr are pinned together to
`1.0.0-rc01` (2026-09-09; beta02 and the earlier DP4/beta releases preceded it). Keep the five artifacts
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
- A new session starts 2D with inherited global defaults; host-confirmed resume and the
  Apply-triggered restart restore the last successful intent with that mode's saved quality tuple.
  Game starts mono and revalidates the source; Movie resets to 2D on resume. Replace a running app, then Apply/reconnect
  and switch among mode quality tuples; neither intentional
  resume may replay the initial replacement authority. Race an active session against Start:
  the custom host must reject `/launch` without any client `/cancel`, while an idle retained session
  can be replaced through `/launch`. Standard hosts retain the client's running-app rejection.
  Race a different app against Resume and grace expiry against Resume; neither may cancel a
  successor or launch with the expired session's preferences. With landscape Normal and portrait Host
  AI saved separately, switching in either direction must reconnect into the target tuple and
  preserve both resolutions.
- Stage distinct resolution/FPS/bitrate tuples for all five modes and confirm they remain isolated.
  A successfully selected mode whose tuple differs from the live decoder must reconnect into that
  tuple automatically. Same-tuple switches stay live through the applicable ACK/frame barrier,
  including exit from packed Game. Selecting Game begins in mono; only validated source readiness
  may widen its encoded stream. Neither Game readiness nor Movie format doubles the desktop.
  Movie format never changes encoded width. Any other staged edits must be
  committed in the same atomic record before that automatic Activity recreation.
- Game stays mono on hosts without GameProviderV1. On a supporting host, exercise source status
  before and after ACK/frame completion, exact packed raster acceptance, unsupported mono fitting,
  source-loss duplicate-eye fallback, overlay/focus recovery, and exit to OFF/AI. Wide frames and
  Host AI depth-ready callbacks must not activate Game stereo. A refused widening must not retry
  after new source revisions or automatic reconnect; explicit re-entry or quality edits may retry.
- Movie initially presents 2D. Explicit Half SBS preserves logical aspect; Full SBS halves the
  physical aspect for the same captured raster. Leaving and re-entering Movie, creating a new
  presenter, or reconnecting clears the manual format while retaining Movie's quality tuple.
- Old Raw last-mode records restore 2D and preserve session identity and original data. Only
  compatible quality copies to an unset Movie tuple, with no width multiplication or format copy;
  explicit Movie settings and later defaults resets must survive without repeated migration.
- 2D, Host AI, Game and Movie remain direct and work when Client SBS initialization fails.
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
  inward-yawed left Settings pane, or wrapped right Stats pane opens. Disconnect remains visible
  immediately before Dump 3D in debug builds.
- Disconnect returns to the current PC's library without requesting host quit; Resume stays
  available during the host's grace window. End session in the library explicitly cancels it.
- After eight idle seconds the dock leaves its reveal/status pill, then expands on the first
  explicit press/pinch.
  It must remain expanded while a pane, pending Apply, depth preparation,
  mode/decoder transition, or focused/hovered control is active.
- Repeated disconnect/resume/mode switches do not leak surfaces, entities, EGL contexts, leases, or
  fences and do not recreate LiteRT during a stable stream.
