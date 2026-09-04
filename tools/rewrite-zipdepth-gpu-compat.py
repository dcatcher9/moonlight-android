#!/usr/bin/env python3
"""Apply guarded, algebraically exact ZipDepth LiteRT GPU compatibility rewrites.

Two passes are intentionally separate because FP16 weight packing happens between
them:

1. ``densify-group-conv`` converts the one 24->32 four-way grouped 1x1
   convolution into an ordinary convolution with block-diagonal weights. This
   avoids a broken Adreno LiteRT group-convolution builder path.
2. ``stabilize-gcb`` rewrites the global-context weighted sum from
   ``sum(x * p)`` to ``sum(x * (p * 1024)) * (1 / 1024)``. The power-of-two
   factors are exact, while the larger intermediate prevents FP16 subnormal
   products from being flushed to zero.

Run the repository's ``convert-tflite-fp16-weights.py`` between the two passes.
Each pass verifies public tensor contracts and bit-exact CPU output before it
atomically publishes the result.
"""

from __future__ import annotations

import argparse
import copy
import hashlib
import os
from pathlib import Path
import sys
import tempfile
from typing import Iterable

import flatbuffers
import numpy as np

try:
    from ai_edge_litert import schema_py_generated as schema
    from ai_edge_litert.interpreter import Interpreter, OpResolverType
except ModuleNotFoundError:
    import tensorflow as tf
    from tensorflow.lite.python import schema_py_generated as schema
    from tensorflow.lite.python.interpreter import OpResolverType

    Interpreter = tf.lite.Interpreter


TOOLS_DIRECTORY = Path(__file__).resolve().parent
if str(TOOLS_DIRECTORY) not in sys.path:
    sys.path.insert(0, str(TOOLS_DIRECTORY))
from client_sbs_model_paths import (  # noqa: E402
    LooseModelPathError,
    validate_loose_model_paths,
)


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
GCB_SCALE = 1024.0
KNOWN_REWRITES: dict[str, dict[str, dict[str, object]]] = {
    "densify-group-conv": {
        "b18ef070262066f7190db3f69f6877ea2aa634caa280c64fe05104fe192770e9": {
            "geometry": "672x384",
            "output_sha256": "be6fa60800c114d628f98eec8197d605cdbe666870857ac2f5c992ce01933c1c",
        },
        "90c0ff5a3f37cb798e15213b70de7a3c0c48bb0fa913bd67e717e2d4a8e986bd": {
            "geometry": "896x384",
            "output_sha256": "0721485db84d6e83ca4d78d52ad9bb02af2d31f818ced767995abe230ace7632",
        },
        "d89c109ddb37ceacd7c169e9037f302421a3e75e2d879aca933ac8d6eaad2b43": {
            "geometry": "928x384",
            "output_sha256": "d711fff9bf6405779e25a491d9d01beaff3ec098c52891663059d2d6dfb75eb2",
        },
    },
    "stabilize-gcb": {
        "2734e23eda172624a86c3d102082bdfbc2443964ae1f4e3bbd99563e5aeda941": {
            "geometry": "672x384",
            "output_sha256": "292d009807c3350ad3ebcce262dec8291fb574b73f41319fe24dff6170d5b279",
        },
        "d9335ca81a7ce59b0f1002e023ecf459d3c36a219518221c0570ce46c68d3000": {
            "geometry": "896x384",
            "output_sha256": "e7519e1b17622d8e857e2415ab55e1a9cca5aa794b9c75d6e5b1f3847fe3e62d",
        },
        "e4a5c2b1b35a6851b5eec89d2a0ee0fc87525aa1899d9b06aac80bd5ddf8596f": {
            "geometry": "928x384",
            "output_sha256": "e5e75073e2fd57b362c1acdd256f623c157bb77d5f909470bcd7d2d6f2033f1b",
        },
    },
}


class ZipDepthRewriteError(RuntimeError):
    """Raised when a graph does not satisfy the guarded ZipDepth contract."""


def _text(value: object) -> str:
    if isinstance(value, bytes):
        return value.decode("utf-8", errors="replace")
    return str(value)


