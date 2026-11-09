#include "beam_search.h"

#include <cmath>
#include <limits>
#include <utility>

namespace satori::reader {
namespace {

constexpr double kNegInf = -std::numeric_limits<double>::infinity();

// The order of candidates: higher score first; at equal scores, the lower row x vocab + token.
// A strict total order, as no two candidates share a flat index, so the search never depends on
// how a sort breaks ties.
bool Better(double score, size_t flat, double other_score, size_t other_flat) {
    return score > other_score || (score == other_score && flat < other_flat);
}

}  // namespace

Status BeamSearch::Start(const Config& config, size_t vocab) {
    started_ = false;
    done_ = true;
    if (config.beam < 1 || config.beam > kMaxBeam || config.max_tokens < 1 || config.min_tokens < 0) {
        return Status::kBadInput;
    }
    if (config.pad_id == config.eos_id) {
        return Status::kBadInput;
    }
    if (vocab == 0 || vocab > static_cast<size_t>(std::numeric_limits<int32_t>::max())) {
        return Status::kBadModel;
    }
    if (config.eos_id < 0 || static_cast<size_t>(config.eos_id) >= vocab) {
        return Status::kBadModel;
    }
    if (config.pad_id >= 0 && static_cast<size_t>(config.pad_id) >= vocab) {
        return Status::kBadModel;
    }
    config_ = config;
    vocab_ = vocab;
    generated_ = 0;
    beams_.assign(1, Hypothesis{});
    finished_.clear();
    last_beams_.clear();
    parents_.clear();
    next_tokens_.clear();
    top_.clear();
    top_.reserve(2 * static_cast<size_t>(config.beam));
    started_ = true;
    done_ = false;
    return Status::kOk;
}

void BeamSearch::Offer(double score, size_t flat) {
    const size_t limit = 2 * static_cast<size_t>(config_.beam);
    if (top_.size() == limit) {
        const Candidate& worst = top_.back();
        if (!Better(score, flat, worst.score, worst.flat)) {
            return;
        }
        top_.pop_back();
    }
    size_t at = top_.size();
    top_.push_back(Candidate{score, flat});
    while (at > 0 && Better(score, flat, top_[at - 1].score, top_[at - 1].flat)) {
        top_[at] = top_[at - 1];
        --at;
    }
    top_[at] = Candidate{score, flat};
}

Status BeamSearch::Step(const float* logits, size_t rows, size_t vocab) {
    if (!started_ || done_ || logits == nullptr || rows != beams_.size() || vocab != vocab_) {
        return Status::kInternal;
    }
    const size_t beam = static_cast<size_t>(config_.beam);
    const size_t eos = static_cast<size_t>(config_.eos_id);
    // The last token a hypothesis may take: only end-of-sentence is left.
    const bool last = generated_ + 1 >= static_cast<size_t>(config_.max_tokens);
    // fairseq's min_len: no end before min_tokens tokens, unless the cap leaves nothing else.
    const bool may_end = last || generated_ >= static_cast<size_t>(config_.min_tokens);
    constexpr float kPosInfFloat = std::numeric_limits<float>::infinity();
    constexpr float kNegInfFloat = -std::numeric_limits<float>::infinity();

    top_.clear();
    for (size_t row = 0; row < rows; ++row) {
        const float* x = logits + row * vocab;
        double max = kNegInf;
        for (size_t v = 0; v < vocab; ++v) {
            const float value = x[v];
            if (std::isnan(value) || value == kPosInfFloat) {
                return Status::kRunFailed;
            }
            if (value > max) {
                max = value;
            }
        }
        if (max == kNegInf) {
            continue;  // the model rules out every token of this beam
        }
        double sum = 0.0;
        for (size_t v = 0; v < vocab; ++v) {
            sum += std::exp(static_cast<double>(x[v]) - max);
        }
        const double lse = max + std::log(sum);
        const double base = beams_[row].score;
        if (last) {
            if (x[eos] != kNegInfFloat) {
                Offer(base + (static_cast<double>(x[eos]) - lse), row * vocab + eos);
            }
            continue;
        }
        for (size_t v = 0; v < vocab; ++v) {
            if (static_cast<int64_t>(v) == static_cast<int64_t>(config_.pad_id) || x[v] == kNegInfFloat ||
                (v == eos && !may_end)) {
                continue;
            }
            Offer(base + (static_cast<double>(x[v]) - lse), row * vocab + v);
        }
    }

    std::vector<Hypothesis> next;
    next.reserve(beam);
    parents_.clear();
    next_tokens_.clear();
    for (size_t rank = 0; rank < top_.size(); ++rank) {
        const size_t row = top_[rank].flat / vocab;
        const size_t token = top_[rank].flat % vocab;
        if (row >= beams_.size()) {
            return Status::kInternal;
        }
        if (token == eos) {
            // fairseq finishes an end-of-sentence only among the first `beam` candidates.
            if (rank < beam && finished_.size() < beam) {
                Hypothesis finished = beams_[row];
                finished.tokens.push_back(static_cast<int32_t>(eos));
                finished.score = top_[rank].score;
                finished_.push_back(std::move(finished));
            }
            continue;
        }
        if (next.size() < beam) {
            Hypothesis extended = beams_[row];
            extended.tokens.push_back(static_cast<int32_t>(token));
            extended.score = top_[rank].score;
            next.push_back(std::move(extended));
            parents_.push_back(static_cast<int32_t>(row));
            next_tokens_.push_back(static_cast<int32_t>(token));
        }
    }
    generated_ += 1;
    if (next.empty()) {
        last_beams_ = std::move(beams_);
    }
    beams_ = std::move(next);
    done_ = finished_.size() >= beam || last || beams_.empty();
    return Status::kOk;
}

Status BeamSearch::Result(std::vector<int32_t>* tokens, double* mean_log_prob, bool* capped) const {
    if (!started_ || !done_ || tokens == nullptr || mean_log_prob == nullptr || capped == nullptr) {
        return Status::kInternal;
    }
    const bool from_finished = !finished_.empty();
    const std::vector<Hypothesis>& pool = from_finished ? finished_ : last_beams_;
    tokens->clear();
    *mean_log_prob = kNegInf;
    *capped = true;
    if (pool.empty()) {
        return Status::kOk;  // the model allowed no token at all: an empty reading
    }
    size_t best = 0;
    double best_mean = kNegInf;
    for (size_t i = 0; i < pool.size(); ++i) {
        const size_t length = pool[i].tokens.size();
        const double mean = length == 0 ? kNegInf : pool[i].score / static_cast<double>(length);
        if (i == 0 || mean > best_mean) {
            best = i;
            best_mean = mean;
        }
    }
    const Hypothesis& chosen = pool[best];
    size_t keep = chosen.tokens.size();
    if (from_finished && keep > 0 && chosen.tokens.back() == config_.eos_id) {
        keep -= 1;
    }
    tokens->assign(chosen.tokens.begin(), chosen.tokens.begin() + static_cast<std::ptrdiff_t>(keep));
    *mean_log_prob = best_mean;
    *capped = !from_finished || chosen.tokens.size() >= static_cast<size_t>(config_.max_tokens);
    return Status::kOk;
}

}  // namespace satori::reader
