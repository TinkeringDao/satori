#!/usr/bin/env python3
"""Toy IndicTrans2 graphs for the reader's host tests (run_host_tests.sh).

Random weights, small dimensions, and the export's contract: the input and output names, dynamic
axes and file layout of hari31416/indictrans2-indic-en-dist-200M-ONNX-int8, as its
src/01_export_encoder_decoder.py (lines 58-169), src/04_quantize_int8.py and
src/onnx_bundle_optimize.py make them:
  encoder_model.onnx + encoder_model.onnx.data
  decoder_model.onnx + decoder_with_past_model.onnx, both on decoder_shared.onnx.data
Like the export, each graph is built in fp32 (opset 17) and quantized with onnxruntime's
quantize_dynamic (int8 weights, per channel), so the reader runs the same int8 kernels.
These are not model weights: nothing here comes from IndicTrans2.

Usage: python3 -I toy_models.py <output dir>
Writes one folder per variant (see VARIANTS) and checks, on the fp32 graphs, that decoding with
decoder_with_past and its cache gives the logits a full decoder_model run gives.
"""

import argparse
import hashlib
import math
import os
import shutil
import sys
import tempfile

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper

OPSET = 17
INLINE_BYTES = 1024  # the export keeps initializers up to 1 KiB inline
EOS, PAD = 2, 1


class Config:
    def __init__(self, **kw):
        self.d = 48
        self.heads = 4
        self.head_dim = 12
        self.ffn = 96
        self.enc_layers = 2
        self.dec_layers = 2
        self.src_vocab = 64
        self.tgt_vocab = 64
        self.src_positions = 256
        self.tgt_positions = 256
        self.output_scale = 0.32
        self.eos_bias = 1.6
        self.ban_eos = False          # the end-of-sentence logit is -inf, so no hypothesis can end
        self.allowed = None           # the only tokens with a finite logit, so fewer beams can go on
        self.first_eos_boost = 0.0    # added to decoder_model's end-of-sentence logit only
        # Graph changes for the tests of models the reader must refuse.
        self.past_layers = None       # decoder_with_past's own layer count
        self.past_heads = None        # heads and head size decoder_with_past declares and uses
        self.drop_past_input = None   # a past_key_values input decoder_with_past lacks
        self.ids_type = TensorProto.INT64
        self.present_twice = False    # present.N.decoder.key gets the new key twice
        self.flat_logits = False      # decoder_model's logits [batch, vocab], shape left out
        self.no_shapes = False        # decoder_model's presents declared without a shape
        self.symbolic_heads = False   # decoder_with_past's past inputs with symbolic heads
        self.first_vocab = None       # decoder_model's own vocabulary, declared symbolic
        # Slow encoders for the cancellation tests. slow_run: heavy passes over a 64-way batch, so
        # a Run takes seconds in a few hundred nodes (the terminate flag is read between nodes).
        # slow_load: thousands of light nodes, so the load takes seconds.
        self.slow_run = 0
        self.slow_load = 0
        self.quantize = True
        for key, value in kw.items():
            setattr(self, key, value)


# name -> settings. "toy" ends most outputs within 30 tokens, "noeos" never chooses the end until
# the cap forces it, "eosinf" can't end at all (logit -inf), "narrow" allows 4 tokens, so fewer
# beams than asked go on, "symbolic" leaves the head shape to the first step, "eosfirst" makes the
# end the best first token by far (decoder_model only), so only the minimum length keeps a
# reading from being empty, "eosalways" makes it the best token of every step by far (both
# decoders), so a reading ends the first step its minimum length allows; the rest are the refused
# shapes and the slow encoders.
VARIANTS = {
    "toy": Config(),
    "noeos": Config(eos_bias=-40.0),
    "eosinf": Config(ban_eos=True),
    "narrow": Config(allowed=(2, 7, 9, 11), eos_bias=-2.5),
    "symbolic": Config(no_shapes=True, symbolic_heads=True),
    "eosfirst": Config(first_eos_boost=8.0),
    "eosalways": Config(eos_bias=12.0),
    "layers3": Config(past_layers=3),
    "heads": Config(past_heads=(6, 8)),
    "missingpast": Config(drop_past_input="past_key_values.1.encoder.value"),
    "int32ids": Config(ids_type=TensorProto.INT32),
    "presenttwice": Config(present_twice=True),
    "flatlogits": Config(flat_logits=True),
    "vocab65": Config(first_vocab=65),
    "slowrun": Config(slow_run=300, quantize=False),
    "slowload": Config(slow_load=800, quantize=False),
}


