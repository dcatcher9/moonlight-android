#!/usr/bin/env python3
"""Create a guarded Depth Anything V2 diagnostic checkpoint model.

The production 350x196 model normally exposes only ``depth_bhwc``.  This tool
changes that sole subgraph output, and the matching SignatureDef output, to an
existing rank-4 float activation.  The resulting model can be run through the
same LiteRT delegate to find the first tensor at which FP16 diverges from FP32.

Generation is deliberately explicit and guarded.  The source must match the
original 350x196 model byte-for-byte and satisfy its graph contract, but the
backup may live anywhere safe outside Apollo-3D. ``--tensor-index`` and
``--output`` are required, outputs remain restricted to this repository's
build/ or temp/ tree, and production Android assets can never be overwritten.
Use ``--list-rank4`` to inspect eligible tensor indices without writing a model.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import sys
import tempfile
from typing import Any, Iterable, Sequence

try:
    import flatbuffers
    from flatbuffers.util import BufferHasIdentifier
except ImportError as exc:  # pragma: no cover - exercised only on an unprepared machine
    raise SystemExit(
        "Missing flatbuffers. Install tools/midas-static-buckets-requirements.txt "
        "or an equivalent LiteRT Python environment."
    ) from exc

try:
    # This is the smaller dependency and matches the LiteRT runtime used by the client.
    from ai_edge_litert import schema_py_generated as schema
except ImportError:
    try:
        # The existing MiDaS model tooling already installs this schema.
        from tensorflow.lite.python import schema_py_generated as schema
    except ImportError as exc:  # pragma: no cover - exercised only on an unprepared machine
        raise SystemExit(
            "Missing the TFLite schema. Install ai-edge-litert or "
            "tools/midas-static-buckets-requirements.txt."
        ) from exc


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
PRODUCTION_ASSET_DIRECTORY = REPOSITORY_ROOT / "app" / "src" / "nonRoot_game" / "assets"
ORIGINAL_MODEL_BACKUP_DIRECTORY = REPOSITORY_ROOT / "build" / "client-sbs-original-models"
ORIGINAL_MODEL_FILENAME = "depth-anything-v2-small-static-350x196-float32.tflite.model"
DEFAULT_SOURCE = ORIGINAL_MODEL_BACKUP_DIRECTORY / ORIGINAL_MODEL_FILENAME
EXPECTED_SOURCE_SIZE = 97_496_648
EXPECTED_SOURCE_SHA256 = (
    "4e62f378646966c99855e4648cbc22f3b6f8ce4ea2efbefd27ee735300f98e57"
)
EXPECTED_SUBGRAPH_NAME = "depth_anything_v2_small_static_350x196_rank4_attention"
EXPECTED_INPUT_INDEX = 0
EXPECTED_OUTPUT_INDEX = 1512
EXPECTED_INPUT_SHAPE = [1, 196, 350, 3]
EXPECTED_OUTPUT_SHAPE = [1, 196, 350, 1]
EXPECTED_TENSOR_COUNT = 1587
EXPECTED_OPERATOR_COUNT = 959
EXPECTED_SIGNATURE_KEY = "serving_default"
EXPECTED_SIGNATURE_OUTPUT_NAME = "depth_bhwc"


class CheckpointModelError(RuntimeError):
    """Raised when a guard or validation check fails."""


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


def _path_has_component(path: Path, component: str) -> bool:
    return any(part.casefold() == component.casefold() for part in path.resolve().parts)


def _is_production_asset_path(path: Path) -> bool:
    try:
        path.resolve().relative_to(PRODUCTION_ASSET_DIRECTORY.resolve())
        return True
    except ValueError:
        return False


def validate_source_path(path: Path) -> Path:
    resolved = path.resolve()
    if _path_has_component(resolved, "Apollo-3D"):
        raise CheckpointModelError(
            f"Refusing to read a client checkpoint source inside Apollo-3D: {resolved}"
        )
    return resolved


def validate_source_identity(path: Path, size: int, digest: str) -> None:
    normalized_digest = digest.casefold()
    if size == EXPECTED_SOURCE_SIZE and normalized_digest == EXPECTED_SOURCE_SHA256:
        return
    if _is_production_asset_path(path):
        raise CheckpointModelError(
            "The selected production asset is transformed/optimized and is not the "
            "original 959-operator source. Restore the exact original model at "
            f"{DEFAULT_SOURCE}, or pass --source to an exact backup."
        )
    if size != EXPECTED_SOURCE_SIZE:
        raise CheckpointModelError(
            f"Source size is {size:,} bytes, expected {EXPECTED_SOURCE_SIZE:,} "
            "for the original model"
        )
    raise CheckpointModelError(
        f"Source SHA-256 is {normalized_digest}, expected {EXPECTED_SOURCE_SHA256} "
        "for the exact original model"
    )


def parse_model(data: bytes) -> schema.ModelT:
    if not BufferHasIdentifier(data, 0, b"TFL3"):
        raise CheckpointModelError("Input is not a TFL3 FlatBuffer")
    try:
        return schema.ModelT.InitFromObj(schema.Model.GetRootAsModel(data, 0))
    except Exception as exc:
        raise CheckpointModelError(f"Unable to parse TFLite FlatBuffer: {exc}") from exc


def serialize_model(model: schema.ModelT) -> bytes:
    builder = flatbuffers.Builder(0)
    root = model.Pack(builder)
    builder.Finish(root, file_identifier=b"TFL3")
    return bytes(builder.Output())


def tensor_type_name(type_value: int) -> str:
    for name, value in vars(schema.TensorType).items():
        if isinstance(value, int) and value == type_value:
            return name
    return f"UNKNOWN({type_value})"


def operator_code_name(model: schema.ModelT, operator: schema.OperatorT) -> str:
    opcode = model.operatorCodes[int(operator.opcodeIndex)]
    builtin_code = max(int(opcode.builtinCode), int(opcode.deprecatedBuiltinCode))
    if builtin_code == int(schema.BuiltinOperator.CUSTOM):
        return f"CUSTOM:{_text(opcode.customCode)}"
    for name, value in vars(schema.BuiltinOperator).items():
        if isinstance(value, int) and value == builtin_code:
            return name
    return f"BUILTIN({builtin_code})"


def validate_patchable_contract(model: schema.ModelT) -> tuple[Any, Any, Any]:
    if len(model.subgraphs) != 1:
        raise CheckpointModelError(
            f"Expected exactly one subgraph, found {len(model.subgraphs)}"
        )
    subgraph = model.subgraphs[0]
    outputs = _ints(subgraph.outputs)
    if len(outputs) != 1:
        raise CheckpointModelError(
            f"Expected exactly one subgraph output, found {outputs}"
        )
    if len(model.signatureDefs) != 1:
        raise CheckpointModelError(
            f"Expected exactly one SignatureDef, found {len(model.signatureDefs)}"
        )
    signature = model.signatureDefs[0]
    if int(signature.subgraphIndex) != 0:
        raise CheckpointModelError(
            f"SignatureDef targets subgraph {signature.subgraphIndex}, expected 0"
        )
    if len(signature.outputs) != 1:
        raise CheckpointModelError(
            f"Expected exactly one SignatureDef output, found {len(signature.outputs)}"
        )
    signature_output = signature.outputs[0]
    if int(signature_output.tensorIndex) != outputs[0]:
        raise CheckpointModelError(
            "Subgraph and SignatureDef outputs do not reference the same tensor: "
            f"{outputs[0]} != {signature_output.tensorIndex}"
        )
    return subgraph, signature, signature_output


def validate_expected_source_contract(model: schema.ModelT) -> None:
    subgraph, signature, signature_output = validate_patchable_contract(model)
    errors: list[str] = []
    if _text(subgraph.name) != EXPECTED_SUBGRAPH_NAME:
        errors.append(f"subgraph name={_text(subgraph.name)!r}")
    if len(subgraph.tensors) != EXPECTED_TENSOR_COUNT:
        errors.append(f"tensor count={len(subgraph.tensors)}")
    if len(subgraph.operators) != EXPECTED_OPERATOR_COUNT:
        errors.append(f"operator count={len(subgraph.operators)}")
    if _ints(subgraph.inputs) != [EXPECTED_INPUT_INDEX]:
        errors.append(f"inputs={_ints(subgraph.inputs)}")
    if _ints(subgraph.outputs) != [EXPECTED_OUTPUT_INDEX]:
        errors.append(f"outputs={_ints(subgraph.outputs)}")
    if _text(signature.signatureKey) != EXPECTED_SIGNATURE_KEY:
        errors.append(f"signature key={_text(signature.signatureKey)!r}")
    if _text(signature_output.name) != EXPECTED_SIGNATURE_OUTPUT_NAME:
        errors.append(f"signature output={_text(signature_output.name)!r}")
    input_shape = _ints(subgraph.tensors[EXPECTED_INPUT_INDEX].shape)
    output_shape = _ints(subgraph.tensors[EXPECTED_OUTPUT_INDEX].shape)
    if input_shape != EXPECTED_INPUT_SHAPE:
        errors.append(f"input shape={input_shape}")
    if output_shape != EXPECTED_OUTPUT_SHAPE:
        errors.append(f"output shape={output_shape}")
    if errors:
        raise CheckpointModelError(
            "Verified bytes have an unexpected DA-V2 graph contract: " + "; ".join(errors)
        )


def load_verified_source(path: Path) -> tuple[bytes, schema.ModelT]:
    resolved = validate_source_path(path)
    if not resolved.is_file():
        if resolved == DEFAULT_SOURCE.resolve():
            raise CheckpointModelError(
                "Default original DA-V2 source is missing. Restore the exact original "
                f"350x196 model at {DEFAULT_SOURCE}, or pass --source with an exact "
                "backup outside Apollo-3D."
            )
        raise CheckpointModelError(f"Source model does not exist: {resolved}")
    size = resolved.stat().st_size
    data = resolved.read_bytes()
    digest = sha256_bytes(data)
    validate_source_identity(resolved, size, digest)
    model = parse_model(data)
    validate_expected_source_contract(model)
    print(
        f"source={resolved} size={size} sha256={digest} "
        f"tensors={EXPECTED_TENSOR_COUNT} operators={EXPECTED_OPERATOR_COUNT}",
        flush=True,
    )
    return data, model


def build_tensor_links(subgraph: Any) -> tuple[dict[int, int], dict[int, list[int]]]:
    producers: dict[int, int] = {}
    consumers: dict[int, list[int]] = {}
    for operator_index, operator in enumerate(subgraph.operators):
        for tensor_index in _ints(operator.outputs):
            if tensor_index < 0:
                continue
            if tensor_index in producers:
                raise CheckpointModelError(
                    f"Tensor {tensor_index} has multiple producers: "
                    f"{producers[tensor_index]} and {operator_index}"
                )
            producers[tensor_index] = operator_index
        for tensor_index in _ints(operator.inputs):
            if tensor_index >= 0:
                consumers.setdefault(tensor_index, []).append(operator_index)
    return producers, consumers


def operator_metadata(
        model: schema.ModelT, subgraph: Any, operator_index: int) -> dict[str, Any]:
    operator = subgraph.operators[operator_index]
    return {
        "index": operator_index,
        "opcode": operator_code_name(model, operator),
        "inputs": _ints(operator.inputs),
        "outputs": _ints(operator.outputs),
    }


def tensor_metadata(
        model: schema.ModelT,
        tensor_index: int,
        producers: dict[int, int] | None = None,
        consumers: dict[int, list[int]] | None = None,
) -> dict[str, Any]:
    subgraph = model.subgraphs[0]
    if tensor_index < 0 or tensor_index >= len(subgraph.tensors):
        raise CheckpointModelError(
            f"Tensor index {tensor_index} is outside [0, {len(subgraph.tensors) - 1}]"
        )
    if producers is None or consumers is None:
        producers, consumers = build_tensor_links(subgraph)
    tensor = subgraph.tensors[tensor_index]
    buffer_index = int(tensor.buffer)
    buffer_data = model.buffers[buffer_index].data
    producer_index = producers.get(tensor_index)
    return {
        "index": tensor_index,
        "name": _text(tensor.name),
        "shape": _ints(tensor.shape),
        "shape_signature": _ints(tensor.shapeSignature),
        "type": tensor_type_name(int(tensor.type)),
        "buffer_index": buffer_index,
        "buffer_bytes": 0 if buffer_data is None else len(buffer_data),
        "is_variable": bool(tensor.isVariable),
        "is_subgraph_input": tensor_index in _ints(subgraph.inputs),
        "producer": (
            None
            if producer_index is None
            else operator_metadata(model, subgraph, producer_index)
        ),
        "consumers": [
            operator_metadata(model, subgraph, index)
            for index in consumers.get(tensor_index, [])
        ],
    }


def validate_checkpoint_target(model: schema.ModelT, tensor_index: int) -> dict[str, Any]:
    subgraph = model.subgraphs[0]
    producers, consumers = build_tensor_links(subgraph)
    metadata = tensor_metadata(model, tensor_index, producers, consumers)
    if len(metadata["shape"]) != 4 or any(value <= 0 for value in metadata["shape"]):
        raise CheckpointModelError(
            f"Tensor {tensor_index} must have a fixed, positive rank-4 shape; "
            f"found {metadata['shape']}"
        )
    if int(subgraph.tensors[tensor_index].type) != int(schema.TensorType.FLOAT32):
        raise CheckpointModelError(
            f"Tensor {tensor_index} must be FLOAT32; found {metadata['type']}"
        )
    if metadata["producer"] is None and not metadata["is_subgraph_input"]:
        raise CheckpointModelError(
            f"Tensor {tensor_index} is a constant/unproduced tensor, not an activation"
        )
    return metadata


def eligible_rank4_tensors(model: schema.ModelT) -> list[dict[str, Any]]:
    subgraph = model.subgraphs[0]
    producers, consumers = build_tensor_links(subgraph)
    eligible: list[dict[str, Any]] = []
    for tensor_index, tensor in enumerate(subgraph.tensors):
        shape = _ints(tensor.shape)
        if (
                len(shape) == 4
                and all(value > 0 for value in shape)
                and int(tensor.type) == int(schema.TensorType.FLOAT32)
                and (tensor_index in producers or tensor_index in _ints(subgraph.inputs))
        ):
            eligible.append(tensor_metadata(
                model, tensor_index, producers, consumers
            ))
    return eligible


def patch_checkpoint_output(model: schema.ModelT, tensor_index: int) -> dict[str, Any]:
    metadata = validate_checkpoint_target(model, tensor_index)
    subgraph, _signature, signature_output = validate_patchable_contract(model)
    subgraph.outputs = [tensor_index]
    signature_output.tensorIndex = tensor_index
    return metadata


def validate_serialized_structure(
        data: bytes,
        tensor_index: int,
        expected_signature_output_name: str = EXPECTED_SIGNATURE_OUTPUT_NAME,
) -> schema.ModelT:
    model = parse_model(data)
    subgraph, _signature, signature_output = validate_patchable_contract(model)
    if _ints(subgraph.outputs) != [tensor_index]:
        raise CheckpointModelError(
            f"Serialized subgraph output is {_ints(subgraph.outputs)}, expected [{tensor_index}]"
        )
    if int(signature_output.tensorIndex) != tensor_index:
        raise CheckpointModelError(
            f"Serialized SignatureDef output is {signature_output.tensorIndex}, "
            f"expected {tensor_index}"
        )
    if _text(signature_output.name) != expected_signature_output_name:
        raise CheckpointModelError(
            f"Signature output name changed to {_text(signature_output.name)!r}"
        )
    validate_checkpoint_target(model, tensor_index)
    return model


def _load_interpreter_class() -> Any:
    try:
        from ai_edge_litert.interpreter import Interpreter
        return Interpreter
    except ImportError:
        try:
            import tensorflow as tf
            return tf.lite.Interpreter
        except ImportError as exc:  # pragma: no cover - environment-specific
            raise CheckpointModelError(
                "Interpreter validation requires ai-edge-litert or tensorflow-cpu"
            ) from exc


def validate_with_interpreter(path: Path, tensor_index: int, shape: Sequence[int]) -> None:
    interpreter_class = _load_interpreter_class()
    try:
        interpreter = interpreter_class(model_path=str(path), num_threads=1)
        interpreter.allocate_tensors()
        outputs = interpreter.get_output_details()
        if len(outputs) != 1:
            raise CheckpointModelError(
                f"Interpreter exposes {len(outputs)} outputs, expected one"
            )
        actual_index = int(outputs[0]["index"])
        actual_shape = _ints(outputs[0]["shape"])
        if actual_index != tensor_index:
            raise CheckpointModelError(
                f"Interpreter output index is {actual_index}, expected {tensor_index}"
            )
        if actual_shape != list(shape):
            raise CheckpointModelError(
                f"Interpreter output shape is {actual_shape}, expected {list(shape)}"
            )
        signature_list = interpreter.get_signature_list()
        signature = signature_list.get(EXPECTED_SIGNATURE_KEY)
        if signature is None:
            raise CheckpointModelError(
                f"Interpreter does not expose signature {EXPECTED_SIGNATURE_KEY!r}"
            )
        if signature.get("outputs") != [EXPECTED_SIGNATURE_OUTPUT_NAME]:
            raise CheckpointModelError(
                f"Interpreter signature outputs are {signature.get('outputs')}, "
                f"expected [{EXPECTED_SIGNATURE_OUTPUT_NAME!r}]"
            )
    except CheckpointModelError:
        raise
    except Exception as exc:
        raise CheckpointModelError(f"LiteRT interpreter rejected the model: {exc}") from exc
    finally:
        if "interpreter" in locals():
            del interpreter


def _is_relative_to(path: Path, parent: Path) -> bool:
    try:
        path.relative_to(parent)
        return True
    except ValueError:
        return False


def _is_android_source_asset_path(path: Path) -> bool:
    try:
        relative = path.resolve().relative_to(REPOSITORY_ROOT.resolve())
    except ValueError:
        return False
    parts = [part.casefold() for part in relative.parts]
    return (
        len(parts) >= 4
        and parts[0] == "app"
        and parts[1] == "src"
        and "assets" in parts[2:]
    )


def validate_output_path(source: Path, output: Path, force: bool) -> Path:
    resolved_source = source.resolve()
    resolved_output = output.resolve()
    if resolved_output == resolved_source:
        raise CheckpointModelError("Output must not overwrite the verified source model")

    known_apollo = (REPOSITORY_ROOT.parent / "Apollo-3D").resolve()
    has_apollo_component = any(
        component.casefold() == "apollo-3d" for component in resolved_output.parts
    )
    if _is_relative_to(resolved_output, known_apollo) or has_apollo_component:
        raise CheckpointModelError(
            f"Refusing to write a client checkpoint model inside Apollo-3D: {resolved_output}"
        )
    if _is_android_source_asset_path(resolved_output):
        raise CheckpointModelError(
            "Refusing to overwrite an Android production asset; use this repository's "
            "build/ or temp/ directory"
        )
    allowed_roots = (REPOSITORY_ROOT / "build", REPOSITORY_ROOT / "temp")
    if not any(
        _is_relative_to(resolved_output, root.resolve()) for root in allowed_roots
    ):
        raise CheckpointModelError(
            "Checkpoint output must stay under the moonlight-android build/ or temp/ tree"
        )
    if resolved_output.exists() and not force:
        raise CheckpointModelError(
            f"Output already exists: {resolved_output}; pass --force to replace it"
        )
    if resolved_output.exists() and not resolved_output.is_file():
        raise CheckpointModelError(f"Output is not a regular file: {resolved_output}")
    return resolved_output


def write_checkpoint_model(
        source: Path,
        output: Path,
        model: schema.ModelT,
        tensor_index: int,
        force: bool,
) -> None:
    resolved_output = validate_output_path(source, output, force)
    metadata = patch_checkpoint_output(model, tensor_index)
    print("checkpoint=" + json.dumps(metadata, sort_keys=True), flush=True)
    serialized = serialize_model(model)
    validate_serialized_structure(serialized, tensor_index)

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

        validate_with_interpreter(
            temporary_path, tensor_index, metadata["shape"]
        )
        if resolved_output.exists() and not force:
            # Close the race between the earlier check and os.replace().
            raise CheckpointModelError(
                f"Output appeared during generation: {resolved_output}"
            )
        os.replace(temporary_path, resolved_output)
        temporary_path = None
    finally:
        if temporary_path is not None:
            temporary_path.unlink(missing_ok=True)

    print(
        f"validated output={resolved_output} size={resolved_output.stat().st_size} "
        f"sha256={sha256_file(resolved_output)}",
        flush=True,
    )


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=DEFAULT_SOURCE)
    parser.add_argument(
        "--tensor-index",
        type=int,
        help="existing rank-4 FLOAT32 activation to expose as the sole output",
    )
    parser.add_argument(
        "--output",
        type=Path,
        help="explicit destination; use a client-repository build/temp path",
    )
    parser.add_argument(
        "--list-rank4",
        action="store_true",
        help="list eligible rank-4 activation tensors without writing a model",
    )
    parser.add_argument(
        "--force",
        action="store_true",
        help="replace an existing output file (never replaces the source model)",
    )
    args = parser.parse_args(argv)
    if args.list_rank4:
        if args.tensor_index is not None or args.output is not None or args.force:
            parser.error(
                "--list-rank4 cannot be combined with --tensor-index, --output, or --force"
            )
    elif args.tensor_index is None or args.output is None:
        parser.error("generation requires both --tensor-index and --output")
    return args


def run(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    source = args.source.resolve()
    _data, model = load_verified_source(source)
    if args.list_rank4:
        for metadata in eligible_rank4_tensors(model):
            print(json.dumps(metadata, sort_keys=True))
        return 0
    write_checkpoint_model(
        source=source,
        output=args.output,
        model=model,
        tensor_index=args.tensor_index,
        force=args.force,
    )
    return 0


def main() -> int:
    try:
        return run()
    except CheckpointModelError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
