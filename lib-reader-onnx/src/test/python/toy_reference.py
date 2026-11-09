#!/usr/bin/env python3
"""The reference the reader's C++ is held to: the same beam search over the toy graphs, with
onnxruntime from pypi (run_host_tests.sh).

It runs each graph as the reader does, so ONNX Runtime sees the same tensors: the same session
settings (D3, D4: 4 threads, sequential, all graph optimizations, no memory pattern, prepacking
off for decoder_model only), decoder_model once with the start token at batch 1, then
decoder_with_past with the active beams as its batch in the reader's order, the cross-attention
cache of the first step fed to every step and the self-attention cache gathered by parent. The
scores are computed in Python floats (IEEE double) in the order the C++ sums them
(beam_search.h), so the tokens and the mean log-probability must come out identical.

Usage: python3 -I toy_reference.py <models dir> <expected.tsv>
Writes one line per case: case, variant, beam, max_new, source ids, output ids, mean
log-probability (repr), capped, decoder runs, minimum length.
"""

import argparse
import math
import os
import sys

import numpy as np
import onnxruntime as ort

EOS, PAD, START = 2, 1, 2
MAX_SOURCE, MAX_TARGET = 256, 256
THREADS = 4
NEG_INF = -math.inf


def session(path, prepack):
    options = ort.SessionOptions()
    options.intra_op_num_threads = THREADS
    options.inter_op_num_threads = 1
    options.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
    options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    options.enable_mem_pattern = False
    options.enable_cpu_mem_arena = True
    if not prepack:
        options.add_session_config_entry("session.disable_prepacking", "1")
    return ort.InferenceSession(path, options, providers=["CPUExecutionProvider"])


class Toy:
    def __init__(self, folder):
        self.encoder = session(os.path.join(folder, "encoder_model.onnx"), True)
        self.decoder = session(os.path.join(folder, "decoder_model.onnx"), False)
        self.step = session(os.path.join(folder, "decoder_with_past_model.onnx"), True)
        self.first_names = [o.name for o in self.decoder.get_outputs()]
        self.layers = sum(1 for n in self.first_names if n.endswith(".decoder.key"))
        self.shrink = ort.RunOptions()
        self.shrink.add_run_config_entry("memory.enable_memory_arena_shrinkage", "cpu:0")
        self.plain = ort.RunOptions()


def log_softmax_terms(row):
    """lse as beam_search.cpp computes it: the maximum, then exp(x - max) summed in order."""
    peak = NEG_INF
    for x in row:
        if math.isnan(x) or x == math.inf:
            raise ValueError("logits not finite")
        if x > peak:
            peak = x
    if peak == NEG_INF:
        return None
    total = 0.0
    for x in row:
        total += math.exp(x - peak)
    return peak + math.log(total)


