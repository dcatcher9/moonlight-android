#!/usr/bin/env python3
"""Store large TFLite constant weights as FP16 while preserving Float32 I/O.

This is a guarded post-processing step for the static Client SBS depth graphs.
It implements the same representation used by TensorFlow Lite's Float16 weight
quantization: each eligible Float32 constant is stored as Float16 and a
DEQUANTIZE operator restores the graph's original Float32 tensor contract.
Consequently, callers and shared GL buffers remain Float32 while LiteRT's GPU
delegate can consume the smaller weights directly.

Only live, finite, rank-2-or-higher constants of at least 1 KiB are converted.
That includes convolution and matrix weights while deliberately retaining
small biases, normalization constants, and shape/control constants in their
original precision. The tool validates structural invariants and CPU parity
before atomically publishing an output below the Artemis repository.
"""

from __future__ import annotations

import argparse
import copy
import hashlib
import math
import os
from pathlib import Path
import tempfile
from typing import Iterable

import flatbuffers
import numpy as np

try:
    import tensorflow as tf
    from tensorflow.lite.python import schema_py_generated as schema
    from tensorflow.lite.python.interpreter import OpResolverType

    Interpreter = tf.lite.Interpreter
except ModuleNotFoundError:
    # LiteRT ships the same generated TFLite schema and interpreter without the
    # much larger TensorFlow package. This keeps the standalone weight packer
    # usable in the model-conversion environment used by the XR client.
    from ai_edge_litert import schema_py_generated as schema
    from ai_edge_litert.interpreter import Interpreter, OpResolverType


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_MINIMUM_BUFFER_BYTES = 1024
MINIMUM_CORRELATION = 0.995
MAXIMUM_NORMALIZED_RMSE = 0.03
MAXIMUM_AFFINE_NORMALIZED_RMSE = 0.03


class WeightConversionError(RuntimeError):
    """Raised when a model violates the guarded conversion contract."""


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def load_model(path: Path) -> schema.ModelT:
    raw = path.read_bytes()
    if raw[4:8] != b"TFL3":
        raise WeightConversionError(f"Not a TFLite FlatBuffer: {path}")
    return schema.ModelT.InitFromObj(schema.Model.GetRootAsModel(raw, 0))


def serialize_model(model: schema.ModelT, path: Path) -> None:
    builder = flatbuffers.Builder(0)
    root = model.Pack(builder)
    builder.Finish(root, file_identifier=b"TFL3")
    path.write_bytes(builder.Output())


def _path_has_apollo_component(path: Path) -> bool:
    return any(part.casefold() == "apollo-3d" for part in path.parts)


def validate_paths(source: Path, output: Path) -> None:
    source = source.resolve()
    output = output.resolve()
    if source == output:
        raise WeightConversionError("Source and output must be different files")
    if _path_has_apollo_component(source) or _path_has_apollo_component(output):
        raise WeightConversionError("Client model files must never use the Apollo-3D tree")
    if not output.is_relative_to(REPOSITORY_ROOT):
        raise WeightConversionError(
            f"Output must remain below the Artemis repository: {REPOSITORY_ROOT}"
        )


def _tensor_name(tensor: schema.TensorT) -> str:
    name = tensor.name
    return name.decode("utf-8") if isinstance(name, bytes) else str(name)


def _shape(tensor: schema.TensorT) -> tuple[int, ...]:
    return tuple(int(value) for value in tensor.shape)


def _tensor_buffer(model: schema.ModelT, tensor: schema.TensorT) -> bytes:
    index = int(tensor.buffer)
    if index < 0 or index >= len(model.buffers):
        raise WeightConversionError(f"Tensor {_tensor_name(tensor)!r} has invalid buffer {index}")
    data = model.buffers[index].data
    return bytes(data) if data is not None else b""


def _public_contract(model: schema.ModelT) -> tuple[tuple[object, ...], ...]:
    contract: list[tuple[object, ...]] = []
    for subgraph_index, subgraph in enumerate(model.subgraphs):
        for role, indices in (("input", subgraph.inputs), ("output", subgraph.outputs)):
            for ordinal, tensor_index_value in enumerate(indices):
                tensor_index = int(tensor_index_value)
                tensor = subgraph.tensors[tensor_index]
                contract.append(
                    (
                        subgraph_index,
                        role,
                        ordinal,
                        tensor_index,
                        _tensor_name(tensor),
                        int(tensor.type),
                        _shape(tensor),
                    )
                )
    return tuple(contract)


