#!/usr/bin/env python3
"""Specialize the fixed MiDaS v2.1 Small TFLite graph for static rectangles.

The pinned Qualcomm AI Hub 256x256 graph is fully convolutional, but its five
ResizeBilinear target tensors are constants.  LiteRT therefore cannot make the
published file rectangular by resizing only its input tensor. This tool changes
the input/output and decoder-pyramid dimensions without changing operators,
asks the CPU interpreter to infer every intermediate tensor shape, and emits a
fully static graph for the Galaxy XR GPU delegate. Production outputs round the
large verified convolution weights to FP16 storage while retaining the original
Float32 NHWC input/output contract.

Both spatial dimensions must be divisible by 32 so the EfficientNet-Lite3
encoder and four decoder skip levels remain aligned.
"""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
from pathlib import Path
import struct
import tempfile

import flatbuffers
import numpy as np
import tensorflow as tf
from tensorflow.lite.python import schema_py_generated as schema
from tensorflow.lite.python.interpreter import OpResolverType

from client_sbs_model_paths import validate_loose_model_paths


FP16_CONVERTER_PATH = Path(__file__).with_name("convert-tflite-fp16-weights.py")
FP16_CONVERTER_SPEC = importlib.util.spec_from_file_location(
    "client_sbs_fp16_weight_converter", FP16_CONVERTER_PATH
)
if FP16_CONVERTER_SPEC is None or FP16_CONVERTER_SPEC.loader is None:
    raise RuntimeError(f"Unable to load {FP16_CONVERTER_PATH}")
FP16_CONVERTER = importlib.util.module_from_spec(FP16_CONVERTER_SPEC)
FP16_CONVERTER_SPEC.loader.exec_module(FP16_CONVERTER)


EXPECTED_SOURCE_SHA256 = (
    "3990551be4f21be7bffc71c159bb643279af221c6e8b328ce265374776ff2ec1"
)
SOURCE_HEIGHT = 256
SOURCE_WIDTH = 256
SOURCE_RESIZE_TARGETS = (
    (16, 16),
    (32, 32),
    (64, 64),
    (128, 128),
    (256, 256),
)
BUCKETS = (
    (352, 192),
    (384, 160),
    (448, 128),
)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def load_model(path: Path) -> schema.ModelT:
    raw = path.read_bytes()
    return schema.ModelT.InitFromObj(schema.Model.GetRootAsModel(raw, 0))


def serialize_model(model: schema.ModelT, path: Path) -> None:
    builder = flatbuffers.Builder(0)
    root = model.Pack(builder)
    builder.Finish(root, file_identifier=b"TFL3")
    path.write_bytes(builder.Output())


def tensor_name(tensor: schema.TensorT) -> str:
    name = tensor.name
    if isinstance(name, bytes):
        return name.decode("utf-8")
    return str(name)


def find_tensor(subgraph: schema.SubGraphT, name: str) -> int:
    matches = [index for index, tensor in enumerate(subgraph.tensors)
               if tensor_name(tensor) == name]
    if len(matches) != 1:
        raise RuntimeError(f"Expected one tensor named {name!r}, found {matches}")
    return matches[0]


def resize_size_tensor_indices(model: schema.ModelT) -> list[int]:
    subgraph = model.subgraphs[0]
    indices: list[int] = []
    for operator in subgraph.operators:
        opcode = model.operatorCodes[operator.opcodeIndex]
        builtin_code = max(opcode.builtinCode, opcode.deprecatedBuiltinCode)
        if builtin_code == schema.BuiltinOperator.RESIZE_BILINEAR:
            if len(operator.inputs) != 2:
                raise RuntimeError("Unexpected ResizeBilinear input contract")
            indices.append(int(operator.inputs[1]))
    if len(indices) != len(SOURCE_RESIZE_TARGETS):
        raise RuntimeError(
            f"Expected {len(SOURCE_RESIZE_TARGETS)} ResizeBilinear operations, "
            f"found {len(indices)}"
        )
    return indices