def _ints(values: Iterable[object] | None) -> list[int]:
    return [] if values is None else [int(value) for value in values]


def _shape(tensor: schema.TensorT) -> tuple[int, ...]:
    return tuple(_ints(tensor.shape))


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def validate_paths(source: Path, output: Path) -> tuple[Path, Path]:
    try:
        return validate_loose_model_paths(source, output, REPOSITORY_ROOT)
    except LooseModelPathError as exc:
        raise ZipDepthRewriteError(str(exc)) from exc


def load_model(path: Path) -> schema.ModelT:
    raw = path.read_bytes()
    if raw[4:8] != b"TFL3":
        raise ZipDepthRewriteError(f"Not a TFLite FlatBuffer: {path}")
    model = schema.ModelT.InitFromObj(schema.Model.GetRootAsModel(raw, 0))
    if len(model.subgraphs) != 1:
        raise ZipDepthRewriteError("Expected exactly one subgraph")
    return model


def serialize_model(model: schema.ModelT) -> bytes:
    builder = flatbuffers.Builder(0)
    root = model.Pack(builder)
    builder.Finish(root, file_identifier=b"TFL3")
    return bytes(builder.Output())


def builtin_code(model: schema.ModelT, operator: schema.OperatorT) -> int:
    opcode = model.operatorCodes[int(operator.opcodeIndex)]
    return max(int(opcode.builtinCode), int(opcode.deprecatedBuiltinCode))


def public_contract(model: schema.ModelT) -> tuple[tuple[object, ...], ...]:
    subgraph = model.subgraphs[0]
    records = []
    for role, indices in (("input", subgraph.inputs), ("output", subgraph.outputs)):
        for ordinal, tensor_index in enumerate(_ints(indices)):
            tensor = subgraph.tensors[tensor_index]
            records.append(
                (role, ordinal, tensor_index, _text(tensor.name),
                 int(tensor.type), _shape(tensor))
            )
    signatures = model.signatureDefs or []
    for signature in signatures:
        records.append(
            ("signature", _text(signature.signatureKey), int(signature.subgraphIndex),
             tuple((_text(item.name), int(item.tensorIndex)) for item in signature.inputs),
             tuple((_text(item.name), int(item.tensorIndex)) for item in signature.outputs))
        )
    return tuple(records)


def constant_float32(model: schema.ModelT, tensor: schema.TensorT) -> np.ndarray:
    if int(tensor.type) != schema.TensorType.FLOAT32:
        raise ZipDepthRewriteError(
            f"Expected Float32 constant tensor: {_text(tensor.name)}"
        )
    data = model.buffers[int(tensor.buffer)].data
    if data is None:
        raise ZipDepthRewriteError(f"Expected constant tensor: {_text(tensor.name)}")
    values = np.frombuffer(bytes(data), dtype="<f4")
    shape = _shape(tensor)
    if values.size != int(np.prod(shape)):
        raise ZipDepthRewriteError(f"Malformed constant tensor: {_text(tensor.name)}")
    return values.reshape(shape)


def constant_int32(model: schema.ModelT, tensor: schema.TensorT) -> np.ndarray:
    if int(tensor.type) != schema.TensorType.INT32:
        raise ZipDepthRewriteError(
            f"Expected Int32 constant tensor: {_text(tensor.name)}"
        )
    data = model.buffers[int(tensor.buffer)].data
    if data is None:
        raise ZipDepthRewriteError(f"Expected constant tensor: {_text(tensor.name)}")
    values = np.frombuffer(bytes(data), dtype="<i4")
    shape = _shape(tensor)
    if values.size != int(np.prod(shape)):
        raise ZipDepthRewriteError(f"Malformed constant tensor: {_text(tensor.name)}")
    return values.reshape(shape)


def tensor_consumers(subgraph: schema.SubGraphT) -> dict[int, list[int]]:
    consumers: dict[int, list[int]] = {}
    for operator_index, operator in enumerate(subgraph.operators):
        for tensor_index in _ints(operator.inputs):
            if tensor_index >= 0:
                consumers.setdefault(tensor_index, []).append(operator_index)
    return consumers