def sinusoid(positions, dim):
    table = np.zeros((positions, dim), dtype=np.float32)
    position = np.arange(positions, dtype=np.float64)[:, None]
    rate = np.exp(-math.log(10000.0) * np.arange(0, dim, 2, dtype=np.float64) / dim)
    table[:, 0::2] = np.sin(position * rate)
    table[:, 1::2] = np.cos(position * rate)
    return table


def make_weights(cfg, seed):
    rng = np.random.default_rng(seed)
    w = {}

    def normal(name, shape, scale):
        w[name] = (rng.standard_normal(shape) * scale).astype(np.float32)

    def norm(name, dim):
        w[name + ".weight"] = (1.0 + 0.1 * rng.standard_normal(dim)).astype(np.float32)
        w[name + ".bias"] = (0.05 * rng.standard_normal(dim)).astype(np.float32)

    def layer(prefix, cross, d, f):
        attns = ["self_attn"] + (["encoder_attn"] if cross else [])
        for attn in attns:
            for proj in ("q_proj", "k_proj", "v_proj", "out_proj"):
                normal(f"{prefix}{attn}.{proj}.weight", (d, d), 1.0 / math.sqrt(d))
                normal(f"{prefix}{attn}.{proj}.bias", (d,), 0.02)
            norm(f"{prefix}{attn}_layer_norm", d)
        normal(prefix + "fc1.weight", (d, f), 1.0 / math.sqrt(d))
        normal(prefix + "fc1.bias", (f,), 0.02)
        normal(prefix + "fc2.weight", (f, d), 1.0 / math.sqrt(f))
        normal(prefix + "fc2.bias", (d,), 0.02)
        norm(prefix + "final_layer_norm", d)

    d = cfg.d
    normal("encoder.embed_tokens.weight", (cfg.src_vocab, d), 1.0)
    w["encoder.embed_positions.weight"] = sinusoid(cfg.src_positions, d)
    for i in range(cfg.enc_layers):
        layer(f"encoder.layers.{i}.", False, d, cfg.ffn)
    norm("encoder.layer_norm", d)
    normal("decoder.embed_tokens.weight", (cfg.tgt_vocab, d), 1.0)
    w["decoder.embed_positions.weight"] = sinusoid(cfg.tgt_positions, d)
    for i in range(max(cfg.dec_layers, cfg.past_layers or 0)):
        layer(f"decoder.layers.{i}.", True, d, cfg.ffn)
    norm("decoder.layer_norm", d)
    if cfg.slow_run:
        normal("encoder.slow_up.weight", (d, 256), 1.0 / math.sqrt(d))
        normal("encoder.slow_down.weight", (256, d), 0.01)
    if cfg.first_vocab:
        normal("lm_head_first.weight", (d, cfg.first_vocab), cfg.output_scale)
        normal("lm_head_first.bias", (cfg.first_vocab,), 0.3)
    normal("lm_head.weight", (d, cfg.tgt_vocab), cfg.output_scale)
    bias = (0.3 * rng.standard_normal(cfg.tgt_vocab)).astype(np.float32)
    bias[EOS] = np.float32(cfg.eos_bias)
    w["lm_head.bias"] = bias
    return w


class Builder:
    """A graph under construction: nodes, initializers (shared by name), inputs and outputs."""

    def __init__(self, scope, weights):
        self.scope = scope
        self.weights = weights
        self.nodes = []
        self.initializers = {}
        self.count = 0
        self.inputs = []
        self.outputs = []

    def fresh(self, base):
        self.count += 1
        return f"/{self.scope}/{base}_{self.count}"

    def op(self, op_type, inputs, out=None, **attrs):
        name = out or self.fresh(op_type)
        self.nodes.append(helper.make_node(op_type, inputs, [name], name=self.fresh(op_type + "_node"), **attrs))
        return name

    def weight(self, name):
        if name not in self.initializers:
            self.initializers[name] = numpy_helper.from_array(self.weights[name], name)
        return name

    def const(self, array):
        name = self.fresh("const")
        self.initializers[name] = numpy_helper.from_array(np.asarray(array), name)
        return name

    def i64(self, values):
        return self.const(np.array(values, dtype=np.int64))

    def f32(self, value):
        return self.const(np.array(value, dtype=np.float32))

    def model(self, check=True):
        """The model; `check` False for a graph that leaves an output's shape out on purpose, which
        ONNX's checker refuses and ONNX Runtime takes."""
        graph = helper.make_graph(self.nodes, self.scope, self.inputs, self.outputs, list(self.initializers.values()))
        model = helper.make_model(graph, opset_imports=[helper.make_opsetid("", OPSET)], producer_name="satori-toy")
        model.ir_version = 8
        if check:
            onnx.checker.check_model(model)
        return model


