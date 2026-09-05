<p align="center">
  <img src="./moonlight3d.svg" width="136" alt="">
</p>

<h1 align="center">Moonlight 3D</h1>

<p align="center">
  <strong>Everything you love on your PC, now in 3D.</strong><br>
  Watch, play, and work in immersive 3D on your Android XR headset.
</p>

> [!IMPORTANT]
> **The full Sunshine 3D + Moonlight 3D experience currently requires a Windows 11 PC with an NVIDIA GPU.**
> Sunshine 3D does not support AMD or Intel GPUs, software encoding, Linux, or macOS hosts.

<p align="center">
  <picture>
    <source media="(prefers-reduced-motion: reduce)" srcset="./docs/assets/readme/sunshine3d-moonlight3d-workflow.png">
    <img src="./docs/assets/readme/sunshine3d-moonlight3d-workflow.gif" width="760"
         alt="Sunshine 3D converts a flat PC scene, then either streams it to Moonlight 3D on Android XR or presents it directly to PC-connected AR glasses without Moonlight 3D.">
  </picture>
</p>

<p align="center">
  Stream to Moonlight 3D on Android XR, or let Sunshine 3D drive connected AR glasses directly.
</p>

<p align="center">
  <a href="https://github.com/dcatcher9/Apollo-3D"><strong>Set up Sunshine 3D →</strong></a>
  ·
  <a href="#quick-start">Quick start</a>
  ·
  <a href="#build-from-source">Build the APK</a>
  ·
  <a href="./docs/android-xr-sbs.md">XR architecture</a>
</p>

