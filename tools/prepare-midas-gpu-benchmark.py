#!/usr/bin/env python3
"""Prepare the LiteRT Community MiDaS Small graph for Artemis GL benchmarking.

The upstream FP16-weight model exposes a rank-3 [1, H, W] output by applying a
terminal RESHAPE to an already rank-4 [1, H, W, 1] tensor. Artemis intentionally
requires packed Float32 BHWC public tensors. Instead of reserializing the model
(which would invalidate its external weight-buffer offsets), this tool changes
the subgraph's single output index in place to the rank-4 producer tensor.

Only the subgraph and signature tensor-index scalars are rewritten in place.
All operators, constants, external weights, and model size remain byte-for-byte
in place. Full OpenCL delegation is still validated by the on-device benchmark.
"""

from __future__ import annotations

import argparse
import gc
import hashlib
import os
from pathlib import Path
import struct
import tempfile

from ai_edge_litert import schema_py_generated as schema
from ai_edge_litert.interpreter import Interpreter

from client_sbs_model_paths import validate_loose_model_paths


EXPECTED_SOURCE_SHA256 = (
    "bec9bce704789e504ec306196fcb0aabe90fd25c2b9d7db382339741950890ca"
)
EXPECTED_OUTPUT_SHA256 = (
    "9d4655e0d3347394af7f441bad7ca19b747968950c757c6345d85ba36c46e518"
)
EXPECTED_SIZE = 33_507_904
EXPECTED_INPUT_SHAPE = (1, 256, 256, 3)
EXPECTED_RANK3_OUTPUT_SHAPE = (1, 256, 256)
EXPECTED_RANK4_OUTPUT_SHAPE = (1, 256, 256, 1)


def sha256_bytes(data: bytes | bytearray) -> str:
    return hashlib.sha256(data).hexdigest()


def tensor_shape(tensor: schema.Tensor) -> tuple[int, ...]:
    return tuple(tensor.Shape(index) for index in range(tensor.ShapeLength()))


