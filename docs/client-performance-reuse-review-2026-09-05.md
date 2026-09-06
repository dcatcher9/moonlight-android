# Historical Client SBS performance and reuse implementation — 2026-09-05

This document records the earlier six-opportunity implementation and its historical rationale.
The present-tense descriptions below describe that reviewed revision, not the current application.
Measured evidence remains useful only with its original implementation, APK and workload context.

The current implementation retains the model-file cache, sparse ordinal sampling, R32F conditioner
and deferred tensor packing. It has removed the separate Exact equality/raw-output-cache tier,
its diagnostic counters and temporary classifier benchmark. Near reuse now has no 100 ms or
four-callback expiry: it compares each candidate with the fixed retained real-inference input,
keeps the existing pixel thresholds, and rejects nonmonotonic identity, invalid ownership and
cumulative content change. Reuse cannot renew or replace its own reference. Stats remains visible-only;
background performance logging and temporary live diagnostic controls are retired.

The only current authority is [Android XR SBS architecture](android-xr-sbs.md); current correctness
filters and clearly separated historical measurements live in [Client SBS evaluation](client-sbs-evaluation.md).
No earlier isolated timing establishes performance or sustained quality for this latest policy.

## Historical main finding

The client now distinguishes **identical model input** from the existing **near-identical input**
heuristic and memoizes the expensive model result independently of temporal state. Static desktops
and repeated frames are the intended beneficiaries; motion-heavy video may produce few exact hits.
Approximate similarity thresholds were not loosened. The new exact tier has its own bounded
freshness policy and still processes current temporal and cut state.

The implementation retains one model invocation in flight, two matched color/tensor slots, and
SceneCore's existing output retention. It adds no full-resolution SBS cache or inference queue.

## Reuse that already exists

| Resource/work | Current behavior | Evidence |
| --- | --- | --- |
| Model and compiled delegate | Stay resident across Client/Normal/Host switches within the stream; only one process-wide model owner | [Engine ownership](../app/src/main/java/com/limelight/utils/ClientSbsGpuInferenceEngine.java), [mode lifecycle](../app/src/main/java/com/limelight/utils/Stereo3DRenderer.java) |
| EGL context, programs, model-sized textures | Preserved while paused; locations and native tensor metadata are cached | [StreamContainer](../app/src/main/java/com/limelight/ui/StreamContainer.java), [renderer](../app/src/main/java/com/limelight/utils/Stereo3DRenderer.java) |
| Matched color/tensor storage | Two bounded reusable slots; same-contract resize reallocates color targets only when dimensions differ | [Frame slots](../app/src/main/java/com/limelight/sbs/ClientSbsFrameSlots.java), renderer `applyPendingLiveStreamResize()` |
| Accepted near-identical frames | Skip LiteRT, all depth/cut postprocessing, disparity conditioning, and both inverse-warp draws; adopt current color | Renderer `adoptNearIdenticalReuseLocked()` |
| Exact model input | Retain or copy the last real model's raw output into the current tensor slot; run current postprocessing and compose current color | Native `nativeRun()` / `copy_model_output()` |
| No new adopted output | No repeated SBS compose/blit; SceneCore retains the last submitted buffer | Renderer `drainLatestFrameWithoutSwapLocked()` |
| Compiler artifacts | All three current aspect buckets already survive compiler-cache pruning | Engine `pruneRetiredProductionCompilerCaches()` |
| Extracted models on disk | Retain all three current manifest-qualified files after extraction; only one model remains GPU-resident | `ClientSbsModelAssetCache.pruneStagedModelDirectory()` |

Both reuse tiers still pay for model-input rendering, comparison/cut evidence, the native decision
read, matched full-resolution color capture, and packed composition. With native lazy packing,
neither writes the current Float32 input tensor. Exact hits additionally copy raw output and run
depth/cut/profile/geometry processing; near hits freeze those stages.

## Implemented changes and qualification gates

### 1. Compute only ordinal samples the cut detector actually consumes

The model-input fragment now guards its independent nearest source-point ordinal fetch with the
same nine-anchor mask consumed by the detector. At the current 16:9 model size, logical fetch
opportunities decrease from 258,048 to 9,072. RGB area integration remains unchanged.

The mask uses `floor(gl_FragCoord.xy)` and the FBO extent derived from existing source-size and
downsample-ratio uniforms. Each clipped tile uses axis positions
`{0, floor((validExtent-1)/2), validExtent-1}`; duplicate edge anchors remain valid. Unused alpha is
zero and never enters model RGB. No pass or allocation is added. Logical fetch reduction is not a promised GPU-time saving:
quad execution, branch behavior, and compiler hoisting matter on Adreno.

