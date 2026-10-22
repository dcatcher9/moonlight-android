# LiteRT native runtime

These binaries are the official LiteRT 2.3.0 Android runtime and OpenCL/OpenGL
accelerator used by the client-SBS zero-copy path. They are redistributed under
the upstream Apache-2.0 license. This directory is intentionally scoped to the
`nonRoot_game` distribution source set alongside the qualified model assets.

The recipient-facing attribution and verbatim Apache-2.0 text are packaged at
`assets/third_party/client_sbs_models/NOTICE.txt` and
`assets/third_party/client_sbs_models/LICENSE-APACHE-2.0.txt` in the same non-root source set.

Upstream release:
https://github.com/google-ai-edge/LiteRT/releases/tag/v2.3.0

Downloads (do not substitute `binaries/latest`, which serves different builds):

- `https://storage.googleapis.com/litert/binaries/2.3.0/android_arm64/libLiteRt.so`
- `https://storage.googleapis.com/litert/binaries/2.3.0/android_arm64/libLiteRtClGlAccelerator.so`
- `https://storage.googleapis.com/litert/binaries/2.3.0/android_x86_64/libLiteRt.so`
- `https://storage.googleapis.com/litert/binaries/2.3.0/android_x86_64/libLiteRtClGlAccelerator.so`

SHA-256, size, and Cloud Storage object generation (uploaded 2026-09-28):

- arm64-v8a/libLiteRt.so: 5,475,936 bytes, generation 1790628005581480
  `482330dbcd73b8471e140f4911cad198f09b013ec29ee00d5e00cb25afc65388`
- arm64-v8a/libLiteRtClGlAccelerator.so: 3,303,992 bytes, generation 1790628005657186
  `534ad2bfdcd928f0f0b4c2a3b175717f6a398deb55c0715204439fe06d290b0b`
- x86_64/libLiteRt.so: 7,606,064 bytes, generation 1790628005833024
  `45f6ad88eddc9623a3fa7a81d92e3bddb0e319734f8bfcdde8c55dc68785da1b`
- x86_64/libLiteRtClGlAccelerator.so: 3,959,520 bytes, generation 1790628005679528
  `9d5ad856c44f5e8fe9665e732b72fe954bd5b8d667e0417c6945dd9091dc01a0`

Google has replaced objects at a release path before: the 2.2.0 objects were re-uploaded on
2026-09-10 with different builds. A different generation or hash at these URLs means a different
build, which needs its own qualification.

The matching public C headers are under
`app/src/main/jni/third_party/litert/include`, copied from the release's `litert_cc_sdk.zip`
(SHA-256 `44ebb188ae1a510f233639fa499928fdaaba46436a153a2dbfbb9d87d59728d2`).
