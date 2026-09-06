"""Small real-TFLite regression tests; no production models or device required."""
from __future__ import annotations

import copy
import importlib.util
from pathlib import Path
import unittest

import numpy as np

SCRIPT = Path(__file__).resolve().parents[1] / "rewrite-zipdepth-output-scatter.py"
SPEC = importlib.util.spec_from_file_location("zipdepth_output_scatter", SCRIPT)
TOOL = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(TOOL)
S = TOOL.schema


def fixture():
    model = S.ModelT()
    model.version = 3
    model.operatorCodes = []
    for code in (S.BuiltinOperator.TRANSPOSE_CONV, S.BuiltinOperator.RELU):
        opcode = S.OperatorCodeT()
        opcode.builtinCode = opcode.deprecatedBuiltinCode = code
        opcode.version = 1
        model.operatorCodes.append(opcode)
    model.buffers = [S.BufferT()]
    for value in (np.array([1, 4, 6, 1], dtype="<i4"),
                  np.eye(4, dtype="<f4").reshape(1, 2, 2, 4)):
        buffer = S.BufferT()
        buffer.data = np.frombuffer(value.tobytes(), dtype=np.uint8).copy()
        model.buffers.append(buffer)
    graph = S.SubGraphT()
    graph.tensors = []
    for name, shape, dtype, buffer in (
            ("subpixels", [1, 2, 3, 4], S.TensorType.FLOAT32, 0),
            ("output_shape", [4], S.TensorType.INT32, 1),
            ("identity_kernel", [1, 2, 2, 4], S.TensorType.FLOAT32, 2),
            ("scattered", [1, 4, 6, 1], S.TensorType.FLOAT32, 0),
            ("depth", [1, 4, 6, 1], S.TensorType.FLOAT32, 0)):
        tensor = S.TensorT()
        tensor.name = name
        tensor.shape = tensor.shapeSignature = shape
        tensor.type, tensor.buffer = dtype, buffer
        graph.tensors.append(tensor)
    scatter = S.OperatorT()
    scatter.opcodeIndex = 0
    scatter.inputs, scatter.outputs = [1, 2, 0], [3]
    scatter.builtinOptionsType = S.BuiltinOptions.TransposeConvOptions
    scatter.builtinOptions = S.TransposeConvOptionsT()
    scatter.builtinOptions.strideH = scatter.builtinOptions.strideW = 2
    scatter.builtinOptions.padding = S.Padding.VALID
    scatter.builtinOptions.fusedActivationFunction = S.ActivationFunctionType.NONE
    relu = S.OperatorT()
    relu.opcodeIndex = 1
    relu.inputs, relu.outputs = [3], [4]
    graph.operators, graph.inputs, graph.outputs = [scatter, relu], [0], [4]
    model.subgraphs = [graph]
    return model


