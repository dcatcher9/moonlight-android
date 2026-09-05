#!/usr/bin/env python3
"""Focused tests for ZipDepth raw-to-host V2 scale calibration."""

from __future__ import annotations

import importlib.util
from pathlib import Path
import tempfile
import unittest

import numpy as np


SCRIPT_PATH = Path(__file__).resolve().parents[1] / "calibrate-zipdepth-v2-scale.py"
SPEC = importlib.util.spec_from_file_location("zipdepth_v2_calibration", SCRIPT_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Unable to load {SCRIPT_PATH}")
TOOL = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(TOOL)


class CalibrateZipDepthV2ScaleTest(unittest.TestCase):
    def test_area_resize_uses_exact_fractional_source_cell_coverage(self) -> None:
        source = np.array(
            [[1.0, 2.0, 4.0], [5.0, 6.0, 8.0]], dtype=np.float32
        )
        actual = TOOL.area_resize(source, (1, 2))
        expected = np.array([[10.0 / 3.0, 16.0 / 3.0]], dtype=np.float64)
        np.testing.assert_allclose(actual, expected, rtol=0.0, atol=1e-12)
        self.assertAlmostEqual(float(np.mean(source)), float(np.mean(actual)), places=6)

    def test_calibration_recovers_grouped_through_origin_scale(self) -> None:
        with tempfile.TemporaryDirectory(prefix="zipdepth-v2-calibration-") as root:
            root_path = Path(root)
            zip_root = root_path / "zip"
            host_root = root_path / "host"
            self._write_affine_clip(zip_root, host_root, "clip_a", (2, 3), 0.05, 0.0)
            self._write_affine_clip(zip_root, host_root, "clip_b", (2, 3), 0.05, 0.03)
            self._write_affine_clip(zip_root, host_root, "clip_c", (2, 4), 0.08, 0.01)

            report = TOOL.calibrate(zip_root, host_root, expected_shapes=None)

            self.assertEqual(3, report["clips"])
            self.assertEqual(9, report["frames"])
            self.assertEqual({"3x2", "4x2"}, set(report["groups"]))
            self.assertAlmostEqual(0.05, report["groups"]["3x2"]["scale"], places=7)
            self.assertAlmostEqual(0.08, report["groups"]["4x2"]["scale"], places=7)
            for group in report["groups"].values():
                self.assertTrue(group["high_is_near"])
                self.assertAlmostEqual(1.0, group["polarity_cosine"], places=10)
                self.assertLess(group["residual"]["rmse"], 1e-6)
                for clip in group["clips"]:
                    self.assertTrue(clip["high_is_near"])
                    self.assertLess(clip["residual"]["rmse"], 1e-6)

    def test_opposite_polarity_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory(prefix="zipdepth-v2-polarity-") as root:
            root_path = Path(root)
            zip_root = root_path / "zip"
            host_root = root_path / "host"
            self._write_affine_clip(
                zip_root, host_root, "clip", (2, 3), -0.05, 0.0
            )

            with self.assertRaisesRegex(TOOL.CalibrationError, "not high-is-near"):
                TOOL.calibrate(zip_root, host_root, expected_shapes=None)

    def test_non_finite_prediction_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory(prefix="zipdepth-v2-finite-") as root:
            root_path = Path(root)
            zip_root = root_path / "zip"
            host_root = root_path / "host"
            self._write_affine_clip(zip_root, host_root, "clip", (2, 3), 0.05, 0.0)
            bad_frame = zip_root / "clip" / "frame_00001.npy"
            depth = np.load(bad_frame)
            depth[0, 0] = np.nan
            np.save(bad_frame, depth)

            with self.assertRaisesRegex(TOOL.CalibrationError, "NaN or infinity"):
                TOOL.calibrate(zip_root, host_root, expected_shapes=None)

    @staticmethod
    def _write_affine_clip(
        zip_root: Path,
        host_root: Path,
        clip_name: str,
        shape: tuple[int, int],
        scale: float,
        clip_offset: float,
    ) -> None:
        zip_clip = zip_root / clip_name
        host_clip = host_root / clip_name
        zip_clip.mkdir(parents=True)
        host_clip.mkdir(parents=True)
        base = np.arange(shape[0] * shape[1], dtype=np.float64).reshape(shape)
        first = 0.2 + clip_offset + base * 0.01
        first_mean = float(np.mean(first))
        for frame_index, delta in enumerate((0.0, 0.02, -0.01)):
            zip_depth = first + delta * (base + 1.0)
            # Repeating every source cell 2x makes exact-area resize recover this target exactly.
            host_target = 3.0 + TOOL.HOST_V2_RAW_SCALE * (
                (zip_depth - first_mean) / scale
            )
            host_depth = np.repeat(np.repeat(host_target, 2, axis=0), 2, axis=1)
            name = f"frame_{frame_index:05d}.npy"
            np.save(zip_clip / name, zip_depth.astype(np.float32))
            np.save(host_clip / name, host_depth.astype(np.float32))


if __name__ == "__main__":
    unittest.main()