def _dequantize_opcode(model: schema.ModelT) -> int:
    for index, opcode in enumerate(model.operatorCodes):
        builtin_code = max(int(opcode.builtinCode), int(opcode.deprecatedBuiltinCode))
        if builtin_code == schema.BuiltinOperator.DEQUANTIZE:
            return index

    opcode = schema.OperatorCodeT()
    opcode.builtinCode = schema.BuiltinOperator.DEQUANTIZE
    opcode.deprecatedBuiltinCode = schema.BuiltinOperator.DEQUANTIZE
    opcode.version = 1
    model.operatorCodes.append(opcode)
    return len(model.operatorCodes) - 1


def _eligible_tensor_indices(
    model: schema.ModelT,
    subgraph: schema.SubGraphT,
    minimum_buffer_bytes: int,
) -> list[int]:
    produced = {
        int(tensor_index)
        for operator in subgraph.operators
        for tensor_index in operator.outputs
        if int(tensor_index) >= 0
    }
    consumers = {
        int(tensor_index)
        for operator in subgraph.operators
        for tensor_index in operator.inputs
        if int(tensor_index) >= 0
    }
    public = {int(value) for value in subgraph.inputs} | {
        int(value) for value in subgraph.outputs
    }

    eligible: list[int] = []
    for tensor_index, tensor in enumerate(subgraph.tensors):
        data = _tensor_buffer(model, tensor)
        dimensions = _shape(tensor)
        if (
            int(tensor.type) != schema.TensorType.FLOAT32
            or not data
            or tensor_index in produced
            or tensor_index not in consumers
            or tensor_index in public
            or len(dimensions) < 2
            or len(data) < minimum_buffer_bytes
        ):
            continue
        if tensor.sparsity is not None:
            raise WeightConversionError(
                f"Sparse weight {_tensor_name(tensor)!r} is not supported"
            )
        element_count = math.prod(dimensions)
        if element_count * np.dtype("<f4").itemsize != len(data):
            raise WeightConversionError(
                f"Weight {_tensor_name(tensor)!r} shape {dimensions} does not match "
                f"its {len(data)}-byte Float32 buffer"
            )
        eligible.append(tensor_index)
    return eligible


def convert_model(
    model: schema.ModelT,
    minimum_buffer_bytes: int = DEFAULT_MINIMUM_BUFFER_BYTES,
) -> dict[str, int]:
    if minimum_buffer_bytes < 2:
        raise ValueError("minimum_buffer_bytes must be at least two")
    if not model.buffers or bytes(model.buffers[0].data or b""):
        raise WeightConversionError("TFLite buffer zero must be empty")

    before_contract = _public_contract(model)
    dequantize_opcode = _dequantize_opcode(model)
    converted_tensors = 0
    float32_bytes = 0
    float16_bytes = 0
    converted_buffers: dict[int, bytes] = {}

    for subgraph in model.subgraphs:
        eligible = _eligible_tensor_indices(model, subgraph, minimum_buffer_bytes)
        dequantize_operators: list[schema.OperatorT] = []
        buffer_owners: dict[int, list[int]] = {}
        for tensor_index, tensor in enumerate(subgraph.tensors):
            if _tensor_buffer(model, tensor):
                buffer_owners.setdefault(int(tensor.buffer), []).append(tensor_index)

        for tensor_index in eligible:
            tensor = subgraph.tensors[tensor_index]
            buffer_index = int(tensor.buffer)
            owners = buffer_owners[buffer_index]
            if owners != [tensor_index]:
                raise WeightConversionError(
                    f"Weight {_tensor_name(tensor)!r} shares buffer {buffer_index} "
                    f"with tensor indices {owners}; refusing an ambiguous conversion"
                )

            source_data = _tensor_buffer(model, tensor)
            source_values = np.frombuffer(source_data, dtype="<f4")
            if not np.isfinite(source_values).all():
                raise WeightConversionError(
                    f"Weight {_tensor_name(tensor)!r} contains non-finite Float32 values"
                )
            half_values = source_values.astype("<f2")
            if not np.isfinite(half_values).all():
                raise WeightConversionError(
                    f"Weight {_tensor_name(tensor)!r} overflows Float16 storage"
                )
            half_data = half_values.tobytes()
            converted_buffers[buffer_index] = half_data

            half_tensor = copy.deepcopy(tensor)
            half_tensor.name = f"{_tensor_name(tensor)}/fp16_storage"
            half_tensor.type = schema.TensorType.FLOAT16
            half_tensor.buffer = buffer_index
            half_tensor.quantization = None
            half_tensor.isVariable = False
            half_tensor_index = len(subgraph.tensors)
            subgraph.tensors.append(half_tensor)

            tensor.buffer = 0
            tensor.type = schema.TensorType.FLOAT32
            tensor.isVariable = False

            operator = schema.OperatorT()
            operator.opcodeIndex = dequantize_opcode
            operator.inputs = [half_tensor_index]
            operator.outputs = [tensor_index]
            operator.builtinOptionsType = schema.BuiltinOptions.NONE
            dequantize_operators.append(operator)

            converted_tensors += 1
            float32_bytes += len(source_data)
            float16_bytes += len(half_data)

        # Constants must be materialized before the first consumer executes.
        subgraph.operators = dequantize_operators + list(subgraph.operators)

    for buffer_index, half_data in converted_buffers.items():
        model.buffers[buffer_index].data = np.frombuffer(half_data, dtype=np.uint8).copy()

    if converted_tensors == 0:
        raise WeightConversionError("Model has no eligible Float32 weight tensors")
    if _public_contract(model) != before_contract:
        raise WeightConversionError("FP16 weight conversion changed the public tensor contract")
    if any(entry[5] != schema.TensorType.FLOAT32 for entry in before_contract):
        raise WeightConversionError("Source public input/output tensors must all be Float32")

    return {
        "converted_tensors": converted_tensors,
        "float32_weight_bytes": float32_bytes,
        "float16_weight_bytes": float16_bytes,
        "weight_bytes_saved": float32_bytes - float16_bytes,
    }


