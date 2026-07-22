#!/usr/bin/env python3
"""Bundle Client SBS models into deterministic solid per-family tar.xz files.

Each family is one ordinary USTAR stream compressed as a single XZ/LZMA2 stream.
Consequently, the 64 MiB preset-9 dictionary can reuse data across complete models.
There is no custom manifest, delta encoding, or cross-model reconstruction format.
"""

from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path
import tarfile
import tempfile


DEPTH_ANYTHING_ARCHIVE_FILENAME = "client-sbs-dav2-models.tar.xz"
MIDAS_ARCHIVE_FILENAME = "client-sbs-midas-models.tar.xz"
COPY_CHUNK_BYTES = 1024 * 1024
XZ_PRESET = 9
XZ_DICTIONARY_BYTES = 64 * 1024 * 1024

DEPTH_ANYTHING_MODELS = (
    "depth-anything-v2-small-static-322x182-fp16weights.tflite.model",
    "depth-anything-v2-small-static-350x154-fp16weights.tflite.model",
    "depth-anything-v2-small-static-434x126-fp16weights.tflite.model",
)
MIDAS_MODELS = (
    "midas-v2-small-static-352x192-fp16weights.tflite.model",
    "midas-v2-small-static-384x160-fp16weights.tflite.model",
    "midas-v2-small-static-448x128-fp16weights.tflite.model",
)

# Tuple order defines both archive publication order and USTAR entry order.
MODEL_FAMILIES = (
    (DEPTH_ANYTHING_ARCHIVE_FILENAME, DEPTH_ANYTHING_MODELS),
    (MIDAS_ARCHIVE_FILENAME, MIDAS_MODELS),
)


class ModelBundleError(RuntimeError):
    """Raised when inputs violate the production model-bundle contract."""


def _path_has_apollo_component(path: Path) -> bool:
    return any(part.casefold() == "apollo-3d" for part in path.parts)


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(COPY_CHUNK_BYTES), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _tar_info(entry_name: str, size: int) -> tarfile.TarInfo:
    """Return platform-independent USTAR metadata for a root regular file."""
    info = tarfile.TarInfo(entry_name)
    info.size = size
    info.mtime = 0
    info.mode = 0o644
    info.uid = 0
    info.gid = 0
    info.uname = ""
    info.gname = ""
    info.type = tarfile.REGTYPE
    info.linkname = ""
    return info


def _validate_paths(
    input_directory: Path,
    output_directory: Path,
) -> dict[str, Path]:
    input_directory = input_directory.resolve()
    output_directory = output_directory.resolve()
    if _path_has_apollo_component(input_directory) or _path_has_apollo_component(
        output_directory
    ):
        raise ModelBundleError("Client model files must never use the Apollo-3D tree")
    if not input_directory.is_dir():
        raise ModelBundleError(f"Input model directory does not exist: {input_directory}")
    if output_directory.exists() and not output_directory.is_dir():
        raise ModelBundleError(
            f"Output model archive path is not a directory: {output_directory}"
        )

    model_paths: dict[str, Path] = {}
    for _, model_names in MODEL_FAMILIES:
        for name in model_names:
            model_path = (input_directory / name).resolve()
            if _path_has_apollo_component(model_path):
                raise ModelBundleError("Client model files must never use the Apollo-3D tree")
            if not model_path.is_file():
                raise ModelBundleError(f"Missing production Client SBS model: {name}")
            if model_path.stat().st_size <= 0:
                raise ModelBundleError(f"Production Client SBS model is empty: {name}")
            model_paths[name] = model_path
    return model_paths


def _write_archive(
    archive_path: Path,
    model_names: tuple[str, ...],
    model_paths: dict[str, Path],
) -> None:
    # One tar stream is compressed by one XZ stream, making this a standard solid archive.
    # liblzma preset 9 uses LZMA2 with a 64 MiB dictionary.
    with tarfile.open(
        archive_path,
        mode="w:xz",
        format=tarfile.USTAR_FORMAT,
        preset=XZ_PRESET,
    ) as archive:
        for name in model_names:
            model_path = model_paths[name]
            with model_path.open("rb") as source:
                archive.addfile(_tar_info(name, model_path.stat().st_size), source)


def bundle_models(
    input_directory: Path,
    output_directory: Path,
) -> dict[str, tuple[dict[str, object], ...]]:
    """Validate and build both archives, then atomically replace each archive file."""
    model_paths = _validate_paths(input_directory, output_directory)
    output_directory = output_directory.resolve()
    output_directory.mkdir(parents=True, exist_ok=True)

    temporary_paths: dict[Path, Path] = {}
    try:
        for archive_name, model_names in MODEL_FAMILIES:
            output_path = output_directory / archive_name
            with tempfile.NamedTemporaryFile(
                prefix=f".{archive_name}.",
                suffix=".partial",
                dir=output_directory,
                delete=False,
            ) as temporary:
                temporary_path = Path(temporary.name)
            temporary_paths[output_path] = temporary_path
            _write_archive(temporary_path, model_names, model_paths)

        for output_path, temporary_path in temporary_paths.items():
            os.replace(temporary_path, output_path)
        temporary_paths.clear()
    finally:
        for temporary_path in temporary_paths.values():
            temporary_path.unlink(missing_ok=True)

    return {
        archive_name: tuple(
            {
                "filename": name,
                "sha256": _sha256(model_paths[name]),
                "size": model_paths[name].stat().st_size,
            }
            for name in model_names
        )
        for archive_name, model_names in MODEL_FAMILIES
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--input-dir",
        required=True,
        type=Path,
        help="directory containing the six raw production model files",
    )
    parser.add_argument(
        "--output-dir",
        required=True,
        type=Path,
        help="directory that will receive the two solid model-family tar.xz archives",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    bundles = bundle_models(args.input_dir, args.output_dir)
    for archive_name, models in bundles.items():
        archive_path = args.output_dir / archive_name
        print(
            f"Wrote {archive_path} ({archive_path.stat().st_size} bytes, "
            f"sha256={_sha256(archive_path)}, {len(models)} complete models, "
            f"solid XZ preset {XZ_PRESET}/{XZ_DICTIONARY_BYTES // (1024 * 1024)} MiB dict)"
        )


if __name__ == "__main__":
    main()
