# Build & update-deploy to Galaxy XR (SM-I610)

Quick reference for building this app and deploying it to the **Samsung Galaxy XR
(SM-I610, Android 14 / API 34, arm64-v8a)** over wireless adb. The headset's
**wireless-adb connection dropping** needs a re-`connect` with the current port.

## 1. Build the supported XR distribution

The sole supported distribution retains the `nonRoot_game` task names and application IDs.
The obsolete Android 7 root flavor and its privileged input helper have been removed.
Use the explicit distribution task:

```bash
JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-25.0.3.9-hotspot" \
  ./gradlew :app:installNonRoot_gameDebug
```

- Build with **JDK 25** (the standard build JDK here). Gradle 9.7 accepts JDK 17–25 but **rejects
  JDK 26** — point `JAVA_HOME` at JDK 25.
- Other useful tasks (same flavor prefix):
  - `:app:assembleNonRoot_gameDebug` — build the APK without installing.
  - `:app:compileNonRoot_gameDebugJavaWithJavac` — fast compile-only sanity check.
- Installed application id: **`com.limelight.moonlight3ddebug`**
- Launcher activity: **`com.limelight.PcView`**

`installNonRoot_gameDebug` performs an update install of that existing debug application and
preserves global defaults, current-session settings, certificates, and host pairings. The retired
active profile is migrated once into XR global defaults. `assembleNonRoot_gameDebug` only
creates APKs; it does not deploy anything. Do not substitute `assembleNonRoot`, which is neither a
complete variant name nor an install task.

> **Physical-headset data safety:** never run `connectedNonRoot_gameDebugAndroidTest`, an
> `uninstall*` task, `adb uninstall com.limelight.moonlight3ddebug`, or
> `pm clear com.limelight.moonlight3ddebug` on the user's Galaxy XR. The connected Android-test workflow
> can uninstall the target package and erase all Artemis data. If update-install reports a signing
> or downgrade conflict, stop instead of uninstalling the existing app. See
> [client-sbs-evaluation.md](client-sbs-evaluation.md) for the data-preserving manual
> instrumentation procedure.

## 2. adb path

`adb` is not on `PATH`. Use the full path:

```
C:\Users\DCatc\AppData\Local\Android\Sdk\platform-tools\adb.exe
```

## 3. Connect / reconnect to the headset (wireless adb drops frequently)

Both the headset's DHCP address and its **adb port are ephemeral** — they can change after a
reconnect, sleep, or Wireless-debugging toggle. Pairing persists on this machine, so normally you
only need to re-`connect` (no re-`pair`). Always copy the complete current `ip:port` endpoint from
`adb mdns services`; do not reuse an endpoint from an earlier session.

**Reconnect procedure** — run whenever `adb devices` is empty or shows the device as `offline`:

```bash
ADB="C:/Users/DCatc/AppData/Local/Android/Sdk/platform-tools/adb.exe"

# 1. Discover the CURRENT TLS-connect port (it changes!):
"$ADB" mdns services | grep tls-connect
#   -> adb-R3GYB050PKL-yTw0ip   _adb-tls-connect._tcp   192.168.68.90:36235

# 2. Connect to that ip:port from step 1:
"$ADB" connect 192.168.68.90:36235

# 3. Verify:
"$ADB" devices -l
#   -> 192.168.68.90:36235   device  product:xrvst2ue model:SM_I610 device:xrvst2
```

- The headset must be **awake and on the Wi-Fi** (`Deco_6GHz`) for mDNS discovery to work.
- Example serial from a successful deployment: **`192.168.68.90:36235`**. Use the complete endpoint
  currently reported by `mdns services` if either the address or port differs.

## 4. Target the right device (an offline emulator is present)

An `emulator-5566` often shows up as `offline`. Pin every adb/Gradle command to the headset serial so
it doesn't accidentally target the emulator:

