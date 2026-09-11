# Client SBS evaluation

This is the reproducible evaluation entry point for Moonlight 3D Client SBS. Production has one
depth backend—the native LiteRT 2.x GPU path—and one model family: original ZipDepth Base with
three fixed short-side-384 aspect graphs. DA-V2 Small, MiDaS v2.1 Small, and DepthART S448 evidence
is retained below only as historical model-selection data; those families are not selectable and
their archives are not packaged in the APK. The guide separates four questions:

1. Do the model, shader, and frame-ownership contracts pass on the JVM?
2. Does the supported Android XR APK assemble?
3. Does the real model execute through shared GL buffers on Galaxy XR and produce non-flat depth?
4. Does the complete decode-to-stereo path remain responsive during a sustained stream?

Run commands from the Moonlight 3D checkout root. Keep generated APKs, reports, and captures in this
repository's build directories, the device app directory, or a temporary directory. Do not write
client artifacts into the Apollo checkout.

## Current reuse and measurement scope

Client SBS has one local content-driven Near reuse path. It compares every admitted finite model
pixel with the retained real-inference input using the existing medium/strong/local thresholds.
Positive source identity must advance and capture time must not move backward relative to that
owner; neither 100 ms nor four callbacks forces refresh. Reused frames never replace the reference,
so cumulative change beyond the retained owner's thresholds still requires real inference.
Generation, model/input-contract changes, invalid geometry and reliable-history holds retain their
invalidation rules. The separate Exact equality/raw-model-cache tier has been removed.

Stats remains available, including its visible-only native wall timings, GL timer queries, device
sampling and asynchronous depth health. The performance-logging switch, background typed
`ClientSbsPerf`/`DecoderPerf` logging, audio level meter, temporary publication/hide/black debug
broadcasts and opt-in classifier timing method have been removed. Operational startup, transition,
failure and playback-recovery logs remain. Existing offline evaluation, Dump3D and production-model
benchmark tooling is retained; retired experimental APIs must not be assumed available.

Current correctness gates include `ClientSbsNearIdenticalPolicyTest`,
`ClientSbsGpuSceneCutShadersTest`, `ClientSbsGpuSceneCutDetectorTest`, and the actual-GLES
`ClientSbsGpuSceneCutDecisionReasonInstrumentedTest`. Its long-lived reuse case exercises more than
a day and a 64-bit source gap, unchanged/lazy inputs, cumulative change against a fixed owner,
new real-owner replacement, reset invalidation and authenticated decision tokens. The native
`nearReusePreservesSlotsAndLazyPackingHandlesEveryInferenceFallback` gate checks the corresponding
slot/fence/fallback path. Build and device qualification for this latest cleanup must be recorded
separately; the historical test totals and timings below are not results for the new implementation.

## Audio startup and unused XR holder, 2026-09-07

The 11:45 session exposed an empty AudioTrack start during native audio's initial 500 ms
resync/discard interval. Playback now waits for current-generation PCM to prime the existing
actual device start threshold. Focus loss, stop and replacement clear priming; backlog recovery
uses the actual output-buffer duration. No audio buffer, overload ceiling or retry deadline grew.
The later 11:45:18.182 underrun and 11:45:36.316 backlog recovery are not proven to share this
startup cause; no matching Wi-Fi scan or causal Cinema event was established.

Normal/Host direct SceneCore presentation no longer exposes the paused, producerless Android
GLSurfaceView holder. Client SBS enables it after decoder parking and disables it after the
acknowledged EGL detach/direct rebind. Intentional removal preserves the connection; unexpected
Client holder loss retains stop-before-cleanup. This addresses the unused layer behind 60 empty
buffer reports, but requires a fresh live capture to verify runtime behavior and any GPU saving.

A separate native video-array local-reference leak is fixed. Failed growth preserves the prior
buffer, and allocation failures are reported without leaving a pending exception on the video
thread. The native fixture passed 512 growth/reuse cycles, allocation failures and final cleanup;
it is part of the transport-contract CI workflow. All 120 focused JVM tests across 16 suites
passed with no skips, including audio priming/backpressure/focus, holder lifecycle/ownership,
connection/input, Cinema, Stats and blending-request regressions. Both debug ABI APKs assembled.