def _make_validation_input(shape: Iterable[int]) -> np.ndarray:
    dimensions = tuple(int(value) for value in shape)
    if len(dimensions) != 4 or dimensions[0] != 1 or dimensions[3] != 3:
        raise WeightConversionError(
            f"Validation expects one NHWC RGB input, got shape {dimensions}"
        )
    _, height, width, _ = dimensions
    x = np.linspace(0.0, 1.0, width, dtype=np.float32)[None, None, :, None]
    y = np.linspace(0.0, 1.0, height, dtype=np.float32)[None, :, None, None]
    sample = np.empty(dimensions, dtype=np.float32)
    sample[..., 0:1] = x
    sample[..., 1:2] = y
    sample[..., 2:3] = 0.5 * x + 0.5 * y
    return sample


def _run_cpu(path: Path, sample: np.ndarray | None = None) -> tuple[np.ndarray, np.ndarray]:
    interpreter = Interpreter(
        model_path=str(path),
        num_threads=1,
        experimental_op_resolver_type=OpResolverType.BUILTIN_WITHOUT_DEFAULT_DELEGATES,
    )
    interpreter.allocate_tensors()
    inputs = interpreter.get_input_details()
    outputs = interpreter.get_output_details()
    if len(inputs) != 1 or len(outputs) != 1:
        raise WeightConversionError(
            f"Expected one input and output, got {len(inputs)} and {len(outputs)}"
        )
    input_detail = inputs[0]
    output_detail = outputs[0]
    if input_detail["dtype"] != np.float32 or output_detail["dtype"] != np.float32:
        raise WeightConversionError(
            f"Public I/O must remain Float32, got {input_detail['dtype']} -> "
            f"{output_detail['dtype']}"
        )
    if sample is None:
        sample = _make_validation_input(input_detail["shape"])
    if tuple(sample.shape) != tuple(int(value) for value in input_detail["shape"]):
        raise WeightConversionError(
            f"Validation input shape {sample.shape} does not match {input_detail['shape']}"
        )
    interpreter.set_tensor(int(input_detail["index"]), sample)
    interpreter.invoke()
    output = interpreter.get_tensor(int(output_detail["index"])).astype(np.float32)
    if not np.isfinite(output).all():
        raise WeightConversionError(f"CPU output from {path.name} contains non-finite values")
    if float(np.ptp(output)) <= 1.0e-5:
        raise WeightConversionError(f"CPU output from {path.name} is flat")
    return sample, output


