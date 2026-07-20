# LiteRT native runtime

These binaries are the official LiteRT 2.1.6 Android runtime and OpenCL/OpenGL
accelerator used by the client-SBS zero-copy path. They are redistributed under
the upstream Apache-2.0 license.

Upstream release:
https://github.com/google-ai-edge/LiteRT/releases/tag/v2.1.6

Downloads:

- `https://storage.googleapis.com/litert/binaries/2.1.6/android_arm64/libLiteRt.so`
- `https://storage.googleapis.com/litert/binaries/2.1.6/android_arm64/libLiteRtClGlAccelerator.so`
- `https://storage.googleapis.com/litert/binaries/2.1.6/android_x86_64/libLiteRt.so`
- `https://storage.googleapis.com/litert/binaries/2.1.6/android_x86_64/libLiteRtClGlAccelerator.so`

SHA-256:

- arm64-v8a/libLiteRt.so:
  `35e34acfb76722868b0fe6bccab9d4432ac3f9fe95e7f29d2d6c030b66052369`
- arm64-v8a/libLiteRtClGlAccelerator.so:
  `7e25f90235193554424fb21599c4276dbc5c50eb80769fab6071ce105ca98880`
- x86_64/libLiteRt.so:
  `aa1530ba8b37b537d37139760716d183d2d7dc1f7781791ddf1d071c73eca535`
- x86_64/libLiteRtClGlAccelerator.so:
  `bf74fef00a60639a63444da7d4d2d107dd91a3fa047d4076cb06e98305ca3648`

The matching public C headers are under
`app/src/main/jni/third_party/litert/include`.