Moonlight 3D is an Android XR streaming client designed and tested with
[Sunshine 3D](https://github.com/dcatcher9/Apollo-3D). It combines Moonlight’s low-latency
streaming foundation with a spatial application library, in-headset session controls, native
side-by-side (SBS) presentation, and optional GPU depth conversion. Create 3D on the Windows PC or
on the headset without requiring game mods, player plug-ins, or application-specific stereo
support.

> **XR-only build:** the application requires Android XR spatial APIs. Samsung Galaxy XR is the
> validated hardware target; the x86_64 build exists for the Android XR emulator.

> **What “content-universal” means:** the AI path can process content visible to the supported
> Windows capture path. Protected or otherwise noncapturable surfaces remain outside the pipeline,
> and monocular depth is an estimate rather than authored stereo geometry.

## Popular use cases

| What you want to do | Best path and payoff |
|---|---|
| **🎬 Watch capturable browser or local video in 3D** | When Windows capture can see the decoded frames, choose Host 3D or Client 3D to convert the player output live |
| **🎮 Turn an existing flat PC game into 3D** | Use the PC GPU with Host 3D or the headset GPU with Client 3D—no game-specific stereo mod or profile required |
| **🖥️ Use a private spatial Windows desktop** | Stay in 2D for maximum text clarity or enable AI depth when useful; the virtual display negotiates landscape or portrait geometry, refresh rate, HDR state, and scale |
| **🎞️ Present native SBS games and media** | Select Raw SBS to preserve the source’s authored left/right views without estimating depth again |
| **⚡ Choose where the AI runs** | Move between Host 3D and Client 3D while keeping the same app library, controls, audio, and input loop |
| **👓 Drive tethered AR glasses directly** | Use Sunshine 3D’s local presenter for 2D or host-generated full SBS while bypassing network encode/decode |

## PC, Android XR, and AR glasses

The product boundary is intentionally simple: Sunshine 3D owns the PC; Moonlight 3D owns the
Android XR experience; directly attached AR glasses stay on the PC path.

```mermaid
flowchart TD
    SOURCE["Capturable Windows content"]
    SOURCE --> PC["PC · Sunshine 3D<br/>capture · optional Host 3D"]
    PC -->|"Encrypted mono or packed SBS"| XR["Android XR · Moonlight 3D<br/>decode · optional Client 3D"]
    PC -->|"Direct D3D11<br/>no Moonlight 3D or network"| GLASSES["PC-connected AR glasses<br/>2D · Host 3D full SBS"]
```

Direct AR output is a Sunshine 3D feature, not a Moonlight 3D client path. It is currently
video-only and supports 1920×1080 2D or 3840×1080 host-generated full SBS on an approved,
non-primary, non-cloned display. A remote XR virtual-display session that is connecting, active,
or retained for resume takes priority over the local glasses presenter. Windows audio remains on
its current default endpoint. See the
[Sunshine 3D local-glasses guide](https://github.com/dcatcher9/Apollo-3D/blob/master/docs/sbs-local-ar-glasses.md).

## Conversion and passthrough modes

| Mode | Where 3D is produced | Use when |
|---|---|---|
| **2D** | No 3D processing | You want a direct mono SceneCore panel with the lowest processing cost |
| **Client 3D** | Galaxy XR GPU using original ZipDepth Base with FP16-stored weights | The host sends mono video and the headset should create depth |
| **Raw SBS** | The source creates both views; Moonlight 3D splits them | The source renders packed left/right views inside a Virtual Display-backed session, which Raw SBS requires |
| **Host 3D** | Windows CUDA/TensorRT-capable NVIDIA GPU | Sunshine 3D should convert mono content before encoding |

Raw SBS presents stereo content that already exists; it does not estimate depth. Full packing
sends two complete eye views at double width, while Half packing keeps the selected stream width
and gives each eye half of its horizontal pixels.

Host 3D and Client 3D are the real-time 2D-to-3D paths. Raw SBS preserves stereo supplied by the
source, while 2D bypasses conversion entirely.

Original ZipDepth Base is the single Client 3D model family. Its three fixed short-384 aspect
graphs use FP16-stored weights while retaining Float32 GL tensors, so 16:9, 21:9, and ultrawide
streams share one model family without forcing every source through one distorted rectangle. See
[Client SBS evaluation](./docs/client-sbs-evaluation.md) for the exact contract.

## Quick start

1. Install and configure [Sunshine 3D](https://github.com/dcatcher9/Apollo-3D) with its bundled
   SudoVDA driver on the Windows PC, then run the host with administrator privileges.
2. Open `https://localhost:47990` on the PC. No account or Web UI login is required; keep the
   page ready for the **Enter PIN** pairing card.
3. [Build and install](#build-from-source) the current arm64 Moonlight 3D APK, or install a
   packaged build when one is available, on Galaxy XR.
4. Put the PC and headset on the same network for initial discovery.
5. Open Moonlight 3D and select the discovered PC, or add its IP address or hostname manually.
6. Select **Pair**. Moonlight 3D displays a four-digit PIN.
7. Enter that PIN in Sunshine 3D’s
   **Enter PIN** card.
8. Open the application library and launch **Virtual Display** for the complete resolution and
   Raw SBS workflow, or launch another configured application.
9. Start in **2D**, then select Client 3D, Raw SBS, or Host 3D from the in-headset dock.

A regular Sunshine or Apollo host can provide an ordinary mono stream for both 2D and Client 3D,
including standard pairing, launch, resume, and end-session behavior. Raw SBS is available when the
selected launch is backed by a compatible virtual display. Sunshine 3D is required for Host 3D,
live host quality controls, and host depth telemetry/debugging.

## Scene-aware adaptive pop and zero plane

The PC and headset AI modes use the same scene-level strategy, with implementation details
calibrated for their respective GPU pipelines:

- **Scene-aware adaptive pop** waits for a new scene’s depth to settle, measures
  gradient-magnitude-weighted depth-edge risk, then chooses a parallax multiplier between `1.20×`
  and `2.00×`. Lower-risk depth fields can use more relief; edge-dense fields move toward the
  gentler end. The choice stays fixed for the shot instead of pumping every frame.
- **Shot-stable zero plane** places the display surface—the depth rendered at zero disparity—at
  the scene’s median inferred depth. It is resolved immediately on an accepted scene cut,
  corrected once after depth settles, and then latched. Between accepted cuts it does not
  continuously follow per-frame motion or depth noise; the committed synthetic exposure-flash
  tests also verify that supported brightness flashes do not relatch it.

```mermaid
flowchart TD
    CUT["Accepted scene cut<br/>set the median zero plane immediately"]
    CUT --> SETTLE["After depth settles<br/>correct the plane once · measure depth-edge risk"]
    SETTLE -->|"Lower risk"| MORE["Stronger relief<br/>pop multiplier toward 2.00×"]
    SETTLE -->|"Higher risk"| LESS["Gentler relief<br/>pop multiplier toward 1.20×"]
    MORE --> HOLD["Hold pop and zero plane<br/>until the next accepted cut"]
    LESS --> HOLD
```

The controller favors stronger stereo relief in lower-risk depth fields and backs off in edge-dense
fields. The shot-latched screen plane reduces convergence breathing from per-frame tracking. These
controls reduce pumping and warp risk; they do not guarantee perfect depth, artifact-free
reprojection, or flawless cut detection.

## Why this pair stands out

Its distinguishing scope is one coordinated workflow spanning flat streaming, authored SBS,
host-side AI conversion, headset-side AI conversion, remote Android XR interaction, and direct
local AR-glasses presentation.

| Workflow category | Scope | Main tradeoff |
|---|---|---|
| **Sunshine 3D + Moonlight 3D** | Capturable Windows content can use AI depth on the PC or Galaxy XR; authored SBS is preserved for remote Android XR, while Sunshine 3D can present host-generated 3D directly to local glasses | Validated around Windows 11, NVIDIA, and Galaxy XR; inferred geometry is scene-dependent |
| **Native stereo only** | The application or media supplies authored eye views to a compatible local or streaming stack | Preserves authored binocular geometry and avoids monocular estimation when well authored, but only where the source explicitly supplies stereo |
| **Media-only conversion** | A player or preprocessing tool converts video for file- or player-oriented output | Well-scoped for video, but not a general interactive desktop/game workflow |
| **Local glasses-only conversion** | A PC converter presents supported content directly to attached glasses | No network round trip, but no remote Android XR experience |
| **Conventional flat streaming** | Games, video, and desktop stay mono across a remote video, audio, and input loop | No stereo depth; avoids AI-depth processing cost |

Individual products vary; this compares workflow scope rather than claiming every implementation
in a category behaves identically. Native stereo remains preferable when accurate authored eye
views are available.

## Main features

| Feature | What it provides |
|---|---|
| **In-headset stream dock** | Switches among 2D, Client 3D, Raw SBS, and Host 3D, reconnecting automatically when transport geometry changes; also provides Cinema, Library, Stats, Dump 3D, and Disconnect actions |
| **Per-mode quality** | Independent resolution, frame-rate ceiling, and bitrate choices for every viewing mode, with shared codec, HDR, range, pacing, and audio settings |
| **Explicit landscape and portrait modes** | 1080p, 1440p, 4K, ultrawide 1080p/1440p, and 5K2K rows, each with a real swapped-dimension portrait counterpart |
| **Adaptive refresh behavior** | Selectable 30, 60, 72, 90, and 120 FPS ceilings; the live stream can follow a lower headset display rate and recover without changing the selected ceiling |
| **Client GPU depth** | Original ZipDepth Base short-384 aspect graphs using a native LiteRT/OpenCL/GLES path—no NPU or CPU fallback |
| **Scene-aware 3D stability** | Selects one depth-edge-aware pop multiplier after a shot settles and holds a median-depth screen plane to reduce pumping and convergence drift |
| **Sunshine 3D integration** | Session-scoped Virtual Display launches, negotiated resolution/FPS/HDR, Host 3D, Raw SBS transport, application library, session resume/end controls, and clipboard sync |
| **Moonlight input and audio** | Gamepad, mouse, keyboard, touchpad, rumble, stereo/5.1/7.1 audio, and host-audio controls |
| **Live diagnostics** | Stream, decoder, network, CPU/GPU load, and Client 3D depth-health telemetry with trend charts |
| **Connection tools** | Automatic host discovery, manual IP/hostname entry, secure PIN pairing, Wake-on-LAN, and network testing |

Codec choices include Auto, AV1, HEVC, and H.264. Available geometry, frame rate, HDR, and codec
combinations still depend on the host encoder, network, and Galaxy XR decoder limits.

## Requirements

- An Android XR device with the spatial API; Samsung Galaxy XR is the validated target.
- The arm64-v8a APK for physical hardware. The x86_64 variant is emulator-only.
- A reachable GameStream-compatible host. Use Sunshine 3D for the complete paired feature set.
- A shared local network for automatic discovery, or a manually entered IP/hostname for any
  securely reachable host.

Client 3D runs on the Galaxy XR GPU. It has no NPU or CPU inference fallback; if GPU depth is
unavailable, presentation remains usable as flat output.

## Install on Galaxy XR

Packaged builds appear on
[Moonlight 3D releases](https://github.com/dcatcher9/moonlight-android/releases) when available.
Choose the `nonRoot_game` arm64-v8a artifact for Galaxy XR. If that page has no suitable build for
the revision you need, use the source-build steps below.

## Build from source

Moonlight 3D uses the Gradle wrapper, Android Gradle Plugin 9.3.2, Android SDK 37.0,
JDK 17–25 (JDK 25 is the development standard), and Android NDK `27.3.13750724`.

Before installing to Galaxy XR, enable Developer options and Wireless debugging on the headset,
connect it through Android SDK Platform Tools, and confirm that it appears in `adb devices`.

```powershell
git clone --recurse-submodules https://github.com/dcatcher9/moonlight-android.git
Set-Location moonlight-android

# Create local.properties with sdk.dir=<your Android SDK path>.
.\gradlew.bat :app:assembleNonRoot_gameDebug

# Update-install to the connected physical headset.
.\gradlew.bat :app:installNonRoot_gameDebug
```

Gradle writes the physical-headset APK under `app/build/outputs/apk/`.
The install task uses an update-install. Pairings and preferences are preserved only when updating
the same application ID with a compatible signing key; this does not migrate data from an older
name/package or a differently signed build. Do not run
`connectedNonRoot_gameDebugAndroidTest` against a personal headset: Android’s connected-test
workflow uninstalls the target package and erases preferences, certificates, pairings, and
profiles. Use a disposable emulator, or follow the data-preserving procedure in
[Client SBS evaluation](./docs/client-sbs-evaluation.md).

## Documentation

| Topic | Guide |
|---|---|
| XR presentation and mode contracts | [Android XR SBS architecture](./docs/android-xr-sbs.md) |
| Host/client 3D behavior | [Client–host SBS parity](./docs/client-host-sbs-parity.md) |
| Client 3D measurement workflow | [Client SBS evaluation](./docs/client-sbs-evaluation.md) |
| Depth-model provenance | [Model sources](./tools/model-sources/README.md) |

## Project lineage and credits

Moonlight 3D was previously named Artemis and Moonlight Noir. It is an Android XR-focused fork of
[Moonlight for Android](https://github.com/moonlight-stream/moonlight-android), designed to pair
with Sunshine 3D while retaining the Moonlight protocol and historical `com.limelight` package
lineage.

Original Moonlight Android authors include
[Cameron Gutman](https://github.com/cgutman),
[Diego Waxemberg](https://github.com/dwaxemberg),
[Aaron Neyer](https://github.com/Aaronneyer), and
[Andrew Hennessy](https://github.com/yetanothername).

## License

Moonlight 3D is licensed under GPLv3. See [LICENSE.txt](./LICENSE.txt). The bundled depth model and
LiteRT runtime retain their own notices and licenses in the
[model notice directory](./app/src/nonRoot_game/assets/third_party/client_sbs_models/).
