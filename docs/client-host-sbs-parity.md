# Historical Client ↔ host SBS parity audit

This records the historical gap analysis that compared the client's GLES SBS pipeline against
Apollo's HLSL pipeline. The audit snapshot used Apollo-3D at `d37237d5` (2026-07-25) and the client
at `c69b4f7f`. The findings and measurements remain below because they explain the current contract,
but the audit-snapshot client behavior must not be read as the current implementation. The current
production contract is documented in `android-xr-sbs.md`.

## Current implementation status (2026-07-25)

| Area | Current status |
|---|---|
| Zero plane | **Closed.** The client uses the shot-latched median anchor, resolved at the cut and settle crossing. |
| Adaptive pop | **Closed.** Risk is gradient-magnitude weighted; endpoints are `0.04` / `0.20`; the settled, shot-latched strength spans `1.20`–`2.00`. |
| Stretch band and raw normalization | **Closed.** Both use P2/P98 outer edges and attack-fast/release-slow temporal envelopes. |
| Warp | **Partial.** Anchor consumption, convergence removal, unreachable-clamp removal, base pop, and the depth-grid-derived probe budget are fixed. The over-wide radius and output-relative probe lattice remain open. |
| Cut detection | **Closed semantics.** Both sides use exposure-invariant structure plus depth corroboration and one-pulse/two-low hysteresis; model-grid depth thresholds remain intentionally different. |
| Apollo depth geometry mirror | **Closed.** `APOLLO_MAX_DEPTH_LONG_SIDE` is `1036`. |
| Model assumptions | **Open caveat.** Depth polarity is still implicit rather than declared and tested. |

The client's depth map is much coarser than the host's — canonical buckets `322x182`, `350x154`,
`434x126` (~55k px) against the host's `770x434` (~334k px), roughly 6x fewer pixels and a 2.4–3.4x
coarser short side. Several host constants are expressed per depth texel and **must not be copied
verbatim**; the last section lists exactly which, and the client already owns the right mechanism
for most of them.

## What already matches — do not "fix" these

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
| Shot state machine: both arms blocked through valid-depth-update age 8; accepted cuts latch; geometry and appearance rearm independently after two low/quiet updates; relative-spike escape at valid-depth-update age 8 | same mechanism; absolute thresholds are calibrated per depth grid (P2 table below) |
| Separable `[0.375, 0.25, 0.375]` depth prefilter | identical |
| `spatialThresholdScale` reference-gradient normalization / `alphaForInterval` wall-time normalization | correct mechanism, keep |

## P0 — the zero plane (closed)

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

## P0 — adaptive pop (closed)

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

## P1 — stretch band (closed)

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

## P1 — raw normalization range (closed)

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

## P2 — warp (partial)

Five audit items are closed: both warp paths consume the shot-latched anchor, `convergenceOffset`
is gone, the unreachable parallax clamp is gone, base pop is `1.20`, and the compiled probe budget
is derived from `1.22 / depthWidth` instead of fixed aspect counts. Two items remain open: the
radius is still the historical over-wide bound, and probes still use an output-relative lattice.

| Detail | Host | Current client | Audit-snapshot client |
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
  gap rather than a correctness gap.
* **Global lattice — open.** Probes at `uv.x ± i*step` move with every pixel, so narrowing the
  window moves every probe. On a global lattice `k * spacing`, a narrowed window is a strict
  *subset* at identical positions. This is what makes window-narrowing provable rather than
  hopeful.

## P2 — cut detection (mechanism parity, calibrated numeric differences)

