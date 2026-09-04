#!/usr/bin/env python3
"""Contract tests for the guarded DepthART FP16 LayerNorm stabilizer."""

from __future__ import annotations

import importlib.util
from pathlib import Path
import unittest

import numpy as np


SCRIPT_PATH = (
    Path(__file__).resolve().parents[1] / "stabilize-depthart-fp16-layernorm.py"
)
SPEC = importlib.util.spec_from_file_location("depthart_layernorm_stabilizer", SCRIPT_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Unable to load {SCRIPT_PATH}")
TOOL = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(TOOL)


class DepthArtLayerNormStabilizerTest(unittest.TestCase):
    def test_production_inputs_and_outputs_are_hash_pinned(self) -> None:
        self.assertEqual(
            {
                (672, 384): (
                    "62492475402f84f55998ed5d9c7ff9a56988684631967e3ef2c89f78a97af019",
                    "3de0ded3a2329a6cc4c89da535f4c1f3035dfc30c7e85359d48580003aad780b",
                    2230,
                    2231,
                ),
                (928, 384): (
                    "65790fd4b8810b0d337781f99159e680c2efed0fe2c03e75bc7c78e3cc4f098e",
                    "d166bb5dcbe16ea386640a344a80134da8e225837f4609eae64f57916ec757f2",
                    2370,
                    2371,
                ),
            },
            {
                geometry: (
                    contract["source_sha256"],
                    contract["output_sha256"],
                    contract["source_operators"],
                    contract["output_operators"],
                )
                for geometry, contract in TOOL.PRODUCTION_CONTRACTS.items()
            },
        )

    def test_scaled_expression_is_algebraically_equivalent(self) -> None:
        centered = np.asarray(
            [[0.003, -0.002, 0.001], [0.1, -0.08, -0.02]], dtype=np.float32
        )
        original = centered / np.sqrt(
            np.mean(centered * centered, axis=-1, keepdims=True)
            + TOOL.ORIGINAL_EPSILON
        )
        scaled = TOOL.SCALE * centered
        stabilized = scaled / np.sqrt(
            np.mean(scaled * scaled, axis=-1, keepdims=True)
            + TOOL.SCALED_EPSILON
        )
        np.testing.assert_allclose(stabilized, original, rtol=1.0e-6, atol=1.0e-6)
        self.assertGreaterEqual(TOOL.SCALED_EPSILON, TOOL.FP16_MINIMUM_NORMAL)

    def test_only_short_384_production_geometries_are_accepted(self) -> None:
        self.assertEqual(2230, TOOL.contract_for(672, 384)["source_operators"])
        with self.assertRaisesRegex(TOOL.StabilizationError, "Unsupported guarded"):
            TOOL.contract_for(448, 448)


if __name__ == "__main__":
    unittest.main()