def linear(b, x, name):
    return b.op("Add", [b.op("MatMul", [x, b.weight(name + ".weight")]), b.weight(name + ".bias")])


def layer_norm(b, x, name):
    return b.op("LayerNormalization", [x, b.weight(name + ".weight"), b.weight(name + ".bias")], axis=-1, epsilon=1e-5)


def split_heads(b, x, heads, head_dim):
    return b.op("Transpose", [b.op("Reshape", [x, b.i64([0, 0, heads, head_dim])])], perm=[0, 2, 1, 3])


def merge_heads(b, x, d):
    return b.op("Reshape", [b.op("Transpose", [x], perm=[0, 2, 1, 3]), b.i64([0, 0, d])])


def attend(b, q, k, v, bias, head_dim):
    scores = b.op("Mul", [b.op("MatMul", [q, b.op("Transpose", [k], perm=[0, 1, 3, 2])]), b.f32(1.0 / math.sqrt(head_dim))])
    if bias is not None:
        scores = b.op("Add", [scores, bias])
    return b.op("MatMul", [b.op("Softmax", [scores], axis=-1), v])


def mask_bias(b, mask):
    """[batch, len] ones and zeros -> an additive [batch, 1, 1, len] bias."""
    keep = b.op("Cast", [mask], to=TensorProto.FLOAT)
    blocked = b.op("Mul", [b.op("Sub", [b.f32(1.0), keep]), b.f32(-1e9)])
    return b.op("Unsqueeze", [blocked, b.i64([1, 2])])


def embed(b, ids, table, positions, start):
    """Token and sinusoidal position embeddings; positions start at `start` (a scalar int64)."""
    tokens = b.op("Mul", [b.op("Gather", [b.weight(table), ids], axis=0), b.f32(math.sqrt(b.d))])
    length = b.op("Gather", [b.op("Shape", [ids]), b.const(np.array(1, dtype=np.int64))], axis=0)
    index = b.op("Range", [start, b.op("Add", [start, length]), b.const(np.array(1, dtype=np.int64))])
    return b.op("Add", [tokens, b.op("Gather", [b.weight(positions), index], axis=0)])


def ffn(b, x, prefix):
    h = layer_norm(b, x, prefix + "final_layer_norm")
    h = linear(b, b.op("Relu", [linear(b, h, prefix + "fc1")]), prefix + "fc2")
    return b.op("Add", [x, h])


def ids_input(cfg, name, length):
    return helper.make_tensor_value_info(name, cfg.ids_type if name == "input_ids" else TensorProto.INT64,
                                         ["batch_size", length])


