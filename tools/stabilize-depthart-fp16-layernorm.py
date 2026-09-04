#!/usr/bin/env python3
"""Apply the guarded FP16-safe block-0 LayerNorm rewrite to DepthART S448.

The Galaxy XR OpenCL FP16 path flushes DepthART's first LayerNorm variance and
its 1e-5 epsilon when both are binary16 subnormals.  This tool replaces only

    d / sqrt(mean(d*d) + eps)

with the algebraically equivalent

    (4*d) / sqrt(mean((4*d)*(4*d)) + 16*eps)

for block 0.  It accepts only the two hash-pinned pre-stabilization graphs and
requires the exact checked-in production digest after deterministic FlatBuffer
serialization.  The other four LayerNorms are structurally required but are
left untouched because scaling their larger activations can overflow FP16.
"""

from __future__ import annotations

import argparse
import copy
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import tempfile

import numpy as np


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
CONVERTER_PATH = REPOSITORY_ROOT / "tools" / "convert-tflite-fp16-weights.py"
SCALE = np.float32(4.0)
ORIGINAL_EPSILON = np.float32(1.0e-5)
SCALED_EPSILON = np.float32(ORIGINAL_EPSILON * SCALE * SCALE)
FP16_MINIMUM_NORMAL = np.float32(2.0**-14)
EPSILON_TENSOR_NAME = (
    "wa/model/pretrained/network.0/network.0.2/op/out_norm/Constant_2_output_0"
)


class StabilizationError(RuntimeError):
    """Raised when a graph does not match the exact production contract."""


PRODUCTION_CONTRACTS = {
    (672, 384): {
        "source_sha256":
            "62492475402f84f55998ed5d9c7ff9a56988684631967e3ef2c89f78a97af019",
        "output_sha256":
            "3de0ded3a2329a6cc4c89da535f4c1f3035dfc30c7e85359d48580003aad780b",
        "source_operators": 2230,
        "output_operators": 2231,
        "output_bytes": 16_307_544,
    },
    (928, 384): {
        "source_sha256":
            "65790fd4b8810b0d337781f99159e680c2efed0fe2c03e75bc7c78e3cc4f098e",
        "output_sha256":
            "d166bb5dcbe16ea386640a344a80134da8e225837f4609eae64f57916ec757f2",
        "source_operators": 2370,
        "output_operators": 2371,
        "output_bytes": 19_983_564,
    },
}


SPEC = importlib.util.spec_from_file_location("fp16_converter", CONVERTER_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Unable to load {CONVERTER_PATH}")
CONVERTER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CONVERTER)
SCHEMA = CONVERTER.schema
BUILTIN_NAMES = {
    getattr(SCHEMA.BuiltinOperator, name): name
    for name in dir(SCHEMA.BuiltinOperator)
    if name.isupper()
}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def tensor_name(tensor: object) -> str:
    value = tensor.name
    return value.decode("utf-8") if isinstance(value, bytes) else str(value)


def buffer_bytes(buffer: object) -> bytes:
    return bytes(buffer.data) if buffer.data is not None else b""


def opcode_names(model: object) -> list[str]:
    return [
        BUILTIN_NAMES.get(
            max(int(code.builtinCode), int(code.deprecatedBuiltinCode)),
            str(max(int(code.builtinCode), int(code.deprecatedBuiltinCode))),
        )
        for code in model.operatorCodes
    ]


def append_scalar(model: object, subgraph: object, name: str,
                  value: np.float32) -> int:
    buffer = SCHEMA.BufferT()
    buffer.data = np.frombuffer(
        np.asarray([value], dtype="<f4").tobytes(), dtype=np.uint8
    ).copy()
    model.buffers.append(buffer)

    tensor = SCHEMA.TensorT()
    tensor.shape = np.asarray([1], dtype=np.int32)
    tensor.shapeSignature = np.asarray([1], dtype=np.int32)
    tensor.type = SCHEMA.TensorType.FLOAT32
    tensor.buffer = len(model.buffers) - 1
    tensor.name = name.encode("utf-8")
    tensor.isVariable = False
    subgraph.tensors.append(tensor)
    return len(subgraph.tensors) - 1


def append_tensor_like(subgraph: object, source: object, name: str) -> int:
    tensor = SCHEMA.TensorT()
    tensor.shape = np.asarray(source.shape, dtype=np.int32).copy()
    tensor.shapeSignature = np.asarray(source.shapeSignature, dtype=np.int32).copy()
    tensor.type = int(source.type)
    tensor.buffer = 0
    tensor.name = name.encode("utf-8")
    tensor.isVariable = False
    subgraph.tensors.append(tensor)
    return len(subgraph.tensors) - 1


def contract_for(width: int, height: int) -> dict[str, object]:
    try:
        return PRODUCTION_CONTRACTS[(width, height)]
    except KeyError as error:
        raise StabilizationError(
            f"Unsupported guarded DepthART geometry: {width}x{height}"
        ) from error