class Search:
    """beam_search.h's BeamSearch, line for line, with fairseq's min_len 1 as its min_tokens unless a
    case asks for another (the debug worst case holds the end back to the cap)."""

    def __init__(self, beam, max_tokens, vocab, min_tokens=1):
        self.beam, self.max_tokens, self.vocab, self.min_tokens = beam, max_tokens, vocab, min_tokens
        self.generated = 0
        self.beams = [([], 0.0)]
        self.finished = []
        self.last_beams = []
        self.parents, self.next_tokens = [], []
        self.done = False

    def step(self, rows):
        last = self.generated + 1 >= self.max_tokens
        may_end = last or self.generated >= self.min_tokens
        candidates = []
        for r, row in enumerate(rows):
            lse = log_softmax_terms(row)
            if lse is None:
                continue
            base = self.beams[r][1]
            if last:
                if row[EOS] != NEG_INF:
                    candidates.append((base + (row[EOS] - lse), r * self.vocab + EOS))
                continue
            for v, x in enumerate(row):
                if v == PAD or x == NEG_INF or (v == EOS and not may_end):
                    continue
                candidates.append((base + (x - lse), r * self.vocab + v))
        candidates.sort(key=lambda c: (-c[0], c[1]))
        top = candidates[:2 * self.beam]
        nxt, self.parents, self.next_tokens = [], [], []
        for rank, (score, flat) in enumerate(top):
            r, token = divmod(flat, self.vocab)
            tokens = self.beams[r][0]
            if token == EOS:
                if rank < self.beam and len(self.finished) < self.beam:
                    self.finished.append((tokens + [EOS], score))
                continue
            if len(nxt) < self.beam:
                nxt.append((tokens + [token], score))
                self.parents.append(r)
                self.next_tokens.append(token)
        self.generated += 1
        if not nxt:
            self.last_beams = self.beams
        self.beams = nxt
        self.done = len(self.finished) >= self.beam or last or not self.beams

    def result(self):
        pool = self.finished if self.finished else self.last_beams
        if not pool:
            return [], NEG_INF, True
        best, best_mean = 0, NEG_INF
        for i, (tokens, score) in enumerate(pool):
            mean = NEG_INF if not tokens else score / len(tokens)
            if i == 0 or mean > best_mean:
                best, best_mean = i, mean
        tokens = pool[best][0]
        keep = tokens[:-1] if self.finished and tokens and tokens[-1] == EOS else tokens
        capped = not self.finished or len(tokens) >= self.max_tokens
        return keep, best_mean, capped


def translate(toy, ids, beam, max_new, min_new=1):
    count = len(ids)
    pieces = max(count - 3, 0)
    cap = min(2 * pieces + 10, MAX_TARGET - 1)
    if max_new > 0:
        cap = min(cap, max_new)
    source = np.array([ids], dtype=np.int64)
    mask = np.ones((1, count), dtype=np.int64)
    hidden = toy.encoder.run(["last_hidden_state"], {"input_ids": source, "attention_mask": mask}, toy.shrink)[0]
    first = dict(zip(toy.first_names, toy.decoder.run(
        toy.first_names, {"input_ids": np.array([[START]], dtype=np.int64), "encoder_attention_mask": mask,
                          "encoder_hidden_states": hidden}, toy.shrink)))
    vocab = first["logits"].shape[2]
    cross = {f"{i}.{p}": first[f"present.{i}.encoder.{p}"] for i in range(toy.layers) for p in ("key", "value")}
    search = Search(beam, cap, vocab, min_new)
    search.step([first["logits"][0, 0].astype(np.float64).tolist()])
    steps = 1
    past = {f"{i}.{p}": first[f"present.{i}.decoder.{p}"] for i in range(toy.layers) for p in ("key", "value")}
    parents = np.array(search.parents, dtype=np.int64)
    past = {k: v[parents] for k, v in past.items()} if not search.done else past
    step_names = ["logits"] + [f"present.{i}.decoder.{p}" for i in range(toy.layers) for p in ("key", "value")]
    while not search.done:
        rows = len(search.beams)
        feed = {"input_ids": np.array(search.next_tokens, dtype=np.int64).reshape(rows, 1),
                "encoder_attention_mask": np.ones((rows, count), dtype=np.int64)}
        for i in range(toy.layers):
            for p in ("key", "value"):
                feed[f"past_key_values.{i}.decoder.{p}"] = past[f"{i}.{p}"]
                feed[f"past_key_values.{i}.encoder.{p}"] = np.repeat(cross[f"{i}.{p}"], rows, axis=0)
        out = dict(zip(step_names, toy.step.run(step_names, feed, toy.plain)))
        steps += 1
        search.step([out["logits"][r, 0].astype(np.float64).tolist() for r in range(rows)])
        if search.done:
            break
        parents = np.array(search.parents, dtype=np.int64)
        past = {f"{i}.{p}": out[f"present.{i}.decoder.{p}"][parents] for i in range(toy.layers) for p in ("key", "value")}
    tokens, mean, capped = search.result()
    return tokens, mean, capped, steps


