#!/usr/bin/env python3
"""Tests for shared loose Client SBS model path guards."""

from __future__ import annotations

import importlib.util
from pathlib import Path
import tempfile
import unittest


SCRIPT_PATH = Path(__file__).resolve().parents[1] / "client_sbs_model_paths.py"
SPEC = importlib.util.spec_from_file_location("client_sbs_model_paths", SCRIPT_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Unable to load {SCRIPT_PATH}")
PATHS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PATHS)


class ClientSbsModelPathsTest(unittest.TestCase):
    def test_client_build_and_temp_outputs_are_allowed(self) -> None:
        with tempfile.TemporaryDirectory(prefix="client-sbs-path-test-") as directory:
            root = Path(directory)
            repository = root / "moonlight-android"
            source = root / "downloads" / "source.tflite"

            for output in (
                repository / "build" / "models",
                repository / "temp" / "model.tflite",
            ):
                resolved_source, resolved_output = PATHS.validate_loose_model_paths(
                    source, output, repository
                )
                self.assertEqual(source.resolve(), resolved_source)
                self.assertEqual(output.resolve(), resolved_output)

    def test_output_outside_client_staging_trees_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory(prefix="client-sbs-path-test-") as directory:
            root = Path(directory)
            repository = root / "moonlight-android"
            with self.assertRaisesRegex(PATHS.LooseModelPathError, "build/ or temp/"):
                PATHS.validate_loose_model_paths(
                    root / "downloads" / "source.tflite",
                    repository / "models" / "output.tflite",
                    repository,
                )

    def test_apollo_source_and_output_are_rejected_case_insensitively(self) -> None:
        with tempfile.TemporaryDirectory(prefix="client-sbs-path-test-") as directory:
            root = Path(directory)
            repository = root / "moonlight-android"
            safe_source = root / "downloads" / "source.tflite"
            safe_output = repository / "build" / "output.tflite"

            with self.assertRaisesRegex(PATHS.LooseModelPathError, "Apollo-3D"):
                PATHS.validate_loose_model_paths(
                    root / "APOLLO-3D" / "source.tflite", safe_output, repository
                )
            with self.assertRaisesRegex(PATHS.LooseModelPathError, "Apollo-3D"):
                PATHS.validate_loose_model_paths(
                    safe_source,
                    repository / "build" / "Apollo-3D" / "output.tflite",
                    repository,
                )

    def test_android_source_asset_inputs_and_outputs_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory(prefix="client-sbs-path-test-") as directory:
            root = Path(directory)
            repository = root / "moonlight-android"
            asset = repository / "app" / "src" / "nonRoot_game" / "assets" / "model"

            with self.assertRaisesRegex(PATHS.LooseModelPathError, "source assets"):
                PATHS.validate_loose_model_paths(
                    asset, repository / "build" / "output.tflite", repository
                )
            with self.assertRaisesRegex(PATHS.LooseModelPathError, "source assets"):
                PATHS.validate_loose_model_paths(
                    root / "downloads" / "source.tflite", asset, repository
                )

    def test_source_cannot_be_replaced_in_place(self) -> None:
        with tempfile.TemporaryDirectory(prefix="client-sbs-path-test-") as directory:
            repository = Path(directory) / "moonlight-android"
            source = repository / "build" / "source.tflite"
            with self.assertRaisesRegex(PATHS.LooseModelPathError, "differ"):
                PATHS.validate_loose_model_paths(source, source, repository)


if __name__ == "__main__":
    unittest.main()