Owner: [ClientSbsShaders](../app/src/main/java/com/limelight/utils/ClientSbsShaders.java).
The passing source-ordinal GLES regression compares dense/sparse RGB and every consumed ordinal,
including all production shapes, portrait mapping, SDR/PQ math, transforms, and partial extents.
Its Canvas/OES fixture does not establish hardware 10-bit HDR-decoder parity. Sustained timings
remain qualification work.

### 2. Reject impossible reuse candidates before native decision mapping

The renderer now tracks the newest real-inference capture as a conservative upper bound on GPU
owner freshness. It bypasses candidate mapping only when that upper bound rules out both the
original near tier and the new exact tier. A separate counter records these bypasses.

This is **negative-only**: Java can reject, but only authenticated GPU evidence can accept reuse.
Java history presence does not prove GPU owner validity; a reliable-history hold can disable near
reuse while a separate real-model owner remains eligible for exact memoization. Neither reuse tier
renews the CPU real-model timestamp.

Owner: [Stereo3DRenderer.couldHaveReusableModelOwner](../app/src/main/java/com/limelight/utils/Stereo3DRenderer.java).
Qualification must cover both age policies, callback coalescing, held history, generation reset,
and native fallback, then measure map reduction and output cadence with real shared contexts.

### 3. Reuse the final scalar texture as horizontal-conditioner scratch

Horizontal forward now writes its scalar result into the already allocated final R32F target.
Horizontal reverse uses read/write image access, reading each texel's forward value before writing
the combined result. Each invocation owns an entire row. The vertical forward RGBA32F envelope and
separate vertical-finish R32F field remain unchanged.

