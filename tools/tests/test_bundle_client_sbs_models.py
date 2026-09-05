#!/usr/bin/env python3
"""Tests for the deterministic solid Client SBS model-family tar.xz builder."""

from __future__ import annotations

import hashlib
import importlib.util
import lzma
from pathlib import Path
import tarfile
import tempfile
import unittest


SCRIPT_PATH = Path(__file__).resolve().parents[1] / "bundle-client-sbs-models.py"
REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
RETIRED_ARCHIVE_DIRECTORY = (
    REPOSITORY_ROOT / "tools" / "model-sources" / "retired-client-sbs-archives"
)
RETIRED_ARTIFACT_SHA256 = {
    "client-sbs-dav2-models.tar.xz": (
        "3f9892624253e5d7301d6b0eb28acc7ef30ac2cf3131acbc7a8c1f59696ad148"
    ),
    "client-sbs-midas-models.tar.xz": (
        "166be90ec3866dfeae61ce7163df49414840b6d054466d79dbe153ea3ebc8b94"
    ),
    "client-sbs-depthart-models.tar.xz": (
        "1dccec4aa315288b5cc471a9d585d57e00d0e12a56870cb4712da5f20fb476a6"
    ),
    "LICENSE-MIDAS-MIT.txt": (
        "5a42e286153d7495b96f5c88b068b760ca1fa0717499f6356ea2ebfa90283e0a"
    ),
}
SPEC = importlib.util.spec_from_file_location("client_sbs_model_bundler", SCRIPT_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Unable to load {SCRIPT_PATH}")
TOOL = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(TOOL)


FIXTURES = {
    TOOL.ZIPDEPTH_MODELS[0]: b"shared-zipdepth-weights-" * 2048 + b"-672x384",
    TOOL.ZIPDEPTH_MODELS[1]: b"shared-zipdepth-weights-" * 2048 + b"-896x384",
    TOOL.ZIPDEPTH_MODELS[2]: b"shared-zipdepth-weights-" * 2048 + b"-928x384",
}


class BundleClientSbsModelsTest(unittest.TestCase):
    def _write_fixtures(self, directory: Path) -> None:
        directory.mkdir(parents=True)
        for name, contents in FIXTURES.items():
            (directory / name).write_bytes(contents)

    def test_archives_are_deterministic_solid_ustar_with_complete_root_entries(self) -> None:
        with tempfile.TemporaryDirectory(prefix="client-sbs-model-bundle-test-") as root:
            root_path = Path(root)
            input_directory = root_path / "input"
            first_output = root_path / "first"
            second_output = root_path / "second"
            self._write_fixtures(input_directory)

            first_metadata = TOOL.bundle_models(input_directory, first_output)
            second_metadata = TOOL.bundle_models(input_directory, second_output)

            self.assertEqual(9, TOOL.XZ_PRESET)
            self.assertEqual(64 * 1024 * 1024, TOOL.XZ_DICTIONARY_BYTES)
            self.assertEqual(first_metadata, second_metadata)
            for archive_name, model_names in TOOL.MODEL_FAMILIES:
                first_archive = first_output / archive_name
                second_archive = second_output / archive_name
                self.assertEqual(first_archive.read_bytes(), second_archive.read_bytes())

                # A single XZ stream wraps the complete multi-entry tar stream. This is the
                # standard solid-archive property that permits cross-model dictionary matches.
                decompressor = lzma.LZMADecompressor(format=lzma.FORMAT_XZ)
                uncompressed_tar = decompressor.decompress(first_archive.read_bytes())
                self.assertTrue(decompressor.eof)
                self.assertEqual(b"", decompressor.unused_data)
                self.assertGreater(len(uncompressed_tar), sum(map(len, (
                    FIXTURES[name] for name in model_names
                ))))

                with tarfile.open(first_archive, mode="r:xz") as archive:
                    members = archive.getmembers()
                    self.assertEqual(list(model_names), [member.name for member in members])
                    for member in members:
                        self.assertNotIn("/", member.name)
                        self.assertTrue(member.isreg())
                        self.assertEqual(0, member.mtime)
                        self.assertEqual(0o644, member.mode)
                        self.assertEqual(0, member.uid)
                        self.assertEqual(0, member.gid)
                        self.assertEqual("", member.uname)
                        self.assertEqual("", member.gname)
                        extracted = archive.extractfile(member)
                        self.assertIsNotNone(extracted)
                        self.assertEqual(FIXTURES[member.name], extracted.read())

                records = first_metadata[archive_name]
                self.assertEqual(list(model_names), [record["filename"] for record in records])
                for record in records:
                    model = FIXTURES[record["filename"]]
                    self.assertEqual(len(model), record["size"])
                    self.assertEqual(hashlib.sha256(model).hexdigest(), record["sha256"])

    def test_archive_contains_only_zipdepth_graphs(self) -> None:
        with tempfile.TemporaryDirectory(prefix="client-sbs-model-bundle-test-") as root:
            root_path = Path(root)
            input_directory = root_path / "input"
            output_directory = root_path / "output"
            self._write_fixtures(input_directory)

            TOOL.bundle_models(input_directory, output_directory)

            self.assertEqual(
                [TOOL.ZIPDEPTH_ARCHIVE_FILENAME],
                [path.name for path in output_directory.iterdir()],
            )
            with tarfile.open(
                output_directory / TOOL.ZIPDEPTH_ARCHIVE_FILENAME, mode="r:xz"
            ) as archive:
                self.assertEqual(list(TOOL.ZIPDEPTH_MODELS), archive.getnames())

    def test_retired_candidate_archives_remain_preserved_outside_android_assets(self) -> None:
        for filename, expected_sha256 in RETIRED_ARTIFACT_SHA256.items():
            artifact = RETIRED_ARCHIVE_DIRECTORY / filename
            self.assertTrue(artifact.is_file(), f"Missing preserved artifact: {artifact}")
            digest = hashlib.sha256()
            with artifact.open("rb") as stream:
                for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                    digest.update(chunk)
            self.assertEqual(expected_sha256, digest.hexdigest(), filename)

    def test_zipdepth_family_uses_original_base_short_384_static_buckets(self) -> None:
        self.assertEqual(
            "client-sbs-zipdepth-models.tar.xz",
            TOOL.ZIPDEPTH_ARCHIVE_FILENAME,
        )
        self.assertEqual(
            (
                "zipdepth-base-static-672x384-fp16weights.tflite.model",
                "zipdepth-base-static-896x384-fp16weights.tflite.model",
                "zipdepth-base-static-928x384-fp16weights.tflite.model",
            ),
            TOOL.ZIPDEPTH_MODELS,
        )

    def test_missing_required_model_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory(prefix="client-sbs-model-bundle-test-") as root:
            root_path = Path(root)
            input_directory = root_path / "input"
            self._write_fixtures(input_directory)
            (input_directory / TOOL.ZIPDEPTH_MODELS[-1]).unlink()

            with self.assertRaisesRegex(TOOL.ModelBundleError, "Missing production"):
                TOOL.bundle_models(input_directory, root_path / "output")

    def test_apollo_paths_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory(prefix="client-sbs-model-bundle-test-") as root:
            root_path = Path(root)
            input_directory = root_path / "input"
            self._write_fixtures(input_directory)

            with self.assertRaisesRegex(TOOL.ModelBundleError, "Apollo-3D"):
                TOOL.bundle_models(
                    input_directory,
                    root_path / "Apollo-3D" / "assets",
                )


if __name__ == "__main__":
    unittest.main()
