# Client SBS evaluation

This is the reproducible evaluation entry point for Artemis Client SBS. Production has one depth
backend—the native LiteRT 2.x GPU path—with DA-V2 Small and MiDaS v2.1 Small as explicit model
choices. The guide separates four questions:

1. Do the model, shader, and frame-ownership contracts pass on the JVM?
2. Does the supported Android XR APK assemble?
3. Does the real model execute through shared GL buffers on Galaxy XR and produce non-flat depth?
4. Does the complete decode-to-stereo path remain responsive during a sustained stream?

Run commands from the Artemis checkout root. Keep generated APKs, reports, and captures in this
repository's build directories, the device app directory, or a temporary directory. Do not write
client artifacts into the Apollo checkout.

## Production contract

Client SBS has one native LiteRT GPU backend with DA-V2 Small and MiDaS v2.1 Small as its two
production model families. Because the Galaxy XR GPU delegate requires static graphs, each family
has three canonical aspect buckets with Float32 public tensors and FP16-stored large weights. The
renderer selects the concrete bucket with the smallest multiplicative error,
`abs(log(bucketAspect / sourceAspect))`, directly among that family's three buckets exactly once
when the stream is created.

| DA-V2 target aspect | Fixed input and output | Pixels / tokens | Logical model and SHA-256 |
| --- | --- | ---: | --- |
| 16:9 | `rgb_nhwc[1,182,322,3]` → `depth_bhwc[1,182,322,1]` | 58,604 / 300 | `depth-anything-v2-small-static-322x182-fp16weights.tflite.model` — `82f8594f4ee615ab82f968aa461a3960c4cd680293fd087cb65d8631b18e4271` |
| 21:9 | `rgb_nhwc[1,154,350,3]` → `depth_bhwc[1,154,350,1]` | 53,900 / 276 | `depth-anything-v2-small-static-350x154-fp16weights.tflite.model` — `2739f306ce71b19a913cdc32c779226a620f7f81685a1946ac213fdbeeba67b0` |
| 32:9 | `rgb_nhwc[1,126,434,3]` → `depth_bhwc[1,126,434,1]` | 54,684 / 280 | `depth-anything-v2-small-static-434x126-fp16weights.tflite.model` — `353eb80fd6b9c6f97552a20b7bd29f79466a9b05287dcd4bfd93baaa4c1730f5` |

Their patch layouts are `23 x 13 + CLS = 300`, `25 x 11 + CLS = 276`, and `31 x 9 + CLS = 280`.
Each token count is already C4-aligned, so none needs explicit attention-tail padding. Direct
full-frame resize into `350 x 154` and `434 x 126` changes the
nominal 21:9 and 32:9 aspect by -2.60% and -3.125%, respectively. That distortion is an explicit
production trade-off and still requires live visual validation.

Both spatial dimensions in every bucket are divisible by the DA-V2 patch size of 14. The three
canonical buckets use roughly 54,000–59,000 pixels. Other source aspects map directly to the nearest
bucket in the selected family. A renderer never changes model or tensor dimensions in the middle
of a stream.

| MiDaS target aspect | Fixed input and output | Pixels | Asset and SHA-256 |
| --- | --- | ---: | --- |
| 16:9 | `image[1,192,352,3]` → `depth_estimates[1,192,352,1]` | 67,584 | `midas-v2-small-static-352x192-fp16weights.tflite.model` — `2a3ee0a1e818c4f785bcd0ceb10f5c81f08b3b91304f2f15d113c1089d3e524e` |
| 21:9 | `image[1,160,384,3]` → `depth_estimates[1,160,384,1]` | 61,440 | `midas-v2-small-static-384x160-fp16weights.tflite.model` — `5a66ab484a888c3c9e1642580ac3086c7d6d3175a860ca1e82f30d7a58c532bd` |
| 32:9 | `image[1,128,448,3]` → `depth_estimates[1,128,448,1]` | 57,344 | `midas-v2-small-static-448x128-fp16weights.tflite.model` — `060ec0e16fd4e20f2626d6ac51d80853a1bdf9b2f082c3d933099784cf9cabfb` |

MiDaS v2.1 Small's EfficientNet-Lite3 encoder and four-level decoder refinement pyramid require
both spatial dimensions to be divisible by 32. The three graphs specialize the verified Qualcomm
Float32 model's input, output, and decoder-resize constants, then round its large convolution
weights to FP16 storage. Small biases and control constants remain Float32. They use MiDaS-specific
dimensions rather than DA-V2's 14-aligned dimensions.

The guarded `tools/convert-tflite-fp16-weights.py` promotion step inserts delegated `DEQUANTIZE`
nodes ahead of the original Float32 graph tensors. It converts only finite live rank-2-or-higher
constants of at least 1 KiB, reparses the result, verifies public I/O, and requires finite non-flat
CPU output plus correlation >= 0.995 and normalized RMSE <= 0.03 against its exact source. DA-V2's
three canonical graphs each contain 765 serialized operations (683 core plus 82 constant
dequantizations); MiDaS contains 234 (138 plus 96). MiDaS CPU correlations were 0.999908, 0.999868,
and 0.999609 for 16:9, 21:9, and 32:9 respectively. Complete-acceleration validation applies to the
full packaged graphs, not only to their pre-storage cores.

The non-root flavor stores the models in two standard solid family TAR/XZ assets; root APKs contain neither
archive nor the LiteRT runtime:

- `app/src/nonRoot_game/assets/client-sbs-dav2-models.tar.xz` contains all three complete DA-V2 model
  files.
- `app/src/nonRoot_game/assets/client-sbs-midas-models.tar.xz` contains all three complete MiDaS model
  files.

Every `.tflite.model` is a complete standard TAR entry using the exact logical filename in the
contract tables. A single XZ/LZMA2 stream compresses each family's entire three-entry TAR, allowing
ordinary solid compression to exploit the graphs' cross-file similarity. There are no base entries,
deltas, XOR transforms, custom model representations, or reconstructed model bytes. The Java model
manifest records each model's family archive, TAR entry, and expected SHA-256.