def build_encoder(cfg, weights):
    b = Builder("encoder", weights)
    b.d = cfg.d
    b.inputs = [ids_input(cfg, "input_ids", "encoder_sequence_length"),
                ids_input(cfg, "attention_mask", "encoder_sequence_length")]
    ids = "input_ids" if cfg.ids_type == TensorProto.INT64 else b.op("Cast", ["input_ids"], to=TensorProto.INT64)
    x = embed(b, ids, "encoder.embed_tokens.weight", "encoder.embed_positions.weight",
              b.const(np.array(0, dtype=np.int64)))
    bias = mask_bias(b, "attention_mask")
    for i in range(cfg.enc_layers):
        p = f"encoder.layers.{i}."
        h = layer_norm(b, x, p + "self_attn_layer_norm")
        q, k, v = (split_heads(b, linear(b, h, p + f"self_attn.{n}"), cfg.heads, cfg.head_dim)
                   for n in ("q_proj", "k_proj", "v_proj"))
        x = b.op("Add", [x, linear(b, merge_heads(b, attend(b, q, k, v, bias, cfg.head_dim), cfg.d), p + "self_attn.out_proj")])
        x = ffn(b, x, p)
    if cfg.slow_run:
        wide = b.op("Expand", [b.op("MatMul", [x, b.weight("encoder.slow_up.weight")]), b.i64([64, 1, 1])])
        for _ in range(cfg.slow_run):
            scores = b.op("Softmax", [b.op("Mul", [b.op("MatMul", [wide, b.op("Transpose", [wide], perm=[0, 2, 1])]),
                                                   b.f32(1.0 / 256)])], axis=-1)
            wide = b.op("Add", [wide, b.op("Mul", [b.op("MatMul", [scores, wide]), b.f32(1e-3)])])
        back = b.op("ReduceMean", [wide], axes=[0], keepdims=1)
        x = b.op("Add", [x, b.op("MatMul", [back, b.weight("encoder.slow_down.weight")])])
    for _ in range(cfg.slow_load):
        scores = b.op("Softmax", [b.op("Mul", [b.op("MatMul", [x, b.op("Transpose", [x], perm=[0, 2, 1])]),
                                               b.f32(1.0 / cfg.d)])], axis=-1)
        x = b.op("Add", [x, b.op("Mul", [b.op("MatMul", [scores, x]), b.f32(1e-3)])])
    b.op("Identity", [layer_norm(b, x, "encoder.layer_norm")], out="last_hidden_state")
    b.outputs = [helper.make_tensor_value_info("last_hidden_state", TensorProto.FLOAT,
                                               ["batch_size", "encoder_sequence_length", cfg.d])]
    return b.model()


def decoder_layer(b, cfg, i, x, self_k, self_v, cross_k, cross_v, self_bias, cross_bias, heads, head_dim):
    """One pre-norm decoder layer over given key/value tensors; returns the new hidden states."""
    p = f"decoder.layers.{i}."
    h = layer_norm(b, x, p + "self_attn_layer_norm")
    q = split_heads(b, linear(b, h, p + "self_attn.q_proj"), heads, head_dim)
    x = b.op("Add", [x, linear(b, merge_heads(b, attend(b, q, self_k, self_v, self_bias, head_dim), cfg.d),
                                p + "self_attn.out_proj")])
    h = layer_norm(b, x, p + "encoder_attn_layer_norm")
    q = split_heads(b, linear(b, h, p + "encoder_attn.q_proj"), heads, head_dim)
    x = b.op("Add", [x, linear(b, merge_heads(b, attend(b, q, cross_k, cross_v, cross_bias, head_dim), cfg.d),
                                p + "encoder_attn.out_proj")])
    return ffn(b, x, p)


def logits_of(b, cfg, x, flat=False, head="lm_head", eos_boost=0.0):
    h = layer_norm(b, x, "decoder.layer_norm")
    logits = linear(b, h, head)
    if eos_boost:
        boost = np.zeros(cfg.tgt_vocab, dtype=np.float32)
        boost[EOS] = eos_boost
        logits = b.op("Add", [logits, b.const(boost)])
    if cfg.ban_eos or cfg.allowed:
        # After the projection, not in its bias: ONNX Runtime's fused int8 MatMul with a bias gives
        # -FLT_MAX for a -inf bias, and the test needs a true -inf.
        banned = np.zeros(cfg.tgt_vocab, dtype=bool)
        if cfg.ban_eos:
            banned[EOS] = True
        if cfg.allowed:
            banned[:] = True
            banned[list(cfg.allowed)] = False
        logits = b.op("Where", [b.const(banned), b.f32(-math.inf), logits])
    if flat:
        logits = b.op("Reshape", [logits, b.i64([-1, cfg.tgt_vocab])])
    b.op("Identity", [logits], out="logits")


def present_info(cfg, name, length, declared=True):
    shape = ["batch_size", cfg.heads, length, cfg.head_dim] if declared else None
    return helper.make_tensor_value_info(name, TensorProto.FLOAT, shape)


