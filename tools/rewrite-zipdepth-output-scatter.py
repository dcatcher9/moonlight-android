#!/usr/bin/env python3
"""Generate an experimental exact DEPTH_TO_SPACE ZipDepth output-scatter graph.

Only the three pinned production graphs are accepted. Outputs stay in ignored
build/temp storage; this tool never changes packaged models or manifests. See
zipdepth-output-scatter.md for the device qualification gates.
"""
from __future__ import annotations

import argparse
import importlib.util
import json
import os
from pathlib import Path
import sys
import tempfile

import numpy as np

SPEC = importlib.util.spec_from_file_location(
    "zipdepth_gpu_compat", Path(__file__).with_name("rewrite-zipdepth-gpu-compat.py"))
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("Unable to load the shared ZipDepth graph utilities")
COMPAT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(COMPAT)
schema = COMPAT.schema
RewriteError = COMPAT.ZipDepthRewriteError

PRODUCTION_INPUTS = {
    "6296d5c2e4f857fd551d854ebf4dd2ab2462c0d7372d526bf0a7463718b8b6d1": 672,
    "31467ab0cd187b74c65b3b20f4850973309d120519b587610e3dd3e27b72df4a": 896,
    "169d5e8802bea9aac839df6acb4a8dd8e92a53728ea6f4e39a4baca453fd34cc": 928,
}


def require(condition: bool, reason: str) -> None:
    if not condition:
        raise RewriteError(reason)


def rewrite_scatter(model) -> int:
    """Replace only a terminal identity scatter, preserving all learned weights.

    Shape-independent structural checking is separately testable on a tiny real
    TFLite graph. The CLI additionally enforces production hashes/shapes/counts.
    """
    require(len(model.subgraphs) == 1, "Expected one subgraph")
    graph = model.subgraphs[0]
    matches = [(index, op) for index, op in enumerate(graph.operators)
               if COMPAT.builtin_code(model, op) == schema.BuiltinOperator.TRANSPOSE_CONV]
    require(len(matches) == 1, "Expected exactly one TRANSPOSE_CONV")
    index, original = matches[0]
    require(len(original.inputs) == 3 and len(original.outputs) == 1,
            "Scatter must have three inputs (no bias) and one output")
    require(original.builtinOptionsType == schema.BuiltinOptions.TransposeConvOptions,
            "Unexpected scatter options")
    options = original.builtinOptions
    require((int(options.strideH), int(options.strideW), int(options.padding),
             int(options.fusedActivationFunction)) ==
            (2, 2, schema.Padding.VALID, schema.ActivationFunctionType.NONE),
            "Scatter must use stride2 VALID without fused activation")
    input_id, output_id = int(original.inputs[2]), int(original.outputs[0])
    source, target = graph.tensors[input_id], graph.tensors[output_id]
    input_shape = tuple(int(x) for x in source.shape)
    output_shape = tuple(int(x) for x in target.shape)
    require(len(input_shape) == 4 and input_shape[0] == 1 and input_shape[3] == 4
            and min(input_shape) > 0, "Scatter input must be static NHWC with four channels")
    require(output_shape == (1, input_shape[1] * 2, input_shape[2] * 2, 1),
            "Scatter output must be the exact 2x single-channel raster")
    require(source.type == target.type == schema.TensorType.FLOAT32,
            "Scatter activations must be Float32")
    shape_constant = COMPAT.constant_int32(model, graph.tensors[int(original.inputs[0])])
    require(shape_constant.shape == (4,) and np.array_equal(shape_constant, output_shape),
            "Scatter output-shape constant disagrees with its tensor")
    kernel = COMPAT.constant_float32(model, graph.tensors[int(original.inputs[1])])
    require(kernel.shape == (1, 2, 2, 4) and np.array_equal(
        kernel, np.eye(4, dtype=np.float32).reshape(1, 2, 2, 4)),
        "Scatter filter must be the exact row-major identity kernel")
    require(COMPAT.tensor_consumers(graph).get(output_id) == [index + 1]
            and index + 1 == len(graph.operators) - 1,
            "Scatter must feed only the terminal ReLU")
    relu = graph.operators[index + 1]
    require(COMPAT.builtin_code(model, relu) == schema.BuiltinOperator.RELU
            and list(relu.inputs) == [output_id]
            and list(graph.outputs) == list(relu.outputs),
            "Scatter must feed the public output through ReLU")
    public_before = COMPAT.public_contract(model)

    opcode = schema.OperatorCodeT()
    opcode.builtinCode = opcode.deprecatedBuiltinCode = schema.BuiltinOperator.DEPTH_TO_SPACE
    opcode.version = 1
    model.operatorCodes.append(opcode)
    replacement = schema.OperatorT()
    replacement.opcodeIndex = len(model.operatorCodes) - 1
    replacement.inputs = np.asarray([input_id], dtype=np.int32)
    replacement.outputs = np.asarray(original.outputs, dtype=np.int32).copy()
    replacement.builtinOptionsType = schema.BuiltinOptions.DepthToSpaceOptions
    replacement.builtinOptions = schema.DepthToSpaceOptionsT()
    replacement.builtinOptions.blockSize = 2
    graph.operators[index] = replacement
    require(COMPAT.public_contract(model) == public_before, "Public tensor contract changed")
    return index


