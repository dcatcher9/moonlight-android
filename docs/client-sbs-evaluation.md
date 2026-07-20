# Client SBS evaluation

This is the reproducible evaluation entry point for Artemis Client SBS. Production has one depth
backend: the native LiteRT 2.x GPU path. The guide separates four questions:

1. Do the model, shader, and frame-ownership contracts pass on the JVM?
2. Does the supported Android XR APK assemble?
3. Does the real model execute through shared GL buffers on Galaxy XR and produce non-flat depth?
4. Does the complete decode-to-stereo path remain responsive during a sustained stream?

Run commands from the Artemis checkout root. Keep generated APKs, reports, and captures in this
repository's build directories, the device app directory, or a temporary directory. Do not write
client artifacts into the Apollo checkout.

## Production contract

Client SBS uses Qualcomm's official float MiDaS V2 export:

- Asset: `app/src/main/assets/midas-midas-v2-float.tflite`
- SHA-256: `3990551be4f21be7bffc71c159bb643279af221c6e8b328ce265374776ff2ec1`
- Input: `image[1,256,256,3]`, packed NHWC Float32 RGB, 12 bytes per pixel
- Output: `depth_estimates[1,256,256,1]`, packed NHWC Float32, 4 bytes per pixel

The renderer writes model input into a shared OpenGL buffer. LiteRT converts the public packed
layout to its internal PHWC4 layout on the GPU, runs the fully delegated graph with OpenCL FP16
compute, and returns depth in a shared OpenGL buffer. GLES then performs depth statistics, temporal
processing, profile generation, prefiltering, and two-eye reprojection.

Production does not package or select a managed Java LiteRT interpreter, QNN/HTP delegate, CPU
model path, PBO readback path, or Java result worker. Failure is fail-closed for synthesized depth:
the backend becomes `Unavailable` and presentation remains usable as flat output. Normal and Host
SBS modes do not depend on the Client SBS runtime.

## 1. Focused JVM tests

The wrapper runs the four focused Client SBS test classes without touching a device:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass `
    -File .\tools\client-sbs-eval.ps1
```

Equivalent direct command:

```powershell
.\gradlew.bat :app:testNonRoot_gameDebugUnitTest `
    --tests "com.limelight.sbs.ClientSbsFrameSlotsTest" `
    --tests "com.limelight.sbs.ClientSbsGpuDepthShadersTest" `
    --tests "com.limelight.utils.ClientSbsModelManifestTest" `
    --tests "com.limelight.utils.ShaderUtilsTest" `
    --console=plain
```

The tests cover:

- `ClientSbsFrameSlotsTest`: matched color-frame ownership and legal slot transitions.
- `ClientSbsGpuDepthShadersTest`: packed Float32 depth reads and overflow-safe GPU histogram math.
- `ClientSbsModelManifestTest`: the exact float model identity, dimensions, and buffer sizes.
- `ShaderUtilsTest`: model-input packing, HDR-only inference tonemapping, depth prefiltering, and
  reprojection invariants.

The HTML report is under
`app\build\reports\tests\testNonRoot_gameDebugUnitTest\index.html`. These tests inspect source and
pure-Java contracts; they do not compile GLSL or prove device GL/OpenCL interoperability.

To run every JVM test for the supported debug variant:

```powershell
.\gradlew.bat :app:testNonRoot_gameDebugUnitTest --console=plain
```

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

## 3. Galaxy XR native GPU smoke test

The physical-device smoke class creates real shared EGL contexts, uploads a deterministic packed
Float32 gradient, invokes the bundled model through native LiteRT, waits for the returned GL fence,
and rejects missing, non-finite, zero, or flat output.

### Protect the installed Artemis data

> **Never run `connectedNonRoot_gameDebugAndroidTest` against the user's Galaxy XR.** Android
> Gradle Plugin uninstalls the target package when that task finishes. That erases Artemis
> preferences, certificates, host pairings, and profiles.

On the physical headset:

- Use only update-install (`:app:installNonRoot_gameDebug` or `adb install -r`) for
  `com.limelight.noirdebug`.
- Never run `adb uninstall com.limelight.noirdebug`, `pm clear com.limelight.noirdebug`,
  `uninstallAll`, or a Gradle uninstall task.
- If update-install reports a signature or downgrade conflict, stop. Do not solve it by
  uninstalling the target app.
- After instrumentation, uninstall only `com.limelight.noirdebug.test`.

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
    com.limelight.noirdebug.test/androidx.test.runner.AndroidJUnitRunner 2>&1)
$TestExit = $LASTEXITCODE
$TestOutput | ForEach-Object { Write-Host $_ }
$TestPassed = $TestExit -eq 0 -and [bool]($TestOutput -match '^OK \(')

& $Adb -s $env:ANDROID_SERIAL uninstall com.limelight.noirdebug.test
if (-not $TestPassed) { throw "Client SBS GPU smoke test failed" }
```

