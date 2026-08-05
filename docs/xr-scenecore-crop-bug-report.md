# androidx.xr.scenecore: SurfaceEntity ignores MediaCodec buffer crop rect (visible decoder padding)

Draft issue for https://issuetracker.google.com/issues/new?component=1689664&template=2070825
(the official XR SceneCore feedback component from the androidx release page).
Status: reproduced on 1.0.0-alpha16 and 1.0.0-beta01 (latest as of 2026-08-04).

## Summary

`SurfaceEntity` renders the **full decoder output buffer**, including vendor alignment padding,
instead of the region declared by the buffer's crop rect. Any video whose height is not a
multiple of the device gralloc alignment (32 on Samsung Galaxy XR / SM-I610) shows a thin strip
of garbage (motion-search edge-extension rows, visually "last row smeared downward") along the
bottom edge of the quad.

## Environment

- Device: Samsung Galaxy XR (SM-I610), Android 14 / API 34, arm64-v8a
- Libraries: androidx.xr.scenecore / runtime / runtime-openxr / arcore / arcore-openxr,
  reproduced identically on **1.0.0-alpha16** and **1.0.0-beta01**
- Decoder: c2.qti HEVC (also reproduces with AV1), 3840x2160 and 7680x2160 streams

## Steps to reproduce

1. Create a `SurfaceEntity` (`Shape.Quad`, any stereo mode including mono/side-by-side).
2. Configure a `MediaCodec` video decoder for any stream with height % 32 != 0
   (e.g. standard 3840x2160: the decoder allocates 2176-row buffers and reports
   `crop-bottom = 2159` in its output `MediaFormat`).
3. `decoder.configure(format, surfaceEntity.getSurface(), ...)` and play.

## Expected

Only the crop region (2160 rows) is textured onto the quad — the behavior of every other
Surface consumer (`SurfaceView`/SurfaceFlinger, `SurfaceTexture` via `getTransformMatrix()`,
`TextureView`).

## Actual

All 2176 buffer rows are textured. The bottom 16 rows (0.74% of the quad) display the
decoder's edge-extension padding: the last content row smeared/repeated, plus occasional
uninitialized blocks. Visible on every frame of every video.

## Control experiment

Routing the same decoder output through a `SurfaceTexture` (applying
`SurfaceTexture.getTransformMatrix()`, which encodes the crop) and rendering that texture
instead produces a clean bottom edge — same device, same stream, same decoder. Only the
direct `SurfaceEntity` path shows the padding, isolating the missing crop handling to the
SceneCore surface consumer.

## Impact

Any app streaming or playing standard-height video (2160, 1080 % 32 == 0 is safe, but 2160 % 32
= 16 is not... note: 1080 % 32 = 24, also affected) into a `SurfaceEntity` shows a permanent
garbage strip. There is no workaround via public API: `EdgeFeatheringParams` is a symmetric
alpha ramp (would need to fade ~3% of real content to hide a 0.74% strip), and no
crop/source-rect API exists on `SurfaceEntity` as of 1.0.0-beta01.