def generate(source: bytes, expected_hash: str) -> tuple[bytes, int]:
    digest = COMPAT.sha256_bytes(source)
    require(digest == expected_hash.lower(), "Source SHA-256 mismatch")
    require(digest in PRODUCTION_INPUTS, "Source is not a pinned production ZipDepth graph")
    require(source[4:8] == b"TFL3", "Source is not a TFLite FlatBuffer")
    model = schema.ModelT.InitFromObj(schema.Model.GetRootAsModel(source, 0))
    require(len(model.subgraphs) == 1, "Expected one subgraph")
    graph = model.subgraphs[0]
    width = PRODUCTION_INPUTS[digest]
    require(len(graph.operators) == 163, "Expected 163 production operators")
    require(len(graph.inputs) == len(graph.outputs) == 1, "Expected one public input/output")
    input_tensor, output_tensor = (graph.tensors[int(graph.inputs[0])],
                                   graph.tensors[int(graph.outputs[0])])
    require(list(input_tensor.shape) == [1, 384, width, 3]
            and list(output_tensor.shape) == [1, 384, width, 1]
            and input_tensor.type == output_tensor.type == schema.TensorType.FLOAT32,
            "Unexpected production Float32 public shapes")
    public_before = COMPAT.public_contract(model)
    require(rewrite_scatter(model) == 161, "Unexpected production scatter position")
    data = COMPAT.serialize_model(model)
    reparsed = schema.ModelT.InitFromObj(schema.Model.GetRootAsModel(data, 0))
    require(COMPAT.public_contract(reparsed) == public_before
            and len(reparsed.subgraphs[0].operators) == 163,
            "Serialized candidate changed its public contract or operator count")
    return data, width


def validate_cpu_outputs(source: bytes, candidate: bytes, inputs: list[np.ndarray]) -> None:
    outputs = []
    for data in (source, candidate):
        interpreter = COMPAT.Interpreter(model_content=data, num_threads=1,
            experimental_op_resolver_type=COMPAT.OpResolverType.BUILTIN_WITHOUT_DEFAULT_DELEGATES)
        interpreter.allocate_tensors()
        input_index = int(interpreter.get_input_details()[0]["index"])
        output_index = int(interpreter.get_output_details()[0]["index"])
        values = []
        for tensor in inputs:
            interpreter.set_tensor(input_index, tensor)
            interpreter.invoke()
            values.append(interpreter.get_tensor(output_index))
        outputs.append(values)
    for index, (reference, actual) in enumerate(zip(*outputs)):
        require(reference.dtype == actual.dtype == np.float32
                and reference.shape == actual.shape and np.isfinite(reference).all()
                and np.isfinite(actual).all()
                and np.array_equal(reference.view(np.uint32), actual.view(np.uint32)),
                f"Rewrite changed complete Float32 CPU output on input {index}")


def validation_inputs(width: int, input_rgb: Path | None) -> list[np.ndarray]:
    shape = (1, 384, width, 3)
    rng = np.random.default_rng(5938 + width)
    inputs = [rng.random(shape, dtype=np.float32)]
    edges = np.zeros(shape, dtype=np.float32)
    edges[:, :, :width // 2, 0] = 1
    edges[:, ::2, :, 1] = 1
    edges[:, :, ::3, 2] = 1
    inputs.append(edges)
    if input_rgb is not None:
        require(input_rgb.stat().st_size == int(np.prod(shape)) * 4,
                "--input-rgb must be one exact-size little-endian Float32 NHWC tensor")
        tensor = np.fromfile(input_rgb, dtype="<f4").reshape(shape)
        require(np.isfinite(tensor).all() and tensor.min() >= 0 and tensor.max() <= 1,
                "--input-rgb must contain finite RGB values in [0,1]")
        inputs.append(tensor)
    return inputs


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--expected-source-sha256", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--input-rgb", type=Path, help="optional real-frame Float32 NHWC CPU probe")
    parser.add_argument("--force", action="store_true")
    args = parser.parse_args()
    source, output = COMPAT.validate_paths(args.source, args.output)
    require(not output.exists() or args.force, "Output exists; use --force to replace after validation")
    source_bytes = source.read_bytes()
    candidate, width = generate(source_bytes, args.expected_source_sha256)
    inputs = validation_inputs(width, args.input_rgb)
    validate_cpu_outputs(source_bytes, candidate, inputs)
    output.parent.mkdir(parents=True, exist_ok=True)
    descriptor, name = tempfile.mkstemp(prefix=f".{output.name}.", suffix=".tmp", dir=output.parent)
    temporary = Path(name)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(candidate)
        if args.force:
            os.replace(temporary, output)
        else:
            # Atomic publication without clobbering a concurrently published candidate.
            os.link(temporary, output)
    finally:
        temporary.unlink(missing_ok=True)
    print(json.dumps({"source_sha256": COMPAT.sha256_bytes(source_bytes),
        "candidate_sha256": COMPAT.sha256_bytes(candidate), "output": str(output),
        "shape": [1, 384, width, 1], "operators": 163, "changed_operator": 161,
        "cpu_bit_exact_inputs": len(inputs), "python": sys.executable,
        "numpy": np.__version__, "device_qualified": False}), flush=True)


if __name__ == "__main__":
    main()
