#!/usr/bin/env python3
"""Structural tests for generate-dav2-attention-k352-model.py."""

from __future__ import annotations

import importlib.util
from pathlib import Path
import struct
import tempfile
import unittest

import numpy as np


SCRIPT_PATH = Path(__file__).resolve().parents[1] / "generate-dav2-attention-k352-model.py"
SPEC = importlib.util.spec_from_file_location("dav2_attention_k352_generator", SCRIPT_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Unable to load {SCRIPT_PATH}")
TOOL = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(TOOL)
SCHEMA = TOOL.schema


def make_tensor(name: str, shape: list[int], tensor_type: int = SCHEMA.TensorType.FLOAT32) -> object:
    tensor = SCHEMA.TensorT()
    tensor.name = name
    tensor.shape = shape.copy()
    tensor.shapeSignature = shape.copy()
    tensor.type = tensor_type
    tensor.buffer = 0
    tensor.isVariable = False
    return tensor


def make_opcode(code: int) -> object:
    opcode = SCHEMA.OperatorCodeT()
    opcode.builtinCode = code
    opcode.deprecatedBuiltinCode = (
        SCHEMA.BuiltinOperator.PLACEHOLDER_FOR_GREATER_OP_CODES
        if code > SCHEMA.BuiltinOperator.PLACEHOLDER_FOR_GREATER_OP_CODES
        else code
    )
    opcode.version = 1
    return opcode


def make_operator(opcode_index: int, inputs: list[int], outputs: list[int], options=None) -> object:
    operator = SCHEMA.OperatorT()
    operator.opcodeIndex = opcode_index
    operator.inputs = inputs.copy()
    operator.outputs = outputs.copy()
    if options is not None:
        operator.builtinOptionsType = SCHEMA.BuiltinOptions.BatchMatMulOptions
        operator.builtinOptions = options
    return operator


def make_synthetic_model(
    target_count: int = 12,
    softmax_lhs: bool = True,
    token_count: int = 351,
) -> tuple[object, list[int]]:
    layout = TOOL.attention_layout(token_count)
    softmax_opcode = 0
    batch_matmul_opcode = 1
    pad_opcode = 2
    relu_opcode = 3
    model = SCHEMA.ModelT()
    model.version = 3
    model.operatorCodes = [
        make_opcode(SCHEMA.BuiltinOperator.SOFTMAX),
        make_opcode(SCHEMA.BuiltinOperator.BATCH_MATMUL),
        make_opcode(SCHEMA.BuiltinOperator.PAD),
        make_opcode(SCHEMA.BuiltinOperator.RELU),
        make_opcode(SCHEMA.BuiltinOperator.SLICE),
        make_opcode(SCHEMA.BuiltinOperator.RESHAPE),
        make_opcode(SCHEMA.BuiltinOperator.CONCATENATION),
    ]
    empty_buffer = SCHEMA.BufferT()
    empty_buffer.data = None
    model.buffers = [empty_buffer]

    subgraph = SCHEMA.SubGraphT()
    subgraph.name = "synthetic"
    subgraph.tensors = [make_tensor("input", [1, 2, 2, 3])]
    subgraph.inputs = [0]
    subgraph.operators = []
    target_indices: list[int] = []
    for block in range(target_count):
        logits = len(subgraph.tensors)
        subgraph.tensors.append(
            make_tensor(f"block_{block}/logits", layout.target_lhs_shape)
        )
        lhs_source = len(subgraph.tensors)
        subgraph.tensors.append(
            make_tensor(f"block_{block}/scores", layout.target_lhs_shape)
        )
        rhs = len(subgraph.tensors)
        subgraph.tensors.append(
            make_tensor(f"block_{block}/value", layout.target_rhs_shape)
        )
        output = len(subgraph.tensors)
        subgraph.tensors.append(
            make_tensor(f"block_{block}/output", layout.target_output_shape)
        )
        producer_opcode = softmax_opcode if softmax_lhs or block != target_count - 1 else relu_opcode
        producer = make_operator(producer_opcode, [logits], [lhs_source])
        if producer_opcode == softmax_opcode:
            softmax_options = SCHEMA.SoftmaxOptionsT()
            softmax_options.beta = 1.0
            producer.builtinOptionsType = SCHEMA.BuiltinOptions.SoftmaxOptions
            producer.builtinOptions = softmax_options
        subgraph.operators.append(producer)
        options = SCHEMA.BatchMatMulOptionsT()
        options.adjX = False
        options.adjY = False
        options.asymmetricQuantizeInputs = False
        target_indices.append(len(subgraph.operators))
        subgraph.operators.append(
            make_operator(batch_matmul_opcode, [lhs_source, rhs], [output], options)
        )
    subgraph.outputs = [target_count * 4]
    model.subgraphs = [subgraph]
    model.description = "synthetic attention padding test"
    model.metadataBuffer = []
    model.metadata = []
    model.signatureDefs = []
    return model, target_indices


def make_scalar_constant(model: object, subgraph: object, name: str, value: float) -> int:
    buffer = SCHEMA.BufferT()
    buffer.data = struct.pack("<f", float(value))
    model.buffers.append(buffer)
    tensor = make_tensor(name, [1])
    tensor.buffer = len(model.buffers) - 1
    subgraph.tensors.append(tensor)
    return len(subgraph.tensors) - 1


def make_synthetic_gelu_model(token_count: int = 351) -> object:
    layout = TOOL.attention_layout(token_count)
    activation_shape = [1, token_count, TOOL.GELU_ACTIVATION_WIDTH]
    output_shape = [1, token_count, TOOL.GELU_BLOCK_OUTPUT_WIDTH]
    builtin_codes = (
        SCHEMA.BuiltinOperator.ADD,
        SCHEMA.BuiltinOperator.MUL,
        SCHEMA.BuiltinOperator.ABS,
        SCHEMA.BuiltinOperator.SIGN,
        SCHEMA.BuiltinOperator.DIV,
        SCHEMA.BuiltinOperator.EXP,
        SCHEMA.BuiltinOperator.SUB,
        SCHEMA.BuiltinOperator.BATCH_MATMUL,
    )
    opcode_indices = {code: index for index, code in enumerate(builtin_codes)}
    model = SCHEMA.ModelT()
    model.version = 3
    model.operatorCodes = [make_opcode(code) for code in builtin_codes]
    empty_buffer = SCHEMA.BufferT()
    empty_buffer.data = None
    model.buffers = [empty_buffer]
    subgraph = SCHEMA.SubGraphT()
    subgraph.name = "synthetic_gelu"
    subgraph.tensors = []
    subgraph.inputs = []
    subgraph.outputs = []
    subgraph.operators = []

    shared_constants: dict[str, int] = {}
    for role, suffix, value in TOOL.GELU_CONSTANT_SPECS:
        if role in TOOL.GELU_SHARED_CONSTANT_ROLES:
            shared_constants[role] = make_scalar_constant(
                model,
                subgraph,
                f"wa/blocks.0/mlp/act/{suffix}",
                value,
            )

    for block in range(TOOL.GELU_CHAIN_COUNT):
        activation_prefix = f"wa/blocks.{block}/mlp/act/"
        left = len(subgraph.tensors)
        subgraph.tensors.append(make_tensor(f"block_{block}/fc1_left", activation_shape))
        right = len(subgraph.tensors)
        subgraph.tensors.append(make_tensor(f"block_{block}/fc1_right", activation_shape))
        subgraph.inputs.extend((left, right))
        source = len(subgraph.tensors)
        subgraph.tensors.append(
            make_tensor(f"wa/blocks.{block}/mlp/fc1/Add_output_0", activation_shape)
        )
        subgraph.operators.append(
            make_operator(opcode_indices[SCHEMA.BuiltinOperator.ADD], [left, right], [source])
        )

        constants = dict(shared_constants)
        for role, suffix, value in TOOL.GELU_CONSTANT_SPECS:
            if role not in TOOL.GELU_SHARED_CONSTANT_ROLES:
                constants[role] = make_scalar_constant(
                    model, subgraph, activation_prefix + suffix, value
                )
        outputs: list[int] = []
        for suffix in TOOL.GELU_OUTPUT_SUFFIXES:
            outputs.append(len(subgraph.tensors))
            subgraph.tensors.append(make_tensor(activation_prefix + suffix, activation_shape))
        value = outputs
        chain_inputs = (
            [source, constants["scale"]],
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
            [source, value[21]],
            [value[22], constants["half"]],
        )
        for name, inputs, output in zip(
            TOOL.GELU_EXPANDED_OPERATOR_NAMES, chain_inputs, outputs
        ):
            builtin_code = getattr(SCHEMA.BuiltinOperator, name)
            subgraph.operators.append(
                make_operator(opcode_indices[builtin_code], inputs, [output])
            )

        weight = len(subgraph.tensors)
        subgraph.tensors.append(
            make_tensor(
                f"wa/blocks.{block}/mlp/fc2/MatMul_output_0_matmul_b_f32",
                [TOOL.GELU_ACTIVATION_WIDTH, TOOL.GELU_BLOCK_OUTPUT_WIDTH],
            )
        )
        downstream = len(subgraph.tensors)
        subgraph.tensors.append(
            make_tensor(
                f"wa/blocks.{block}/mlp/fc2/MatMul_output_0_matmul_f32",
                output_shape,
            )
        )
        options = SCHEMA.BatchMatMulOptionsT()
        options.adjX = False
        options.adjY = False
        options.asymmetricQuantizeInputs = False
        subgraph.operators.append(
            make_operator(
                opcode_indices[SCHEMA.BuiltinOperator.BATCH_MATMUL],
                [outputs[-1], weight],
                [downstream],
                options,
            )
        )
        subgraph.outputs.append(downstream)

    model.subgraphs = [subgraph]
    model.description = "synthetic exact GELU fusion test"
    model.metadataBuffer = []
    model.metadata = []
    model.signatureDefs = []
    self_check = TOOL.find_expanded_gelu_chains(model, layout=layout)
    if len(self_check) != TOOL.GELU_CHAIN_COUNT:
        raise AssertionError("Synthetic GELU fixture failed its own structural guard")
    return model


def make_synthetic_aligned_gelu_only_model(
    token_count: int = 300,
) -> tuple[object, list[int]]:
    model = make_synthetic_gelu_model(token_count=token_count)
    layout = TOOL.attention_layout(token_count)
    subgraph = model.subgraphs[0]
    softmax_opcode = len(model.operatorCodes)
    model.operatorCodes.append(make_opcode(SCHEMA.BuiltinOperator.SOFTMAX))
    batch_matmul_opcodes = [
        index
        for index, opcode in enumerate(model.operatorCodes)
        if max(int(opcode.builtinCode), int(opcode.deprecatedBuiltinCode))
        == int(SCHEMA.BuiltinOperator.BATCH_MATMUL)
    ]
    if len(batch_matmul_opcodes) != 1:
        raise AssertionError("Synthetic fixture does not have one BATCH_MATMUL opcode")
    batch_matmul_opcode = batch_matmul_opcodes[0]
    target_indices: list[int] = []
    for block in range(12):
        logits = len(subgraph.tensors)
        subgraph.tensors.append(
            make_tensor(f"attention_{block}/logits", layout.target_lhs_shape)
        )
        scores = len(subgraph.tensors)
        subgraph.tensors.append(
            make_tensor(f"attention_{block}/scores", layout.target_lhs_shape)
        )
        value = len(subgraph.tensors)
        subgraph.tensors.append(
            make_tensor(f"attention_{block}/value", layout.target_rhs_shape)
        )
        output = len(subgraph.tensors)
        subgraph.tensors.append(
            make_tensor(f"attention_{block}/output", layout.target_output_shape)
        )
        subgraph.inputs.extend((logits, value))
        subgraph.outputs.append(output)

        softmax_options = SCHEMA.SoftmaxOptionsT()
        softmax_options.beta = 1.0
        softmax = make_operator(softmax_opcode, [logits], [scores])
        softmax.builtinOptionsType = SCHEMA.BuiltinOptions.SoftmaxOptions
        softmax.builtinOptions = softmax_options
        subgraph.operators.append(softmax)

        options = SCHEMA.BatchMatMulOptionsT()
        options.adjX = False
        options.adjY = False
        options.asymmetricQuantizeInputs = False
        target_indices.append(len(subgraph.operators))
        subgraph.operators.append(
            make_operator(
                batch_matmul_opcode,
                [scores, value],
                [output],
                options,
            )
        )
    matches = TOOL.find_attention_value_products(model, layout=layout)
    if matches != target_indices:
        raise AssertionError("Synthetic aligned attention fixture failed its own guard")
    return model, target_indices


class AttentionK352ToolTest(unittest.TestCase):
    def test_exact_gelu_fusion_replaces_twelve_dags_and_round_trips(self) -> None:
        model = make_synthetic_gelu_model()
        subgraph = model.subgraphs[0]
        old_tensors = len(subgraph.tensors)
        old_operators = len(subgraph.operators)
        old_buffers = len(model.buffers)
        old_opcodes = len(model.operatorCodes)
        original_records = TOOL.find_expanded_gelu_chains(model)
        original_outputs = [record["output"] for record in original_records]

        fusion = TOOL.fuse_expanded_gelu_chains(model)
        TOOL.validate_fused_gelu_structure(
            model,
            fusion.records,
            original_tensor_count=old_tensors,
            original_operator_count=old_operators,
            original_buffer_count=old_buffers,
            original_operator_code_count=old_opcodes,
        )
        reparsed = TOOL.parse_model(TOOL.serialize_model(model))
        TOOL.validate_fused_gelu_structure(
            reparsed,
            fusion.records,
            original_tensor_count=old_tensors,
            original_operator_count=old_operators,
            original_buffer_count=old_buffers,
            original_operator_code_count=old_opcodes,
        )

        self.assertEqual(
            old_operators - TOOL.GELU_REMOVED_OPERATOR_COUNT,
            len(reparsed.subgraphs[0].operators),
        )
        self.assertEqual(old_tensors, len(reparsed.subgraphs[0].tensors))
        self.assertEqual(old_buffers, len(reparsed.buffers))
        self.assertEqual(old_opcodes + 1, len(reparsed.operatorCodes))
        self.assertEqual(original_outputs, [record["output"] for record in fusion.records])

    def test_exact_gelu_fusion_refuses_changed_constant(self) -> None:
        model = make_synthetic_gelu_model()
        first_start = TOOL._expanded_gelu_type_candidates(model)[0]
        scale_tensor = int(model.subgraphs[0].operators[first_start].inputs[1])
        scale_buffer = int(model.subgraphs[0].tensors[scale_tensor].buffer)
        model.buffers[scale_buffer].data = struct.pack("<f", 0.70710677)
        with self.assertRaisesRegex(
            TOOL.AttentionPadModelError, "is not exactly 0.70703125"
        ):
            TOOL.find_expanded_gelu_chains(model)

    def test_original_source_contracts_are_exact_relocatable_and_complete(self) -> None:
        expected = {
            "depth-anything-v2-small-static-350x196-float32.tflite.model": (
                97_496_648,
                "4e62f378646966c99855e4648cbc22f3b6f8ce4ea2efbefd27ee735300f98e57",
                (1, 196, 350, 3),
                (1, 196, 350, 1),
                351,
            ),
            "depth-anything-v2-small-static-392x168-float32.tflite.model": (
                97_475_144,
                "5d1cb6cfe13a6fb984ca23410df6e70eb5846ad4af7c49e6e89e193463a3869a",
                (1, 168, 392, 3),
                (1, 168, 392, 1),
                337,
            ),
            "depth-anything-v2-small-static-490x140-float32.tflite.model": (
                97_496_648,
                "ffc15cbc13a5b499844b7fede553bf14b9895bc10bd1904b253419bcc5dbcdb9",
                (1, 140, 490, 3),
                (1, 140, 490, 1),
                351,
            ),
            "depth-anything-v2-small-static-322x182-float32.tflite.model": (
                97_418_312,
                "eaf4f4fc25809da9000ba4e5330b1e3335722b1937fcd94c6e4935fbc411bc23",
                (1, 182, 322, 3),
                (1, 182, 322, 1),
                300,
            ),
            "depth-anything-v2-small-static-350x154-float32.tflite.model": (
                97_381_448,
                "174ab97d5fb87c1d992f1c0ff6700ced949ccd3e5eda3bdf641be2c446f441f1",
                (1, 154, 350, 3),
                (1, 154, 350, 1),
                276,
            ),
            "depth-anything-v2-small-static-434x126-float32.tflite.model": (
                97_387_592,
                "0e746d66a40eaa6673cef93144f49843c0f0a10fc618dbe15cc71bba2f9a3055",
                (1, 126, 434, 3),
                (1, 126, 434, 1),
                280,
            ),
        }
        self.assertEqual(set(expected), {item.filename for item in TOOL.SOURCE_CONTRACTS})
        for contract in TOOL.SOURCE_CONTRACTS:
            size, digest, input_shape, output_shape, token_count = expected[
                contract.filename
            ]
            self.assertEqual(size, contract.source_size)
            self.assertEqual(digest, contract.source_sha256)
            self.assertEqual(input_shape, contract.input_shape)
            self.assertEqual(output_shape, contract.output_shape)
            self.assertEqual(token_count, contract.token_count)
            self.assertEqual(
                contract,
                TOOL.source_contract_for_identity(
                    TOOL.REPOSITORY_ROOT / "temp" / "relocated" / contract.filename,
                    contract.source_size,
                    contract.source_sha256,
                ),
            )

    def test_default_source_uses_original_model_backup_directory(self) -> None:
        self.assertEqual(
            TOOL.ORIGINAL_MODEL_BACKUP_DIRECTORY
            / TOOL.DEFAULT_SOURCE_CONTRACT.filename,
            TOOL.DEFAULT_SOURCE,
        )

    def test_rejects_transformed_production_source_identity(self) -> None:
        production_source = (
            TOOL.SOURCE_ASSET_DIRECTORY / TOOL.DEFAULT_SOURCE_CONTRACT.filename
        )
        with self.assertRaisesRegex(
            TOOL.AttentionPadModelError, "production asset is transformed/optimized"
        ):
            TOOL.source_contract_for_identity(production_source, 1, "00" * 32)

    def test_rejects_apollo_source_path(self) -> None:
        apollo_source = TOOL.REPOSITORY_ROOT.parent / "Apollo-3D" / "source.tflite"
        with self.assertRaisesRegex(TOOL.AttentionPadModelError, "Apollo-3D"):
            TOOL.validate_source_path(apollo_source)

    def test_tail_padding_is_the_minimum_multiple_of_four(self) -> None:
        cases = (
            (351, 352, 1),
            (337, 340, 3),
            (300, 300, 0),
            (276, 276, 0),
            (280, 280, 0),
            (352, 352, 0),
        )
        for tokens, aligned, padding in cases:
            with self.subTest(tokens=tokens):
                layout = TOOL.attention_layout(tokens)
                self.assertEqual(aligned, layout.aligned_token_count)
                self.assertEqual(padding, layout.tail_padding)
                self.assertEqual(0, layout.aligned_token_count % 4)
                self.assertLess(layout.tail_padding, 4)

    def test_322_contract_has_exact_gelu_only_inventory(self) -> None:
        contract = next(
            item for item in TOOL.SOURCE_CONTRACTS
            if item.filename
            == "depth-anything-v2-small-static-322x182-float32.tflite.model"
        )
        self.assertEqual(300, contract.token_count)
        self.assertEqual(0, TOOL.attention_layout(contract.token_count).tail_padding)
        self.assertEqual(
            683,
            contract.operator_count - TOOL.GELU_REMOVED_OPERATOR_COUNT,
        )
        self.assertEqual(1587, contract.tensor_count)
        self.assertEqual(629, contract.buffer_count)
        self.assertEqual(21, contract.operator_code_count + 1)

    def test_all_performance_contracts_are_naturally_c4_aligned(self) -> None:
        expected = {
            "depth-anything-v2-small-static-322x182-float32.tflite.model": 300,
            "depth-anything-v2-small-static-350x154-float32.tflite.model": 276,
            "depth-anything-v2-small-static-434x126-float32.tflite.model": 280,
        }
        contracts = {
            item.filename: item
            for item in TOOL.SOURCE_CONTRACTS
            if item.filename in expected
        }
        self.assertEqual(set(expected), set(contracts))
        for filename, token_count in expected.items():
            with self.subTest(filename=filename):
                contract = contracts[filename]
                self.assertEqual(token_count, contract.token_count)
                self.assertEqual(
                    0,
                    TOOL.attention_layout(contract.token_count).tail_padding,
                )
                self.assertEqual(
                    683,
                    contract.operator_count - TOOL.GELU_REMOVED_OPERATOR_COUNT,
                )

    def test_gelu_only_fuses_aligned_graph_without_attention_padding(self) -> None:
        layout = TOOL.attention_layout(300)
        model, targets = make_synthetic_aligned_gelu_only_model()
        subgraph = model.subgraphs[0]
        old_tensors = len(subgraph.tensors)
        old_operators = len(subgraph.operators)
        old_buffers = len(model.buffers)
        old_opcodes = len(model.operatorCodes)
        source_wiring = TOOL._attention_product_wiring(model, targets)

        result = TOOL.fuse_aligned_gelu_only(
            model,
            expected_target_indices=targets,
            layout=layout,
        )
        TOOL.validate_gelu_only_structure(
            model,
            result.records,
            original_tensor_count=old_tensors,
            original_operator_count=old_operators,
            original_buffer_count=old_buffers,
            original_operator_code_count=old_opcodes,
            source_attention_wiring=source_wiring,
            layout=layout,
        )
        reparsed = TOOL.validate_serialized_gelu_only_structure(
            TOOL.serialize_model(model),
            result.records,
            original_tensor_count=old_tensors,
            original_operator_count=old_operators,
            original_buffer_count=old_buffers,
            original_operator_code_count=old_opcodes,
            source_attention_wiring=source_wiring,
            layout=layout,
            validate_production_io=False,
        )

        self.assertEqual(source_wiring, result.attention_wiring)
        self.assertEqual(
            old_operators - TOOL.GELU_REMOVED_OPERATOR_COUNT,
            len(reparsed.subgraphs[0].operators),
        )
        self.assertEqual(old_tensors, len(reparsed.subgraphs[0].tensors))
        self.assertEqual(old_buffers, len(reparsed.buffers))
        self.assertEqual(old_opcodes + 1, len(reparsed.operatorCodes))
        self.assertEqual(
            source_wiring,
            TOOL._attention_product_wiring(
                reparsed,
                TOOL.find_attention_value_products(reparsed, layout=layout),
            ),
        )
        self.assertNotIn(
            "CONCATENATION",
            [TOOL.operator_name(reparsed, operator)
             for operator in reparsed.subgraphs[0].operators],
        )

    def test_gelu_only_refuses_nonaligned_source_before_mutation(self) -> None:
        layout = TOOL.attention_layout(351)
        model, targets = make_synthetic_aligned_gelu_only_model(token_count=351)
        subgraph = model.subgraphs[0]
        old_counts = (
            len(subgraph.tensors),
            len(subgraph.operators),
            len(model.buffers),
            len(model.operatorCodes),
        )
        with self.assertRaisesRegex(
            TOOL.AttentionPadModelError,
            "requires a token count already divisible by four",
        ):
            TOOL.fuse_aligned_gelu_only(
                model,
                expected_target_indices=targets,
                layout=layout,
            )
        self.assertEqual(
            old_counts,
            (
                len(subgraph.tensors),
                len(subgraph.operators),
                len(model.buffers),
                len(model.operatorCodes),
            ),
        )

    def test_presoftmax_refuses_zero_width_tail(self) -> None:
        layout = TOOL.attention_layout(300)
        model, targets = make_synthetic_model(token_count=300)
        with self.assertRaisesRegex(
            TOOL.AttentionPadModelError,
            "already divisible by four",
        ):
            TOOL.pad_attention_value_products(
                model,
                expected_target_indices=targets,
                mode=TOOL.MODE_PRESOFTMAX,
                layout=layout,
            )

    def test_gelu_only_mode_cannot_enter_attention_padding_function(self) -> None:
        layout = TOOL.attention_layout(300)
        model, targets = make_synthetic_model(token_count=300)
        with self.assertRaisesRegex(
            TOOL.AttentionPadModelError,
            "must not rewrite or pad attention",
        ):
            TOOL.pad_attention_value_products(
                model,
                expected_target_indices=targets,
                mode=TOOL.MODE_GELU_ONLY,
                layout=layout,
            )

    def test_pads_both_inner_dimensions_for_exactly_twelve_targets(self) -> None:
        model, targets = make_synthetic_model()
        subgraph = model.subgraphs[0]
        old_tensors = len(subgraph.tensors)
        old_operators = len(subgraph.operators)
        old_buffers = len(model.buffers)

        records = TOOL.pad_attention_value_products(
            model, expected_target_indices=targets
        )
        TOOL.validate_padded_structure(
            model,
            records,
            original_tensor_count=old_tensors,
            original_operator_count=old_operators,
            original_buffer_count=old_buffers,
        )

        self.assertEqual(12, len(records))
        self.assertEqual(old_operators + 24, len(subgraph.operators))
        self.assertEqual(old_tensors + 48, len(subgraph.tensors))
        self.assertEqual(old_buffers + 24, len(model.buffers))
        for record in records:
            bmm = subgraph.operators[record["patched_bmm_operator"]]
            lhs, rhs = [subgraph.tensors[index] for index in bmm.inputs]
            output = subgraph.tensors[int(bmm.outputs[0])]
            self.assertEqual(TOOL.PADDED_LHS_SHAPE, TOOL._ints(lhs.shape))
            self.assertEqual(TOOL.PADDED_RHS_SHAPE, TOOL._ints(rhs.shape))
            self.assertEqual(TOOL.TARGET_OUTPUT_SHAPE, TOOL._ints(output.shape))

    def test_patched_graph_round_trips_with_all_pad_constants(self) -> None:
        model, targets = make_synthetic_model()
        subgraph = model.subgraphs[0]
        old_tensors = len(subgraph.tensors)
        old_operators = len(subgraph.operators)
        old_buffers = len(model.buffers)
        records = TOOL.pad_attention_value_products(model, expected_target_indices=targets)

        serialized = TOOL.serialize_model(model)
        reparsed = TOOL.validate_serialized_structure(
            serialized,
            records,
            original_tensor_count=old_tensors,
            original_operator_count=old_operators,
            original_buffer_count=old_buffers,
            validate_production_io=False,
        )

        self.assertEqual(old_operators + 24, len(reparsed.subgraphs[0].operators))
        first = records[0]
        lhs_constant = reparsed.subgraphs[0].tensors[first["padding_tensors"][0]]
        rhs_constant = reparsed.subgraphs[0].tensors[first["padding_tensors"][1]]
        self.assertEqual(
            TOOL._flatten_paddings(TOOL.LHS_PADDINGS),
            TOOL._decode_padding_buffer(reparsed, lhs_constant),
        )
        self.assertEqual(
            TOOL._flatten_paddings(TOOL.RHS_PADDINGS),
            TOOL._decode_padding_buffer(reparsed, rhs_constant),
        )

    def test_km352_pads_query_and_reduction_then_slices_original_output(self) -> None:
        model, targets = make_synthetic_model()
        subgraph = model.subgraphs[0]
        old_tensors = len(subgraph.tensors)
        old_operators = len(subgraph.operators)
        old_buffers = len(model.buffers)

        records = TOOL.pad_attention_value_products(
            model,
            expected_target_indices=targets,
            mode=TOOL.MODE_KM352,
        )
        TOOL.validate_padded_structure(
            model,
            records,
            original_tensor_count=old_tensors,
            original_operator_count=old_operators,
            original_buffer_count=old_buffers,
            mode=TOOL.MODE_KM352,
        )

        self.assertEqual(old_operators + 36, len(subgraph.operators))
        self.assertEqual(old_tensors + 84, len(subgraph.tensors))
        self.assertEqual(old_buffers + 48, len(model.buffers))
        for record in records:
            bmm = subgraph.operators[record["patched_bmm_operator"]]
            slice_operator = subgraph.operators[record["slice_operator"]]
            lhs, rhs = [subgraph.tensors[index] for index in bmm.inputs]
            padded_output = subgraph.tensors[int(bmm.outputs[0])]
            restored_output = subgraph.tensors[int(slice_operator.outputs[0])]
            self.assertEqual(TOOL.KM_PADDED_LHS_SHAPE, TOOL._ints(lhs.shape))
            self.assertEqual(TOOL.PADDED_RHS_SHAPE, TOOL._ints(rhs.shape))
            self.assertEqual(
                TOOL.KM_PADDED_OUTPUT_SHAPE, TOOL._ints(padded_output.shape)
            )
            self.assertEqual(
                TOOL.TARGET_OUTPUT_SHAPE, TOOL._ints(restored_output.shape)
            )
            self.assertEqual(record["output"], int(slice_operator.outputs[0]))

    def test_km352_round_trip_preserves_slice_constants_and_wiring(self) -> None:
        model, targets = make_synthetic_model()
        subgraph = model.subgraphs[0]
        old_tensors = len(subgraph.tensors)
        old_operators = len(subgraph.operators)
        old_buffers = len(model.buffers)
        records = TOOL.pad_attention_value_products(
            model,
            expected_target_indices=targets,
            mode=TOOL.MODE_KM352,
        )

        reparsed = TOOL.validate_serialized_structure(
            TOOL.serialize_model(model),
            records,
            original_tensor_count=old_tensors,
            original_operator_count=old_operators,
            original_buffer_count=old_buffers,
            validate_production_io=False,
            mode=TOOL.MODE_KM352,
        )

        first = records[0]
        begin_index, size_index = first["slice_tensors"]
        self.assertEqual(
            TOOL.OUTPUT_SLICE_BEGIN,
            TOOL._decode_int32_vector(
                reparsed, reparsed.subgraphs[0].tensors[begin_index], 4
            ),
        )
        self.assertEqual(
            TOOL.OUTPUT_SLICE_SIZE,
            TOOL._decode_int32_vector(
                reparsed, reparsed.subgraphs[0].tensors[size_index], 4
            ),
        )

    def test_rank3_wraps_only_value_bmms_and_restores_original_outputs(self) -> None:
        model, targets = make_synthetic_model()
        subgraph = model.subgraphs[0]
        old_tensors = len(subgraph.tensors)
        old_operators = len(subgraph.operators)
        old_buffers = len(model.buffers)

        records = TOOL.pad_attention_value_products(
            model,
            expected_target_indices=targets,
            mode=TOOL.MODE_RANK3,
        )
        TOOL.validate_padded_structure(
            model,
            records,
            original_tensor_count=old_tensors,
            original_operator_count=old_operators,
            original_buffer_count=old_buffers,
            mode=TOOL.MODE_RANK3,
        )

        self.assertEqual(old_operators + 36, len(subgraph.operators))
        self.assertEqual(old_tensors + 72, len(subgraph.tensors))
        self.assertEqual(old_buffers + 36, len(model.buffers))
        for record in records:
            bmm = subgraph.operators[record["patched_bmm_operator"]]
            output_reshape = subgraph.operators[record["output_reshape_operator"]]
            lhs, rhs = [subgraph.tensors[index] for index in bmm.inputs]
            rank3_output = subgraph.tensors[int(bmm.outputs[0])]
            restored_output = subgraph.tensors[int(output_reshape.outputs[0])]
            self.assertEqual(TOOL.RANK3_LHS_SHAPE, TOOL._ints(lhs.shape))
            self.assertEqual(TOOL.RANK3_RHS_SHAPE, TOOL._ints(rhs.shape))
            self.assertEqual(TOOL.RANK3_OUTPUT_SHAPE, TOOL._ints(rank3_output.shape))
            self.assertEqual(
                TOOL.TARGET_OUTPUT_SHAPE, TOOL._ints(restored_output.shape)
            )
            self.assertEqual(record["output"], int(output_reshape.outputs[0]))

    def test_rank3_round_trip_preserves_reshape_options_and_constants(self) -> None:
        model, targets = make_synthetic_model()
        subgraph = model.subgraphs[0]
        old_tensors = len(subgraph.tensors)
        old_operators = len(subgraph.operators)
        old_buffers = len(model.buffers)
        records = TOOL.pad_attention_value_products(
            model,
            expected_target_indices=targets,
            mode=TOOL.MODE_RANK3,
        )

        reparsed = TOOL.validate_serialized_structure(
            TOOL.serialize_model(model),
            records,
            original_tensor_count=old_tensors,
            original_operator_count=old_operators,
            original_buffer_count=old_buffers,
            validate_production_io=False,
            mode=TOOL.MODE_RANK3,
        )

        first = records[0]
        bmm = reparsed.subgraphs[0].operators[first["patched_bmm_operator"]]
        output_reshape = reparsed.subgraphs[0].operators[
            first["output_reshape_operator"]
        ]
        self.assertEqual(TOOL.RANK3_LHS_SHAPE, TOOL._ints(
            reparsed.subgraphs[0].tensors[int(bmm.inputs[0])].shape
        ))
        self.assertEqual(
            TOOL.TARGET_OUTPUT_SHAPE,
            TOOL._reshape_options_shape(output_reshape),
        )

    def test_rank3_rewrite_is_exact_matmul_identity(self) -> None:
        lhs = np.arange(1 * 6 * 5 * 5, dtype=np.float32).reshape(1, 6, 5, 5)
        rhs = (
            np.arange(1 * 6 * 5 * 4, dtype=np.float32).reshape(1, 6, 5, 4)
            / np.float32(17.0)
        )
        original = np.matmul(lhs, rhs)
        rewritten = np.matmul(lhs.reshape(6, 5, 5), rhs.reshape(6, 5, 4)).reshape(
            1, 6, 5, 4
        )
        np.testing.assert_array_equal(original, rewritten)

    def test_presoftmax_concatenates_logits_and_value_before_bmm(self) -> None:
        model, targets = make_synthetic_model()
        subgraph = model.subgraphs[0]
        old_tensors = len(subgraph.tensors)
        old_operators = len(subgraph.operators)
        old_buffers = len(model.buffers)

        records = TOOL.pad_attention_value_products(
            model,
            expected_target_indices=targets,
            mode=TOOL.MODE_PRESOFTMAX,
        )
        TOOL.validate_padded_structure(
            model,
            records,
            original_tensor_count=old_tensors,
            original_operator_count=old_operators,
            original_buffer_count=old_buffers,
            mode=TOOL.MODE_PRESOFTMAX,
        )

        self.assertEqual(old_operators + 24, len(subgraph.operators))
        self.assertEqual(old_tensors + 38, len(subgraph.tensors))
        self.assertEqual(old_buffers + 2, len(model.buffers))
        self.assertEqual(1, len({record["logits_constant"] for record in records}))
        self.assertEqual(1, len({record["value_constant"] for record in records}))
        for record in records:
            logits_concat = subgraph.operators[
                record["patched_logits_concat_operator"]
            ]
            softmax = subgraph.operators[record["patched_softmax_operator"]]
            value_concat = subgraph.operators[
                record["patched_value_concat_operator"]
            ]
            bmm = subgraph.operators[record["patched_bmm_operator"]]
            self.assertEqual(3, TOOL._concat_axis(logits_concat))
            self.assertEqual(2, TOOL._concat_axis(value_concat))
            self.assertEqual(
                TOOL.PRESOFTMAX_LOGITS_SHAPE,
                TOOL._ints(subgraph.tensors[int(softmax.outputs[0])].shape),
            )
            self.assertEqual(
                TOOL.PRESOFTMAX_VALUE_SHAPE,
                TOOL._ints(subgraph.tensors[int(value_concat.outputs[0])].shape),
            )
            self.assertEqual(record["output"], int(bmm.outputs[0]))

    def test_presoftmax_337_tokens_adds_three_lanes_and_shares_constants(self) -> None:
        layout = TOOL.attention_layout(337)
        model, targets = make_synthetic_model(token_count=337)
        subgraph = model.subgraphs[0]
        old_tensors = len(subgraph.tensors)
        old_operators = len(subgraph.operators)
        old_buffers = len(model.buffers)

        records = TOOL.pad_attention_value_products(
            model,
            expected_target_indices=targets,
            mode=TOOL.MODE_PRESOFTMAX,
            layout=layout,
        )
        TOOL.validate_padded_structure(
            model,
            records,
            original_tensor_count=old_tensors,
            original_operator_count=old_operators,
            original_buffer_count=old_buffers,
            mode=TOOL.MODE_PRESOFTMAX,
            layout=layout,
        )
        reparsed = TOOL.validate_serialized_structure(
            TOOL.serialize_model(model),
            records,
            original_tensor_count=old_tensors,
            original_operator_count=old_operators,
            original_buffer_count=old_buffers,
            validate_production_io=False,
            mode=TOOL.MODE_PRESOFTMAX,
            layout=layout,
        )

        self.assertEqual(old_operators + 24, len(subgraph.operators))
        self.assertEqual(old_tensors + 38, len(subgraph.tensors))
        self.assertEqual(old_buffers + 2, len(model.buffers))
        logits_constants = {record["logits_constant"] for record in records}
        value_constants = {record["value_constant"] for record in records}
        self.assertEqual(1, len(logits_constants))
        self.assertEqual(1, len(value_constants))
        first = records[0]
        TOOL._validate_float_constant(
            reparsed,
            reparsed.subgraphs[0].tensors[first["logits_constant"]],
            [1, 6, 337, 3],
            TOOL.PRESOFTMAX_SENTINEL,
        )
        TOOL._validate_float_constant(
            reparsed,
            reparsed.subgraphs[0].tensors[first["value_constant"]],
            [1, 6, 3, 64],
            0.0,
        )
        for record in records:
            softmax = subgraph.operators[record["patched_softmax_operator"]]
            value_concat = subgraph.operators[
                record["patched_value_concat_operator"]
            ]
            self.assertEqual(
                [1, 6, 337, 340],
                TOOL._tensor_shape(subgraph, int(softmax.outputs[0])),
            )
            self.assertEqual(
                [1, 6, 340, 64],
                TOOL._tensor_shape(subgraph, int(value_concat.outputs[0])),
            )

    def test_presoftmax_round_trip_preserves_concat_constants_and_axes(self) -> None:
        model, targets = make_synthetic_model()
        subgraph = model.subgraphs[0]
        old_tensors = len(subgraph.tensors)
        old_operators = len(subgraph.operators)
        old_buffers = len(model.buffers)
        records = TOOL.pad_attention_value_products(
            model,
            expected_target_indices=targets,
            mode=TOOL.MODE_PRESOFTMAX,
        )

        reparsed = TOOL.validate_serialized_structure(
            TOOL.serialize_model(model),
            records,
            original_tensor_count=old_tensors,
            original_operator_count=old_operators,
            original_buffer_count=old_buffers,
            validate_production_io=False,
            mode=TOOL.MODE_PRESOFTMAX,
        )

        first = records[0]
        TOOL._validate_float_constant(
            reparsed,
            reparsed.subgraphs[0].tensors[first["logits_constant"]],
            TOOL.PRESOFTMAX_LOGIT_CONSTANT_SHAPE,
            TOOL.PRESOFTMAX_SENTINEL,
        )
        TOOL._validate_float_constant(
            reparsed,
            reparsed.subgraphs[0].tensors[first["value_constant"]],
            TOOL.PRESOFTMAX_VALUE_CONSTANT_SHAPE,
            0.0,
        )

    def test_presoftmax_float32_sentinel_is_numerical_identity(self) -> None:
        logits = np.linspace(-2.0, 2.0, 1 * 6 * 5 * 5, dtype=np.float32).reshape(
            1, 6, 5, 5
        )
        values = np.linspace(-1.0, 1.0, 1 * 6 * 5 * 4, dtype=np.float32).reshape(
            1, 6, 5, 4
        )
        logits_max = np.max(logits, axis=-1, keepdims=True)
        original_exp = np.exp(logits - logits_max)
        original_weights = original_exp / np.sum(
            original_exp, axis=-1, keepdims=True
        )
        with np.errstate(under="ignore"):
            sentinel_exp = np.exp(np.float32(TOOL.PRESOFTMAX_SENTINEL))
        self.assertEqual(np.float32(0.0), sentinel_exp)
        original_output = np.matmul(original_weights, values)
        for padding in (1, 3):
            with self.subTest(padding=padding):
                expanded_exp = np.concatenate(
                    [
                        original_exp,
                        np.zeros(
                            (*original_exp.shape[:-1], padding), dtype=np.float32
                        ),
                    ],
                    axis=-1,
                )
                expanded_weights = expanded_exp / np.sum(
                    expanded_exp, axis=-1, keepdims=True
                )
                expanded_values = np.concatenate(
                    [
                        values,
                        np.zeros(
                            (*values.shape[:-2], padding, values.shape[-1]),
                            dtype=np.float32,
                        ),
                    ],
                    axis=-2,
                )
                expanded_output = np.matmul(expanded_weights, expanded_values)
                np.testing.assert_allclose(
                    original_weights,
                    expanded_weights[..., :-padding],
                    rtol=3e-7,
                    atol=3e-8,
                )
                np.testing.assert_allclose(
                    original_output,
                    expanded_output,
                    rtol=3e-7,
                    atol=3e-8,
                )

    def test_refuses_partial_eleven_target_patch(self) -> None:
        model, targets = make_synthetic_model(target_count=11)
        with self.assertRaisesRegex(TOOL.AttentionPadModelError, "exactly 12"):
            TOOL.pad_attention_value_products(model, expected_target_indices=targets)

    def test_requires_softmax_to_identify_second_attention_matmul(self) -> None:
        model, targets = make_synthetic_model(softmax_lhs=False)
        self.assertEqual(targets[:-1], TOOL.find_attention_value_products(model))
        with self.assertRaisesRegex(TOOL.AttentionPadModelError, "Refusing partial patch"):
            TOOL.pad_attention_value_products(model, expected_target_indices=targets)

    def test_refuses_source_apollo_assets_and_existing_outputs(self) -> None:
        source = TOOL.DEFAULT_SOURCE.resolve()
        with self.assertRaisesRegex(TOOL.AttentionPadModelError, "source model"):
            TOOL.validate_output_path(source, source)
        apollo = TOOL.REPOSITORY_ROOT.parent / "Apollo-3D" / "model.tflite"
        with self.assertRaisesRegex(TOOL.AttentionPadModelError, "Apollo-3D"):
            TOOL.validate_output_path(source, apollo)
        asset = TOOL.REPOSITORY_ROOT / "app" / "src" / "nonRoot_game" / "assets" / "new.tflite"
        with self.assertRaisesRegex(TOOL.AttentionPadModelError, "source assets"):
            TOOL.validate_output_path(source, asset)
        build_root = TOOL.REPOSITORY_ROOT / "build"
        build_root.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=build_root) as directory:
            existing = Path(directory) / "existing.tflite"
            existing.write_bytes(b"do not replace")
            with self.assertRaisesRegex(TOOL.AttentionPadModelError, "overwrite"):
                TOOL.validate_output_path(source, existing)
        with tempfile.TemporaryDirectory() as directory:
            outside_repo = Path(directory) / "new.tflite"
            with self.assertRaisesRegex(TOOL.AttentionPadModelError, "moonlight-android"):
                TOOL.validate_output_path(source, outside_repo)


if __name__ == "__main__":
    unittest.main()