def densify_group_conv(model: schema.ModelT) -> dict[str, object]:
    subgraph = model.subgraphs[0]
    matches = []
    for operator_index, operator in enumerate(subgraph.operators):
        if builtin_code(model, operator) != schema.BuiltinOperator.CONV_2D:
            continue
        if len(operator.inputs) != 3 or len(operator.outputs) != 1:
            continue
        input_tensor = subgraph.tensors[int(operator.inputs[0])]
        filter_tensor = subgraph.tensors[int(operator.inputs[1])]
        output_tensor = subgraph.tensors[int(operator.outputs[0])]
        if (
            _shape(input_tensor)[-1:] == (24,)
            and _shape(filter_tensor) == (32, 1, 1, 6)
            and _shape(output_tensor)[-1:] == (32,)
        ):
            matches.append((operator_index, operator, filter_tensor))
    if len(matches) != 1:
        raise ZipDepthRewriteError(
            "Expected exactly one 24->32 grouped 1x1 convolution, "
            f"found {len(matches)}"
        )

    operator_index, operator, filter_tensor = matches[0]
    options = operator.builtinOptions
    if (
        int(options.strideH) != 1
        or int(options.strideW) != 1
        or int(options.dilationHFactor) != 1
        or int(options.dilationWFactor) != 1
    ):
        raise ZipDepthRewriteError("Unexpected stride or dilation on target convolution")

    grouped = constant_float32(model, filter_tensor)
    filter_index = int(operator.inputs[1])
    consumers = tensor_consumers(subgraph)
    if consumers.get(filter_index) != [operator_index]:
        raise ZipDepthRewriteError("Target grouped-convolution filter has another consumer")
    buffer_index = int(filter_tensor.buffer)
    sharing_tensors = [
        index for index, tensor in enumerate(subgraph.tensors)
        if int(tensor.buffer) == buffer_index and index != filter_index
    ]
    if sharing_tensors:
        raise ZipDepthRewriteError(
            "Target grouped-convolution filter shares its buffer with tensors "
            f"{sharing_tensors}"
        )
    bias_tensor = subgraph.tensors[int(operator.inputs[2])]
    if _shape(bias_tensor) != (32,):
        raise ZipDepthRewriteError(
            f"Unexpected target convolution bias shape: {_shape(bias_tensor)}"
        )
    dense = np.zeros((32, 1, 1, 24), dtype="<f4")
    for group in range(4):
        dense[group * 8:(group + 1) * 8, :, :, group * 6:(group + 1) * 6] = (
            grouped[group * 8:(group + 1) * 8]
        )
    model.buffers[int(filter_tensor.buffer)].data = dense.tobytes()
    filter_tensor.shape = list(dense.shape)
    filter_tensor.shapeSignature = None
    return {
        "operator_index": operator_index,
        "filter": _text(filter_tensor.name),
        "old_shape": list(grouped.shape),
        "new_shape": list(dense.shape),
    }


def append_scalar(model: schema.ModelT, subgraph: schema.SubGraphT,
                  name: str, value: float) -> int:
    buffer = schema.BufferT()
    buffer.data = np.asarray([value], dtype="<f4").tobytes()
    model.buffers.append(buffer)
    tensor = schema.TensorT()
    tensor.name = name
    tensor.shape = [1]
    tensor.type = schema.TensorType.FLOAT32
    tensor.buffer = len(model.buffers) - 1
    tensor.isVariable = False
    subgraph.tensors.append(tensor)
    return len(subgraph.tensors) - 1


def append_activation(subgraph: schema.SubGraphT, template_index: int,
                      name: str) -> int:
    tensor = copy.deepcopy(subgraph.tensors[template_index])
    tensor.name = name
    tensor.buffer = 0
    tensor.isVariable = False
    tensor.quantization = None
    tensor.sparsity = None
    subgraph.tensors.append(tensor)
    return len(subgraph.tensors) - 1