def build_decoder(cfg, weights):
    b = Builder("decoder", weights)
    b.d = cfg.d
    b.inputs = [ids_input(cfg, "input_ids", "decoder_sequence_length"),
                ids_input(cfg, "encoder_attention_mask", "encoder_sequence_length"),
                helper.make_tensor_value_info("encoder_hidden_states", TensorProto.FLOAT,
                                              ["batch_size", "encoder_sequence_length", cfg.d])]
    ids = "input_ids" if cfg.ids_type == TensorProto.INT64 else b.op("Cast", ["input_ids"], to=TensorProto.INT64)
    x = embed(b, ids, "decoder.embed_tokens.weight", "decoder.embed_positions.weight",
              b.const(np.array(0, dtype=np.int64)))
    # A causal bias over the decoder's own positions: [len, len], -1e9 above the diagonal.
    length = b.op("Gather", [b.op("Shape", [ids]), b.const(np.array(1, dtype=np.int64))], axis=0)
    steps = b.op("Range", [b.const(np.array(0, dtype=np.int64)), length, b.const(np.array(1, dtype=np.int64))])
    later = b.op("Greater", [b.op("Unsqueeze", [steps, b.i64([0])]), b.op("Unsqueeze", [steps, b.i64([1])])])
    causal = b.op("Where", [later, b.f32(-1e9), b.f32(0.0)])
    cross_bias = mask_bias(b, "encoder_attention_mask")
    outputs = []
    for i in range(cfg.dec_layers):
        p = f"decoder.layers.{i}."
        h = layer_norm(b, x, p + "self_attn_layer_norm")
        k = split_heads(b, linear(b, h, p + "self_attn.k_proj"), cfg.heads, cfg.head_dim)
        v = split_heads(b, linear(b, h, p + "self_attn.v_proj"), cfg.heads, cfg.head_dim)
        ck = split_heads(b, linear(b, "encoder_hidden_states", p + "encoder_attn.k_proj"), cfg.heads, cfg.head_dim)
        cv = split_heads(b, linear(b, "encoder_hidden_states", p + "encoder_attn.v_proj"), cfg.heads, cfg.head_dim)
        for value, part, length_name in ((k, "decoder.key", "decoder_sequence_length"),
                                         (v, "decoder.value", "decoder_sequence_length"),
                                         (ck, "encoder.key", "encoder_sequence_length"),
                                         (cv, "encoder.value", "encoder_sequence_length")):
            b.op("Identity", [value], out=f"present.{i}.{part}")
            outputs.append(present_info(cfg, f"present.{i}.{part}", length_name, not cfg.no_shapes))
        x = decoder_layer(b, cfg, i, x, k, v, ck, cv, causal, cross_bias, cfg.heads, cfg.head_dim)
    logits_of(b, cfg, x, cfg.flat_logits, "lm_head_first" if cfg.first_vocab else "lm_head", cfg.first_eos_boost)
    logits_info = (helper.make_tensor_value_info("logits", TensorProto.FLOAT, None) if cfg.flat_logits else
                   helper.make_tensor_value_info("logits", TensorProto.FLOAT,
                                                 ["batch_size", "decoder_sequence_length",
                                                  "vocab" if cfg.first_vocab else cfg.tgt_vocab]))
    b.outputs = [logits_info] + outputs
    return b.model(check=not (cfg.no_shapes or cfg.flat_logits))


