# Retired Client SBS model archives

These archives were removed from the Android `nonRoot_game` asset source set on 2026-09-03 when
original ZipDepth Base became the only production Client SBS model family. They remain in the
repository solely for reproducibility and future offline evaluation. Gradle does not package files
under `tools/` into any APK.

Do not move these archives back under `app/src/*/assets` unless the corresponding model family is
deliberately restored to the product, including its runtime manifest, migration policy, tests,
recipient-facing notice, and license review.

| Retired family archive | Bytes | SHA-256 |
| --- | ---: | --- |
| `client-sbs-dav2-models.tar.xz` | 44,429,612 | `3f9892624253e5d7301d6b0eb28acc7ef30ac2cf3131acbc7a8c1f59696ad148` |
| `client-sbs-midas-models.tar.xz` | 29,947,928 | `166be90ec3866dfeae61ce7163df49414840b6d054466d79dbe153ea3ebc8b94` |
| `client-sbs-depthart-models.tar.xz` | 10,991,860 | `1dccec4aa315288b5cc471a9d585d57e00d0e12a56870cb4712da5f20fb476a6` |

Detailed graph identities, conversion caveats, and historical measurements remain in the parent
[`README.md`](../README.md), [`docs/client-sbs-evaluation.md`](../../../docs/client-sbs-evaluation.md),
and [`docs/client-sbs-dav2-fp16-bisect.md`](../../../docs/client-sbs-dav2-fp16-bisect.md).
The retained MiDaS license is adjacent to these archives. Depth Anything V2's Apache notice is in
[`../LICENSE-DEPTH-ANYTHING-V2-APACHE-2.0.txt`](../LICENSE-DEPTH-ANYTHING-V2-APACHE-2.0.txt), and the
standard Apache 2.0 text also remains packaged for the active LiteRT runtime.
