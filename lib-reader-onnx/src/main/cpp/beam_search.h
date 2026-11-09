// Beam search over a decoder's logits, as fairseq's SequenceGenerator does it with its defaults
// (length penalty 1, min_len 1), the decoding IndicTrans2's published numbers come from (beam 5
// there; the reader uses 4, D2). No ONNX Runtime here: the engine feeds each step's logits and
// reads which beams go on, so the arithmetic can be checked on its own and against the Python
// reference (lib-reader-onnx/src/test/python/toy_reference.py), which does the same sums in the
// same order.
//
// One step, for the n active beams (1 at the first step, at most `beam` after):
//   1. Each beam's logits become log-probabilities in double: lse = m + log(sum exp(x - m)) with m
//      the row's maximum, summed in vocabulary order; log p(v) = x(v) - lse. A row holding NaN or
//      +inf fails the step; -inf logits are tokens the model rules out.
//   2. The padding id is never a candidate, and at the last allowed step (max_tokens - 1 tokens
//      generated) only end-of-sentence is, so every hypothesis ends within max_tokens. Before
//      min_tokens tokens are generated end-of-sentence is no candidate either (fairseq's min_len:
//      a reading is never empty), except at that last step, where the cap wins. Like fairseq, the
//      log-probabilities of the other tokens stay as they are, not renormalized without it.
//   3. A candidate's score is its beam's score plus log p(v). The best 2 x beam candidates are
//      kept, ordered by score, ties by the lower (beam row x vocabulary + token).
//   4. In that order: an end-of-sentence among the first `beam` candidates finishes its hypothesis
//      while fewer than `beam` are finished (later ones are dropped); any other token extends its
//      beam while fewer than `beam` new beams exist.
//   5. The search ends when `beam` hypotheses are finished, at the last allowed step, or when no
//      beam goes on.
// The result is the finished hypothesis with the best score per token (its end-of-sentence
// counted), ties to the one finished first; without any, the best of the last beams, marked
// capped. Its mean log-probability is that score per token (D5's floor reads it).

#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

#include "reader_status.h"

namespace satori::reader {

// The most beams the reader runs: it sizes the caches (288 KiB per beam and token on IndicTrans2).
inline constexpr int kMaxBeam = 8;

class BeamSearch {
public:
    struct Config {
        int beam = 4;
        // Generated tokens at most, the closing end-of-sentence included.
        int max_tokens = 1;
        int32_t eos_id = 2;
        // Never generated; negative when the vocabulary has none.
        int32_t pad_id = 1;
        // The fewest tokens a hypothesis has before its end-of-sentence: fairseq's min_len, whose
        // default 1 the reader keeps.
        int min_tokens = 1;
    };

    // Starts a search over a vocabulary of `vocab` tokens: one beam, no tokens, score 0.
    Status Start(const Config& config, size_t vocab);

    // One step. `logits` holds `rows` rows of `vocab` floats, row i for active beam i (in the
    // order next_tokens() gave them). kRunFailed for a row that is not finite, kInternal for a
    // step the search did not ask for.
    Status Step(const float* logits, size_t rows, size_t vocab);

    bool done() const { return done_; }
    // Tokens every active beam has generated so far: the next step's past length.
    size_t generated() const { return generated_; }
    // After a step that did not end the search: the beams that go on, in order. parents()[j] is
    // the row of beam j's parent in the step's logits, next_tokens()[j] the token it adds.
    size_t active() const { return beams_.size(); }
    const std::vector<int32_t>& parents() const { return parents_; }
    const std::vector<int32_t>& next_tokens() const { return next_tokens_; }

    // After done(): the best hypothesis's tokens without its end-of-sentence, its mean
    // log-probability (-inf when there is no token at all), and whether it reached max_tokens or
    // ended without an end-of-sentence.
    Status Result(std::vector<int32_t>* tokens, double* mean_log_prob, bool* capped) const;

private:
    struct Hypothesis {
        std::vector<int32_t> tokens;
        double score = 0.0;
    };
    struct Candidate {
        double score;
        size_t flat;  // row x vocab + token
    };

    void Offer(double score, size_t flat);

    Config config_{};
    size_t vocab_ = 0;
    size_t generated_ = 0;
    bool done_ = true;
    bool started_ = false;
    std::vector<Hypothesis> beams_;
    std::vector<Hypothesis> finished_;
    // The beams of the last step that had any, kept for a search that ends with none finished.
    std::vector<Hypothesis> last_beams_;
    std::vector<int32_t> parents_;
    std::vector<int32_t> next_tokens_;
    std::vector<Candidate> top_;
};

}  // namespace satori::reader