The deterministic DA-V2 TAR/XZ is 44,429,612 bytes (42.37 MiB), SHA-256
`3f9892624253e5d7301d6b0eb28acc7ef30ac2cf3131acbc7a8c1f59696ad148`; the MiDaS TAR/XZ is
29,947,928 bytes (28.56 MiB), SHA-256
`166be90ec3866dfeae61ce7163df49414840b6d054466d79dbe153ea3ebc8b94`. Together they are
74,377,540 bytes (70.93 MiB), with both already-compressed XZ assets stored directly in the APK.
Measure total APK size from the current build output rather than treating a debug APK byte count as
a stable model-package property.

On first Client SBS use in a stream session, the inference worker sequentially scans the selected
family's TAR/XZ stream and writes
only the selected complete model entry into `code_cache/client-sbs-model-assets`, verifies its
SHA-256, fsyncs a temporary file,
and atomically renames it to a hash-named final file before passing its read-only descriptor to
LiteRT. The cache prunes other staged models. A later stream reuses the verified selected file or
extracts its newly selected family/aspect; first initialization timing includes one-time extraction
and verification. A cold selection of a later TAR entry must decompress the preceding XZ stream,
although those preceding files are not written; the verified cache avoids repeated decompression.

Only one selected model may compile or remain GPU-resident process-wide. The engine stays idle and
resident when presentation switches from Client SBS to Normal, Raw, or Host SBS, then is reused
without a compile stall if Client SBS is selected again. It is closed at full stream teardown;
inactive modes do not submit inference work.

Both DA-V2 and MiDaS receive one direct bilinear resize of the complete decoded frame into the
selected rectangle. There is no crop, square padding, or reflected border. Only the model-input
branch is downscaled. The matched-color and per-eye targets use the client-requested `W x H` output
contract. If a legacy host negotiates a lower decoded resolution, that input is upscaled to `W x
H`; the stats resolution remains the request/output target rather than a claim about the decoded
input.

Client SBS evaluation requires a mono host-application frame. `SBS_MODE_OFF` disables Apollo's
Host SBS AI packing, but cannot un-pack an SBS image already rendered by the application for Host
SBS Raw; Client SBS would process that packed image as one mono input.

The renderer writes packed Float32 model input into a shared OpenGL buffer. The normal preprocess
combines that pack with the color-cut luma reduction in one 16x16 compute pass. LiteRT converts that
public layout to its internal GPU layout, runs the model's validated OpenCL precision, and returns
Float32 depth in a shared OpenGL buffer. Both production families report
`LITERT_OPENCL_FP16_GL_IO`. Initialization is accepted only when the complete graph is delegated to
OpenCL; partial delegation or CPU execution makes the backend unavailable. GLES then performs
depth statistics, temporal processing, profile generation, prefiltering, and two-eye reprojection.
The GPU color-cut detector consumes the exact rectangular SDR model-input frame and publishes its
cut flag directly to the depth processor through an SSBO.

Both families use LiteRT's device-specific automatic OpenCL storage policy and
`AUTOMATIC_FP16` execution policy. The retired non-C4 DA-V2 Quality graphs were not FP16-safe on
this Galaxy XR firmware: an edge-rich input exposed the packed-softmax-tail defect and collapsed to
the final output-convolution bias (`0.088684082`). Tail-padding plus exact-GELU variants proved the
workaround, but production now uses only the three naturally C4-aligned canonical graphs. They need
no tail padding, use delegated builtin exact GELU, and pass the edge-rich FP16-vs-FP32 parity gate.
Execution policy is part of the compiler-cache namespace. Public GL tensors stay packed Float32. A debug-only direct
external probe forces FP16 buffer storage and uses half4 shaders, but Galaxy XR validation leaves
pixels unwritten after a fresh output refill and one invocation; automatic texture storage is also
incompatible with the runtime's GL-buffer interop contract. Direct external mode therefore remains
rejected rather than becoming a production fallback.
Release builds always use LiteRT's low GPU-priority hint (`priority = 1`) so sustained inference
yields to XR composition and UI work. Debug builds can run a controlled low/normal experiment with
the non-persistent `debug.artemis.sbs_gpu_priority` ADB property. The only accepted values are
`low` and `normal`; unset or invalid values select low, and high priority is deliberately rejected.
LiteRT exposes this as a hint and does not report the effective Adreno scheduler priority. Record
exact-output cadence as well as inference latency: a lower model-call wall time is not a win if
SurfaceFlinger responsiveness or retained-output cadence regresses.

The hint is read once before `LiteRtCreateCompiledModel()`, so change it only between streams and
recreate the Client SBS renderer after each change:

```powershell
& $Adb -s $env:ANDROID_SERIAL shell setprop debug.artemis.sbs_gpu_priority low
& $Adb -s $env:ANDROID_SERIAL shell getprop debug.artemis.sbs_gpu_priority

# Run the matched Low leg, disconnect/reconnect, then select Normal for the next engine.
& $Adb -s $env:ANDROID_SERIAL shell setprop debug.artemis.sbs_gpu_priority normal
& $Adb -s $env:ANDROID_SERIAL shell getprop debug.artemis.sbs_gpu_priority
```

The property resets on reboot. Explicitly set it back to `low` after an experiment. The Stats panel
row `LiteRT GPU priority hint` and each `ClientSbsPerf` line are authoritative for which hint the
new engine accepted; this is not a user-facing Client SBS parameter.

LiteRT exposes a CPU/runtime resize API through `LiteRtCompiledModelResizeInputTensor()`, but the
current Android OpenCL and OpenGL GPU delegates are static-only. The exact dynamic DA-V2 experiment
is therefore not a production option: the normal compiled-model path rejects its dynamic-sized
tensors, while forcing the full-delegation hint exposes an `INT64` `CAST`, `FILL`, and rank-5
attention `GATHER`; only 64 of 1,366 nodes delegate before creation fails. Resizing through the
classic interpreter before applying the delegate does not specialize the graph into a static one.

Each DA-V2 static export folds the shape graph and rewrites the 12 transformer attention blocks from
rank-5 `GATHER` indexing to constant Q/K/V slices plus rank-4 reshape/transpose operations. The
production transform replaces each private 24-operation expanded GELU DAG with one builtin exact
GELU. The canonical token counts are already C4-aligned, so no explicit attention-tail operators are
needed. Before FP16 weight-storage nodes, each core contains 683 builtin-v1 operations; the storage
transform adds 82 constant `DEQUANTIZE` operators without changing the maximum tensor rank or adding
dynamic, custom, or Flex tensors. Complete OpenCL delegation and CL/GL interoperability are
required for the full packaged graph.

