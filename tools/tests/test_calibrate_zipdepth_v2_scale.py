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

            self.assertEqual("historical-shot-centered-fixed-divisor", report["policy"])
            self.assertFalse(report["current_adaptive_calibration"])
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

    def test_live_model_priors_are_read_from_their_java_owner(self) -> None:
        floors = TOOL.production_floors()
        self.assertEqual(TOOL.PRODUCTION_GRAPH_SHAPES, set(floors))
        self.assertTrue(all(value > 0.0 for value in floors.values()))
        self.assertGreater(TOOL.production_collapse_floor(), 0.0)

    def test_adaptive_histogram_uses_outer_bin_edges_not_exact_sorted_percentiles(self) -> None:
        depth = np.zeros((10, 10), dtype=np.float32)
        depth[-1, -1] = 100.0
        stats = TOOL.raw_geometry_statistics(depth, 0.1, 1e-6)
        self.assertEqual(0.0, stats["p05_lower_bin_edge"])
        self.assertEqual(100.0 / 256.0, stats["p95_upper_bin_edge"])
        self.assertEqual(100.0 / 512.0, stats["target_amplitude"])
        self.assertEqual(1.0, stats["mean"])
        self.assertTrue(stats["geometry_usable"])
        self.assertFalse(stats["amplitude_floor_active"])

    def test_small_contrast_uses_model_prior_and_collapsed_fields_are_unusable(self) -> None:
        stats = TOOL.raw_geometry_statistics(
            np.array([[0.1, 0.101], [0.1, 0.101]], dtype=np.float32), 0.05, 1e-6
        )
        self.assertEqual(0.05, stats["target_amplitude"])
        self.assertTrue(stats["amplitude_floor_active"])
        self.assertAlmostEqual(stats["population_std"] / 0.05,
                               stats["std_over_amplitude_floor"])
        flat = TOOL.raw_geometry_statistics(np.ones((2, 3), dtype=np.float32), 0.05, 1e-6)
        self.assertFalse(flat["geometry_usable"])
        self.assertFalse(flat["amplitude_floor_active"])
        self.assertIsNone(flat["target_amplitude"])
        self.assertIsNone(flat["robust_half_span"])

    def test_adaptive_audit_preserves_native_host_grid_and_records_exact_inputs(self) -> None:
        with tempfile.TemporaryDirectory(prefix="zipdepth-adaptive-audit-") as root:
            root_path = Path(root)
            zip_root, host_root = root_path / "zip", root_path / "host"
            (zip_root / "clip").mkdir(parents=True)
            (host_root / "clip").mkdir(parents=True)
            coarse = np.arange(4, dtype=np.float32).reshape(2, 2)
            fine = np.repeat(np.repeat(coarse, 2, axis=0), 2, axis=1)
            fine += (np.indices((4, 4)).sum(axis=0) % 2) * 8.0
            for frame in range(2):
                name = f"frame_{frame:05d}.npy"
                np.save(zip_root / "clip" / name, 0.2 + coarse + frame)
                np.save(host_root / "clip" / name, fine + frame)

            report = TOOL.audit_adaptive(
                zip_root, host_root, floors={(2, 2): 0.05},
                collapse_floor=1e-6, expected_shapes=None,
            )

            self.assertFalse(report["production_values_changed"])
            self.assertFalse(report["replacement_floor_fit"])
            self.assertFalse(report["temporal_controller_replayed"])
            self.assertIn("not supplied", report["source_observation_timestamps"])
            self.assertEqual(4, len(report["prediction_files"]))
            self.assertEqual(64, len(report["prediction_manifest_sha256"]))
            observations = report["groups"]["2x2"]["clips"][0]["observations"]
            self.assertEqual([4, 4], observations[0]["host_native"]["shape"])
            self.assertGreater(observations[0]["host_native"]["robust_half_span"], 4.0)
            resized = TOOL.raw_geometry_statistics(TOOL.area_resize(fine, (2, 2)), 2.25, 1e-6)
            self.assertLess(resized["robust_half_span"], 2.0)
            original_digest = report["prediction_manifest_sha256"]
            np.save(zip_root / "clip" / "frame_00001.npy", 0.2 + coarse + 1.01)
            revised = TOOL.audit_adaptive(
                zip_root, host_root, floors={(2, 2): 0.05},
                collapse_floor=1e-6, expected_shapes=None,
            )
            self.assertNotEqual(original_digest, revised["prediction_manifest_sha256"])

    def test_adaptive_audit_rejects_missing_pairs_and_unowned_graph_calibration(self) -> None:
        with tempfile.TemporaryDirectory(prefix="zipdepth-adaptive-pairing-") as root:
            root_path = Path(root)
            zip_root, host_root = root_path / "zip", root_path / "host"
            self._write_affine_clip(zip_root, host_root, "clip", (2, 3), 0.05, 0.0)
            with self.assertRaisesRegex(TOOL.CalibrationError, "No amplitude floor"):
                TOOL.audit_adaptive(zip_root, host_root, floors={},
                                    collapse_floor=1e-6, expected_shapes=None)
            (host_root / "clip" / "frame_00001.npy").unlink()
            with self.assertRaisesRegex(TOOL.CalibrationError, "pairing mismatch"):
                TOOL.audit_adaptive(zip_root, host_root, floors={(2, 3): 0.05},
                                    collapse_floor=1e-6, expected_shapes=None)

    def test_complex_depth_cannot_silently_discard_an_imaginary_component(self) -> None:
        with tempfile.TemporaryDirectory(prefix="zipdepth-complex-") as root:
            path = Path(root) / "depth.npy"
            np.save(path, np.ones((2, 3), dtype=np.complex64) + 2j)
            with self.assertRaisesRegex(TOOL.CalibrationError, "real numeric"):
                TOOL._load_depth(path, require_nonnegative=True)

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