This removes 12 nominal write bytes per texel, about 2.95 MiB per 16:9 geometry update. It does
**not** eliminate an allocation or dispatch: all four passes remain. Command publication barriers
and the distinct vertical candidate are retained; the reverse pass uses image access rather than
sampler/write feedback. Ordering follows the [GLSL ES memory model](https://registry.khronos.org/OpenGL/specs/es/3.1/GLSL_ES_Specification_3.10.pdf).

Owner: [ClientSbsGpuDisparityShaders](../app/src/main/java/com/limelight/sbs/ClientSbsGpuDisparityShaders.java).
Qualification uses the actual-GLES conditioner differential tests, including edge extrema, invalid
fields, all production aspects, and subsequent inverse-map/compose parity. Time the affected stage
on Galaxy XR; nominal byte counts do not establish a speedup.

### 4. Add exact-input model-result memoization as a distinct reuse tier

The classifier now publishes `EXACT` (decision tag 3, reason 10) in the same authenticated 32-byte
record when every finite RGBA8 RGB texel equals the separately retained last real model input.
Near acceptance still has priority. Exact proof depends on the production RGBA8 model texture;
this representation must not silently be reused for higher-precision model input.

Native memoizes the **raw model result**, not the entire processed presentation:

- Private source ordinals can change while tone-mapped/downsampled RGB stays identical.
- Range, temporal filtering, reliable-history holds, and cut confirmation can still evolve.
- Equal model input does not prove equal full-resolution color: fine text, cursor movement, or
  texture can disappear during model downsampling.

The worker remembers which existing packed Float32 output slot holds the last real model result.
An exact hit copies that data only when the current leased slot differs. The renderer orders its
ready fence and follows the ordinary depth,
cut, range, temporal, profile, conditioner, and map path. Current color is captured and composed.
The classifier separately holds an RGBA8 input texture; each new usable actual inference can
replace that owner even while reliable scene history is held. Exact commits may advance reliable
history from current texture RGB, but never replace the actual-model input owner or renew the
near owner clock. The exact-input texture adds about 0.98 MiB at `672x384`; raw memoization adds
only metadata. Every real invocation invalidates the old output alias before writes and promotes
its slot only on success. Postprocessing only reads those buffers, and existing single-flight and
consumer fences protect writes. There is no extra raw allocation or copy after real inference;
cross-slot exact hits still pay a raw-output copy. Include these costs when measuring benefit.

The initial implementation expired exact ownership after half a second. The subsequent static-input
review removed that timer: unchanged feedforward input does not need another invocation merely
because time passed. Current eligibility and invalidation rules are owned by
[the scheduling contract](android-xr-sbs.md#colordepth-scheduling-and-input-reuse).
Reliable scene history, actual-model ownership, and current-color freshness remain separate state
domains.

Existing model repeatability tests allow normalized MAE below 0.02
in `ClientSbsGpuInferenceEngineInstrumentedTest`; they do not establish bitwise output
determinism. Qualification requires real repeated-input runs and complete depth/cut trajectories,
including alpha-only changes, one-code RGB changes, temporal settling, flashes, cuts, subtitles,
scrolling, and moving video. Owner files are
[SceneCutDetector](../app/src/main/java/com/limelight/sbs/ClientSbsGpuSceneCutDetector.java),
[SceneCutShaders](../app/src/main/java/com/limelight/sbs/ClientSbsGpuSceneCutShaders.java),
[NearIdenticalPolicy](../app/src/main/java/com/limelight/sbs/ClientSbsNearIdenticalPolicy.java),
the native bridge, and renderer. Classifier fixtures with supplied processor-state flags isolate
ownership behavior; they are not substitutes for native cache-copy or full temporal integration.

### 5. Retain all current extracted model files on disk

Authoritative initialization now retains all three current manifest-qualified extracted files,
matching the compiler cache's existing aspect retention. An A → B → A sequence can use the prior
verified A file instead of decompressing it again. Disk retention does not load all three models.

Stale versions and incomplete files are still pruned. Each current file is 12,345,768 bytes: all
three occupy 37,037,304 bytes, about 35.3 MiB. Retained files still pass existing digest validation
before authoritative use. There remains one resident GPU model and one compilation/inference owner.
This targets preparation/reconnect cost rather than steady-state frame rate. Qualification covers
A → B → A, corruption, interrupted publication, and obsolete-manifest pruning. Owner:
[ClientSbsModelAssetCache](../app/src/main/java/com/limelight/utils/ClientSbsModelAssetCache.java).

### 6. Pack model input only when native arbitration requires inference

With the optional native pack program available, classification reads current RGBA8 RGB directly
and leaves the Float32 input slot untouched. The existing worker then packs that same texture only
on INFER, saving approximately 3.10 MB of input writes on a 16:9 near or exact hit. The model texture
stays immutable through native completion and renderer history commit/discard. Cut computation
remains eager and uses the existing decision-read boundary; there is no additional CPU round trip.

Every native INFER outcome packs before calling LiteRT, including failed maps/authentication,
expired or unavailable raw memos, and failed cache copies. The packer clamps RGB to `[0,1]` and flips
GL row `y` to top-first tensor row `H-1-y`; a row-asymmetric parity fixture is required. If lazy
packing or the optional detector is unavailable, eager renderer packing remains the fallback.
Owner: [client_sbs_gpu.c](../app/src/main/jni/client_sbs_gpu/client_sbs_gpu.c), engine and renderer.

The renderer's model-input GPU query now measures render/cut/classification and includes packing
only on the eager path. Lazy packing and raw-memo copies occur in the worker context outside that
query. Their synchronization may affect LiteRT call wall, but no separate pure-GPU time is claimed.
Compare complete capture-to-adoption/output cadence and thermals, not just the shorter renderer
input timer. Qualification must force native map/token/cache failure as well as test ordinary hits.

## Assumptions to challenge carefully

- **"Same model input means the entire result can be retained."** It justifies investigating model
  memoization; it does not establish identical full color or settled temporal/cut state.
- **"Small screen damage means only a few depth tiles need inference."** ZipDepth includes global
  context and the conditioner has row/column dependencies. Local updates require a model/geometry
  design change, not merely a dirty-rectangle cache.
- **"More in-flight frames improve throughput."** The existing single-flight boundary protects
  freshness and shared GPU responsiveness. More queued work can worsen latency and thermals.
- **"A lower panel rate proves faster inference."** The new 60 Hz preference can reduce compositor
  pressure, but its actual panel behavior and sustained thermal effect remain unmeasured.
- **"A new packed-output cache will avoid redraws."** SceneCore already retains the submitted
  output. Skipping compose for a newly decoded frame requires stronger full-color identity than
  model-input equality. Host-assisted exact identity would require its own negotiated extension
  and a correct local fallback on ordinary Sunshine/Apollo.

## Evidence and measurement limits

The initial review inspected source, existing tests, archive member sizes, and evaluation docs.
It did not establish a bottleneck percentage or predicted FPS gain. The implementation now passes
853 JVM tests, seven model-tool tests, and the targeted offscreen/native device gates. Measured
results, fixture limitations, and installation evidence are recorded in
[the evaluation guide](client-sbs-evaluation.md#client-reuse-and-model-internal-review-2026-09-05).
The model-internal scatter experiment preserved CPU/GPU output but provided no useful timing gain;
packaged models remain unchanged. [The model experiment](../tools/zipdepth-output-scatter.md) records
the tested rewrite and the larger resize/projection and learned-tail targets identified by profiling.

For qualification, compare the same APK configuration and repeatable source at the default Client
stream setting with a verified actual panel rate. Capture input/cut, matched-color, depth/state,
compose, decision-read, inference, and capture-to-adoption timings alongside latch/infer/near/exact/
output cadence, candidate-map bypasses, rejection reasons, frame age, and thermals. Use both static/duplicate and moving
content; report them separately. Require the real GLES correctness fixtures and sustained Galaxy
XR results. An isolated LiteRT call-time improvement or a larger reuse percentage alone is not
acceptance evidence.