Idle-device, thermal-status-0 validation after two warmups measured the following
`LiteRtRunCompiledModel()` wall times and full-output parity. These values include runtime/driver
blocking and are not end-to-end stream latency:

| DA-V2 bucket | FP16 mean | FP32 mean | FP16 vs transformed FP32 | Transformed FP32 vs original FP32 |
| --- | ---: | ---: | --- | --- |
| `350 x 196` retired Quality | 18.724 ms | 34.093 ms | NRMSE 0.008552, max abs 0.070056, cosine 0.999967727 | NRMSE 0.000788, max abs 0.003037, cosine 0.999999812 |
| `392 x 168` retired Quality | 18.439 ms | 32.867 ms | NRMSE 0.006959, max abs 0.057709, cosine 0.999975790 | NRMSE 0.001047, max abs 0.003876, cosine 0.999999799 |
| `490 x 140` retired Quality | 18.558 ms | 34.001 ms | NRMSE 0.006971, max abs 0.051251, cosine 0.999981069 | NRMSE 0.000520, max abs 0.002780, cosine 0.999999874 |
| `322 x 182` canonical | 16.416 ms | 28.177 ms | NRMSE 0.008812, max abs 0.045978, cosine 0.999968471 | NRMSE 0.001675, max abs 0.005831, cosine 0.999999754 |
| `350 x 154` canonical | 15.664 ms | 26.782 ms | NRMSE 0.003951, max abs 0.052774, cosine 0.999994676 | NRMSE 0.001440, max abs 0.003832, cosine 0.999999915 |
| `434 x 126` canonical | 15.830 ms | 26.920 ms | NRMSE 0.007030, max abs 0.057833, cosine 0.999994172 | NRMSE 0.000633, max abs 0.002037, cosine 0.999999946 |

Historically, the exact-GELU rewrite reduced the retired `350 x 196` corrected-FP16 mean from 22.20 ms to 18.72 ms
(about 15.7%). Its intrusive profile recorded 462 model kernels / 16.528 ms, plus 1.083 ms
upload/bind and 0.619 ms download, for 18.230 ms of delegate work. The original 959-op graph's
wrong-FP16 and safe-FP32 results remain historical bisect data; see
`docs/client-sbs-dav2-fp16-bisect.md`. The loose historical assets are not retained in the working
tree or APK; their hashes and pinned reproduction inputs remain documented. If they are regenerated,
stage them only under the ignored client `build/` tree or system temporary storage.

The naturally aligned canonical graphs reduce isolated FP16 call wall by 12.33%, 15.05%, and
14.70% against their retired `350 x 196`, `392 x 168`, and `490 x 140` Quality counterparts. The
`322 x 182` profile recorded 438 model kernels / 14.715 ms and 16.395 ms total delegate work.
`350 x 154` recorded 438 kernels / 14.073 ms, 0.946 ms upload/bind, 0.606 ms download, and 15.625
ms total delegate work; `434 x 126` recorded 438 kernels / 14.118 ms, 1.018 ms upload/bind, 0.345
ms download, and 15.481 ms total. These results, together with live testing, supported promoting
all three aligned graphs to the canonical production DA-V2 set.

The separate half-resolution-head experiment retained the `350 x 196` input but moved the exact
output to `175 x 98`. Its generated graph, SHA-256
`1a0df67bd9d2b6524ae51649f7c332420f64fa4f9a8ebdb812c51eec9b553b26`, still delegated 707/707
operations in one partition. It measured 18.050 ms FP16 and 33.194 ms FP32; FP16 versus FP32 was
NRMSE 0.008534, maximum absolute error 0.056952, and cosine 0.999967916. The profile recorded 462
model kernels / 16.154 ms and 17.516 ms total delegate work. That saves only about 0.67 ms over the
full-resolution retired Quality graph, and edge-aware GLES reconstruction would likely consume the saving,
so the half-resolution model and renderer path are not promoted. More aggressive decoder pruning
damaged depth quality and is also rejected. Generated experiment models remain under this client's
`build` or temporary storage and never in Apollo-3D.

Galaxy XR validation of the Float32-stored source also covered all three rectangular MiDaS cores at
138/138 `LITERT_CL` operations in one partition. Each FP16-weight packaged graph has 96 additional
constant `DEQUANTIZE` operators and must report complete acceleration and CL/GL interoperability as
a 234-operation graph. It must also return finite, non-flat, repeatable depth from both shared
buffer slots. The earlier Float32-stored source's LiteRT call-wall
samples with automatic internal storage were 16.84–21.06 ms at `352 x 192`, 15.68–16.44 ms at
`384 x 160`, and 15.31–16.27 ms at `448 x 128`. Invoke-to-output-ready samples were
17.17–21.55 ms, 15.94–16.81 ms, and 15.59–16.74 ms, respectively. These isolated smoke timings do
not include decode, OES preprocessing, GLES depth/profile processing, stereo composition, or the XR
compositor.

`tools/generate-midas-static-buckets.py` reproduces the three graphs from a verified square source
downloaded to temporary storage. The pinned URL, revision, digest, and commands are recorded under
`tools/model-sources/`, and generating package versions are pinned in
`tools/midas-static-buckets-requirements.txt`. The source graph is not stored in the repository and
is not packaged in either APK. The previously observed 10.79–18.37 ms result belongs only to the retired
`256 x 256` square graph and remains historical.

Legacy `depth-anything-v2-small-dynamic`, `depth-anything-v2-small-static-350`, and the retired
Quality-bucket preference migrate to the canonical three-bucket DA-V2 setting. Failure never
silently selects MiDaS: MiDaS remains an explicit model choice. If the selected backend cannot
initialize or execute, it becomes `Unavailable` and Client SBS duplicates mono.

Production does not package or select a managed Java LiteRT interpreter, QNN/HTP delegate, CPU
model path, PBO tensor-readback path, or Java result worker. Normal and Host SBS modes do not depend
on the Client SBS runtime. Generated ONNX files, conversion reports, and intermediate models stay
under `C:\tmp\dav2-dynamic-export`; only the validated production TFLite assets belong in the client
repository, and no client artifacts belong in the Apollo checkout.

