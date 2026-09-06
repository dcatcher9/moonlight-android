# Experimental ZipDepth output-scatter rewrite

`rewrite-zipdepth-output-scatter.py` generates an isolated candidate from one of the three
current production graphs. It does not change packaged assets, manifests, model selection, or
rendering. A successful CPU check is not permission to ship the candidate: full GPU delegation,
complete numerical output, and a repeatable improvement in normal device wall time are required.

The input model must match both `--expected-source-sha256` and one of the production hashes in
[the model provenance document](model-sources/README.md#zipdepth-original-base-production-family).
The shared [loose-model path guard](client_sbs_model_paths.py) accepts output only below this
client checkout's ignored `build/` or `temp/` tree and rejects Android source assets. Existing
output is preserved unless `--force` is explicit. Publication occurs only after validation.
No private model is needed to run the focused tests.

## Exact invariant

Current operator 161 is a bias-free `TRANSPOSE_CONV` with stride two, VALID padding, no fused
activation, and the exact Float32 `[1,2,2,4]` identity scatter kernel. It maps
`[1,H,W,4]` to `[1,2H,2W,1]`. The channels already contain the four learned convex subpixel
values in row-major order:

```text
output[0, 2*y+dy, 2*x+dx, 0] = input[0, y, x, 2*dy+dx]
```

`DEPTH_TO_SPACE(block_size=2)` expresses that same rearrangement directly. The tool verifies
the actual serialized filter, shapes, types, options, consumer chain and final public ReLU;
it rejects any learned/nonidentity kernel, channel permutation, bias, alternate padding or
activation. Only that operator is replaced. All learned weights, input preprocessing,
global-context scaling, tensor identities, output contract and final ReLU remain unchanged.
The original unused scatter constants are retained to minimize the experiment.

There are still 163 operators. This change does not inherently remove a dispatch or allocation.
The possible gain is a simpler OpenCL rearrangement kernel in place of convolution arithmetic;
the delegate may already optimize the original. Operator count is not kernel count or timing.

## Generate and test

Use the already-configured model evaluation interpreter consistently. The shared rewrite
dependencies are recorded in `zipdepth-gpu-rewrite-requirements.txt`; do not replace a working
machine's environment to mix numerical runtimes in a comparison. In the examples, `$ModelPython`
is the explicit path to that interpreter and `$Source` is the verified loose source graph.

```powershell
& $ModelPython tools/rewrite-zipdepth-output-scatter.py `
  --source $Source `
  --expected-source-sha256 6296d5c2e4f857fd551d854ebf4dd2ab2462c0d7372d526bf0a7463718b8b6d1 `
  --output build/zipdepth-scatter/zipdepth-base-672x384-depth-to-space.tflite

& $ModelPython -m unittest discover -s tools/tests `
  -p test_rewrite_zipdepth_output_scatter.py -v
```

Generation executes both complete graphs through the CPU builtins with one thread and no default
delegates. Independent random RGB and channel-distinct edge/checker inputs must produce finite,
bit-exact Float32 outputs. Optional `--input-rgb path.f32le` adds one exact-size little-endian
Float32 NHWC real-frame tensor in `[0,1]`. Run it for multiple representative frames. The tool
prints source/candidate hashes, shape, interpreter, NumPy version, and CPU validation count.

The focused tests execute tiny real TFLite scatter/ReLU graphs, validate unique subpixel
coordinates and repeated inputs, and reject malformed kernels, shapes, options and consumers.
They also verify preservation of weights/public output and rejection of unpinned source hashes.

## Device qualification through existing benchmark hooks

Follow [the device evaluation and data-preservation rules](../docs/client-sbs-evaluation.md).
The existing `ClientSbsGpuInferenceEngineInstrumentedTest` external hooks take files directly
inside the debug app's external-files `client-sbs-checkpoints` directory, verify their SHA-256,
and use isolated benchmark compiler caches. Production model selection is untouched.

- `externallyPushedCheckpointFp16VsFp32`: pass `checkpoint`, `sha256`, `input_shape`,
  `output_shape`, `precision=fp16`, `warmups=20`, `runs=100`, `input_file`, and `output_prefix`.
  Run baseline and candidate with the identical RGB tensor and alternate order. This supplies
  normal LiteRT call wall and output-ready timings plus `<output_prefix>-fp16.f32le` output.
  Compare complete finite output arrays across both graphs and multiple inputs/repetitions;
  a non-flat result alone does not prove every reused output element was overwritten.
- `externallyPushedModelsFp32Parity`: pass `reference`, `reference_sha256`, `candidate`,
  `candidate_sha256`, `input_shape`, and `output_shape`. This compares actual GPU FP32 output
  on its built-in gradient. It does not accept a real `input_file`; use the checkpoint hook
  separately for each graph for real-frame comparisons.
- `externallyPushedModelOpenClProfile`: pass `checkpoint`, `sha256`, `input_shape`,
  `output_shape`, and explicit `precision=fp16` (the default is FP32). It reports OpenCL
  `PROFILE_EVENT`/`PROFILE_SUMMARY`/`PROFILE_REPORT`. Profiling causes double dispatch;
  use it only to identify kernels, separately from normal timing measurements.

Require all three production shapes, full OpenCL delegation, no CPU fallback, output completeness,
parity and repeatability, controlled thermals, and a useful improvement beyond run variation.
Any unsuccessful candidate stays experimental. Isolated model timing does not establish sustained
stream performance under decode, GLES reprojection and XR composition load.

## Other graph candidates and constraints

These are hypotheses to rank with the profiler, not enabled rewrites:

- The learned convex tail has four nine-way softmax branches. A static rank-four view such as
  `[1,H,W*4,9]` could combine the softmax work without altering learned masks. Layout conversion
  and reduction order may offset any dispatch saving; verify borders, subpixel order and FP16
  parity before considering wider tail fusion.
- The global-context branch explicitly transposes NHWC features to NCHW for its weighted sum.
  NHWC probability broadcast and spatial reduction could avoid this intermediate, but delegate
  reduction support and numerical order must pass the same parity gates. Retain the power-of-two
  scale and inverse scale.
- The compatibility 24-to-32 grouped convolution has block-diagonal dense weights. Splitting it
  into four ordinary convolutions avoids zero arithmetic but adds dispatches and six-channel
  layout overhead. A split/concat probe already exists in earlier local evaluation history;
  do not repeat it without profiler evidence or a changed delegate implementation.

Rejected shortcuts remain rejected: dropping global-context scale1024 reintroduces observed FP16
underflow; replacing learned context weights with average pooling failed source parity; using
`base_npu` substitutes a separately trained checkpoint and different upsampler; direct external
PHWC4 previously left output elements unwritten. Symmetric PAD1 plus stride2 VALID on even input
sizes is not equivalent to SAME padding: the leading footprint changes. FP16 stored weights are
already used, and constant DEQUANTIZE nodes do not imply per-frame CPU work. Static shapes,
automatic internal storage and automatic FP16 GPU execution already apply to production.

## Galaxy XR result, 2026-09-05: no measurable gain

The output-scatter candidate was tested on all three production shapes with OpenCL FP16,
automatic internal storage, low priority, 20 discarded warmups, 100 measured invocations per
leg, and thermal status 0 before/after. Each shape used the same real-frame RGB tensor for its
baseline and candidate. Complete downloaded GPU output arrays were bit-exact for all three
paired frames. This establishes parity on those inputs, not a complete corpus qualification.

| Shape | Normal LiteRT wall median, baseline → candidate | Output-ready median, baseline → candidate |
| --- | ---: | ---: |
| 672×384 | 10.073 → 10.095 ms | 10.690 → 10.696 ms |
| 896×384 | 12.964 → 12.974 ms | 13.577 → 13.588 ms |
| 928×384 | 13.336 → 13.365 ms | 13.981 → 13.994 ms |

There was no useful timing improvement. The packaged models remain unchanged, and this tool
retains an experimental candidate only. Raw local evidence is under the ignored
`build/client-reuse-2026-09-05/model/` directory (`gpu-parity.json`, benchmark logs, and
`profile-baseline-log.txt` / `profile-depth-to-space-log.txt`).

Separate intrusive 672×384 gradient profiles showed 96 model kernels in both graphs. The
baseline scatter plus ReLU took 20 µs of the 9,040 µs model-kernel total; the candidate
depth-to-space plus ReLU took 43 µs. These are individual profiled invocations, not normal
wall-time samples or evidence of a sustained regression. The profiler's double dispatch and
the variation in other unchanged kernels make cross-profile totals unsuitable for ranking
the two models. They do explain why optimizing this tiny final operation had little upside.

The profiles suggest more substantial targets for a future, separately qualified experiment:

1. **Project channels before the final bilinear upsample.** Serialized ops 127–128 resize
   `[1,96,168,96]` to `[1,192,336,96]`, then apply a linear grouped 1×1 convolution to 32
   channels. The last resize and fused projection/residual/BN/ReLU kernels took 537 and
   508 µs in the baseline profile (523 and 451 µs in the candidate profile). Moving only
   the linear projection before the resize would perform it at one quarter as many pixels
   and resize 32 channels instead of 96. Channelwise linear interpolation and the projection
   commute mathematically; keep the residual, BN and ReLU after resizing and preserve the
   exact half-pixel/border behavior. FP16 rounding and altered delegate fusion may change
   numerical output or erase the benefit, so this is a hypothesis, not an enabled rewrite.
2. **Reduce materialized branches in the learned convex tail.** Four channel slices cost
   269 µs, their four softmaxes cost 384 µs across eight kernels, four products cost 333 µs,
   and four weighted reductions cost 156 µs in the baseline profile. Combining the existing
   nine-way groups or fusing that exact learned tail has more upside than changing the final
   scatter. Preserve learned masks, replicate-border neighbors and subpixel order; do not
   substitute the separately trained NPU head.
3. **Deprioritize tiny compatibility cleanup.** The entire global-context attention branch
   cost 94 µs and the densified 24-to-32 convolution cost 162 µs. Scaling operations already
   appear inside fused `reshape -> mul` / `reduce_sum -> mul` kernels. Removing the proven
   underflow protection is neither necessary nor a credible performance optimization.

Ordinary 3×3 convolutions account for 4,963 µs (about 55%) of the baseline model-kernel total.
Accelerating those materially would require a separately verified backend/kernel change or a
model-quality tradeoff, not simply removing redundant exporter operators. No further candidate
graph was generated or packaged as part of this measured scatter experiment.