| detail | host | client |
|---|---|---|
| per-depth-texel change | `0.05` | `0.12` |
| armed standalone geometry cut | changed fraction `>= 0.60` | `>= 0.58`, or `>= 0.42` with distribution shift `>= 0.10` |
| appearance proposal | raw-RGB delta `>= 0.20` on `>= 0.70` of texels **and** local max-RGB ordinal fraction `>= 0.03` | spatially broad raw change **and** max-RGB ordinal reversal on `>= 0.15` of 16x16 block sites |
| exposure-like geometry veto | broad raw replacement and ordinal fraction `< 0.01`; vetoes standalone/relative depth routes only | broad raw/energy replacement and ordinal reversal on `< 0.05` of block sites; vetoes standalone/relative depth routes only |
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
spatially broad raw change are required for a proposal. Fewer than 5% structural sites with the
same broad raw/energy replacement instead marks the transition exposure-like; the 5%-to-15% band
is deliberately ambiguous and leaves standalone geometry authority intact. Coarse histogram L1 is diagnostic rather
than authority because exposure can move it and a real edit can preserve it. The former
brightness-only `uniformHardTransition` override is gone. The detector publishes one per-slot
uint32 evidence word: bit 0 is the qualified appearance proposal and bit 1 is the exposure-like
veto. Both reach the depth pipeline without another buffer, dispatch, or readback. The explicit
CPU/manual cut input still asserts appearance authority and never creates the automatic veto.

That proposal may still reset temporal depth filtering, but it is not sufficient authority to move
shot-owned state. `RESOLVE_RAW_RANGE` accepts it as a one-update shot pulse only when at least 18%
of depth texels changed, or at least 10% changed alongside a 6% range-distribution shift. Startup
blocks both proposal arms until settling completes. An accepted cut clears both arms and latches
shot state. Geometry and appearance then recover independently: two low-depth updates rearm
geometry, while two proposal-quiet updates rearm appearance. One noisy channel therefore cannot
starve the other.

Both implementations retain the latched bit while either arm recovers. On or after the eighth
valid depth update following a cut, a new geometry spike can still cut through persistent above-low
motion when it exceeds both the absolute `0.30` floor and the previous depth-change EMA by `+0.20`
or `2x`, unless that exact color transition is exposure-like. Absolute standalone geometry is
vetoed under the same condition. The depth-corroborated appearance route is unaffected. Constant
motion converges into that EMA and cannot periodically retrigger; a genuinely
stronger geometry event remains detectable. Client elapsed-time catch-up never advances this
counter: `referenceFrameAdvance` applies only to the separate adaptive-pop/anchor profile age.

If an accepted inference has no valid depth after its exact color frame has already committed
detector history, the client carries either mailbox classification (qualified appearance or
exposure-like) to exactly the next valid accepted depth update. Current geometry must corroborate
an appearance proposal there; an exposure-like classification vetoes only the standalone and
relative geometry routes there. That update consumes the classification. This is GPU-state
evidence carry, not color/depth re-pairing.

These numeric thresholds are intentionally not literal copies. The client evaluates a depth grid
whose short side is roughly 2.4–3.4x coarser and has an authenticated range-distribution statistic
that the host detector does not consume. The parity contract is therefore shared state-machine
semantics—exposure-invariant appearance evidence, geometry authority, independent arms, one pulse
per accepted cut, and the same relative-spike escape—while each grid keeps its calibrated absolute
and corroboration bands.

## P3 — host geometry constant (closed)

`ClientSbsTemporalTuning.APOLLO_MAX_DEPTH_LONG_SIDE` is now **1036**, matching the host's
2026-07-25 TensorRT profile bound. The audit found the stale value `1008`, which made the client
mis-predict the host depth shape on ultrawide content. The host increase lets 21:9 resolve to
`1036x434` and 5K2K to `1022x434`, instead of both dropping to a 420 short side.

## Resolution-dependent parameters — what must NOT be copied verbatim

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

**2. Probe-budget rule is now handled correctly — keep the formula.**
Host probe spacing is `BESTV2_TARGET_DEPTH_TEXELS / depth_width` with `TARGET = 1.22` — i.e. 1.22
*depth texels*, expressed in normalized source U. Feeding the client's own `depth_width` adapts
automatically: at 322 wide, spacing is 2.4x the host's at 770, and the step count for a given radius
falls proportionally. The current client derives:

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