The native engine has exactly two packed input/output tensor slots, matched one-to-one with two
full-resolution color slots. Renderer input-ready and previous output-consumed fences transfer to
native ownership on each invocation. The returned output-ready fence transfers to the renderer,
which must publish a new post-read consumer fence before that slot is reused. This is what preserves
the exact delayed color/depth pair without blocking the renderer or growing a result queue.
Native teardown retains transferred close fences and reports bounded-drain failures instead of
discarding the opaque engine handle. A failed close is quarantined and retried only from the next
dedicated inference worker; a new model is not initialized while an older context remains unsafe to
destroy.

The live order mirrors Apollo: adopt/postprocess completed pair N, capture and enqueue N+1 into the
other slot, then blur/warp/submit N. Inference and compose may overlap on the GPU, but the color and
depth identities do not. The direct rectangular depth shaders for both model families use one raw
tensor load per requested sample; no selected production model uses the legacy reflected-padding
mapping. The 1x-depth two-eye inverse-warp map shares one stream-fixed 19/14/12-step
depth/parallax solve across both eyes for the 16:9, 21:9, and 32:9 buckets respectively. Its
validated compose path emits the full-width packed SBS image in one draw; only the direct
compatibility path retains two half-width draws.

Inference is uncapped: there is no target FPS, thermal cadence reduction, or fixed idle interval
after LiteRT completes. Low GPU priority controls queue priority, not inference cadence. The atomic
single-flight claim still spans capture, the synchronous model run, result-mailbox ownership, and
renderer adoption, so at most one depth transaction exists and decoded callbacks coalesce to the
newest frame while it is occupied. Thermal status is telemetry, not a scheduling input.

Presentation also preserves that exact pairing without continuously redrawing it. The GL thread
coalesces and drains pending `SurfaceTexture` callbacks while inference is busy, but once a valid
matched output exists, a drain that adopts no newer result performs no SBS draw and no EGL swap.
Each newly adopted exact pair is rendered directly into the default framebuffer and swapped once;
SceneCore retains that submitted buffer until the next adoption. There is no packed SBS offscreen
cache or repeated cache blit. The active full-resolution color-slot lease therefore remains owned
until the next exact pair is adopted and supersedes it.

## 1. Focused JVM tests

The repository wrapper runs the focused Client SBS contract suite without touching an
emulator or headset:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass `
    -File .\tools\client-sbs-eval.ps1
```

Direct command including the family-archive extraction contract:

```powershell
.\gradlew.bat :app:testNonRoot_gameDebugUnitTest `
    --tests "com.limelight.sbs.ClientSbsFrameSlotsTest" `
    --tests "com.limelight.sbs.ClientSbsGpuDepthShadersTest" `
    --tests "com.limelight.sbs.ClientSbsGpuSceneCutDetectorTest" `
    --tests "com.limelight.sbs.ClientSbsGpuSceneCutShadersTest" `
    --tests "com.limelight.sbs.ClientSbsShotCutPolicyTest" `
    --tests "com.limelight.sbs.ClientSbsGpuTimerTest" `
    --tests "com.limelight.sbs.ClientSbsTemporalTuningTest" `
    --tests "com.limelight.binding.video.DecoderModeTransitionGateTest" `
    --tests "com.limelight.preferences.PreferenceConfigurationPerformanceLoggingTest" `
    --tests "com.limelight.utils.ClientSbsDepthInputShapeTest" `
    --tests "com.limelight.utils.ClientSbsGpuInferenceEngineTest" `
    --tests "com.limelight.utils.ClientSbsOutputSurfaceValidationTest" `
    --tests "com.limelight.utils.ClientSbsModelManifestTest" `
    --tests "com.limelight.utils.ClientSbsModelArchiveTest" `
    --tests "com.limelight.utils.ClientSbsPackagedModelArchiveTest" `
    --tests "com.limelight.utils.Stereo3DRendererSchedulingTest" `
    --tests "com.limelight.utils.ShaderUtilsTest" `
    --tests "com.limelight.ui.XrStreamPresenterLayoutTest" `
    --tests "com.limelight.ui.XrStreamPresenterTransitionTest" `
    --tests "com.limelight.ui.XrViewStateStoreTest" `
    --console=plain
```

The tests cover:

- `ClientSbsFrameSlotsTest`: matched color-frame ownership and legal slot transitions.
- `ClientSbsGpuDepthShadersTest`: packed Float32 depth reads and overflow-safe GPU histogram math.
- `ClientSbsGpuSceneCutDetectorTest`: accepted/discarded frame history and GPU transaction state.
- `ClientSbsGpuSceneCutShadersTest`: rectangular model-input luma/median-max-RGB reduction,
  numerical gain/offset/nonlinear-clipped-exposure rejection, reliable local ordinal structure,
  same-histogram structural cuts, and GPU-only evidence-word contracts.
- `ClientSbsShotCutPolicyTest`: numerical model-grid cut boundaries, startup blocking through the
  settle crossing, one-pulse sustained evidence, independent two-update geometry/appearance
  rearming, refractory relative-spike escape, and one-valid-update carry after an all-invalid
  depth result.
- `ClientSbsGpuTimerTest`: nonblocking per-stage query rings, unsigned results, and disjoint-sample
  rejection.
- `ClientSbsTemporalTuningTest`: Apollo-equivalent temporal response and spatial-scale mapping at
  different client and negotiated host cadences.
- `DecoderModeTransitionGateTest`: IDR/serial and presentation-timestamp gating around decoder
  output-surface transitions.
- `PreferenceConfigurationPerformanceLoggingTest`: performance logging remains independent of the
  visible Stats panel and defaults to the requested debug-on policy.
- `ClientSbsDepthInputShapeTest`: deterministic nearest-multiplicative-aspect selection directly
  within the canonical DA-V2 and MiDaS bucket sets.
- `ClientSbsGpuInferenceEngineTest`: compiler-cache identity, GPU priority parsing, and the
  process-wide single-model ownership slot.
- `ClientSbsOutputSurfaceValidationTest`: exact `2W x H` EGL realization and per-eye GL limit
  validation, including packed widths larger than the per-viewport limit.
- `ClientSbsModelManifestTest`: exact DA-V2 and MiDaS logical model identities, hashes, fixed tensor
  contracts, dimensions, buffer sizes, nearest-aspect routing, legacy-ID migration, and archive
  references.
- `ClientSbsModelArchiveTest`: flavor-neutral solid TAR/XZ entry extraction and missing-entry
  rejection.
- `ClientSbsPackagedModelArchiveTest`: every `nonRoot_game` production archive entry matches its
  manifest SHA-256.
- `Stereo3DRendererSchedulingTest`: stale-result overlap ownership, packed-viewport limits, and
  Stats epoch boundaries.
- `ShaderUtilsTest`: direct full-frame DA-V2 and MiDaS resize, per-stream fixed-shape packing,
  HDR-only inference tonemapping, depth prefiltering, and reprojection invariants.
- `XrStreamPresenterLayoutTest` and `XrStreamPresenterTransitionTest`: readable right-side panel
  placement and whether a presentation-mode change requires a decoder surface transition.
- `XrViewStateStoreTest`: per-machine/app isolation, fresh-session Normal defaults, and
  host-confirmed resume restoration.

The HTML report is under
`app\build\reports\tests\testNonRoot_gameDebugUnitTest\index.html`. These tests inspect source and
pure-Java contracts; they do not compile GLSL or prove device GL/OpenCL interoperability.

Run the complete supported-variant JVM suite before release or deployment:

```powershell
.\gradlew.bat :app:testNonRoot_gameDebugUnitTest --console=plain
```

PC tests cannot execute the Android Adreno OpenCL/OpenGL interop path. An x86_64 emulator is useful
for disposable lifecycle testing, but it is not evidence for Galaxy XR inference latency, fences,
HDR precision, or driver behavior. The physical-device smoke and live-stream steps remain required.

## 2. Assemble

Run the focused tests and assemble the arm64 Galaxy XR and x86_64 emulator splits:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass `
    -File .\tools\client-sbs-eval.ps1 -Assemble
