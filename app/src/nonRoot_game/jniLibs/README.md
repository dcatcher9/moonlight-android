# LiteRT native runtime

These binaries are the official LiteRT 2.2.0 Android runtime and OpenCL/OpenGL
accelerator used by the client-SBS zero-copy path. They are redistributed under
the upstream Apache-2.0 license. This directory is intentionally scoped to the
`nonRoot_game` product flavor so root APKs do not package the runtime.

The recipient-facing attribution and verbatim Apache-2.0 text are packaged at
`assets/third_party/client_sbs_models/NOTICE.txt` and
`assets/third_party/client_sbs_models/LICENSE-APACHE-2.0.txt` in the same non-root source set.

Upstream release:
https://github.com/google-ai-edge/LiteRT/releases/tag/v2.2.0

Downloads:

- `https://storage.googleapis.com/litert/binaries/2.2.0/android_arm64/libLiteRt.so`
- `https://storage.googleapis.com/litert/binaries/2.2.0/android_arm64/libLiteRtClGlAccelerator.so`
- `https://storage.googleapis.com/litert/binaries/2.2.0/android_x86_64/libLiteRt.so`
- `https://storage.googleapis.com/litert/binaries/2.2.0/android_x86_64/libLiteRtClGlAccelerator.so`

SHA-256:

- arm64-v8a/libLiteRt.so:
  `b2913eb689e731aef26589601c0d18f01695c140329561d4785ba36cd07dda4a`
- arm64-v8a/libLiteRtClGlAccelerator.so:
  `34cbd6fddf442539ce2833c934c43213a12fd25618635d2e8dced21840d2a3c4`
- x86_64/libLiteRt.so:
  `bd9ab78e8fa1c36991d7fb6fdd5261d2b99fe6d34205de66d20d25c7ab98434a`
- x86_64/libLiteRtClGlAccelerator.so:
  `d66318c5d1a24728f68ae69970c9929c52e977b7c3739a88532c41c3593d3b3c`

The matching public C headers are under
`app/src/main/jni/third_party/litert/include`.
