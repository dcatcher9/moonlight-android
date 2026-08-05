# Request: third-party app access to the NPU (Hexagon HTP) on Samsung Galaxy XR

Draft post for the Samsung Developer Forum (Galaxy XR category) and/or the Android XR
feedback channel. Written 2026-08-04; device facts verified on SM-I610, Android 14,
platform `anorak`.

---

**Title:** Please provide a supported path for third-party NPU (Hexagon HTP) inference on
Galaxy XR — hardware and vendor stack are present but policy-gated

**Body:**

We develop an open-source XR streaming client for Galaxy XR that runs real-time monocular
depth estimation on-device to convert 2D video into stereoscopic 3D (LiteRT, Depth Anything
V2 Small). This workload is exactly what an NPU is for, and the device clearly has one — but
third-party apps cannot reach it.

## What works today

- Inference runs on the **GPU delegate at ~22–23 ms/frame** against a 33 ms budget (30 fps).
  It works, but it competes directly with stereo rendering and the XR compositor for the same
  GPU — the scarcest and most thermally constrained resource on a head-mounted device.

## What the device ships

`/vendor/lib64` contains the complete Qualcomm NPU stack, including:

```
libQnnHtp.so
libQnnHtpPrepare.so
libQnnTFLiteDelegate.so
libSnpeHtpV73Stub.so   (Hexagon V73)
```

We correctly declare the vendor libraries with `<uses-native-library>` (API 31 mechanism);
they load, and the QNN TFLite delegate initializes up to the point of opening a DSP session.

## The wall (platform policy, not missing hardware)

- `/dev/fastrpc-cdsp` does not exist on the device.
- `/dev/adsprpc-smd` is `crw-rw-r-- system:system`, and SELinux grants `appdomain` only
  `ioctl read` on it.
- `createUnsignedPD` fails, so unsigned process domains — the standard mechanism by which
  third-party apps run models on the HTP — are not permitted.

Net result: the NPU is reserved for system components; untrusted apps are policy-blocked.

## Why this is worth changing

1. **Precedent exists in your own product line.** On Galaxy S-series phones the same QNN
   stack is app-accessible (unsigned PDs allowed on `/dev/adsprpc-smd`); developers ship HTP
   inference today. Galaxy XR is the device family where offloading the GPU matters *most* —
   perf/W and thermals directly bound session length and comfort on an HMD.
2. **The alternative paths are closed too.** NNAPI is deprecated; there is no AICore/NPU
   delegation surface on Android XR today. GPU delegation is the only option, and it taxes
   the compositor.
3. **XR is the showcase for on-device ML.** Depth estimation, segmentation, hand/scene
   understanding, super-resolution — every serious XR app will want sustained small-model
   inference. A supported NPU path is a platform-level differentiator for Galaxy XR.

## The request

Any one of these would unblock third-party ML on the device:

1. Align the FastRPC/SELinux policy with Galaxy phones: allow unsigned PDs on the cDSP for
   untrusted apps (`/dev/fastrpc-cdsp` node + `appdomain` ioctl rw).
2. Or expose the HTP through a supported delegate surface (LiteRT NPU delegate / AICore) so
   apps never touch the device nodes directly.
3. Or, minimally, document the intended policy so developers can plan around it.

We're happy to provide a working test app, benchmarks (GPU vs expected HTP latency for a
~25M-parameter ViT-S encoder), and any traces that help.

---

Facts recorded from on-device investigation (2026-07-28 session): vendor library listing,
`<uses-native-library>` integration attempt, device-node permissions, SELinux `appdomain`
policy, and the `createUnsignedPD` failure.