```

Or assemble directly:

```powershell
.\gradlew.bat :app:assembleNonRoot_gameDebug --console=plain
Get-ChildItem .\app\build\outputs\apk\nonRoot_game\debug\*.apk
```

Assembly does not install, uninstall, launch, or clear any device package.

Use `assembleNonRoot_gameDebug` when only a build artifact is wanted. Use
`installNonRoot_gameDebug` for a data-preserving update deployment; plain `assembleNonRoot` is not
an install task and omits the required build type.

## 3. Galaxy XR native GPU smoke test

The physical-device smoke class creates real shared EGL contexts, uploads a deterministic packed
Float32 gradient, invokes the selected fixed-shape graph through native LiteRT, waits for the
returned GL fence, and rejects missing, non-finite, zero, or flat output. Its six graph/bucket tests
cover the two selectable model families independently: three canonical DA-V2 buckets (`322 x 182`,
`350 x 154`, and `434 x 126`) and three MiDaS buckets (`352 x 192`, `384 x 160`, and `448 x 128`)
through the same packed-GL native path.

### Protect the installed Artemis data

> **Never run `connectedNonRoot_gameDebugAndroidTest` against the user's Galaxy XR.** Android
> Gradle Plugin uninstalls the target package when that task finishes. That erases Artemis
> global defaults, current-session settings, certificates, and host pairings.

On the physical headset:

- Use only update-install (`:app:installNonRoot_gameDebug` or `adb install -r`) for
  `com.limelight.moonlight3ddebug`.
- Never run `adb uninstall com.limelight.moonlight3ddebug`,
  `pm clear com.limelight.moonlight3ddebug`,
  `uninstallAll`, or a Gradle uninstall task.
- If update-install reports a signature or downgrade conflict, stop. Do not solve it by
  uninstalling the target app.
- After instrumentation, uninstall only `com.limelight.moonlight3ddebug.test`.

The connected-test task is acceptable only on a disposable emulator whose data may be erased.

### Data-preserving manual procedure

```powershell
if (-not $env:ANDROID_SDK_ROOT) { throw "Set ANDROID_SDK_ROOT first" }
if (-not $env:ANDROID_SERIAL) { throw "Set ANDROID_SERIAL to the Galaxy XR adb serial" }

$Adb = Join-Path $env:ANDROID_SDK_ROOT "platform-tools\adb.exe"
$MainApk = "app\build\outputs\apk\nonRoot_game\debug\app-nonRoot_game-arm64-v8a-debug.apk"
$TestApk = "app\build\outputs\apk\androidTest\nonRoot_game\debug\app-nonRoot_game-debug-androidTest.apk"
$Class = "com.limelight.utils.ClientSbsGpuInferenceEngineInstrumentedTest"

& $Adb -s $env:ANDROID_SERIAL get-state
.\gradlew.bat `
    :app:assembleNonRoot_gameDebug `
    :app:assembleNonRoot_gameDebugAndroidTest `
    --console=plain

& $Adb -s $env:ANDROID_SERIAL install -r $MainApk
if ($LASTEXITCODE -ne 0) { throw "Main APK update-install failed; do not uninstall the app" }
& $Adb -s $env:ANDROID_SERIAL install -r $TestApk
if ($LASTEXITCODE -ne 0) { throw "Test APK update-install failed" }

$TestOutput = @(& $Adb -s $env:ANDROID_SERIAL shell am instrument -w -e class $Class `
    com.limelight.moonlight3ddebug.test/androidx.test.runner.AndroidJUnitRunner 2>&1)
$TestExit = $LASTEXITCODE
$TestOutput | ForEach-Object { Write-Host $_ }
$TestPassed = $TestExit -eq 0 -and [bool]($TestOutput -match '^OK \(')

