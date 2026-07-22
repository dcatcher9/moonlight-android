#!/usr/bin/env python3
"""Build guarded experimental DA-V2 attention-layout models.

The verified static Depth Anything V2 Small graphs have twelve transformer
attention products.  For the 350x196 and 490x140 buckets they have this form::

    [1, 6, 351, 351] @ [1, 6, 351, 64] -> [1, 6, 351, 64]

In ``k352`` mode the tool inserts zero-padding on both operands immediately
before each BATCH_MATMUL.  The inner dimension becomes 352 while the result
remains [1, 6, 351, 64].  In ``km352`` mode it additionally pads the query (M)
dimension to 352, computes [1, 6, 352, 64], then slices the first 351 queries
back into the original output tensor before any existing consumer.  Both
modes preserve the exact mathematical result.  This is an intentionally
narrow experiment for isolating OpenCL FP16/compiler behaviour; it is not a
model exporter and never edits the checked-in model.

In ``rank3`` mode, the operands are instead reshaped to [6, 351, 351] and
[6, 351, 64], the existing untransposed BATCH_MATMUL produces [6, 351, 64],
and a final reshape restores the original [1, 6, 351, 64] tensor before its
existing consumers.  The removed leading dimension is exactly one, so only
the operator rank changes; the mathematical product does not.

In ``presoftmax`` mode, the minimum number of -65504 lanes needed to align the
token count to four is concatenated to the QK logits before SOFTMAX, while the
same number of zero rows is concatenated to V.  Thus 351 tokens become 352 and
337 tokens become 340.  The value BATCH_MATMUL still produces the original
token count.  Float32 exp(-65504) is exactly zero, so the added lanes
contribute neither probability nor value.  CONCATENATION is intentional here:
unlike a post-softmax PAD, it forces the delegate to repack the attention
tensors.  Each model shares one immutable sentinel tensor and one immutable
zero tensor across all twelve blocks; concatenation outputs remain distinct.

In ``gelu-only`` mode, an already four-lane-aligned source keeps every
attention tensor and product unchanged.  The mode accepts no hidden-tail
workaround: it requires zero tail padding, then replaces exactly the twelve
guarded expanded GELU DAGs with builtin exact GELU.  This is the appropriate
path for the 322x182 graph's 23x13 patch grid plus CLS, or 300 tokens.  It must
not be used on the 351- or 337-token sources that require explicit pre-softmax
alignment for correct Qualcomm OpenCL FP16 execution.

With ``--fuse-gelu``, the guarded presoftmax rewrite is followed by an exact
GELU fusion.  Each of the twelve private 24-operator polynomial/ERF DAGs is
matched by operator types, wiring, shapes, float constants, names, producers,
and consumers, then replaced by one builtin GELU v1 with
``approximate=false``.  Its input and terminal output tensor IDs are retained;
the now-dead intermediate tensors and constant buffers intentionally remain in
the first experimental model.

Generation is guarded by the exact original source size, SHA-256, graph
contract, and the twelve known operator positions.  The source may be an exact
backup at any safe location, but never inside Apollo-3D; optimized production
assets are not valid sources.  The destination must be explicit, under
this repository's build/ or temp/ tree, must not exist, must not be the source
or an Android source-assets directory, and must never be inside Apollo-3D.
The serialized graph is reparsed and a CPU LiteRT interpreter must allocate it
before the destination is published.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import sys
import tempfile
from typing import Any, Iterable, NamedTuple, Sequence

try:
    import flatbuffers
    from flatbuffers.util import BufferHasIdentifier
except ImportError as exc:  # pragma: no cover - environment setup failure
    raise SystemExit("Missing flatbuffers; use the DA-V2 LiteRT Python environment") from exc

try:
    from ai_edge_litert import schema_py_generated as schema
except ImportError:
    try:
        from tensorflow.lite.python import schema_py_generated as schema
    except ImportError as exc:  # pragma: no cover - environment setup failure
        raise SystemExit("Missing ai-edge-litert (or TensorFlow Lite schema bindings)") from exc


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
EXPECTED_SIGNATURE_KEY = "serving_default"
EXPECTED_SIGNATURE_OUTPUT_NAME = "depth_bhwc"


class SourceContract(NamedTuple):
    filename: str
    source_size: int
    source_sha256: str
    subgraph_name: str
    inputs: tuple[int, ...]
    outputs: tuple[int, ...]
    input_shape: tuple[int, ...]
    output_shape: tuple[int, ...]
    tensor_count: int
    operator_count: int
    buffer_count: int
    operator_code_count: int
    token_count: int
    target_operator_indices: tuple[int, ...]


EXPECTED_TARGET_OPERATOR_INDICES = (
    29,
    98,
    167,
    250,
    323,
    392,
    475,
    548,
    617,
    699,
    771,
    840,
)
SOURCE_ASSET_DIRECTORY = REPOSITORY_ROOT / "app" / "src" / "nonRoot_game" / "assets"
ORIGINAL_MODEL_BACKUP_DIRECTORY = REPOSITORY_ROOT / "build" / "client-sbs-original-models"
SOURCE_CONTRACTS = (
    SourceContract(
        filename="depth-anything-v2-small-static-350x196-float32.tflite.model",
        source_size=97_496_648,
        source_sha256="4e62f378646966c99855e4648cbc22f3b6f8ce4ea2efbefd27ee735300f98e57",
        subgraph_name="depth_anything_v2_small_static_350x196_rank4_attention",
        inputs=(0,),
        outputs=(1512,),
        input_shape=(1, 196, 350, 3),
        output_shape=(1, 196, 350, 1),
        tensor_count=1587,
        operator_count=959,
        buffer_count=629,
        operator_code_count=20,
        token_count=351,
        target_operator_indices=EXPECTED_TARGET_OPERATOR_INDICES,
    ),
    SourceContract(
        filename="depth-anything-v2-small-static-392x168-float32.tflite.model",
        source_size=97_475_144,
        source_sha256="5d1cb6cfe13a6fb984ca23410df6e70eb5846ad4af7c49e6e89e193463a3869a",
        subgraph_name="depth_anything_v2_small_static_392x168_rank4_attention",
        inputs=(0,),
        outputs=(1512,),
        input_shape=(1, 168, 392, 3),
        output_shape=(1, 168, 392, 1),
        tensor_count=1587,
        operator_count=959,
        buffer_count=629,
        operator_code_count=20,
        token_count=337,
        target_operator_indices=EXPECTED_TARGET_OPERATOR_INDICES,
    ),
    SourceContract(
        filename="depth-anything-v2-small-static-490x140-float32.tflite.model",
        source_size=97_496_648,
        source_sha256="ffc15cbc13a5b499844b7fede553bf14b9895bc10bd1904b253419bcc5dbcdb9",
        subgraph_name="depth_anything_v2_small_static_490x140_rank4_attention",
        inputs=(0,),
        outputs=(1512,),
        input_shape=(1, 140, 490, 3),
        output_shape=(1, 140, 490, 1),
        tensor_count=1587,
        operator_count=959,
        buffer_count=629,
        operator_code_count=20,
        token_count=351,
        target_operator_indices=EXPECTED_TARGET_OPERATOR_INDICES,
    ),
    SourceContract(
        filename="depth-anything-v2-small-static-322x182-float32.tflite.model",
        source_size=97_418_312,
        source_sha256="eaf4f4fc25809da9000ba4e5330b1e3335722b1937fcd94c6e4935fbc411bc23",
        subgraph_name="depth_anything_v2_small_static_322x182_rank4_attention",
        inputs=(0,),
        outputs=(1512,),
        input_shape=(1, 182, 322, 3),
        output_shape=(1, 182, 322, 1),
        tensor_count=1587,
        operator_count=959,
        buffer_count=629,
        operator_code_count=20,
        token_count=300,
        target_operator_indices=EXPECTED_TARGET_OPERATOR_INDICES,
    ),
    SourceContract(
        filename="depth-anything-v2-small-static-350x154-float32.tflite.model",
        source_size=97_381_448,
        source_sha256="174ab97d5fb87c1d992f1c0ff6700ced949ccd3e5eda3bdf641be2c446f441f1",
        subgraph_name="depth_anything_v2_small_static_350x154_rank4_attention",
        inputs=(0,),
        outputs=(1512,),
        input_shape=(1, 154, 350, 3),
        output_shape=(1, 154, 350, 1),
        tensor_count=1587,
        operator_count=959,
        buffer_count=629,
        operator_code_count=20,
        token_count=276,
        target_operator_indices=EXPECTED_TARGET_OPERATOR_INDICES,
    ),
    SourceContract(
        filename="depth-anything-v2-small-static-434x126-float32.tflite.model",
        source_size=97_387_592,
        source_sha256="0e746d66a40eaa6673cef93144f49843c0f0a10fc618dbe15cc71bba2f9a3055",
        subgraph_name="depth_anything_v2_small_static_434x126_rank4_attention",
        inputs=(0,),
        outputs=(1512,),
        input_shape=(1, 126, 434, 3),
        output_shape=(1, 126, 434, 1),
        tensor_count=1587,
        operator_count=959,
        buffer_count=629,
        operator_code_count=20,
        token_count=280,
        target_operator_indices=EXPECTED_TARGET_OPERATOR_INDICES,
    ),
)
DEFAULT_SOURCE_CONTRACT = SOURCE_CONTRACTS[0]
DEFAULT_SOURCE = ORIGINAL_MODEL_BACKUP_DIRECTORY / DEFAULT_SOURCE_CONTRACT.filename

# Backward-compatible aliases used by the original 350x196 experimental modes
# and their focused tests.
EXPECTED_SOURCE_SIZE = DEFAULT_SOURCE_CONTRACT.source_size
EXPECTED_SOURCE_SHA256 = DEFAULT_SOURCE_CONTRACT.source_sha256
EXPECTED_SUBGRAPH_NAME = DEFAULT_SOURCE_CONTRACT.subgraph_name
EXPECTED_INPUTS = list(DEFAULT_SOURCE_CONTRACT.inputs)
EXPECTED_OUTPUTS = list(DEFAULT_SOURCE_CONTRACT.outputs)
EXPECTED_INPUT_SHAPE = list(DEFAULT_SOURCE_CONTRACT.input_shape)
EXPECTED_OUTPUT_SHAPE = list(DEFAULT_SOURCE_CONTRACT.output_shape)
EXPECTED_TENSOR_COUNT = DEFAULT_SOURCE_CONTRACT.tensor_count
EXPECTED_OPERATOR_COUNT = DEFAULT_SOURCE_CONTRACT.operator_count
EXPECTED_BUFFER_COUNT = DEFAULT_SOURCE_CONTRACT.buffer_count
EXPECTED_OPERATOR_CODE_COUNT = DEFAULT_SOURCE_CONTRACT.operator_code_count

TARGET_LHS_SHAPE = [1, 6, 351, 351]
TARGET_RHS_SHAPE = [1, 6, 351, 64]
TARGET_OUTPUT_SHAPE = [1, 6, 351, 64]
PADDED_LHS_SHAPE = [1, 6, 351, 352]
PADDED_RHS_SHAPE = [1, 6, 352, 64]
LHS_PADDINGS = [[0, 0], [0, 0], [0, 0], [0, 1]]
RHS_PADDINGS = [[0, 0], [0, 0], [0, 1], [0, 0]]
KM_PADDED_LHS_SHAPE = [1, 6, 352, 352]
KM_PADDED_OUTPUT_SHAPE = [1, 6, 352, 64]
KM_LHS_PADDINGS = [[0, 0], [0, 0], [0, 1], [0, 1]]
OUTPUT_SLICE_BEGIN = [0, 0, 0, 0]
OUTPUT_SLICE_SIZE = TARGET_OUTPUT_SHAPE
MODE_K352 = "k352"
MODE_KM352 = "km352"
MODE_RANK3 = "rank3"
MODE_PRESOFTMAX = "presoftmax"
MODE_GELU_ONLY = "gelu-only"
ATTENTION_REWRITE_MODES = (
    MODE_K352,
    MODE_KM352,
    MODE_RANK3,
    MODE_PRESOFTMAX,
)
MODES = (*ATTENTION_REWRITE_MODES, MODE_GELU_ONLY)
RANK3_LHS_SHAPE = [6, 351, 351]
RANK3_RHS_SHAPE = [6, 351, 64]
RANK3_OUTPUT_SHAPE = [6, 351, 64]
PRESOFTMAX_LOGIT_CONSTANT_SHAPE = [1, 6, 351, 1]
PRESOFTMAX_VALUE_CONSTANT_SHAPE = [1, 6, 1, 64]
PRESOFTMAX_LOGITS_SHAPE = [1, 6, 351, 352]
PRESOFTMAX_VALUE_SHAPE = [1, 6, 352, 64]
PRESOFTMAX_SENTINEL = -65504.0
GELU_EXPANDED_OPERATOR_NAMES = (
    "MUL",
    "ABS",
    "SIGN",
    "MUL",
    "ADD",
    "DIV",
    "MUL",
    "MUL",
    "EXP",
    "MUL",
    "ADD",
    "MUL",
    "ADD",
    "MUL",
    "ADD",
    "MUL",
    "ADD",
    "MUL",
    "MUL",
    "SUB",
    "MUL",
    "ADD",
    "MUL",
    "MUL",
)
GELU_CHAIN_LENGTH = len(GELU_EXPANDED_OPERATOR_NAMES)
GELU_CHAIN_COUNT = 12
GELU_REMOVED_OPERATOR_COUNT = GELU_CHAIN_COUNT * (GELU_CHAIN_LENGTH - 1)
GELU_ACTIVATION_WIDTH = 1536
GELU_BLOCK_OUTPUT_WIDTH = 384
GELU_OUTPUT_SUFFIXES = (
    "Div_output_0",
    "Erf_output_0_erf_abs",
    "Erf_output_0_erf_sign",
    "Erf_output_0_erf_px",
    "Erf_output_0_erf_one_plus_px",
    "Erf_output_0_erf_t",
    "Erf_output_0_erf_abs_sq",
    "Erf_output_0_erf_neg_abs_sq",
    "Erf_output_0_erf_exp",
    "Erf_output_0_erf_s1_mul",
    "Erf_output_0_erf_s1_add",
    "Erf_output_0_erf_s2_mul",
    "Erf_output_0_erf_s2_add",
    "Erf_output_0_erf_s3_mul",
    "Erf_output_0_erf_s3_add",
    "Erf_output_0_erf_s4_mul",
    "Erf_output_0_erf_s4_add",
    "Erf_output_0_erf_poly",
    "Erf_output_0_erf_poly_exp",
    "Erf_output_0_erf_one_minus",
    "Erf_output_0",
    "Add_output_0",
    "Mul_output_0",
    "Mul_1_output_0",
)
GELU_CONSTANT_SPECS = (
    ("scale", "Div_output_0_div_reciprocal", 0.70703125),
    ("p", "Erf_output_0_erf_p", 0.32763671875),
    ("one", "Erf_output_0_erf_one", 1.0),
    ("minus_one", "Erf_output_0_erf_minus_one", -1.0),
    ("a5", "Erf_output_0_erf_a5", 1.0615234375),
    ("a4", "Erf_output_0_erf_a4", -1.453125),
    ("a3", "Erf_output_0_erf_a3", 1.421875),
    ("a2", "Erf_output_0_erf_a2", -0.284423828125),
    ("a1", "Erf_output_0_erf_a1", 0.2548828125),
    ("add_one", "Constant_1_output_0", 1.0),
    ("half", "Constant_2_output_0", 0.5),
)
GELU_SHARED_CONSTANT_ROLES = ("add_one", "half")


class AttentionLayout(NamedTuple):
    token_count: int
    aligned_token_count: int
    tail_padding: int
    target_lhs_shape: list[int]
    target_rhs_shape: list[int]
    target_output_shape: list[int]
    presoftmax_logit_constant_shape: list[int]
    presoftmax_value_constant_shape: list[int]
    presoftmax_logits_shape: list[int]
    presoftmax_value_shape: list[int]


def attention_layout(token_count: int) -> AttentionLayout:
    token_count = int(token_count)
    if token_count <= 0:
        raise AttentionPadModelError(f"Token count must be positive, found {token_count}")
    tail_padding = (-token_count) % 4
    aligned_token_count = token_count + tail_padding
    return AttentionLayout(
        token_count=token_count,
        aligned_token_count=aligned_token_count,
        tail_padding=tail_padding,
        target_lhs_shape=[1, 6, token_count, token_count],
        target_rhs_shape=[1, 6, token_count, 64],
        target_output_shape=[1, 6, token_count, 64],
        presoftmax_logit_constant_shape=[1, 6, token_count, tail_padding],
        presoftmax_value_constant_shape=[1, 6, tail_padding, 64],
        presoftmax_logits_shape=[1, 6, token_count, aligned_token_count],
        presoftmax_value_shape=[1, 6, aligned_token_count, 64],
    )


DEFAULT_ATTENTION_LAYOUT = attention_layout(DEFAULT_SOURCE_CONTRACT.token_count)


class AttentionPadModelError(RuntimeError):
    """Raised when a source, graph, destination, or validation guard fails."""


def _path_has_component(path: Path, component: str) -> bool:
    return any(part.casefold() == component.casefold() for part in path.resolve().parts)


def _is_production_asset_path(path: Path) -> bool:
    return _is_relative_to(path.resolve(), SOURCE_ASSET_DIRECTORY.resolve())


def validate_source_path(path: Path) -> Path:
    resolved = path.resolve()
    if _path_has_component(resolved, "Apollo-3D"):
        raise AttentionPadModelError(
            f"Refusing to read a client model source inside Apollo-3D: {resolved}"
        )
    return resolved


def source_contract_for_identity(
    path: Path,
    size: int,
    digest: str,
) -> SourceContract:
    normalized_digest = digest.casefold()
    for contract in SOURCE_CONTRACTS:
        if size == contract.source_size and normalized_digest == contract.source_sha256:
            return contract
    if _is_production_asset_path(path):
        raise AttentionPadModelError(
            "The selected production asset is transformed/optimized and is not an "
            "original 959-operator source. Restore the exact original model under "
            f"{ORIGINAL_MODEL_BACKUP_DIRECTORY} or pass --source to an exact backup."
        )
    expected_sizes = sorted({contract.source_size for contract in SOURCE_CONTRACTS})
    if size not in expected_sizes:
        raise AttentionPadModelError(
            f"Source size is {size:,} bytes; expected one of "
            f"{[f'{value:,}' for value in expected_sizes]} for an original model"
        )
    expected_hashes = ", ".join(contract.source_sha256 for contract in SOURCE_CONTRACTS)
    raise AttentionPadModelError(
        f"Source SHA-256 is {normalized_digest}; it is not an exact original model. "
        f"Expected one of: {expected_hashes}"
    )


def source_contract_for_path(path: Path) -> SourceContract:
    resolved = validate_source_path(path)
    if not resolved.is_file():
        raise AttentionPadModelError(f"Source model does not exist: {resolved}")
    return source_contract_for_identity(
        resolved,
        resolved.stat().st_size,
        sha256_file(resolved),
    )


def _text(value: Any) -> str:
    if isinstance(value, bytes):
        return value.decode("utf-8", errors="replace")
    return str(value)


def _ints(values: Iterable[Any] | None) -> list[int]:
    if values is None:
        return []
    return [int(value) for value in values]


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def parse_model(data: bytes) -> schema.ModelT:
    if not BufferHasIdentifier(data, 0, b"TFL3"):
        raise AttentionPadModelError("Input is not a TFL3 FlatBuffer")
    try:
        return schema.ModelT.InitFromObj(schema.Model.GetRootAsModel(data, 0))
    except Exception as exc:
        raise AttentionPadModelError(f"Unable to parse TFLite FlatBuffer: {exc}") from exc


def serialize_model(model: schema.ModelT) -> bytes:
    builder = flatbuffers.Builder(0)
    root = model.Pack(builder)
    builder.Finish(root, file_identifier=b"TFL3")
    return bytes(builder.Output())


def builtin_operator_code(model: schema.ModelT, operator: schema.OperatorT) -> int:
    opcode = model.operatorCodes[int(operator.opcodeIndex)]
    return max(int(opcode.builtinCode), int(opcode.deprecatedBuiltinCode))


def operator_name(model: schema.ModelT, operator: schema.OperatorT) -> str:
    code = builtin_operator_code(model, operator)
    for name, value in vars(schema.BuiltinOperator).items():
        if isinstance(value, int) and value == code:
            return name
    return f"BUILTIN({code})"


def _producer_map(subgraph: Any) -> dict[int, int]:
    producers: dict[int, int] = {}
    for operator_index, operator in enumerate(subgraph.operators):
        for tensor_index in _ints(operator.outputs):
            if tensor_index < 0:
                continue
            if tensor_index in producers:
                raise AttentionPadModelError(
                    f"Tensor {tensor_index} has multiple producers: "
                    f"{producers[tensor_index]} and {operator_index}"
                )
            producers[tensor_index] = operator_index
    return producers


def _consumer_map(subgraph: Any) -> dict[int, list[tuple[int, int]]]:
    consumers: dict[int, list[tuple[int, int]]] = {}
    for operator_index, operator in enumerate(subgraph.operators):
        for input_position, tensor_index in enumerate(_ints(operator.inputs)):
            if tensor_index >= 0:
                consumers.setdefault(tensor_index, []).append(
                    (operator_index, input_position)
                )
    return consumers


def _tensor_shape(subgraph: Any, tensor_index: int) -> list[int]:
    if tensor_index < 0 or tensor_index >= len(subgraph.tensors):
        raise AttentionPadModelError(f"Invalid tensor index {tensor_index}")
    return _ints(subgraph.tensors[tensor_index].shape)


def _find_opcode_index(model: schema.ModelT, builtin_code: int) -> int:
    matches = []
    for index, opcode in enumerate(model.operatorCodes):
        code = max(int(opcode.builtinCode), int(opcode.deprecatedBuiltinCode))
        if code == int(builtin_code):
            matches.append(index)
    if len(matches) != 1:
        raise AttentionPadModelError(
            f"Expected one opcode entry for builtin {builtin_code}, found {matches}"
        )
    return matches[0]


def _batch_matmul_is_untransposed(operator: schema.OperatorT) -> bool:
    options = operator.builtinOptions
    return (
        options is not None
        and not bool(getattr(options, "adjX", True))
        and not bool(getattr(options, "adjY", True))
    )


def find_attention_value_products(
    model: schema.ModelT,
    *,
    layout: AttentionLayout = DEFAULT_ATTENTION_LAYOUT,
) -> list[int]:
    """Return the unmodified softmax-times-value BATCH_MATMUL positions."""
    if len(model.subgraphs) != 1:
        raise AttentionPadModelError(
            f"Expected one subgraph, found {len(model.subgraphs)}"
        )
    subgraph = model.subgraphs[0]
    producers = _producer_map(subgraph)
    matches: list[int] = []
    for operator_index, operator in enumerate(subgraph.operators):
        if builtin_operator_code(model, operator) != int(schema.BuiltinOperator.BATCH_MATMUL):
            continue
        inputs = _ints(operator.inputs)
        outputs = _ints(operator.outputs)
        if len(inputs) != 2 or len(outputs) != 1:
            continue
        if (
            _tensor_shape(subgraph, inputs[0]) != layout.target_lhs_shape
            or _tensor_shape(subgraph, inputs[1]) != layout.target_rhs_shape
            or _tensor_shape(subgraph, outputs[0]) != layout.target_output_shape
            or not _batch_matmul_is_untransposed(operator)
        ):
            continue
        producer_index = producers.get(inputs[0])
        if producer_index is None:
            continue
        if operator_name(model, subgraph.operators[producer_index]) != "SOFTMAX":
            continue
        if any(
            int(subgraph.tensors[index].type) != int(schema.TensorType.FLOAT32)
            for index in (inputs[0], inputs[1], outputs[0])
        ):
            continue
        matches.append(operator_index)
    return matches


def _validate_signature_and_io(
    model: schema.ModelT,
    contract: SourceContract = DEFAULT_SOURCE_CONTRACT,
) -> None:
    subgraph = model.subgraphs[0]
    expected_inputs = list(contract.inputs)
    expected_outputs = list(contract.outputs)
    expected_input_shape = list(contract.input_shape)
    expected_output_shape = list(contract.output_shape)
    if _ints(subgraph.inputs) != expected_inputs:
        raise AttentionPadModelError(f"Unexpected graph inputs: {_ints(subgraph.inputs)}")
    if _ints(subgraph.outputs) != expected_outputs:
        raise AttentionPadModelError(f"Unexpected graph outputs: {_ints(subgraph.outputs)}")
    if _tensor_shape(subgraph, expected_inputs[0]) != expected_input_shape:
        raise AttentionPadModelError("Unexpected model input shape")
    if _tensor_shape(subgraph, expected_outputs[0]) != expected_output_shape:
        raise AttentionPadModelError("Unexpected model output shape")
    if len(model.signatureDefs) != 1:
        raise AttentionPadModelError(
            f"Expected one SignatureDef, found {len(model.signatureDefs)}"
        )
    signature = model.signatureDefs[0]
    if _text(signature.signatureKey) != EXPECTED_SIGNATURE_KEY:
        raise AttentionPadModelError(
            f"Unexpected signature key: {_text(signature.signatureKey)!r}"
        )
    if int(signature.subgraphIndex) != 0 or len(signature.outputs) != 1:
        raise AttentionPadModelError("Unexpected SignatureDef graph/output structure")
    signature_output = signature.outputs[0]
    if (
        _text(signature_output.name) != EXPECTED_SIGNATURE_OUTPUT_NAME
        or int(signature_output.tensorIndex) != expected_outputs[0]
    ):
        raise AttentionPadModelError("Unexpected SignatureDef output")


def validate_verified_source_graph(
    model: schema.ModelT,
    contract: SourceContract = DEFAULT_SOURCE_CONTRACT,
) -> list[int]:
    if len(model.subgraphs) != 1:
        raise AttentionPadModelError(
            f"Expected one subgraph, found {len(model.subgraphs)}"
        )
    subgraph = model.subgraphs[0]
    errors: list[str] = []
    if _text(subgraph.name) != contract.subgraph_name:
        errors.append(f"subgraph name={_text(subgraph.name)!r}")
    if len(subgraph.tensors) != contract.tensor_count:
        errors.append(f"tensor count={len(subgraph.tensors)}")
    if len(subgraph.operators) != contract.operator_count:
        errors.append(f"operator count={len(subgraph.operators)}")
    if len(model.buffers) != contract.buffer_count:
        errors.append(f"buffer count={len(model.buffers)}")
    if len(model.operatorCodes) != contract.operator_code_count:
        errors.append(f"operator-code count={len(model.operatorCodes)}")
    if errors:
        raise AttentionPadModelError(
            "Verified bytes have an unexpected graph contract: " + "; ".join(errors)
        )
    _validate_signature_and_io(model, contract)
    for name, builtin_code in (
        ("PAD", schema.BuiltinOperator.PAD),
        ("SLICE", schema.BuiltinOperator.SLICE),
        ("RESHAPE", schema.BuiltinOperator.RESHAPE),
        ("CONCATENATION", schema.BuiltinOperator.CONCATENATION),
        ("SOFTMAX", schema.BuiltinOperator.SOFTMAX),
    ):
        opcode_index = _find_opcode_index(model, builtin_code)
        if int(model.operatorCodes[opcode_index].version) != 1:
            raise AttentionPadModelError(
                f"The verified {name} opcode is not version 1"
            )
    layout = attention_layout(contract.token_count)
    matches = find_attention_value_products(model, layout=layout)
    if tuple(matches) != contract.target_operator_indices:
        raise AttentionPadModelError(
            "Expected the 12 known attention value products at "
            f"{list(contract.target_operator_indices)}, found {matches}"
        )
    return matches


def load_verified_source(path: Path) -> tuple[bytes, schema.ModelT, SourceContract]:
    resolved = validate_source_path(path)
    if not resolved.is_file():
        if _same_path(resolved, DEFAULT_SOURCE):
            raise AttentionPadModelError(
                "Default original DA-V2 source is missing. Restore the exact original "
                f"models under {ORIGINAL_MODEL_BACKUP_DIRECTORY}, or pass --source "
                "with an exact original backup outside Apollo-3D."
            )
        raise AttentionPadModelError(f"Source model does not exist: {resolved}")
    size = resolved.stat().st_size
    if size <= 0:
        raise AttentionPadModelError(
            f"Source model is empty: {resolved}"
        )
    data = resolved.read_bytes()
    digest = sha256_bytes(data)
    contract = source_contract_for_identity(resolved, size, digest)
    model = parse_model(data)
    targets = validate_verified_source_graph(model, contract)
    print(
        f"source={resolved} size={size} sha256={digest} "
        f"operators={len(model.subgraphs[0].operators)} attention_targets={targets}",
        flush=True,
    )
    return data, model, contract


def _flatten_paddings(paddings: Sequence[Sequence[int]]) -> list[int]:
    if len(paddings) != 4 or any(len(row) != 2 for row in paddings):
        raise AttentionPadModelError(f"Paddings must have shape [4,2], found {paddings}")
    return [int(value) for row in paddings for value in row]


def _make_padding_constant(
    model: schema.ModelT,
    subgraph: Any,
    name: str,
    paddings: Sequence[Sequence[int]],
) -> int:
    values = _flatten_paddings(paddings)
    buffer = schema.BufferT()
    buffer.data = struct.pack("<8i", *values)
    buffer.offset = 0
    buffer.size = 0
    model.buffers.append(buffer)

    tensor = schema.TensorT()
    tensor.shape = [4, 2]
    tensor.shapeSignature = [4, 2]
    tensor.type = schema.TensorType.INT32
    tensor.buffer = len(model.buffers) - 1
    tensor.name = name
    tensor.isVariable = False
    tensor.hasRank = False
    subgraph.tensors.append(tensor)
    return len(subgraph.tensors) - 1


def _make_int32_constant(
    model: schema.ModelT,
    subgraph: Any,
    name: str,
    values: Sequence[int],
) -> int:
    packed_values = [int(value) for value in values]
    buffer = schema.BufferT()
    buffer.data = struct.pack(f"<{len(packed_values)}i", *packed_values)
    buffer.offset = 0
    buffer.size = 0
    model.buffers.append(buffer)

    tensor = schema.TensorT()
    tensor.shape = [len(packed_values)]
    tensor.shapeSignature = [len(packed_values)]
    tensor.type = schema.TensorType.INT32
    tensor.buffer = len(model.buffers) - 1
    tensor.name = name
    tensor.isVariable = False
    tensor.hasRank = False
    subgraph.tensors.append(tensor)
    return len(subgraph.tensors) - 1


def _make_float_constant(
    model: schema.ModelT,
    subgraph: Any,
    name: str,
    shape: Sequence[int],
    value: float,
) -> int:
    fixed_shape = [int(dimension) for dimension in shape]
    element_count = 1
    for dimension in fixed_shape:
        if dimension <= 0:
            raise AttentionPadModelError(f"Invalid float constant shape {fixed_shape}")
        element_count *= dimension
    buffer = schema.BufferT()
    buffer.data = struct.pack("<f", float(value)) * element_count
    buffer.offset = 0
    buffer.size = 0
    model.buffers.append(buffer)

    tensor = schema.TensorT()
    tensor.shape = fixed_shape
    tensor.shapeSignature = fixed_shape.copy()
    tensor.type = schema.TensorType.FLOAT32
    tensor.buffer = len(model.buffers) - 1
    tensor.name = name
    tensor.isVariable = False
    tensor.hasRank = False
    subgraph.tensors.append(tensor)
    return len(subgraph.tensors) - 1


def _make_padded_tensor(
    subgraph: Any,
    source_tensor_index: int,
    name: str,
    shape: Sequence[int],
) -> int:
    source = subgraph.tensors[source_tensor_index]
    if int(source.type) != int(schema.TensorType.FLOAT32):
        raise AttentionPadModelError(f"Padding source {source_tensor_index} is not FLOAT32")
    tensor = schema.TensorT()
    tensor.shape = list(shape)
    tensor.shapeSignature = list(shape)
    tensor.type = schema.TensorType.FLOAT32
    tensor.buffer = 0
    tensor.name = name
    tensor.isVariable = False
    tensor.hasRank = False
    subgraph.tensors.append(tensor)
    return len(subgraph.tensors) - 1


def _make_pad_operator(
    pad_opcode_index: int,
    source_tensor_index: int,
    padding_tensor_index: int,
    output_tensor_index: int,
) -> schema.OperatorT:
    operator = schema.OperatorT()
    operator.opcodeIndex = pad_opcode_index
    operator.inputs = [source_tensor_index, padding_tensor_index]
    operator.outputs = [output_tensor_index]
    operator.builtinOptionsType = schema.BuiltinOptions.NONE
    operator.builtinOptions = None
    return operator


def _make_slice_operator(
    slice_opcode_index: int,
    source_tensor_index: int,
    begin_tensor_index: int,
    size_tensor_index: int,
    output_tensor_index: int,
) -> schema.OperatorT:
    operator = schema.OperatorT()
    operator.opcodeIndex = slice_opcode_index
    operator.inputs = [source_tensor_index, begin_tensor_index, size_tensor_index]
    operator.outputs = [output_tensor_index]
    operator.builtinOptionsType = schema.BuiltinOptions.NONE
    operator.builtinOptions = None
    return operator


def _make_reshape_operator(
    reshape_opcode_index: int,
    source_tensor_index: int,
    shape_tensor_index: int,
    output_tensor_index: int,
    output_shape: Sequence[int],
) -> schema.OperatorT:
    options = schema.ReshapeOptionsT()
    options.newShape = [int(value) for value in output_shape]
    operator = schema.OperatorT()
    operator.opcodeIndex = reshape_opcode_index
    operator.inputs = [source_tensor_index, shape_tensor_index]
    operator.outputs = [output_tensor_index]
    operator.builtinOptionsType = schema.BuiltinOptions.ReshapeOptions
    operator.builtinOptions = options
    return operator


def _make_concat_operator(
    concat_opcode_index: int,
    input_tensor_indices: Sequence[int],
    output_tensor_index: int,
    axis: int,
) -> schema.OperatorT:
    options = schema.ConcatenationOptionsT()
    options.axis = int(axis)
    options.fusedActivationFunction = schema.ActivationFunctionType.NONE
    operator = schema.OperatorT()
    operator.opcodeIndex = concat_opcode_index
    operator.inputs = [int(value) for value in input_tensor_indices]
    operator.outputs = [output_tensor_index]
    operator.builtinOptionsType = schema.BuiltinOptions.ConcatenationOptions
    operator.builtinOptions = options
    return operator


def _expanded_gelu_type_candidates(model: schema.ModelT) -> list[int]:
    subgraph = model.subgraphs[0]
    names = [operator_name(model, operator) for operator in subgraph.operators]
    width = GELU_CHAIN_LENGTH
    return [
        start
        for start in range(len(names) - width + 1)
        if tuple(names[start : start + width]) == GELU_EXPANDED_OPERATOR_NAMES
    ]


def _validate_gelu_data_tensor(
    tensor: schema.TensorT,
    *,
    expected_name: str,
    expected_shape: Sequence[int],
) -> None:
    shape = [int(value) for value in expected_shape]
    if _text(tensor.name) != expected_name:
        raise AttentionPadModelError(
            f"Expanded GELU tensor name changed: {_text(tensor.name)!r}, "
            f"expected {expected_name!r}"
        )
    if _ints(tensor.shape) != shape or _ints(tensor.shapeSignature) != shape:
        raise AttentionPadModelError(
            f"Expanded GELU tensor {expected_name!r} shape changed"
        )
    if (
        int(tensor.type) != int(schema.TensorType.FLOAT32)
        or int(tensor.buffer) != 0
        or bool(tensor.isVariable)
    ):
        raise AttentionPadModelError(
            f"Expanded GELU tensor {expected_name!r} is not a transient FLOAT32 tensor"
        )


def _validate_gelu_scalar_constant(
    model: schema.ModelT,
    subgraph: Any,
    producers: dict[int, int],
    tensor_index: int,
    *,
    expected_name: str,
    expected_value: float,
) -> None:
    tensor = subgraph.tensors[tensor_index]
    if _text(tensor.name) != expected_name:
        raise AttentionPadModelError(
            f"Expanded GELU constant name changed: {_text(tensor.name)!r}, "
            f"expected {expected_name!r}"
        )
    if (
        _ints(tensor.shape) != [1]
        or _ints(tensor.shapeSignature) != [1]
        or int(tensor.type) != int(schema.TensorType.FLOAT32)
        or bool(tensor.isVariable)
        or tensor_index in producers
    ):
        raise AttentionPadModelError(
            f"Expanded GELU constant {expected_name!r} is not a static FLOAT32 [1] tensor"
        )
    buffer_index = int(tensor.buffer)
    if buffer_index <= 0 or buffer_index >= len(model.buffers):
        raise AttentionPadModelError(
            f"Expanded GELU constant {expected_name!r} has an invalid buffer"
        )
    data = model.buffers[buffer_index].data
    expected = struct.pack("<f", float(expected_value))
    if data is None or bytes(data) != expected:
        raise AttentionPadModelError(
            f"Expanded GELU constant {expected_name!r} is not exactly {expected_value}"
        )


def _validate_expanded_gelu_candidate(
    model: schema.ModelT,
    start: int,
    *,
    layout: AttentionLayout,
    producers: dict[int, int],
    consumers: dict[int, list[tuple[int, int]]],
) -> dict[str, Any]:
    subgraph = model.subgraphs[0]
    operators = subgraph.operators[start : start + GELU_CHAIN_LENGTH]
    inputs = [_ints(operator.inputs) for operator in operators]
    outputs = [_ints(operator.outputs) for operator in operators]
    expected_input_counts = (
        2,
        1,
        1,
        2,
        2,
        2,
        2,
        2,
        1,
        2,
        2,
        2,
        2,
        2,
        2,
        2,
        2,
        2,
        2,
        2,
        2,
        2,
        2,
        2,
    )
    if tuple(len(value) for value in inputs) != expected_input_counts:
        raise AttentionPadModelError(
            f"Expanded GELU candidate at operator {start} has unexpected arity"
        )
    if any(len(value) != 1 for value in outputs):
        raise AttentionPadModelError(
            f"Expanded GELU candidate at operator {start} does not have single outputs"
        )
    output_tensors = [value[0] for value in outputs]
    if len(set(output_tensors)) != GELU_CHAIN_LENGTH:
        raise AttentionPadModelError(
            f"Expanded GELU candidate at operator {start} reuses output tensors"
        )

    source_tensor = inputs[0][0]
    source_name = _text(subgraph.tensors[source_tensor].name)
    match = re.fullmatch(r"wa/blocks\.(\d+)/mlp/fc1/Add_output_0", source_name)
    if match is None:
        raise AttentionPadModelError(
            f"Expanded GELU source tensor name changed: {source_name!r}"
        )
    block = int(match.group(1))
    activation_prefix = f"wa/blocks.{block}/mlp/act/"
    activation_shape = [1, layout.token_count, GELU_ACTIVATION_WIDTH]
    _validate_gelu_data_tensor(
        subgraph.tensors[source_tensor],
        expected_name=source_name,
        expected_shape=activation_shape,
    )
    for tensor_index, suffix in zip(output_tensors, GELU_OUTPUT_SUFFIXES):
        _validate_gelu_data_tensor(
            subgraph.tensors[tensor_index],
            expected_name=activation_prefix + suffix,
            expected_shape=activation_shape,
        )

    constants = {
        "scale": inputs[0][1],
        "p": inputs[3][1],
        "one": inputs[4][0],
        "minus_one": inputs[7][1],
        "a5": inputs[9][0],
        "a4": inputs[10][1],
        "a3": inputs[12][1],
        "a2": inputs[14][1],
        "a1": inputs[16][1],
        "add_one": inputs[21][1],
        "half": inputs[23][1],
    }
    if len(set(constants.values())) != len(GELU_CONSTANT_SPECS):
        raise AttentionPadModelError(
            f"Expanded GELU block {block} unexpectedly aliases constants"
        )
    for role, suffix, value in GELU_CONSTANT_SPECS:
        constant_prefix = (
            "wa/blocks.0/mlp/act/"
            if role in GELU_SHARED_CONSTANT_ROLES
            else activation_prefix
        )
        _validate_gelu_scalar_constant(
            model,
            subgraph,
            producers,
            constants[role],
            expected_name=constant_prefix + suffix,
            expected_value=value,
        )

    value = output_tensors
    expected_inputs = (
        [source_tensor, constants["scale"]],
        [value[0]],
        [value[0]],
        [value[1], constants["p"]],
        [constants["one"], value[3]],
        [constants["one"], value[4]],
        [value[1], value[1]],
        [value[6], constants["minus_one"]],
        [value[7]],
        [constants["a5"], value[5]],
        [value[9], constants["a4"]],
        [value[10], value[5]],
        [value[11], constants["a3"]],
        [value[12], value[5]],
        [value[13], constants["a2"]],
        [value[14], value[5]],
        [value[15], constants["a1"]],
        [value[16], value[5]],
        [value[17], value[8]],
        [constants["one"], value[18]],
        [value[2], value[19]],
        [value[20], constants["add_one"]],
        [source_tensor, value[21]],
        [value[22], constants["half"]],
    )
    if tuple(inputs) != expected_inputs:
        raise AttentionPadModelError(
            f"Expanded GELU block {block} wiring changed"
        )

    source_producer = producers.get(source_tensor)
    if (
        source_producer is None
        or operator_name(model, subgraph.operators[source_producer]) != "ADD"
        or _ints(subgraph.operators[source_producer].outputs) != [source_tensor]
    ):
        raise AttentionPadModelError(
            f"Expanded GELU block {block} source is not produced by fc1 ADD"
        )

    private_tensors = [
        source_tensor,
        *output_tensors[:-1],
        *(
            tensor_index
            for role, tensor_index in constants.items()
            if role not in GELU_SHARED_CONSTANT_ROLES
        ),
    ]
    for tensor_index in private_tensors:
        expected_uses: list[tuple[int, int]] = []
        for local_operator, operator_inputs in enumerate(expected_inputs):
            for input_position, candidate in enumerate(operator_inputs):
                if candidate == tensor_index:
                    expected_uses.append((start + local_operator, input_position))
        if consumers.get(tensor_index, []) != expected_uses:
            raise AttentionPadModelError(
                f"Expanded GELU block {block} tensor {tensor_index} is not private: "
                f"consumers={consumers.get(tensor_index, [])}, expected={expected_uses}"
            )

    for local_operator, tensor_index in enumerate(output_tensors):
        if producers.get(tensor_index) != start + local_operator:
            raise AttentionPadModelError(
                f"Expanded GELU block {block} tensor {tensor_index} producer changed"
            )

    terminal_tensor = output_tensors[-1]
    terminal_consumers = consumers.get(terminal_tensor, [])
    if len(terminal_consumers) != 1:
        raise AttentionPadModelError(
            f"Expanded GELU block {block} terminal tensor is not private to fc2"
        )
    downstream_index, downstream_input_position = terminal_consumers[0]
    downstream = subgraph.operators[downstream_index]
    downstream_inputs = _ints(downstream.inputs)
    downstream_outputs = _ints(downstream.outputs)
    if (
        downstream_input_position != 0
        or operator_name(model, downstream) != "BATCH_MATMUL"
        or len(downstream_inputs) != 2
        or len(downstream_outputs) != 1
        or downstream_inputs[0] != terminal_tensor
        or _tensor_shape(subgraph, downstream_inputs[1])
        != [GELU_ACTIVATION_WIDTH, GELU_BLOCK_OUTPUT_WIDTH]
        or _tensor_shape(subgraph, downstream_outputs[0])
        != [1, layout.token_count, GELU_BLOCK_OUTPUT_WIDTH]
    ):
        raise AttentionPadModelError(
            f"Expanded GELU block {block} terminal consumer is not the expected fc2 BMM"
        )

    return {
        "block": block,
        "source_operator_indices": list(range(start, start + GELU_CHAIN_LENGTH)),
        "input": source_tensor,
        "output": terminal_tensor,
        "intermediate_tensors": output_tensors[:-1],
        "constant_tensors": [constants[role] for role, _suffix, _value in GELU_CONSTANT_SPECS],
        "constants": constants,
        "downstream_output": downstream_outputs[0],
    }


def find_expanded_gelu_chains(
    model: schema.ModelT,
    *,
    layout: AttentionLayout = DEFAULT_ATTENTION_LAYOUT,
) -> list[dict[str, Any]]:
    if len(model.subgraphs) != 1:
        raise AttentionPadModelError(
            f"Expected one subgraph, found {len(model.subgraphs)}"
        )
    starts = _expanded_gelu_type_candidates(model)
    if len(starts) != GELU_CHAIN_COUNT:
        raise AttentionPadModelError(
            f"Expected exactly {GELU_CHAIN_COUNT} expanded GELU type sequences, "
            f"found {starts}"
        )
    subgraph = model.subgraphs[0]
    producers = _producer_map(subgraph)
    consumers = _consumer_map(subgraph)
    records = [
        _validate_expanded_gelu_candidate(
            model,
            start,
            layout=layout,
            producers=producers,
            consumers=consumers,
        )
        for start in starts
    ]
    blocks = [int(record["block"]) for record in records]
    if blocks != list(range(GELU_CHAIN_COUNT)):
        raise AttentionPadModelError(
            f"Expanded GELU blocks are not exactly 0..11 in graph order: {blocks}"
        )
    consumers = _consumer_map(model.subgraphs[0])
    for role, local_operator in (("add_one", 21), ("half", 23)):
        indices = {int(record["constants"][role]) for record in records}
        if len(indices) != 1:
            raise AttentionPadModelError(
                f"Expanded GELU blocks do not share exactly one {role} constant"
            )
        tensor_index = next(iter(indices))
        expected_uses = [
            (int(record["source_operator_indices"][0]) + local_operator, 1)
            for record in records
        ]
        if consumers.get(tensor_index, []) != expected_uses:
            raise AttentionPadModelError(
                f"Expanded GELU shared {role} constant has unexpected consumers: "
                f"{consumers.get(tensor_index, [])}"
            )
    private_constants = [
        int(record["constants"][role])
        for record in records
        for role, _suffix, _value in GELU_CONSTANT_SPECS
        if role not in GELU_SHARED_CONSTANT_ROLES
    ]
    if len(set(private_constants)) != len(private_constants):
        raise AttentionPadModelError(
            "Expanded GELU blocks unexpectedly share private polynomial constants"
        )
    return records


def _append_exact_gelu_opcode(model: schema.ModelT) -> int:
    existing = [
        index
        for index, opcode in enumerate(model.operatorCodes)
        if max(int(opcode.builtinCode), int(opcode.deprecatedBuiltinCode))
        == int(schema.BuiltinOperator.GELU)
    ]
    if existing:
        raise AttentionPadModelError(
            f"Refusing GELU fusion because the source already has GELU opcodes: {existing}"
        )
    opcode = schema.OperatorCodeT()
    opcode.builtinCode = schema.BuiltinOperator.GELU
    opcode.deprecatedBuiltinCode = schema.BuiltinOperator.PLACEHOLDER_FOR_GREATER_OP_CODES
    opcode.version = 1
    model.operatorCodes.append(opcode)
    return len(model.operatorCodes) - 1


def _make_exact_gelu_operator(
    gelu_opcode_index: int,
    input_tensor_index: int,
    output_tensor_index: int,
) -> schema.OperatorT:
    options = schema.GeluOptionsT()
    options.approximate = False
    operator = schema.OperatorT()
    operator.opcodeIndex = gelu_opcode_index
    operator.inputs = [input_tensor_index]
    operator.outputs = [output_tensor_index]
    operator.builtinOptionsType = schema.BuiltinOptions.GeluOptions
    operator.builtinOptions = options
    return operator


class GeluFusionResult(NamedTuple):
    records: list[dict[str, Any]]
    operator_index_map: dict[int, int]


def fuse_expanded_gelu_chains(
    model: schema.ModelT,
    *,
    layout: AttentionLayout = DEFAULT_ATTENTION_LAYOUT,
) -> GeluFusionResult:
    """Replace all twelve guarded expanded GELUs, retaining every tensor ID."""
    records = find_expanded_gelu_chains(model, layout=layout)
    gelu_opcode_index = _append_exact_gelu_opcode(model)
    records_by_start = {
        int(record["source_operator_indices"][0]): record for record in records
    }
    removed_indices = {
        index
        for record in records
        for index in record["source_operator_indices"]
    }
    original_operators = list(model.subgraphs[0].operators)
    patched_operators: list[Any] = []
    operator_index_map: dict[int, int] = {}
    for original_index, operator in enumerate(original_operators):
        record = records_by_start.get(original_index)
        if record is not None:
            patched_index = len(patched_operators)
            patched_operators.append(
                _make_exact_gelu_operator(
                    gelu_opcode_index,
                    int(record["input"]),
                    int(record["output"]),
                )
            )
            operator_index_map[original_index] = patched_index
            record["patched_operator"] = patched_index
            continue
        if original_index in removed_indices:
            continue
        operator_index_map[original_index] = len(patched_operators)
        patched_operators.append(operator)
    model.subgraphs[0].operators = patched_operators
    return GeluFusionResult(records, operator_index_map)


def remap_presoftmax_operator_records(
    records: Sequence[dict[str, Any]],
    operator_index_map: dict[int, int],
) -> list[dict[str, Any]]:
    operator_keys = (
        "patched_logits_concat_operator",
        "patched_softmax_operator",
        "patched_value_concat_operator",
        "patched_bmm_operator",
    )
    remapped: list[dict[str, Any]] = []
    for record in records:
        copied = dict(record)
        for key in operator_keys:
            old_index = int(copied[key])
            if old_index not in operator_index_map:
                raise AttentionPadModelError(
                    f"GELU fusion unexpectedly removed presoftmax operator {old_index}"
                )
            copied[key] = operator_index_map[old_index]
        remapped.append(copied)
    return remapped


def validate_fused_gelu_structure(
    model: schema.ModelT,
    records: Sequence[dict[str, Any]],
    *,
    original_tensor_count: int,
    original_operator_count: int,
    original_buffer_count: int,
    original_operator_code_count: int,
    layout: AttentionLayout = DEFAULT_ATTENTION_LAYOUT,
) -> None:
    if len(records) != GELU_CHAIN_COUNT:
        raise AttentionPadModelError(
            f"Expected {GELU_CHAIN_COUNT} GELU fusion records, found {len(records)}"
        )
    if len(model.subgraphs) != 1:
        raise AttentionPadModelError("Fused GELU model no longer has one subgraph")
    subgraph = model.subgraphs[0]
    if len(subgraph.operators) != original_operator_count - GELU_REMOVED_OPERATOR_COUNT:
        raise AttentionPadModelError(
            f"GELU fusion must remove exactly {GELU_REMOVED_OPERATOR_COUNT} operators"
        )
    if len(subgraph.tensors) != original_tensor_count:
        raise AttentionPadModelError("GELU fusion must retain all original tensor IDs")
    if len(model.buffers) != original_buffer_count:
        raise AttentionPadModelError("GELU fusion must retain all original buffers")
    if len(model.operatorCodes) != original_operator_code_count + 1:
        raise AttentionPadModelError("GELU fusion must append exactly one opcode")

    gelu_opcode_indices = [
        index
        for index, opcode in enumerate(model.operatorCodes)
        if max(int(opcode.builtinCode), int(opcode.deprecatedBuiltinCode))
        == int(schema.BuiltinOperator.GELU)
    ]
    if len(gelu_opcode_indices) != 1:
        raise AttentionPadModelError(
            f"Fused model must have exactly one GELU opcode, found {gelu_opcode_indices}"
        )
    gelu_opcode_index = gelu_opcode_indices[0]
    gelu_opcode = model.operatorCodes[gelu_opcode_index]
    if (
        int(gelu_opcode.builtinCode) != int(schema.BuiltinOperator.GELU)
        or int(gelu_opcode.deprecatedBuiltinCode)
        != int(schema.BuiltinOperator.PLACEHOLDER_FOR_GREATER_OP_CODES)
        or int(gelu_opcode.version) != 1
    ):
        raise AttentionPadModelError("Fused GELU opcode contract changed")

    gelu_operator_indices = [
        index
        for index, operator in enumerate(subgraph.operators)
        if int(operator.opcodeIndex) == gelu_opcode_index
    ]
    expected_gelu_indices = [int(record["patched_operator"]) for record in records]
    if gelu_operator_indices != expected_gelu_indices:
        raise AttentionPadModelError(
            f"Fused GELU operator positions changed: {gelu_operator_indices}"
        )
    if _expanded_gelu_type_candidates(model):
        raise AttentionPadModelError("Expanded GELU type sequences remain after fusion")

    producers = _producer_map(subgraph)
    consumers = _consumer_map(subgraph)
    dead_tensors: set[int] = set()
    for expected_block, record in enumerate(records):
        block = int(record["block"])
        if block != expected_block:
            raise AttentionPadModelError("Fused GELU records are not in block order")
        operator_index = int(record["patched_operator"])
        operator = subgraph.operators[operator_index]
        input_tensor = int(record["input"])
        output_tensor = int(record["output"])
        if _ints(operator.inputs) != [input_tensor] or _ints(operator.outputs) != [output_tensor]:
            raise AttentionPadModelError(
                f"Fused GELU block {block} does not preserve input/output tensor IDs"
            )
        if int(operator.builtinOptionsType) != int(schema.BuiltinOptions.GeluOptions):
            raise AttentionPadModelError(f"Fused GELU block {block} has no GeluOptions")
        options = operator.builtinOptions
        if options is None or bool(options.approximate):
            raise AttentionPadModelError(
                f"Fused GELU block {block} is not exact approximate=false"
            )

        activation_shape = [1, layout.token_count, GELU_ACTIVATION_WIDTH]
        _validate_gelu_data_tensor(
            subgraph.tensors[input_tensor],
            expected_name=f"wa/blocks.{block}/mlp/fc1/Add_output_0",
            expected_shape=activation_shape,
        )
        _validate_gelu_data_tensor(
            subgraph.tensors[output_tensor],
            expected_name=f"wa/blocks.{block}/mlp/act/Mul_1_output_0",
            expected_shape=activation_shape,
        )
        if producers.get(output_tensor) != operator_index:
            raise AttentionPadModelError(f"Fused GELU block {block} output producer changed")
        source_producer = producers.get(input_tensor)
        if (
            source_producer is None
            or operator_name(model, subgraph.operators[source_producer]) != "ADD"
        ):
            raise AttentionPadModelError(f"Fused GELU block {block} input producer changed")
        if consumers.get(input_tensor, []) != [(operator_index, 0)]:
            raise AttentionPadModelError(f"Fused GELU block {block} input is not private")

        terminal_consumers = consumers.get(output_tensor, [])
        if len(terminal_consumers) != 1:
            raise AttentionPadModelError(
                f"Fused GELU block {block} output no longer has one fc2 consumer"
            )
        downstream_index, input_position = terminal_consumers[0]
        downstream = subgraph.operators[downstream_index]
        if (
            input_position != 0
            or operator_name(model, downstream) != "BATCH_MATMUL"
            or _ints(downstream.inputs)[0] != output_tensor
            or _ints(downstream.outputs) != [int(record["downstream_output"])]
        ):
            raise AttentionPadModelError(
                f"Fused GELU block {block} downstream fc2 wiring changed"
            )
        dead_tensors.update(int(value) for value in record["intermediate_tensors"])
        dead_tensors.update(int(value) for value in record["constant_tensors"])

    for tensor_index in dead_tensors:
        if tensor_index in producers or consumers.get(tensor_index):
            raise AttentionPadModelError(
                f"Retained GELU tensor {tensor_index} is not dead after fusion"
            )


AttentionProductWiring = tuple[
    tuple[tuple[int, ...], tuple[int, ...]], ...
]


class GeluOnlyResult(NamedTuple):
    records: list[dict[str, Any]]
    attention_wiring: AttentionProductWiring


def _attention_product_wiring(
    model: schema.ModelT,
    operator_indices: Sequence[int],
) -> AttentionProductWiring:
    subgraph = model.subgraphs[0]
    wiring: list[tuple[tuple[int, ...], tuple[int, ...]]] = []
    for operator_index in operator_indices:
        index = int(operator_index)
        if index < 0 or index >= len(subgraph.operators):
            raise AttentionPadModelError(
                f"Attention product index is outside the graph: {index}"
            )
        operator = subgraph.operators[index]
        if operator_name(model, operator) != "BATCH_MATMUL":
            raise AttentionPadModelError(
                f"Attention product {index} is not BATCH_MATMUL"
            )
        wiring.append(
            (tuple(_ints(operator.inputs)), tuple(_ints(operator.outputs)))
        )
    return tuple(wiring)


def validate_gelu_only_structure(
    model: schema.ModelT,
    records: Sequence[dict[str, Any]],
    *,
    original_tensor_count: int,
    original_operator_count: int,
    original_buffer_count: int,
    original_operator_code_count: int,
    source_attention_wiring: AttentionProductWiring,
    layout: AttentionLayout,
) -> None:
    """Validate exact GELU fusion while proving attention stayed untouched."""
    if layout.tail_padding != 0:
        raise AttentionPadModelError(
            "gelu-only mode requires a token count already divisible by four; "
            f"found {layout.token_count} tokens with tail padding "
            f"{layout.tail_padding}"
        )
    if len(source_attention_wiring) != 12:
        raise AttentionPadModelError(
            "gelu-only mode requires exactly 12 source attention products"
        )
    validate_fused_gelu_structure(
        model,
        records,
        original_tensor_count=original_tensor_count,
        original_operator_count=original_operator_count,
        original_buffer_count=original_buffer_count,
        original_operator_code_count=original_operator_code_count,
        layout=layout,
    )
    matches = find_attention_value_products(model, layout=layout)
    if len(matches) != 12:
        raise AttentionPadModelError(
            "gelu-only fusion did not preserve exactly 12 attention products: "
            f"{matches}"
        )
    if _attention_product_wiring(model, matches) != source_attention_wiring:
        raise AttentionPadModelError(
            "gelu-only fusion changed attention tensor wiring"
        )


def fuse_aligned_gelu_only(
    model: schema.ModelT,
    *,
    expected_target_indices: Sequence[int],
    layout: AttentionLayout,
) -> GeluOnlyResult:
    """Fuse GELU only for an aligned graph with the exact 12 known attentions."""
    if layout.tail_padding != 0:
        raise AttentionPadModelError(
            "gelu-only mode requires a token count already divisible by four; "
            f"found {layout.token_count} tokens with tail padding "
            f"{layout.tail_padding}"
        )
    matches = find_attention_value_products(model, layout=layout)
    expected = [int(value) for value in expected_target_indices]
    if matches != expected or len(matches) != 12:
        raise AttentionPadModelError(
            "Refusing GELU-only fusion: expected exactly 12 attention products at "
            f"{expected}, found {matches}"
        )
    source_attention_wiring = _attention_product_wiring(model, matches)
    subgraph = model.subgraphs[0]
    original_tensor_count = len(subgraph.tensors)
    original_operator_count = len(subgraph.operators)
    original_buffer_count = len(model.buffers)
    original_operator_code_count = len(model.operatorCodes)
    fusion = fuse_expanded_gelu_chains(model, layout=layout)
    validate_gelu_only_structure(
        model,
        fusion.records,
        original_tensor_count=original_tensor_count,
        original_operator_count=original_operator_count,
        original_buffer_count=original_buffer_count,
        original_operator_code_count=original_operator_code_count,
        source_attention_wiring=source_attention_wiring,
        layout=layout,
    )
    return GeluOnlyResult(fusion.records, source_attention_wiring)


def validate_serialized_gelu_only_structure(
    data: bytes,
    records: Sequence[dict[str, Any]],
    *,
    original_tensor_count: int,
    original_operator_count: int,
    original_buffer_count: int,
    original_operator_code_count: int,
    source_attention_wiring: AttentionProductWiring,
    layout: AttentionLayout,
    contract: SourceContract = DEFAULT_SOURCE_CONTRACT,
    validate_production_io: bool = True,
) -> schema.ModelT:
    model = parse_model(data)
    validate_gelu_only_structure(
        model,
        records,
        original_tensor_count=original_tensor_count,
        original_operator_count=original_operator_count,
        original_buffer_count=original_buffer_count,
        original_operator_code_count=original_operator_code_count,
        source_attention_wiring=source_attention_wiring,
        layout=layout,
    )
    if validate_production_io:
        _validate_signature_and_io(model, contract)
        subgraph = model.subgraphs[0]
        errors: list[str] = []
        if _text(subgraph.name) != contract.subgraph_name:
            errors.append(f"subgraph name={_text(subgraph.name)!r}")
        expected_operator_count = (
            contract.operator_count - GELU_REMOVED_OPERATOR_COUNT
        )
        if len(subgraph.operators) != expected_operator_count:
            errors.append(f"operator count={len(subgraph.operators)}")
        if len(subgraph.tensors) != contract.tensor_count:
            errors.append(f"tensor count={len(subgraph.tensors)}")
        if len(model.buffers) != contract.buffer_count:
            errors.append(f"buffer count={len(model.buffers)}")
        if len(model.operatorCodes) != contract.operator_code_count + 1:
            errors.append(f"operator-code count={len(model.operatorCodes)}")
        if errors:
            raise AttentionPadModelError(
                "Serialized gelu-only graph contract changed: " + "; ".join(errors)
            )
    return model


def pad_attention_value_products(
    model: schema.ModelT,
    *,
    expected_target_indices: Sequence[int],
    mode: str = MODE_K352,
    layout: AttentionLayout = DEFAULT_ATTENTION_LAYOUT,
) -> list[dict[str, Any]]:
    """Patch all matched products and return deterministic structural records."""
    if mode not in ATTENTION_REWRITE_MODES:
        if mode == MODE_GELU_ONLY:
            raise AttentionPadModelError(
                "gelu-only mode must not rewrite or pad attention products"
            )
        raise AttentionPadModelError(f"Unknown attention rewrite mode {mode!r}")
    if mode != MODE_PRESOFTMAX and layout.token_count != DEFAULT_ATTENTION_LAYOUT.token_count:
        raise AttentionPadModelError(
            "Only presoftmax mode supports non-351-token production buckets"
        )
    if mode == MODE_PRESOFTMAX and layout.tail_padding == 0:
        raise AttentionPadModelError(
            f"Token count {layout.token_count} is already divisible by four"
        )
    subgraph = model.subgraphs[0]
    matches = find_attention_value_products(model, layout=layout)
    expected = [int(value) for value in expected_target_indices]
    if matches != expected or len(matches) != 12:
        raise AttentionPadModelError(
            f"Refusing partial patch: expected exactly 12 targets at {expected}, found {matches}"
    )
    producers = _producer_map(subgraph)
    target_ordinals = {operator_index: ordinal for ordinal, operator_index in enumerate(matches)}
    softmax_to_bmm: dict[int, int] = {}
    if mode == MODE_PRESOFTMAX:
        consumers: dict[int, list[int]] = {}
        for consumer_index, consumer_operator in enumerate(subgraph.operators):
            for tensor_index in _ints(consumer_operator.inputs):
                if tensor_index >= 0:
                    consumers.setdefault(tensor_index, []).append(consumer_index)
        for bmm_index in matches:
            bmm_inputs = _ints(subgraph.operators[bmm_index].inputs)
            softmax_index = producers.get(bmm_inputs[0])
            if softmax_index is None or operator_name(
                model, subgraph.operators[softmax_index]
            ) != "SOFTMAX":
                raise AttentionPadModelError(
                    f"Value BATCH_MATMUL {bmm_index} has no SOFTMAX producer"
                )
            if softmax_index in softmax_to_bmm:
                raise AttentionPadModelError("Two value products share one SOFTMAX")
            if consumers.get(bmm_inputs[0], []) != [bmm_index]:
                raise AttentionPadModelError(
                    f"SOFTMAX output {bmm_inputs[0]} is not private to BATCH_MATMUL "
                    f"{bmm_index}: consumers={consumers.get(bmm_inputs[0], [])}"
                )
            softmax_to_bmm[softmax_index] = bmm_index
        if len(softmax_to_bmm) != 12:
            raise AttentionPadModelError(
                f"Expected 12 distinct SOFTMAX producers, found {len(softmax_to_bmm)}"
            )

    pad_opcode_index = (
        _find_opcode_index(model, schema.BuiltinOperator.PAD)
        if mode in (MODE_K352, MODE_KM352)
        else None
    )
    slice_opcode_index = (
        _find_opcode_index(model, schema.BuiltinOperator.SLICE)
        if mode == MODE_KM352
        else None
    )
    concat_opcode_index = (
        _find_opcode_index(model, schema.BuiltinOperator.CONCATENATION)
        if mode == MODE_PRESOFTMAX
        else None
    )
    reshape_opcode_index = (
        _find_opcode_index(model, schema.BuiltinOperator.RESHAPE)
        if mode == MODE_RANK3
        else None
    )
    shared_logits_constant_index: int | None = None
    shared_value_constant_index: int | None = None
    if mode == MODE_PRESOFTMAX:
        shared_prefix = f"experimental/attention_{mode}/shared"
        shared_logits_constant_index = _make_float_constant(
            model,
            subgraph,
            f"{shared_prefix}/logits_sentinel",
            layout.presoftmax_logit_constant_shape,
            PRESOFTMAX_SENTINEL,
        )
        shared_value_constant_index = _make_float_constant(
            model,
            subgraph,
            f"{shared_prefix}/value_zeros",
            layout.presoftmax_value_constant_shape,
            0.0,
        )
    targets = set(matches)
    original_operators = list(subgraph.operators)
    patched_operators: list[Any] = []
    records: list[dict[str, Any]] = []
    presoftmax_states: dict[int, dict[str, Any]] = {}

    for original_index, operator in enumerate(original_operators):
        if mode == MODE_PRESOFTMAX and original_index in softmax_to_bmm:
            if concat_opcode_index is None:
                raise AttentionPadModelError("CONCATENATION opcode was not resolved")
            bmm_source_index = softmax_to_bmm[original_index]
            block_number = target_ordinals[bmm_source_index]
            prefix = f"experimental/attention_{mode}/block_{block_number:02d}"
            softmax_inputs = _ints(operator.inputs)
            softmax_outputs = _ints(operator.outputs)
            if len(softmax_inputs) != 1 or len(softmax_outputs) != 1:
                raise AttentionPadModelError(
                    f"SOFTMAX {original_index} does not have one input and output"
                )
            if shared_logits_constant_index is None:
                raise AttentionPadModelError("Shared logits constant was not created")
            logits_constant_index = shared_logits_constant_index
            logits_padded_index = _make_padded_tensor(
                subgraph,
                softmax_inputs[0],
                f"{prefix}/logits_concat",
                layout.presoftmax_logits_shape,
            )
            softmax_padded_index = _make_padded_tensor(
                subgraph,
                softmax_outputs[0],
                f"{prefix}/softmax_352",
                layout.presoftmax_logits_shape,
            )
            logits_concat_index = len(patched_operators)
            patched_operators.append(
                _make_concat_operator(
                    concat_opcode_index,
                    [softmax_inputs[0], logits_constant_index],
                    logits_padded_index,
                    axis=3,
                )
            )
            operator.inputs = [logits_padded_index]
            operator.outputs = [softmax_padded_index]
            patched_softmax_index = len(patched_operators)
            patched_operators.append(operator)
            presoftmax_states[bmm_source_index] = {
                "mode": mode,
                "block": block_number,
                "source_softmax_operator": original_index,
                "patched_logits_concat_operator": logits_concat_index,
                "patched_softmax_operator": patched_softmax_index,
                "source_logits": softmax_inputs[0],
                "source_softmax_output": softmax_outputs[0],
                "logits_constant": logits_constant_index,
                "logits_padded": logits_padded_index,
                "softmax_padded": softmax_padded_index,
            }
            continue

        if original_index not in targets:
            patched_operators.append(operator)
            continue

        original_inputs = _ints(operator.inputs)
        output_index = _ints(operator.outputs)[0]
        block_number = len(records)
        prefix = f"experimental/attention_{mode}/block_{block_number:02d}"

        if mode == MODE_PRESOFTMAX:
            if concat_opcode_index is None:
                raise AttentionPadModelError("CONCATENATION opcode was not resolved")
            state = presoftmax_states.pop(original_index, None)
            if state is None or int(state["block"]) != block_number:
                raise AttentionPadModelError(
                    f"Missing ordered SOFTMAX rewrite for BATCH_MATMUL {original_index}"
                )
            if shared_value_constant_index is None:
                raise AttentionPadModelError("Shared value constant was not created")
            value_constant_index = shared_value_constant_index
            value_padded_index = _make_padded_tensor(
                subgraph,
                original_inputs[1],
                f"{prefix}/value_concat",
                layout.presoftmax_value_shape,
            )
            value_concat_index = len(patched_operators)
            patched_operators.append(
                _make_concat_operator(
                    concat_opcode_index,
                    [original_inputs[1], value_constant_index],
                    value_padded_index,
                    axis=2,
                )
            )
            operator.inputs = [int(state["softmax_padded"]), value_padded_index]
            patched_bmm_index = len(patched_operators)
            patched_operators.append(operator)
            state.update(
                {
                    "source_bmm_operator": original_index,
                    "patched_value_concat_operator": value_concat_index,
                    "patched_bmm_operator": patched_bmm_index,
                    "source_inputs": original_inputs,
                    "value_constant": value_constant_index,
                    "value_padded": value_padded_index,
                    "output": output_index,
                }
            )
            records.append(state)
            continue

        if mode == MODE_RANK3:
            if reshape_opcode_index is None:
                raise AttentionPadModelError("RESHAPE opcode was not resolved")
            lhs_shape_index = _make_int32_constant(
                model, subgraph, f"{prefix}/lhs_shape", RANK3_LHS_SHAPE
            )
            lhs_rank3_index = _make_padded_tensor(
                subgraph,
                original_inputs[0],
                f"{prefix}/lhs_rank3",
                RANK3_LHS_SHAPE,
            )
            rhs_shape_index = _make_int32_constant(
                model, subgraph, f"{prefix}/rhs_shape", RANK3_RHS_SHAPE
            )
            rhs_rank3_index = _make_padded_tensor(
                subgraph,
                original_inputs[1],
                f"{prefix}/rhs_rank3",
                RANK3_RHS_SHAPE,
            )
            output_shape_index = _make_int32_constant(
                model, subgraph, f"{prefix}/output_shape", TARGET_OUTPUT_SHAPE
            )
            rank3_output_index = _make_padded_tensor(
                subgraph,
                output_index,
                f"{prefix}/bmm_rank3_output",
                RANK3_OUTPUT_SHAPE,
            )
            lhs_reshape = _make_reshape_operator(
                reshape_opcode_index,
                original_inputs[0],
                lhs_shape_index,
                lhs_rank3_index,
                RANK3_LHS_SHAPE,
            )
            rhs_reshape = _make_reshape_operator(
                reshape_opcode_index,
                original_inputs[1],
                rhs_shape_index,
                rhs_rank3_index,
                RANK3_RHS_SHAPE,
            )
            patched_operators.extend((lhs_reshape, rhs_reshape))
            operator.inputs = [lhs_rank3_index, rhs_rank3_index]
            operator.outputs = [rank3_output_index]
            patched_bmm_index = len(patched_operators)
            patched_operators.append(operator)
            output_reshape_index = len(patched_operators)
            patched_operators.append(
                _make_reshape_operator(
                    reshape_opcode_index,
                    rank3_output_index,
                    output_shape_index,
                    output_index,
                    TARGET_OUTPUT_SHAPE,
                )
            )
            records.append(
                {
                    "mode": mode,
                    "block": block_number,
                    "source_bmm_operator": original_index,
                    "patched_bmm_operator": patched_bmm_index,
                    "source_inputs": original_inputs,
                    "reshape_shape_tensors": [
                        lhs_shape_index,
                        rhs_shape_index,
                        output_shape_index,
                    ],
                    "rank3_inputs": [lhs_rank3_index, rhs_rank3_index],
                    "rank3_output": rank3_output_index,
                    "output_reshape_operator": output_reshape_index,
                    "output": output_index,
                }
            )
            continue

        lhs_paddings = KM_LHS_PADDINGS if mode == MODE_KM352 else LHS_PADDINGS
        lhs_shape = KM_PADDED_LHS_SHAPE if mode == MODE_KM352 else PADDED_LHS_SHAPE

        lhs_padding_index = _make_padding_constant(
            model, subgraph, f"{prefix}/lhs_paddings", lhs_paddings
        )
        lhs_padded_index = _make_padded_tensor(
            subgraph,
            original_inputs[0],
            f"{prefix}/lhs_padded",
            lhs_shape,
        )
        rhs_padding_index = _make_padding_constant(
            model, subgraph, f"{prefix}/rhs_paddings", RHS_PADDINGS
        )
        rhs_padded_index = _make_padded_tensor(
            subgraph,
            original_inputs[1],
            f"{prefix}/rhs_padded",
            PADDED_RHS_SHAPE,
        )

        if pad_opcode_index is None:
            raise AttentionPadModelError("PAD opcode was not resolved")
        lhs_pad_operator = _make_pad_operator(
            pad_opcode_index,
            original_inputs[0],
            lhs_padding_index,
            lhs_padded_index,
        )
        rhs_pad_operator = _make_pad_operator(
            pad_opcode_index,
            original_inputs[1],
            rhs_padding_index,
            rhs_padded_index,
        )
        patched_operators.extend((lhs_pad_operator, rhs_pad_operator))
        operator.inputs = [lhs_padded_index, rhs_padded_index]
        patched_bmm_index = len(patched_operators)
        record: dict[str, Any] = {
            "mode": mode,
            "block": block_number,
            "source_bmm_operator": original_index,
            "patched_bmm_operator": patched_bmm_index,
            "source_inputs": original_inputs,
            "padding_tensors": [lhs_padding_index, rhs_padding_index],
            "padded_inputs": [lhs_padded_index, rhs_padded_index],
            "output": output_index,
        }

        if mode == MODE_KM352:
            padded_output_index = _make_padded_tensor(
                subgraph,
                output_index,
                f"{prefix}/bmm_padded_output",
                KM_PADDED_OUTPUT_SHAPE,
            )
            slice_begin_index = _make_int32_constant(
                model,
                subgraph,
                f"{prefix}/slice_begin",
                OUTPUT_SLICE_BEGIN,
            )
            slice_size_index = _make_int32_constant(
                model,
                subgraph,
                f"{prefix}/slice_size",
                OUTPUT_SLICE_SIZE,
            )
            operator.outputs = [padded_output_index]
            patched_operators.append(operator)
            if slice_opcode_index is None:  # Defensive; the mode branch resolved it above.
                raise AttentionPadModelError("SLICE opcode was not resolved")
            slice_operator = _make_slice_operator(
                slice_opcode_index,
                padded_output_index,
                slice_begin_index,
                slice_size_index,
                output_index,
            )
            patched_slice_index = len(patched_operators)
            patched_operators.append(slice_operator)
            record.update(
                {
                    "padded_output": padded_output_index,
                    "slice_operator": patched_slice_index,
                    "slice_tensors": [slice_begin_index, slice_size_index],
                }
            )
        else:
            patched_operators.append(operator)

        records.append(record)

    subgraph.operators = patched_operators
    if presoftmax_states:
        raise AttentionPadModelError(
            f"Unconsumed SOFTMAX rewrite states: {sorted(presoftmax_states)}"
        )
    return records


def _decode_padding_buffer(model: schema.ModelT, tensor: schema.TensorT) -> list[int]:
    if _ints(tensor.shape) != [4, 2] or int(tensor.type) != int(schema.TensorType.INT32):
        raise AttentionPadModelError("PAD paddings tensor is not INT32 [4,2]")
    data = model.buffers[int(tensor.buffer)].data
    if data is None or len(data) != 32:
        raise AttentionPadModelError("PAD paddings tensor does not contain eight int32 values")
    return list(struct.unpack("<8i", bytes(data)))


def _decode_int32_vector(
    model: schema.ModelT,
    tensor: schema.TensorT,
    expected_length: int,
) -> list[int]:
    if (
        _ints(tensor.shape) != [expected_length]
        or int(tensor.type) != int(schema.TensorType.INT32)
    ):
        raise AttentionPadModelError(
            f"Constant is not an INT32 [{expected_length}] vector"
        )
    data = model.buffers[int(tensor.buffer)].data
    expected_bytes = expected_length * 4
    if data is None or len(data) != expected_bytes:
        raise AttentionPadModelError(
            f"INT32 vector does not contain {expected_length} values"
        )
    return list(struct.unpack(f"<{expected_length}i", bytes(data)))


def _reshape_options_shape(operator: schema.OperatorT) -> list[int]:
    if int(operator.builtinOptionsType) != int(schema.BuiltinOptions.ReshapeOptions):
        raise AttentionPadModelError("RESHAPE does not carry ReshapeOptions")
    options = operator.builtinOptions
    if options is None:
        raise AttentionPadModelError("RESHAPE options are missing")
    return _ints(options.newShape)


def _concat_axis(operator: schema.OperatorT) -> int:
    if int(operator.builtinOptionsType) != int(
        schema.BuiltinOptions.ConcatenationOptions
    ):
        raise AttentionPadModelError("CONCATENATION options are missing")
    options = operator.builtinOptions
    if options is None:
        raise AttentionPadModelError("CONCATENATION options are missing")
    if int(options.fusedActivationFunction) != int(schema.ActivationFunctionType.NONE):
        raise AttentionPadModelError("CONCATENATION unexpectedly has a fused activation")
    return int(options.axis)


def _validate_float_constant(
    model: schema.ModelT,
    tensor: schema.TensorT,
    expected_shape: Sequence[int],
    expected_value: float,
) -> None:
    fixed_shape = [int(value) for value in expected_shape]
    if (
        _ints(tensor.shape) != fixed_shape
        or int(tensor.type) != int(schema.TensorType.FLOAT32)
    ):
        raise AttentionPadModelError(
            f"Float constant does not have expected shape {fixed_shape}"
        )
    element_count = 1
    for dimension in fixed_shape:
        element_count *= dimension
    data = model.buffers[int(tensor.buffer)].data
    expected_data = struct.pack("<f", float(expected_value)) * element_count
    if data is None or bytes(data) != expected_data:
        raise AttentionPadModelError(
            f"Float constant is not uniformly {expected_value}"
        )


def validate_padded_structure(
    model: schema.ModelT,
    records: Sequence[dict[str, Any]],
    *,
    original_tensor_count: int,
    original_operator_count: int,
    original_buffer_count: int,
    mode: str = MODE_K352,
    layout: AttentionLayout = DEFAULT_ATTENTION_LAYOUT,
    removed_operator_count: int = 0,
) -> None:
    if mode not in MODES:
        raise AttentionPadModelError(f"Unknown attention rewrite mode {mode!r}")
    if len(records) != 12:
        raise AttentionPadModelError(f"Expected 12 patch records, found {len(records)}")
    if len(model.subgraphs) != 1:
        raise AttentionPadModelError("Patched model no longer has exactly one subgraph")
    subgraph = model.subgraphs[0]
    added_operators = 36 if mode in (MODE_KM352, MODE_RANK3) else 24
    if mode == MODE_KM352:
        added_tensors = 84
        added_buffers = 48
    elif mode == MODE_RANK3:
        added_tensors = 72
        added_buffers = 36
    elif mode == MODE_PRESOFTMAX:
        # Two immutable constants are shared by all twelve attention blocks;
        # each block retains three private concatenation/SOFTMAX output tensors.
        added_tensors = 38
        added_buffers = 2
    else:
        added_tensors = 48
        added_buffers = 24
    expected_operator_count = (
        original_operator_count + added_operators - int(removed_operator_count)
    )
    if len(subgraph.operators) != expected_operator_count:
        if mode == MODE_KM352:
            detail = "24 PAD plus 12 SLICE"
        elif mode == MODE_RANK3:
            detail = "36 RESHAPE"
        elif mode == MODE_PRESOFTMAX:
            detail = "24 CONCATENATION"
        else:
            detail = "24 PAD"
        raise AttentionPadModelError(
            f"Patched graph must add exactly {detail} operators and remove exactly "
            f"{removed_operator_count} fused operators"
        )
    if len(subgraph.tensors) != original_tensor_count + added_tensors:
        raise AttentionPadModelError(
            f"Patched {mode} graph must add exactly {added_tensors} tensors"
        )
    if len(model.buffers) != original_buffer_count + added_buffers:
        raise AttentionPadModelError(
            f"Patched {mode} graph must add exactly {added_buffers} constant buffers"
        )
    if mode == MODE_PRESOFTMAX:
        logits_constants = {int(record["logits_constant"]) for record in records}
        value_constants = {int(record["value_constant"]) for record in records}
        if len(logits_constants) != 1 or len(value_constants) != 1:
            raise AttentionPadModelError(
                "presoftmax blocks must share one logits and one value constant"
            )
        if logits_constants == value_constants:
            raise AttentionPadModelError(
                "presoftmax logits and value constants must be distinct tensors"
            )

    pad_opcode_index = (
        _find_opcode_index(model, schema.BuiltinOperator.PAD)
        if mode in (MODE_K352, MODE_KM352)
        else None
    )
    slice_opcode_index = (
        _find_opcode_index(model, schema.BuiltinOperator.SLICE)
        if mode == MODE_KM352
        else None
    )
    reshape_opcode_index = (
        _find_opcode_index(model, schema.BuiltinOperator.RESHAPE)
        if mode == MODE_RANK3
        else None
    )
    concat_opcode_index = (
        _find_opcode_index(model, schema.BuiltinOperator.CONCATENATION)
        if mode == MODE_PRESOFTMAX
        else None
    )
    expected_lhs_shape = KM_PADDED_LHS_SHAPE if mode == MODE_KM352 else PADDED_LHS_SHAPE
    expected_lhs_paddings = KM_LHS_PADDINGS if mode == MODE_KM352 else LHS_PADDINGS
    for expected_block, record in enumerate(records):
        if record.get("mode") != mode:
            raise AttentionPadModelError("Patch record mode does not match validation mode")
        if int(record["block"]) != expected_block:
            raise AttentionPadModelError("Patch records are not in block order")
        bmm_index = int(record["patched_bmm_operator"])
        if bmm_index < 2:
            raise AttentionPadModelError("Patched BATCH_MATMUL has no preceding rewrite pair")
        bmm = subgraph.operators[bmm_index]
        if operator_name(model, bmm) != "BATCH_MATMUL" or not _batch_matmul_is_untransposed(bmm):
            raise AttentionPadModelError("Patched target is not an untransposed BATCH_MATMUL")

        source_inputs = [int(value) for value in record["source_inputs"]]
        if mode == MODE_RANK3:
            if reshape_opcode_index is None:
                raise AttentionPadModelError("RESHAPE opcode was not resolved")
            lhs_reshape = subgraph.operators[bmm_index - 2]
            rhs_reshape = subgraph.operators[bmm_index - 1]
            output_reshape_index = int(record["output_reshape_operator"])
            if output_reshape_index != bmm_index + 1:
                raise AttentionPadModelError(
                    "Output RESHAPE must immediately follow its BATCH_MATMUL"
                )
            output_reshape = subgraph.operators[output_reshape_index]
            if any(
                int(operator.opcodeIndex) != reshape_opcode_index
                for operator in (lhs_reshape, rhs_reshape, output_reshape)
            ):
                raise AttentionPadModelError(
                    "Each rank3 BATCH_MATMUL must be surrounded by three RESHAPEs"
                )
            rank3_inputs = [int(value) for value in record["rank3_inputs"]]
            shape_tensors = [
                int(value) for value in record["reshape_shape_tensors"]
            ]
            rank3_output = int(record["rank3_output"])
            original_output = int(record["output"])
            if _ints(lhs_reshape.inputs) != [source_inputs[0], shape_tensors[0]]:
                raise AttentionPadModelError("Left rank3 RESHAPE inputs changed")
            if _ints(rhs_reshape.inputs) != [source_inputs[1], shape_tensors[1]]:
                raise AttentionPadModelError("Right rank3 RESHAPE inputs changed")
            if _ints(lhs_reshape.outputs) != [rank3_inputs[0]]:
                raise AttentionPadModelError("Left rank3 RESHAPE output changed")
            if _ints(rhs_reshape.outputs) != [rank3_inputs[1]]:
                raise AttentionPadModelError("Right rank3 RESHAPE output changed")
            if _ints(bmm.inputs) != rank3_inputs or _ints(bmm.outputs) != [rank3_output]:
                raise AttentionPadModelError("Rank3 BATCH_MATMUL wiring changed")
            if _ints(output_reshape.inputs) != [rank3_output, shape_tensors[2]]:
                raise AttentionPadModelError("Output rank4 RESHAPE inputs changed")
            if _ints(output_reshape.outputs) != [original_output]:
                raise AttentionPadModelError(
                    "Output RESHAPE does not restore the original tensor"
                )
            expected_shapes = (
                RANK3_LHS_SHAPE,
                RANK3_RHS_SHAPE,
                TARGET_OUTPUT_SHAPE,
            )
            reshape_operators = (lhs_reshape, rhs_reshape, output_reshape)
            for shape_tensor, reshape_operator, expected_shape in zip(
                shape_tensors, reshape_operators, expected_shapes
            ):
                if _decode_int32_vector(
                    model,
                    subgraph.tensors[shape_tensor],
                    len(expected_shape),
                ) != expected_shape:
                    raise AttentionPadModelError("RESHAPE shape constant changed")
                if _reshape_options_shape(reshape_operator) != expected_shape:
                    raise AttentionPadModelError("RESHAPE options changed")
            if _tensor_shape(subgraph, rank3_inputs[0]) != RANK3_LHS_SHAPE:
                raise AttentionPadModelError("Left rank3 tensor shape changed")
            if _tensor_shape(subgraph, rank3_inputs[1]) != RANK3_RHS_SHAPE:
                raise AttentionPadModelError("Right rank3 tensor shape changed")
            if _tensor_shape(subgraph, rank3_output) != RANK3_OUTPUT_SHAPE:
                raise AttentionPadModelError("Rank3 BATCH_MATMUL output shape changed")
            if _tensor_shape(subgraph, original_output) != TARGET_OUTPUT_SHAPE:
                raise AttentionPadModelError("Restored rank4 output shape changed")
            forbidden = (
                "padding_tensors",
                "padded_inputs",
                "padded_output",
                "slice_operator",
                "slice_tensors",
            )
            if any(key in record for key in forbidden):
                raise AttentionPadModelError("rank3 record contains padding state")
            continue

        if mode == MODE_PRESOFTMAX:
            if concat_opcode_index is None:
                raise AttentionPadModelError("CONCATENATION opcode was not resolved")
            logits_concat_index = int(record["patched_logits_concat_operator"])
            softmax_index = int(record["patched_softmax_operator"])
            value_concat_index = int(record["patched_value_concat_operator"])
            if logits_concat_index != softmax_index - 1:
                raise AttentionPadModelError(
                    "Logits CONCATENATION must immediately precede SOFTMAX"
                )
            if value_concat_index != bmm_index - 1:
                raise AttentionPadModelError(
                    "Value CONCATENATION must immediately precede BATCH_MATMUL"
                )
            logits_concat = subgraph.operators[logits_concat_index]
            softmax = subgraph.operators[softmax_index]
            value_concat = subgraph.operators[value_concat_index]
            if (
                int(logits_concat.opcodeIndex) != concat_opcode_index
                or int(value_concat.opcodeIndex) != concat_opcode_index
            ):
                raise AttentionPadModelError(
                    "presoftmax rewrite does not use two CONCATENATION operators"
                )
            if operator_name(model, softmax) != "SOFTMAX":
                raise AttentionPadModelError("presoftmax rewrite lost SOFTMAX")
            softmax_options = softmax.builtinOptions
            if softmax_options is None or float(softmax_options.beta) != 1.0:
                raise AttentionPadModelError("SOFTMAX beta changed")

            source_logits = int(record["source_logits"])
            source_softmax_output = int(record["source_softmax_output"])
            logits_constant = int(record["logits_constant"])
            logits_padded = int(record["logits_padded"])
            softmax_padded = int(record["softmax_padded"])
            value_constant = int(record["value_constant"])
            value_padded = int(record["value_padded"])
            original_output = int(record["output"])
            if source_inputs[0] != source_softmax_output:
                raise AttentionPadModelError("Recorded SOFTMAX output is not the BMM lhs")
            if _ints(logits_concat.inputs) != [source_logits, logits_constant]:
                raise AttentionPadModelError("Logits CONCATENATION inputs changed")
            if _ints(logits_concat.outputs) != [logits_padded]:
                raise AttentionPadModelError("Logits CONCATENATION output changed")
            if _concat_axis(logits_concat) != 3:
                raise AttentionPadModelError("Logits CONCATENATION axis is not 3")
            if _ints(softmax.inputs) != [logits_padded]:
                raise AttentionPadModelError("SOFTMAX does not consume concatenated logits")
            if _ints(softmax.outputs) != [softmax_padded]:
                raise AttentionPadModelError("SOFTMAX does not produce the 352-lane tensor")
            if _ints(value_concat.inputs) != [source_inputs[1], value_constant]:
                raise AttentionPadModelError("Value CONCATENATION inputs changed")
            if _ints(value_concat.outputs) != [value_padded]:
                raise AttentionPadModelError("Value CONCATENATION output changed")
            if _concat_axis(value_concat) != 2:
                raise AttentionPadModelError("Value CONCATENATION axis is not 2")
            if _ints(bmm.inputs) != [softmax_padded, value_padded]:
                raise AttentionPadModelError("Value BATCH_MATMUL inputs changed")
            if _ints(bmm.outputs) != [original_output]:
                raise AttentionPadModelError(
                    "Value BATCH_MATMUL does not preserve its original output tensor"
                )
            expected_tensor_shapes = (
                (source_logits, layout.target_lhs_shape),
                (logits_padded, layout.presoftmax_logits_shape),
                (softmax_padded, layout.presoftmax_logits_shape),
                (source_inputs[1], layout.target_rhs_shape),
                (value_padded, layout.presoftmax_value_shape),
                (original_output, layout.target_output_shape),
            )
            for tensor_index, expected_shape in expected_tensor_shapes:
                if _tensor_shape(subgraph, tensor_index) != expected_shape:
                    raise AttentionPadModelError(
                        f"presoftmax tensor {tensor_index} shape changed"
                    )
            _validate_float_constant(
                model,
                subgraph.tensors[logits_constant],
                layout.presoftmax_logit_constant_shape,
                PRESOFTMAX_SENTINEL,
            )
            _validate_float_constant(
                model,
                subgraph.tensors[value_constant],
                layout.presoftmax_value_constant_shape,
                0.0,
            )
            if any(
                source_softmax_output in _ints(candidate.inputs)
                for candidate in subgraph.operators
            ):
                raise AttentionPadModelError(
                    f"Original {layout.token_count}-lane SOFTMAX tensor still has a consumer"
                )
            forbidden = (
                "padding_tensors",
                "padded_inputs",
                "padded_output",
                "slice_operator",
                "slice_tensors",
                "rank3_inputs",
                "rank3_output",
            )
            if any(key in record for key in forbidden):
                raise AttentionPadModelError("presoftmax record contains another mode's state")
            continue

        if pad_opcode_index is None:
            raise AttentionPadModelError("PAD opcode was not resolved")
        lhs_pad = subgraph.operators[bmm_index - 2]
        rhs_pad = subgraph.operators[bmm_index - 1]
        if int(lhs_pad.opcodeIndex) != pad_opcode_index or int(rhs_pad.opcodeIndex) != pad_opcode_index:
            raise AttentionPadModelError("Each patched BATCH_MATMUL must immediately follow two PADs")
        padded_inputs = [int(value) for value in record["padded_inputs"]]
        padding_tensors = [int(value) for value in record["padding_tensors"]]
        if _ints(lhs_pad.inputs) != [source_inputs[0], padding_tensors[0]]:
            raise AttentionPadModelError("Left PAD inputs changed")
        if _ints(rhs_pad.inputs) != [source_inputs[1], padding_tensors[1]]:
            raise AttentionPadModelError("Right PAD inputs changed")
        if _ints(lhs_pad.outputs) != [padded_inputs[0]] or _ints(rhs_pad.outputs) != [padded_inputs[1]]:
            raise AttentionPadModelError("PAD outputs changed")
        if _ints(bmm.inputs) != padded_inputs:
            raise AttentionPadModelError("BATCH_MATMUL does not consume both padded tensors")
        if _tensor_shape(subgraph, padded_inputs[0]) != expected_lhs_shape:
            raise AttentionPadModelError(
                f"Left padded tensor shape is not {expected_lhs_shape}"
            )
        if _tensor_shape(subgraph, padded_inputs[1]) != PADDED_RHS_SHAPE:
            raise AttentionPadModelError("Right padded tensor shape is not [1,6,352,64]")
        expected_bmm_output_shape = (
            KM_PADDED_OUTPUT_SHAPE if mode == MODE_KM352 else TARGET_OUTPUT_SHAPE
        )
        if _tensor_shape(subgraph, _ints(bmm.outputs)[0]) != expected_bmm_output_shape:
            raise AttentionPadModelError(
                f"BATCH_MATMUL output shape is not {expected_bmm_output_shape}"
            )
        if _decode_padding_buffer(model, subgraph.tensors[padding_tensors[0]]) != _flatten_paddings(expected_lhs_paddings):
            raise AttentionPadModelError("Left PAD constant changed")
        if _decode_padding_buffer(model, subgraph.tensors[padding_tensors[1]]) != _flatten_paddings(RHS_PADDINGS):
            raise AttentionPadModelError("Right PAD constant changed")

        if mode == MODE_KM352:
            if slice_opcode_index is None:
                raise AttentionPadModelError("SLICE opcode was not resolved")
            padded_output = int(record["padded_output"])
            slice_index = int(record["slice_operator"])
            slice_tensors = [int(value) for value in record["slice_tensors"]]
            if _ints(bmm.outputs) != [padded_output]:
                raise AttentionPadModelError("BATCH_MATMUL does not produce the padded output")
            if slice_index != bmm_index + 1:
                raise AttentionPadModelError("SLICE must immediately follow its BATCH_MATMUL")
            slice_operator = subgraph.operators[slice_index]
            if int(slice_operator.opcodeIndex) != slice_opcode_index:
                raise AttentionPadModelError("Patched output is not consumed by SLICE")
            if _ints(slice_operator.inputs) != [padded_output, *slice_tensors]:
                raise AttentionPadModelError("SLICE inputs changed")
            if _ints(slice_operator.outputs) != [int(record["output"])]:
                raise AttentionPadModelError("SLICE does not restore the original output tensor")
            if _tensor_shape(subgraph, padded_output) != KM_PADDED_OUTPUT_SHAPE:
                raise AttentionPadModelError("Padded BATCH_MATMUL output shape changed")
            if _tensor_shape(subgraph, int(record["output"])) != TARGET_OUTPUT_SHAPE:
                raise AttentionPadModelError("Sliced output shape changed")
            if _decode_int32_vector(model, subgraph.tensors[slice_tensors[0]], 4) != OUTPUT_SLICE_BEGIN:
                raise AttentionPadModelError("SLICE begin constant changed")
            if _decode_int32_vector(model, subgraph.tensors[slice_tensors[1]], 4) != OUTPUT_SLICE_SIZE:
                raise AttentionPadModelError("SLICE size constant changed")
        elif any(key in record for key in ("padded_output", "slice_operator", "slice_tensors")):
            raise AttentionPadModelError("k352 record unexpectedly contains query slicing")


def validate_serialized_structure(
    data: bytes,
    records: Sequence[dict[str, Any]],
    *,
    original_tensor_count: int,
    original_operator_count: int,
    original_buffer_count: int,
    validate_production_io: bool,
    mode: str = MODE_K352,
    layout: AttentionLayout = DEFAULT_ATTENTION_LAYOUT,
    contract: SourceContract = DEFAULT_SOURCE_CONTRACT,
    gelu_records: Sequence[dict[str, Any]] | None = None,
    original_operator_code_count: int | None = None,
) -> schema.ModelT:
    model = parse_model(data)
    removed_operator_count = (
        GELU_REMOVED_OPERATOR_COUNT if gelu_records is not None else 0
    )
    validate_padded_structure(
        model,
        records,
        original_tensor_count=original_tensor_count,
        original_operator_count=original_operator_count,
        original_buffer_count=original_buffer_count,
        mode=mode,
        layout=layout,
        removed_operator_count=removed_operator_count,
    )
    if gelu_records is not None:
        if mode != MODE_PRESOFTMAX:
            raise AttentionPadModelError(
                "Exact GELU fusion is only valid after the presoftmax rewrite"
            )
        if original_operator_code_count is None:
            raise AttentionPadModelError(
                "Serialized GELU validation requires the source opcode count"
            )
        validate_fused_gelu_structure(
            model,
            gelu_records,
            original_tensor_count=original_tensor_count + 38,
            original_operator_count=original_operator_count + 24,
            original_buffer_count=original_buffer_count + 2,
            original_operator_code_count=original_operator_code_count,
            layout=layout,
        )
    if validate_production_io:
        _validate_signature_and_io(model, contract)
    return model


def _load_interpreter_class() -> Any:
    try:
        from ai_edge_litert.interpreter import Interpreter
        return Interpreter
    except ImportError:
        try:
            import tensorflow as tf
            return tf.lite.Interpreter
        except ImportError as exc:  # pragma: no cover - environment setup failure
            raise AttentionPadModelError(
                "Interpreter validation requires ai-edge-litert or tensorflow-cpu"
            ) from exc


def validate_interpreter_allocation(
    path: Path,
    contract: SourceContract = DEFAULT_SOURCE_CONTRACT,
) -> None:
    interpreter_class = _load_interpreter_class()
    try:
        interpreter = interpreter_class(model_path=str(path), num_threads=1)
        interpreter.allocate_tensors()
        inputs = interpreter.get_input_details()
        outputs = interpreter.get_output_details()
        if len(inputs) != 1 or _ints(inputs[0]["shape"]) != list(contract.input_shape):
            raise AttentionPadModelError(f"Interpreter input contract changed: {inputs}")
        if len(outputs) != 1 or _ints(outputs[0]["shape"]) != list(contract.output_shape):
            raise AttentionPadModelError(f"Interpreter output contract changed: {outputs}")
        signature = interpreter.get_signature_list().get(EXPECTED_SIGNATURE_KEY)
        if signature is None or signature.get("outputs") != [EXPECTED_SIGNATURE_OUTPUT_NAME]:
            raise AttentionPadModelError(f"Interpreter signature contract changed: {signature}")
    except AttentionPadModelError:
        raise
    except Exception as exc:
        raise AttentionPadModelError(f"LiteRT interpreter rejected the patched model: {exc}") from exc
    finally:
        if "interpreter" in locals():
            del interpreter


def _same_path(left: Path, right: Path) -> bool:
    return os.path.normcase(str(left.resolve())) == os.path.normcase(str(right.resolve()))


def _is_relative_to(path: Path, parent: Path) -> bool:
    try:
        path.resolve().relative_to(parent.resolve())
        return True
    except ValueError:
        return False


def _is_android_source_asset_path(path: Path) -> bool:
    try:
        relative = path.resolve().relative_to(REPOSITORY_ROOT.resolve())
    except ValueError:
        return False
    parts = [part.casefold() for part in relative.parts]
    return len(parts) >= 4 and parts[0] == "app" and parts[1] == "src" and "assets" in parts[2:]


def validate_output_path(source: Path, output: Path) -> Path:
    resolved_source = source.resolve()
    resolved_output = output.resolve()
    if _same_path(resolved_source, resolved_output):
        raise AttentionPadModelError("Output must not overwrite the verified source model")
    if any(part.casefold() == "apollo-3d" for part in resolved_output.parts):
        raise AttentionPadModelError(
            f"Refusing to write a client model inside Apollo-3D: {resolved_output}"
        )
    if _is_android_source_asset_path(resolved_output):
        raise AttentionPadModelError(
            "Refusing to write an experimental model into Android source assets; "
            "use this repository's build/ or temp/ directory"
        )
    allowed_roots = (REPOSITORY_ROOT / "build", REPOSITORY_ROOT / "temp")
    if not any(_is_relative_to(resolved_output, root) for root in allowed_roots):
        raise AttentionPadModelError(
            "Experimental output must stay under the moonlight-android build/ or temp/ tree"
        )
    if resolved_output.exists():
        raise AttentionPadModelError(f"Refusing to overwrite existing output: {resolved_output}")
    return resolved_output


def _publish_without_overwrite(temporary_path: Path, output: Path) -> None:
    try:
        os.link(temporary_path, output)
    except FileExistsError as exc:
        raise AttentionPadModelError(f"Refusing to overwrite existing output: {output}") from exc
    except OSError as exc:
        raise AttentionPadModelError(
            f"Unable to publish validated output without overwrite via hard link: {exc}"
        ) from exc


def write_experimental_model(
    source: Path,
    output: Path,
    model: schema.ModelT,
    *,
    mode: str,
    contract: SourceContract = DEFAULT_SOURCE_CONTRACT,
    fuse_gelu: bool = False,
) -> None:
    if mode == MODE_GELU_ONLY and fuse_gelu:
        raise AttentionPadModelError(
            "--mode gelu-only performs its guarded GELU fusion directly; "
            "do not also pass --fuse-gelu"
        )
    if fuse_gelu and mode != MODE_PRESOFTMAX:
        raise AttentionPadModelError(
            "--fuse-gelu is guarded to run only after --mode presoftmax"
        )
    resolved_output = validate_output_path(source, output)
    layout = attention_layout(contract.token_count)
    subgraph = model.subgraphs[0]
    original_tensor_count = len(subgraph.tensors)
    original_operator_count = len(subgraph.operators)
    original_buffer_count = len(model.buffers)
    original_operator_code_count = len(model.operatorCodes)
    if mode == MODE_GELU_ONLY:
        gelu_only = fuse_aligned_gelu_only(
            model,
            expected_target_indices=contract.target_operator_indices,
            layout=layout,
        )
        for record in gelu_only.records:
            print("gelu_fusion=" + json.dumps(record, sort_keys=True), flush=True)
        serialized = serialize_model(model)
        validate_serialized_gelu_only_structure(
            serialized,
            gelu_only.records,
            original_tensor_count=original_tensor_count,
            original_operator_count=original_operator_count,
            original_buffer_count=original_buffer_count,
            original_operator_code_count=original_operator_code_count,
            source_attention_wiring=gelu_only.attention_wiring,
            layout=layout,
            contract=contract,
        )
    else:
        records = pad_attention_value_products(
            model,
            expected_target_indices=contract.target_operator_indices,
            mode=mode,
            layout=layout,
        )
        validate_padded_structure(
            model,
            records,
            original_tensor_count=original_tensor_count,
            original_operator_count=original_operator_count,
            original_buffer_count=original_buffer_count,
            mode=mode,
            layout=layout,
        )
        for record in records:
            print("attention_pad=" + json.dumps(record, sort_keys=True), flush=True)

        gelu_records: list[dict[str, Any]] | None = None
        if fuse_gelu:
            pre_fusion_tensor_count = len(subgraph.tensors)
            pre_fusion_operator_count = len(subgraph.operators)
            pre_fusion_buffer_count = len(model.buffers)
            fusion = fuse_expanded_gelu_chains(model, layout=layout)
            gelu_records = fusion.records
            records = remap_presoftmax_operator_records(
                records, fusion.operator_index_map
            )
            validate_fused_gelu_structure(
                model,
                gelu_records,
                original_tensor_count=pre_fusion_tensor_count,
                original_operator_count=pre_fusion_operator_count,
                original_buffer_count=pre_fusion_buffer_count,
                original_operator_code_count=original_operator_code_count,
                layout=layout,
            )
            validate_padded_structure(
                model,
                records,
                original_tensor_count=original_tensor_count,
                original_operator_count=original_operator_count,
                original_buffer_count=original_buffer_count,
                mode=mode,
                layout=layout,
                removed_operator_count=GELU_REMOVED_OPERATOR_COUNT,
            )
            for record in gelu_records:
                print("gelu_fusion=" + json.dumps(record, sort_keys=True), flush=True)

        serialized = serialize_model(model)
        validate_serialized_structure(
            serialized,
            records,
            original_tensor_count=original_tensor_count,
            original_operator_count=original_operator_count,
            original_buffer_count=original_buffer_count,
            validate_production_io=True,
            mode=mode,
            layout=layout,
            contract=contract,
            gelu_records=gelu_records,
            original_operator_code_count=original_operator_code_count,
        )

    resolved_output.parent.mkdir(parents=True, exist_ok=True)
    temporary_path: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="wb",
            prefix=f".{resolved_output.name}.",
            suffix=".tmp",
            dir=resolved_output.parent,
            delete=False,
        ) as stream:
            stream.write(serialized)
            stream.flush()
            os.fsync(stream.fileno())
            temporary_path = Path(stream.name)
        validate_interpreter_allocation(temporary_path, contract)
        _publish_without_overwrite(temporary_path, resolved_output)
    finally:
        if temporary_path is not None:
            temporary_path.unlink(missing_ok=True)

    print(
        f"validated mode={mode} fuse_gelu={str(fuse_gelu).lower()} "
        f"tokens={layout.token_count}->{layout.aligned_token_count} "
        f"tail_padding={layout.tail_padding} output={resolved_output} "
        f"size={resolved_output.stat().st_size} "
        f"sha256={sha256_file(resolved_output)} operators={len(subgraph.operators)} "
        f"tensors={len(subgraph.tensors)} buffers={len(model.buffers)}",
        flush=True,
    )


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=DEFAULT_SOURCE)
    parser.add_argument(
        "--mode",
        choices=MODES,
        default=MODE_K352,
        help=(
            "k352 pads reduction K; km352 also pads query M and slices it back; "
            "rank3 wraps each value BMM in rank-changing reshapes; presoftmax "
            "concatenates sentinel logits and zero V rows before the value BMM; "
            "gelu-only preserves already aligned attention and fuses the 12 "
            "expanded GELU DAGs"
        ),
    )
    parser.add_argument(
        "--fuse-gelu",
        action="store_true",
        help=(
            "after presoftmax alignment, replace the 12 exact guarded expanded "
            "GELU DAGs with builtin GELU v1 approximate=false"
        ),
    )
    parser.add_argument(
        "--output",
        type=Path,
        required=True,
        help="new destination under this repository's build/ or temp/ directory",
    )
    return parser.parse_args(argv)


def run(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    source = args.source.resolve()
    _data, model, contract = load_verified_source(source)
    write_experimental_model(
        source,
        args.output,
        model,
        mode=args.mode,
        contract=contract,
        fuse_gelu=args.fuse_gelu,
    )
    return 0


def main() -> int:
    try:
        return run()
    except AttentionPadModelError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
