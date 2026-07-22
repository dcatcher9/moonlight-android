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
never under `app/src/*/assets` and never in Apollo-3D. After all three validated DA-V2
graphs and all three MiDaS graphs are present in the same staging directory, publish the two
standard solid family archives with:

```powershell
python tools/bundle-client-sbs-models.py `
    --input-dir .\build\client-sbs-model-staging `
    --output-dir .\app\src\nonRoot_game\assets
```

The bundler requires all six exact production filenames, creates one TAR/XZ per family, builds both
temporary archives first, and then atomically replaces each destination file. The pair is not a
filesystem transaction, so run the packaged-archive test before committing. Do not copy the loose
models into source assets.

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

Do not copy these large ordinary model binaries into the repository. The pinned URLs, revisions,
hashes, transformation tools, and license notice above are the reproducible source of truth.