def build_decoder_with_past(cfg, weights):
    layers = cfg.past_layers or cfg.dec_layers
    heads, head_dim = cfg.past_heads or (cfg.heads, cfg.head_dim)
    b = Builder("decoder_with_past", weights)
    b.d = cfg.d
    b.inputs = [ids_input(cfg, "input_ids", "decoder_sequence_length"),
                ids_input(cfg, "encoder_attention_mask", "encoder_sequence_length")]
    head_dims = ("heads", "head_size") if cfg.symbolic_heads else (heads, head_dim)
    for i in range(layers):
        for part, length_name in (("decoder.key", "past_decoder_sequence_length"),
                                  ("decoder.value", "past_decoder_sequence_length"),
                                  ("encoder.key", "encoder_sequence_length"),
                                  ("encoder.value", "encoder_sequence_length")):
            name = f"past_key_values.{i}.{part}"
            if name != cfg.drop_past_input:
                b.inputs.append(helper.make_tensor_value_info(
                    name, TensorProto.FLOAT, ["batch_size", head_dims[0], length_name, head_dims[1]]))
    ids = "input_ids" if cfg.ids_type == TensorProto.INT64 else b.op("Cast", ["input_ids"], to=TensorProto.INT64)
    past_len = b.op("Gather", [b.op("Shape", ["past_key_values.0.decoder.key"]), b.const(np.array(2, dtype=np.int64))],
                    axis=0)
    x = embed(b, ids, "decoder.embed_tokens.weight", "decoder.embed_positions.weight", past_len)
    cross_bias = mask_bias(b, "encoder_attention_mask")
    outputs = []
    for i in range(layers):
        p = f"decoder.layers.{i}."
        h = layer_norm(b, x, p + "self_attn_layer_norm")
        k = split_heads(b, linear(b, h, p + "self_attn.k_proj"), heads, head_dim)
        v = split_heads(b, linear(b, h, p + "self_attn.v_proj"), heads, head_dim)
        key = b.op("Concat", [f"past_key_values.{i}.decoder.key", k] + ([k] if cfg.present_twice else []), axis=2)
        value = b.op("Concat", [f"past_key_values.{i}.decoder.value", v] + ([v] if cfg.present_twice else []), axis=2)
        cross_k = f"past_key_values.{i}.encoder.key"
        cross_v = f"past_key_values.{i}.encoder.value"
        if cross_v == cfg.drop_past_input:
            cross_v = cross_k
        for tensor, part, length_name in ((key, "decoder.key", "past_decoder_sequence_length_plus_1"),
                                          (value, "decoder.value", "past_decoder_sequence_length_plus_1"),
                                          (cross_k, "encoder.key", "encoder_sequence_length"),
                                          (cross_v, "encoder.value", "encoder_sequence_length")):
            b.op("Identity", [tensor], out=f"present.{i}.{part}")
            outputs.append(helper.make_tensor_value_info(
                f"present.{i}.{part}", TensorProto.FLOAT, ["batch_size", head_dims[0], length_name, head_dims[1]]))
        x = decoder_layer(b, cfg, i, x, key, value, cross_k, cross_v, None, cross_bias, heads, head_dim)
    logits_of(b, cfg, x)
    b.outputs = [helper.make_tensor_value_info("logits", TensorProto.FLOAT,
                                               ["batch_size", "decoder_sequence_length", cfg.tgt_vocab])] + outputs
    return b.model()


def quantize(model, scratch, name):
    """onnxruntime's quantize_dynamic as the export's src/04_quantize_int8.py calls it."""
    from onnxruntime.quantization import QuantType, quantize_dynamic

    src = os.path.join(scratch, name + ".fp32.onnx")
    dst = os.path.join(scratch, name + ".int8.onnx")
    onnx.save_model(model, src)
    quantize_dynamic(model_input=src, model_output=dst, weight_type=QuantType.QInt8, per_channel=True,
                     extra_options={"DefaultTensorType": TensorProto.FLOAT})
    return onnx.load(dst)


def raw_bytes(tensor):
    return numpy_helper.to_array(tensor).tobytes()


def make_inline(tensor, raw):
    for field in ("float_data", "int32_data", "string_data", "int64_data", "double_data", "uint64_data"):
        tensor.ClearField(field)
    del tensor.external_data[:]
    tensor.data_location = TensorProto.DEFAULT
    tensor.raw_data = raw


def make_external(tensor, location, offset, length):
    """As the export's _assign_external_tensor: the bytes live at [offset, offset + length) of location."""
    for field in ("float_data", "int32_data", "string_data", "int64_data", "double_data", "uint64_data", "raw_data"):
        tensor.ClearField(field)
    del tensor.external_data[:]
    tensor.data_location = TensorProto.EXTERNAL
    for key, value in (("location", location), ("offset", str(offset)), ("length", str(length))):
        entry = tensor.external_data.add()
        entry.key = key
        entry.value = value


