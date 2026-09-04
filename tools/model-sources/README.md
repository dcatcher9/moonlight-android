# Client SBS model-source provenance

This directory records provenance only. Large source and benchmark model binaries are deliberately
not stored in the repository or packaged in either Artemis APK. Download them to temporary storage
when a model must be regenerated or a historical benchmark reproduced.

## Depth Anything V2 Small production family

- Upstream project: `DepthAnything/Depth-Anything-V2`
- Upstream model: Depth Anything V2 Small
- Upstream project URL: `https://github.com/DepthAnything/Depth-Anything-V2`
- Upstream model card: `https://huggingface.co/depth-anything/Depth-Anything-V2-Small`
- License stated by upstream for the Small model: Apache-2.0
- License and modification notice: `LICENSE-DEPTH-ANYTHING-V2-APACHE-2.0.txt`

The non-root APK delivers the corresponding recipient-facing notice and verbatim license text at
`assets/third_party/client_sbs_models/NOTICE.txt` and
`assets/third_party/client_sbs_models/LICENSE-APACHE-2.0.txt`. The root flavor packages neither
the models nor their model-specific notices.

The packaged Artemis graphs are modified TFLite conversions, not verbatim upstream checkpoint
files. The guarded transform records the exact immediate Float32 graph contracts that it accepts.
For the three canonical production shapes those source SHA-256 values are:

- `322x182`: `eaf4f4fc25809da9000ba4e5330b1e3335722b1937fcd94c6e4935fbc411bc23`
- `350x154`: `174ab97d5fb87c1d992f1c0ff6700ced949ccd3e5eda3bdf641be2c446f441f1`
- `434x126`: `0e746d66a40eaa6673cef93144f49843c0f0a10fc618dbe15cc71bba2f9a3055`

`tools/generate-dav2-attention-k352-model.py` verifies those immediate inputs and applies the
documented static attention/GELU transformation; `tools/convert-tflite-fp16-weights.py` performs
the guarded FP16-weight-storage conversion. The final production entry names and hashes are
recorded in `ClientSbsModelManifest` and verified against the checked-in family archive by the
non-root packaged-archive unit test.

This checkout does **not** currently record the pinned upstream checkpoint revision and digest,
exporter source revision, dependency lock, or exact export command that originally produced those
immediate Float32 TFLite graphs. Therefore the checked-in tools reproduce the final transformed
graphs only when the already verified immediate inputs are available; they do not provide a
complete upstream-checkpoint-to-TFLite reproduction chain. Do not invent or imply that missing
chain. Any future model replacement must record those pins before its generated archive is
promoted.

## Qualcomm MiDaS v2.1 Small float graph

- Suggested temporary filename: `midas-midas-v2-float.tflite.model`
- SHA-256: `3990551be4f21be7bffc71c159bb643279af221c6e8b328ce265374776ff2ec1`
- Source: Qualcomm AI Hub, `qualcomm/Midas-V2`
- Qualcomm release: `v0.58.0`
- Hugging Face repository revision: `561f23b4bed3ece084c079dfe83dfe62cebb8879`
- Source archive: `midas-tflite-float.zip`
- Source archive SHA-256: `e7fbce04e25cd56d6882d2a3a1c65e23ba112d535a8e2a42c185acc1b8d537e9`
- Model: MiDaS v2.1 Small with EfficientNet-Lite3 backbone
- Public contract: Float32 `[1,256,256,3]` to Float32 `[1,256,256,1]`

Pinned Qualcomm download URL:

```text
https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/midas/releases/v0.58.0/midas-tflite-float.zip
```

This verified square graph is the source for `tools/generate-midas-static-buckets.py`. The generator
first specializes a temporary Float32 graph,
then runs `tools/convert-tflite-fp16-weights.py`; its three production outputs use
`-fp16weights.tflite.model` filenames while keeping Float32 public NHWC tensors. The square source
must remain outside the repository and is never packaged in Artemis.

After downloading and extracting the pinned archive, regenerate the static FP16-weight buckets with:

```powershell
python tools/generate-midas-static-buckets.py `
    --source C:\tmp\midas-midas-v2-float.tflite.model `
    --output-directory .\build\client-sbs-model-staging
```

Loose generated graphs must stay under this client checkout's ignored `build` or `temp` tree,
never under `app/src/*/assets` and never in Apollo-3D.

## ZipDepth `base_npu` experimental family

- Upstream project: `fabiotosi92/ZipDepth`
- Upstream project URL: `https://github.com/fabiotosi92/ZipDepth`
- Pinned source commit: `91f3fd21e131641f51e8d35736d1958350180e3a`
- Checkpoint: `zipdepth_base_npu.pth`
- Checkpoint SHA-256:
  `627c04fda584133ead4310074884a4a037061b4c01ba86e73e492ea30fab570d`
- Code license stated upstream: MIT

The candidate uses `upsample_unfold=False`, fused inference weights, raw RGB Float32 NHWC input,
and full-resolution Float32 BHWC relative-depth output. The faithful conversion retains the
checkpoint's learned softmax global-context pooling as an equivalent weighted spatial sum. It
does not use ZipDepth's upstream deployment exporter substitution of average pooling, which failed
the source-parity gate. The checkpoint was distilled from Depth Anything V2 Large; complete the
model-weight licensing review before commercial distribution.

The complete upstream-checkpoint-to-TFLite exporter is not tracked in this repository yet, so do
not claim full reproduction from the checkpoint alone. The guarded tools below start from the
verified immediate Float32 graphs. These are the exact graph identities for the short-side-384
candidate family:

| Geometry | Faithful FP32 input | Dense FP32 output | Dense FP16-weight output | GCB-stable FP16 output |
| --- | --- | --- | --- | --- |
| `672 x 384` | `b18ef070262066f7190db3f69f6877ea2aa634caa280c64fe05104fe192770e9` | `be6fa60800c114d628f98eec8197d605cdbe666870857ac2f5c992ce01933c1c` | `2734e23eda172624a86c3d102082bdfbc2443964ae1f4e3bbd99563e5aeda941` | `292d009807c3350ad3ebcce262dec8291fb574b73f41319fe24dff6170d5b279` |
| `896 x 384` | `90c0ff5a3f37cb798e15213b70de7a3c0c48bb0fa913bd67e717e2d4a8e986bd` | `0721485db84d6e83ca4d78d52ad9bb02af2d31f818ced767995abe230ace7632` | `d9335ca81a7ce59b0f1002e023ecf459d3c36a219518221c0570ce46c68d3000` | `e7519e1b17622d8e857e2415ab55e1a9cca5aa794b9c75d6e5b1f3847fe3e62d` |
| `928 x 384` | `d89c109ddb37ceacd7c169e9037f302421a3e75e2d879aca933ac8d6eaad2b43` | `d711fff9bf6405779e25a491d9d01beaff3ec098c52891663059d2d6dfb75eb2` | `e4a5c2b1b35a6851b5eec89d2a0ee0fc87525aa1899d9b06aac80bd5ddf8596f` | `e5e75073e2fd57b362c1acdd256f623c157bb77d5f909470bcd7d2d6f2033f1b` |

`tools/rewrite-zipdepth-gpu-compat.py` accepts only those pinned immediate sources and deterministic
outputs. Its first pass replaces one 24-to-32 four-way grouped 1x1 convolution with an exactly
equivalent zero-filled block-diagonal standard convolution. This avoids the Galaxy XR Adreno
LiteRT graph-builder failure while preserving CPU output bit-for-bit. Run the standard guarded
FP16-weight conversion next. The final pass rewrites `sum(features * probability)` as
`sum(features * (probability * 1024)) / 1024`. Both scale factors are exact powers of two; this
keeps the weighted products above the FP16 subnormal range without changing CPU output.

For example, regenerate the exact `672 x 384` candidate from its verified immediate input with:

```powershell
python -m pip install -r tools/zipdepth-gpu-rewrite-requirements.txt
python tools/rewrite-zipdepth-gpu-compat.py `
    --rewrite densify-group-conv `
    --source C:\tmp\zipdepth-faithful-672x384-fp32-bhwc.tflite `
    --expected-source-sha256 b18ef070262066f7190db3f69f6877ea2aa634caa280c64fe05104fe192770e9 `
    --output .\build\zipdepth-dense-672x384-fp32.tflite
python tools/convert-tflite-fp16-weights.py `
    --source .\build\zipdepth-dense-672x384-fp32.tflite `
    --expected-source-sha256 be6fa60800c114d628f98eec8197d605cdbe666870857ac2f5c992ce01933c1c `
    --output .\build\zipdepth-dense-672x384-fp16weights.tflite
python tools/rewrite-zipdepth-gpu-compat.py `
    --rewrite stabilize-gcb `
    --source .\build\zipdepth-dense-672x384-fp16weights.tflite `
    --expected-source-sha256 2734e23eda172624a86c3d102082bdfbc2443964ae1f4e3bbd99563e5aeda941 `
    --output .\build\zipdepth-stable-672x384-fp16weights.tflite
```

The stages contain 108, 108, 147, and 149 operators respectively. Each rewrite reparses the
serialized graph, verifies the public contract, requires bit-exact CPU output, and refuses loose
outputs outside `build/` or `temp/`. On Galaxy XR the final `672 x 384` graph delegated all
149 operators, ran in 9.5 ms median invoke-to-output-ready time, and improved real-frame FP16 versus
FP32 agreement from Pearson `0.9477` / affine range-NRMSE `9.12%` to `0.99884` / `1.38%`.
Sustained decode/reprojection thermals and full-clip on-device quality remain separate gates.

## ZipDepth original `base` production family

- Upstream project: `fabiotosi92/ZipDepth`
- Upstream project URL: `https://github.com/fabiotosi92/ZipDepth`
- Pinned source commit: `91f3fd21e131641f51e8d35736d1958350180e3a`
- Checkpoint: `zipdepth_base.pth`
- Checkpoint SHA-256:
  `a55910bb0b99c8c5e641cb9206e810b269690ad94e8a2ef08c827c4679391a65`
- Code license stated upstream: MIT
- Recipient-facing code license: `LICENSE-ZIPDEPTH-MIT.txt`

These production candidates use the original base checkpoint and preserve its standard convex
unfold upsampling tail; they are not exports of the separately trained `zipdepth_base_npu.pth`
checkpoint. The final static graphs have raw RGB Float32 NHWC input and full-resolution Float32
NHWC relative-depth output. Selected constant weights use FP16 storage. The Adreno compatibility
transforms densify one grouped 1x1 convolution with exactly equivalent block-diagonal weights and
power-of-two-scale the learned global-context weighted sum to keep its products out of the FP16
subnormal range.

The ignored local conversion results below are the exact inputs to the archive bundler. The paths
are relative to the repository root; their hashes, rather than the untracked paths, are the durable
identity contract:

| Geometry | Production entry | SHA-256 | Bytes | Operators | Public contract |
| --- | --- | --- | ---: | ---: | --- |
| `672 x 384` | `zipdepth-base-static-672x384-fp16weights.tflite.model` | `6296d5c2e4f857fd551d854ebf4dd2ab2462c0d7372d526bf0a7463718b8b6d1` | 12,345,768 | 163 | Float32 `[1,384,672,3]` -> `[1,384,672,1]` |
| `896 x 384` | `zipdepth-base-static-896x384-fp16weights.tflite.model` | `31467ab0cd187b74c65b3b20f4850973309d120519b587610e3dd3e27b72df4a` | 12,345,768 | 163 | Float32 `[1,384,896,3]` -> `[1,384,896,1]` |
| `928 x 384` | `zipdepth-base-static-928x384-fp16weights.tflite.model` | `169d5e8802bea9aac839df6acb4a8dd8e92a53728ea6f4e39a4baca453fd34cc` | 12,345,768 | 163 | Float32 `[1,384,928,3]` -> `[1,384,928,1]` |