At that initial review, the 261 non-JNI-local-reference warnings remained unresolved: all emitting threads were
identified as Binder workers, but no warning-site stack identifies the offending library. The
independent local-reference leak must not be called a fix for those warnings. Device API 3 also
rejects native media-blending control even when the SDK preference getter retains OPAQUE.
[SceneCore's release page](https://developer.android.com/jetpack/androidx/releases/xr-scenecore)
still lists the already-used beta02 as latest on September 7. No warning suppression or speculative
dependency substitution was introduced.

Build/test/deployment evidence is under `app/build/runtime-audio-fixes-2026-09-07/`. Original logs
and Binder/native investigation remain under `app/build/log-review-2026-09-07-1148/`. No host,
model or shader change was needed. Live playback qualification was pending at that build stage;
the subsequent captures and final qualification are recorded below.

The subsequent update-install verified SHA-256
`b98c20e80b8b78bd8b9dd1a36ed9c7eee700f78ced144019a4f180b85a551711`. The new Host 3D
session confirms the unused Android holder is absent and its empty-buffer reports stop; it does
not quantify GPU savings or qualify physical Client 3D transitions. Startup priming succeeds, but
the platform explicitly rejects FAST and forces XR_SPATIALIZER deep-buffer output (5130 frames
at 48 kHz). This is the actual platform buffer, not the 1920-byte/10-ms request. Shared audio/video
disturbances recur about 120 seconds apart; a separate burst has Wi-Fi scan-completion evidence.
Neither complete short trace includes a periodic disturbance. A longer attempt began after
headset doff/sleep and is excluded from playback evidence. The complete system bug report and
capture qualifications are under `app/build/system-capture-2026-09-07/`; its `capture-summary.md`
distinguishes finalized healthy traces, partial/asleep traces and the still-pending warning-site
JNI stack. No production changes were introduced during capture.

After the wearer reconnected, a finalized 150-second trace (host 14:25:59.417–14:28:29.557)
captured the periodic disturbance at device 14:27:13. Audio and video receive threads wait for
incoming data together, and Qualcomm packet-delivery threads sleep before waking both receivers.
The decoder workers are not starved of CPU, the mixer keeps its cadence while queued PCM drains,
and no overlapping app GC explains the event. The evidence places the pause before application
decoding but cannot separate host transmission, access-point delivery, or headset radio/firmware.
See `audio-live-cycle.pftrace` and its companion review in the same capture directory. Two later
native debugger attempts attached but failed during stop/resume handling; both cleaned up and
left the app untraced and resumed. They occurred after the timing trace and yielded no JNI stack.

## Audio route request and opacity reporting, 2026-09-07

The first routing experiment preserved game audio usage while identifying the PCM as an audiovisual
soundtrack. API 32+ stereo opted out of optional platform spatialization; multichannel retained
platform surround rendering. Setup, fallback, focus and replacement shared these attributes.
One-time audio configuration logs now separate requests from the native-granted performance
mode and existing buffer/start threshold. The audio write path and recovery limits are unchanged.
This targeted the observed forced spatializer/deep-buffer route. The live qualification below
established its result and the final removal of that experimental opt-out.

The opaque-video adapter no longer reads the cached SDK blending value or returns it as native
success. It retains the best-effort request and independent visibility transitions. This corrects
an acceptance assumption; it cannot add the missing media-blending capability to device API 3.
There is no measured GPU improvement from this change and no warning suppression.

The 58 focused audio, opacity and surface-handoff JVM tests passed with no failures/errors/skips;
both ABI APKs assembled. The arm64 APK was update-installed and its on-device SHA-256 matched
`96e057fe520d4d90d159968504190d2fddf2851544c6febbf4051412d1667572`.
Evidence is under `app/build/runtime-route-fixes-2026-09-07/`. The headset was asleep during
deployment; live audio route qualification and identification of the JNI warning caller remain
pending. Host source was not changed in this iteration.

Subsequent inspection of the saved bugreport's audio policy shows a broader platform constraint:
the built-in Speaker routes through `deep_buffer` or the voice-call path, and the configured
output profiles expose no FAST/MMAP media output. Output 13 reports `PRIMARY|DEEP_BUFFER` with
110 ms policy latency. Properties include `audio.deep_buffer.media=true` and
`vendor.audio.feature.deepbuffer_as_primary.enable=true`. The scoped excerpt is
`app/build/system-capture-2026-09-07/audio-policy-evidence.txt`. Thus the earlier forced deep-buffer
observation must not be attributed solely to optional spatialization. The stereo opt-out cannot
create a missing fast output; the hardware test must report its actual effect. The policy latency
is not an acoustic end-to-end measurement, and the voice-call path is not a valid media workaround.

The wearer then reconnected on the treatment build. At 15:13:16, the log confirms `NEVER` reached
the policy, but the granted performance mode stayed `NONE` and the buffer/start threshold stayed
5130 frames. The active track still used output 13 / XR_SPATIALIZER / PRIMARY|DEEP_BUFFER. The
snapshot's 194.23 ms track latency versus the earlier 163.47 ms is explained approximately by
different queued PCM (2906 versus 1424 frames); it does not establish a regression or improvement.
Output write-latency averages were essentially unchanged (134.50 versus 134.24 ms). The initial
four-block/~16 ms recovery overlapped HAL speaker initialization, with no subsequent backlog
warning in the pre-debugger sample and zero current-track underruns in its snapshot. Evidence is
`app/build/runtime-route-fixes-2026-09-07/live/`.

Because the spatialization opt-out gave no demonstrated route benefit, it was removed. The final
implementation keeps GAME/MOVIE media classification, platform-default spatialization and truthful
performance-mode reporting. Its 57 focused tests passed; both ABIs built. The final arm64 APK was
update-installed and the device hash matched
`ad0b6eb7f9f04729c97b36214f88851306dd4fce5b17a280e20dc4eea52b8224` at 15:25:47.
No additional live session was required from the wearer after that cleanup installation; no
latency or GPU improvement is claimed for the final build.

The warning-site JNI capture succeeded during reconnect in
`app/build/system-capture-2026-09-07/jni-warning-20260907-152111/`. It identifies the captured
warning's caller as Impress beta03 `SplitEngineSubspaceManager.nForwardSubspaceTransform`, whose
cleanup calls `DeleteLocalRef` on its incoming transform-array reference. JNI permits deletion of
local argument references; ART's transition-reference implementation warns and performs a
successful no-op here. This establishes the source of this compatibility warning, not a leak or
use-after-free. The qualified stack/disassembly report is `jni-impress-root-cause.md` in the same
capture root. No newer published Impress version or supported app control was found. No warning
suppression, private JNI rebinding or binary patch was introduced. The successful capture took
63 ms for the raw stack and detached cleanly; cleanup verified `TracerPid: 0` and a running app.
All debugger intervals are excluded from performance claims.

## Cinema environment subpanel, 2026-09-07

Cinema now follows the existing tile/subpanel pattern: first tap enters using the saved background,
and an active tile tap toggles the shared lower panel. Its connected choices are Black, System
environment and Passthrough; Exit Cinema restores the previous transform/preferences and closes
the panel. The presentation and environment ownership contract lives in `android-xr-sbs.md`.

All 96 focused JVM tests across nine suites passed. They cover the three environment modes,
capability and asynchronous state changes, partial SDK failures, preference restoration, durable
defaults, actual subpanel interactions, late/hidden choice callbacks, and shared panel/control
regressions. Both debug ABI APKs assembled. Seven new strings are synchronized across English and
all 28 existing translations; XML and array IDs were checked. No model, shader or host change was
needed for this iteration.

The arm64 APK was update-installed with app data and pairing preserved. Installed/local SHA-256:
`039604252ef3fd6d1c83e3d692e14fab4c4f8701286be4679f87a34bf35ed1b3`.
Evidence is under `app/build/cinema-environments-2026-09-07/`. Live headset verification of all
three backgrounds and the shared panel remains pending; mocked SceneCore tests establish request
and restoration behavior, not device compositor acceptance.

## Expanded Host Stats and fixed dock, 2026-09-07

The host/client pair now uses one 240-byte version-2 telemetry body. Original Sunshine/Apollo
connections remain capability-gated; the unpublished custom version-1 format is not supported.
Stats shows independent GPU infer/reuse/invalid and output rates, publication/copy freshness,
and eight cumulative CPU/GPU stage means with sample counts. Performance-only updates may advance
without advancing the health chart. The host contract in `host-sbs.md` owns wire fields and
measurement semantics; `android-xr-sbs.md` owns client presentation and lifecycle behavior.

The dock has no secondary-action expander. End session stays visible immediately before the debug
Dump 3D button, and the panel width follows its actual tile count. All 24 new Stats strings are
translated into the 28 existing locales; the obsolete expander labels were removed. XML, unique
keys, format placeholders and preservation of unrelated resource entries were checked.

All 93 focused JVM tests across 11 suites passed, including strict V2 parsing, host-clock rates,
reset/wrap/stale cases, independent health/performance updates, original-host capability gating,
subscription lifecycle, real Stats tables, dock collapse/reveal and tile sizing. Both debug ABI
APKs assembled on Temurin 25.0.3+9. Native control and encrypted packet/FEC fixtures passed. The
companion optimized host built and passed 135 focused tests across 28 suites.

The arm64 APK was update-installed without clearing app data or pairing. Installed and local
SHA-256 match: `d5cd9957a867f78e16e42e0dddf1abe8cc6e9f99f8100c6dad4a65bfffcf32ee`.
Evidence is under `app/build/host-stats-2026-09-07/` and
`app/build/host-stats-v2-2026-09-07/`. The new host executable has not been started; final-pair
headset layout, telemetry cadence and runtime latency remain to be live-qualified. No shader,
model or rendering-policy change was made in this telemetry/dock iteration.

## Host Stats and negotiated source repeats, 2026-09-06

Host Stats subscriptions now follow visible Stats ownership. Hiding cancels retries, unsubscribes
and clears local chart history; reopening requests fresh samples, and late packets cannot restore
hidden history. The host also fixes its GPU-opaque snapshot admission, repeated cut-pulse reporting
and the separate processing-latency/content-age fields. Host implementation and wire semantics are
owned by the companion host documentation.

Client SBS can additionally retain already-presented output for negotiated unchanged encoder input.
It attributes tokens through exact input PTS and output Surface timestamps, with bounded metadata
rings and codec/surface epochs. The ordinary decoder and SurfaceTexture drain continue. A valid
GPU-authenticated Near adoption is required before retention; successful texture allocation cannot
authorize retention of invalid depth or unresolved geometry history. The initial codec-settling
heuristic and its lossy-quality limitation are defined in `android-xr-sbs.md`; no recurring Near
age limit or pixel-equality reduction was restored.

All 156 focused JVM tests across 19 suites passed, covering decoder attribution, epoch/PTS failures,
long static retention, unknown/changed/cropped sources, GPU-owner validity and presentation conflicts,
Stats hide/reopen/retry ownership, existing decoder recovery and renderer scheduling. The eight new
renderer tests exercise the real renderer state with a mocked view. Initial JVM reflection failed
on unavailable legacy GL types; targeted method-handle lookup fixed test resolution without changing
production visibility or relaxing assertions. Both debug ABI APKs assembled on Temurin 25.0.3+9.
The native packet/FEC gate passed encrypted HEVC/AV1, capability negotiation, zero/legacy/long headers,
short-header truncation and recovery cases. Evidence is under
`app/build/source-identity-review-2026-09-06/`.

The new Stats row is localized in English and all 28 existing translation files. Both keys and
format placeholders were validated, existing entries were unchanged, and all 16 relevant UI tests
passed again after the resource update. The final APKs assembled and the arm64 APK was
update-installed with app data and pairing preserved. Installed and local SHA-256 match:
`9daf18937ebeb8a21d8ee5cd5a472889da9a43cbda87a3b51802b925e2d50c4c`.

This qualification does not measure live repeat rate, display quality after stopping motion,
Host Stats refresh cadence, end-to-end latency or whole-device GPU utilization. A headset stream
must still confirm those behaviors with the final host/client pair.

## Processor setup and Stats review, 2026-09-06

The current depth/disparity processors initialize fixed uniforms once per private program,
removing 21 steady-state uploads per normal real-inference/postprocess cycle. Decoder Stats avoid
hidden-window snapshot/histogram allocation; visible rows retain unchanged text and coalesce
panel-sizing callbacks. Rendering contracts remain in `android-xr-sbs.md`.

Both debug APKs assembled on Temurin 25.0.3+9. All 258 focused JVM tests and nine Galaxy XR
depth/disparity GLES tests passed. The new fixed-uniform test checks program isolation, temporal
resets, repeated raw output and disparity dispatch. Its initial sampler float-query assertion
failed; using the integer sampler getter passed with the production APK unchanged. No assertion
was relaxed. The existing output and production-shape tests also passed.

Evidence is under `app/build/perf-review-2026-09-06-*`; the final GLES log is
`perf-review-2026-09-06-gles-retry.log`. The update-installed arm64 APK SHA-256 is
`b8f6d76d4af9e91f49fc2c2fd8454021094ef4fca995e575c83a908d1cbe261d`.
Saved app data/pairing were preserved, and only the `.test` package was removed after testing.
These gates establish correctness and removed redundant work; live FPS, latency and device GPU
utilization improvements have not yet been measured.

## Audio recovery and runtime follow-up, 2026-09-06

The 12:03 live Host SBS session's client snapshot is under `app/build/log-review-2026-09-06-1203/`.
It confirms the preceding optimized APK and 7680x2160 HDR direct decode, with the physical display
at 90 Hz. Operational logs do not establish actual decoded FPS or dropped video frames.

Eight audio-backlog recoveries at headset time 12:05:33.676–12:05:34.911 discarded about 410 ms of
PCM across the burst. The measured headset clock was about 3356 ms ahead of the host. A separate
code review reproduced premature recovery reporting: native backlog could reach its target, but
the next PCM write could still fail or stop early after the drop totals had already been cleared.
Two real-renderer tests first failed, then passed with reporting/reset after a complete write on the
same active playback generation. The fix adds no native-queue reads, waits or stricter discard
policy. It fixes accounting; it is not evidence that the audio gaps have been eliminated.

Three legacy migration readers now avoid opening absent databases. Tests also create and migrate
all three real historical schemas alongside a current computer and verify preservation after
reopening. All 31 focused audio, priority and database tests passed; the non-root debug APK assembled
and was update-installed with app data preserved. SHA-256:
`203d144eedf2961703b6175e588f74951802d6495d1f12a3ff5e409c937debff`.
Evidence is under `app/build/audio-cadence-2026-09-06-*`. The main app was not uninstalled or cleared.

Read-only Wi-Fi service history records scan completions at 12:05:35.206, 12:07:36.864, 12:09:38.520,
12:11:40.174 and 12:13:41.832, about 121.7 seconds apart. Later audio recoveries align with these
too; the last reported recovery at 12:13:41.825 precedes scan completion by about 7 ms. This repeats
the earlier scan correlation but does not identify the requesting module or radio failure mechanism.
`AllSingleScanListener` also receives scans initiated by other modules; associated network selection
was already disabled, external-app one-shot scan requests were zero, and scan throttling was enabled.
The service dump and device context are under `app/build/wifi-scan-review-2026-09-06/`.

The saved link used 6295 MHz and Android Wi-Fi standard 8 (802.11be). Samsung acknowledges Galaxy XR
PC-streaming stutter on Wi-Fi 7/6 GHz and recommends temporarily using Wi-Fi 6/5 GHz while an update
rolls out. A matched test should keep stream settings fixed and span several scan cycles. The notice
does not name our exact periodic symptom or a fixed firmware version, so the observed
`I610UEU2AZF3`/June 2026 security patch cannot be classified from that notice alone.
[Samsung guidance](https://www.samsung.com/us/support/troubleshoot/TSG10007646/),
[Android Wi-Fi standard constants](https://developer.android.com/reference/android/net/wifi/ScanResult#WIFI_STANDARD_11BE).

Keep scan throttling enabled: it limits app `startScan()` calls, not all system scans. The generic
Wi-Fi-scanning setting is not a reliable connected-scan shutdown switch. Disabling Location Accuracy
is a lower-confidence controlled test only if that service is the requester; XR availability is
unverified and location-dependent features can lose accuracy. No device setting was changed during
this review. [Android scan throttling](https://developer.android.com/develop/connectivity/wifi/wifi-scan),
[Google Location Accuracy](https://support.google.com/android/answer/3467281?hl=en),
[AOSP scan listener](https://android.googlesource.com/platform/packages/modules/Wifi/+/c6e2a6083b342a9196091f17ab5276cac8f4d2b3/service/java/com/android/server/wifi/WifiConnectivityManager.java).

The capture also contains 181 non-JNI-local-reference warnings, eight subview messages during the
stereo commit, and 28 startup pose warnings. There is no fatal exception. The subview messages come
from SceneCore's native media-material path; application head-pose reads use published runtime
state. The JNI warning lacks a native stack to identify its exact library. No warning suppression
or unverified dependency replacement was made; official SceneCore releases still listed beta02
when checked. Dependency sources are retained under `app/build/xr-warning-review-2026-09-06/`.

## Historical client reuse and model-internal review, 2026-09-05

The [six client reuse changes](client-performance-reuse-review-2026-09-05.md) were built on JDK 25
and tested on the physical Galaxy XR SM-I610, Android 14/API 34. All 853 non-root debug JVM tests
passed, both APKs assembled, and seven focused output-scatter tool tests passed. Host production
code and model assets were not changed by this client optimization pass.

The offscreen/device gates passed for sparse OES ordinal sampling (2 tests), R32F conditioner
differential/production shapes (2), reliable-history parity (2), classifier ownership/expiry/lazy
packing (4), real native inference/cache/fallback (1), and archive retention/corruption (1).
The additional opt-in classifier timing fixture also passed. Its supplied depth-state flags isolate
classifier behavior; it does not establish a full decoded-video temporal trajectory.

The initial native test used a real 672×384 production model, asymmetric RGBA8 textures, shared EGL
contexts, and the actual JNI fence protocol. It verifies all-pixel RGB packing with the vertical
row conversion, untouched input on EXACT, bit-identical cross-slot raw output, replacement real
owners, same-slot retention, and fresh packing after malformed authentication/reasons, CPU bypass,
the then-current owner expiry, invalid record ranges, and disabled record reads. The implementation aliases
the last real output slot, avoiding a separate raw buffer and every-inference copy. Sparse ordinal
and conditioner tests compare against the preceding dense-sample/RGBA32F horizontal paths.

The archive test uses a uniquely created disposable cache directory, opens the real packaged
archive only twice for A → B → A, then verifies that corrupted speculative C is extracted again.
All three final files pass their manifest hashes. The live production cache and saved settings
are outside that test directory.

At 672×384, 20 native warmups and 50 measured invocations per path with a partial wake lock gave:

| Native path | Median invoke-to-output-ready | p95 |
| --- | ---: | ---: |
| Real inference, including deferred pack | 10.927 ms | 11.385 ms |
| Exact raw reuse, alternating output slots | 0.894 ms | 1.608 ms |

These timings include the JNI/worker/fence path, but exclude classification, matched-color capture,
depth/profile/geometry processing, and XR presentation. They do not predict whole-stream FPS.
An earlier run without a wake lock had large inference outliers and is not used for the table.
Before raw-slot aliasing, the same bounded wake-lock fixture measured 11.588/1.244 ms medians;
these sequential runs are supporting evidence, not a randomized sustained A/B.

Before exact-input expiry was removed, the separate classifier fixture ran 8 warmups and 32 samples
per leg, with eager/lazy order reversed
on its second round. Moving inputs produced 32 INFER decisions in every leg. Identical inputs gave
6 near, 24 exact, and 2 refresh-infer decisions in every leg under synthetic 30 fps capture clocks.
Moving eager/lazy medians were 1.568/1.405 ms and 1.617/1.290 ms; identical eager/lazy medians were
1.251/1.217 ms and 1.254/1.199 ms. These are CPU submission plus `glFinish` wall times, not pure GPU
times. Input uploads, decision reads, commits, native deferred packing, inference, color, and XR
are excluded. Do not add measurements from separate fixtures to claim total pipeline latency.

The [model-internal experiment](../tools/zipdepth-output-scatter.md#galaxy-xr-result-2026-09-05-no-measurable-gain)
replaced only the identity output scatter with `DEPTH_TO_SPACE`. Complete CPU outputs were
bit-exact on three inputs for each shape; real-frame GPU outputs were also bit-exact on all three
shapes. Six normal benchmark legs (20 warmups/100 samples, thermal status 0) and two separate
kernel-profile runs passed. There was no measurable speedup, so the packaged graphs and hashes
remain unchanged. Profiles prioritize moving the final linear channel projection before resize
and reducing learned-tail intermediate branches for future experiments; those are not shipped.

Artifacts, logs, numerical-runtime fingerprint, and model outputs are under the ignored
`build/client-reuse-2026-09-05/`. Every numerical comparison used
`E:\ApolloDev\modelopt-py312\Scripts\python.exe` (Python 3.12.14, NumPy 2.5.1, Pillow 12.3.0,
ONNX 1.21.0). Update-install/manual instrumentation preserved the existing app data. The final
arm64 APK SHA-256 is `34cc88868897e32476dc3403385e93e24f38c080942441280294e400d8019624`.

The Exact/native-cache and classifier-timing methods used for these measurements are retired.
Use the current correctness filters above through the data-preserving manual procedure below;
`ClientSbsModelAssetCacheInstrumentedTest` continues to cover archive retention and corruption.

Sustained default-1080p30 streaming, moving-content hit rates, visible depth/cut quality, real panel
refresh, and headset thermals remain physical live-test work. Canvas/OES SDR/PQ shader coverage
does not qualify the complete hardware 10-bit HDR decoder path.

## Historical live-log fixes and static-input reuse, 2026-09-05

The 17:20 paused-video retest used the preceding APK. It reported approximately 23 latched
frames/s, 8 real inferences/s, and 61–69% total reuse, with no steady-state raw-field or flat-output
faults. Its log format did not include the exact tier or per-texel equality evidence, so those
records cannot establish why the visible Exact counter remained zero. An owner-age rejection
does not establish unchanged pixels: the old classifier skipped near comparison after expiry.

The historical diagnostic control added the exact counter and sampled pre-commit equality evidence to
`ClientSbsPerf`. It passed all 878 non-root debug JVM tests and assembled both APKs. The physical
Galaxy XR test `ClientSbsGpuDepthProcessorInstrumentedTest#exactReuseHealthCopiesPrecommitEvidenceAndRejectsMissingOrShortProbe`
passed: the existing asynchronous health readback preserved the copied candidate/owner pair and
rejected missing or undersized evidence. Update-install and removal of only the test package
preserved application data. Control arm64 SHA-256:
`cbc66d29045ff6628051dd1359ded3a30c7009f2aa7e141356720337052cb57e`.

This control also contains the playback backpressure/priority fixes and API-34 window refresh
fallback. Its successful tests establish request/lifecycle behavior, not achieved panel refresh,
elimination of live backlog, or thermal improvement.

The subsequent expiry-removal build passed all 879 non-root debug JVM tests and both APK builds.
Four data-preserving instrumentation invocations passed on the Galaxy XR: the two health-copy
tests, the classifier decision/ownership class, and the real native model/cache fixture. The
classifier's opt-in timing method was not enabled. The GPU classifier accepted exact input with
capture timestamps more than a minute beyond its owner, rejected a one-code RGB change after
ordinary reuse expired, and retained reset/new-owner/token checks. The native fixture waited
750 ms without real inference and then required an exact hit, untouched input storage, zero
LiteRT run time, and bit-identical cached raw depth. Diagnostic copy failure left production depth
running, and repaired staging recovered only through a fresh sample.

The native fixture's 50 repeated samples measured median/p95 invoke-to-output-ready times of
10.882/11.323 ms for real inference and 0.935/1.465 ms for exact reuse. These isolated correctness
fixture timings exclude classification, color, postprocessing, and XR presentation; they are not
a sustained performance comparison. The final arm64 APK was update-installed and only the test
package removed. SHA-256:
`9a1c5e9c428c07b3bf1b61a08ac20155d62cd05d97ad7f9c2392cb3af674c4ff`.

The current reuse policy is owned by
[the scheduling contract](android-xr-sbs.md#colordepth-scheduling-and-input-reuse). Live paused-input
pixel evidence, achieved refresh rate, playback stability, and thermal behavior were still
unverified at installation.

The subsequent 18:12–18:18 live session confirmed static-input memoization on this APK. With the
user-confirmed paused video, telemetry retained real owner frame 3907 for over 149 seconds,
reported zero inference and LiteRT call time, 100% exact reuse, and zero changed/nonfinite model
texels. Current stereo output continued around 21–24 FPS without flat or invalid-depth faults.
The display service explicitly rejected the 60 Hz window request, and the physical/compositor
rate remained 90 Hz. Device GPU clock fell from roughly 788 MHz during moving content to 421 MHz
while static; whole-device busy percentage remained high and thermal status remained SEVERE.
Those utilization samples include system/XR work and are not a controlled power comparison.
The user confirmed audio was muted; no recurring backlog error appeared, but sustained audible
playback remains unqualified. Local evidence is under `build/log-review-2026-09-05-1814/`.

Local build logs, APKs, and the data-preserving device result are under the ignored
`build/log-review-2026-09-05-1657/`. Raw streaming logs may contain credentials and remain local.

## Hidden-panel idle investigation, 2026-09-05

A saved Perfetto trace spans 18:25:09.546–18:25:19.577 PDT with Stats closed. The app's Android
RenderThread repainted a 42×42 vector drawable 877 times in 10.031 seconds, while its video GL
thread drew 231 frames. The drawable size, centered panel bounds, and source hierarchy identify
the depth-loading spinner: SceneCore hid the entity while the hosted Android root remained
visible. This panel is also created in Normal 2D. Its visibility lifecycle is now governed by
[the Spatial UI contract](android-xr-sbs.md#spatial-ui-learnings).

The RenderThread used 1.792 scheduled CPU seconds, compared with 0.625 for the video GL thread.
Its 7.375 seconds inside named buffer-dequeue slices are inclusive wall waits, not GPU execution.
The trace has no GPU execution slices, so it cannot assign a fraction of whole-device GPU usage
to the spinner, inference, or XR compositor. Its overlapping stream log reports 6.2 inferences/s
and a small number of changed model texels; this is distinct from the earlier zero-inference,
100% exact-reuse interval. Thermal status was SEVERE. The later Normal 2D sample uses 4K/90
instead of 1080p/30, so it is a separate workload rather than a matched performance control.

The refresh preference now follows the publicly advertised mode described in
[the presentation contract](android-xr-sbs.md#reconnect-and-saved-view-contract). GPU diagnostic logs
also retain six decimal places and a sample count for each stage; unavailable samples report
`n/a`. The old rounded `depth_cut=0.00` field alone did not establish zero postprocessing cost.

The final JDK-25 build passed all 891 non-root debug JVM tests with no failures or skips and
assembled both main and instrumentation APKs. Eight spinner lifecycle cases across API 34/35
verify real drawable start/stop, initially hidden attachment, delayed-show cancellation, mode
reset, and destruction. The fixture supplies the window-manager visibility event omitted by its
Robolectric window; it does not stub the ProgressBar's visibility or animation. The refresh
fixtures now use the suite's default mock engine, removing a mixed-engine registration conflict
that caused order-dependent failures in unrelated tests. The Wi-Fi fixture uses resettable
Robolectric network objects instead of framework mocks. The first failing runs remain in the local
artifact directory alongside the successful gate log.

The main arm64 APK was update-installed at 18:53:35, preserving the existing application data,
and its installed SHA-256 matches the built artifact:
`f0583776ca83d3f0496f203c2bd8f193e92633045c794d2f88c562583da11ee4`.
The preceding installed APK was saved as `control-installed-arm64.apk` and matches the
expiry-removal hash recorded above; the new build is saved as `treatment-arm64.apk`. No new
physical GPU or achieved-refresh measurement was taken after this update.

Saved logs, trace, processor provenance, SQL, and derived CPU/frame counts are under the ignored
`build/static-gpu-review-2026-09-05/`; `perfetto-review.md` records trace health and attribution
limits. A worn-headset retest must verify the hidden panel stops submitting frames, the achieved
refresh rate, and idle GPU/thermal behavior with the same scene and settings. A sleeping or
removed headset is not a valid idle-stream control.

The 19:35:30.395–19:35:40.366 Normal 2D retest verified the specific spinner fix on the installed
APK: zero 42×42 vector repaints, ten ordinary UI submissions, and 27.134 ms of app RenderThread
CPU time in 9.971 seconds. The earlier trace had 877 repaints and 1791.824 ms of RenderThread CPU
time. These are different video modes and thermal conditions, so they establish removal of the
unwanted animation, not a controlled overall GPU improvement. Normal 4K/90 still received and
decoded approximately 50 frames/s while the user reported a paused video. The decoder queue
remained empty. Host mode 0 was active; no host or client depth inference ran in this 2D interval.
The trace still showed substantial XR system work at about 90 Hz but no GPU execution events.
Saved evidence is under `build/idle-2d-retest-2026-09-05-1935/`.

The subsequent Client 3D retest logged the window and Surface preferences at 19:44:23, and later
display state confirmed physical mode 2 at 72.00001 Hz. The initial display snapshot still showed
90 Hz, so the preference must not be treated as an immediate mode switch. This paused-video
interval was not pixel-static: sampled model input changed by a few texels, and telemetry still
reported roughly 6–7 real inferences/s with bounded approximate reuse between them. Device busy
readings remained substantial at changing GPU frequencies and thermal status SEVERE. This does
not invalidate the earlier authenticated 100% exact-input interval. Evidence is under
`build/idle-client3d-retest-2026-09-05-1945/`.

The active performance target is 10% device GPU busy for the paused 2D case, with improvements
that also preserve moving-video quality and latency. That target is not yet achieved. Further
measurement must separate repeated frame delivery from the cost of retaining the same XR scene,
and retain GPU frequency, refresh, resolution, environment, and thermal context rather than
treating a lower utilization percentage at a higher clock as a power saving.

### Historical publication and environment hold measurements

The temporary debug receiver, decoder-output hold, subtree-hide and black-environment flags used
for these experiments have been removed. The following evidence describes those historical APKs
only; there is no active broadcast procedure in the current app. The normal Cinema action retains
its public-SDK black background with ordinary video publication and exact preference restoration.
Production opaque video and solid panel backgrounds also remain. SDK readback describes a request,
not a compositor GPU acknowledgement.

The publication-hold build passed all 907 non-root debug JVM tests (including 16 new policy,
decoder-ownership, and receiver/lifecycle cases) and both APK builds on JDK 25. The first two
runs exposed incomplete FragmentActivity setup in the new receiver fixture; restoring saved
state and attaching its fragment host in the framework's normal order fixed the fixture. The
final full run has zero failures, errors, or skips. The arm64 APK was update-installed without
clearing data, and its device SHA-256 matches
`ad2db0307fa8d796756894f6fa1002d9db47f2da7a2cb8e7636276065f8cd8f3`.
Evidence is in `build/static-gpu-review-2026-09-05/publish-hold-*`. Physical hold/resume and GPU
benefit are not yet validated by this build gate.

The subsequent physical Normal 2D 3840×2160/90 Hz test accepted a 20-second hold at
20:41:43.877 PDT and resumed publication at 20:42:03.877. It discarded 997 outputs; all seven
interior telemetry windows reported zero render releases and surface presentations while decode
continued at approximately 49–50 fps. Physical refresh remained 90 Hz. With three-second
transition exclusions, the independent one-second GPU samples averaged 50.21% before, 46.14%
during, and 49.33% afterward, all at 421 MHz. The 38–41% dips also occurred before the hold;
they do not establish a sustained 10–12 percentage-point saving. The short post-trace window has
only three raw samples, and thermal status moved from LIGHT to MODERATE across the capture.

Most measured GPU load therefore persisted without new video publication. This does not assign
that remaining load to an individual process, the video quad, or passthrough. The trace has no
per-process GPU execution data. Its ring buffer overwrote about 49 MB of ftrace data, leaving only
0.927 seconds of baseline CPU coverage and 2.665 seconds post-hold, so CPU deltas are not robust.
SystemUI and SurfaceFlinger submissions continued throughout the retained interval. Future paired
captures must retain complete scheduling windows, and isolate the app subtree from the environment
before attributing the remaining load. The 10% target remains unmet.

The paired host counters recorded approximately 50 desktop presents and conversions per second,
with one valid dirty rectangle per present, changing present timestamps, no cursor events, and
zero heartbeat encodes. These are real desktop update notifications; they do not prove changed
pixel values. Capture metadata or resized model-input equality cannot authorize dropping the
complete transmitted image. Evidence and reproducible phase analysis are in
`build/idle-publish-hold-2026-09-05/publish-hold-review.md`.

The follow-up build adds the bounded whole-subtree variant and avoids calling `setText()` on the
glance load indicator when the displayed text is unchanged. All 921 non-root debug JVM tests and
both APK builds pass, including exact-entity restoration, worker-to-main completion, lifecycle
cancellation, and receiver acceptance-order coverage. Its update-installed arm64 APK matches
SHA-256 `c826a36286d6a685e5d1ed1a95fc2bdea712f14ff4c3ae826a1ad5129b40a2da`.
The improved Perfetto configuration uses periodic file writing plus a separate metadata buffer;
a one-second device smoke test passed from `/data/misc/perfetto-configs`. This is configuration
validation only, not a performance sample. Whole-subtree GPU qualification remains pending.

The follow-up physical test hid the video and every child panel from 21:02:38.786 to
21:03:08.787 PDT. The wearer confirmed disappearance and normal restoration. All eleven complete
interior decoder windows retained approximately 50 fps input/decode with zero publication;
1,499 outputs were discarded. Physical refresh stayed 90 Hz. Settled GPU samples averaged
46.63% before, 43.21% hidden, and 52.75% afterward, all at 421 MHz. The restored window has only
four samples. SystemUI CPU decreased while camera/perception and compositor submissions persisted;
these CPU figures cannot allocate device GPU busy to individual processes. Scheduling coverage is
complete and neither trace buffer overwrote data. Full evidence and caveats are in
`build/idle-scene-hide-2026-09-05/scene-hide-review.md`.

The wearer then closed Moonlight and other open apps and remained at XR Home. The host disconnected
at 21:07:25.017 PDT, and Moonlight had no running process before or after the baseline capture.
Thirty independent GPU samples averaged 47.33% (45–51%), all at 421 MHz; thermal status stayed
MODERATE. XR Home ran at approximately 72 Hz, so this is a separate ambient baseline with different
foreground UI and refresh, not a matched numerical subtraction from the 90 Hz streaming trials.
It establishes substantial device-wide GPU activity without Moonlight, network streaming, or
decoding. It does not prove that the baseline is unavoidable or identify an individual GPU shader.
Evidence is under `build/idle-xr-home-2026-09-05/`.

The subsequent black-environment diagnostic build passed all 941 non-root debug JVM tests and
both APK builds, with zero failures, errors, or skips. Focused coverage checks asynchronous
application, exact preference restoration, partial SDK failures, capability loss, stale callbacks,
combined scene controls, and activity/decoder lifecycle cancellation. Independent review of the
cached SceneCore beta02 implementation confirmed main-thread listener delivery and that the empty
black preference does not start an asynchronous environment-asset load. The arm64 APK was
update-installed with data preserved and its on-device SHA-256 matched
`67bdd141431ba8152e52978f3c655918ab648b98a6363336e01ae4bd711b56a2`.
Build, review, capture scripts, and validation records are under
`build/idle-publish-hold-2026-09-05/black-environment-*`.

The subsequent matched-build captures retained full scheduling windows, 70 independent GPU samples
each, physical 90 Hz throughout, and 421 MHz in all 140 samples. Both 30-second holds discarded
1,500 outputs while hardware decode continued near 50 fps. Black applied after 551 ms; the wearer
confirmed black behind the visible video/controls and normal environment restoration. With the
same three-second transition exclusions, settled device GPU means were:

| Capture | Before | Held | Restored |
|---|---:|---:|---:|
| Publication only | 35.07% | 32.29% | 36.79% |
| Publication plus black | 38.64% | 38.92% | 52.21% |

The black-to-restored transition agrees with the wearer's approximately 38% versus 50% observation.
The initial and separate control baselines varied, and publication resumed at the same time as the
environment was restored. The pair therefore does not isolate a precise background saving or
establish a null effect. Camera/perception CPU occupancy also fell during black; this does not
attribute GPU execution or imply that tracking stopped. Control thermal status remained 3, while
black changed from 3 to 2. The 38.92% held value remains far above the 10% target.

Both traces retain scheduling across every selected phase and report no per-buffer overwrite,
ftrace overrun, or error-severity statistics; service-global informational discard counts remain
documented in the report. No per-process GPU execution records are available. This firmware's
`perfetto --background-wait` returned no PID, so the first setup stopped before any hold; the actual
paired scripts accept that behavior and use unique output paths with a bounded final flush wait.
Reproducible clock alignment, full-phase sensitivity checks, raw artifacts, hashes, and limitations
are in `build/idle-black-environment-2026-09-05/paired-review.md` and its companion files.

The saved system log additionally exposes a coarse CPM `Compositor` GPU-duration field. Settled
means were 2.194 ms in the control hold (eight samples), 0.790 ms during black (eight), and
2.390 ms after restoration (five). This reversible change identifies compositor work affected by
the environment; it does not separate individual sampling, blending, or reprojection passes.
The separately reported SystemUI GPU field includes negative values, and its late/drop fields are
`nan`; do not add these fields into a GPU budget. A targeted `cpm`/`xrp` TrackEvent probe enabled
additional vendor instrumentation, but ran with the screen OFF and exposed CPU scopes only. It
cannot qualify the active-rendering instrumentation. Preserve that distinction from the earlier
finding of no per-process GPU execution records in the ordinary capture configuration.

System-wide auto-spatialization was inactive in both paired captures: the saved runtime policy
logs contain 30 control and 28 black records with `Auto spatialization active: false`, and no true
records. The XR Home measurement window also contains ten such inactive policy records.
A later read-only settings check returned `system auto_spatialization_settings=0`.
This rules out active system 2D-to-3D conversion as the cause of those recorded GPU readings;
Moonlight's explicitly selected Client SBS mode remains a separate pipeline.

The historical Cinema integration and opaque diagnostic build passed all 956 non-root debug JVM tests and
both APK builds, with zero failures, errors, or skips. Seven focused Cinema cases exercise the
existing size/pose toggle, exact environment restoration, lifecycle reentry, unsupported capabilities,
and separation from debug ownership. The opaque adapter's cached readback/restoration paths also
passed focused and independent review. The single initial fixture failure used a mocked preference
whose empty fields were equal to the black preference; a real geometry-bearing preference corrected
that fixture. Identity comparison would be wrong because beta02's getter constructs fresh wrappers.
The update-installed arm64 APK retained app data and matched SHA-256
`c8bd8e36d62cf0ad3c92cb68547320ea773e6ce6f6b2751af55dfb7f6a072278`.
Build and validation artifacts are `build/idle-publish-hold-2026-09-05/cinema-opaque-*`.
This gate does not qualify the opaque experiment's visual behavior or GPU benefit.

The subsequent production opaque-video/solid-panel build passed all 957 non-root debug JVM
tests and both APK builds, with zero failures, errors, or skips. The focused 133-case rerun also
passed. Its first run found a drawing-recorder fixture issue: Robolectric's legacy Paint shadow
does not synchronize its separate alpha field when setting a packed color. Checking the submitted
color retained the full-bounds draw assertion. SDK review also corrected the dock to use a single
pixel-size request because beta02's metre-size setter recomputes and overwrites raster dimensions.
Tests cover opacity request failure/readback, preserved transition visibility, solid panel content,
and collapse/expand/reveal raster restoration. The temporary opaque diagnostic was removed.
The arm64 APK was update-installed with app data preserved at 22:36 PDT and its installed SHA-256
matched `1a298a90e04f64f691091975189018098a5dfa5a2d8aabb3f4805f0e0e360ed6`.
Artifacts are `build/idle-publish-hold-2026-09-05/solid-opaque-*`. Physical Cinema, opaque video,
solid-panel layout, mode/HDR transitions, and GPU impact remain unqualified on this build.

The following live session retained Client SBS from 22:38:15 through 22:45:43. The host delivered
1080p30 without encoder queue drops, and the display service confirmed 72 Hz. Cinema reapplied
black after 555 ms and restored the prior system environment; the initial opaque startup log had
already rotated out. This does not qualify visual panel layout or all mode/HDR transitions.
Thermal status reached SEVERE during the session. Four audio backlog bursts about 122 seconds
apart aligned with Wi-Fi scan completion; this is a strong diagnostic correlation, not a proven
radio/driver root cause. The app already holds Android's low-latency Wi-Fi lock.

The user's paused-input Exact counter matched the renderer logs. In the final six retained
windows, Exact remained zero while total reuse averaged about 15.7 FPS out of 30.0 FPS;
individual sampled candidate/owner comparisons reported 61,673–77,628 changed model texels of
258,048, without difference magnitudes. Earlier windows did contain Exact hits. Decision bypass
counts remained zero as expected for valid monotonic owner tuples; these are not skipped-inference
counts. No stale Stats binding or duplicate counter drain was found.

An added physical GPU regression,
`ClientSbsGpuSceneCutDecisionReasonInstrumentedTest#exactRgb8EqualityCoversEveryCodeAndIgnoresOnlyAlpha`,
passed against the unchanged installed production APK above. It covers all 256 codes in each RGB
channel, alpha-only changes, single-code RGB changes in the last partial tile, and Exact owners
older than a day. This did not reproduce the suspected sampler/image normalized-float mismatch;
the production predicate and Near policy were left unchanged. Only the instrumentation package
was installed and then removed; main app data remained intact. Saved logs, test artifacts and
source-linked reviews are under `build/solid-opaque-live-2026-09-05-224605/`. Locating the source
and magnitude of the reported paused-input changes still requires additional live evidence.

## Production contract

The current model/runtime, rendering math, and surface-ownership authority is
[android-xr-sbs.md](android-xr-sbs.md). This guide owns reproducible test procedures and measured
qualification evidence; retired contracts below describe the artifacts used in those experiments.

Client SBS has one native LiteRT GPU backend and one production model family, original ZipDepth
Base. Because the Galaxy XR GPU delegate requires static graphs, ZipDepth uses three fixed aspect
buckets with Float32 public tensors and FP16-stored large weights. The renderer selects the graph
with the smallest multiplicative error, `abs(log(bucketAspect / sourceAspect))`, exactly once when
the stream is created. Users do not select among model families.

### Retired DA-V2 and MiDaS comparison contracts

The following contracts are preserved to make earlier whole-clip and device measurements
auditable. Their archives now live under `tools/model-sources/retired-client-sbs-archives/`, outside
all Android source sets.

| DA-V2 target aspect | Fixed input and output | Pixels / tokens | Logical model and SHA-256 |
| --- | --- | ---: | --- |
| 16:9 | `rgb_nhwc[1,182,322,3]` → `depth_bhwc[1,182,322,1]` | 58,604 / 300 | `depth-anything-v2-small-static-322x182-fp16weights.tflite.model` — `82f8594f4ee615ab82f968aa461a3960c4cd680293fd087cb65d8631b18e4271` |
| 21:9 | `rgb_nhwc[1,154,350,3]` → `depth_bhwc[1,154,350,1]` | 53,900 / 276 | `depth-anything-v2-small-static-350x154-fp16weights.tflite.model` — `2739f306ce71b19a913cdc32c779226a620f7f81685a1946ac213fdbeeba67b0` |
| 32:9 | `rgb_nhwc[1,126,434,3]` → `depth_bhwc[1,126,434,1]` | 54,684 / 280 | `depth-anything-v2-small-static-434x126-fp16weights.tflite.model` — `353eb80fd6b9c6f97552a20b7bd29f79466a9b05287dcd4bfd93baaa4c1730f5` |

Their patch layouts are `23 x 13 + CLS = 300`, `25 x 11 + CLS = 276`, and `31 x 9 + CLS = 280`.
Each token count is already C4-aligned, so none needs explicit attention-tail padding. Direct
full-frame resize into `350 x 154` and `434 x 126` changes the
nominal 21:9 and 32:9 aspect by -2.60% and -3.125%, respectively. That distortion was an explicit
trade-off in the retired family.

Both spatial dimensions in every bucket are divisible by the DA-V2 patch size of 14. The three
canonical buckets use roughly 54,000–59,000 pixels. Other source aspects mapped directly to the
nearest bucket in the then-selected family. A renderer never changed model or tensor dimensions in
the middle of a stream.

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

### Production original ZipDepth Base short-384 family

ZipDepth uses the original `base` checkpoint and learned convex upsampler, not the lower-edge-detail
`base_npu` variant. All three static graphs keep the short side at 384; `896 x 384` exactly serves
21:9, while `928 x 384` is the bounded-compute ultrawide bucket rather than a true 32:9 raster.

| ZipDepth target | Fixed input and output | Pixels | Asset and SHA-256 |
| --- | --- | ---: | --- |
| 16:9-nearest | `image[1,384,672,3]` → `depth[1,384,672,1]` | 258,048 | `zipdepth-base-static-672x384-fp16weights.tflite.model` — `6296d5c2e4f857fd551d854ebf4dd2ab2462c0d7372d526bf0a7463718b8b6d1` |
| 21:9-nearest | `image[1,384,896,3]` → `depth[1,384,896,1]` | 344,064 | `zipdepth-base-static-896x384-fp16weights.tflite.model` — `31467ab0cd187b74c65b3b20f4850973309d120519b587610e3dd3e27b72df4a` |
| ultrawide-nearest | `image[1,384,928,3]` → `depth[1,384,928,1]` | 356,352 | `zipdepth-base-static-928x384-fp16weights.tflite.model` — `169d5e8802bea9aac839df6acb4a8dd8e92a53728ea6f4e39a4baca453fd34cc` |

The shared packer supplies raw Float32 RGB in `[0, 1]`; ImageNet normalization remains inside each
graph. ZipDepth emits nonnegative relative inverse depth with larger values nearer. Its absolute
numbers are much smaller than MiDaS and DA-V2, so geometry uses an explicit graph-specific offline
calibration rather than per-frame normalization:

| Graph | Raw V2 coordinate scale |
|---|---:|
| `672 x 384` | `0.04864449` |
| `896 x 384` | `0.04707071` |
| `928 x 384` | `0.05421491` |

The first accepted raw field of each shot contributes its arithmetic mean, which then stays fixed
until the next accepted cut. Geometry evaluates `(zipRaw - shotMean) / graphScale`; raw P2/P98 and
normalized temporal depth remain private cut-analysis/health fields and never feed disparity.
Whole-clip polarity passed 192/192 frames. A positive scale, complete finite nonnegative raw field,
and finite shot mean are mandatory.

The exact convex tail is expressed with standard convolution, four nine-way softmax groups,
weighted sums, and transpose convolution. One delegate-incompatible grouped convolution is
densified, and an algebraically equivalent 1024x/1024x global-context reduction keeps small FP16
products out of Adreno's flush-to-zero range. Each final graph has 163 operations, fully delegates
as one OpenCL partition, and preserves Float32 public I/O with FP16-stored weights.

### Retired DepthART S448 short-384 comparison

The retired DepthART evaluation used one S448 checkpoint exported into two immutable, fixed-shape
aspect graphs; these were not two independently trained models. There was no dedicated 32:9 graph,
so an extreme ultrawide source routed to `928 x 384` and incurred substantial direct-resize aspect
compression.

| DepthART target | Fixed input and output | Pixels | Asset and SHA-256 |
| --- | --- | ---: | --- |
| 16:9-nearest | `image[1,384,672,3]` → `depth[1,384,672,1]` | 258,048 | `depthart-s448-static-672x384-fp16weights.tflite.model` — `3de0ded3a2329a6cc4c89da535f4c1f3035dfc30c7e85359d48580003aad780b` |
| 21:9-nearest | `image[1,384,928,3]` → `depth[1,384,928,1]` | 356,352 | `depthart-s448-static-928x384-fp16weights.tflite.model` — `d166bb5dcbe16ea386640a344a80134da8e225837f4609eae64f57916ec757f2` |

The shared packer still supplies raw Float32 RGB in `[0, 1]`. Each graph bakes the checkpoint's
ImageNet input normalization into the model, so adding external input normalization would apply it
twice. The model emits disparity-like relative depth with larger values nearer. This retired study
used the then-current normalized P2/P98/profile evaluator and therefore did not fit a fixed raw
scale. A future attempt to reintroduce DepthART would require a new graph-specific raw-V2
calibration; it cannot reuse ZipDepth's constants.

Large weights use FP16 storage, while public input/output tensors remain packed Float32 for the
existing GL-buffer contract. The evaluation requested the same OpenCL `AUTOMATIC_FP16` execution
policy used in production. Both corrected graphs demonstrated complete one-partition GPU
delegation, finite structured output, and bounded isolated latency on Galaxy XR. Model provenance,
license metadata, and the retired archive remain under `tools/model-sources/` and are not bundled
in the APK.

DepthART's first selective-scan LayerNorm needs one delegate-specific numerical stabilization. The
unmodified graph adds epsilon `1e-5`, a binary16 subnormal, to low block-0 variance. Galaxy's Adreno
FP16 path flushed both operands to zero for 59 of 252 test tokens; reciprocal standard deviation
then became `+Inf`, the first encoder stage contained 261,120 NaNs, and the final graph returned only
its FP16-rounded bias (`0.8330078`). The packaged graph uses the algebraically equivalent block-0
form `z = 4 * (x - mean)` and `z / sqrt(mean(z*z) + 16e-5)`. This adds one scalar `MUL`, keeps the
variance and epsilon normal in FP16, and leaves the other four LayerNorms unchanged. Structural
audits permit only those block-0 rewires; CPU output is bit-identical to the source graph on the
deterministic probe and all eight sampled real clips. Uniformly scaling all five LayerNorms is
rejected because deep real-frame activations can overflow FP16 when squared.
`tools/stabilize-depthart-fp16-layernorm.py` hash-pins both immediate inputs, performs this one
rewrite, verifies exact output hashes and zero-error CPU parity, and atomically publishes the
result; `tools/model-sources/README.md` records the commands and pinned environment.

The guarded `tools/convert-tflite-fp16-weights.py` promotion step inserts delegated `DEQUANTIZE`
nodes ahead of the original Float32 graph tensors. It converts only finite live rank-2-or-higher
constants of at least 1 KiB, reparses the result, verifies public I/O, and requires finite non-flat
CPU output plus correlation >= 0.995 and normalized RMSE <= 0.03 against its exact source. DA-V2's
three canonical graphs each contain 765 serialized operations (683 core plus 82 constant
dequantizations); MiDaS contains 234 (138 plus 96). MiDaS CPU correlations were 0.999908, 0.999868,
and 0.999609 for 16:9, 21:9, and 32:9 respectively. Historical complete-acceleration validation
applied to the full formerly packaged graphs, not only to their pre-storage cores.

The supported XR distribution stores only the ZipDepth family in a standard solid TAR/XZ asset:

- `app/src/nonRoot_game/assets/client-sbs-zipdepth-models.tar.xz` contains the three complete
  original-Base ZipDepth graphs.

Every `.tflite.model` is a complete standard TAR entry using the exact logical filename in the
contract table. A single XZ/LZMA2 stream compresses the complete TAR, allowing ordinary solid
compression to exploit the graphs' cross-file similarity. There are no base entries, deltas, XOR
transforms, custom model representations, or reconstructed model bytes. The Java manifest records
each graph's TAR entry and expected SHA-256.

The deterministic ZipDepth TAR/XZ is 11,149,420 bytes (10.63 MiB), SHA-256
`0b737e7ff7d6717c9b376e2e6d195eb5ff4a54d49d862e3415f155d137c78558`, and is stored directly
because a second APK compression layer would not help. The three retired archives total 85,369,400
bytes (81.41 MiB) and remain under `tools/model-sources/retired-client-sbs-archives/`; Gradle does
not package that directory. Measure total APK size from the current build output rather than
treating a debug APK byte count as a stable model-package property.

On first Client SBS use in a stream session, the inference worker sequentially scans the ZipDepth
TAR/XZ stream and writes only the selected complete graph into `code_cache/client-sbs-model-assets`, verifies its
SHA-256, fsyncs a temporary file,
and atomically renames it to a hash-named final file before passing its read-only descriptor to
LiteRT. The cache prunes other staged graphs. A later stream reuses the verified selected file or
extracts its newly selected aspect graph; first initialization timing includes one-time extraction
and verification. A cold selection of a later TAR entry must decompress the preceding XZ stream,
although those preceding files are not written; the verified cache avoids repeated decompression.

Only one ZipDepth graph may compile or remain GPU-resident process-wide. The engine stays idle and
resident when presentation switches from Client SBS to Normal, Raw, or Host SBS, then is reused
without a compile stall if Client SBS is selected again. It is closed at full stream teardown;
inactive modes do not submit inference work.

ZipDepth receives the complete decoded landscape frame in the selected rectangle. Downsampling
integrates every covered source texel cell with its exact overlap weight; when either axis is a
genuine upscale, pixel-center bilinear sampling is used instead. There is no crop or square
padding. Portrait input is aspect-fitted with reflected side padding resolved per source cell and
cropped back in depth processing. HDR conversion happens per source cell before integration. Only
the model-input branch is resized. The matched-color and per-eye targets use the client-requested
`W x H` output contract. If a legacy host negotiates a lower decoded resolution, that input is
upscaled to `W x H`; the stats resolution remains the request/output target rather than a claim
about the decoded input.

Client SBS evaluation requires a mono host-application frame. `SBS_MODE_OFF` disables Apollo's
Host SBS AI packing, but cannot un-pack an SBS image already rendered by the application for Host
SBS Raw; Client SBS would process that packed image as one mono input.

The renderer writes packed Float32 model input into a shared OpenGL buffer. The normal preprocess
combines that pack with the color-cut luma reduction in one 16x16 compute pass. LiteRT converts that
public layout to its internal GPU layout, runs the model's validated OpenCL precision, and returns
Float32 depth in a shared OpenGL buffer. All three production aspect graphs report
`LITERT_OPENCL_FP16_GL_IO`. Initialization is accepted only when the complete graph is delegated to
OpenCL; partial delegation or CPU execution makes the backend unavailable. GLES then performs
the raw-mean reduction, private P2/P98 cut analysis, and profile publication. For each newly adopted
real depth, the source-aligned `R32F` ZipDepth output feeds the conditioner directly. The only live
compose path subtracts the shot-latched arithmetic raw mean, divides by the selected graph's
calibrated scale, applies the host V2 far-exp/linear/near-log curve, fixed pop
`1.75`, parallax-per-pop `0.00375`, and the exact `+/-0.04` fourth-root container. Four compute
passes build the contractive signed-parallax field. The host-exact at-most-11-update inverse is
stored as a linearly sampled 1x-depth `RG16F` seed; one paired-eye fixed-point correction then
produces a 2x-horizontal x 1x-depth `RG16F` refined cache before matched full-resolution color is
sampled. No seed-only or Bestv2/frontmost-probe geometry path is compiled for live use.
The GPU color-cut detector consumes the exact rectangular SDR model-input frame and publishes its
cut flag directly to the depth processor through an SSBO.
It is optional to depth-pipeline readiness: creation or runtime failure disables color-cut evidence
and near-identical reuse, while inference and reprojection continue with bounded two-observation
depth-only confirmation.

ZipDepth requests LiteRT's device-specific automatic OpenCL storage policy and `AUTOMATIC_FP16`
execution policy. The retired non-C4 DA-V2 Quality graphs were not
FP16-safe on this Galaxy XR firmware: an edge-rich input exposed the packed-softmax-tail defect and
collapsed to the final output-convolution bias (`0.088684082`). Tail-padding plus exact-GELU variants proved the
workaround, but the retired DA-V2 family ultimately used three naturally C4-aligned graphs. They
needed no tail padding, used delegated builtin exact GELU, and passed the edge-rich FP16-vs-FP32
parity gate.
Execution policy is part of the compiler-cache namespace. Public GL tensors stay packed Float32.
The temporary half4 direct-external probe failed output completeness on Galaxy XR and has been
removed along with the async runtime probe and ADB priority override. Debug and release builds use
the same fixed Low GPU-priority hint (`priority = 1`), allowing inference to yield to XR composition
and UI work. LiteRT exposes a hint, not an effective Adreno scheduler-priority measurement. Earlier
Low/Normal and external-storage measurements are historical evidence, not active runtime choices.
The existing offline production-model benchmark and profiler are retained for controlled evaluation.

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
ms download, and 15.481 ms total. These results, together with live testing, supported the
then-production canonical DA-V2 set before the ZipDepth-only decision.

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

Every legacy Client SBS model preference, including old DA-V2, MiDaS, and DepthART IDs, migrates to
ZipDepth. If ZipDepth cannot initialize or execute, it becomes `Unavailable` and Client SBS
duplicates mono; there is no alternate-model, inference-backend, or geometry fallback. The
conditioner, exact 1x `RG16F` seed, 2x-horizontal one-correction `RG16F` refinement, and packed
compose are required parts of one strict V2 route. Initialization or runtime failure in any of them
presents current color flat; the seed is never presented as a lower-quality fallback.

Production does not package or select a managed Java LiteRT interpreter, QNN/HTP delegate, CPU
model path, PBO tensor-readback path, or Java result worker. Normal and Host SBS modes do not depend
on the Client SBS runtime. Generated ONNX files, conversion reports, and intermediate models stay
under `C:\tmp\dav2-dynamic-export`; retired family archives belong only in the documented
non-packaged repository directory, and no client artifacts belong in the Apollo checkout.

The native engine has exactly two packed input/output tensor slots, matched one-to-one with two
full-resolution color slots. Renderer input-ready and previous output-consumed fences transfer to
native ownership on each arbitration. The returned ready fence transfers to the renderer. A real
inference publishes a new post-read/history-commit consumer fence before slot reuse; a reuse or
discard keeps the returned no-read fence as that dependency. This preserves bounded ownership
without blocking the renderer or growing a result queue.
Native teardown retains transferred close fences and reports bounded-drain failures instead of
discarding the opaque engine handle. A failed close is quarantined and retried only from the next
dedicated inference worker; a new model is not initialized while an older context remains unsafe to
destroy.

The live order uses one bounded arbitration transaction at a time. The renderer captures current
color plus its exact packed Float32 model input, and a fused GPU pass compares that input with the
last valid real inference. A real result is ordered, postprocessed, and committed before the claim
is released. An authenticated near-identical result instead presents the current color using the
last real inference's cached depth/profile/warp and freezes both model-input and scene-cut history.
Landscape input uses exact source-cell overlap without padding. Portrait input retains the
per-source-cell reflected aspect-fit mapping described above.

Reuse follows Apollo's literal decoded-pixel thresholds: `16 x 16` tiles; per-channel absolute
medium delta `>= 1/64` and strong delta `>= 0.20`; global medium and strong counts no greater than
10% and 2.5%; and local strong count no greater than 75% for every tile with at least 64 pixels.
All expected pixels must be finite. The owner must be valid in the same generation, with a positive
source sequence preceding the candidate and a capture timestamp no later than the candidate's.
Elapsed time and callback count do not expire it; reuse never advances its owner. Cumulative content
change is compared against the retained real-inference input. The client cannot duplicate Apollo's DDup
present-ID/dirty-rectangle/route gates without new host metadata, so decoded-pixel arbitration is
the documented client boundary.

Host-assisted exact/damage reuse, foreground-window ROI, subtitle detection/local-plane
conditioning, direct/full-resolution `R32F`, and a blind 2x-by-2x 11-step warp map are not part of
this acceptance run. They remain separate deferred experiments. A future host metadata path must be
versioned and advertised; its absence or malformed data must retain compatibility with original Sunshine and
Apollo by using local arbitration or full inference.

Raw P2/P98 range and normalized temporal depth exist only to classify geometry changes. An ordinary
geometry-only candidate requires two consecutive qualifying valid updates: the first holds the raw
camera mean, geometry baseline, reliable normalized depth, model-input owner, and scene-cut owner as
one coherent comparison tuple while live range and immediate temporal depth continue to update. Its
finite noncollapsed current raw field still publishes through the existing shot camera; the second
update either confirms the cut and coherently advances all owners or clears the pending state. The
normalized reliable-depth texture is physically promoted at the beginning of the next actual
inference, after the scalar decision and before the new comparison; reuse does not dispatch and
cannot alter it. An invalid next result may finish that already-authorized copy but cannot authorize
its own history advance. If no valid camera exists after a collapsed cut, the first later usable field may
reacquire it even while the comparison-history tuple remains held. A qualified appearance proposal
with depth corroboration cuts immediately. Only the first supported-to-structureless update and first geometry-confirmation
observation hold reliable history; ordinary preserved exposure, persistent-low structure, and a
same-scene return advance it. Invalid raw fields advance neither live nor reliable state. Geometry
and appearance rearm independently after two qualifying valid updates. The source-aligned raw
`R32F` sample is not spatially prefiltered or rounded through a half-float target. For raw sample
`d`, shot mean `m`, and graph scale `s`, the strict path computes `c = (d - m) / s`, then:

```text
curve(c) = 0.75 * expm1(c / 0.75)                    when c < 0
           c                                         when 0 <= c <= 1
           1 + 0.5 * log1p((c - 1) / 0.5)           when c > 1
requested = 1.75 * 0.00375 * curve(c)
parallax = requested / fourth_root(1 + (requested / 0.04)^4)
```

Vertical `2/W` upper/lower envelopes with a `0.75/0.25` share and a horizontal `0.5/W` least
majorant make the field contractive. The host-exact at-most-11-update shader solves the unique
inverse on the 1x depth lattice and stores signed offsets in an `RG16F` seed. At each texel of a
2x-horizontal x 1x-depth `RG16F` target, a refinement shader bilinearly samples that seed,
reconstructs both source positions, and applies one more paired-eye fixed-point correction against
the conditioned `R32F` field. Full-width packed SBS remains one draw with one refined-map lookup and
one color lookup per output pixel. Near-identical reuse retains both caches and reruns none of these
stages. A failed raw transaction, conditioner, seed, refinement, target, or compose draws current
color flat and preserves the last reliable history owner; it never exposes the seed alone or old
geometry on the failed frame. Direct/full-resolution `R32F` and blind 2x-by-2x 11-step inverse maps
remain deferred experiments.

Inference is uncapped: there is no target FPS, thermal cadence reduction, or fixed idle interval
after LiteRT completes. Low GPU priority controls queue priority, not inference cadence. The atomic
single-flight claim spans capture, native infer/reuse arbitration, result-mailbox ownership,
renderer adoption, and real-inference history commit, so at most one depth transaction exists and
decoded callbacks coalesce to the newest frame while it is occupied. Thermal status is telemetry,
not a scheduling input.

Presentation does not continuously redraw retained output. The GL thread coalesces and drains
pending `SurfaceTexture` callbacks while arbitration is busy, but a drain that adopts no newer
result performs no SBS draw and no EGL swap. Each newly adopted real pair or accepted reuse is
rendered directly into the default framebuffer and swapped once; SceneCore retains that submitted
buffer until the next adoption. There is no packed SBS offscreen cache or repeated cache blit. The
active full-resolution color-slot lease remains owned until the next adoption supersedes it.
If a newer decoded buffer exists and the retained pair becomes strictly older than 250 ms, a single
lifecycle/generation-guarded deadline requests a draw of current OES color duplicated flat. Further
callbacks keep that safe flat output current until fresh depth arrives; the fallback does not reset,
advance, or release the in-flight depth transaction or any temporal history. With no newer latch,
the retained pair does not expire.

### Offline raw-coordinate calibration and whole-clip evaluation

The September 2026 calibration uses all 192 saved original-ZipDepth and host-hybrid predictions
from eight clips. It does not normalize either producer per frame. For each clip it exact-area
resizes host depth to the ZipDepth graph, computes each producer's arithmetic mean on frame zero,
then fits one through-origin graph scale over every centered pixel in every paired frame:

```text
(zipRaw - zipFirstFrameMean) / fittedScale
    ~= (areaResize(hostRaw) - hostFirstFrameMean) / 2.25
```

Run the reproducible fit from the repository root:

```powershell
python .\tools\calibrate-zipdepth-v2-scale.py
python -m unittest .\tools\tests\test_calibrate_zipdepth_v2_scale.py
```

The checked production values and pooled diagnostics are:

| ZipDepth graph | Frames | Fitted scale | Polarity cosine | Relative coordinate RMSE |
|---|---:|---:|---:|---:|
| `672 x 384` | 96 | `0.0486444901` | `0.950544` | `0.310590` |
| `896 x 384` | 48 | `0.0470707120` | `0.882988` | `0.469396` |
| `928 x 384` | 48 | `0.0542149100` | `0.940619` | `0.339465` |

All pooled and per-clip coordinate dot products are positive, confirming high-is-near polarity.
The materially different scales prove that a single ZipDepth output multiplier would not compare
the three aspect graphs fairly. The residuals also matter: affine scale alignment does not turn the
original ZipDepth head into the host hybrid model, so quality must still be evaluated on complete
clips rather than inferred from calibration alone.

The earlier `all192_raw_prefiltered.json` replay measured the retired normalized-depth/Bestv2
candidate. It explains why the separable prefilter was retained for the first A/B build, but its
fold, shear, and reconstruction numbers are not acceptance results for the new raw V2 coordinate.
The production raw V2 path now follows the host contract directly without that client-only filter;
the historical replay remains useful only as provenance for the retired choice.

The production baseline is the exact 1x `RG16F` seed plus 2x-horizontal one-correction `RG16F`
refined cache. Future reruns must compare its output with an exact direct inverse reference and,
separately, deferred direct/full-resolution `R32F` and blind 2x-by-2x experiments. Record pointwise
container and slope violations, seed/refinement inverse residual or
nonconvergence, boundary clamps, source-coordinate MAE/p95/p99/max, RGB-edge-weighted and silhouette
stair-step error, non-cut contour jitter, changed-edge lag split by infer/reuse, two-update cut
recovery, compose GPU time, cadence, and 15-minute Galaxy XR clocks/thermals. Spearman, ordinal
accuracy, and edge-aware depth agreement remain model metrics; they cannot by themselves prove
reprojection quality.

Pixel count alone does not predict latency. For the `672x384` graph, the exact seed costs 258,048
fragments, 0.98 MiB, and at most 5,677,056 `R32F` parallax samples per real depth update. The
`1344x384` refined target adds 516,096 fragments / 1.97 MiB and, per update, 516,096 `RG16F` seed
samples plus 1,032,192 `R32F` parallax samples: 1,548,288 added logical texture lookups. The two
maps occupy 2.95 MiB; total strict-build parallax sampling is at most 6,709,248, versus 22,708,224
for a blind 2x-by-2x 11-step solve. The full-resolution compose remains one refined-map and one color
sample per output, and reuse rebuilds neither map. At the previously observed 17-24 real
inferences/s, status-4 thermal state, and 69-98% GPU-busy peaks, this analytical saving still needs
sustained device measurement. The refined map spans about `2.86 x 5.63` source pixels per texel at
4K, so the A/B should target moving diagonal and silhouette reconstruction specifically. Changing
to `RG32F` is lower priority: half-float
displacement rounding contributes less than 0.06 source pixel at the `+/-0.04` limit and 3840-pixel
eye width.

## 1. Focused JVM tests

The repository wrapper runs the established focused Client SBS contract suite without touching an
emulator or headset:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass `
    -File .\tools\client-sbs-eval.ps1
```

For this raw-V2 migration, run the expanded direct command below as well; it includes the new
coordinate-calibration and strict-failure guardrails in addition to the family-archive contract:

```powershell
.\gradlew.bat :app:testNonRoot_gameDebugUnitTest `
    --tests "com.limelight.sbs.ClientSbsFrameSlotsTest" `
    --tests "com.limelight.sbs.ClientSbsGpuDepthShadersTest" `
    --tests "com.limelight.sbs.ClientSbsGpuDisparityShadersTest" `
    --tests "com.limelight.sbs.ClientSbsV2CoordinateContractTest" `
    --tests "com.limelight.sbs.ClientSbsGpuSceneCutDetectorTest" `
    --tests "com.limelight.sbs.ClientSbsGpuSceneCutShadersTest" `
    --tests "com.limelight.sbs.ClientSbsNearIdenticalPolicyTest" `
    --tests "com.limelight.sbs.ClientSbsShotCutPolicyTest" `
    --tests "com.limelight.sbs.ClientSbsGpuTimerTest" `
    --tests "com.limelight.sbs.ClientSbsTemporalTuningTest" `
    --tests "com.limelight.binding.video.DecoderModeTransitionGateTest" `
    --tests "com.limelight.utils.ClientSbsDepthInputShapeTest" `
    --tests "com.limelight.utils.ClientSbsGpuInferenceEngineTest" `
    --tests "com.limelight.utils.ClientSbsOutputSurfaceValidationTest" `
    --tests "com.limelight.utils.ClientSbsModelManifestTest" `
    --tests "com.limelight.utils.ClientSbsZipDepthV2CalibrationTest" `
    --tests "com.limelight.utils.ClientSbsModelArchiveTest" `
    --tests "com.limelight.utils.ClientSbsPackagedModelArchiveTest" `
    --tests "com.limelight.utils.Stereo3DRendererSchedulingTest" `
    --tests "com.limelight.utils.Stereo3DRendererStrictFailureContractTest" `
    --tests "com.limelight.utils.ShaderUtilsTest" `
    --tests "com.limelight.ui.XrStreamPresenterLayoutTest" `
    --tests "com.limelight.ui.XrStreamPresenterTransitionTest" `
    --tests "com.limelight.ui.XrViewStateStoreTest" `
    --console=plain
```

The tests cover:

- `ClientSbsFrameSlotsTest`: matched color-frame ownership and legal slot transitions.
- `ClientSbsGpuDepthShadersTest`: packed Float32 depth reads, complete-field validity, exact raw
  arithmetic-mean reduction, private cut-only P2/P98 analysis, two-update history gates, and
  overflow-safe GPU histogram math.
- `ClientSbsGpuDisparityShadersTest` and `ClientSbsV2CoordinateContractTest`: the graph-scaled raw
  camera coordinate, host far-exp/linear/near-log curve, fixed `1.75 * 0.00375` gain, odd `+/-0.04`
  fourth-root container, four serial-line compute passes, and CPU-reference contract bounds.
- `ClientSbsGpuSceneCutDetectorTest`: real-inference commit/reuse-discard history, exact packed-input
  ownership, authenticated two-slot decision records, and GPU transaction state.
- `ClientSbsGpuSceneCutShadersTest`: rectangular model-input luma/median-max-RGB reduction,
  numerical gain/offset/nonlinear-clipped-exposure rejection, reliable local ordinal structure,
  same-histogram structural cuts, fused near-identical evidence, and GPU-only evidence-word
  contracts.
- `ClientSbsNearIdenticalPolicyTest`: Apollo's inclusive pixel/tile thresholds, rectangular partial
  tiles, finite/complete evidence validation, authenticated decision encoding, cumulative one-to-four
  callback-step limit, and strict sub-100-ms owner age.
- `ClientSbsShotCutPolicyTest`: numerical model-grid cut boundaries, startup blocking, two-update
  ordinary-geometry confirmation, immediate qualified appearance cuts, coherent reliable-history
  ownership, one-update photometric recovery, independent geometry/appearance rearming, refractory
  relative-spike escape, and rejection of invalid transaction evidence.
- `ClientSbsGpuTimerTest`: nonblocking per-stage query rings, unsigned results, and disjoint-sample
  rejection.
- `ClientSbsTemporalTuningTest`: Apollo-equivalent temporal response and spatial-scale mapping at
  different client and negotiated host cadences.
- `DecoderModeTransitionGateTest`: IDR/serial and presentation-timestamp gating around decoder
  output-surface transitions.
- `ClientSbsDepthInputShapeTest`: deterministic nearest-multiplicative-aspect selection across the
  three ZipDepth buckets.
- `ClientSbsGpuInferenceEngineTest`: compiler-cache identity, fixed Low GPU priority, infer/reuse
  disposition validation, and the process-wide single-model ownership slot.
- `ClientSbsOutputSurfaceValidationTest`: exact `2W x H` EGL realization and per-eye GL limit
  validation, including packed widths larger than the per-viewport limit.
- `ClientSbsModelManifestTest`: exact ZipDepth graph identities, hashes, fixed tensor contracts,
  dimensions, buffer sizes, nearest-aspect routing, and legacy-ID migration.
- `ClientSbsZipDepthV2CalibrationTest`: every production graph carries its independently fitted raw
  scale and retired uncalibrated graphs cannot enter V2 geometry.
- `ClientSbsModelArchiveTest`: flavor-neutral solid TAR/XZ entry extraction and missing-entry
  rejection.
- `ClientSbsPackagedModelArchiveTest`: every packaged ZipDepth entry matches its manifest SHA-256,
  and retired family archives are absent from `nonRoot_game` assets.
- `Stereo3DRendererSchedulingTest`: stale-result overlap ownership, post-swap capture ordering,
  explicit urgent-display `SurfaceTexture` callback routing/token invalidation, nonblocking callback
  Looper teardown, packed-viewport limits, and Stats epoch boundaries.
- `Stereo3DRendererStrictFailureContractTest`: production contains only the strict V2 programs;
  retired Bestv2/probe shader state is absent, raw `R32F` depth feeds the conditioner directly
  while both inverse targets remain `RG16F`, the exact seed cannot become a fallback, and any
  strict V2 stage failure reaches flat composition only.
- `ShaderUtilsTest`: exact source-cell area downsampling, bilinear-upscale selection, reflected
  per-cell portrait mapping, per-cell HDR conversion, per-stream fixed-shape packing, the 11-step
  unique seed inverse, and the one-correction refinement contract.
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

## 3. Galaxy XR native GPU and disparity shader gates

The physical-device smoke class creates real shared EGL contexts, uploads a deterministic packed
Float32 gradient, invokes each fixed-shape ZipDepth graph through native LiteRT, waits for the
returned GL fence, and rejects missing, non-finite, zero, or flat output. Its three graph/bucket
tests cover `672 x 384`, `896 x 384`, and `928 x 384` through the production packed-GL native path.
The separate disparity class compiles and dispatches the production four-pass conditioner on those
same three shapes in a real GLES 3.1 context. Sustained streaming remains a separate acceptance
gate.

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

### Source-exposure and history regression

`com.limelight.utils.ClientSbsSourceOrdinalInstrumentedTest` exercises a real 3840 x 2160
SurfaceTexture/OES image through production model preprocessing, fused RGB/ordinal packing,
scene-cut comparison, and GPU history commit. It applies gamma before resizing textured source
regions. Its former max-of-area-RGB control must propose an appearance cut; the source-point
ordinal must retain the exposure veto with zero reversed orderings. Both paths use deterministic
undithered quantization and must produce identical model RGB. This verifies the composed shader
path rather than treating a model-grid exposure transform as equivalent to source exposure.

Run it with the manual procedure below by selecting that class in `$Classes`. It needs GLES 3.1
and external-OES support, but does not initialize LiteRT. The companion
`ClientSbsGpuSceneCutDecisionReasonInstrumentedTest` and
`ClientSbsGpuReliableHistoryParityInstrumentedTest` classes in `com.limelight.sbs` check decision
ownership and accepted-history behavior. An emulator pass establishes shader correctness only;
it does not establish Galaxy XR OpenCL interoperability, PQ decoder behavior, or live performance.

### Data-preserving manual procedure

```powershell
if (-not $env:ANDROID_SDK_ROOT) { throw "Set ANDROID_SDK_ROOT first" }
if (-not $env:ANDROID_SERIAL) { throw "Set ANDROID_SERIAL to the Galaxy XR adb serial" }

$Adb = Join-Path $env:ANDROID_SDK_ROOT "platform-tools\adb.exe"
$MainApk = "app\build\outputs\apk\nonRoot_game\debug\app-nonRoot_game-arm64-v8a-debug.apk"
$TestApk = "app\build\outputs\apk\androidTest\nonRoot_game\debug\app-nonRoot_game-debug-androidTest.apk"
$Classes = @(
    "com.limelight.utils.ClientSbsGpuInferenceEngineInstrumentedTest",
    "com.limelight.sbs.ClientSbsGpuDepthProcessorInstrumentedTest#rawMeanLatchesPerShotAndInvalidFieldPublishesNotReady",
    "com.limelight.sbs.ClientSbsGpuDisparityProcessorInstrumentedTest#allProductionShapesCompileAndDispatchOnDevice",
    "com.limelight.utils.ClientSbsContractiveRenderInstrumentedTest#contractiveWarpSeedRefinementAndPackedComposeRenderOffscreen"
)

& $Adb -s $env:ANDROID_SERIAL get-state
.\gradlew.bat `
    :app:assembleNonRoot_gameDebug `
    :app:assembleNonRoot_gameDebugAndroidTest `
    --console=plain

& $Adb -s $env:ANDROID_SERIAL install -r $MainApk
if ($LASTEXITCODE -ne 0) { throw "Main APK update-install failed; do not uninstall the app" }
& $Adb -s $env:ANDROID_SERIAL install -r $TestApk
if ($LASTEXITCODE -ne 0) { throw "Test APK update-install failed" }

$TestsPassed = $true
foreach ($Class in $Classes) {
    $TestOutput = @(& $Adb -s $env:ANDROID_SERIAL shell am instrument -w -e class $Class `
        com.limelight.moonlight3ddebug.test/androidx.test.runner.AndroidJUnitRunner 2>&1)
    $TestExit = $LASTEXITCODE
    $TestOutput | ForEach-Object { Write-Host $_ }
    $TestsPassed = $TestsPassed -and ($TestExit -eq 0) -and [bool]($TestOutput -match '^OK \(')
}

& $Adb -s $env:ANDROID_SERIAL uninstall com.limelight.moonlight3ddebug.test
if (-not $TestsPassed) { throw "Client SBS GPU smoke or disparity gate failed" }
```

A pass requires all of the following:

- Native LiteRT initializes with OpenCL/OpenGL interoperability.
- Archive validation checks the one ZipDepth TAR/XZ asset, all three complete TAR entries, each
  extracted byte count, and all three final SHA-256 values. It also checks that retired family
  archives are absent from the APK. Only manifest-qualified current graphs may remain staged in
  `code_cache/client-sbs-model-assets`; already verified aspect buckets are retained.
- Each original-Base ZipDepth graph reports `163/163` operations in exactly one OpenCL partition;
  partial delegation or more than one partition is a failure.
- `LiteRtCompiledModelIsFullyAccelerated()` succeeds, and the log confirms CL/GL buffer
  interoperability for all three buckets.
- Each bucket reports its expected logical name, extracted SHA-256, and fixed input/output
  layouts. No runtime tensor resize occurs.
- Exactly two native input/output tensor slots are present.
- Public tensor strides are 12-byte RGB input and 4-byte depth output.
- The deterministic output for every bucket is finite, positive, and non-flat.
- Extraction/verification, initialization, and warm invocation latency are recorded separately
  for all three buckets.
- Both slots exchange mandatory input-ready, output-ready, and final output-consumed fences without
  aliasing, reuse-before-consume, or teardown errors.
- The dedicated disparity gate compiles and dispatches the four-pass conditioner at all three
  production shapes without a GL error. Its repeated-dispatch timing is a hardware gate for the
  conditioner only, not full SBS composition or LiteRT inference.
- The raw V2 state gate verifies the arithmetic raw mean, first-shot latch, ordinary within-shot
  updates, and strict invalid-field ownership in a real GLES 3.1 context. One injected NaN must
  report `1023 / 1024` valid samples while clearing current geometry readiness and preserving the
  reliable shot owner.
- The offscreen render gate compiles and executes the production exact 1x seed, 2x-horizontal
  one-correction refinement, and packed compose fragments through both `RG16F` maps and an `RGBA8`
  target, then validates both eye directions by readback. This covers the GLES route that the
  compute-only disparity gate cannot exercise.

The disparity gate passed on SM-I610. Initialization / first dispatch / mean repeated dispatch over
20 dispatches with a final `glFinish()` measured `470.183 / 9.041 / 1.336 ms` at `672 x 384`,
`35.339 / 8.880 / 1.579 ms` at `896 x 384`, and `29.746 / 8.318 / 1.620 ms` at `928 x 384`. The
first initialization includes one-time shader compilation. These figures do not include the
inverse-map render, packed draw, model inference, decoder, or XR composition.

The raw V2 state gate passed on SM-I610 on 2026-09-04. A deterministic `32 x 32` field produced the
expected arithmetic shot/current means, an ordinary offset changed only the current mean, and one
injected NaN published current-depth invalid, history-not-advanced, geometry-not-ready, and exactly
`1023 / 1024` valid samples with no GL error.

The offscreen render gate also passed on SM-I610 at `672 x 384 -> 1344 x 384`; a constant positive
parallax field produced the expected distinct center-gradient samples (`119 / 135`) for the left and
right eyes with no GL error. This is a shader/format/orientation gate, not a live-stream timing result.

### Historical retired-family measurements

Every canonical DA-V2 bucket delegated its 683-operation core in one `LITERT_CL` partition and used
`AUTOMATIC_FP16`. The edge-rich gate returns finite, non-flat depth and the full-tensor parity values
in the historical contract table above. With FP16-stored weights, 20 discarded warm-ups, 100
measured invocations, normal priority, and thermal status 0, isolated mean LiteRT wall time is
16.552 ms at `322 x 182`, 15.725 ms at `350 x 154`, and 15.864 ms at `434 x 126`. Every change from
the prior Float32-stored/FP16-compute means is below 1%, so weight storage is a size optimization
rather than a compute optimization. The corresponding transformed-FP32 means are 28.177 ms,
26.782 ms, and 26.920 ms.

The retired corrected Quality graphs measured 18.628 ms at `350 x 196`, 18.367 ms at `392 x 168`,
and 18.581 ms at `490 x 140`; the original non-C4 959-operation graph's smooth-input FP16 pass,
flat edge-rich result, 46–50 ms safe-FP32 runs, and scheduling/fence tails remain historical bisect
evidence. None is a current production acceptance baseline. The family archive now exists only in
`tools/model-sources/retired-client-sbs-archives/`.

Every FP16-stored MiDaS bucket reported complete delegation as a 234-operation graph, CL/GL
interoperability, and finite, non-flat, repeatable output. The same controlled 20-warm-up,
100-sample run measured:

| Shape | LiteRT median / p95 | Output-ready median / p95 | Isolated inference rate |
| --- | ---: | ---: | ---: |
| `352 x 192` | 10.293 / 10.473 ms | 10.909 / 11.164 ms | 91.20/s |
| `384 x 160` | 9.703 / 9.872 ms | 10.332 / 10.557 ms | 96.23/s |
| `448 x 128` | 9.430 / 9.593 ms | 10.051 / 10.279 ms | 99.05/s |

The corrected DepthART graphs were measured separately with the production low-priority hint,
20 discarded warm-ups, 100 measured invocations, and thermal status 0:

| Shape | LiteRT median / p95 | Output-ready median / p95 | Mean LiteRT wall |
| --- | ---: | ---: | ---: |
| `672 x 384` | 27.760 / 28.028 ms | 28.385 / 28.740 ms | 27.758 ms |
| `928 x 384` | 38.258 / 38.486 ms | 38.904 / 39.149 ms | 38.249 ms |

The deterministic gradient and four representative `672 x 384` real inputs all produced complete,
finite, non-flat FP16 output. Against Galaxy FP32 execution of the same corrected graph, the real
frames measured Pearson correlation `0.99639`–`0.99972` and affine range-normalized RMSE
`0.83%`–`2.28%`. A real `928 x 384` probe measured `0.99711` and `2.10%`, respectively. These are
precision diagnostics, not substitutes for the ground-truth quality suite or sustained thermal
testing.

### Current ZipDepth isolated measurements

The original-Base ZipDepth graphs were measured with the production low-priority hint, 20 discarded
warm-ups, 100 measured invocations, thermal status 0, and complete one-partition OpenCL delegation:

| Shape | LiteRT median / p95 | Output-ready median / p95 |
| --- | ---: | ---: |
| `672 x 384` | 10.089 / 10.310 ms | 10.670 / 10.919 ms |
| `896 x 384` | 12.991 / 13.189 ms | 13.599 / 13.815 ms |
| `928 x 384` | 13.308 / 13.488 ms | 13.924 / 14.128 ms |

Representative real-frame FP16 output retained Pearson `0.9976`–`0.9992` and affine
range-normalized RMSE `1.16%`–`1.43%` against FP32 across the three shapes. These numbers exclude
decoder, input pack, depth processing, reprojection, and XR composition; they do not establish
sustained stream thermals.

For historical 16:9 model-selection context, use controlled LiteRT call-wall medians rather than
sequential live-stream windows:

| Model | Shape | Controlled LiteRT wall median |
| --- | ---: | ---: |
| DA-V2 | `322 x 182` | 16.532 ms |
| MiDaS | `352 x 192` | 10.293 ms |
| ZipDepth Base | `672 x 384` | 10.089 ms |

All used complete OpenCL delegation, automatic FP16, thermal status 0, 20 discarded warm-ups, and
100 measured invocations, but the retained DA-V2/MiDaS figures used normal priority while the
ZipDepth figure used production low priority. `LiteRtRunCompiledModel()` wall is still a
CPU-observed runtime/driver call, not pure GPU kernel time. Live streaming adds contention from
decode, GLES, XR composition, clocks, and thermal state, so sequential live windows cannot rank the
models unless those conditions are controlled. The retired `350 x 196` Quality graph measured
18.615 ms under the same conditions and remains historical comparison data only.

The old `256 x 256` MiDaS measurement of 10.79–18.37 ms is retained only as historical data; do
not mix it into the rectangular-bucket range.

### Sustained production-model benchmark

The smoke timings above use only four invocations per fixed-shape graph and predate the controlled
wake-lock benchmark. Do not use them for current throughput decisions. The dedicated benchmark
class is separate from the three-graph/one-family smoke suite, holds a bounded partial wake lock
to prevent off-head
system suspend, discards 20 warm-ups, and records 100 fence-complete invocations per result.

Run one production bucket at a time so the logged model, shape, delegation, thermals, and latency
remain attributable:

```powershell
$Benchmark = "com.limelight.utils.ClientSbsProductionGpuBenchmarkInstrumentedTest"
& $Adb -s $env:ANDROID_SERIAL shell am instrument -w `
    -e class "$Benchmark#productionZipDepthBase672x384" `
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
Moonlight 3D already asked LiteRT for OpenCL FP16 computation; that model's Float32 stored weights
do not imply Float32 GPU arithmetic. The Community graph also requires external ImageNet
normalization, which this isolated graph benchmark performs before the timed window. That was a
MiDaS-specific historical decision and is not the current ZipDepth production contract.

The retired Float32-stored production `352 x 192` MiDaS graph measured 10.247 ms median / 10.476 ms
p95 LiteRT call wall and 10.875 ms median / 11.110 ms p95 output-ready, or 91.76 isolated
inferences/s. FP16-stored weights now measure 10.293 / 10.473 ms and 10.909 / 11.164 ms,
respectively, or 91.20/s. The sub-0.5% differences are noise; the benefit is the 49.8% model-byte
reduction, not faster FP16 compute.

The temporary async and direct-external PHWC4 LiteRT I/O probes have been removed. Their historical
results follow; the failed external path is not a supported runtime option. The OpenCL async
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
    ClientSbsGpu:I ClientSbsGpuSmoke:I ClientSbsDisparity:I LiteRT:I tflite:I '*:S'
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

- `Depth backend` is `LITERT_OPENCL_FP16_GL_IO` for ZipDepth, never a managed, CPU, or QNN backend.
  Confirm it under the live decode/reprojection workload.
- `Depth inference policy` reports `Uncapped | single flight | newest frame when free`; there is no
  FPS ceiling or inference-gap row. `Android thermal status` is informational only.
- The ZipDepth logical graph and fixed input dimensions match the source aspect: `672 x 384`,
  `896 x 384`, and `928 x 384` for 16:9, 21:9, and ultrawide-nearest sources. Other aspects use the
  documented stream-fixed nearest-bucket routing.
- `Latch / infer / reuse / output FPS` shows decoder-consumer, real-inference adoption,
  near-identical adoption, and final SBS submission cadence. Output should approximately track
  infer plus reuse; latch may be higher while the single-flight transaction is occupied.
- `Near-identical reuse` reports accepted reuse as a percentage of owner-eligible decisions. Static
  or nearly static material should exercise it; sustained motion or edits should force inference.
- `Reuse rejects` attributes rejected candidates to content or invalid ownership/evidence.
  Retired age/frame-gap and Exact fields must not appear.
- `Decision read avg / max` measures the existing CPU wall around validation and the authenticated
  32-byte map/copy/unmap. After the first candidate, immutable buffer/range checks are cached; use
  this row to detect an Adreno cross-context synchronization stall rather than inferring cost from
  the record's small byte count.
- `LiteRT wall avg / max` remains bounded for the selected ZipDepth graph and excludes reuse. `Depth
  result age avg / max` is the real inference pair's capture-to-adoption age. Near reuse has no
  age or callback-count expiry; it remains relative to the retained real-inference input.
- The four true GL GPU averages populate without stalling: model-input pack, matched-color copy,
  depth/cut state, and SBS compose.
- The `Faults` row keeps `color_busy`, `flat`, invalid raw transactions, and collapsed diagnostic cut
  ranges visible; they remain zero during ordinary content. Expected reuse, latest-frame replacement, callback coalescing, and
  ordinary single-flight busy events are not fault counters.
- `V2 state` distinguishes renderer readiness, complete current-field validity, and whether the
  reliable comparison-history tuple advanced. A confirmation-held but valid field must report `ready yes | current
  valid yes | history hold`; history hold alone is not a flat-output condition.
- `V2 coordinate` reports fixed pop `1.75`, `shotMean`, and `currentMean`. The adjacent cut evidence,
  decision, and count rows explain a relatch. Retired normalized stretch/recenter/subject/Bestv2
  anchor and adaptive-pop values must not appear as current production state.
- For HDR input, `Client SBS HDR path` reports either preserved `RGB10_A2`/`RGBA16F` output or the
  explicit BT.709/sRGB tonemap. SDR reports BT.709/SDR. Switching back to a direct mode clears
  Client SBS metadata.
- `Stereo compose path` reports
  `RG16F 1x 11-iteration seed + 2x-horizontal x1 refinement, packed single draw`.
  Startup must log `Client SBS contractive disparity: R32F WxH`, initialize
  `seed=WxH refined=2WxH`, distinguish exact-seed and seeded-refinement validation, and report
  `warp-map compose validated`. A conditioner, seed fixed-point, refinement, `RG16F`-target, or
  compose failure must report a flat path and duplicate current mono color. Any live seed-only,
  legacy cached-probe, or direct-probe path is a regression.
- App CPU core-equivalent load, device GPU busy/clock, and Android thermal status remain plausible.
  The pane intentionally has no NPU row or custom CPU/GPU temperature probes; Android 14 exposes no
  trustworthy public per-app NPU-utilization API. The production backend is GPU.

There is deliberately no image/tensor PBO readback, free CPU tensor-buffer wait, managed result
queue, or Java postprocess worker in this path. Near-identical arbitration maps only its
authenticated 32-byte decision record after the input fence; it never maps model input or output.
A stats row for one of the removed stages is stale and should be removed, not interpreted as a
zero-latency operation.

### Read the latency domains correctly

The lean panel intentionally omits CPU command-submission, worker/JNI invoke, and native dependency
submission timings. The retained latency fields are:

- `LiteRT wall avg / max`, which brackets `LiteRtRunCompiledModel()`. It includes runtime overhead
  and any blocking visible to that API; LiteRT does not expose its OpenCL event, so this is not a
  pure accelerator duration.
- `Decision read avg / max`, which brackets only the candidate's authenticated 32-byte
  map/copy/unmap plus one-time validation. It exposes synchronization wall time, not transfer
  bandwidth, and is zero/unavailable when no eligible candidate was sampled.
- `Depth result age avg / max`, which measures the real inference pair's capture-to-adoption freshness
  rather than one isolated GPU stage. It does not average the retained-depth age of reuses.
- Four GL GPU averages from nonblocking `GL_EXT_disjoint_timer_query`: model render + color cut +
  pack, matched-color copy, depth/cut-state processing, and stereo raw-depth V2
  conditioner/inverse-map/packed draw. These measure actual GLES completion but cannot include
  LiteRT's OpenCL execution.

Query availability is polled without waiting and clock-disjoint samples are discarded. The panel
does not expose GPU maxima or sample counts. Record the active warp path with each capture; a future
direct/full-resolution `R32F` experiment is not directly comparable to the exact-seed/refined-cache
production route. SceneCore exposes no final compositor-present timestamp.

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
The performance-logging switch and periodic background performance lines are retired. Use the
visible Stats panel for rates, reuse decisions, wall timings, GPU stage samples, faults and health;
use operational logs for startup, transitions, errors and bounded playback recovery. Closing Stats
disables its timer queries, health copies/polling, detailed counters and formatting. Reopening starts
fresh CPU, Client-SBS, health and GL timer windows; hidden time is not part of the first sample.

## Sustained comparison

For optimization decisions, use the same moving host clip, resolution, frame rate, codec, HDR mode,
and headset power state for Normal and ZipDepth Client SBS runs. Keep one concrete ZipDepth shape
stable for the entire capture. Collect at least 15 minutes after warm-up and record:

- Stats-panel screenshots at the start, middle, and end.
- Latch/infer/reuse/output FPS, eligible-decision reuse percentage, decision-read wall
  average/maximum, LiteRT call-wall average/maximum, and depth-age average/maximum.
- Each of the four GL GPU completion averages, kept separate from LiteRT call-wall latency.
- Exceptional `color_busy` and `flat` counts; do not collect expected reuse/skip/coalescing behavior
  as counter noise.
- Renderer readiness, complete current-depth validity, history advance/hold, private cut-range width,
  fixed pop, shot/current raw means, and causal cut state.
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
model-input rendering, color copy, depth/profile, or conditioned SBS composition. Retained output eliminates
draws with no adoption, while accepted near-identical reuse additionally removes LiteRT and depth
postprocess work for the bounded current-color/cached-depth update. Judge thermal benefit together
with reuse rate and visual stability; reuse raises output cadence without raising real-inference
cadence.

## Failure classification

- **Backend `Unavailable` at startup:** inspect native library loading, model contract, full GPU
  delegation, and CL/GL interop logs.
- **Wrong static bucket selected:** verify the decoded source aspect supplied when the renderer was
  created and the nearest-multiplicative-aspect selector. The graph must not
  change midstream.
- **A ZipDepth bucket delegates fewer than 163/163 operations, is not completely accelerated, or
  creates more than one partition:** reject it and verify
  the archive manifest mapping, extracted SHA-256, and rank-4 static graph. CPU fallback is not a
  valid result.
- **An isolated early inference tail appears despite complete delegation:** retain the sample,
  correlate it with initialization, scheduling, and thermal state, then judge sustained live-stream
  cadence. Do not misclassify a one-off tail as partial delegation when the model's complete
  delegation and one-partition contract are confirmed.
- **Finite but flat model output:** reproduce with the deterministic native smoke test before
  changing the fixed raw V2 coordinate.
- **Any raw texel is NaN/Inf/negative:** the complete current geometry transaction must be rejected,
  current color must be flat, and every reliable history owner must remain unchanged. Do not weaken
  this to partial-pixel validity.
- **Finite non-flat depth but flat stereo:** inspect current-depth/V2 validity, persistent-state and
  shot-camera readiness, selected graph scale, conditioner, inverse-map target, and packed compose
  logs. A valid `history hold` is renderable and must not by itself force flat output.
- **Depth smears across hard edits:** inspect the GPU scene-cut path. The cut word must stay
  GPU-resident and paired with the same tensor/color slot.
- **Near-identical reuse stays at zero on a static scene:** confirm one valid real inference has
  established the owner, the generation is stable, and no native decision-buffer/range/map or
  authentication warning forced inference. Check the candidate count before changing thresholds.
- **Reuse persists through visible motion or a hard edit:** treat it as a correctness failure. Verify
  the exact packed-input comparison, finite/complete evidence gates, literal global/local bounds,
  fixed retained reference, monotonic source identity/capture time, and decision token/cookies. Do not relax scene-cut
  behavior to conceal it.
- **Good depth with lag:** compare LiteRT wall, depth age, and latch/infer/reuse/output FPS; check
  the exceptional `color_busy` counter before changing model resolution.
- **GPU remains high while output FPS is low:** compare the four GLES completion averages with
  LiteRT wall and GPU busy/clock. A packed output blit or a swap on every latched decoder frame means
  the removed cache-repeat path has returned.
- **One GLES region is slow:** optimize its corresponding GL GPU average. Do not treat LiteRT call
  wall as a pure GPU timer.
- **Stereo compose reports a seed-only or legacy cached/direct probe path:** this is a regression.
  The live renderer must compile only strict raw V2 geometry and present flat after a conditioner,
  R32F-linear-sampling, either RG16F target, seed/refinement inverse, or packed-compose failure.
- **Edges still show serration on the contractive path:** inspect whole-clip slope/inverse residual
  and refined-cache reconstruction metrics before increasing model or map resolution. Record moving
  silhouette and diagonal evidence rather than judging one still frame.
- **HDR is washed out or clipped:** verify the actual window bits, selected color target, and stats
  HDR path. BT.2020/ST2084 must be advertised only for verified end-to-end high precision.
- **Normal/Host modes regress:** treat it as a surface-routing or lifecycle bug. Those paths must
  not depend on Client SBS runtime availability.
