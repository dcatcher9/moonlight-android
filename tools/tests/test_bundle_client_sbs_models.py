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
SPEC = importlib.util.spec_from_file_location("client_sbs_model_bundler", SCRIPT_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Unable to load {SCRIPT_PATH}")
TOOL = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(TOOL)


FIXTURES = {
    TOOL.DEPTH_ANYTHING_MODELS[0]: b"shared-dav2-weights-" * 2048 + b"-322x182",
    TOOL.DEPTH_ANYTHING_MODELS[1]: b"shared-dav2-weights-" * 2048 + b"-350x154",
    TOOL.DEPTH_ANYTHING_MODELS[2]: b"shared-dav2-weights-" * 2048 + b"-434x126",
    TOOL.MIDAS_MODELS[0]: b"shared-midas-weights-" * 2048 + b"-352x192",
    TOOL.MIDAS_MODELS[1]: b"shared-midas-weights-" * 2048 + b"-384x160",
    TOOL.MIDAS_MODELS[2]: b"shared-midas-weights-" * 2048 + b"-448x128",
    TOOL.DEPTHART_MODELS[0]: b"shared-depthart-weights-" * 2048 + b"-672x384",
    TOOL.DEPTHART_MODELS[1]: b"shared-depthart-weights-" * 2048 + b"-928x384",
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

    def test_each_archive_contains_only_its_own_family(self) -> None:
        with tempfile.TemporaryDirectory(prefix="client-sbs-model-bundle-test-") as root:
            root_path = Path(root)
            input_directory = root_path / "input"
            output_directory = root_path / "output"
            self._write_fixtures(input_directory)

            TOOL.bundle_models(input_directory, output_directory)

            with tarfile.open(
                output_directory / TOOL.DEPTH_ANYTHING_ARCHIVE_FILENAME, mode="r:xz"
            ) as archive:
                self.assertEqual(list(TOOL.DEPTH_ANYTHING_MODELS), archive.getnames())
            with tarfile.open(
                output_directory / TOOL.MIDAS_ARCHIVE_FILENAME, mode="r:xz"
            ) as archive:
                self.assertEqual(list(TOOL.MIDAS_MODELS), archive.getnames())
            with tarfile.open(
                output_directory / TOOL.DEPTHART_ARCHIVE_FILENAME, mode="r:xz"
            ) as archive:
                self.assertEqual(list(TOOL.DEPTHART_MODELS), archive.getnames())
            with tarfile.open(
                output_directory / TOOL.ZIPDEPTH_ARCHIVE_FILENAME, mode="r:xz"
            ) as archive:
                self.assertEqual(list(TOOL.ZIPDEPTH_MODELS), archive.getnames())

    def test_depthart_family_uses_the_short_384_static_buckets(self) -> None:
        self.assertEqual(
            "client-sbs-depthart-models.tar.xz",
            TOOL.DEPTHART_ARCHIVE_FILENAME,
        )
        self.assertEqual(
            (
                "depthart-s448-static-672x384-fp16weights.tflite.model",
                "depthart-s448-static-928x384-fp16weights.tflite.model",
            ),
            TOOL.DEPTHART_MODELS,
        )

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
            (input_directory / TOOL.MIDAS_MODELS[-1]).unlink()

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