def read_int32_pair(model: schema.ModelT, tensor_index: int) -> tuple[int, int]:
    tensor = model.subgraphs[0].tensors[tensor_index]
    data = bytes(model.buffers[tensor.buffer].data)
    if len(data) != 8:
        raise RuntimeError(
            f"Resize size tensor {tensor_index} has {len(data)} bytes, expected 8"
        )
    return struct.unpack("<ii", data)


def write_int32_pair(
        model: schema.ModelT, tensor_index: int, values: tuple[int, int]) -> None:
    tensor = model.subgraphs[0].tensors[tensor_index]
    model.buffers[tensor.buffer].data = np.frombuffer(
        struct.pack("<ii", *values), dtype=np.uint8
    ).copy()


def static_shape(shape: list[int] | np.ndarray) -> list[int]:
    return [int(value) for value in shape]


def patch_io_and_decoder(model: schema.ModelT, width: int, height: int) -> None:
    if width <= 0 or height <= 0 or width % 32 or height % 32:
        raise ValueError(f"MiDaS dimensions must be positive multiples of 32: {width}x{height}")

    subgraph = model.subgraphs[0]
    input_index = find_tensor(subgraph, "image")
    output_index = find_tensor(subgraph, "depth_estimates")
    if list(subgraph.inputs) != [input_index] or list(subgraph.outputs) != [output_index]:
        raise RuntimeError("Unexpected MiDaS graph input/output indices")

    input_shape = [1, height, width, 3]
    output_shape = [1, height, width, 1]
    subgraph.tensors[input_index].shape = input_shape.copy()
    subgraph.tensors[input_index].shapeSignature = input_shape.copy()
    subgraph.tensors[output_index].shape = output_shape.copy()
    subgraph.tensors[output_index].shapeSignature = output_shape.copy()

    size_indices = resize_size_tensor_indices(model)
    source_targets = tuple(read_int32_pair(model, index) for index in size_indices)
    if source_targets != SOURCE_RESIZE_TARGETS:
        raise RuntimeError(
            f"Unexpected MiDaS decoder targets: {source_targets}; refusing to patch"
        )

    target_sizes = (
        (height // 16, width // 16),
        (height // 8, width // 8),
        (height // 4, width // 4),
        (height // 2, width // 2),
        (height, width),
    )
    for tensor_index, target in zip(size_indices, target_sizes, strict=True):
        write_int32_pair(model, tensor_index, target)


def infer_tensor_shapes(path: Path) -> dict[int, list[int]]:
    interpreter = tf.lite.Interpreter(
        model_path=str(path),
        num_threads=1,
        experimental_op_resolver_type=OpResolverType.BUILTIN_WITHOUT_DEFAULT_DELEGATES,
        experimental_preserve_all_tensors=True,
    )
    interpreter.allocate_tensors()
    shapes = {
        int(detail["index"]): static_shape(detail["shape"])
        for detail in interpreter.get_tensor_details()
    }
    del interpreter
    return shapes


def apply_inferred_shapes(model: schema.ModelT, shapes: dict[int, list[int]]) -> None:
    tensors = model.subgraphs[0].tensors
    missing = sorted(set(range(len(tensors))) - set(shapes))
    if missing:
        raise RuntimeError(
            f"Interpreter omitted shapes for {len(missing)}/{len(tensors)} tensors; "
            f"missing {missing[:10]}"
        )
    for index, shape in shapes.items():
        # The interpreter exposes temporary runtime tensors after the serialized graph's tensor
        # range. They have no FlatBuffer entry and therefore require no static metadata patch.
        if index >= len(tensors):
            continue
        tensors[index].shape = shape.copy()
        tensors[index].shapeSignature = shape.copy()


def validate_model(path: Path, width: int, height: int) -> None:
    interpreter = tf.lite.Interpreter(
        model_path=str(path),
        num_threads=1,
        experimental_op_resolver_type=OpResolverType.BUILTIN_WITHOUT_DEFAULT_DELEGATES,
    )
    interpreter.allocate_tensors()
    input_detail = interpreter.get_input_details()[0]
    output_detail = interpreter.get_output_details()[0]
    expected_input = [1, height, width, 3]
    expected_output = [1, height, width, 1]
    if static_shape(input_detail["shape"]) != expected_input:
        raise RuntimeError(f"Wrong input shape: {input_detail['shape']}")
    if static_shape(output_detail["shape"]) != expected_output:
        raise RuntimeError(f"Wrong output shape: {output_detail['shape']}")
    if input_detail["name"] != "image" or output_detail["name"] != "depth_estimates":
        raise RuntimeError(
            f"Wrong I/O names: {input_detail['name']} -> {output_detail['name']}"
        )

    x = np.linspace(0.0, 1.0, width, dtype=np.float32)[None, None, :, None]
    y = np.linspace(0.0, 1.0, height, dtype=np.float32)[None, :, None, None]
    sample = np.empty(expected_input, dtype=np.float32)
    sample[..., 0:1] = x
    sample[..., 1:2] = y
    sample[..., 2:3] = 0.5 * x + 0.5 * y
    interpreter.set_tensor(int(input_detail["index"]), sample)
    interpreter.invoke()
    output = interpreter.get_tensor(int(output_detail["index"]))
    if not np.isfinite(output).all():
        raise RuntimeError("MiDaS validation output contains non-finite values")
    output_range = float(np.max(output) - np.min(output))
    if output_range <= 1e-5:
        raise RuntimeError(f"MiDaS validation output is flat: range={output_range}")
    print(
        f"validated {width}x{height}: output range={output_range:.6f} "
        f"mean={float(np.mean(output)):.6f}",
        flush=True,
    )


def specialize(source: Path, output: Path, width: int, height: int) -> None:
    model = load_model(source)
    patch_io_and_decoder(model, width, height)
    with tempfile.TemporaryDirectory(prefix="midas-static-") as temporary_directory:
        provisional = Path(temporary_directory) / "provisional.tflite"
        serialize_model(model, provisional)
        shapes = infer_tensor_shapes(provisional)
    apply_inferred_shapes(model, shapes)

    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="midas-static-float32-") as temporary_directory:
        float32_model = Path(temporary_directory) / "static-float32.tflite.model"
        serialize_model(model, float32_model)
        validate_model(float32_model, width, height)
        FP16_CONVERTER.convert_file(
            float32_model,
            output,
            expected_source_sha256=None,
            minimum_buffer_bytes=FP16_CONVERTER.DEFAULT_MINIMUM_BUFFER_BYTES,
        )


def parse_args() -> argparse.Namespace:
    repository = Path(__file__).resolve().parents[1]
    default_staging_directory = repository / "build" / "client-sbs-model-staging"
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--source",
        type=Path,
        required=True,
        help="downloaded Qualcomm MiDaS Float32 source; keep it outside the repository",
    )
    parser.add_argument(
        "--output-directory",
        type=Path,
        default=default_staging_directory,
        help=(
            "temporary loose-model staging directory; defaults under the ignored client build "
            "tree and must not be an Android source-assets directory"
        ),
    )
    parser.add_argument(
        "--allow-unverified-source",
        action="store_true",
        help="permit a source whose SHA-256 differs from the pinned Qualcomm graph",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    repository = Path(__file__).resolve().parents[1]
    source, output_directory = validate_loose_model_paths(
        args.source, args.output_directory, repository
    )
    source_digest = sha256(source)
    if not args.allow_unverified_source and source_digest != EXPECTED_SOURCE_SHA256:
        raise RuntimeError(
            f"Source SHA-256 is {source_digest}, expected {EXPECTED_SOURCE_SHA256}"
        )
    print(f"source {source} sha256={source_digest}", flush=True)
    for width, height in BUCKETS:
        output = output_directory / (
            f"midas-v2-small-static-{width}x{height}-fp16weights.tflite.model"
        )
        # Validate the concrete file as well as its parent directory so a source that already has
        # a production bucket filename cannot be replaced in place.
        _, output = validate_loose_model_paths(source, output, repository)
        specialize(source, output, width, height)


if __name__ == "__main__":
    main()