The three corresponding local sources are:

```text
build/client-sbs-model-eval/results/zipdepth-tflite-conversion/original-base-standard-tail-672x384/tflite/zipdepth_base_672x384_exact_standard_tail_fp16weights_dense_gcbscale1024.tflite
build/client-sbs-model-eval/results/zipdepth-tflite-conversion/original-base-standard-tail-896x384/tflite/zipdepth_base_896x384_exact_standard_tail_fp16weights_dense_gcbscale1024.tflite
build/client-sbs-model-eval/results/zipdepth-tflite-conversion/original-base-standard-tail-928x384/tflite/zipdepth_base_928x384_exact_standard_tail_fp16weights_dense_gcbscale1024.tflite
```

The complete original-checkpoint-to-standard-tail-TFLite exporter and dependency lock are not yet
tracked in this repository. Consequently, the pinned checkpoint and final graph identities do not
by themselves constitute an end-to-end reproducible conversion chain. Upstream describes ZipDepth
as knowledge-distilled from Depth Anything V2 Large and does not separately state a model-weight
license or analyze that training relationship. Clear the complete redistribution chain before a
commercial release.

## DepthART S448 experimental family

- Upstream project: `xuefeng-cvr/DepthART`
- Upstream project URL: `https://github.com/xuefeng-cvr/DepthART`
- Upstream model repository: `https://huggingface.co/Fengxue93/DepthART`
- Released relative-depth S448 checkpoint SHA-256:
  `e17adf70a87b4d2b7665bf0546aad68f8c5b8b63866cbca604602a7859fadebe`
- License stated by the model repository: Apache-2.0

The candidate uses one S448 checkpoint exported into two static raw-RGB graphs. Five custom
SelectiveScan operations are represented by exact associative prefix scans, ImageNet
normalization is baked into the graph, selected weights use FP16 storage, and public tensors stay
Float32 NHWC. No pruning, retraining, scan approximation, or S224 substitution is applied. The
repository does not yet contain the entire checkpoint-to-ONNX-to-TFLite exporter chain, so do not
claim complete upstream reproduction from this file alone.

The final Adreno stabilization step is reproducible and deliberately narrow. These are its exact
pre-stabilization inputs:

| Geometry | Guarded input SHA-256 | Operators |
| --- | --- | ---: |
| `672 x 384` | `62492475402f84f55998ed5d9c7ff9a56988684631967e3ef2c89f78a97af019` | 2230 |
| `928 x 384` | `65790fd4b8810b0d337781f99159e680c2efed0fe2c03e75bc7c78e3cc4f098e` | 2370 |

`tools/stabilize-depthart-fp16-layernorm.py` accepts only those hashes. It algebraically rescales
only the first selective-scan LayerNorm by four and its epsilon by sixteen, verifies CPU output is
unchanged, and requires these exact deterministic outputs:

| Geometry | Production entry | SHA-256 | Operators |
| --- | --- | --- | ---: |
| `672 x 384` | `depthart-s448-static-672x384-fp16weights.tflite.model` | `3de0ded3a2329a6cc4c89da535f4c1f3035dfc30c7e85359d48580003aad780b` | 2231 |
| `928 x 384` | `depthart-s448-static-928x384-fp16weights.tflite.model` | `d166bb5dcbe16ea386640a344a80134da8e225837f4609eae64f57916ec757f2` | 2371 |

Regenerate the exact final entries from verified immediate inputs with:

```powershell
python -m pip install -r tools/depthart-layernorm-stabilization-requirements.txt
python tools/stabilize-depthart-fp16-layernorm.py `
    --source C:\tmp\depthart-pre-stabilization-672x384.tflite.model `
    --output .\build\client-sbs-model-staging\depthart-s448-static-672x384-fp16weights.tflite.model `
    --width 672
python tools/stabilize-depthart-fp16-layernorm.py `
    --source C:\tmp\depthart-pre-stabilization-928x384.tflite.model `
    --output .\build\client-sbs-model-staging\depthart-s448-static-928x384-fp16weights.tflite.model `
    --width 928
```

