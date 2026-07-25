# Client ↔ host SBS parity

Line-by-line comparison of the client's GLES SBS pipeline against Apollo's HLSL pipeline, with the
work needed to make the client reproduce host logic and quality. Host reference is Apollo-3D at
`d37237d5` (2026-07-25); client is `c69b4f7f`.

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
| Subject EMA 0.20, convergence EMA 0.10, recenter strength 0.35 | identical |
| Cut rearm after `sceneAge >= 2`, cut arming at `sceneAge >= 8` | identical |
| Separable `[0.375, 0.25, 0.375]` depth prefilter | identical |
| `spatialThresholdScale` / `alphaForInterval` normalization design | correct mechanism, keep |

## P0 — the zero plane (largest single quality gap)

The client runs the host's **removed** `legacy` plane. Its own comment says so:

```glsl
// Match Apollo's active production profile: legacy zero plane, subject_lock=0.5,
float anchorShift = 0.5 * subjectShift;   // per-frame subject anchor
```

The host replaced this with a **shot-latched `median`** anchor on 2026-07-24 and deleted `legacy`
entirely on 2026-07-25. Host findings that apply directly:

* Legacy's anchor is re-derived every frame from the subject EMA, so it *wobbles*, translating the
  whole disparity field slightly each frame. That wobble fed both mapping stretch and jitter.
* Switching to a shot-latched plane bought roughly **58% of mapping-stretch headroom**.
* The anchor must be resolved through *exactly* the same shaping as the warp
  (`shapedDepth` then `bestv2RawShift`), and stored as a **shift in source pixels**, not as a raw
  depth — otherwise later percentile/recenter motion makes convergence breathe.
* It is resolved **twice** per shot: once immediately on the cut so the new shot never renders on
  the old plane, then once more at `sceneAge == 8` when the depth field has settled. Tracking every
  frame through the settle window is measurably worse (scene_cut jitter 4.90 → 8.19).

Client work: add a shot-latched anchor to `RESOLVE_PROFILE` (median of `depthHistogram`, shaped and
converted to a shift, latched with a valid flag), publish it in the profile texture, and consume it
in both warp shaders as `anchorShift`. Then delete `convergenceOffset` and `subjectShift` from the
warp — under an explicit plane the convergence bias is identically zero.

## P0 — adaptive pop is inert, mistimed, and unweighted

Four separate defects, all fixed host-side:

1. **Endpoints saturate on all real content.** Client uses `smoothstep(0.007, 0.016, edgeFraction)`.
   The host measured the weighted edge fraction across 23 clips: real footage spans **0.038–0.245**
   with a median near 0.10, while only the three synthetic probes fall below 0.016. So every real
   scene pins to the ceiling. Host endpoints are now `POP_RISK_LOW 0.04` / `POP_RISK_HIGH 0.20`,
   spanning roughly the 10th–90th percentile of real content.
2. **The band is below the noise floor.** `mix(1.25, 1.30, confidence)` is a **1.04 ratio**. The host
   proved a controller with 4% authority can neither be validated nor falsified — every metric that
   could judge it has a 0.15–0.20 `rel_tol`. Host band is now **1.20 → 2.00** (1.67 ratio).
3. **It classifies on the cut frame.** Client resolves pop under `!wasInitialized || hardCut`.
   Normalization settling perturbs 50–60% of depth texels on the first frames, so a busy scene reads
   *smoother* than it is and holds full pop for the entire shot. Host holds the floor until
   `sceneAge == POP_CLASSIFY_SETTLE_FRAMES (8)`, then latches once, bit-stable until the next cut.
4. **The edge count is unweighted.** Client counts texels over a threshold. Host accumulates
   `min(grad / 0.02, 8.0) * 256` in fixed point, so a violent edge counts more than a marginal one.
   Without this the risk statistic cannot distinguish a soft gradient field from a shattered one.

## P1 — stretch band

| | host | client |
|---|---|---|
| percentiles | **P2/P98** (`STRETCH_BAND_TAIL 0.02`) | P5/P95 |
| bin → value | crossing bin's **outer edge** | bin centre |
| temporal | attack-fast/release-slow in `(lo, hi)` space, α 0.18 | **none** — recomputed every frame |

All three matter:

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
* **No band EMA at all.** `lo`/`inv` form a *multiplicative* gain, so an unsmoothed band makes the
  depth mapping breathe between cuts, and that wobble is then multiplied by pop strength. The host
  damps at 0.18 — and the damping must be attack-fast/release-slow (below), or the lag itself clips.

