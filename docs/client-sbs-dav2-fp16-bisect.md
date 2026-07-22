# Depth Anything V2 FP16/OpenCL bisect

Date: 2026-07-21

## Result

Depth Anything V2 Small's constant `0.088684082` output on the Galaxy XR is not
caused by the model weights, preprocessing, final decoder convolution, or an
ordinary loss of FP16 accuracy. The first bad visible activation is the second
transformer block's value-attention `BATCH_MATMUL` (TFLite operator 98, tensor
266). Both of its logical inputs are finite and bounded, but its FP16 OpenCL
output contains NaNs. FP32 is correct.

This matches the upstream report
[tensorflow/tensorflow#93476](https://github.com/tensorflow/tensorflow/issues/93476)
exactly: Depth Anything V2 on a Qualcomm GPU returns the same learned final bias,
`0.088684`. The associated upstream fix
[tensorflow/tensorflow#104247](https://github.com/tensorflow/tensorflow/pull/104247)
zeros invalid packed softmax lanes. LiteRT also has independently reported
OpenCL `BATCH_MATMUL` alignment/lowering bugs on Qualcomm in
[google-ai-edge/LiteRT#6518](https://github.com/google-ai-edge/LiteRT/issues/6518).

The shipped LiteRT 2.1.6 CompiledModel path still reproduces the defect. A paired
official `latest` runtime snapshot dated 2026-07-13 and LiteRT's
`enable_infinite_float_capping` option were tested separately and did not fix
the original graph. Downgrading or replacing LiteRT is therefore not the
production fix. The tail-padding plus GELU-rewrite Quality graphs proved a safe
historical workaround, while the smaller graphs are naturally C4-aligned and
need only the GELU rewrite. Production now ships only those three aligned
`322 x 182`, `350 x 154`, and `434 x 126` graphs. They pass the on-device FP16
parity gate and run with `AUTOMATIC_FP16`; the larger Quality graphs are retired.

## Historical environment and original graph

- Device: Samsung Galaxy XR, Adreno GPU, Android API 34.
- Runtime: official LiteRT 2.1.6 `libLiteRt.so` and
  `libLiteRtClGlAccelerator.so`.
- Backend: CompiledModel, ML Drift OpenCL, packed Float32 GL boundaries.
- Model: `depth-anything-v2-small-static-350x196-float32.tflite.model`.
- Model SHA-256:
  `4e62f378646966c99855e4648cbc22f3b6f8ce4ea2efbefd27ee735300f98e57`.
- Delegation: all 959 operators in one `LITERT_CL` partition.
- Test input: deterministic edge-rich full-frame gradient uploaded through the
  same GL-buffer path as production.

The 350x196 input creates a 25x14 patch grid plus the class token: 351 tokens.
The other retired Quality buckets are also non-multiples of four:

| Input bucket | Patch tokens + CLS | Packed tail |
| --- | ---: | ---: |
| 350x196 | 350 + 1 = 351 | 3 valid lanes, 1 hidden lane |
| 392x168 | 336 + 1 = 337 | 1 valid lane, 3 hidden lanes |
| 490x140 | 350 + 1 = 351 | 3 valid lanes, 1 hidden lane |

All three buckets returned the same final bias under OpenCL FP16 and healthy,
non-flat depth under OpenCL FP32.

These details describe the original graphs used for the bisect, not the
current production assets. Reversible copies of all three originals are kept
under the ignored `build/client-sbs-original-models/` directory in the client
checkout.

## Activation bisect

Checkpoint models change only the graph's sole output; they do not change
weights or upstream operations. Each checkpoint ran through the same fully
delegated native GL/OpenCL path in both FP16 and FP32.

| Tensor / producer | FP16 result | Finding |
| --- | --- | --- |
| 157, block 0 Q | all finite; cosine 0.999996 vs FP32 | Early encoder is healthy |
| 251, block 1 Q | all finite; cosine 0.999900 | Block 1 inputs remain healthy |
| 259, block 1 transposed V | all finite, `[-4.71875, 3.78320]` | Value operand is bounded |
| 264, block 1 QK matmul | all finite, `[-36.0625, 73.0625]` | QK matmul is healthy |
| 265, block 1 softmax | all finite, `[0, 1]` | Logical softmax output looks healthy |
| 266, block 1 softmax x V | 896 NaNs; no infinities | First bad visible activation |
| 267, following transpose | same 896 non-finite values | Merely propagates corruption |
| 345, later block Q | 5,376 non-finite values | Corruption spreads |
| 446, 765, 1390 | entirely non-finite | Encoder/decoder eventually collapse |
| 1504, post-ReLU/pre-final-conv | all zero | Final conv can return only its bias |

The updated raw checkpoint classified all 896 bad values at tensor 266 as
NaNs. They form exactly 14 complete 64-channel vectors; there are no partially
bad vectors. Block 0's identically shaped value-attention output, tensor 172,
is entirely finite. Shape alone is therefore not sufficient; the failure is
data- and lowering-dependent.

Legitimate FP16 overflow is impossible at operator 98. The softmax operand is
in `[0, 1]`, V is in `[-4.71875, 3.78320]`, and a deliberately loose bound that
ignores softmax normalization is only `351 * 4.71875 = 1656.28`, far below the
FP16 maximum 65,504. Rounding the CPU operands to IEEE half, including a
flush-to-zero simulation, also keeps every output finite.

The strongest explanation is the same packed-softmax-tail defect family as the
upstream DA-V2 report. The logical tensor exposes only 351 channels, so a
checkpoint cannot see the fourth PHWC4 lane. A bad hidden lane can be consumed
by the following ML Drift attention lowering and produce `Inf * 0 = NaN` even
though both logical inputs look valid.

## Workaround experiments

All times below are idle-device, thermal-status-0 `LiteRtRunCompiledModel()`
wall times after two warmups. They include runtime/driver blocking, not just GPU
kernel duration.

| Variant | FP16 result | FP16 mean | FP32 mean | Outcome |
| --- | --- | ---: | ---: | --- |
| Original 959-op graph | flat bias | 21.37 ms | 41.02 ms | Wrong |
| Post-softmax K pad 351->352 | flat bias | 22.25 ms | 42.38 ms | Wrong |
| K and output-M pad to 352 | flat bias | 22.27 ms | 43.75 ms | Wrong |
| Pre-softmax explicit tail 351->352 | non-flat and finite | 22.16 ms | 42.30 ms | Correct |
| Value attention rank 4->3->4 | non-flat and finite | 23.88 ms | 43.99 ms | Correct |
| Official `latest` paired runtime | flat bias | 21.21 ms | 40.22 ms | Wrong |
| Infinite-float capping | flat bias | 21.22 ms | 40.21 ms | Wrong |

The preferred workaround makes the normally hidden packed tail explicit before
each of the 12 attention softmax operations. It appends a `-65504` logit, so
the new lane has zero probability, and appends a zero row to V. The attention
product still returns the original 351 query rows. The graph remains fully
delegated and its FP16 result compared with FP32 at NRMSE 0.01059, cosine
0.999950, maximum absolute error 0.06387, and RMS ratio 1.00353. It adds 24
kernels; profiling measured 534 model kernels and 19.441 ms of model-kernel
work, versus 510 kernels and 18.746 ms for the incorrect original graph.

The alternative working rank rewrite changes every value-attention product from
`[1,6,N,N] x [1,6,N,64]` to the mathematically identical
`[6,N,N] x [6,N,64]`, then reshapes its output back. On the Galaxy XR its FP16
result has the same parity statistics and remains fully delegated, but it is
about 1.72 ms slower than the explicit-tail rewrite.

Post-softmax padding did not help because it operates after the suspected
packed-lane corruption and adds real GPU copy kernels. Profiling showed exactly
24 extra kernels and about 0.86 ms of extra model-kernel work for the 24 PAD
operators.

The explicit-tail transform was then generalized to all three historical Quality buckets.
The `350 x 196` and `490 x 140` graphs align 351 tokens to 352; `392 x 168`
aligns 337 tokens to 340. One sentinel constant and one zero-V constant are
shared across all 12 blocks in each graph. Before GELU fusion, all three graphs
remained fully delegated and returned finite, non-flat FP16 output:

| Bucket | Corrected FP16 mean | Corrected FP32 mean | FP16 vs corrected FP32 |
| --- | ---: | ---: | --- |
| `350 x 196` | 22.199 ms | 42.186 ms | NRMSE 0.010591, cosine 0.999950322 |
| `392 x 168` | 21.779 ms | 40.953 ms | NRMSE 0.008151, cosine 0.999968777 |
| `490 x 140` | 22.274 ms | 41.917 ms | NRMSE 0.008359, cosine 0.999984958 |

Corrected FP32 was bit-for-bit identical to original FP32 for all three
buckets (NRMSE 0, cosine 1, maximum absolute error 0), isolating this transform
from the intended Float32 model function.

The second production transform identifies each private 24-operation expanded
GELU DAG by wiring, shapes, constants, and consumers and replaces it with one
builtin exact GELU. Combining the 12 replacements with the explicit-tail fix
reduces each graph from 983 to 707 operations. All 707 operations delegate in
one `LITERT_CL` partition. Final Galaxy XR results were:

| Bucket | FP16 mean | FP32 mean | FP16 vs final FP32 | Final FP32 vs original FP32 |
| --- | ---: | ---: | --- | --- |
| `350 x 196` | 18.724 ms | 34.093 ms | NRMSE 0.00855207, max abs 0.0700558, cosine 0.999967727 | NRMSE 0.000787537, max abs 0.00303674, cosine 0.999999812 |
| `392 x 168` | 18.439 ms | 32.867 ms | NRMSE 0.00695852, max abs 0.0577090, cosine 0.999975790 | NRMSE 0.00104656, max abs 0.00387597, cosine 0.999999799 |
| `490 x 140` | 18.558 ms | 34.001 ms | NRMSE 0.00697083, max abs 0.0512509, cosine 0.999981069 | NRMSE 0.000520406, max abs 0.00277960, cosine 0.999999874 |

At `350 x 196`, exact GELU reduced corrected-FP16 wall time from 22.20 ms to
18.72 ms, about 15.7%. The final profile recorded 462 model kernels and
16.528 ms of model-kernel time, plus 1.083 ms upload/bind and 0.619 ms
download, for 18.230 ms of recorded delegate work.

### Naturally aligned canonical-bucket experiments

The production DA-V2 setting has one naturally C4-aligned graph for each
canonical aspect. This avoids the explicit K/V tail-padding operations while
reducing the transformer token budget. All three guarded GELU-only graphs
contain 683 operations and delegate 683/683 in one OpenCL partition. Stream
initialization selects the nearest of these three aspects directly.

#### `322 x 182`

The canonical 16:9 bucket uses 58,604 input pixels and a `23 x 13`
patch grid. Its 299 patches plus CLS produce exactly 300 tokens, so it is
already C4-aligned and needs none of the 24 explicit K/V tail-padding
operations used by the retired Quality graphs. After the guarded builtin-GELU rewrite,
the candidate contains 683 operations and delegates 683/683 in one OpenCL
partition. Its asset name and SHA-256 are:

- `depth-anything-v2-small-static-322x182-float32.tflite.model`
- `c257f29a774b55d3a7ffef8e9f2769876ad6adce0f0977e31b14ca706fa25e24`

Idle-device, thermal-status-0 validation measured 16.416 ms FP16 and 28.177 ms
FP32. Candidate FP16 versus candidate FP32 was NRMSE 0.00881204, maximum
absolute error 0.0459776, and cosine 0.999968471. Candidate FP32 versus the
original `322 x 182` FP32 graph was NRMSE 0.00167522, maximum absolute error
0.005831, and cosine 0.999999754 on the device. The independent PC comparison
was NRMSE 0.00420616, maximum absolute error 0.00146663, and cosine
0.999999976. Before GELU fusion, the original aligned graph's FP16-versus-FP32
result was also healthy: NRMSE 0.0118906, maximum absolute error 0.0519145, and
cosine 0.99998844.

The intrusive profile recorded 438 model kernels / 14.715 ms and 16.395 ms
total delegate work. Relative to the final retired `350 x 196` Quality graph's 18.724
ms call-wall mean, the candidate is 12.33% faster and uses about 15% fewer
transformer tokens. It is now the canonical production 16:9 graph; the larger
Quality graph is retained only as historical comparison evidence.

#### `350 x 154`

The canonical 21:9 graph uses 53,900 pixels and a `25 x 11` patch grid:
275 patches plus CLS equals exactly 276 tokens. Its asset identity is:

- `depth-anything-v2-small-static-350x154-float32.tflite.model`
- Candidate SHA-256:
  `3a4db950d5764203cdcba5fb1a104456a81e41721318a3d0fd07dc834085bc96`
- Original 959-op SHA-256:
  `174ab97d5fb87c1d992f1c0ff6700ced949ccd3e5eda3bdf641be2c446f441f1`

Idle-device, thermal-status-0 validation measured 15.664 ms FP16 and 26.782 ms
FP32. Candidate FP16 versus candidate FP32 was NRMSE 0.00395061387, maximum
absolute error 0.0527739525, and cosine 0.999994676. Candidate FP32 versus the
original FP32 graph on-device was NRMSE 0.00143996169, maximum absolute error
0.00383198261, and cosine 0.999999915. The independent PC comparison was NRMSE
0.00095827313, MAE 0.00089220112, maximum absolute error 0.00166559219, and
cosine 0.999999995535.

Its profile recorded 438 model kernels / 14.073 ms, 0.946 ms upload/bind, and
0.606 ms download, for 15.625 ms total delegate work. The call-wall mean is
15.05% faster than the retired `392 x 168` Quality baseline of 18.439 ms. Directly
resizing nominal 21:9 content to `350 x 154` introduces -2.60% aspect
distortion, so live visual comparison remains an acceptance requirement.

#### `434 x 126`

The canonical 32:9 graph uses 54,684 pixels and a `31 x 9` patch grid:
279 patches plus CLS equals exactly 280 tokens. Its asset identity is:

- `depth-anything-v2-small-static-434x126-float32.tflite.model`
- Candidate SHA-256:
  `71937f21c58006726bf263daa2ddb7dbb6335f3ca1398530e4ca5f5b994889a4`
- Original 959-op SHA-256:
  `0e746d66a40eaa6673cef93144f49843c0f0a10fc618dbe15cc71bba2f9a3055`

Idle-device, thermal-status-0 validation measured 15.830 ms FP16 and 26.920 ms
FP32. Candidate FP16 versus candidate FP32 was NRMSE 0.00703013665, maximum
absolute error 0.0578334332, and cosine 0.999994172. Candidate FP32 versus the
original FP32 graph on-device was NRMSE 0.000633352391, maximum absolute error
0.00203704834, and cosine 0.999999946. The independent PC comparison was NRMSE
0.00096661404, MAE 0.00088941009, maximum absolute error 0.00135374069, and
cosine 0.999999999312.

Its profile recorded 438 model kernels / 14.118 ms, 1.018 ms upload/bind, and
0.345 ms download, for 15.481 ms total delegate work. The call-wall mean is
14.70% faster than the retired `490 x 140` Quality baseline of 18.558 ms. Directly
resizing nominal 32:9 content to `434 x 126` introduces -3.125% aspect
distortion, so live visual comparison remains an acceptance requirement.

### Half-resolution decoder/head experiment

Profiling attributed about 9.8 ms in aggregate to decoder/head 1x1 and 3x3
convolutions, motivating a guarded graph that retains the `350 x 196` input but
moves the final resize and output convolutions to an exact `175 x 98` depth
output. The generated graph has SHA-256
`1a0df67bd9d2b6524ae51649f7c332420f64fa4f9a8ebdb812c51eec9b553b26`,
keeps 707 operations, and delegates 707/707 in one OpenCL partition.

It measured 18.050 ms FP16 and 33.194 ms FP32. FP16 versus FP32 was NRMSE
0.008534, maximum absolute error 0.056952, and cosine 0.999967916. Its profile
recorded 462 model kernels / 16.154 ms and 17.516 ms total delegate work. The
call-wall saving is only about 0.67 ms versus the full-resolution retired Quality
graph, because most encoder and decoder work remains unchanged. An edge-aware
GLES reconstruction pass would likely consume the saving while introducing a
new visual-quality risk, so this model and renderer path are not promoted.
More aggressive decoder pruning damaged depth quality and is rejected.

The guarded experimental generators are:

- `tools/generate-dav2-checkpoint-model.py`
- `tools/generate-dav2-attention-k352-model.py`

They accept only a verified original source hash/graph, write only below this
client repository's `build` or `temp` directories, refuse Apollo-3D paths and
Android source-assets destinations, reparse the result, and require CPU
interpreter allocation before publishing it. The attention generator's
`--fuse-gelu` option applies the guarded exact-GELU rewrite after the explicit
tail transform. Its guarded `--mode gelu-only` path requires an already
C4-aligned token count, leaves attention wiring unchanged, and fuses exactly
the twelve known GELU DAGs; this is the path used for all three canonical
buckets. Validated generated models enter the production model archive only as
a separate promotion step.

## Why the model is slow

The input contains only about 68,600 pixels, but DA-V2 Small is a 12-block
DINOv2 transformer, not a small convolutional depth network. Static graph
accounting gives about 11.694 billion multiply-accumulates per inference before
elementwise work:

- `BATCH_MATMUL`: about 8.588 GMAC.
- `CONV_2D`: about 2.847 GMAC.
- `TRANSPOSE_CONV`: about 0.258 GMAC.

The corrected intrusive OpenCL profile of the original wrong-FP16 graph found
510 model kernels, 18.746 ms of model-kernel time, 0.933 ms upload/bind, and
0.574 ms download: 20.253 ms of recorded delegate work. FP32 measured about
36.955 ms of model kernels and 38.508 ms including transfers. Thus FP16 is
nearly twice as fast when isolated, but the unmodified FP16 result is invalid.

Representative FP16 kernel attribution was:

- Transformer encoder: 81.7%.
- Decoder/head: 17.0%.
- Patch/input: about 1%.
- Convolution/GEMM kernels: 60.6%.
- Expanded GELU: 14.3%.
- Layout, resize, residual, and ReLU: 12.5%.
- Softmax: 6.4%.
- LayerNorm: 6.2%.

Live streaming is slower than the isolated model test because decode, 4K/XR
rendering, GL packing, OpenCL inference, and composition contend for the same
Adreno. The observed 57-79 ms inference rises to roughly 105 ms when thermal
throttling lowers the GPU clock from about 788 MHz to 421 MHz. Transfers are
only around 1.5 ms and are not the primary bottleneck.

Replacing each 24-operation expanded GELU chain with LiteRT's delegated builtin
GELU was the next highest-value graph optimization. It removed 276 operations
and 72 executed kernels relative to the corrected pre-softmax-only graph while
preserving the parity shown above. Further large gains are more likely to come
from reducing transformer tokens or using a genuinely mobile backbone than
from micro-optimizing the already-GPU-resident pre/postprocess path.

The follow-up experiments confirm that split. Naturally aligned 300-, 276-,
and 280-token graphs produced 12.33%, 15.05%, and 14.70% inference wins and are
now the canonical production set. Those gains were measured against the retired
Quality graphs. The 21:9 and 32:9 canonical shapes introduce -2.60% and -3.125%
direct-resize aspect distortion, so their latency results do not replace live
visual acceptance. Merely reducing the final output
head to `175 x 98` saved about 0.67 ms, too little to fund edge-aware
reconstruction; removing more decoder work produced unacceptable depth-quality
loss.

## Production decision

Do not enable FP16 for an original non-C4 959-operation Quality graph. The
tail-padding plus exact-GELU variants proved the defect and workaround, but the
larger Quality set is now retired. Production ships only the naturally aligned,
exact-GELU canonical graphs with `AUTOMATIC_FP16`:

| Role | Bucket | Production SHA-256 |
| --- | --- | --- |
| Canonical DA-V2 | `322 x 182` | `82f8594f4ee615ab82f968aa461a3960c4cd680293fd087cb65d8631b18e4271` |
| Canonical DA-V2 | `350 x 154` | `2739f306ce71b19a913cdc32c779226a620f7f81685a1946ac213fdbeeba67b0` |
| Canonical DA-V2 | `434 x 126` | `353eb80fd6b9c6f97552a20b7bd29f79466a9b05287dcd4bfd93baaa4c1730f5` |

The corrected Quality graphs remain historical evidence only:

| Retired role | Bucket | Historical packaged SHA-256 |
| --- | --- | --- |
| Quality | `350 x 196` | `0f88116842c30c63f9fddd60b9c45ced3ad2ce47da1efcb805fa117865853faf` |
| Quality | `392 x 168` | `0be8def82de0993caf64201baa59782224bb64bc0087a04cbb4e56842425476b` |
| Quality | `490 x 140` | `85ffc4360718b5b1f273de8508fc16783152d4641f385a563c521cff3119a918` |

The production hashes are the FP16-weight-storage packaging of the validated
aligned graphs discussed above. Their public tensors remain Float32, and their
CPU outputs are bit-identical to the corresponding Float32-stored transformed
sources. Historical `float32` filenames and hashes earlier in this document
identify experimental inputs, not APK assets.

Before storage conversion, every canonical core passed one-partition 683/683
OpenCL delegation. The packaged graphs add 82 constant `DEQUANTIZE` nodes and
must pass complete acceleration as 765-operation graphs. All three passed CL/GL
interop, finite non-flat edge-rich output, repeatability, FP16-vs-FP32 parity,
and transformed-FP32-vs-original-FP32 parity on the Galaxy XR.

The APK stores the three DA-V2 graphs as complete standard TAR entries in the solid
`client-sbs-dav2-models.tar.xz` archive and the three MiDaS graphs as complete
standard TAR entries in `client-sbs-midas-models.tar.xz`. One XZ/LZMA2 stream
compresses each family's entire TAR so ordinary compression can exploit similarity
between all three complete graphs. There is no base/delta encoding, XOR transform,
custom model representation, or reconstruction step. At stream initialization, the
loader scans the family stream, writes only the selected complete model into the app
code cache, and verifies its SHA-256 before LiteRT compiles it. Selecting a later
entry on a cold cache decompresses the preceding stream, but verified cache reuse
avoids that cost on later initialization.
Legacy Quality model IDs migrate to the canonical DA-V2 selection.

The deterministic DA-V2 TAR/XZ is 44,429,612 bytes (42.37 MiB), SHA-256
`3f9892624253e5d7301d6b0eb28acc7ef30ac2cf3131acbc7a8c1f59696ad148`;
the MiDaS TAR/XZ is 29,947,928 bytes (28.56 MiB), SHA-256
`166be90ec3866dfeae61ce7163df49414840b6d054466d79dbe153ea3ebc8b94`.
Together they are 74,377,540 bytes (70.93 MiB). Total debug APK size is intentionally not pinned;
measure the current requested split from Gradle's build output.

MiDaS remains available as the explicit comparison family. The half-resolution
head is rejected and is not selectable. Generated experiment models stay under
the client repository's ignored `build` tree or system temporary storage. Loose
original assets are not retained in the working tree or APK; the recorded hashes
and pinned reproduction inputs preserve the historical evidence without placing
generated client files in Apollo-3D.
