# Client, Host AI and Game 3D pipeline audit

## Current migration review (2026-10-23)

This review uses the local Apollo-3D checkout at `12e5266c` and the Android XR implementation.
It replaces the September 4 comparison against `e827a6fc`. That comparison described a
shot-latched mean, nonlinear far/near curve and soft container which the host retired on
October 5; those are no longer the target for Client AI 3D.

The source of truth for Android geometry, model calibration, timing and surface ownership is
[Android XR SBS](android-xr-sbs.md). The companion host's
[Host SBS contract](https://github.com/dcatcher9/Apollo-3D/blob/12e5266c218845b217f3f36a210db15b5038ccca/docs/host-sbs.md) and
[Game 3D contract](https://github.com/dcatcher9/Apollo-3D/blob/12e5266c218845b217f3f36a210db15b5038ccca/docs/reshade-sbs.md) own their respective algorithms.
This audit records shared behavior and remaining differences; it does not define another
calibration or establish equal output quality.

The inherited Client AI graph priors are not newly qualified amplitude floors. The October 23
calibration review reproduced the old fit and audited the saved raw observations, but those used
historical preprocessing/runtime and lack source clocks. It retained production constants and
identified the current-runtime evidence needed for any recalibration in
[Client SBS evaluation](client-sbs-evaluation.md#model-calibration-and-stats-review).

### What changed on the host

| Date / commit | Change relevant to the client |
|---|---|
| August 28 / `4fc42fbc` | Consolidated Host AI on the fused DAV2 Small plus frozen ZipDepth convex-2x runtime and shared live/offline geometry core. |
| September 29 / `7b19c442` | Tiled Host vertical/horizontal limiters and mechanically ported them into Game 3D. Shared conditioning moved from Host to Game; the depth producers remained separate. |
| October 5 / `f9d0a4ca` | Made continuously observed mean zero and robust relative amplitude, linear calibrated displacement, and the adaptive subtitle plane the sole Host AI policy. Removed the shot-latched camera, nonlinear curve, soft container and experimental selector. |
| October 6 / `cf0330ef` | Allowed two desktop Host SBS pictures in flight through NVENC, following the existing Game 3D encoder strategy. This changes scheduling, not depth calibration. |
| October 22 / `492dd231` | Replaced coarse subtitle-cover conditioning with native temporal pixel evidence, Gaussian Base/UI-plane blending and shared post-blend slope conditioning. Current OCR covers provide provisional protection while native evidence acquires. |
| October 22–23 / `37ed8198`, `f2e07acf` | Corrected OCR column projection to exact integer floor/ceil mapping and bounded its input locally. |

Host AI 3D still estimates depth from captured color. Game 3D obtains depth, camera and UI
resources inside the game, renders there, and exports final SBS. The host does not turn Host AI
into Game 3D by selecting a different provider. The October 5 Host change explicitly left
Game 3D's algorithms unchanged. Their current scene-zero and amplitude policies also differ:
Host uses a mean and robust range; Game uses its native-depth contrast-midpoint and nearest
reference policy. Original color-plus-depth export for host-side Game warping remains separate
future work in the host roadmap.

### Client migration and intentional differences

Client AI now follows the current Host AI coordinate structure: continuously observed raw mean
and robust relative amplitude, linear displacement with a separate hard bound, shared vertical
and horizontal slope constraints, and the strict contractive inverse. Its per-graph ZipDepth
calibrations remain model-specific amplitude floors; the migration does not substitute Host's
model units or Game's native-depth calibration. Fixed client pop remains unchanged.

| Area | Current Host AI / Game 3D behavior | Android XR Client AI behavior |
|---|---|---|
| **Depth producer and grid** | Host AI uses its authenticated high-grid DAV2/ZipDepth composite. Game uses selected native game depth with paired camera/encoding metadata where available. | Original ZipDepth Base remains the sole packaged model family, with one immutable aspect graph selected per stream. Model, detail, noise and thermal characteristics differ intentionally. |
| **Coordinate and strength** | Host AI uses an adaptive mean and robust amplitude with a model-specific floor. Game retains its independently calibrated native-depth zero/gain controller. Both apply linear signed displacement and bounded display output. | Adaptive mean and robust amplitude replace the September shot latch and far/linear/near curve. Existing per-graph calibration supplies the amplitude floor, fixed pop supplies artistic strength, and the independent hard bound replaces the soft container. This aligns the Host AI structure without claiming native game-depth equivalence. |
| **Time and cuts** | Fresh source observations advance Host adaptation; authenticated reuse freezes it. Host cuts remain evidence/ownership events, not a command to relatch scene zero. Game owns its separate capture/reset and native-depth state. | The controller uses the observation paired with the inferred source. Reuse does not spend time or advance zero/scale. Accepted cuts retain their cut/history responsibilities and do not reintroduce a shot-latched camera. Input-domain resets and invalid or interrupted observations follow the Android controller contract. |
| **Conditioning and inverse** | Host and Game share the vertical-envelope and horizontal-majorant geometry structure. Host evaluates its final conditioned field directly in the packed-output inverse. | Four GLES conditioner passes remain, followed by the exact iteration schedule in a depth-grid two-eye seed and one horizontal refinement correction. These preserve the slope/inverse semantics; the cached map is a deliberate sampling and precision difference. |
| **UI / subtitle protection** | Host uses PP-OCRv6/OCR8, current-cover authority, native temporal pixel evidence, Gaussian protection and an adaptive plane. Game uses eligible native UI sources and its source-alpha plane/pin-band policy. | No OCR model, subtitle pixel detector, native game UI mask or adaptive UI plane is implemented. The client conditions scene depth only. These features need their own quality, memory, cadence and thermal evaluation. |
| **ROI and capture authority** | Host can authorize foreground/video crops and exact DDup damage reuse from matched capture metadata. Game has its own depth crop, camera, resource and frame identities. | Decoded mono frames do not carry those authorities. No foreground ROI or host exact/damage proof is inferred from MediaCodec callbacks. A future host extension must be advertised and versioned. |
| **Cut evidence** | Host sees capture pixels and route state; Game follows the selected provider's captured evidence. | Appearance and geometry decisions remain client-grid calibrated on decoded model-input pixels and private cut depth. Existing confirmation, reliable-history ownership and independent rearming remain distinct from continuous zero/scale tracking. |
| **Reuse and busy retention** | Host retains complete authenticated depth/UI/final tuples; Game leases completed native resources and exports. | Existing fixed-owner local near-identical reuse retains the complete client geometry and histories while presenting current color. Busy retention stays bounded; a rejected raw field cannot become a reusable geometry owner. |
| **Failure behavior** | Invalid Host geometry renders current color flat. Missing or invalid current Game input cannot borrow an unrelated camera or old geometry to create stereo. | Invalid raw statistics, controller state, conditioner, seed/refinement or compose stages present current mono color duplicated. There is no legacy curve, seed-only or alternate depth-backend fallback. |
| **Input and color** | Host owns SDR/HDR preprocessing, native source-color warp and encoder conversion. Game preserves its declared game transfer and export contract. | Exact-area model preprocessing, client HDR handling, matched decoded color and SceneCore output remain Android responsibilities. Similar geometry does not equate their color or tensor-staging paths. |
| **Platform and scheduling** | Host uses D3D/CUDA/TensorRT/NVENC; Game runs on the game's graphics queue and exports completed SBS. | GLES/OpenCL/LiteRT, single-flight latest-frame coalescing, cached inverse maps and SceneCore remain intentional Galaxy XR adaptations. Thermal status is telemetry, not a hidden cadence controller. |

Private P2/P98 normalization still belongs to client cut/history analysis. The adaptive geometry
uses its separate robust-amplitude statistics; those are not the retired endpoint stretch,
subject recentering or normalized Bestv2 path. The same distinction exists on Host AI, where
geometry and cut-normalization histograms can share a traversal without sharing authority.

### Verification boundary and remaining work

The migration changes coordinate state and mapping. It does not establish complete Client/Host/
Game parity, equal model output, a performance improvement or sustained headset acceptance.
The owning Android document supplies the implemented controller and reset rules; historical
measurement windows below do not validate the new controller.

The final October 23 review gate passed all 1,191 JVM tests across 144 suites, built both debug ABI APKs
and the instrumentation APK, and executed the exported production compute shaders on the PC's
ANGLE OpenGL and Vulkan backends. Twenty-one synthetic GPU cases passed on each backend, including
all three production grids and a reflected-padding/partial-workgroup case. These checks cover controller state, raw statistics, clock gaps and
invalid inputs, cut independence and linear bounded conditioning. Exact commands, artifacts and
limits are recorded in [Client SBS evaluation](client-sbs-evaluation.md#adaptive-geometry-migration-2026-10-23).

The October 24 physical Galaxy XR gate also passed 20 selected GPU tests, including all three
fully delegated OpenCL models, adaptive depth, disparity, inverse rendering and reuse/history.
Settings and pairings retained identical hashes. See the
[physical test record](client-sbs-evaluation.md#physical-galaxy-xr-gpu-gate-2026-10-24).

The October 24 live capture obtained about 11 minutes of Client AI 3D before headset sleep;
the user elected to report that interruption. It observed near-30-FPS producer throughput
with uneven intervals, device-wide GPU busy averaging 59.5%, and Severe thermal status from
the start. Recurring app JNI warnings and system XR errors remain unresolved. This is partial
evidence, with settings/pairings unchanged, and does not close sustained acceptance. See the
[interrupted capture record](client-sbs-evaluation.md#interrupted-client-ai-3d-streaming-capture-2026-10-24).

The subsequent [client pacing/layout follow-up](client-sbs-evaluation.md#client-pacing-and-xr-layout-follow-up-2026-10-24)
adds explicit completed-frame swap authorization, removes the producerless visible Android
holder and avoids identical panel-pose transactions. It changes neither model calibration nor
geometry/reuse policy. SDK JNI/Node warnings and SystemUI XR call-order errors are not claimed
resolved by those client corrections alone; the short awake capture below records the runtime
observations.

The [five-minute post-deployment verification](client-sbs-evaluation.md#five-minute-post-deployment-stream-verification-2026-10-24)
completed with user-reported smooth output and no black flashes, about 29.8 producer returns/s,
no observed JNI/empty-Game-holder warnings and identical saved-data hashes. Node-close and
SystemUI errors persist, and one 119 ms producer gap remains. The different workload/initial
thermal state prevents a causal performance comparison; this short check does not replace
uninterrupted sustained acceptance.

Remaining work includes same-clip native-output comparisons and sustained
Galaxy XR inspection of relief, pumping, edge reconstruction, stage cost and thermal behavior.
The seed/refined `RG16F` cache, model grid, model-input staging and serial GLES conditioner remain
independent optimization candidates. A change to any of them needs its own measured evidence.
Client subtitle/ROI conditioning and host-assisted capture metadata remain absent and must not
be described as delivered by this geometry migration.

### Historical migration snapshot (2026-09-04)

The September review aligned the client with Host `e827a6fc`: graph-specific raw scales,
shot-arithmetic-mean latching, the far/linear/near curve and soft container, strict raw-field
failure handling, four-pass conditioning, the exact inverse seed and horizontal refinement.
Its per-graph model calibration, inverse-map work and model/platform distinctions remain useful;
its shot-latched coordinate and curve are superseded by the October 23 migration above.
September 4 thermal and map-cost measurements remain historical evidence in
[Android XR SBS](android-xr-sbs.md) and [Client SBS evaluation](client-sbs-evaluation.md).

## Historical audit (2026-07-25)

This records the historical gap analysis that compared the client's GLES SBS pipeline against
Apollo's HLSL pipeline. The audit snapshot used Apollo-3D at `d37237d5` (2026-07-25) and the client
at `c69b4f7f`. The findings and measurements remain below because they explain why several
experiments were tried. They are not the current contract.

> **Historical terminology:** every use of “current client,” “closed,” “preferred,” or “fallback”
> below refers to the July normalized-depth/Bestv2 implementation and its later closure work. That
> geometry has since been removed from live production. The authoritative current contract is the
> October migration review above and `android-xr-sbs.md`.

### Historical implementation status

| Area | Status at the historical snapshot / later close |
|---|---|
| Zero plane | The July path later used a shot-latched median/Bestv2 anchor. Raw V2 replaced it. |
| Adaptive pop | The July path later used a settled `1.20`–`2.00` controller. Fixed `1.75` replaced it. |
| Stretch and normalization | The July path later used P2/P98 for geometry. P2/P98 now survives only in private cut analysis. |
| Warp | The July probe route and its later normalized contractive variant are no longer compiled by the live renderer. Raw V2 feeds the strict contractive inverse directly. |
| Cut detection | Exposure-invariant evidence and independent rearming survived; ordinary geometry now adds two-update confirmation and coherent history ownership. |
| Apollo depth geometry mirror | **Closed.** `APOLLO_MAX_DEPTH_LONG_SIDE` is `1036`. |
| Model assumptions | The sole ZipDepth family now declares high-is-near and carries one calibrated raw scale per graph. |

At that snapshot, the client's depth map was much coarser than the host's — canonical buckets `322x182`, `350x154`,
`434x126` (~55k px) against the host's `770x434` (~334k px), roughly 6x fewer pixels and a 2.4–3.4x
coarser short side. Several host constants are expressed per depth texel and **must not be copied
verbatim**; the last section lists exactly which, and the client already owns the right mechanism
for most of them.

## What matched in the July pipeline — historical only

| Element | Status |
|---|---|
| `bestv2RawShift` degree-7 polynomial | identical coefficients |
| `shapedDepth` = `clamp((d - lo) * inv + recenter, 0, 1)` | identical, and the client got here first — the host carried a redundant double `saturate()` until 2026-07-25 |
| Calibration width 854, reference aspect 5120/2160, `clamp(ref/aspect, 0.5, 3.0)` | identical |
| Raw normalization percentiles P2/P98 | identical |
| Subject estimate: weighted 35th percentile from the near side | identical |
| Subject weighting: `centerWeight(σx 0.70, σy 0.55) * (1 - sigmoid(10*(g - 0.025)))` | identical |
| Subject EMA 0.20, recenter strength 0.35 | identical |
| Separate convergence EMA/bias | absent under the explicit zero plane |
| Shot state machine: startup blocks both arms through valid-depth-update age 8; accepted cuts latch; geometry and appearance rearm independently after two low/quiet updates; relative-spike escape at valid-depth-update age 8 | same mechanism; absolute thresholds are calibrated per depth grid (P2 table below) |
| Separable `[0.375, 0.25, 0.375]` depth prefilter | historical match; common to preferred fixed-inverse and legacy frontmost-probe branches on the client |
| `spatialThresholdScale` reference-gradient normalization / `alphaForInterval` wall-time normalization | correct mechanism, keep |

## P0 — the zero plane (historical closure; removed)

The client now matches the host's **shot-latched `median`** anchor. Both resolve the median through
the same shaped-depth and Bestv2 shift path used by reprojection, store the result as a source-pixel
shift, latch it immediately on a cut, and resolve it once more at the settle crossing. The obsolete
per-frame half-subject-shift anchor, subject-lock multiplier, and separate convergence bias are
gone.

The host findings that motivated the change were:

* Legacy's anchor is re-derived every frame from the subject EMA, so it *wobbles*, translating the
  whole disparity field slightly each frame. That wobble fed both mapping stretch and jitter.
* Switching to a shot-latched plane bought roughly **58% of mapping-stretch headroom**.
* The anchor must be resolved through *exactly* the same shaping as the warp
  (`shapedDepth` then `bestv2RawShift`), and stored as a **shift in source pixels**, not as a raw
  depth — otherwise later percentile/recenter motion makes convergence breathe.
* It is resolved **twice** per shot: once immediately on the cut so the new shot never renders on
  the old plane, then once more when the depth field settles. The host reference uses
  `sceneAge == 8`; the client detects the equivalent `previousAge < 8 && sceneAge >= 8` crossing
  because its wall-time advance can step over 8. This reference-frame-scaled profile age is
  deliberately separate from the one-per-valid-result cut age. Tracking every frame through the
  measurably worse (scene_cut jitter 4.90 → 8.19).

Implemented client contract: `RESOLVE_PROFILE` derives the median from `depthHistogram`, converts it
to a shift through the exact warp shaping, publishes it in the profile texture, and both warp
shaders consume it as `anchorShift`. Under this explicit plane the convergence bias is identically
zero.

## P0 — adaptive pop (historical closure; removed)

The client now matches the production controller: above the 0.02 edge threshold it accumulates
`min(referenceGradient / 0.02, 8.0) * 256` rather than a bare edge count, maps the resulting
weighted edge fraction through `smoothstep(0.04, 0.20)`, holds the `1.20` floor through the post-cut
settle window, and resolves a strength in **1.20–2.00** only when scene age crosses 8. That result
is latched once and remains bit-stable until the next cut.

The four audit defects, and why the closed changes matter, were:

1. **The old endpoints saturated on all real content.** The client used
   `smoothstep(0.007, 0.016, edgeFraction)`. The host measured weighted edge fraction across 23
   clips: real footage spans **0.038–0.245** with a median near 0.10, while only the three synthetic
   probes fall below 0.016. The current `0.04` / `0.20` endpoints span roughly the 10th–90th
   percentile of that real-content sample instead of pinning every real scene to one endpoint.
2. **The old band was below the noise floor.** `mix(1.25, 1.30, confidence)` was a **1.04 ratio**.
   The host proved a controller with 4% authority can neither be validated nor falsified — every
   metric that could judge it has a 0.15–0.20 `rel_tol`. The current **1.20 → 2.00** band has a 1.67
   ratio.
3. **The old client classified on the cut frame.** Normalization settling perturbs 50–60% of depth
   texels on the first frames, so a busy scene reads *smoother* than it is and can hold full pop for
   the entire shot. The current client waits for the `sceneAge` settle crossing at 8, classifies
   once, and latches through the shot.
4. **The old edge count was unweighted.** Counting texels over a threshold cannot distinguish a
   soft gradient field from a shattered one. The current fixed-point magnitude weight makes a
   violent edge count more than a marginal one and evaluates the gradient in Apollo reference-grid
   units.

## P1 — stretch band (historical geometry; now cut-only)

The current client uses the host contract: **P2/P98**, the crossing bins' outer edges, and an
attack-fast/release-slow envelope in `(lo, hi)` space. Expansion is immediate; contraction uses the
wall-time-normalized form of α 0.18.

| Detail | Host and current client | Audit-snapshot client |
|---|---|---|
| percentiles | **P2/P98** (`STRETCH_BAND_TAIL 0.02`) | P5/P95 |
| bin → value | crossing bin's **outer edge** | bin centre |
| temporal | attack-fast/release-slow in `(lo, hi)` space, α 0.18 | **none** — recomputed every frame |

Why all three closed changes matter:

* **P5/P95 → P2/P98** was the cardboarding fix. A hard band edge maps every out-of-band pixel onto
  one shaped depth, and since the parallax field is a pure function of shaped depth, they all render
  at an *identical* disparity — a flat plane with no relief. Widening cut the plateau from 15.84% to
  9.21% of pixels on the host's extended suite at **zero stereo-volume cost**. Note the host tried
  softening the band edge instead and **rejected it**: softening keeps the same over-clipping and
  charges the band interior for it, costing 6.3–6.4% of mid-scene relief. Widen, never soften.
* **Bin centre → outer edge.** A percentile only excludes its nominal fraction when the distribution
  is smooth across the crossing bin. With a large atom there — sky, a far wall — a centred bound cuts
  through it. On c525, bin 0 holds 66.1% of pixels and the centred bound saturated 49.9% of the frame
  to a single depth. An outer edge can only widen the range, costing at most one bin (~0.4%). **This
  matters more on the client, not less**: with ~6x fewer pixels per bin the quantization is coarser.
* **No band EMA in the audit snapshot.** `lo`/`inv` form a *multiplicative* gain, so an unsmoothed
  band makes the depth mapping breathe between cuts, and that wobble is then multiplied by pop
  strength. The current client damps the release side at 0.18; the attack side is an immediate
  `min`/`max`, because symmetric lag itself clips.

## P1 — raw normalization range (historical geometry; now cut-only)

The current client also uses P2/P98 outer bounds for raw normalization and expands the range
immediately while contracting it with the wall-time-normalized α 0.18 envelope. The audit had the
same two temporal/quantization defects as the band:

* The percentile crossing used the bin centre; the current `percentileValue()` takes the lower
  outer edge for P2 and upper outer edge for P98.
* The former `mix(rangeState.zw, vec2(frameLow, frameHigh), uRangeAlpha)` was a **symmetric** EMA. A
  symmetric EMA lags the live percentiles, and any frame whose smoothed range is narrower than the
  live one clips the difference in normalization — **lag literally becomes clipped depth**. The
  current client expands immediately and contracts at α:

  ```
  low  = min(mix(low,  frameLow,  α), frameLow)
  high = max(mix(high, frameHigh, α), frameHigh)
  ```

  Expansion is also the stability-safe direction: the range is a multiplicative gain, so growing it
  *lowers* the gain; fast shrinking is what makes the depth scale breathe. Measured host-side, jitter
  improved (core `static_jitter_p95` -2.75%) rather than regressing.

## P2 — legacy probe warp (historical; removed from live rendering)

This section describes the July fallback and the normalized-depth contractive path that briefly
superseded it. Neither is compiled by the current live renderer. The measurements remain useful for
understanding why frontmost multi-probe inversion was rejected, but its radius and lattice are no
longer production work items.

Five audit items are closed: both warp paths consume the shot-latched anchor, `convergenceOffset`
is gone, the unreachable parallax clamp is gone, base pop is `1.20`, and the compiled probe budget
is derived from `1.22 / depthWidth` instead of fixed aspect counts. Two items remain open: the
radius is still the historical over-wide bound, and probes still use an output-relative lattice.

| Detail | Host at that audit | July closure path | Audit-snapshot client |
|---|---|---|---|
| `depthParallax` | `(shift - anchor) * parallaxScale * outputScale` | same anchor-relative form (with `outputScale` folded into `parallaxScale`) | added `convergenceOffset`, then wrapped in `clamp(±limit)` |
| base pop | 1.20 | **1.20** | 1.25 |
| search radius | frame's exact reach × 1.10 | **open:** over-wide historical formula sized with the 2.00 ceiling | over-wide historical formula sized with 1.30 |
| probe positions | global lattice `k * spacing` | **open:** `startX + stepX * i`, relative to the output pixel | same relative lattice |
| probe budget | derived from spacing and the frame's window (~16) | derived from `1.22 / depthWidth` and the bucket's worst-case window, clamped 8–48 | fixed 32 / 24 / 16 by aspect |

* **Anchor/convergence — closed.** `convergenceOffset` is identically zero under any explicit zero
  plane. It existed only for the retired per-frame plane and was removed when both shaders began
  consuming the shot-latched `anchorShift`.
* **Parallax clamp — closed.** The former `parallaxLimit` (`0.071 * outputScale`) is provably
  unreachable. Reach is
  `9.979 * (0.35/854) * strength * outputScale` against a bound of `0.071 * outputScale`, so
  **outputScale cancels** and binding needs `strength > 17.36` while the configured max is 2.0 —
  an 8.7x margin. Removing it produced no measured host metric change.
* **Base pop and probe budget — closed.** The base now matches 1.20, while the budget ports Apollo's
  depth-texel spacing rule rather than copying its step count. The client sizes one shareable shader
  per depth bucket from that bucket's widest model grid and worst-case radius.
* **Search radius — open.** The host's old form budgeted for three things at once: a convergence
  bias that is now always zero (~49% of the radius), the most adverse anchor *any* frame could
  produce rather than this frame's, and the configured ceiling rather than the resolved ratio.
  Replacing it with the frame's own exact bound cut mean probes per pixel from 42.7 to 16.0 and warp
  time by ~46% host-side. The client still uses that over-wide form, so this is now a performance
  gap in that retired path rather than a correctness gap.
* **Global lattice — open.** Probes at `uv.x ± i*step` move with every pixel, so narrowing the
  window moves every probe. On a global lattice `k * spacing`, a narrowed window is a strict
  *subset* at identical positions. This is what makes window-narrowing provable rather than
  hopeful.

## P2 — cut detection (historical baseline; current confirmation is above)

| detail | host | client |
|---|---|---|
| per-depth-texel change | `0.05` | `0.12` |
| armed standalone geometry cut | changed fraction `>= 0.60` | `>= 0.58`, or `>= 0.42` with distribution shift `>= 0.10` |
| appearance proposal | raw-RGB delta `>= 0.20` on `>= 0.70` of texels **and** local max-RGB ordinal fraction `>= 0.03` | spatially broad raw change **and** max-RGB ordinal reversal on `>= 0.15` of 16x16 block sites |
| exposure-like geometry veto | quiet supported exposure, one deferred reliable-to-structureless update regardless of raw color distance, or a strict same-scene return from that held gap; support floor `0.01`; a second persistent low-structure update restores geometry authority | quiet preserved exposure, one deferred supported-history-to-structureless update regardless of raw color distance, or a strict same-scene return (`<= 2` average luma codes/block and `< 1%` moderate blocks) from that bridged gap; current/common support floor `0.05`; a second persistent low-structure update restores geometry authority |
| persistent-low supported return | one absolute standalone decision independent of ordinary arm/refractory, then consume | same; event bit plus `cutStateCounters.y` marker |
| appearance/depth acceptance | proposal plus depth fraction `>= 0.25` | proposal plus depth `>= 0.18`, or `>= 0.10` with distribution shift `>= 0.06` |
| initial arming | after valid-depth-update age 8; that update cannot fire | same |
| geometry rearm | two consecutive depth updates `< 0.10` | two consecutive depth updates `< 0.08` |
| appearance rearm | two consecutive proposal-quiet updates | same |
| latched geometry-spike escape | at valid-depth-update age 8: depth `>= 0.30` and (`>= EMA + 0.20` or `>= 2 * EMA`) | same |
| depth-baseline EMA | alpha `0.125`, reset on an accepted cut | same |

**Correction to an earlier reading of this file:** the client is NOT missing colour cut detection.
`ClientSbsGpuSceneCutDetector` is a dedicated GPU detector over the model-input texture. It keeps
average Rec.709 luma per 16x16 tile for broad raw-change energy, but structural evidence uses the
median `max(R,G,B)` of a fixed 3x3 sample lattice. Like the host, it compares all ten pairwise
orderings in a cross-five spatial stencil only when the same relation clears a reliability floor in
both frames. Under the supported identical global monotone RGB exposure model, clipping and
rounding can create a rejected tie but cannot reverse an ordering. At least 15% of block sites plus
spatially broad raw change are required for a proposal. The client also counts sites with four
reliable current relations and with four common relations. Five percent is sufficient frame-level
support. Supported history losing current structure is exposure-like for one update regardless of
raw color distance, and COMMIT preserves the last supported ordinal grid and histogram while setting
supported-history and one-gap bits in the existing block count. The depth path retains the
pre-gap geometry baseline and dedicated reliable depth on that first structureless update, while
its live range and immediate temporal depth still update. A second consecutive low-structure update resolves against that reliable tuple with
geometry authority, advances history, and enters
an accepted persistent-low state. Later unsupported updates remain there without a timer or event,
so a real persistent flat scene cannot extend the brightness veto or periodically retrigger it.
A supported return from the one-update hold is exposure-like only when it is a strict endpoint
match: quiet structure, no more than two average luma codes per block, and fewer than 1% moderate
block deltas. This prevents either edge of `A -> saturated black/white -> A` from relatching without
letting a low-appearance `B` overrule authoritative depth geometry. A structurally different
supported `B` is compared directly against `A` and can propose a cut; when current support exists
but common support does not, the transition is ambiguous and leaves standalone geometry authority
intact. Startup unsupported history followed by supported content follows that latter rule. The
5%-to-15% reversal band is likewise ambiguous. Coarse histogram L1 is diagnostic rather than
authority because exposure can move it and a real edit can preserve it. The former
brightness-only `uniformHardTransition` override is gone. The detector publishes one per-slot
uint32 evidence word: bit 0 is the qualified appearance proposal, bit 1 is the exposure-like veto,
bit 2 marks accepted persistent-low start, and bit 3 marks its first supported return. All reach the
depth pipeline without another buffer, dispatch, or readback. The explicit CPU/manual cut input
still asserts appearance authority and never creates the automatic veto.

The color detector is optional to presentation. If its program cannot be created or later fails,
Client SBS keeps inference and reprojection active, disables appearance/exposure classification and
near-identical reuse, and falls back to bounded two-valid-observation geometry confirmation without
inventing ordinal support. `CUT_DECISION_DEPTH_ONLY_FALLBACK` keeps pending, accepted, and rejected
fallback decisions attributable in telemetry.

That proposal does not reset immediate temporal depth and is not sufficient authority to move
shot-owned state. The post-temporal cut/profile resolver accepts it as a one-update shot pulse only
when at least 18% of depth texels changed, or at least 10% changed alongside a 6%
range-distribution shift. Startup
blocks both proposal arms until settling completes. An accepted cut clears both arms and latches
shot state. Geometry and appearance then recover independently: two low-depth updates rearm
geometry, while two proposal-quiet updates rearm appearance. One noisy channel therefore cannot
starve the other.

The six-dispatch Android implementation publishes an advancing result's scalar ownership decision
at the final resolver, then promotes that result's exact temporal texture at the beginning of the
next actual inference, before the new comparison. Scene/model history is committed after the
result. This deferred per-pixel copy is the coherent equivalent of an end-of-result tuple advance;
reuse dispatches nothing and cannot promote or alter it.

Persistent-low start sets the existing reserved `cutStateCounters.y` lane to one. It stays one
through later low-support updates. The typed first-supported-return event gets exactly one absolute
standalone geometry decision independent of normal arm/refractory state, then clears the marker
whether it cuts or not. This authority is event-scoped, not timer-based, so persistent content
cannot periodically pulse.

The host retains its global latched marker after the first accepted cut; the client instead clears
each source-specific latch bit when that source rearms. In both implementations the route is
eligible only while geometry remains unarmed, so the representation difference does not change
relative-spike authority. On or after the eighth valid depth update following a cut, a new geometry
spike can still cut through persistent above-low motion when it exceeds both the absolute `0.30`
floor and the previous depth-change EMA by `+0.20` or `2x`, unless that exact color transition is
exposure-like or is the one-valid-update recovery tail after a preserved exposure/same-scene
return. That tail freezes only the novelty baseline; a real appearance proposal bypasses it.
Absolute standalone geometry is vetoed under the same condition. The
depth-corroborated appearance route is unaffected. Constant
motion converges into that EMA and cannot periodically retrigger; a genuinely
stronger geometry event remains detectable. Client elapsed-time catch-up never advances this
counter: `referenceFrameAdvance` applies only to the separate adaptive-pop/anchor profile age.

If an accepted inference has no valid depth, its scene classification has no later authority.
The invalid transaction preserves camera, cut FSM, range, immediate temporal depth, and every
reliable history owner; only health/event telemetry may record it. The next valid update consumes
only its own exact scene record, matching the host resolver's evidence lifetime.

These numeric thresholds are intentionally not literal copies. The client evaluates a depth grid
whose short side is roughly 2.4–3.4x coarser and has an authenticated range-distribution statistic
that the host detector does not consume. The parity contract is therefore shared state-machine
semantics—exposure-invariant appearance evidence, geometry authority, independent arms, one pulse
per accepted cut, and the same relative-spike escape—while each grid keeps its calibrated absolute
and corroboration bands.

## P3 — host geometry constant (historical closure)

`ClientSbsTemporalTuning.APOLLO_MAX_DEPTH_LONG_SIDE` is now **1036**, matching the host's
2026-07-25 TensorRT profile bound. The audit found the stale value `1008`, which made the client
mis-predict the host depth shape on ultrawide content. The host increase lets 21:9 resolve to
`1036x434` and 5K2K to `1022x434`, instead of both dropping to a 420 short side.

## Historical resolution-dependent parameter audit

The client's depth map is 2.4–3.4x coarser on the short side. Three classes:

**1. Already handled correctly — keep the mechanisms.**
`spatialThresholdScale()` returns `hostShortSide / clientShortSide` (clamped 1–4). The client
divides finite-difference gradient magnitude by this scale before temporal edge gating, subject
weighting, and adaptive-pop magnitude weighting, expressing those gradients in Apollo
reference-texel units.

It must **not** divide the resulting weighted `edgeFraction`. On a coarser grid, a one-texel
silhouette occupies `spatialThresholdScale` times the pixel fraction, while its normalized linear
gradient weight is divided by that same factor. The two effects cancel below Apollo's per-pixel
weight cap. At saturation the uncapped cancellation no longer applies, so the producer also divides
the cap (`8.0`) by `spatialThresholdScale` before accumulation. That cap-aware producer weighting
covers both regimes; dividing `edgeFraction` afterward would normalize twice and under-report risk.

`alphaForInterval()` likewise converts per-host-frame α into wall-time α for the client's slower
depth cadence. The raw-range and stretch-band release alphas (both based on 0.18) now go through it;
their attack side is an immediate `min`/`max` and has no time constant.

**2. Legacy probe-budget rule was handled correctly before removal.**
Historical host probe spacing was `BESTV2_TARGET_DEPTH_TEXELS / depth_width` with `TARGET = 1.22` — i.e. 1.22
*depth texels*, expressed in normalized source U. Feeding the client's own `depth_width` adapts
automatically: at 322 wide, spacing is 2.4x the host's at 770, and the step count for a given radius
fell proportionally. The July closure path derived:

```
spacing = 1.22 / clientDepthWidth
steps   = clamp(ceil(2 * bucketWorstCaseRadius / spacing), 8, 48)
```

The bucket worst case keeps shader source shareable across direct landscape streams in the same
bucket. Portrait streams use their exact aspect-fit cropped depth width, which is already part of
their immutable pipeline contract. This closes the fixed 32/24/16 budget gap, but it does not close
the separate over-wide-radius or relative-lattice items in the warp section.

The host's calibration explicitly stops at ~1.0–1.22 texels because that is where the probe grid
matches the resolution of the signal it samples; finer oversamples a bilinear map, coarser breaks the
one-breakpoint-per-interval argument. The client's coarser depth means **fewer** probes, not the same
number — a direct perf win alongside the quality fixes.

**3. Historical content calibration.**
The adaptive-pop endpoints (0.04 / 0.20) were calibrated against the *host's* measured weighted edge
fraction at 434 short side. Resolution transfer comes from the linear
weighted-gradient/coarse-density cancellation plus the cap-aware producer normalization described
above, not from an extra division of `edgeFraction`. The `0.04` / `0.20` implementation parity work
was closed for that normalized-depth controller, but it is no longer a production geometry input.
The current raw V2 path fixes pop at `1.75`; private edge density remains cut/diagnostic evidence.

## Historical model note: DA-V2 vs MiDaS v2 (superseded)

At the 2026-07-25 audit snapshot, there was no separate MiDaS processing path. `midas` appeared only
in `ClientSbsModelManifest` and preference/UI classes; the two families fed the same depth processor
and warp programs. Their manifest factories differed only in tensor names and dimension
constraints, and both used direct full-frame resize, static shapes, and automatic FP16 execution.

Per-model geometry also flowed correctly in that snapshot: `Stereo3DRenderer` constructed the
processor from the selected output dimensions and derived `spatialThresholdScale` from them, so
MiDaS `352x192` resolved to 2.26 and DA-V2 `322x182` to 2.38 without per-model constants.

That historical analysis operated on the *normalized* depth map, after `RESOLVE_RAW_RANGE` mapped
each model's output into [0,1] through its own P2/P98 range. It identified two model-dependent
concerns:

1. **Adaptive-pop edge-density calibration.** This concern became obsolete when raw V2 fixed pop at
   `1.75`; edge density remains useful for cut diagnostics only.
2. **Depth polarity.** The old DA-V2/MiDaS selection relied on an implicit high-is-near assumption.
   Current production removes that ambiguity: the sole ZipDepth manifest declares and asserts that
   its nonnegative affine-invariant inverse-depth output is high-is-near. Per-graph raw V2 scales
   handle coordinate magnitude; private P2/P98 analysis must not flip polarity. Any future model
   family must declare, calibrate, and test this contract before it can enter production.

## Legacy search-radius/pop-ceiling coupling (historical; removed)

The following coupling applied only to the removed cached/direct probe route. The current raw V2
contractive path has no search radius or probe lattice.

The probe radius must be sized from the adaptive-pop **maximum**, not the resolved ratio. When the
band rose to 2.00, both warp templates therefore changed their radius multiplier from 1.30 to 2.00;
otherwise a high-pop scene's displacement could leave the search window and the probe would miss
crossings.

That correctness coupling was closed before the path was removed. Radius tightening and a global
probe lattice are not current follow-ups.

## Historical follow-up order (superseded)

This was the July audit's order. Use the current migration matrix at the top of this file for new
work.

1. Finish warp radius/lattice work (P2): frame-exact reach plus a global probe lattice.
2. Decide whether the remaining depth-side cut thresholds need host parity given the richer client
   colour-cut path.
3. Declare and test model depth polarity before adding a third model family.
4. Treat per-model adaptive-pop measurement as validation/tuning only; the weighted
   `0.04` / `0.20`, settle-latched `1.20`–`2.00` parity implementation is closed.