Both corrected graphs pass isolated Galaxy XR full-delegation, finite-output, and latency checks.
DepthART remains an explicitly labeled experimental candidate until sustained live
decode/reprojection cadence, compositor responsiveness, and thermals are qualified. There is no
dedicated 32:9 graph; current 32:9 streams use the nearer `928 x 384` graph and must pass visual
acceptance for direct-resize aspect compression.

## Publish the family archives

After all three validated DA-V2 graphs, all three MiDaS graphs, both DepthART candidate graphs, and
all three ZipDepth base graphs are present in one staging directory, publish the four standard
solid family archives with:

```powershell
python tools/bundle-client-sbs-models.py `
    --input-dir .\build\client-sbs-model-staging `
    --output-dir .\app\src\nonRoot_game\assets
```

The bundler requires all eleven exact filenames, creates one TAR/XZ per family, builds all four
temporary archives first, and then atomically replaces each destination file. The four-file
publication is not a filesystem transaction, so run the packaged-archive test before committing.
Do not copy the loose models into source assets. The experimental DepthART archive is 10,991,860
bytes with SHA-256
`1dccec4aa315288b5cc471a9d585d57e00d0e12a56870cb4712da5f20fb476a6`; its entry hashes above remain
the stronger per-model contract. The ZipDepth archive is 11,149,420 bytes with SHA-256
`0b737e7ff7d6717c9b376e2e6d195eb5ff4a54d49d862e3415f155d137c78558`; the three entry hashes in
the production-family table remain its stronger per-model contract.

## LiteRT Community MiDaS v2.1 Small FP16-weight graph (benchmark only)

- Upstream repository: `litert-community/MiDaS-small`
- Pinned revision: `e67ad159d92fba999903bdd394737a87c47509b0`
- Upstream file: `midas_small_256_fp16.tflite`
- Upstream SHA-256: `bec9bce704789e504ec306196fcb0aabe90fd25c2b9d7db382339741950890ca`
- Suggested temporary prepared filename: `midas-small-litert-fp16-output-bhwc.tflite.model`
- Prepared SHA-256: `9d4655e0d3347394af7f441bad7ca19b747968950c757c6345d85ba36c46e518`
- Public contract after preparation: Float32 `[1,256,256,3]` to Float32
  `[1,256,256,1]`

Pinned download URL:

```text
https://huggingface.co/litert-community/MiDaS-small/resolve/e67ad159d92fba999903bdd394737a87c47509b0/midas_small_256_fp16.tflite
```

Reproduce the prepared graph from a downloaded source file:

```powershell
python -m pip install -r tools/midas-gpu-benchmark-requirements.txt
python tools/prepare-midas-gpu-benchmark.py `
    --source C:\tmp\midas_small_256_fp16.tflite `
    --output .\build\client-sbs-model-staging\midas-small-litert-fp16-output-bhwc.tflite.model
```

The preparation changes only the graph-output and default-signature tensor indices from terminal
rank-3 tensor 337 to its rank-4 producer tensor 336. It does not reserialize or move the external
FP16 weight buffers. CPU output before/after this contract patch is bit-identical. Production does
not use this independently exported graph; it derives FP16-stored weights reproducibly from the
pinned Qualcomm Float32 source above so the existing static-bucket graph identity is retained.

MiDaS is distributed under the MIT license; its notice is in `LICENSE-MIDAS-MIT.txt`.
EfficientNet-Lite3 is attributed by the model card under Apache-2.0. See the
[Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0) and the pinned upstream model card
for converter/model-specific provenance. The non-root APK delivers the MiDaS license at
`assets/third_party/client_sbs_models/LICENSE-MIDAS-MIT.txt`; its adjacent `NOTICE.txt` describes
the shipped graph modifications and references both bundled licenses.

Do not copy loose model binaries into the repository. Only the solid family archives belong in the
non-root asset source set. The pinned URLs, revisions, hashes, transformation tools, explicit gaps,
and license notice above are the reproducibility source of truth.