def validate_cpu_parity(
    source: Path,
    candidate: Path,
    allow_affine_output: bool = False,
) -> dict[str, float]:
    sample, reference = _run_cpu(source)
    _, converted = _run_cpu(candidate, sample)
    if converted.shape != reference.shape:
        raise WeightConversionError(
            f"Output shape changed from {reference.shape} to {converted.shape}"
        )

    reference64 = reference.astype(np.float64).ravel()
    converted64 = converted.astype(np.float64).ravel()
    difference = converted64 - reference64
    rmse = float(np.sqrt(np.mean(np.square(difference))))
    reference_range = float(np.ptp(reference64))
    normalized_rmse = rmse / max(reference_range, 1.0e-12)
    correlation = float(np.corrcoef(reference64, converted64)[0, 1])
    max_absolute_error = float(np.max(np.abs(difference)))

    # Relative-depth models are consumed only after per-frame affine
    # normalization. FP16 weights can shift their arbitrary output scale and
    # offset while preserving the actual depth ordering. Keep the stricter raw
    # check as the default, but permit an explicit affine-invariant check for
    # those graphs.
    converted_centered = converted64 - float(np.mean(converted64))
    reference_centered = reference64 - float(np.mean(reference64))
    converted_energy = float(np.dot(converted_centered, converted_centered))
    if converted_energy <= 1.0e-24:
        raise WeightConversionError("Converted CPU output has no usable variance")
    affine_scale = float(
        np.dot(converted_centered, reference_centered) / converted_energy
    )
    affine_offset = float(np.mean(reference64) - affine_scale * np.mean(converted64))
    affine_difference = affine_scale * converted64 + affine_offset - reference64
    affine_rmse = float(np.sqrt(np.mean(np.square(affine_difference))))
    affine_normalized_rmse = affine_rmse / max(reference_range, 1.0e-12)

    metrics = [
        normalized_rmse,
        correlation,
        max_absolute_error,
        affine_scale,
        affine_offset,
        affine_normalized_rmse,
    ]
    if not np.isfinite(metrics).all():
        raise WeightConversionError("CPU parity metrics contain non-finite values")
    parity_error = (
        affine_normalized_rmse > MAXIMUM_AFFINE_NORMALIZED_RMSE
        if allow_affine_output
        else normalized_rmse > MAXIMUM_NORMALIZED_RMSE
    )
    if correlation < MINIMUM_CORRELATION or parity_error:
        raise WeightConversionError(
            "FP16 weight CPU parity failed: "
            f"correlation={correlation:.9f}, normalized_rmse={normalized_rmse:.9f}, "
            f"affine_normalized_rmse={affine_normalized_rmse:.9f}, "
            f"max_abs={max_absolute_error:.9f}"
        )
    return {
        "correlation": correlation,
        "normalized_rmse": normalized_rmse,
        "affine_normalized_rmse": affine_normalized_rmse,
        "affine_scale": affine_scale,
        "affine_offset": affine_offset,
        "max_absolute_error": max_absolute_error,
    }


def convert_file(
    source: Path,
    output: Path,
    expected_source_sha256: str | None,
    minimum_buffer_bytes: int,
    allow_affine_output: bool = False,
) -> tuple[dict[str, int], dict[str, float]]:
    source = source.resolve()
    output = output.resolve()
    validate_paths(source, output)
    source_digest = sha256(source)
    if expected_source_sha256 and source_digest != expected_source_sha256.casefold():
        raise WeightConversionError(
            f"Source SHA-256 is {source_digest}, expected {expected_source_sha256}"
        )

    model = load_model(source)
    conversion = convert_model(model, minimum_buffer_bytes)
    output.parent.mkdir(parents=True, exist_ok=True)
    file_descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{output.name}.", suffix=".tmp", dir=output.parent
    )
    os.close(file_descriptor)
    temporary_output = Path(temporary_name)
    try:
        serialize_model(model, temporary_output)
        # Reparse before execution so serialization errors cannot be hidden by
        # the in-memory object graph.
        reparsed = load_model(temporary_output)
        if _public_contract(reparsed) != _public_contract(load_model(source)):
            raise WeightConversionError("Serialized candidate changed the public contract")
        parity = validate_cpu_parity(
            source,
            temporary_output,
            allow_affine_output=allow_affine_output,
        )
        os.replace(temporary_output, output)
    finally:
        temporary_output.unlink(missing_ok=True)

    print(
        f"converted {source.name} -> {output.name}: "
        f"tensors={conversion['converted_tensors']} "
        f"saved={conversion['weight_bytes_saved']} bytes "
        f"correlation={parity['correlation']:.9f} "
        f"nrmse={parity['normalized_rmse']:.9f} "
        f"affine_nrmse={parity['affine_normalized_rmse']:.9f} "
        f"max_abs={parity['max_absolute_error']:.9f} "
        f"sha256={sha256(output)}",
        flush=True,
    )
    return conversion, parity


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument(
        "--expected-source-sha256",
        help="refuse conversion unless the source has this SHA-256",
    )
    parser.add_argument(
        "--minimum-buffer-bytes",
        type=int,
        default=DEFAULT_MINIMUM_BUFFER_BYTES,
    )
    parser.add_argument(
        "--allow-affine-output-parity",
        action="store_true",
        help=(
            "validate after optimal scale/offset alignment; only use for "
            "relative-depth outputs that are normalized per frame"
        ),
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    convert_file(
        args.source,
        args.output,
        args.expected_source_sha256,
        args.minimum_buffer_bytes,
        allow_affine_output=args.allow_affine_output_parity,
    )


if __name__ == "__main__":
    main()