def prepare(source: Path, output: Path) -> None:
    raw = bytearray(source.read_bytes())
    source_sha = sha256_bytes(raw)
    if len(raw) != EXPECTED_SIZE or source_sha != EXPECTED_SOURCE_SHA256:
        raise ValueError(
            "Unexpected MiDaS FP16 source: "
            f"size={len(raw)} sha256={source_sha}"
        )
    if raw[4:8] != b"TFL3":
        raise ValueError("Source is not a TFLite FlatBuffer")

    model = schema.Model.GetRootAsModel(raw, 0)
    if model.SubgraphsLength() != 1:
        raise ValueError("Expected exactly one MiDaS subgraph")
    graph = model.Subgraphs(0)
    if graph.InputsLength() != 1 or graph.OutputsLength() != 1:
        raise ValueError("Expected exactly one model input and output")
    if tensor_shape(graph.Tensors(graph.Inputs(0))) != EXPECTED_INPUT_SHAPE:
        raise ValueError("Unexpected MiDaS input shape")

    output_tensor_index = graph.Outputs(0)
    if tensor_shape(graph.Tensors(output_tensor_index)) != EXPECTED_RANK3_OUTPUT_SHAPE:
        raise ValueError("Unexpected upstream MiDaS output shape")
    if graph.OperatorsLength() != 234:
        raise ValueError("Unexpected upstream MiDaS operator count")

    terminal = graph.Operators(graph.OperatorsLength() - 1)
    opcode = model.OperatorCodes(terminal.OpcodeIndex()).BuiltinCode()
    if opcode != schema.BuiltinOperator.RESHAPE:
        raise ValueError("Expected terminal MiDaS RESHAPE")
    if terminal.InputsLength() != 2 or terminal.OutputsLength() != 1:
        raise ValueError("Unexpected terminal RESHAPE contract")
    if terminal.Outputs(0) != output_tensor_index:
        raise ValueError("Terminal RESHAPE is not the graph output")

    rank4_tensor_index = terminal.Inputs(0)
    if tensor_shape(graph.Tensors(rank4_tensor_index)) != EXPECTED_RANK4_OUTPUT_SHAPE:
        raise ValueError("Terminal RESHAPE producer is not packed BHWC depth")

    # SubGraph field 8 is outputs:[int]. Patch its sole Int32 element in place.
    outputs_field = graph._tab.Offset(8)  # pylint: disable=protected-access
    if outputs_field == 0 or graph._tab.VectorLen(outputs_field) != 1:  # pylint: disable=protected-access
        raise ValueError("Unable to locate the subgraph output vector")
    output_vector = graph._tab.Vector(outputs_field)  # pylint: disable=protected-access
    if struct.unpack_from("<i", raw, output_vector)[0] != output_tensor_index:
        raise ValueError("Subgraph output vector does not match parsed metadata")
    struct.pack_into("<i", raw, output_vector, rank4_tensor_index)

    # Keep the default signature consistent with the subgraph. LiteRT CompiledModel resolves
    # public layouts through this map; a stale rank-3 tensor index makes its layout query fail.
    if model.SignatureDefsLength() != 1:
        raise ValueError("Expected exactly one MiDaS signature")
    signature = model.SignatureDefs(0)
    if signature.SubgraphIndex() != 0 or signature.OutputsLength() != 1:
        raise ValueError("Unexpected MiDaS signature contract")
    signature_output = signature.Outputs(0)
    if signature_output.TensorIndex() != output_tensor_index:
        raise ValueError("Signature output does not match the upstream graph output")
    signature_index_field = signature_output._tab.Offset(6)  # pylint: disable=protected-access
    if signature_index_field == 0:
        raise ValueError("Unable to locate the signature output tensor index")
    signature_index_offset = (  # pylint: disable=protected-access
        signature_output._tab.Pos + signature_index_field
    )
    struct.pack_into("<I", raw, signature_index_offset, rank4_tensor_index)

    output_sha = sha256_bytes(raw)
    if len(raw) != EXPECTED_SIZE or output_sha != EXPECTED_OUTPUT_SHA256:
        raise ValueError(
            "Prepared MiDaS benchmark model is not reproducible: "
            f"size={len(raw)} sha256={output_sha}"
        )

    output.parent.mkdir(parents=True, exist_ok=True)
    file_descriptor, temporary_name = tempfile.mkstemp(
        prefix=output.name + ".", suffix=".partial", dir=output.parent
    )
    published = False
    try:
        with os.fdopen(file_descriptor, "wb") as stream:
            stream.write(raw)
            stream.flush()
            os.fsync(stream.fileno())

        # Validate before atomic publication. Allocation catches broken external buffer offsets
        # without executing a costly CPU inference.
        interpreter = None
        try:
            interpreter = Interpreter(model_path=temporary_name, num_threads=1)
            input_shape = tuple(
                int(value) for value in interpreter.get_input_details()[0]["shape"]
            )
            output_shape = tuple(
                int(value) for value in interpreter.get_output_details()[0]["shape"]
            )
            if (input_shape != EXPECTED_INPUT_SHAPE
                    or output_shape != EXPECTED_RANK4_OUTPUT_SHAPE):
                raise ValueError(
                    f"Prepared tensor contract mismatch: {input_shape} -> {output_shape}"
                )
            interpreter.allocate_tensors()
        finally:
            del interpreter
            gc.collect()

        os.replace(temporary_name, output)
        published = True
    finally:
        if not published and os.path.exists(temporary_name):
            os.unlink(temporary_name)

    print(f"Prepared {output}")
    print(f"size={len(raw)} sha256={output_sha}")
    print(f"tensor={input_shape} -> {output_shape}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument(
        "--output",
        required=True,
        type=Path,
        help="generated model path under this client checkout's build/ or temp/ tree",
    )
    arguments = parser.parse_args()
    repository = Path(__file__).resolve().parents[1]
    source, output = validate_loose_model_paths(
        arguments.source, arguments.output, repository
    )
    prepare(source, output)


if __name__ == "__main__":
    main()