```bash
export ANDROID_SERIAL=192.168.68.90:36235    # bash / Git Bash; use the current mDNS endpoint
```
```powershell
$env:ANDROID_SERIAL = "192.168.68.90:36235"  # PowerShell; use the current mDNS endpoint
```

Gradle `install*` tasks also honor `ANDROID_SERIAL`.

## 5. Runtime notes

- **Launch the app:**
  ```bash
  "$ADB" shell am start -n com.limelight.moonlight3ddebug/com.limelight.PcView
  ```
- **Presentation mode** is chosen from the five XR dock buttons: **2D**, **Host AI 3D**,
  **Client AI 3D**, **Game 3D**, and **Movie 3D**. Host means the streaming computer; Client means
  this device. This app still requires Android XR, and Host AI 3D requires a compatible
  Sunshine 3D Windows/NVIDIA host. A fresh host connection starts in 2D; only a host-confirmed
  resume of the same session/app restores the last successful mode. Tap the active mode again
  to open its settings in the shared pane below the dock. Settings, Cinema, Stats, and Disconnect
  remain direct actions; debug builds also offer Dump 3D.
- **Game 3D** is the source-neutral entry for ReShade or another compatible game-stereo provider.
  Configure the supported export using the
  [host ReShade guide](https://github.com/dcatcher9/Apollo-3D/blob/master/docs/reshade-sbs.md),
  then choose Game 3D on a supporting Sunshine 3D host. No host provider toggle or host restart
  is needed for streaming. Its own Resolution/FPS/Bandwidth pane stays available, with
  **Waiting for game**, **3D active**, or **Showing 2D** above it. Entry and reconnect start in mono;
  a validated source triggers a guarded stereo-stream resize after host acknowledgement and a
  fresh matching decoder frame. The game's desktop stays at the selected resolution. Source loss
  keeps the established packed stream with the current mono picture in both eyes. If stereo cannot
  start, change quality or leave and re-enter Game 3D to retry. Hosts without this capability stay
  safely in 2D and show that source connection is unavailable. Host AI 3D remains AI regardless
  of the host's separate local AR provider setting. Physical game-to-headset acceptance is still
  required; JVM tests exercise the protocol/presenter with the device boundary mocked.
- **Movie 3D** starts in 2D and offers manual **Picture format: 2D / Half SBS / Full SBS**.
  Choose the movie’s incoming SBS format when it is playing, then return to 2D for the desktop.
  There is no Auto detector. These choices do not resize the desktop or stream, multiply width,
  or require a virtual display; reconnecting or re-entering Movie 3D resets the format to 2D.
- Client AI 3D has no strength/convergence/balance parameter panel; the old `render_mode_list`
  and client depth-parameter preferences are not part of the current path.
- Performance logging is opt-in in both debug and release builds so diagnostics do not perturb
  latency or frame-pacing measurements. The first update from the former debug-on policy disables
  that forced value once; subsequent explicit Diagnostics choices persist.
- From **Git Bash**, prefix adb commands that pass Unix-style paths (e.g. `run-as`, `/data/...`)
  with `MSYS_NO_PATHCONV=1` so MSYS doesn't mangle the paths.

## 6. Client SBS PC checks and headset evaluation

Run the focused Client SBS PC contract suite without touching a device:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass `
    -File .\tools\client-sbs-eval.ps1
```

Add `-Assemble` to produce the supported debug APK splits after the tests. PC/JVM tests verify the
model manifest and aspect-bucket selection, the process-wide single-model lifecycle, two-slot
scheduling, decoder/presenter transitions, shader contracts, scene-cut state and GPU sources, and
disjoint timer logic, but cannot execute the Galaxy XR Adreno OpenCL/OpenGL interop path. Follow
[client-sbs-evaluation.md](client-sbs-evaluation.md) for the safe manual native smoke test, stats
interpretation, HDR/SDR checks, and sustained live-stream comparison.