**3. Content calibration remains validation, not an implementation-parity gap.**
The adaptive-pop endpoints (0.04 / 0.20) were calibrated against the *host's* measured weighted edge
fraction at 434 short side. Resolution transfer comes from the linear
weighted-gradient/coarse-density cancellation plus the cap-aware producer normalization described
above, not from an extra division of `edgeFraction`. The `0.04` / `0.20` implementation parity work
is closed, but this remains an empirical calibration rather than an identity: MiDaS v2 and DA-V2
may produce different edge-density distributions. Measure representative content before making any
model-specific retuning. The old, unmeasured `0.007` / `0.016` endpoints are the known-bad
historical values.

## Model note: DA-V2 vs MiDaS v2 — verified

**There is no separate MiDaS path.** `midas` appears only in `ClientSbsModelManifest` and the
preference/UI classes; there is not one line of MiDaS-specific code in `com.limelight.sbs` or in
either shader file. `createMidasStaticManifest` and `createDepthAnythingStaticManifest` differ only
in tensor names and dimension constraints — both produce
`directFullFrameResize=true, dynamicSpatialShape=false, AUTOMATIC_FP16`. Both feed the same
`ClientSbsGpuDepthProcessor` and the same two warp programs, so any change here lands on both.

Per-model geometry flows correctly: `Stereo3DRenderer` constructs the processor from
`aiModel.getOutputWidth()/getOutputHeight()`, and `spatialThresholdScale` is derived from those, so
MiDaS `352x192` resolves to 2.26 and DA-V2 `322x182` to 2.38 with no per-model constants.

Everything above is model-independent — it operates on the *normalized* depth map, after
`RESOLVE_RAW_RANGE` has mapped whatever the model emits into [0,1] via its own P2/P98 range. Two
places where model identity does leak through:

1. **Adaptive-pop edge-density calibration** (class 3 above). The parity implementation is closed,
   but edge density remains a property of output sharpness. Measure weighted edge fraction per
   model family before changing the current 0.04/0.20 calibration.
2. **Depth polarity is an undocumented, untested assumption.** The client has no polarity transform
   and no per-model flag, where Apollo has an explicit "transform model output into high-is-near
   convention" pipeline step. It currently works because MiDaS v2 emits inverse depth and DA-V2's
   output is disparity-like, so both are high-is-near — but nothing enforces or tests it. The
   subject percentile scans from bin 255 as "near" and `bestv2RawShift` maps higher shaped depth to
   larger positive shift, so an opposite-polarity model would render the whole scene inside-out with
   nothing catching it. A one-off startup check (correlate a coarse depth statistic against a known
   near/far test pattern, or simply assert the manifest declares a polarity) is cheap insurance
   before a third model is ever added.

## Search-radius/pop-ceiling coupling (closed)

The probe radius must be sized from the adaptive-pop **maximum**, not the resolved ratio. When the
band rose to 2.00, both warp templates therefore changed their radius multiplier from 1.30 to 2.00;
otherwise a high-pop scene's displacement could leave the search window and the probe would miss
crossings.

That correctness coupling is closed. It is separate from the still-open radius-tightening work:
the current 2.00-sized radius is deliberately safe but uses the historical over-wide formula. The
probe budget is no longer a fixed 32/24/16; it now follows the `1.22 / depthWidth` rule described
above. Replacing the radius with the frame's exact reach and moving those probes onto a global
lattice remain the two warp follow-ups.

## Current follow-up order

1. Finish warp radius/lattice work (P2): frame-exact reach plus a global probe lattice.
2. Decide whether the remaining depth-side cut thresholds need host parity given the richer client
   colour-cut path.
3. Declare and test model depth polarity before adding a third model family.
4. Treat per-model adaptive-pop measurement as validation/tuning only; the weighted
   `0.04` / `0.20`, settle-latched `1.20`–`2.00` parity implementation is closed.
