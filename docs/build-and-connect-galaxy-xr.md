# Build & deploy to Galaxy XR (SM-I610)

Quick reference for building this app and deploying it to the **Samsung Galaxy XR
(SM-I610, Android 14 / API 34, arm64-v8a)** over wireless adb. Written to avoid the two
recurring snags: picking the wrong **product flavor** (build/install fails) and the
headset's **wireless-adb connection dropping** (needs a re-`connect` with the current port).

## 1. Use the correct flavor task (most common build failure)

The app has **two product flavors**: `root` (`maxSdk 25`) and `nonRoot_game`. Consequences:

- There is **no plain `installDebug` / `assembleDebug`** task — Gradle requires a flavor-qualified
  task name.
- **`installRootDebug` installs but then fails on the headset**: the `root` flavor is capped at
  `maxSdk 25`, while the Galaxy XR is **API 34**, so the package manager rejects it.

➡️ **Always use the `nonRoot_game` flavor:**

```bash
JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-25.0.3.9-hotspot" \
  ./gradlew :app:installNonRoot_gameDebug
```

- Build with **JDK 25** (the standard build JDK here). Gradle 9.6 accepts JDK 17–25 but **rejects
  JDK 26** — point `JAVA_HOME` at JDK 25.
- Other useful tasks (same flavor prefix):
  - `:app:assembleNonRoot_gameDebug` — build the APK without installing.
  - `:app:compileNonRoot_gameDebugJavaWithJavac` — fast compile-only sanity check.
- Installed application id: **`com.limelight.noirdebug`**
- Launcher activity: **`com.limelight.PcView`**

## 2. adb path

`adb` is not on `PATH`. Use the full path:

```
C:\Users\DCatc\AppData\Local\Android\Sdk\platform-tools\adb.exe
```

## 3. Connect / reconnect to the headset (wireless adb drops frequently)

The headset is at **IP `192.168.68.95`**, but the **adb port is ephemeral** — it changes after a
reconnect / sleep / Wireless-debugging toggle. Pairing already persists on this machine, so you only
need to re-`connect` (no re-`pair`).

**Reconnect procedure** — run whenever `adb devices` is empty or shows the device as `offline`:

```bash
ADB="C:/Users/DCatc/AppData/Local/Android/Sdk/platform-tools/adb.exe"

# 1. Discover the CURRENT TLS-connect port (it changes!):
"$ADB" mdns services | grep tls-connect
#   -> adb-R3GYB050PKL-yTw0ip   _adb-tls-connect._tcp   192.168.68.95:42073

# 2. Connect to that ip:port from step 1:
"$ADB" connect 192.168.68.95:42073

# 3. Verify:
"$ADB" devices -l
#   -> 192.168.68.95:42073   device  product:xrvst2ue model:SM_I610 device:xrvst2
```

- The headset must be **awake and on the Wi-Fi** (`Deco_6GHz`) for mDNS discovery to work.
- Example serial (current): **`192.168.68.95:42073`**. Use whatever port `mdns services` reports if
  this exact port no longer connects.

## 4. Target the right device (an offline emulator is present)

An `emulator-5566` often shows up as `offline`. Pin every adb/Gradle command to the headset serial so
it doesn't accidentally target the emulator:

```bash
export ANDROID_SERIAL=192.168.68.95:42073    # bash / Git Bash
```
```powershell
$env:ANDROID_SERIAL = "192.168.68.95:42073"  # PowerShell
```

Gradle `install*` tasks also honor `ANDROID_SERIAL`.

## 5. Runtime notes

- **Launch the app:**
  ```bash
  "$ADB" shell am start -n com.limelight.noirdebug/com.limelight.PcView
  ```
- **Presentation mode** is chosen from the XR control bar inside an active stream (Normal,
  Host SBS Raw, Host SBS AI, or Client SBS AI) and is restored per machine/app. The old
  `render_mode_list` and client depth-parameter preferences are no longer used.
- From **Git Bash**, prefix adb commands that pass Unix-style paths (e.g. `run-as`, `/data/...`)
  with `MSYS_NO_PATHCONV=1` so MSYS doesn't mangle the paths.
