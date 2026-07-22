#!/usr/bin/env python3
"""Tests for guarded Client SBS FP16 weight storage conversion."""

from __future__ import annotations

import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest

import numpy as np


SCRIPT_PATH = Path(__file__).resolve().parents[1] / "convert-tflite-fp16-weights.py"
SPEC = importlib.util.spec_from_file_location("tflite_fp16_weight_converter", SCRIPT_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Unable to load {SCRIPT_PATH}")
TOOL = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(TOOL)
SCHEMA = TOOL.schema


def make_tensor(name: str, shape: list[int], buffer: int = 0) -> object:
    tensor = SCHEMA.TensorT()
    tensor.name = name
    tensor.shape = shape.copy()
    tensor.shapeSignature = shape.copy()
    tensor.type = SCHEMA.TensorType.FLOAT32
    tensor.buffer = buffer
    tensor.isVariable = False
    return tensor


def make_mul_model() -> object:
    model = SCHEMA.ModelT()
    model.version = 3
    model.description = "FP16 weight conversion test"

    opcode = SCHEMA.OperatorCodeT()
    opcode.builtinCode = SCHEMA.BuiltinOperator.MUL
    opcode.deprecatedBuiltinCode = SCHEMA.BuiltinOperator.MUL
    opcode.version = 1
    model.operatorCodes = [opcode]

    empty_buffer = SCHEMA.BufferT()
    weight_buffer = SCHEMA.BufferT()
    values = np.array([1.0, 2.0, 3.0, 4.0, 5.0, 6.0], dtype="<f4")
    weight_buffer.data = np.frombuffer(values.tobytes(), dtype=np.uint8).copy()
    model.buffers = [empty_buffer, weight_buffer]

    subgraph = SCHEMA.SubGraphT()
    subgraph.name = "main"
    subgraph.tensors = [
        make_tensor("rgb_nhwc", [1, 1, 2, 3]),
        make_tensor("weights", [1, 1, 2, 3], buffer=1),
        make_tensor("depth_bhwc", [1, 1, 2, 3]),
    ]
    subgraph.inputs = [0]
    subgraph.outputs = [2]

    operator = SCHEMA.OperatorT()
    operator.opcodeIndex = 0
    operator.inputs = [0, 1]
    operator.outputs = [2]
    operator.builtinOptionsType = SCHEMA.BuiltinOptions.MulOptions
    operator.builtinOptions = SCHEMA.MulOptionsT()
    subgraph.operators = [operator]
    model.subgraphs = [subgraph]
    return model


class ConvertTfliteFp16WeightsTest(unittest.TestCase):
    def test_conversion_preserves_float32_contract_and_cpu_output(self) -> None:
        build_directory = TOOL.REPOSITORY_ROOT / "build"
        build_directory.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(
            prefix="fp16-weight-tool-test-", dir=build_directory
        ) as directory:
            source = Path(directory) / "source.tflite.model"
            output = Path(directory) / "output.tflite.model"
            TOOL.serialize_model(make_mul_model(), source)
            source_digest = hashlib.sha256(source.read_bytes()).hexdigest()

            conversion, parity = TOOL.convert_file(
                source,
                output,
                expected_source_sha256=source_digest,
                minimum_buffer_bytes=2,
            )

            self.assertEqual(1, conversion["converted_tensors"])
            self.assertEqual(12, conversion["weight_bytes_saved"])
            self.assertEqual(1.0, parity["correlation"])
            self.assertEqual(source_digest, hashlib.sha256(source.read_bytes()).hexdigest())

            converted = TOOL.load_model(output)
            subgraph = converted.subgraphs[0]
            self.assertEqual(SCHEMA.TensorType.FLOAT32, subgraph.tensors[0].type)
            self.assertEqual(SCHEMA.TensorType.FLOAT32, subgraph.tensors[2].type)
            self.assertEqual(0, subgraph.tensors[1].buffer)
            self.assertEqual(SCHEMA.TensorType.FLOAT16, subgraph.tensors[3].type)
            self.assertEqual(1, subgraph.tensors[3].buffer)
            self.assertEqual([3], list(subgraph.operators[0].inputs))
            self.assertEqual([1], list(subgraph.operators[0].outputs))
            self.assertEqual([0, 1], list(subgraph.operators[1].inputs))

    def test_rejects_shared_weight_buffer(self) -> None:
        model = make_mul_model()
        duplicate = make_tensor("duplicate_weights", [1, 1, 2, 3], buffer=1)
        model.subgraphs[0].tensors.append(duplicate)
        model.subgraphs[0].operators[0].inputs = [0, 1, 3]
        with self.assertRaisesRegex(TOOL.WeightConversionError, "shares buffer"):
            TOOL.convert_model(model, minimum_buffer_bytes=2)

    def test_rejects_apollo_paths(self) -> None:
        with self.assertRaisesRegex(TOOL.WeightConversionError, "Apollo-3D"):
            TOOL.validate_paths(
                TOOL.REPOSITORY_ROOT / "source.tflite",
                TOOL.REPOSITORY_ROOT.parent / "Apollo-3D" / "model.tflite",
            )


if __name__ == "__main__":
    unittest.main()