def stabilize_gcb(model: schema.ModelT) -> dict[str, object]:
    subgraph = model.subgraphs[0]
    if any(_text(tensor.name).startswith("zipdepth_gcb_") for tensor in subgraph.tensors):
        raise ZipDepthRewriteError("Global-context scaling is already present")

    softmax_ops = [
        (index, operator) for index, operator in enumerate(subgraph.operators)
        if builtin_code(model, operator) == schema.BuiltinOperator.SOFTMAX
    ]
    if len(softmax_ops) != 1:
        raise ZipDepthRewriteError(f"Expected one SOFTMAX, found {len(softmax_ops)}")
    softmax_index, softmax = softmax_ops[0]
    if len(softmax.inputs) != 1 or len(softmax.outputs) != 1:
        raise ZipDepthRewriteError("Unexpected SOFTMAX input/output count")
    if float(softmax.builtinOptions.beta) != 1.0:
        raise ZipDepthRewriteError("Expected global-context SOFTMAX beta=1")
    softmax_output = int(softmax.outputs[0])

    public_input_shape = _shape(subgraph.tensors[int(subgraph.inputs[0])])
    if (
        len(public_input_shape) != 4
        or public_input_shape[0] != 1
        or public_input_shape[-1] != 3
        or public_input_shape[1] % 16 != 0
        or public_input_shape[2] % 16 != 0
    ):
        raise ZipDepthRewriteError(
            f"Unexpected ZipDepth public input shape: {public_input_shape}"
        )
    feature_height = public_input_shape[1] // 16
    feature_width = public_input_shape[2] // 16
    expected_softmax_shape = (1, 1, feature_height * feature_width)
    if _shape(subgraph.tensors[softmax_output]) != expected_softmax_shape:
        raise ZipDepthRewriteError(
            "Unexpected global-context SOFTMAX shape: "
            f"{_shape(subgraph.tensors[softmax_output])}"
        )

    reshape_matches = []
    for index, operator in enumerate(subgraph.operators):
        if (
            builtin_code(model, operator) == schema.BuiltinOperator.RESHAPE
            and softmax_output in _ints(operator.inputs)
            and len(operator.outputs) == 1
            and len(_shape(subgraph.tensors[int(operator.outputs[0])])) == 4
        ):
            reshape_matches.append((index, operator))
    if len(reshape_matches) != 1:
        raise ZipDepthRewriteError(
            f"Expected one rank-4 reshape after SOFTMAX, found {len(reshape_matches)}"
        )
    reshape_index, reshape = reshape_matches[0]
    probability_tensor = int(reshape.outputs[0])
    if _shape(subgraph.tensors[probability_tensor]) != (
        1, 1, feature_height, feature_width
    ):
        raise ZipDepthRewriteError(
            "Unexpected global-context probability view shape: "
            f"{_shape(subgraph.tensors[probability_tensor])}"
        )

    multiply_matches = []
    for index, operator in enumerate(subgraph.operators):
        if (
            builtin_code(model, operator) == schema.BuiltinOperator.MUL
            and probability_tensor in _ints(operator.inputs)
            and len(operator.outputs) == 1
        ):
            multiply_matches.append((index, operator))
    if len(multiply_matches) != 1:
        raise ZipDepthRewriteError(
            f"Expected one weighted MUL after SOFTMAX, found {len(multiply_matches)}"
        )
    multiply_index, multiply = multiply_matches[0]
    product_tensor = int(multiply.outputs[0])
    feature_inputs = [
        int(value) for value in multiply.inputs
        if int(value) != probability_tensor
    ]
    if len(feature_inputs) != 1 or _shape(subgraph.tensors[feature_inputs[0]]) != (
        1, 192, feature_height, feature_width
    ):
        raise ZipDepthRewriteError("Unexpected global-context weighted feature shape")
    if _shape(subgraph.tensors[product_tensor]) != (
        1, 192, feature_height, feature_width
    ):
        raise ZipDepthRewriteError("Unexpected global-context weighted product shape")

    sum_matches = []
    for index, operator in enumerate(subgraph.operators):
        if (
            builtin_code(model, operator) == schema.BuiltinOperator.SUM
            and _ints(operator.inputs)[0] == product_tensor
            and len(operator.outputs) == 1
        ):
            sum_matches.append((index, operator))
    if len(sum_matches) != 1:
        raise ZipDepthRewriteError(
            f"Expected one SUM after weighted MUL, found {len(sum_matches)}"
        )
    sum_index, summation = sum_matches[0]
    context_tensor = int(summation.outputs[0])
    if len(summation.inputs) != 2:
        raise ZipDepthRewriteError("Unexpected global-context SUM input count")
    axes_tensor = subgraph.tensors[int(summation.inputs[1])]
    if constant_int32(model, axes_tensor).reshape(-1).tolist() != [2, 3]:
        raise ZipDepthRewriteError("Global-context SUM must reduce axes [2, 3]")
    if not bool(summation.builtinOptions.keepDims):
        raise ZipDepthRewriteError("Global-context SUM must retain dimensions")
    if _shape(subgraph.tensors[context_tensor]) != (1, 192, 1, 1):
        raise ZipDepthRewriteError("Unexpected global-context sum output shape")
    if not (softmax_index < reshape_index < multiply_index < sum_index):
        raise ZipDepthRewriteError("Unexpected global-context operator ordering")
    consumers = tensor_consumers(subgraph)
    if consumers.get(softmax_output) != [reshape_index]:
        raise ZipDepthRewriteError("SOFTMAX output has an unexpected consumer chain")
    if consumers.get(probability_tensor) != [multiply_index]:
        raise ZipDepthRewriteError("Probability view has an unexpected consumer chain")
    if consumers.get(product_tensor) != [sum_index]:
        raise ZipDepthRewriteError("Weighted products have an unexpected consumer chain")
    context_consumers = consumers.get(context_tensor, [])
    if (
        len(context_consumers) != 1
        or builtin_code(model, subgraph.operators[context_consumers[0]])
        != schema.BuiltinOperator.TRANSPOSE
    ):
        raise ZipDepthRewriteError("Context sum must feed exactly one TRANSPOSE")

    model.buffers = list(model.buffers)
    subgraph.tensors = list(subgraph.tensors)
    scale_tensor = append_scalar(model, subgraph, "zipdepth_gcb_scale", GCB_SCALE)
    inverse_tensor = append_scalar(
        model, subgraph, "zipdepth_gcb_inverse_scale", 1.0 / GCB_SCALE
    )
    scaled_probability = append_activation(
        subgraph, probability_tensor, "zipdepth_gcb_scaled_mask"
    )
    scaled_sum = append_activation(
        subgraph, context_tensor, "zipdepth_gcb_scaled_sum"
    )

    scale_probability = copy.deepcopy(multiply)
    scale_probability.inputs = [probability_tensor, scale_tensor]
    scale_probability.outputs = [scaled_probability]
    multiply.inputs = [
        scaled_probability if int(value) == probability_tensor else int(value)
        for value in multiply.inputs
    ]
    summation.outputs = [scaled_sum]
    unscale_sum = copy.deepcopy(multiply)
    unscale_sum.inputs = [scaled_sum, inverse_tensor]
    unscale_sum.outputs = [context_tensor]

    rewritten = []
    for operator_index, operator in enumerate(subgraph.operators):
        if operator_index == multiply_index:
            rewritten.append(scale_probability)
        rewritten.append(operator)
        if operator_index == sum_index:
            rewritten.append(unscale_sum)
    subgraph.operators = rewritten
    return {
        "softmax_operator_index": softmax_index,
        "weighted_multiply_operator_index": multiply_index,
        "sum_operator_index": sum_index,
        "scale": GCB_SCALE,
    }


