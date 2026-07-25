# Client SBS on the Hexagon NPU — status and setup

Depth inference is the client's dominant cost and its thermal limiter. A live Client SBS session on
SXR2230P measured LiteRT at 15–17 ms with the GPU clock throttling 750 → 599 MHz at `thermal=4`,
while the same MiDaS graph benchmarks at **9.4 ms on a cool device** — so throttling is costing
roughly 60–80% of inference throughput. The NPU is attractive not because it is faster in absolute
terms (the GPU is already quick) but because it is several times more power-efficient, which keeps
the device out of the state that costs those 60–80%.

`ClientSbsAcceleratorBenchmarkInstrumentedTest` measures CPU vs GPU vs NPU on equal terms. It is
deliberately GL-free — every accelerator uses host-memory buffers allocated from the compiled
model's own requirements — so **the GPU number here is not the production GPU number**: the shipping
path additionally gets zero-copy GL interop that this harness omits. Compare NPU against the GPU
figure from this harness, never against `litert_ms` in a live session.

## Measured baseline (2026-07-25, SXR2230P, `thermal 0 → 0`)

| model | CPU | GPU | NPU |
|---|---|---|---|
| `midas-v2-static-352x192` | 343 ms | **9.4 ms** (compile 1.45 s) | blocked, see below |
| `depth-anything-v2-small-static-322x182` | 627 ms | **15.5 ms** (compile 1.86 s) | blocked, see below |

Two things worth carrying forward regardless of the NPU: DA-V2 is **65% slower than MiDaS**, and
both compile in 1.4–1.9 s, so a cold start without a warm compiler cache is user-visible.

## What is already in place

* `kLiteRtHwAcceleratorNpu` exists in the vendored LiteRT (2.1.6), and the release the plugins come
  from is the same 2.1.6 — no version skew.
* The device ships the QNN backends in `/vendor/lib64` (`libQnnHtp.so`, `libQnnSystem.so`,
  `libSnpeHtpV73Stub.so`). **These cannot be used**: an app may not `dlopen` arbitrary vendor
  libraries, which is exactly why the QAIRT copies below must be bundled into the APK.