& $Adb -s $env:ANDROID_SERIAL uninstall com.limelight.moonlight3ddebug.test
if (-not $TestPassed) { throw "Client SBS GPU smoke test failed" }
```

A pass requires all of the following:

- Native LiteRT initializes with OpenCL/OpenGL interoperability.
- Archive validation checks both family TAR/XZ assets, all six complete TAR entries, each extracted byte
  count, and all six final SHA-256 values. Only the selected fixed-shape graph may remain
  staged in `code_cache/client-sbs-model-assets`.
- Offline graph verification fixes every DA-V2 core at 683 operations, while the native smoke test
  programmatically requires complete acceleration. Confirm the accompanying LiteRT log says
  `Replacing 683 out of 683 node(s)`, followed by the one-partition message
  `yielding 1 partitions`; partial delegation or more than one partition is a failure.
- Each of the three MiDaS buckets reports complete acceleration and exactly one OpenCL partition;
  record its actual delegated/total operator count rather than assuming the DA-V2 count.
- `LiteRtCompiledModelIsFullyAccelerated()` succeeds, and the log confirms CL/GL buffer
  interoperability for all six buckets.
- Each bucket reports its expected logical name, extracted SHA-256, and fixed input/output
  layouts. No runtime tensor resize occurs.
- Exactly two native input/output tensor slots are present.
- Public tensor strides are 12-byte RGB input and 4-byte depth output.
- The deterministic output for every bucket is finite, positive, and non-flat. The `322 x 182`
  DA-V2 test additionally checks range, checksum, and sampled pixels against an edge-rich desktop
  CPU golden; a smooth gradient alone is not an adequate FP16 correctness test.
- Extraction/verification, initialization, and warm invocation latency are recorded separately
  for all six buckets.
- Both slots exchange mandatory input-ready, output-ready, and final output-consumed fences without
  aliasing, reuse-before-consume, or teardown errors.

Every canonical DA-V2 bucket delegates its 683-operation core in one `LITERT_CL` partition and uses
`AUTOMATIC_FP16`. The edge-rich gate returns finite, non-flat depth and the full-tensor parity values
in the production-contract table above. With FP16-stored weights, 20 discarded warm-ups, 100
measured invocations, normal priority, and thermal status 0, isolated mean LiteRT wall time is
16.552 ms at `322 x 182`, 15.725 ms at `350 x 154`, and 15.864 ms at `434 x 126`. Every change from
the prior Float32-stored/FP16-compute means is below 1%, so weight storage is a size optimization
rather than a compute optimization. The corresponding transformed-FP32 means are 28.177 ms,
26.782 ms, and 26.920 ms.

The retired corrected Quality graphs measured 18.628 ms at `350 x 196`, 18.367 ms at `392 x 168`,
and 18.581 ms at `490 x 140`; the original non-C4 959-operation graph's smooth-input FP16 pass,
flat edge-rich result, 46–50 ms safe-FP32 runs, and scheduling/fence tails remain historical bisect
evidence. None is a production acceptance baseline or an entry in `client-sbs-dav2-models.tar.xz`.

Every FP16-stored MiDaS bucket reported complete delegation as a 234-operation graph, CL/GL
interoperability, and finite, non-flat, repeatable output. The same controlled 20-warm-up,
100-sample run measured:

| Shape | LiteRT median / p95 | Output-ready median / p95 | Isolated inference rate |
| --- | ---: | ---: | ---: |
| `352 x 192` | 10.293 / 10.473 ms | 10.909 / 11.164 ms | 91.20/s |
| `384 x 160` | 9.703 / 9.872 ms | 10.332 / 10.557 ms | 96.23/s |
| `448 x 128` | 9.430 / 9.593 ms | 10.051 / 10.279 ms | 99.05/s |

For an apples-to-apples 16:9 ranking, use the controlled LiteRT call-wall medians, not sequential
live-stream windows:

| Model | Shape | Controlled LiteRT wall median |
| --- | ---: | ---: |
| DA-V2 | `322 x 182` | 16.532 ms |
| MiDaS | `352 x 192` | 10.293 ms |

Both used complete OpenCL delegation, automatic FP16, normal priority, thermal status 0, 20
discarded warm-ups, and 100 measured invocations. `LiteRtRunCompiledModel()` wall is still a
CPU-observed runtime/driver call, not pure GPU kernel time. Live streaming adds contention from
decode, GLES, XR composition, clocks, and thermal state, so sequential live windows cannot rank the
models unless those conditions are controlled. The retired `350 x 196` Quality graph measured
18.615 ms under the same conditions and remains historical comparison data only.

The old `256 x 256` MiDaS measurement of 10.79–18.37 ms is retained only as historical data; do
not mix it into the rectangular-bucket range.

### Sustained production-model benchmark

The smoke timings above use only four invocations per fixed-shape graph and predate the controlled
wake-lock benchmark. Do not use them for current throughput decisions. The dedicated benchmark
class keeps the normal six-graph/two-family smoke suite unchanged, holds a bounded partial wake lock
to prevent off-head
system suspend, discards 20 warm-ups, and records 100 fence-complete invocations per result.

Run one production bucket at a time so the logged model, shape, delegation, thermals, and latency
remain attributable:

```powershell
$Benchmark = "com.limelight.utils.ClientSbsMidasGpuBenchmarkInstrumentedTest"
& $Adb -s $env:ANDROID_SERIAL shell am instrument -w `
    -e class "$Benchmark#productionMidas352x192" `
    com.limelight.moonlight3ddebug.test/androidx.test.runner.AndroidJUnitRunner