class RewriteZipDepthOutputScatterTest(unittest.TestCase):
    def test_actual_tflite_scatter_and_subpixel_rearrangement_are_bit_exact(self):
        model = fixture()
        source = TOOL.COMPAT.serialize_model(model)
        TOOL.rewrite_scatter(model)
        candidate = TOOL.COMPAT.serialize_model(model)
        # Unique values prove every subpixel location, with negative/zero samples
        # exercising the retained terminal ReLU. A second invocation checks updates.
        ramp = np.arange(-8, 16, dtype=np.float32).reshape(1, 2, 3, 4)
        TOOL.validate_cpu_outputs(source, candidate, [ramp, np.flip(ramp, axis=2).copy()])

    def test_rewrite_retains_weights_public_contract_and_final_relu(self):
        model = fixture()
        graph = model.subgraphs[0]
        tensors, buffers = graph.tensors.copy(), copy.deepcopy(model.buffers)
        public = TOOL.COMPAT.public_contract(model)
        relu = graph.operators[1]
        self.assertEqual(0, TOOL.rewrite_scatter(model))
        self.assertEqual(2, len(graph.operators))
        self.assertIs(relu, graph.operators[1])
        self.assertEqual(tensors, graph.tensors)
        self.assertEqual(public, TOOL.COMPAT.public_contract(model))
        for before, after in zip(buffers, model.buffers):
            np.testing.assert_array_equal(before.data, after.data)
        replacement = graph.operators[0]
        self.assertEqual(S.BuiltinOperator.DEPTH_TO_SPACE,
                         TOOL.COMPAT.builtin_code(model, replacement))
        self.assertEqual([0], list(replacement.inputs))
        self.assertEqual([3], list(replacement.outputs))
        self.assertEqual(2, replacement.builtinOptions.blockSize)

    def test_rejects_permuted_or_learned_scatter_weights(self):
        for kernel in (np.eye(4, dtype="<f4")[:, ::-1].copy(),
                       np.eye(4, dtype="<f4") * 2,
                       np.full((4, 4), np.nan, dtype="<f4")):
            with self.subTest(kernel=kernel):
                model = fixture()
                model.buffers[2].data = np.frombuffer(kernel.tobytes(), dtype=np.uint8)
                with self.assertRaisesRegex(TOOL.RewriteError, "identity kernel"):
                    TOOL.rewrite_scatter(model)

    def test_rejects_bias_stride_padding_and_fused_activation_changes(self):
        for field, value in (("strideW", 1), ("strideH", 3),
                             ("padding", S.Padding.SAME),
                             ("fusedActivationFunction", S.ActivationFunctionType.RELU)):
            with self.subTest(field=field):
                model = fixture()
                setattr(model.subgraphs[0].operators[0].builtinOptions, field, value)
                with self.assertRaisesRegex(TOOL.RewriteError, "stride2 VALID"):
                    TOOL.rewrite_scatter(model)
        model = fixture()
        model.subgraphs[0].operators[0].inputs.append(2)
        with self.assertRaisesRegex(TOOL.RewriteError, "no bias"):
            TOOL.rewrite_scatter(model)

    def test_rejects_wrong_layout_shape_type_or_output_constant(self):
        for tensor_id, field, value in (
                (0, "shape", [1, 4, 2, 3]), (3, "shape", [1, 6, 4, 1]),
                (0, "type", S.TensorType.FLOAT16)):
            with self.subTest(tensor=tensor_id, field=field):
                model = fixture()
                setattr(model.subgraphs[0].tensors[tensor_id], field, value)
                with self.assertRaises(TOOL.RewriteError):
                    TOOL.rewrite_scatter(model)
        model = fixture()
        model.buffers[1].data = np.array([1, 6, 4, 1], dtype="<i4").view(np.uint8)
        with self.assertRaisesRegex(TOOL.RewriteError, "output-shape constant"):
            TOOL.rewrite_scatter(model)

    def test_rejects_extra_consumers_missing_relu_or_repeated_rewrite(self):
        model = fixture()
        model.subgraphs[0].operators.append(copy.deepcopy(model.subgraphs[0].operators[1]))
        with self.assertRaisesRegex(TOOL.RewriteError, "terminal ReLU"):
            TOOL.rewrite_scatter(model)
        model = fixture()
        model.subgraphs[0].outputs = [3]
        with self.assertRaisesRegex(TOOL.RewriteError, "public output through ReLU"):
            TOOL.rewrite_scatter(model)
        model = fixture()
        TOOL.rewrite_scatter(model)
        with self.assertRaisesRegex(TOOL.RewriteError, "exactly one"):
            TOOL.rewrite_scatter(model)

    def test_hash_guards_reject_unknown_models_before_parsing(self):
        raw = TOOL.COMPAT.serialize_model(fixture())
        with self.assertRaisesRegex(TOOL.RewriteError, "SHA-256 mismatch"):
            TOOL.generate(raw, "0" * 64)
        with self.assertRaisesRegex(TOOL.RewriteError, "not a pinned production"):
            TOOL.generate(raw, TOOL.COMPAT.sha256_bytes(raw))


if __name__ == "__main__":
    unittest.main()
