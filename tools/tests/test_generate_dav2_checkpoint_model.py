#!/usr/bin/env python3
"""Focused structural tests for generate-dav2-checkpoint-model.py."""

from __future__ import annotations

import importlib.util
from pathlib import Path
import tempfile
import unittest


SCRIPT_PATH = Path(__file__).resolve().parents[1] / "generate-dav2-checkpoint-model.py"
SPEC = importlib.util.spec_from_file_location("dav2_checkpoint_generator", SCRIPT_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Unable to load {SCRIPT_PATH}")
TOOL = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(TOOL)
SCHEMA = TOOL.schema


def make_tensor(name: str, shape: list[int]) -> object:
    tensor = SCHEMA.TensorT()
    tensor.name = name
    tensor.shape = shape.copy()
    tensor.shapeSignature = shape.copy()
    tensor.type = SCHEMA.TensorType.FLOAT32
    tensor.buffer = 0
    tensor.isVariable = False
    return tensor


def make_operator(input_index: int, output_index: int) -> object:
    operator = SCHEMA.OperatorT()
    operator.opcodeIndex = 0
    operator.inputs = [input_index]
    operator.outputs = [output_index]
    return operator


def make_model() -> object:
    opcode = SCHEMA.OperatorCodeT()
    opcode.builtinCode = SCHEMA.BuiltinOperator.RELU
    opcode.deprecatedBuiltinCode = SCHEMA.BuiltinOperator.RELU
    opcode.version = 1

    buffer = SCHEMA.BufferT()
    buffer.data = None

    subgraph = SCHEMA.SubGraphT()
    subgraph.name = "synthetic"
    subgraph.tensors = [
        make_tensor("input", [1, 2, 3, 3]),
        make_tensor("checkpoint", [1, 2, 3, 8]),
        make_tensor("output", [1, 2, 3, 1]),
        make_tensor("rank3", [1, 6, 8]),
        make_tensor("constant", [1, 1, 1, 8]),
    ]
    subgraph.inputs = [0]
    subgraph.outputs = [2]
    subgraph.operators = [
        make_operator(0, 1),
        make_operator(1, 2),
        make_operator(2, 3),
    ]

    signature_input = SCHEMA.TensorMapT()
    signature_input.name = "input"
    signature_input.tensorIndex = 0
    signature_output = SCHEMA.TensorMapT()
    signature_output.name = TOOL.EXPECTED_SIGNATURE_OUTPUT_NAME
    signature_output.tensorIndex = 2
    signature = SCHEMA.SignatureDefT()
    signature.signatureKey = TOOL.EXPECTED_SIGNATURE_KEY
    signature.subgraphIndex = 0
    signature.inputs = [signature_input]
    signature.outputs = [signature_output]

    model = SCHEMA.ModelT()
    model.version = 3
    model.operatorCodes = [opcode]
    model.subgraphs = [subgraph]
    model.description = "synthetic checkpoint test"
    model.buffers = [buffer]
    model.metadataBuffer = []
    model.metadata = []
    model.signatureDefs = [signature]
    return model


class CheckpointModelToolTest(unittest.TestCase):
    def test_patch_updates_graph_and_signature_and_round_trips(self) -> None:
        model = make_model()

        metadata = TOOL.patch_checkpoint_output(model, 1)
        serialized = TOOL.serialize_model(model)
        reparsed = TOOL.validate_serialized_structure(serialized, 1)

        self.assertEqual([1, 2, 3, 8], metadata["shape"])
        self.assertEqual("RELU", metadata["producer"]["opcode"])
        self.assertEqual([1], TOOL._ints(reparsed.subgraphs[0].outputs))
        self.assertEqual(1, int(reparsed.signatureDefs[0].outputs[0].tensorIndex))
        self.assertEqual(
            TOOL.EXPECTED_SIGNATURE_OUTPUT_NAME,
            TOOL._text(reparsed.signatureDefs[0].outputs[0].name),
        )

    def test_rejects_rank3_tensor(self) -> None:
        with self.assertRaisesRegex(TOOL.CheckpointModelError, "rank-4"):
            TOOL.patch_checkpoint_output(make_model(), 3)

    def test_rejects_constant_tensor(self) -> None:
        with self.assertRaisesRegex(TOOL.CheckpointModelError, "constant/unproduced"):
            TOOL.patch_checkpoint_output(make_model(), 4)

    def test_rank4_listing_excludes_constants_and_rank3(self) -> None:
        indices = {
            item["index"] for item in TOOL.eligible_rank4_tensors(make_model())
        }
        self.assertEqual({0, 1, 2}, indices)

    def test_rejects_production_source_as_output(self) -> None:
        source = TOOL.DEFAULT_SOURCE.resolve()
        with self.assertRaisesRegex(TOOL.CheckpointModelError, "verified source"):
            TOOL.validate_output_path(source, source, force=True)

    def test_default_source_uses_original_model_backup_directory(self) -> None:
        self.assertEqual(
            TOOL.ORIGINAL_MODEL_BACKUP_DIRECTORY / TOOL.ORIGINAL_MODEL_FILENAME,
            TOOL.DEFAULT_SOURCE,
        )

    def test_accepts_relocated_exact_original_source_identity(self) -> None:
        relocated = TOOL.REPOSITORY_ROOT / "temp" / "relocated-original.tflite"
        TOOL.validate_source_identity(
            relocated,
            TOOL.EXPECTED_SOURCE_SIZE,
            TOOL.EXPECTED_SOURCE_SHA256,
        )

    def test_rejects_transformed_production_source_identity(self) -> None:
        production = TOOL.PRODUCTION_ASSET_DIRECTORY / TOOL.ORIGINAL_MODEL_FILENAME
        with self.assertRaisesRegex(
            TOOL.CheckpointModelError, "production asset is transformed/optimized"
        ):
            TOOL.validate_source_identity(production, 1, "00" * 32)

    def test_rejects_apollo_source_path(self) -> None:
        source = TOOL.REPOSITORY_ROOT.parent / "Apollo-3D" / "source.tflite"
        with self.assertRaisesRegex(TOOL.CheckpointModelError, "Apollo-3D"):
            TOOL.validate_source_path(source)

    def test_rejects_apollo_output_path(self) -> None:
        source = TOOL.DEFAULT_SOURCE.resolve()
        output = TOOL.REPOSITORY_ROOT.parent / "Apollo-3D" / "checkpoint.tflite"
        with self.assertRaisesRegex(TOOL.CheckpointModelError, "Apollo-3D"):
            TOOL.validate_output_path(source, output, force=False)

    def test_refuses_existing_output_without_force(self) -> None:
        build_root = TOOL.REPOSITORY_ROOT / "build"
        build_root.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=build_root) as temporary_directory:
            directory = Path(temporary_directory)
            source = directory / "source.tflite"
            output = directory / "output.tflite"
            source.write_bytes(b"source")
            output.write_bytes(b"existing")
            with self.assertRaisesRegex(TOOL.CheckpointModelError, "already exists"):
                TOOL.validate_output_path(source, output, force=False)

    def test_rejects_production_asset_and_outside_repository_outputs(self) -> None:
        source = TOOL.DEFAULT_SOURCE.resolve()
        production = TOOL.PRODUCTION_ASSET_DIRECTORY / "checkpoint.tflite"
        with self.assertRaisesRegex(TOOL.CheckpointModelError, "production asset"):
            TOOL.validate_output_path(source, production, force=True)
        with tempfile.TemporaryDirectory() as temporary_directory:
            outside = Path(temporary_directory) / "checkpoint.tflite"
            with self.assertRaisesRegex(TOOL.CheckpointModelError, "moonlight-android"):
                TOOL.validate_output_path(source, outside, force=False)

    def test_source_size_guard_runs_before_flatbuffer_parse(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            source = Path(temporary_directory) / "wrong.tflite"
            source.write_bytes(b"TFL3")
            with self.assertRaisesRegex(TOOL.CheckpointModelError, "Source size"):
                TOOL.load_verified_source(source)


if __name__ == "__main__":
    unittest.main()