& $Adb -s $env:ANDROID_SERIAL logcat -d -s ClientSbsGpuBench:I ClientSbsGpu:I tflite:I '*:S'
```

Historical square A/B binaries are no longer stored in the repository or instrumentation APK.
Their pinned URLs, hashes, and preparation commands remain in `tools/model-sources/README.md` for
reproduction from temporary storage. Production benchmark models come from the target APK;
instrumentation uses dedicated `client-sbs-benchmark-*` code-cache namespaces and never prunes the
production model or compiler caches.

Galaxy XR controlled results at `256 x 256`, with 138/138 Qualcomm nodes and 234/234 Community
nodes each delegated as one `LITERT_CL` partition, were effectively identical:

- Qualcomm Float32-weight artifact: 9.898 ms median / 10.092 ms p95 LiteRT call wall; 10.546 ms
  median / 10.771 ms p95 invoke-to-output-ready; 94.28 isolated inferences/s.
- LiteRT Community FP16-weight artifact: 9.930 ms median / 10.083 ms p95 LiteRT call wall; 10.584
  ms median / 10.838 ms p95 invoke-to-output-ready; 93.95 isolated inferences/s.

The FP16 file is 33,507,904 bytes versus 66,306,460 bytes for Qualcomm, but it is not faster.
Artemis already asks LiteRT for OpenCL FP16 computation; the current model's Float32 stored weights
do not imply Float32 GPU arithmetic. The Community graph also requires external ImageNet
normalization, which this isolated graph benchmark performs before the timed window. Keep Qualcomm
for production performance unless a later quality/size study justifies the external graph.

The retired Float32-stored production `352 x 192` MiDaS graph measured 10.247 ms median / 10.476 ms
p95 LiteRT call wall and 10.875 ms median / 11.110 ms p95 output-ready, or 91.76 isolated
inferences/s. FP16-stored weights now measure 10.293 / 10.473 ms and 10.909 / 11.164 ms,
respectively, or 91.20/s. The sub-0.5% differences are noise; the benefit is the 49.8% model-byte
reduction, not faster FP16 compute.

Experimental LiteRT I/O probes are debug/instrumentation-only and fail closed. The OpenCL async
probe reported `backend_async=no`, with the entire invocation spent in submit and no event-backed
wait; it cannot overlap two model invocations. The corrected direct-external probe forced BUFFER
storage, received complete FP16 half4 requirements (`540672` bytes, types `[11,6]`, strides
`[16,0]`), and fully delegated the graph. Its provisional median was 9.426 ms versus 10.235 ms for
packed input/output, but two fresh-refill repetitions left 80 and 59 of 67,584 output pixels at the
sentinel value. Because completeness, parity, and repeatability did not pass, the apparent 0.8 ms
gain is invalid and production remains automatic-storage packed NHWC.

`isolatedInferenceFps` is not final stream FPS. It excludes decoder load, model-input packing,
external normalization cost, full-resolution postprocess/reprojection, XR composition, and
presentation. Off-head runs without the wake lock showed 430–466 ms suspend tails and must not be
treated as model latency. The large gap between roughly 92–94 isolated MiDaS inferences/s and the
57–79 ms live call wall points to shared-GPU queue contention as the next diagnostic target.

Device thermal status was 0 during the run. The isolated 478.21 ms `350 x 196` tail is therefore
recorded as an early lazy-initialization, scheduling, or preemption candidate that still requires
sustained live-stream observation; it is not a delegation failure. A future bucket failure is not
permission to use CPU execution or silently select another model family.

Use the instrumentation log for diagnostics:

```powershell
& $Adb -s $env:ANDROID_SERIAL logcat -d -s `
    ClientSbsGpu:I ClientSbsGpuSmoke:I LiteRT:I tflite:I '*:S'
```

The x86_64 APK keeps emulator development possible, but an emulator is not proof of the Galaxy XR
Adreno driver, CL/GL interop, latency, thermals, or sustained frame cadence.

## 4. Live-stream validation

Update-install the normal debug APK without clearing data:

```powershell
.\gradlew.bat :app:installNonRoot_gameDebug --console=plain
```

Wake the headset, connect to the existing host, start a moving repeatable scene, and select
**Client SBS AI**. A static scene intentionally produces little visual evidence and can make depth
reuse look like a stalled pipeline.

Open the in-headset Stats panel and verify:

- `Depth backend` is `LITERT_OPENCL_FP16_GL_IO` for both DA-V2 and MiDaS, never a managed, CPU, or
  QNN backend.
- `Depth inference policy` reports `Uncapped | single flight | newest frame when free`; there is no
  FPS ceiling or inference-gap row. `Android thermal status` is informational only.
- The selected logical model and fixed input dimensions match the source aspect. DA-V2 reports
  `322 x 182`, `350 x 154`, and `434 x 126` for canonical 16:9, 21:9, and 32:9 streams; MiDaS
  reports `352 x 192`, `384 x 160`, and `448 x 128`. Other aspects use the documented stream-fixed
  nearest-bucket routing for the selected family.
- A/B DA-V2 and MiDaS on the same moving content at each canonical aspect. Record exact-output
  cadence, thermals, depth detail/pop, and visible geometry; DA-V2's `350 x 154` and `434 x 126`
  direct-resize paths carry -2.60% and -3.125% aspect distortion.
- `Latch / depth / output FPS` shows the decoder-consumer cadence, adopted depth cadence, and final
  SBS submission cadence. Depth and output should track one another; latch may be higher while
  single-flight inference is occupied.
- `LiteRT wall avg / max` remains bounded for the selected model, and `Depth age avg / max` remains
  bounded for the exact matched color/depth pair.
- The four true GL GPU averages populate without stalling: model-input pack, matched-color copy,
  depth profile, and SBS compose.
- Exceptional fault counters `color_busy` and `flat` remain zero during ordinary content. Expected
  latest-frame replacement, callback coalescing, and ordinary single-flight busy events are not
  exposed as counters.
- Minimal depth health remains plausible: valid fraction is high, effective range width is
  non-collapsed, and pop strength responds to real scene depth.
- For HDR input, `Client SBS HDR path` reports either preserved `RGB10_A2`/`RGBA16F` output or the
  explicit BT.709/sRGB tonemap. SDR reports BT.709/SDR. Switching back to a direct mode clears
  Client SBS metadata.
- `Stereo compose path` reports the preferred
  `RG16F 1x-depth warp map, packed single draw (N-probe)`. The startup log should
  include the warp-map dimensions plus `warp-map render validated` and
  `warp-map compose validated`.
  `Direct GLES N-probe` is still GPU reprojection, but its timings must be analyzed separately
  because it repeats the inverse solve per output pixel. `N` is fixed when the stream starts from
  the selected 16:9, 21:9, or 32:9 aspect bucket (19/14/12 probes for either model family).
- App CPU core-equivalent load, device GPU busy/clock, and Android thermal status remain plausible.
  The pane intentionally has no NPU row or custom CPU/GPU temperature probes; Android 14 exposes no
  trustworthy public per-app NPU-utilization API. The production backend is GPU.

There is deliberately no PBO readback, free CPU tensor-buffer wait, managed result queue, or Java
postprocess worker in this path. A stats row for one of those stages is stale and should be removed,
not interpreted as a zero-latency operation.

### Read the latency domains correctly

The lean panel intentionally omits CPU command-submission, worker/JNI invoke, and native dependency
submission timings. The retained latency fields are:

- `LiteRT wall avg / max`, which brackets `LiteRtRunCompiledModel()`. It includes runtime overhead
  and any blocking visible to that API; LiteRT does not expose its OpenCL event, so this is not a
  pure accelerator duration.
- `Depth age avg / max`, which measures freshness of the exact matched color/depth pair rather than
  one isolated GPU stage.
- Four GL GPU averages from nonblocking `GL_EXT_disjoint_timer_query`: model render + color cut +
  pack, matched-color copy, depth normalization/profile, and depth blur + packed single-draw SBS
  warp. These measure actual GLES completion but cannot include LiteRT's OpenCL execution.

