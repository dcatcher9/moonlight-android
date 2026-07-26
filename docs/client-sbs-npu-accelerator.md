# Client SBS Hexagon NPU — archived investigation

## Decision

Artemis does not ship or use a Qualcomm QNN/HTP path. Client SBS depth inference remains on the
production LiteRT OpenCL/OpenGL accelerator.

The Galaxy XR vendor image does not grant third-party application UIDs the FastRPC access required
to execute on the Hexagon NPU. The experimental CPU/GPU/NPU benchmark, its JNI entry point, the
Qualcomm LiteRT plugins, and the QAIRT/QNN runtime payloads were therefore removed. They must not be
added to `app/src/main/jniLibs`: that shared source set would affect every product flavor and the
payload cannot provide NPU execution on the target device.

The only LiteRT binaries intentionally packaged by Artemis are flavor-scoped under
`app/src/nonRoot_game/jniLibs`:

- `libLiteRt.so`
- `libLiteRtClGlAccelerator.so`

Those libraries implement the supported zero-copy GPU path and are not Qualcomm NPU components.
The root flavor packages neither library.

## Historical benchmark result

The removed host-memory harness recorded the following cool-device baseline on SXR2230P
(`thermal 0 → 0`) on 2026-07-25:

| model | CPU | GPU | NPU |
|---|---:|---:|---|
| `midas-v2-static-352x192` | 343 ms | 9.4 ms | blocked before graph execution |
| `depth-anything-v2-small-static-322x182` | 627 ms | 15.5 ms | blocked before graph execution |

The GPU values excluded the zero-copy GL interop used by production and should not be treated as
live Client SBS inference timings. The deleted experiment remains recoverable from Git history,
beginning with commit `becb4887`, if the platform restriction changes.

## Evidence retained for future reassessment

The bring-up reached the HTP transport and then failed while creating the DSP protection domain:

```text
QnnDsp <E> createUnsignedPD unsigned PD or DSPRPC_GET_DSP_INFO not supported by HTP
QnnDsp <E> DspTransport.createUnsignedPD failed, 0x00000003
```

Device policy and node permissions agreed with that result:

```text
/vendor/etc/selinux/vendor_sepolicy.cil:
  (allow appdomain vendor_qdsp_device (chr_file (ioctl read)))

/dev/adsprpc-smd:
  crw-rw-r--  system system  u:object_r:vendor_qdsp_device:s0
```

The application domain receives read access but not the read/write access needed by FastRPC.
`/dev/fastrpc-cdsp` was also absent. This is a platform restriction, not evidence that the MiDaS or
Depth Anything models are incompatible with HTP.

Reconsider an NPU experiment only if a Galaxy XR OS update exposes the required FastRPC access to
third-party apps, or a future LiteRT/QAIRT stack provides a device-supported signed protection
domain. Any renewed experiment should live outside production source sets and must prove full
delegation and validate its output before reporting accelerator timings.