def save_bundle(out_dir, encoder, decoder, decoder_with_past):
    os.makedirs(out_dir, exist_ok=True)
    onnx.save_model(encoder, os.path.join(out_dir, "encoder_model.onnx"), save_as_external_data=True,
                    all_tensors_to_one_file=True, location="encoder_model.onnx.data", size_threshold=INLINE_BYTES)
    # The two decoders' weights in one file, each distinct block once (share_decoder_external_data).
    shared = bytearray()
    blocks = {}
    for model in (decoder, decoder_with_past):
        for tensor in model.graph.initializer:
            raw = raw_bytes(tensor)
            if len(raw) <= INLINE_BYTES:
                make_inline(tensor, raw)
                continue
            digest = hashlib.sha256(raw).hexdigest()
            if digest not in blocks:
                blocks[digest] = (len(shared), len(raw))
                shared.extend(raw)
            offset, length = blocks[digest]
            make_external(tensor, "decoder_shared.onnx.data", offset, length)
    with open(os.path.join(out_dir, "decoder_shared.onnx.data"), "wb") as f:
        f.write(shared)
    onnx.save_model(decoder, os.path.join(out_dir, "decoder_model.onnx"))
    onnx.save_model(decoder_with_past, os.path.join(out_dir, "decoder_with_past_model.onnx"))


def check_cache(cfg, encoder, decoder, decoder_with_past):
    """The fp32 graphs: decoder_with_past with its cache must give a full decoder_model run's logits."""
    import onnxruntime as ort

    sessions = [ort.InferenceSession(m.SerializeToString(), providers=["CPUExecutionProvider"])
                for m in (encoder, decoder, decoder_with_past)]
    rng = np.random.default_rng(7)
    src = rng.integers(4, cfg.src_vocab, size=(2, 9)).astype(np.int64)
    mask = np.ones_like(src)
    hidden = sessions[0].run(["last_hidden_state"], {"input_ids": src, "attention_mask": mask})[0]
    tokens = rng.integers(4, cfg.tgt_vocab, size=(2, 6)).astype(np.int64)
    full = sessions[1].run(["logits"], {"input_ids": tokens, "encoder_attention_mask": mask,
                                       "encoder_hidden_states": hidden})[0]
    names = [o.name for o in sessions[1].get_outputs()]
    first = dict(zip(names, sessions[1].run(None, {"input_ids": tokens[:, :1], "encoder_attention_mask": mask,
                                                    "encoder_hidden_states": hidden})))
    worst = float(np.max(np.abs(first["logits"][:, 0] - full[:, 0])))
    past = {f"past_key_values.{i}.{part}": first[f"present.{i}.{part}"]
            for i in range(cfg.dec_layers) for part in ("decoder.key", "decoder.value", "encoder.key", "encoder.value")}
    step_names = [o.name for o in sessions[2].get_outputs()]
    for t in range(1, tokens.shape[1]):
        out = dict(zip(step_names, sessions[2].run(None, {"input_ids": tokens[:, t:t + 1],
                                                           "encoder_attention_mask": mask, **past})))
        worst = max(worst, float(np.max(np.abs(out["logits"][:, 0] - full[:, t]))))
        past = {f"past_key_values.{i}.{part}": out[f"present.{i}.{part}"]
                for i in range(cfg.dec_layers) for part in ("decoder.key", "decoder.value", "encoder.key", "encoder.value")}
    if not worst < 1e-4:
        sys.exit(f"cached decoding differs from a full run by {worst}")
    return worst


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("out")
    parser.add_argument("--seed", type=int, default=20261019)
    parser.add_argument("--only", nargs="*", default=None)
    args = parser.parse_args()
    names = args.only or list(VARIANTS)
    with tempfile.TemporaryDirectory() as scratch:
        for name in names:
            cfg = VARIANTS[name]
            weights = make_weights(cfg, args.seed)
            encoder = build_encoder(cfg, weights)
            decoder = build_decoder(cfg, weights)
            decoder_with_past = build_decoder_with_past(cfg, weights)
            if name == "toy":
                print(f"toy: cached decoding matches a full run within {check_cache(cfg, encoder, decoder, decoder_with_past):.2e}")
            if cfg.quantize:
                encoder = quantize(encoder, scratch, name + "-encoder")
                decoder = quantize(decoder, scratch, name + "-decoder")
                decoder_with_past = quantize(decoder_with_past, scratch, name + "-decoder_with_past")
            folder = os.path.join(args.out, name)
            shutil.rmtree(folder, ignore_errors=True)
            save_bundle(folder, encoder, decoder, decoder_with_past)
            ops = sorted({n.op_type for m in (encoder, decoder, decoder_with_past) for n in m.graph.node})
            print(f"{name}: {', '.join(ops)}")


if __name__ == "__main__":
    main()