A pass requires all of the following:

- Native LiteRT initializes with OpenCL/OpenGL interoperability.
- The entire model is GPU delegated.
- Public tensor strides are 12-byte RGB input and 4-byte depth output.
- The deterministic output is finite, positive, and non-flat.
- The renderer and inference contexts exchange input/output fences successfully.

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

- `Depth backend` is `LITERT_GPU_GL_FP16`, never a managed, CPU, or QNN backend.
- Surface callbacks and GL latches track the decoded stream without unbounded coalescing.
- Matched color capture and GPU input preprocessing continue at the intended depth cadence.
- Inference completion remains stable; on the current Galaxy XR build it is the dominant stage.
- Inference-to-renderer fence/adoption delay and matched color-to-depth age remain bounded.
- GPU depth postprocessing, SBS composition, and final GL submission continue without flat-output,
  occupied-mailbox, color-slot, or AI-busy growth.
- App CPU and device GPU telemetry remain plausible. Android 14 exposes no trustworthy public
  per-app NPU-utilization API; a vendor counter, when readable, is device-wide and is not evidence
  that Client SBS uses an NPU. The production backend is GPU.

There is deliberately no PBO readback, free CPU tensor-buffer wait, managed result queue, or Java
postprocess worker in this path. A stats row for one of those stages is stale and should be removed,
not interpreted as a zero-latency operation.

Capture a clean log window around one stream:

```powershell
$Package = "com.limelight.noirdebug"
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

## Sustained comparison

For optimization decisions, use the same moving host clip, resolution, frame rate, codec, HDR
mode, and headset power state for both Normal and Client SBS runs. Collect at least 15 minutes after
warm-up and record:

- Stats-panel screenshots at the start, middle, and end.
- Inference average/maximum and completion rate.
- Matched depth age, compose time, output submission time, and backpressure counters.
- Visible depth quality, judder, black/flat frames, thermal warnings, and discomfort.
- `adb shell dumpsys meminfo com.limelight.noirdebug` and `adb shell dumpsys thermalservice` at
  consistent points.

Optimize the stage with sustained latency or backpressure, not an isolated maximum. Current GL
input/output boundaries are GPU-resident; a high inference number is model/delegate work, while a
high inference-to-adoption number indicates synchronization or renderer scheduling.

## Failure classification

- **Backend `Unavailable` at startup:** inspect native library loading, model contract, full GPU
  delegation, and CL/GL interop logs.
- **Finite but flat model output:** reproduce with the deterministic native smoke test before
  changing reprojection parameters.
- **Non-flat depth but flat stereo:** inspect GPU depth/profile readiness, subject-plane profile,
  and reprojection shader inputs.
- **Good depth with lag:** compare inference time, inference-to-adoption delay, and matched depth
  age; check busy-slot and mailbox counters before changing model resolution.
- **Normal/Host modes regress:** treat it as a surface-routing or lifecycle bug. Those paths must
  not depend on Client SBS runtime availability.