def cases(rng):
    """(variant, beam, max_new, source ids, min_new): short sources, the source cap, the output caps,
    and the minimum lengths; min_new is fairseq's 1 but in the last cases."""
    def source(pieces):
        return [4, 5] + [int(x) for x in rng.integers(6, 64, size=pieces)] + [EOS]

    out = []
    for pieces in (1, 2, 3, 5, 8, 12, 17, 25, 40):
        ids = source(pieces)
        for beam in (1, 4):
            out.append(("toy", beam, 0, ids))
    for pieces in (4, 9, 30):
        ids = source(pieces)
        for beam in (2, 3, 8):
            out.append(("toy", beam, 0, ids))
    longest = source(MAX_SOURCE - 3)
    for beam in (1, 4):
        out.append(("toy", beam, 0, longest))
        out.append(("noeos", beam, 0, longest))
    for pieces, max_new in ((6, 1), (6, 2), (10, 5), (20, 9)):
        ids = source(pieces)
        for beam in (1, 4):
            out.append(("toy", beam, max_new, ids))
    for pieces in (3, 15):
        ids = source(pieces)
        for beam in (1, 4):
            out.append(("noeos", beam, 0, ids))
            out.append(("eosinf", beam, 0, ids))
            out.append(("symbolic", beam, 0, ids))
    for pieces in (2, 7, 20):
        ids = source(pieces)
        for beam in (1, 4, 8):
            out.append(("narrow", beam, 0, ids))
    out.append(("toy", 4, 0, [4, 5, EOS]))
    out.append(("toy", 4, 0, [EOS]))
    # Last, so the cases above keep their sources: "eosfirst", whose decoder_model makes the end the
    # best first token by far. fairseq's min_len holds it back, so the readings are not empty,
    # except with one token allowed, where the cap leaves only the end.
    for pieces in (3, 15):
        ids = source(pieces)
        for beam in (1, 4):
            out.append(("eosfirst", beam, 0, ids))
    out.append(("eosfirst", 4, 1, source(6)))
    out = [case + (1,) for case in out]
    # Last again: the minimum length a call asks for. The debug worst case (V1) holds the end back
    # to the cap's last token, so the longest source runs all 255 steps; a minimum over a lowered
    # cap leaves the cap's last token to the end all the same; and one under the cap holds back
    # "eosfirst"'s end only so far.
    for beam in (1, 4):
        out.append(("toy", beam, 0, longest, MAX_TARGET - 1))
    out.append(("toy", 4, 9, source(20), MAX_TARGET - 1))
    ids = source(12)
    for beam in (1, 4):
        out.append(("eosfirst", beam, 0, ids, 7))
    # Last once more: "eosalways", whose every step makes the end the best token by far, so a
    # reading ends the first step its minimum allows: a minimum of 1, 3 or 7 gives exactly that many
    # tokens, where "eosfirst" can't tell a minimum under the cap from fairseq's 1.
    ids = source(9)
    for beam in (1, 4):
        for min_new in (1, 3, 7):
            out.append(("eosalways", beam, 0, ids, min_new))
    return out


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("models")
    parser.add_argument("expected")
    parser.add_argument("--seed", type=int, default=31)
    args = parser.parse_args()
    toys = {}
    lines = []
    lengths = []
    for n, (variant, beam, max_new, ids, min_new) in enumerate(cases(np.random.default_rng(args.seed))):
        if variant not in toys:
            toys[variant] = Toy(os.path.join(args.models, variant))
        tokens, mean, capped, steps = translate(toys[variant], ids, beam, max_new, min_new)
        lengths.append(len(tokens))
        lines.append("\t".join([str(n), variant, str(beam), str(max_new), " ".join(map(str, ids)),
                                " ".join(map(str, tokens)), repr(mean), "1" if capped else "0", str(steps),
                                str(min_new)]))
    with open(args.expected, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    print(f"{len(lines)} cases, onnxruntime {ort.__version__}, output lengths {min(lengths)}-{max(lengths)}, "
          f"capped {sum(1 for line in lines if line.split(chr(9))[7] == '1')}")


if __name__ == "__main__":
    sys.exit(main())