Query availability is polled without waiting and clock-disjoint samples are discarded. The panel
does not expose GPU maxima or sample counts. Record the active warp path with each capture; RG16F
warp-map and full-resolution compatibility runs are not directly comparable. SceneCore exposes no
final compositor-present timestamp.

Capture a clean log window around one stream:

```powershell
$Package = "com.limelight.moonlight3ddebug"
& $Adb -s $env:ANDROID_SERIAL logcat -c
& $Adb -s $env:ANDROID_SERIAL shell am force-stop $Package
& $Adb -s $env:ANDROID_SERIAL shell am start -n "$Package/com.limelight.PcView"
# Connect and exercise Client SBS, then:
& $Adb -s $env:ANDROID_SERIAL logcat -d -v threadtime > `
    (Join-Path $env:TEMP "artemis-client-sbs-logcat.txt")
```

Review for `ClientSbsGpu`, `Stereo3DRenderer`, delegate coverage, GL errors, fence failures,
mailbox/slot failures, and uncaught exceptions. Initialization is expected once per new renderer;
repeated initialization during a stable stream indicates a surface or lifecycle problem.
Performance logging is enabled by default and can be disabled in preferences. While enabled, grep
`DecoderPerf` in Normal/Host SBS or `ClientSbsPerf` in Client SBS for one typed line per
approximately two-second window. Both expose
the complete sender-sequence / receive / decoder-output / render-release / surface-presented chain.
The Client SBS line also identifies the model/backend/input and reports latch/depth/output FPS,
LiteRT wall average/maximum, depth-age average/maximum, the four GLES completion averages,
exceptional `color_busy`/`flat` counts, minimal depth health, app CPU core-equivalent load, GPU
busy/clock, and Android thermal status. Neither line adds per-frame logging or GPU synchronization.
When Stats is closed and explicit performance logging is disabled, timer queries, depth-health PBO
copies/polling, detailed Client-SBS counter updates, and diagnostic formatting are disabled.
Reopening Stats starts fresh CPU, Client-SBS, health, and GL timer windows; do not treat hidden time
as part of the first sample.

## Sustained comparison

For optimization decisions, use the same moving host clip, resolution, frame rate, codec, HDR
mode, and headset power state for Normal, DA-V2 Client SBS, and MiDaS Client SBS runs. Model changes
take effect on the next stream so that one concrete shape and one compiled model remain stable for
the entire capture. Collect at least 15 minutes after warm-up and record:

- Stats-panel screenshots at the start, middle, and end.
- Latch/depth/output FPS, LiteRT call-wall average/maximum, and depth-age average/maximum.
- Each of the four GL GPU completion averages, kept separate from LiteRT call-wall latency.
- Exceptional `color_busy` and `flat` counts; do not collect expected skip/coalescing behavior as
  counter noise.
- Valid-depth fraction, effective range width, pop strength, and collapsed-range state.
- App CPU core-equivalent load, GPU busy/clock, and Android thermal status.
- HDR path and selected presentation target format for the run.
- Visible depth quality, judder, black/flat frames, thermal warnings, and discomfort.
- `adb shell dumpsys meminfo com.limelight.moonlight3ddebug` and
  `adb shell dumpsys thermalservice` at
  consistent points.

Optimize the stage with sustained latency or an exceptional fault, not an isolated maximum. Current
GL input/output boundaries are GPU-resident. A high LiteRT call-wall number points at model/runtime
work but is not itself pure GPU timing; rising depth age with healthy LiteRT timing indicates
synchronization or renderer scheduling. Use the dedicated GLES completion averages when optimizing
model-input rendering, color copy, depth/profile, or direct SBS warp. The retained-buffer change is
expected to reduce redundant full-frame GPU work, bandwidth, and thermal pressure; it does not by
itself increase the unique exact-pair cadence above the inference/adoption rate.

## Failure classification

- **Backend `Unavailable` at startup:** inspect native library loading, model contract, full GPU
  delegation, and CL/GL interop logs.
- **Wrong static bucket selected:** verify the selected model family, decoded source aspect supplied
  when the renderer was created, and nearest-multiplicative-aspect selector. The model must not
  change midstream.
- **A DA-V2 bucket delegates fewer than 683/683 core operations, a MiDaS bucket is not completely
  accelerated, or either family creates more than one partition:** reject it and verify the archive
  manifest mapping, extracted SHA-256, and rank-4 static graph. CPU fallback is not a valid result.
- **An isolated early inference tail appears despite complete delegation:** retain the sample,
  correlate it with initialization, scheduling, and thermal state, then judge sustained live-stream
  cadence. Do not misclassify a one-off tail as partial delegation when the model's complete
  delegation and one-partition contract are confirmed.
- **Finite but flat model output:** reproduce with the deterministic native smoke test before
  changing fixed reprojection/profile math.
- **Valid fraction drops or range collapses:** inspect the minimal health fields and model output
  before changing the fixed profile. NaN/Inf and finite negative pixels are rejected and retain
  prior temporal depth.
- **Non-flat depth but flat stereo:** inspect GPU depth/profile readiness, subject-plane profile,
  and reprojection shader inputs.
- **Depth smears across hard edits:** inspect the GPU scene-cut path. The cut word must stay
  GPU-resident and paired with the same tensor/color slot.
- **Good depth with lag:** compare LiteRT wall, depth age, and latch/depth/output FPS; check the
  exceptional `color_busy` counter before changing model resolution.
- **GPU remains high while output FPS is low:** compare the four GLES completion averages with
  LiteRT wall and GPU busy/clock. A packed output blit or a swap on every latched decoder frame means
  the removed cache-repeat path has returned.
- **One GLES region is slow:** optimize its corresponding GL GPU average. Do not treat LiteRT call
  wall as a pure GPU timer.
- **Stereo compose path is `Direct GLES N-probe`:** inspect the RG16F target/shader validation
  log. This is a GLES render compatibility path, not evidence that LiteRT switched to CPU or a
  legacy model.
- **HDR is washed out or clipped:** verify the actual window bits, selected color target, and stats
  HDR path. BT.2020/ST2084 must be advertised only for verified end-to-end high precision.
- **Normal/Host modes regress:** treat it as a surface-routing or lifecycle bug. Those paths must
  not depend on Client SBS runtime availability.
