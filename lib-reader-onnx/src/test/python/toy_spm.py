#!/usr/bin/env python3
"""A toy SentencePiece model and its pieces, for the host test of the reader's tokenizer path.

The reader's real tokenizer, IndicTrans2's model.SRC, stays off this machine (its check is V0b on
the phone). This trains a small unigram model of the same kind on synthetic Devanagari, Latin and
digit text with the sentencepiece wheel (the version the submodule pins, 0.2.1), and writes the
pieces that wheel's EncodeAsPieces gives for test lines, edge cases included. The C++ build of the
same SentencePiece must give the same pieces for the same model.

Usage: python3 -I toy_spm.py <output dir>
Writes toy.model and pieces.tsv: per line the text, then its pieces, as hex of their UTF-8.
"""

import argparse
import os

import numpy as np
import sentencepiece as spm

CONSONANTS = [chr(c) for c in range(0x0915, 0x0939 + 1)]
VOWEL_SIGNS = ["", "ा", "ि", "ी", "ु", "ू", "े", "ै", "ो", "ौ"]
VIRAMA = "्"
LATIN = ["meeting", "file", "call", "office", "phone", "list", "ticket", "report", "today", "plan"]


def word(rng):
    syllables = []
    for _ in range(int(rng.integers(1, 5))):
        syllable = CONSONANTS[int(rng.integers(len(CONSONANTS)))]
        if rng.random() < 0.15:
            syllable += VIRAMA + CONSONANTS[int(rng.integers(len(CONSONANTS)))]
        syllables.append(syllable + VOWEL_SIGNS[int(rng.integers(len(VOWEL_SIGNS)))])
    return "".join(syllables)


def sentence(rng):
    words = []
    for _ in range(int(rng.integers(3, 14))):
        roll = rng.random()
        if roll < 0.12:
            words.append(LATIN[int(rng.integers(len(LATIN)))])
        elif roll < 0.18:
            words.append(str(int(rng.integers(0, 2000))))
        else:
            words.append(word(rng))
    line = " ".join(words)
    return line + ("." if rng.random() < 0.5 else "")


def test_lines(rng):
    lines = [sentence(rng) for _ in range(40)]
    lines += [
        "",
        " ",
        "   leading and trailing   ",
        "tab\tseparated\twords",
        "multiple    inner     spaces",
        word(rng) + "‍" + word(rng),
        word(rng) + "‌" + word(rng),
        "<ID1> " + word(rng) + " <ID2>",
        "12:30 3.5 1,000 2026-10-19",
        "മലയാളം letters not in training",
        "emoji \U0001F600 and 中文",
        "ＡＢＣ full width",
        "Mixed CASE Latin Words",
        "!!! ??? ... ---",
        " ".join(word(rng) for _ in range(35)),
        "".join(word(rng) for _ in range(120)),
        "x" * 300,
    ]
    return lines


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("out")
    parser.add_argument("--seed", type=int, default=11)
    args = parser.parse_args()
    os.makedirs(args.out, exist_ok=True)
    rng = np.random.default_rng(args.seed)
    corpus = [sentence(rng) for _ in range(3000)]
    prefix = os.path.join(args.out, "toy")
    spm.SentencePieceTrainer.train(sentence_iterator=iter(corpus), model_prefix=prefix, vocab_size=400,
                                   model_type="unigram", character_coverage=1.0, num_threads=1,
                                   minloglevel=2)
    processor = spm.SentencePieceProcessor(model_file=prefix + ".model")
    rows = []
    for line in test_lines(rng):
        pieces = processor.EncodeAsPieces(line)
        rows.append(line.encode("utf-8").hex() + "\t" + " ".join(p.encode("utf-8").hex() for p in pieces))
    with open(os.path.join(args.out, "pieces.tsv"), "w", encoding="ascii") as f:
        f.write("\n".join(rows) + "\n")
    print(f"toy SentencePiece {spm.__version__}: {processor.get_piece_size()} pieces, {len(rows)} test lines")


if __name__ == "__main__":
    main()