def cpu_output(path: Path, input_values: np.ndarray) -> np.ndarray:
    interpreter = Interpreter(
        model_path=str(path),
        num_threads=1,
        experimental_op_resolver_type=OpResolverType.BUILTIN_WITHOUT_DEFAULT_DELEGATES,
    )
    interpreter.allocate_tensors()
    input_detail = interpreter.get_input_details()[0]
    interpreter.set_tensor(int(input_detail["index"]), input_values)
    interpreter.invoke()
    return interpreter.get_tensor(int(interpreter.get_output_details()[0]["index"]))


def validate_cpu_bit_exact(source: Path, candidate: Path) -> None:
    interpreter = Interpreter(
        model_path=str(source),
        num_threads=1,
        experimental_op_resolver_type=OpResolverType.BUILTIN_WITHOUT_DEFAULT_DELEGATES,
    )
    interpreter.allocate_tensors()
    details = interpreter.get_input_details()
    if len(details) != 1 or details[0]["dtype"] != np.float32:
        raise ZipDepthRewriteError("Expected one Float32 public input")
    shape = tuple(int(value) for value in details[0]["shape"])
    values = np.random.default_rng(0x5A495044).random(shape, dtype=np.float32)
    reference = cpu_output(source, values)
    actual = cpu_output(candidate, values)
    if not np.array_equal(reference, actual):
        delta = np.abs(reference.astype(np.float64) - actual.astype(np.float64))
        raise ZipDepthRewriteError(
            "Rewrite changed CPU output: "
            f"max_abs={float(np.max(delta)):.9g} mae={float(np.mean(delta)):.9g}"
        )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--expected-source-sha256", required=True)
    parser.add_argument(
        "--rewrite",
        choices=("densify-group-conv", "stabilize-gcb"),
        required=True,
    )
    parser.add_argument(
        "--force",
        action="store_true",
        help="replace an existing loose output after all guards and validation pass",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    source, output = validate_paths(args.source, args.output)
    if output.exists() and not args.force:
        raise ZipDepthRewriteError(
            f"Output already exists; pass --force to replace it: {output}"
        )
    actual_source_hash = sha256_file(source)
    if actual_source_hash != args.expected_source_sha256.lower():
        raise ZipDepthRewriteError(
            "Source SHA-256 mismatch: expected "
            f"{args.expected_source_sha256.lower()}, got {actual_source_hash}"
        )
    known = KNOWN_REWRITES[args.rewrite].get(actual_source_hash)
    if known is None:
        raise ZipDepthRewriteError(
            f"Source is not a pinned {args.rewrite} ZipDepth graph: {actual_source_hash}"
        )
    source_model = load_model(source)
    source_contract = public_contract(source_model)
    source_ops = len(source_model.subgraphs[0].operators)
    if args.rewrite == "densify-group-conv":
        detail = densify_group_conv(source_model)
        expected_added_ops = 0
    else:
        detail = stabilize_gcb(source_model)
        expected_added_ops = 2

    data = serialize_model(source_model)
    output_hash = sha256_bytes(data)
    if output_hash != known["output_sha256"]:
        raise ZipDepthRewriteError(
            "Generated model does not match the pinned deterministic output: "
            f"expected {known['output_sha256']}, got {output_hash}"
        )
    candidate_model = schema.ModelT.InitFromObj(schema.Model.GetRootAsModel(data, 0))
    if public_contract(candidate_model) != source_contract:
        raise ZipDepthRewriteError("Rewrite changed the public tensor contract")
    candidate_ops = len(candidate_model.subgraphs[0].operators)
    if candidate_ops != source_ops + expected_added_ops:
        raise ZipDepthRewriteError(
            f"Unexpected operator count: {source_ops} -> {candidate_ops}"
        )

    output.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{output.name}.", suffix=".tmp", dir=output.parent
    )
    os.close(descriptor)
    temporary = Path(temporary_name)
    try:
        temporary.write_bytes(data)
        validate_cpu_bit_exact(source, temporary)
        if output.exists() and not args.force:
            raise ZipDepthRewriteError(
                "Output appeared while validation was running; refusing to replace it "
                f"without --force: {output}"
            )
        os.replace(temporary, output)
    finally:
        temporary.unlink(missing_ok=True)
    print(
        f"rewrite={args.rewrite} ops={source_ops}->{candidate_ops} "
        f"geometry={known['geometry']} cpu_bit_exact=true sha256={output_hash} "
        f"detail={detail} "
        f"output={output}",
        flush=True,
    )


if __name__ == "__main__":
    main()
