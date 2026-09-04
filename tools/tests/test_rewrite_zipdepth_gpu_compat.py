#!/usr/bin/env python3
"""Focused tests for the guarded ZipDepth GPU compatibility rewrites."""

from __future__ import annotations

import importlib.util
import hashlib
from pathlib import Path
import unittest
from types import SimpleNamespace
import tempfile
from unittest import mock

import numpy as np


SCRIPT_PATH = Path(__file__).resolve().parents[1] / "rewrite-zipdepth-gpu-compat.py"
SPEC = importlib.util.spec_from_file_location("zipdepth_gpu_rewriter", SCRIPT_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Unable to load {SCRIPT_PATH}")
TOOL = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(TOOL)
SCHEMA = TOOL.schema


def make_buffer(values: np.ndarray | None = None) -> object:
    buffer = SCHEMA.BufferT()
    if values is not None:
        buffer.data = np.frombuffer(values.tobytes(), dtype=np.uint8).copy()
    return buffer


def make_tensor(name: str, shape: list[int], tensor_type: int,
                buffer: int = 0) -> object:
    tensor = SCHEMA.TensorT()
    tensor.name = name
    tensor.shape = shape.copy()
    tensor.shapeSignature = shape.copy()
    tensor.type = tensor_type
    tensor.buffer = buffer
    tensor.isVariable = False
    return tensor


def make_opcode(code: int) -> object:
    opcode = SCHEMA.OperatorCodeT()
    opcode.builtinCode = code
    opcode.deprecatedBuiltinCode = code
    opcode.version = 1
    return opcode


def make_operator(opcode_index: int, inputs: list[int], outputs: list[int],
                  options_type: int, options: object | None) -> object:
    operator = SCHEMA.OperatorT()
    operator.opcodeIndex = opcode_index
    operator.inputs = inputs
    operator.outputs = outputs
    operator.builtinOptionsType = options_type
    operator.builtinOptions = options
    return operator


def make_group_conv_model(shared_filter_buffer: bool = False) -> tuple[object, np.ndarray]:
    model = SCHEMA.ModelT()
    model.version = 3
    model.operatorCodes = [make_opcode(SCHEMA.BuiltinOperator.CONV_2D)]
    weights = np.arange(32 * 6, dtype="<f4").reshape(32, 1, 1, 6)
    bias = np.arange(32, dtype="<f4")
    model.buffers = [make_buffer(), make_buffer(weights), make_buffer(bias)]

    tensors = [
        make_tensor("input", [1, 8, 8, 24], SCHEMA.TensorType.FLOAT32),
        make_tensor("group_filter", [32, 1, 1, 6], SCHEMA.TensorType.FLOAT32, 1),
        make_tensor("bias", [32], SCHEMA.TensorType.FLOAT32, 2),
        make_tensor("output", [1, 8, 8, 32], SCHEMA.TensorType.FLOAT32),
    ]
    if shared_filter_buffer:
        tensors.append(make_tensor(
            "shared_filter", [32, 1, 1, 6], SCHEMA.TensorType.FLOAT32, 1
        ))
    options = SCHEMA.Conv2DOptionsT()
    options.padding = SCHEMA.Padding.SAME
    options.strideH = 1
    options.strideW = 1
    options.dilationHFactor = 1
    options.dilationWFactor = 1
    options.fusedActivationFunction = SCHEMA.ActivationFunctionType.NONE
    operation = make_operator(
        0, [0, 1, 2], [3], SCHEMA.BuiltinOptions.Conv2DOptions, options
    )
    subgraph = SCHEMA.SubGraphT()
    subgraph.tensors = tensors
    subgraph.inputs = [0]
    subgraph.outputs = [3]
    subgraph.operators = [operation]
    model.subgraphs = [subgraph]
    return model, weights


def make_gcb_model(sum_axes: tuple[int, int] = (2, 3)) -> object:
    model = SCHEMA.ModelT()
    model.version = 3
    codes = (
        SCHEMA.BuiltinOperator.SOFTMAX,
        SCHEMA.BuiltinOperator.RESHAPE,
        SCHEMA.BuiltinOperator.MUL,
        SCHEMA.BuiltinOperator.SUM,
        SCHEMA.BuiltinOperator.TRANSPOSE,
    )
    model.operatorCodes = [make_opcode(code) for code in codes]
    model.buffers = [
        make_buffer(),
        make_buffer(np.asarray([1, 1, 24, 42], dtype="<i4")),
        make_buffer(np.asarray(sum_axes, dtype="<i4")),
        make_buffer(np.asarray([0, 2, 3, 1], dtype="<i4")),
    ]
    model.subgraphs = []
    subgraph = SCHEMA.SubGraphT()
    subgraph.tensors = [
        make_tensor("image", [1, 384, 672, 3], SCHEMA.TensorType.FLOAT32),
        make_tensor("logits", [1, 1, 1008], SCHEMA.TensorType.FLOAT32),
        make_tensor("softmax", [1, 1, 1008], SCHEMA.TensorType.FLOAT32),
        make_tensor("reshape_shape", [4], SCHEMA.TensorType.INT32, 1),
        make_tensor("probability", [1, 1, 24, 42], SCHEMA.TensorType.FLOAT32),
        make_tensor("features", [1, 192, 24, 42], SCHEMA.TensorType.FLOAT32),
        make_tensor("products", [1, 192, 24, 42], SCHEMA.TensorType.FLOAT32),
        make_tensor("sum_axes", [2], SCHEMA.TensorType.INT32, 2),
        make_tensor("context", [1, 192, 1, 1], SCHEMA.TensorType.FLOAT32),
        make_tensor("transpose_perm", [4], SCHEMA.TensorType.INT32, 3),
        make_tensor("context_nhwc", [1, 1, 1, 192], SCHEMA.TensorType.FLOAT32),
    ]
    softmax_options = SCHEMA.SoftmaxOptionsT()
    softmax_options.beta = 1.0
    reshape_options = SCHEMA.ReshapeOptionsT()
    reshape_options.newShape = [1, 1, 24, 42]
    mul_options = SCHEMA.MulOptionsT()
    mul_options.fusedActivationFunction = SCHEMA.ActivationFunctionType.NONE
    reducer_options = SCHEMA.ReducerOptionsT()
    reducer_options.keepDims = True
    subgraph.operators = [
        make_operator(0, [1], [2], SCHEMA.BuiltinOptions.SoftmaxOptions,
                      softmax_options),
        make_operator(1, [2, 3], [4], SCHEMA.BuiltinOptions.ReshapeOptions,
                      reshape_options),
        make_operator(2, [5, 4], [6], SCHEMA.BuiltinOptions.MulOptions,
                      mul_options),
        make_operator(3, [6, 7], [8], SCHEMA.BuiltinOptions.ReducerOptions,
                      reducer_options),
        make_operator(4, [8, 9], [10], SCHEMA.BuiltinOptions.NONE, None),
    ]
    subgraph.inputs = [0]
    subgraph.outputs = [10]
    model.subgraphs = [subgraph]
    return model


class RewriteZipDepthGpuCompatTest(unittest.TestCase):
    def test_group_conv_is_exact_block_diagonal_dense_filter(self) -> None:
        model, grouped = make_group_conv_model()
        detail = TOOL.densify_group_conv(model)

        self.assertEqual([32, 1, 1, 24], detail["new_shape"])
        tensor = model.subgraphs[0].tensors[1]
        dense = TOOL.constant_float32(model, tensor)
        self.assertEqual((32, 1, 1, 24), dense.shape)
        for group in range(4):
            output_slice = slice(group * 8, (group + 1) * 8)
            input_slice = slice(group * 6, (group + 1) * 6)
            np.testing.assert_array_equal(
                grouped[output_slice], dense[output_slice, :, :, input_slice]
            )
            off_group = dense[output_slice].copy()
            off_group[:, :, :, input_slice] = 0
            self.assertEqual(0, np.count_nonzero(off_group))

    def test_group_conv_rejects_shared_weight_buffer(self) -> None:
        model, _ = make_group_conv_model(shared_filter_buffer=True)
        with self.assertRaisesRegex(TOOL.ZipDepthRewriteError, "shares its buffer"):
            TOOL.densify_group_conv(model)

    def test_gcb_inserts_exact_power_of_two_scale_around_weighted_sum(self) -> None:
        model = make_gcb_model()
        detail = TOOL.stabilize_gcb(model)
        subgraph = model.subgraphs[0]

        self.assertEqual(1024.0, detail["scale"])
        self.assertEqual(7, len(subgraph.operators))
        scale_op, weighted_op, sum_op, unscale_op = (
            subgraph.operators[index] for index in (2, 3, 4, 5)
        )
        scale_tensor = subgraph.tensors[int(scale_op.inputs[1])]
        inverse_tensor = subgraph.tensors[int(unscale_op.inputs[1])]
        self.assertEqual(1024.0, float(TOOL.constant_float32(model, scale_tensor)[0]))
        self.assertEqual(
            1.0 / 1024.0,
            float(TOOL.constant_float32(model, inverse_tensor)[0]),
        )
        self.assertEqual(int(scale_op.outputs[0]), int(weighted_op.inputs[1]))
        self.assertEqual(int(sum_op.outputs[0]), int(unscale_op.inputs[0]))
        self.assertEqual([8], list(unscale_op.outputs))
        self.assertEqual([10], list(subgraph.outputs))

    def test_gcb_rejects_wrong_sum_axes(self) -> None:
        with self.assertRaisesRegex(TOOL.ZipDepthRewriteError, r"axes \[2, 3\]"):
            TOOL.stabilize_gcb(make_gcb_model(sum_axes=(1, 2)))

    def test_known_sources_pin_deterministic_outputs(self) -> None:
        self.assertEqual(
            {
                "densify-group-conv": 3,
                "stabilize-gcb": 3,
            },
            {name: len(records) for name, records in TOOL.KNOWN_REWRITES.items()},
        )
        for records in TOOL.KNOWN_REWRITES.values():
            for source_hash, record in records.items():
                self.assertEqual(64, len(source_hash))
                self.assertEqual(64, len(record["output_sha256"]))

    def test_main_refuses_existing_output_without_force(self) -> None:
        build_directory = TOOL.REPOSITORY_ROOT / "build"
        build_directory.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(
            prefix="zipdepth-rewrite-test-", dir=build_directory
        ) as directory:
            root = Path(directory)
            source = root / "source.tflite"
            output = root / "output.tflite"
            output.write_bytes(b"do not replace")
            arguments = SimpleNamespace(
                source=source,
                output=output,
                expected_source_sha256="0" * 64,
                rewrite="densify-group-conv",
                force=False,
            )
            with mock.patch.object(TOOL, "parse_args", return_value=arguments):
                with self.assertRaisesRegex(TOOL.ZipDepthRewriteError,
                                            "Output already exists"):
                    TOOL.main()
            self.assertEqual(b"do not replace", output.read_bytes())

    def test_main_rejects_unpinned_source_even_with_matching_cli_hash(self) -> None:
        build_directory = TOOL.REPOSITORY_ROOT / "build"
        build_directory.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(
            prefix="zipdepth-rewrite-test-", dir=build_directory
        ) as directory:
            root = Path(directory)
            source = root / "untrusted.tflite"
            output = root / "output.tflite"
            source.write_bytes(b"untrusted graph")
            digest = hashlib.sha256(source.read_bytes()).hexdigest()
            arguments = SimpleNamespace(
                source=source,
                output=output,
                expected_source_sha256=digest,
                rewrite="stabilize-gcb",
                force=False,
            )
            with mock.patch.object(TOOL, "parse_args", return_value=arguments):
                with self.assertRaisesRegex(TOOL.ZipDepthRewriteError,
                                            "not a pinned"):
                    TOOL.main()
            self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