def rewrite_block0(model: object, width: int, height: int) -> dict[str, object]:
    contract = contract_for(width, height)
    if len(model.subgraphs) != 1:
        raise StabilizationError(f"Expected one subgraph, got {len(model.subgraphs)}")
    subgraph = model.subgraphs[0]
    if len(subgraph.operators) != contract["source_operators"]:
        raise StabilizationError(
            f"Unexpected source operator count: {len(subgraph.operators)}"
        )

    public_input = subgraph.tensors[int(subgraph.inputs[0])]
    public_output = subgraph.tensors[int(subgraph.outputs[0])]
    if [int(value) for value in public_input.shape] != [1, height, width, 3]:
        raise StabilizationError(f"Unexpected public input shape: {public_input.shape}")
    if [int(value) for value in public_output.shape] != [1, height, width, 1]:
        raise StabilizationError(f"Unexpected public output shape: {public_output.shape}")
    if (int(public_input.type) != SCHEMA.TensorType.FLOAT32
            or int(public_output.type) != SCHEMA.TensorType.FLOAT32):
        raise StabilizationError("DepthART public tensors must remain Float32")

    names = opcode_names(model)
    tensor_names = {
        tensor_name(tensor): index for index, tensor in enumerate(subgraph.tensors)
    }
    epsilon_index = tensor_names.get(EPSILON_TENSOR_NAME)
    if epsilon_index is None:
        raise StabilizationError("The shared DepthART LayerNorm epsilon is missing")
    epsilon_tensor = subgraph.tensors[epsilon_index]
    epsilon = np.frombuffer(
        buffer_bytes(model.buffers[int(epsilon_tensor.buffer)]), dtype="<f4"
    )
    if epsilon.shape != (1,) or epsilon[0] != ORIGINAL_EPSILON:
        raise StabilizationError(f"Unexpected original LayerNorm epsilon: {epsilon}")

    centered_indices = [
        index
        for index, tensor in enumerate(subgraph.tensors)
        if tensor_name(tensor).endswith("/out_norm/Sub_output_0")
    ]
    sequence_length = (width // 32) * (height // 32)
    expected_shapes = [
        [1, sequence_length, channels] for channels in (12, 32, 84, 84, 168)
    ]
    actual_shapes = [
        [int(value) for value in subgraph.tensors[index].shape]
        for index in centered_indices
    ]
    if actual_shapes != expected_shapes:
        raise StabilizationError(
            f"Expected the five DepthART LayerNorm tensors, got {actual_shapes}"
        )
    centered_index = centered_indices[0]
    centered = subgraph.tensors[centered_index]

    consumers: dict[int, list[int]] = {}
    for operator_index, operator in enumerate(subgraph.operators):
        for input_index in operator.inputs:
            if int(input_index) >= 0:
                consumers.setdefault(int(input_index), []).append(operator_index)

    layer_norm_consumers = sorted(set(consumers.get(centered_index, [])))
    square_matches = [
        index
        for index in layer_norm_consumers
        if names[int(subgraph.operators[index].opcodeIndex)] == "MUL"
        and [int(value) for value in subgraph.operators[index].inputs]
        == [centered_index, centered_index]
    ]
    normalized_matches = [
        index
        for index in layer_norm_consumers
        if names[int(subgraph.operators[index].opcodeIndex)] == "MUL"
        and any(
            tensor_name(subgraph.tensors[int(output)]).endswith(
                "/out_norm/Mul_1_output_0"
            )
            for output in subgraph.operators[index].outputs
            if int(output) >= 0
        )
    ]
    if len(square_matches) != 1 or len(normalized_matches) != 1:
        raise StabilizationError(
            f"Unexpected block-0 LayerNorm consumers: {layer_norm_consumers}"
        )

    square_index = square_matches[0]
    normalized_index = normalized_matches[0]
    square = subgraph.operators[square_index]
    normalized = subgraph.operators[normalized_index]
    square_output = int(square.outputs[0])
    variance_matches = [
        index
        for index in consumers.get(square_output, [])
        if names[int(subgraph.operators[index].opcodeIndex)] == "MEAN"
    ]
    if len(variance_matches) != 1:
        raise StabilizationError("Unexpected block-0 variance reduction")
    variance_index = variance_matches[0]
    variance_output = int(subgraph.operators[variance_index].outputs[0])
    epsilon_add_matches = [
        index
        for index in consumers.get(variance_output, [])
        if names[int(subgraph.operators[index].opcodeIndex)] == "ADD"
        and epsilon_index in [
            int(value) for value in subgraph.operators[index].inputs
        ]
    ]
    if len(epsilon_add_matches) != 1:
        raise StabilizationError("Unexpected block-0 epsilon addition")
    epsilon_add_index = epsilon_add_matches[0]

    all_epsilon_consumers = sorted(set(consumers.get(epsilon_index, [])))
    if len(all_epsilon_consumers) != 5 or epsilon_add_index != all_epsilon_consumers[0]:
        raise StabilizationError(
            f"Expected block 0 of five shared-epsilon consumers: {all_epsilon_consumers}"
        )

    mul_opcodes = [
        index
        for index, code in enumerate(model.operatorCodes)
        if max(int(code.builtinCode), int(code.deprecatedBuiltinCode))
        == SCHEMA.BuiltinOperator.MUL
    ]
    if len(mul_opcodes) != 1:
        raise StabilizationError(f"Unexpected MUL opcode table: {mul_opcodes}")
    scale_index = append_scalar(
        model, subgraph, "depthart_diagnostic/layer_norm_scale", SCALE
    )
    scaled_epsilon_index = append_scalar(
        model,
        subgraph,
        "depthart_diagnostic/layer_norm_scaled_epsilon",
        SCALED_EPSILON,
    )
    centered_prefix = tensor_name(centered).removesuffix("Sub_output_0")
    scaled_centered_index = append_tensor_like(
        subgraph, centered, f"{centered_prefix}ScaledCentered_output_0"
    )

    scale_operator = SCHEMA.OperatorT()
    scale_operator.opcodeIndex = mul_opcodes[0]
    scale_operator.inputs = np.asarray(
        [centered_index, scale_index], dtype=np.int32
    )
    scale_operator.outputs = np.asarray([scaled_centered_index], dtype=np.int32)
    scale_operator.builtinOptionsType = square.builtinOptionsType
    scale_operator.builtinOptions = copy.deepcopy(square.builtinOptions)
    scale_operator.customOptionsFormat = square.customOptionsFormat
    scale_operator.debugMetadataIndex = -1

    square.inputs = np.asarray(
        [scaled_centered_index, scaled_centered_index], dtype=np.int32
    )
    normalized.inputs = np.asarray(
        [
            scaled_centered_index if int(value) == centered_index else int(value)
            for value in normalized.inputs
        ],
        dtype=np.int32,
    )
    epsilon_add = subgraph.operators[epsilon_add_index]
    epsilon_add.inputs = np.asarray(
        [
            scaled_epsilon_index if int(value) == epsilon_index else int(value)
            for value in epsilon_add.inputs
        ],
        dtype=np.int32,
    )

    original_operators = list(subgraph.operators)
    subgraph.operators = (
        original_operators[:square_index]
        + [scale_operator]
        + original_operators[square_index:]
    )
    if len(subgraph.operators) != contract["output_operators"]:
        raise StabilizationError("The stabilized graph must add exactly one operator")

    return {
        "centered_tensor": tensor_name(centered),
        "centered_tensor_index": centered_index,
        "inserted_before_operator": square_index,
        "rewritten_epsilon_add_operator": epsilon_add_index,
        "untouched_layer_norms": 4,
    }


def stabilize(source: Path, output: Path, width: int, height: int) -> dict[str, object]:
    source = source.resolve()
    output = output.resolve()
    contract = contract_for(width, height)
    CONVERTER.validate_paths(source, output)
    if not source.is_file():
        raise FileNotFoundError(source)
    source_digest = sha256(source)
    if source_digest != contract["source_sha256"]:
        raise StabilizationError(
            f"Source SHA-256 is {source_digest}, expected {contract['source_sha256']}"
        )
    if SCALED_EPSILON < FP16_MINIMUM_NORMAL:
        raise StabilizationError("Scaled epsilon is still binary16-subnormal")

    model = CONVERTER.load_model(source)
    rewrite = rewrite_block0(model, width, height)
    output.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{output.name}.", suffix=".tmp", dir=output.parent
    )
    os.close(descriptor)
    temporary = Path(temporary_name)
    try:
        CONVERTER.serialize_model(model, temporary)
        output_digest = sha256(temporary)
        if output_digest != contract["output_sha256"]:
            raise StabilizationError(
                f"Output SHA-256 is {output_digest}, expected {contract['output_sha256']}"
            )
        if temporary.stat().st_size != contract["output_bytes"]:
            raise StabilizationError(
                f"Output is {temporary.stat().st_size} bytes, "
                f"expected {contract['output_bytes']}"
            )
        parity = CONVERTER.validate_cpu_parity(source, temporary)
        if parity["max_absolute_error"] > 1.0e-5:
            raise StabilizationError(
                f"Algebraic LayerNorm rewrite changed CPU output: {parity}"
            )
        os.replace(temporary, output)
    finally:
        temporary.unlink(missing_ok=True)

    return {
        "geometry": f"{width}x{height}",
        "source_sha256": source_digest,
        "output_sha256": sha256(output),
        "output_bytes": output.stat().st_size,
        "scale": float(SCALE),
        "scaled_epsilon": float(SCALED_EPSILON),
        "source_operators": contract["source_operators"],
        "output_operators": contract["output_operators"],
        "rewrite": rewrite,
        "cpu_parity": parity,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--width", type=int, required=True, choices=(672, 928))
    parser.add_argument("--height", type=int, default=384)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()

    report = stabilize(args.source, args.output, args.width, args.height)
    rendered = json.dumps(report, indent=2)
    if args.report is not None:
        report_path = args.report.resolve()
        if not report_path.is_relative_to(REPOSITORY_ROOT):
            raise StabilizationError(
                f"Report must remain below the Artemis repository: {REPOSITORY_ROOT}"
            )
        report_path.parent.mkdir(parents=True, exist_ok=True)
        report_path.write_text(rendered + "\n", encoding="utf-8")
    print(rendered)


if __name__ == "__main__":
    main()