* `libLiteRtCompilerPlugin_Qualcomm.so` and `libLiteRtDispatch_Qualcomm.so` (v73, matching this
  SoC's Hexagon generation) are unpacked into `app/src/main/jniLibs/arm64-v8a/`. Note `**/jniLibs`
  is gitignored, so they are NOT in the repo and must be re-fetched on a clean checkout.
* The benchmark passes `kLiteRtEnvOptionTagDispatchLibraryDir`. This is **distinct** from
  `RuntimeLibraryDir` and is easy to miss: without it LiteRT logs *"You should provide the
  `DispatchLibraryDir` option to use NPU"* and fails with `kLiteRtStatusErrorCompilation` (504),
  which is indistinguishable from an unsupported graph. Anything integrating NPU must set it.

## Blocked at the DSP protection domain

Every software layer now loads. The bring-up took four rounds, each of which moved the failure
one step further, and the final stop is a device permission rather than anything in this code:

| # | symptom | cause | fix |
|---|---|---|---|
| 1 | `kLiteRtStatusErrorCompilation` (504) | no Qualcomm plugin in the APK | unpack `libLiteRt*_Qualcomm.so` into `jniLibs` |
| 2 | 504, *"You should provide the `DispatchLibraryDir` option"* | `DispatchLibraryDir` is distinct from `RuntimeLibraryDir` | set `kLiteRtEnvOptionTagDispatchLibraryDir` |
| 3 | `dlopen libQnnSystem.so / libQnnHtpV69Stub.so failed` | QAIRT backends not bundled | copy them from the QAIRT SDK |
| 4 | `dlopen libcdsprpc.so` not found | vendor library not in the app's linker namespace | **`<uses-native-library>`** in the manifest |
| 5 | `createUnsignedPD ... not supported by HTP`, error 1002 | device does not permit unsigned PD | **no app-side fix** |

Step 4 is worth dwelling on, because it looked like a hard platform wall and was not. A vendor
library being present in `/vendor/lib64` and listed in `/vendor/etc/public.libraries.txt` is
necessary but NOT sufficient: since Android 12 an app must also DECLARE it. Without the
declaration both implicit `NEEDED` resolution and an explicit `dlopen(..., RTLD_GLOBAL)` fail with
"library not found", which is indistinguishable from the library being withheld.

### Why step 5 stops here

```
QnnDsp <E> createUnsignedPD unsigned PD or DSPRPC_GET_DSP_INFO not supported by HTP
QnnDsp <E> DspTransport.createUnsignedPD failed, 0x00000003
```

Two independent pieces of device evidence:

* `/dev/fastrpc-cdsp` does not exist — the compute-DSP FastRPC node is not exposed on this build.
* `/dev/adsprpc-smd` is `crw-rw-r--  system:system`, so a normal app uid gets read-only access
  while FastRPC needs read/write. This matches Qualcomm forum reports that `qnn-net-run` works
  under `adb root` but not from an app.

Qualcomm's documented workaround is to request a **signed** process domain
(`QNN_HTP_DEVICE_CONFIG_OPTION_SIGNEDPD`, `useSignedProcessDomain = true`). That is a raw QNN
device-config option, and **LiteRT 2.1.6 does not expose it** — its Qualcomm options surface has 24
setters covering log level, HTP preference, quantization behaviour, weight sharing, JIT, ConvHMX,
FoldReLU, graph IO memory type and performance modes, with nothing for process domains. Even if the
device would accept a signed PD, there is no way to ask for one through this runtime.

### What would change the answer

* **LiteRT exposing the signed-PD option.** This is the most likely unlock and is purely upstream;
  worth an issue against google-ai-edge/LiteRT.
* **A device/OS update** enabling unsigned PD or exposing `/dev/fastrpc-cdsp` to apps.
* Anything shipping as a **privileged or vendor app**, which is not applicable here.

Do not conclude the models are unsuitable: HTP never got far enough to see a graph. Everything up
to the transport succeeded, so a future unlock only has to clear step 5.

### Version note discovered along the way

This SoC is **Hexagon v69**, not v73. `libQnnHtp.so` detects the SoC itself and asks for
`libQnnHtpV69Stub.so` by name — that runtime request is authoritative. The earlier v73 guess came
from `libSnpeHtpV73Stub.so` in `/vendor/lib64`, which is SNPE's *supported set*, not the SoC's
architecture; the device also ships `libSnpeHtpV69Stub.so`, and its QNN stack ships only the v69
stub.

The LiteRT plugins do **not** need to match: `libLiteRtCompilerPlugin_Qualcomm.so` and
`libLiteRtDispatch_Qualcomm.so` are byte-identical across the `qualcomm_runtime_v69` .. `_v81`
directories in the release zip. Only the QAIRT stub/skel pair is version-specific.

### Steps

1. Download **QAIRT 2.47.0.260601** from
   <https://softwarecenter.qualcomm.com/catalog/item/Qualcomm_AI_Runtime_Community>. An account and
   EULA acceptance are required — the direct URL in LiteRT's `fetch_qualcomm_library.sh` returns
   HTTP 403 unauthenticated, so this step cannot be scripted unattended.

   Take the version the plugins were built against, not the newest: `fetch_qualcomm_library.sh`
   pins `2.47.0.260601` for LiteRT 2.1.6, inside a `LINT.IfChange` block tying that same version to
   the Bazel workspace, the Python wheel and the CMake headers dir. Establish the baseline on the
   pinned pairing first — with a version-skewed pairing you cannot tell "HTP rejected the graph"
   from "QNN API mismatch", and this path has already produced two rounds of ambiguous 504s.

   Upgrading afterwards is nearly free: `libLiteRtDispatch_Qualcomm.so` declares no `NEEDED` entry
   for any `libQnn*.so` and resolves QNN through `dlopen`/`dlsym` at runtime, so swapping QAIRT
   versions is just replacing files in `jniLibs` and re-running the benchmark — no rebuild, no code
   change. Try a newer SDK (e.g. 2.48.40.260702) as a deliberate second step once a known-good
   number exists to compare against. Upstream's `QAIRT_SDK.md` describes the same three
   categories this table lists — `libQnnSystem.so`, the `libQnn{Backend}*.so` set, and the Hexagon
   skeleton under `lib/hexagon-v{arch}/unsigned/`.
2. Copy into `app/src/main/jniLibs/arm64-v8a/`, from `qairt/2.47.0.260601/`:

   | file | source path |
   |---|---|
   | `libQnnHtp.so` | `lib/aarch64-android/` |
   | `libQnnSystem.so` | `lib/aarch64-android/` |
   | `libQnnHtpV73Stub.so` | `lib/aarch64-android/` |
   | `libQnnHtpV73Skel.so` | `lib/hexagon-v73/unsigned/` |
   | `libQnnHtpPrepare.so` | `lib/aarch64-android/` — JIT only |
   | `libQnnIr.so` | `lib/aarch64-android/` — JIT only |
   | `libQnnSaver.so` | `lib/aarch64-android/` — JIT only |

   Upstream tells you to look the Hexagon arch up in Qualcomm's "Supported Snapdragon devices"
   table. No lookup is needed here: this device answers for itself, shipping
   `libSnpeHtpV73Stub.so` in `/vendor/lib64`, so **v73** is correct for SXR2230P.

   The last three are only needed for on-device (JIT) compilation, which is the path here; AOT
   instead pre-compiles models into a Google Play AI Pack.
3. Re-run and read the `AcceleratorBench` lines:

   ```
   ./gradlew :app:connectedNonRoot_gameDebugAndroidTest \
     -Pandroid.testInstrumentationRunnerArguments.class=com.limelight.utils.ClientSbsAcceleratorBenchmarkInstrumentedTest
   adb logcat -d | grep AcceleratorBench
   ```

To re-fetch the LiteRT half after a clean checkout: take
`litert_npu_runtime_libraries_jit.zip` from the
[LiteRT v2.1.6 release](https://github.com/google-ai-edge/LiteRT/releases) and unpack
`qualcomm_runtime_v73/src/main/jni/arm64-v8a/*.so` into `app/src/main/jniLibs/arm64-v8a/`.

## If it works, what integration would still cost

A working benchmark does **not** mean a drop-in replacement. The production engine
(`client_sbs_gpu.c`) is GPU-resident by design: its tensor buffers come from
`LiteRtCreateTensorBufferFromGlBuffer`, which the NPU cannot consume. An NPU path needs the depth
result copied back into a GL texture each frame — ~270 KB at 352×192, small in bytes but a real
sync point in a pipeline built to avoid them. Budget for that copy eating part of the win, and
measure end-to-end frame cost rather than inference time alone.

Per the LiteRT docs, models do **not** need quantizing, which removes the quality risk that would
otherwise dominate this decision — worth noting given the FP16/OpenCL `BATCH_MATMUL` defect
documented in `client-sbs-dav2-fp16-bisect.md`.

Expect MiDaS to map onto HTP more cleanly than DA-V2: EfficientNet-Lite is a far better fit for the
tensor accelerator than a 12-block ViT. A partial delegation or outright rejection of DA-V2 would
not be surprising, and is itself a useful result.