## P1 — raw normalization range

Same two temporal/quantization defects as the band:

* Bin centre → outer edge in `percentileValue()`.
* `mix(rangeState.zw, vec2(frameLow, frameHigh), uRangeAlpha)` is a **symmetric** EMA. A symmetric
  EMA lags the live percentiles, and any frame whose smoothed range is narrower than the live one
  clips the difference in the normalization — **lag literally becomes clipped depth**. Host now
  expands immediately and contracts at α:

  ```
  low  = min(mix(low,  frameLow,  α), frameLow)
  high = max(mix(high, frameHigh, α), frameHigh)
  ```

  Expansion is also the stability-safe direction: the range is a multiplicative gain, so growing it
  *lowers* the gain; fast shrinking is what makes the depth scale breathe. Measured host-side, jitter
  improved (core `static_jitter_p95` -2.75%) rather than regressing.

## P2 — warp

| | host | client |
|---|---|---|
| `depthParallax` | `(shift - anchor) * parallaxScale * outputScale` | adds `convergenceOffset`, wraps in `clamp(±limit)` |
| base pop | 1.20 | 1.25 |
| search radius | frame's exact reach × 1.10 | `outputScale * 1.30 * (0.004 + 12.51*0.35/pw + guard)` |
| probe positions | global lattice `k * spacing` | `startX + stepX * i`, relative to the output pixel |
| probe count | derived from spacing and the frame's window (~16) | fixed 32 / 24 / 16 by aspect |

* **`convergenceOffset`** is identically zero under any explicit zero plane — it exists only for
  `legacy`. Delete with the anchor change.
* **`parallaxLimit`** (`0.071 * outputScale`) is provably unreachable. Reach is
  `9.979 * (0.35/854) * strength * outputScale` against a bound of `0.071 * outputScale`, so
  **outputScale cancels** and binding needs `strength > 17.36` while the configured max is 2.0 —
  an 8.7x margin. The host removed it; measured metric change was nil.
* **Search radius.** The host's old form budgeted for three things at once: a convergence bias that
  is now always zero (~49% of the radius), the most adverse anchor *any* frame could produce rather
  than this frame's, and the configured ceiling rather than the resolved ratio. Replacing it with
  the frame's own exact bound cut mean probes per pixel from 42.7 to 16.0 and warp time by ~46%.
  The client's radius is that same old over-wide form.
* **Global lattice.** Probes at `uv.x ± i*step` move with every pixel, so narrowing the window moves
  every probe. On a global lattice `k * spacing`, a narrowed window is a strict *subset* at identical
  positions. This is what makes window-narrowing provable rather than hopeful.

## P2 — cut detection

| | host | client |
|---|---|---|
| per-pixel depth-change threshold | 0.05 | 0.12 |
| depth cut | `changeFraction >= 0.65` | `>= 0.58`, or `>= 0.42 && distributionShift >= 0.10` |
| colour cut | `colorChangeFraction >= 0.70` on normalized model input | present, and richer — see below |
| rearm | `(change < 0.35 && colour < 0.50) \|\| age >= 2` | `change < 0.35 \|\| age >= 2` |

**Correction to an earlier reading of this file:** the client is NOT missing colour cut detection.
`ClientSbsGpuSceneCutDetector` is a dedicated GPU detector over the model-input texture — Rec.709
luma reduced per 16x16 tile, compared against persistent history, combining spatially broad change,
mean-compensated structural change and coarse histogram change, explicitly built to reject ordinary
object motion. It publishes one uint32 that reaches both `RESOLVE_RAW_RANGE` and `RESOLVE_PROFILE`
through `externalSceneCutRequested()`. That is a richer detector than the host's per-pixel
`colorChangeFraction >= 0.70`, and it covers the same gap: being blind to a similar-depth shot cut
during the 8-update depth settle window.

What remains is only threshold parity on the depth-side detector (0.12 vs 0.05 per-pixel, 0.58 vs
0.65 frame fraction). Those are secondary while the colour path carries hard cuts.

## P3 — stale host geometry constant

`ClientSbsTemporalTuning.APOLLO_MAX_DEPTH_LONG_SIDE = 1008` replicates the host's TensorRT profile
bound to predict what depth shape the host *would* have chosen. The host raised that bound to
**1036** on 2026-07-25 so ultrawide reaches its configured short side (21:9 now resolves to
`1036x434`, 5K2K to `1022x434`, instead of both dropping to a 420 short side). Update the constant or
`spatialThresholdScale` mispredicts on 21:9 and wider.

## Resolution-dependent parameters — what must NOT be copied verbatim

The client's depth map is 2.4–3.4x coarser on the short side. Three classes:

**1. Already handled correctly — keep the mechanism.**
`spatialThresholdScale()` returns `hostShortSide / clientShortSide` (clamped 1–4) and is already
applied to the gradient threshold (0.02), the change threshold, and the edge fraction. That is the
right normalization: a one-texel silhouette occupies a larger fraction of a coarser map.
`alphaForInterval()` likewise converts per-host-frame α into wall-time α for the client's slower
depth cadence. **Both new EMAs (band 0.18, and the attack-fast range) must go through
`alphaForInterval` too** — only the release side, since the attack side is a `min`/`max` and has no
time constant.

**2. Self-adapting once ported — copy the formula, not the number.**
Host probe spacing is `BESTV2_TARGET_DEPTH_TEXELS / depth_width` with `TARGET = 1.22` — i.e. 1.22
*depth texels*, expressed in normalized source U. Feed the client's own `depth_width` and it adapts
automatically: at 322 wide, spacing is 2.4x the host's at 770, and the step count for a given radius
falls proportionally. Do **not** port the host's step count. Derive:

```
spacing = 1.22 / clientDepthWidth      // quantize to a power-of-two multiple, see below
steps   = ceil(2 * radius / spacing)
```

The host's calibration explicitly stops at ~1.0–1.22 texels because that is where the probe grid
matches the resolution of the signal it samples; finer oversamples a bilinear map, coarser breaks the
one-breakpoint-per-interval argument. The client's coarser depth means **fewer** probes, not the same
number — a direct perf win alongside the quality fixes.

**3. Genuinely needs re-derivation on client content.**
The adaptive-pop endpoints (0.04 / 0.20) were calibrated against the *host's* measured weighted edge
fraction at 434 short side. `spatialThresholdScale` normalizes density back to that grid, so the
endpoints should transfer — but this is a calibration, not an identity, and the client's models
(MiDaS v2 in particular) may produce a different edge-density distribution than DA-V2. Measure the
weighted edge fraction across representative client content before trusting the endpoints, exactly as
the host did. Applying the host's *old* 0.007/0.016 endpoints to client content without measuring is
what produced the current inert controller.

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

1. **Adaptive-pop edge-density calibration** (class 3 above). Edge density is a property of output
   sharpness, so validate the weighted edge fraction per model family before trusting 0.04/0.20.
2. **Depth polarity is an undocumented, untested assumption.** The client has no polarity transform
   and no per-model flag, where Apollo has an explicit "transform model output into high-is-near
   convention" pipeline step. It currently works because MiDaS v2 emits inverse depth and DA-V2's
   output is disparity-like, so both are high-is-near — but nothing enforces or tests it. The
   subject percentile scans from bin 255 as "near" and `bestv2RawShift` maps higher shaped depth to
   larger positive shift, so an opposite-polarity model would render the whole scene inside-out with
   nothing catching it. A one-off startup check (correlate a coarse depth statistic against a known
   near/far test pattern, or simply assert the manifest declares a polarity) is cheap insurance
   before a third model is ever added.

## Search radius is coupled to the pop ceiling

The probe radius constant is the adaptive-pop **maximum**, not the resolved ratio. Raising the band
to 2.00 therefore requires raising the radius multiplier from 1.30 to 2.00 in both warp templates,
or a high-pop scene's displacement leaves the search window and the probe misses crossings.

That widening does not under-sample: at a 1920-wide 16:9 source (`parallaxWidth` 854,
`outputScale` 1.333) the radius is 0.0243 in normalized U, and the fixed 32 probes give ~0.40 depth
texels of spacing against the host's 1.22 target — still roughly 3x oversampled. The lattice work in
P2 reclaims that (32 -> ~13 probes) rather than being required for correctness.

## Suggested order

1. Zero plane (P0) — largest quality gain, and it deletes `convergenceOffset`/`subjectShift` with it.
2. Adaptive pop (P0) — all four defects together; the band is meaningless until the endpoints and
   the settle timing are both right.
3. Band + range quantization and temporal behaviour (P1) — cardboarding and depth-scale breathing.
4. Warp cleanup and radius/lattice (P2) — correctness-neutral, mostly a perf win.
5. Colour cut detector (P2), then the 1036 constant (P3).
