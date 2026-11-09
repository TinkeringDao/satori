// Host tests of the reader's native code (src/main/cpp) on a Mac, built under ASan and UBSan by
// CMakeLists.txt here and run by run_host_tests.sh:
//
//   reader_host_test <models dir> <expected.tsv> <spm dir>
//
// <models dir> holds the toy graphs of toy_models.py (random weights, the export's inputs, outputs
// and file layout), <expected.tsv> what toy_reference.py's beam search over them gives with
// onnxruntime from pypi, <spm dir> the toy SentencePiece model of toy_spm.py and its pieces from
// the sentencepiece wheel. What is checked:
//   - every reference case gives the same tokens, mean log-probability (to the bit), capped flag and
//     decoder-run count, at beams 1 to 8, short sources, the 256-id source cap, the output caps and
//     the minimum lengths a call asks for (the debug worst case: the longest source, all 255 steps),
//     twice on one reader;
//   - the beam search and the caches on their own: ties, the minimum length, non-finite logits,
//     bounds;
//   - decoder_with_past's arena: no block the size of a cross-cache copy, as every output of a
//     step goes into the reader's buffers;
//   - bad input (the source's framing too), bad paths, graphs of the wrong shape, each a status and
//     never a crash;
//   - cancel(): before a load, during a load (early in it: in under half the load still to come),
//     during a Run (within 100 ms, the V6 bar), and a second call while one runs;
//   - the tokenizer: the wheel's pieces for every line, broken UTF-8 refused.
// It exits 1 on any failed check.

#include <spawn.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>

#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <map>
#include <memory>
#include <random>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

#include "beam_search.h"
#include "kv_cache.h"
#include "reader_engine.h"
#include "spm_tokenizer.h"

using satori::reader::BeamSearch;
using satori::reader::Dims;
using satori::reader::IsValidUtf8;
using satori::reader::KvCache;
using satori::reader::Options;
using satori::reader::Reader;
using satori::reader::Status;
using satori::reader::Translation;
using Clock = std::chrono::steady_clock;

extern char** environ;

namespace satori::reader {

// reader_engine.h befriends it for TestStepArena; the reader itself never uses it.
struct ReaderTestPeer {
    static const OrtApi* Api(const Reader& reader) { return reader.api_; }
    static const OrtSession* DecoderWithPast(const Reader& reader) { return reader.decoder_with_past_; }
    static const OrtMemoryInfo* Memory(const Reader& reader) { return reader.memory_; }
};

}  // namespace satori::reader

namespace {

int g_checks = 0;
int g_failures = 0;

#define EXPECT(condition, ...)                                                       \
    do {                                                                             \
        ++g_checks;                                                                  \
        if (!(condition)) {                                                          \
            ++g_failures;                                                            \
            std::fprintf(stderr, "FAIL line %d: %s: ", __LINE__, #condition);       \
            std::fprintf(stderr, __VA_ARGS__);                                       \
            std::fputc('\n', stderr);                                                \
        }                                                                            \
    } while (0)

const char* Name(Status status) {
    switch (status) {
        case Status::kOk: return "ok";
        case Status::kCancelled: return "cancelled";
        case Status::kBadInput: return "bad input";
        case Status::kBadModel: return "bad model";
        case Status::kLoadFailed: return "load failed";
        case Status::kRunFailed: return "run failed";
        case Status::kOutOfMemory: return "out of memory";
        case Status::kNotLoaded: return "not loaded";
        case Status::kBusy: return "busy";
        case Status::kInternal: return "internal";
    }
    return "?";
}

double Ms(Clock::time_point from, Clock::time_point to) {
    return std::chrono::duration<double, std::milli>(to - from).count();
}

std::vector<std::string> Split(const std::string& text, char separator) {
    std::vector<std::string> parts;
    std::string part;
    std::istringstream in(text);
    while (std::getline(in, part, separator)) {
        parts.push_back(part);
    }
    if (!text.empty() && text.back() == separator) {
        parts.emplace_back();
    }
    return parts;
}

std::vector<int32_t> Ints(const std::string& text) {
    std::vector<int32_t> out;
    std::istringstream in(text);
    long value = 0;
    while (in >> value) {
        out.push_back(static_cast<int32_t>(value));
    }
    return out;
}

std::string Unhex(const std::string& hex) {
    std::string out;
    for (size_t i = 0; i + 1 < hex.size(); i += 2) {
        out.push_back(static_cast<char>(std::strtol(hex.substr(i, 2).c_str(), nullptr, 16)));
    }
    return out;
}

std::string Join(const std::vector<int32_t>& ids) {
    std::string out;
    for (int32_t id : ids) {
        out += (out.empty() ? "" : " ") + std::to_string(id);
    }
    return out;
}

Options ToyOptions() {
    Options options;
    options.source_vocab = 64;
    return options;
}

std::string Graph(const std::string& folder, const char* file) { return folder + "/" + file; }

Status LoadFolder(Reader* reader, const std::string& folder, const Options& options = ToyOptions()) {
    return reader->Load(Graph(folder, "encoder_model.onnx"), Graph(folder, "decoder_model.onnx"),
                        Graph(folder, "decoder_with_past_model.onnx"), options);
}

std::vector<int32_t> Source(size_t pieces, uint32_t seed) {
    std::mt19937 random(seed);
    std::vector<int32_t> ids = {4, 5};
    for (size_t i = 0; i < pieces; ++i) {
        ids.push_back(static_cast<int32_t>(6 + random() % 58));
    }
    ids.push_back(2);
    return ids;
}

std::string TempDir() {
    char pattern[] = "/tmp/reader-host-test-XXXXXX";
    const char* made = mkdtemp(pattern);
    return made == nullptr ? std::string() : std::string(made);
}

bool WriteFile(const std::string& path, const std::string& bytes) {
    std::ofstream out(path, std::ios::binary);
    out.write(bytes.data(), static_cast<std::streamsize>(bytes.size()));
    return static_cast<bool>(out);
}

std::string ReadFile(const std::string& path) {
    std::ifstream in(path, std::ios::binary);
    std::ostringstream all;
    all << in.rdbuf();
    return all.str();
}

void TestReference(const std::string& models, const std::string& expected) {
    std::ifstream in(expected);
    EXPECT(static_cast<bool>(in), "no %s", expected.c_str());
    std::map<std::string, std::unique_ptr<Reader>> readers;
    int cases = 0;
    int matched = 0;
    int capped = 0;
    size_t longest = 0;
    // "eosfirst" without a token cap: the end is decoder_model's best first token by far, so a
    // reading that isn't empty shows the minimum length at work in the reference as well.
    int eos_first = 0;
    int eos_first_read = 0;
    // The debug worst case: the longest source with the end held back to the cap, all its steps run.
    int worst = 0;
    // "eosalways": the end is the best token of every step, so a reading has exactly its minimum's tokens.
    int eos_always = 0;
    std::map<int, int> eos_always_minima;
    std::string line;
    while (std::getline(in, line)) {
        if (line.empty()) {
            continue;
        }
        const std::vector<std::string> field = Split(line, '\t');
        EXPECT(field.size() == 10, "a reference line with %zu fields", field.size());
        if (field.size() != 10) {
            continue;
        }
        const std::string& variant = field[1];
        const int beam = std::atoi(field[2].c_str());
        const int max_new = std::atoi(field[3].c_str());
        const std::vector<int32_t> source = Ints(field[4]);
        const std::vector<int32_t> want = Ints(field[5]);
        const double mean = std::strtod(field[6].c_str(), nullptr);
        const bool want_capped = field[7] == "1";
        const int steps = std::atoi(field[8].c_str());
        const int min_new = std::atoi(field[9].c_str());
        std::unique_ptr<Reader>& reader = readers[variant];
        if (!reader) {
            reader = std::make_unique<Reader>();
            const Status status = LoadFolder(reader.get(), models + "/" + variant);
            EXPECT(status == Status::kOk, "%s: load %s: %s", variant.c_str(), Name(status), reader->last_error().c_str());
        }
        // Twice on the same reader: nothing of one segment carries over to the next.
        for (int round = 0; round < 2; ++round) {
            Translation got;
            const Status status = reader->Translate(source.data(), source.size(), beam, max_new, min_new, &got);
            const bool same = status == Status::kOk && got.ids == want && got.mean_log_prob == mean &&
                              got.capped == want_capped && got.steps == steps;
            EXPECT(same,
                   "case %s (%s, beam %d, max %d, min %d, %zu ids) round %d: %s [%s] mean %.17g capped %d steps %d; "
                   "reference [%s] mean %.17g capped %d steps %d; %s",
                   field[0].c_str(), variant.c_str(), beam, max_new, min_new, source.size(), round, Name(status),
                   Join(got.ids).c_str(), got.mean_log_prob, got.capped, got.steps, Join(want).c_str(), mean,
                   want_capped, steps, reader->last_error().c_str());
            if (round == 0) {
                ++cases;
                matched += same ? 1 : 0;
                capped += want_capped ? 1 : 0;
                longest = std::max(longest, want.size());
                if (variant == "eosfirst" && max_new == 0 && min_new == Reader::kDefaultMinTokens) {
                    ++eos_first;
                    eos_first_read += want.empty() ? 0 : 1;
                }
                if (variant == "eosalways" && max_new == 0) {
                    EXPECT(want.size() == static_cast<size_t>(min_new) && !want_capped,
                           "eosalways %s: %zu tokens with a minimum of %d", field[0].c_str(), want.size(), min_new);
                    ++eos_always;
                    ++eos_always_minima[min_new];
                }
                if (source.size() == 256 && max_new == 0 && min_new >= 255) {
                    // 254 tokens and the end forced at the 255th: every step of the cap ran.
                    EXPECT(steps == 255 && want.size() == 254 && want_capped,
                           "worst case %s: %d steps, %zu tokens, capped %d", field[0].c_str(), steps, want.size(),
                           want_capped);
                    ++worst;
                }
            }
        }
    }
    EXPECT(eos_first >= 4 && eos_first_read == eos_first, "eosfirst: %d of %d readings not empty", eos_first_read,
           eos_first);
    EXPECT(worst >= 2, "%d worst cases (the longest source, the end held back to the cap)", worst);
    EXPECT(eos_always >= 6 && eos_always_minima.size() >= 3,
           "%d eosalways cases over %zu minima (each ends where its minimum allows)", eos_always, eos_always_minima.size());
    const Dims toy = readers.count("toy") ? readers["toy"]->dims() : Dims{};
    EXPECT(toy.layers == 2 && toy.heads == 4 && toy.head_dim == 12, "toy dims %d %d %d", toy.layers, toy.heads,
           toy.head_dim);
    if (readers.count("symbolic")) {
        const Dims learnt = readers["symbolic"]->dims();
        EXPECT(learnt.heads == 4 && learnt.head_dim == 12, "symbolic: heads %d, head size %d after a step",
               learnt.heads, learnt.head_dim);
    }
    std::printf("reference: %d of %d cases identical (tokens, mean log-probability, capped, decoder runs), "
                "twice each; %d capped, longest output %zu tokens; eosfirst: %d of %d uncapped readings not empty; "
                "%d worst cases of 255 steps; eosalways: %d readings of exactly their minimum's tokens over %zu minima\n",
                matched, cases, capped, longest, eos_first_read, eos_first, worst, eos_always, eos_always_minima.size());
}

void TestBeamSearch() {
    BeamSearch search;
    const size_t vocab = 10;
    EXPECT(search.Start(BeamSearch::Config{0, 5, 2, 1}, vocab) == Status::kBadInput, "beam 0");
    EXPECT(search.Start(BeamSearch::Config{9, 5, 2, 1}, vocab) == Status::kBadInput, "beam 9");
    EXPECT(search.Start(BeamSearch::Config{4, 0, 2, 1}, vocab) == Status::kBadInput, "no tokens");
    EXPECT(search.Start(BeamSearch::Config{4, 5, 2, 2}, vocab) == Status::kBadInput, "pad = eos");
    EXPECT(search.Start(BeamSearch::Config{4, 5, 10, 1}, vocab) == Status::kBadModel, "eos outside");
    EXPECT(search.Start(BeamSearch::Config{4, 5, 2, 10}, vocab) == Status::kBadModel, "pad outside");
    EXPECT(search.Start(BeamSearch::Config{4, 5, 2, 1}, 0) == Status::kBadModel, "no vocabulary");
    EXPECT(search.Start(BeamSearch::Config{4, 5, 2, 1, -1}, vocab) == Status::kBadInput, "a negative minimum");
    std::vector<float> row(vocab, 0.0f);
    EXPECT(search.Step(row.data(), 1, vocab) == Status::kInternal, "a step after a failed start");

    // Without a minimum length (min_tokens 0), equal logits: candidates in token order, the padding
    // id skipped, end-of-sentence at rank 1 finishes, the next two tokens go on.
    EXPECT(search.Start(BeamSearch::Config{2, 5, 2, 1, 0}, vocab) == Status::kOk, "start");
    EXPECT(search.Step(row.data(), 2, vocab) == Status::kInternal, "rows that are not the beams");
    EXPECT(search.Step(row.data(), 1, vocab) == Status::kOk, "step");
    EXPECT(!search.done() && search.active() == 2 && search.next_tokens() == std::vector<int32_t>({0, 3}) &&
               search.parents() == std::vector<int32_t>({0, 0}),
           "ties: %zu beams", search.active());
    std::vector<float> two(2 * vocab, 0.0f);
    two[vocab + 2] = 5.0f;  // beam 1 strongly ends
    EXPECT(search.Step(two.data(), 2, vocab) == Status::kOk, "second step");
    EXPECT(search.done(), "two finished hypotheses end a beam of 2");
    std::vector<int32_t> tokens;
    double mean = 0;
    bool capped = false;
    EXPECT(search.Result(&tokens, &mean, &capped) == Status::kOk && tokens == std::vector<int32_t>({3}) && !capped,
           "best: [%s] capped %d", Join(tokens).c_str(), capped);
    // Summed in another order than the search sums, so equal up to rounding.
    const double lse = 5.0 + std::log(9.0 * std::exp(-5.0) + 1.0);
    const double expect_mean = ((0.0 - std::log(10.0)) + (5.0 - lse)) / 2.0;
    EXPECT(std::fabs(mean - expect_mean) < 1e-12, "mean %.17g, expected %.17g", mean, expect_mean);
    EXPECT(search.Step(row.data(), 1, vocab) == Status::kInternal, "a step after the end");

    // fairseq's min_len 1, the default: end-of-sentence is no first token even when it is by far the
    // best, and the others keep their log-probabilities (not renormalized without it).
    std::vector<float> ends(vocab, 0.0f);
    ends[2] = 9.0f;
    const double ends_lse = 9.0 + std::log(9.0 * std::exp(-9.0) + 1.0);
    EXPECT(search.Start(BeamSearch::Config{2, 5, 2, 1}, vocab) == Status::kOk, "start");
    EXPECT(search.Step(ends.data(), 1, vocab) == Status::kOk && !search.done() && search.active() == 2 &&
               search.next_tokens() == std::vector<int32_t>({0, 3}),
           "min length: %zu beams, done %d", search.active(), search.done());
    std::vector<float> then_end(2 * vocab, 0.0f);
    then_end[2] = 9.0f;
    then_end[vocab + 2] = 9.0f;
    EXPECT(search.Step(then_end.data(), 2, vocab) == Status::kOk && search.done(), "the end after one token");
    EXPECT(search.Result(&tokens, &mean, &capped) == Status::kOk && tokens == std::vector<int32_t>({0}) && !capped,
           "min length result: [%s] capped %d", Join(tokens).c_str(), capped);
    EXPECT(std::fabs(mean - ((0.0 - ends_lse) + (9.0 - ends_lse)) / 2.0) < 1e-12, "min length mean %.17g", mean);
    // The same logits without the minimum: an empty reading at once.
    EXPECT(search.Start(BeamSearch::Config{1, 5, 2, 1, 0}, vocab) == Status::kOk, "start");
    EXPECT(search.Step(ends.data(), 1, vocab) == Status::kOk && search.done(), "no minimum: the end at once");
    EXPECT(search.Result(&tokens, &mean, &capped) == Status::kOk && tokens.empty() && !capped,
           "no minimum: [%s] capped %d", Join(tokens).c_str(), capped);
    // A minimum of 2 holds the end back at the second token too.
    EXPECT(search.Start(BeamSearch::Config{1, 5, 2, 1, 2}, vocab) == Status::kOk, "start");
    EXPECT(search.Step(ends.data(), 1, vocab) == Status::kOk && search.Step(ends.data(), 1, vocab) == Status::kOk &&
               !search.done() && search.generated() == 2,
           "min length 2: done %d after %zu", search.done(), search.generated());
    // When the end is the only token the model allows, the minimum leaves no candidate: an empty
    // reading, marked capped, never a crash.
    std::vector<float> only_end(vocab, -INFINITY);
    only_end[2] = 0.0f;
    EXPECT(search.Start(BeamSearch::Config{4, 5, 2, 1}, vocab) == Status::kOk, "start");
    EXPECT(search.Step(only_end.data(), 1, vocab) == Status::kOk && search.done(), "only the end, held back");
    EXPECT(search.Result(&tokens, &mean, &capped) == Status::kOk && tokens.empty() && std::isinf(mean) && capped,
           "only the end: [%s] mean %g capped %d", Join(tokens).c_str(), mean, capped);

    // Non-finite logits fail the step; -inf is a token the model rules out.
    for (float bad : {std::nanf(""), INFINITY}) {
        EXPECT(search.Start(BeamSearch::Config{4, 5, 2, 1}, vocab) == Status::kOk, "start");
        std::vector<float> broken(vocab, 0.0f);
        broken[7] = bad;
        EXPECT(search.Step(broken.data(), 1, vocab) == Status::kRunFailed, "non-finite logits pass");
    }
    EXPECT(search.Start(BeamSearch::Config{4, 5, 2, 1}, vocab) == Status::kOk, "start");
    std::vector<float> none(vocab, -INFINITY);
    EXPECT(search.Step(none.data(), 1, vocab) == Status::kOk && search.done(), "nothing possible ends the search");
    EXPECT(search.Result(&tokens, &mean, &capped) == Status::kOk && tokens.empty() && std::isinf(mean) && capped,
           "an empty reading");

    // One token allowed: only end-of-sentence.
    EXPECT(search.Start(BeamSearch::Config{4, 1, 2, 1}, vocab) == Status::kOk, "start");
    std::vector<float> peaked(vocab, 0.0f);
    peaked[5] = 9.0f;
    EXPECT(search.Step(peaked.data(), 1, vocab) == Status::kOk && search.done(), "the cap of one");
    EXPECT(search.Result(&tokens, &mean, &capped) == Status::kOk && tokens.empty() && capped, "only the end");
    const double peaked_lse = 9.0 + std::log(9.0 * std::exp(-9.0) + 1.0);
    EXPECT(std::fabs(mean - (0.0 - peaked_lse)) < 1e-12, "forced end mean %.17g", mean);

    // A beam of 1 is greedy, ties to the lower token.
    EXPECT(search.Start(BeamSearch::Config{1, 3, 2, 1}, vocab) == Status::kOk, "start");
    std::vector<float> tie(vocab, 0.0f);
    tie[4] = 1.0f;
    tie[6] = 1.0f;
    EXPECT(search.Step(tie.data(), 1, vocab) == Status::kOk && search.next_tokens() == std::vector<int32_t>({4}),
           "greedy tie");
    BeamSearch unstarted;
    EXPECT(unstarted.Result(&tokens, &mean, &capped) == Status::kInternal, "a result before the end");
    std::printf("beam search: unit checks done\n");
}

void TestKvCache() {
    KvCache cache;
    EXPECT(cache.Allocate(KvCache::Shape{0, 4, 8, 4, 10, 5}) == Status::kBadModel, "no layers");
    EXPECT(cache.Allocate(KvCache::Shape{2, 65, 8, 4, 10, 5}) == Status::kBadModel, "too many heads");
    EXPECT(cache.Allocate(KvCache::Shape{2, 4, 8, 0, 10, 5}) == Status::kBadInput, "no beams");
    EXPECT(cache.Allocate(KvCache::Shape{2, 4, 8, 9, 10, 5}) == Status::kBadInput, "9 beams");
    EXPECT(cache.Allocate(KvCache::Shape{2, 4, 8, 4, 0, 5}) == Status::kBadInput, "no length");
    EXPECT(cache.Allocate(KvCache::Shape{2, 4, 8, 4, 10, 4097}) == Status::kBadInput, "a source past the limit");
    EXPECT(cache.Allocate(KvCache::Shape{64, 64, 1024, 8, 4096, 4096}) == Status::kOutOfMemory, "over the budget");
    EXPECT(cache.past(0, 0) == nullptr, "no buffer after a refused shape");
    // heads 2, head size 3, beams 3, up to 4 entries, source 5
    EXPECT(cache.Allocate(KvCache::Shape{2, 2, 3, 3, 4, 5}) == Status::kOk, "allocate");
    std::vector<float> one(2 * 5 * 3);
    for (size_t i = 0; i < one.size(); ++i) {
        one[i] = static_cast<float>(i);
    }
    EXPECT(cache.FillCross(1, 1, one.data(), one.size() - 1) == Status::kInternal, "a wrong cross size");
    EXPECT(cache.FillCross(2, 0, one.data(), one.size()) == Status::kInternal, "a layer past the last");
    EXPECT(cache.FillCross(1, 1, one.data(), one.size()) == Status::kOk, "fill");
    EXPECT(std::memcmp(cache.cross(1, 1) + 2 * one.size(), one.data(), one.size() * sizeof(float)) == 0,
           "the third beam slot holds the copy");
    // present rows of length 2 (heads 2 x 2 x head size 3 = 12 floats a row), 3 rows
    const size_t row = 2 * 2 * 3;
    for (size_t layer = 0; layer < 2; ++layer) {
        for (size_t kv = 0; kv < 2; ++kv) {
            for (size_t i = 0; i < 3 * row; ++i) {
                cache.present(layer, kv)[i] = static_cast<float>(1000 * layer + 100 * kv + i);
            }
        }
    }
    const int32_t bad[2] = {3, 0};
    EXPECT(cache.Gather(nullptr, 3, 2, bad, 2) == Status::kInternal, "a parent past the rows");
    const int32_t parents[2] = {2, 0};
    EXPECT(cache.Gather(nullptr, 3, 5, parents, 2) == Status::kInternal, "a length past the cap");
    EXPECT(cache.Gather(nullptr, 3, 2, parents, 4) == Status::kInternal, "more children than beams");
    EXPECT(cache.Gather(nullptr, 3, 2, parents, 2) == Status::kOk, "gather");
    EXPECT(cache.past(1, 1)[0] == 1100.0f + 2 * row && cache.past(1, 1)[row] == 1100.0f, "rows 2 and 0 in order");
    const float* before = cache.present(0, 0);
    const int32_t same[3] = {0, 1, 2};
    EXPECT(cache.Gather(nullptr, 3, 2, same, 3) == Status::kOk && cache.past(0, 0) == before,
           "unchanged beams swap the buffers");
    size_t product = 0;
    EXPECT(!satori::reader::MultiplyChecked(SIZE_MAX / 2 + 1, 2, &product), "overflow caught");
    std::printf("caches: unit checks done\n");
}

// Random sources, beams and caps on three variants: every call must succeed and stay in bounds,
// with ASan and UBSan watching the cache and search arithmetic on shapes the reference doesn't hit.
// The ids are random but framed as Translate takes them: </s> (2) last and nowhere else.
void TestRandomInputs(const std::string& models) {
    std::mt19937 random(20261019);
    int calls = 0;
    for (const char* variant : {"toy", "narrow", "noeos"}) {
        Reader reader;
        const Status load = LoadFolder(&reader, models + "/" + variant);
        EXPECT(load == Status::kOk, "%s: %s", variant, Name(load));
        for (int i = 0; i < 120; ++i) {
            const size_t count = 1 + random() % 256;
            std::vector<int32_t> ids(count, 2);
            for (size_t k = 0; k + 1 < count; ++k) {
                const auto id = static_cast<int32_t>(random() % 63);
                ids[k] = id >= 2 ? id + 1 : id;  // 0..63 but 2
            }
            const int beam = 1 + static_cast<int>(random() % 8);
            const int max_new = static_cast<int>(random() % 4 == 0 ? random() % 40 : 0);
            const size_t pieces = count > 3 ? count - 3 : 0;
            size_t cap = std::min<size_t>(2 * pieces + 10, 255);
            if (max_new > 0) {
                cap = std::min<size_t>(cap, static_cast<size_t>(max_new));
            }
            Translation out;
            const Status status = reader.Translate(ids.data(), ids.size(), beam, max_new, &out);
            bool in_range = true;
            for (int32_t id : out.ids) {
                in_range = in_range && id >= 0 && id < 64 && id != 1;
            }
            EXPECT(status == Status::kOk && in_range && out.ids.size() <= cap && out.steps >= 1 &&
                       static_cast<size_t>(out.steps) <= cap && !std::isnan(out.mean_log_prob) &&
                       out.mean_log_prob <= 0.0,
                   "%s: %zu ids, beam %d, cap %zu: %s, %zu out, %d runs, mean %g", variant, count, beam, cap,
                   Name(status), out.ids.size(), out.steps, out.mean_log_prob);
            ++calls;
        }
    }
    std::printf("random inputs: %d translations in bounds\n", calls);
}

// The statistics of decoder_with_past's CPU arena (ONNX Runtime's BFC arena) by key: InUse,
// TotalAllocated, MaxInUse, NumAllocs, MaxAllocSize and others; empty if ONNX Runtime gives none.
std::map<std::string, long long> StepArenaStats(const Reader& reader) {
    using satori::reader::ReaderTestPeer;
    std::map<std::string, long long> stats;
    const OrtApi* api = ReaderTestPeer::Api(reader);
    OrtAllocator* allocator = nullptr;
    OrtStatus* status =
        api->CreateAllocator(ReaderTestPeer::DecoderWithPast(reader), ReaderTestPeer::Memory(reader), &allocator);
    if (status == nullptr) {
        OrtKeyValuePairs* pairs = nullptr;
        status = api->AllocatorGetStats(allocator, &pairs);
        if (status == nullptr) {
            const char* const* keys = nullptr;
            const char* const* values = nullptr;
            size_t count = 0;
            api->GetKeyValuePairs(pairs, &keys, &values, &count);
            for (size_t i = 0; i < count; ++i) {
                stats[keys[i]] = std::atoll(values[i]);
            }
            api->ReleaseKeyValuePairs(pairs);
        }
        api->ReleaseAllocator(allocator);
    }
    if (status != nullptr) {
        std::fprintf(stderr, "arena statistics: %s\n", api->GetErrorMessage(status));
        api->ReleaseStatus(status);
    }
    return stats;
}

// decoder_with_past's arena holds only a step's small intermediates. Every output of a step goes
// into the reader's buffers, present.N.encoder.* (the graph's Identity of
// past_key_values.N.encoder.*) into the very cross buffer fed as that input. Left unfetched, those
// outputs were still computed, each into an arena block the size of the beams' whole cross cache
// per step: here [4 beams, 4 heads, 256 ids, 12] floats, 196,608 bytes, 4 of them; on IndicTrans2
// at beam 4 and 256 ids, 36 of 2 MiB.
void TestStepArena(const std::string& models) {
    Reader reader;
    const Status load = LoadFolder(&reader, models + "/toy");
    EXPECT(load == Status::kOk, "load: %s %s", Name(load), reader.last_error().c_str());
    const std::vector<int32_t> source = Source(253, 7);
    Translation out;
    const Status status = reader.Translate(source.data(), source.size(), 4, 12, &out);
    EXPECT(status == Status::kOk && out.steps > 2, "translate: %s, %d runs", Name(status), out.steps);
    const std::map<std::string, long long> stats = StepArenaStats(reader);
    const long long cross_bytes = 4LL * 4 * 256 * 12 * static_cast<long long>(sizeof(float));
    const auto stat = [&stats](const char* key) { return stats.count(key) ? stats.at(key) : -1LL; };
    // Measured on the toy: bound, a largest block of 16 KiB and at most about 190 KB in use (its
    // prepacked weights and a step's intermediates); unbound, 196,608 bytes and about 900 KB.
    EXPECT(stat("MaxAllocSize") > 0 && stat("MaxAllocSize") < cross_bytes / 2,
           "decoder_with_past's largest arena block is %lld bytes; one beam-4 cross output is %lld", stat("MaxAllocSize"),
           cross_bytes);
    EXPECT(stat("MaxInUse") > 0 && stat("MaxInUse") < 2 * cross_bytes,
           "decoder_with_past's arena held up to %lld bytes; the 4 cross outputs of a step would be %lld",
           stat("MaxInUse"), 4 * cross_bytes);
    std::printf("step arena: %d decoder runs over 256 ids at beam 4; decoder_with_past's arena: largest block %lld B, "
                "at most %lld B in use, %lld B reserved, %lld allocations (one cross output %lld B)\n",
                out.steps, stat("MaxAllocSize"), stat("MaxInUse"), stat("TotalAllocated"), stat("NumAllocs"),
                cross_bytes);
}

void TestBadInput(const std::string& models) {
    Reader idle;
    const std::vector<int32_t> source = Source(5, 1);
    Translation out;
    EXPECT(idle.Translate(source.data(), source.size(), 4, 0, &out) == Status::kNotLoaded, "translate before load");
    EXPECT(idle.Load("", "b", "c", ToyOptions()) == Status::kBadInput, "an empty path");
    Options odd = ToyOptions();
    odd.threads = 0;
    EXPECT(LoadFolder(&idle, models + "/toy", odd) == Status::kBadInput, "0 threads");
    odd = ToyOptions();
    odd.pad_id = odd.eos_id;
    EXPECT(LoadFolder(&idle, models + "/toy", odd) == Status::kBadInput, "pad = eos");
    odd = ToyOptions();
    odd.eos_id = 64;
    EXPECT(LoadFolder(&idle, models + "/toy", odd) == Status::kBadModel, "eos outside the target vocabulary");

    Reader reader;
    Status status = LoadFolder(&reader, models + "/toy");
    EXPECT(status == Status::kOk, "load: %s %s", Name(status), reader.last_error().c_str());
    EXPECT(LoadFolder(&reader, models + "/toy") == Status::kBadInput, "a second load");
    EXPECT(reader.Translate(source.data(), 0, 4, 0, &out) == Status::kBadInput, "no ids");
    EXPECT(reader.Translate(nullptr, 5, 4, 0, &out) == Status::kBadInput, "null ids");
    const std::vector<int32_t> long_source = Source(254, 2);
    EXPECT(long_source.size() == 257 && reader.Translate(long_source.data(), long_source.size(), 4, 0, &out) ==
                                            Status::kBadInput,
           "257 ids");
    std::vector<int32_t> outside = source;
    outside[3] = 64;
    EXPECT(reader.Translate(outside.data(), outside.size(), 4, 0, &out) == Status::kBadInput, "id 64");
    outside[3] = -1;
    EXPECT(reader.Translate(outside.data(), outside.size(), 4, 0, &out) == Status::kBadInput, "id -1");
    // The framing: </s> last and only there.
    std::vector<int32_t> unended(source.begin(), source.end() - 1);
    status = reader.Translate(unended.data(), unended.size(), 4, 0, &out);
    EXPECT(status == Status::kBadInput && out.ids.empty() && reader.last_error().find("does not end with") != std::string::npos,
           "no closing </s>: %s %s", Name(status), reader.last_error().c_str());
    std::vector<int32_t> joined = source;
    joined.insert(joined.end(), source.begin(), source.end());
    status = reader.Translate(joined.data(), joined.size(), 4, 0, &out);
    EXPECT(status == Status::kBadInput && reader.last_error().find("before the source's end") != std::string::npos,
           "two segments joined with their </s>: %s %s", Name(status), reader.last_error().c_str());
    const int32_t tags_only[2] = {4, 5};
    EXPECT(reader.Translate(tags_only, 2, 4, 0, &out) == Status::kBadInput, "the tags without </s>");
    EXPECT(reader.Translate(source.data(), source.size(), 0, 0, &out) == Status::kBadInput, "beam 0");
    EXPECT(reader.Translate(source.data(), source.size(), 9, 0, &out) == Status::kBadInput, "beam 9");
    EXPECT(reader.Translate(source.data(), source.size(), 4, -1, &out) == Status::kBadInput, "a negative cap");
    status = reader.Translate(source.data(), source.size(), 4, 0, -1, &out);
    EXPECT(status == Status::kBadInput && reader.last_error().find("negative minimum") != std::string::npos,
           "a negative minimum length: %s %s", Name(status), reader.last_error().c_str());
    EXPECT(reader.Translate(source.data(), source.size(), 4, 0, nullptr) == Status::kInternal, "no result");
    status = reader.Translate(source.data(), source.size(), 4, 0, &out);
    EXPECT(status == Status::kOk && !out.ids.empty(), "still works after the refusals: %s", Name(status));
    std::printf("bad input: refused with statuses\n");
}

void TestBadPaths(const std::string& models) {
    const std::string toy = models + "/toy";
    const std::string temp = TempDir();
    EXPECT(!temp.empty(), "no temp dir");
    Reader reader;
    Status status = reader.Load("/nonexistent/encoder_model.onnx", Graph(toy, "decoder_model.onnx"),
                                Graph(toy, "decoder_with_past_model.onnx"), ToyOptions());
    EXPECT(status == Status::kLoadFailed, "a missing file: %s", Name(status));
    status = reader.Load(toy, Graph(toy, "decoder_model.onnx"), Graph(toy, "decoder_with_past_model.onnx"), ToyOptions());
    EXPECT(status == Status::kLoadFailed, "a folder: %s", Name(status));
    std::string garbage(4096, '\0');
    std::mt19937 random(5);
    for (char& c : garbage) {
        c = static_cast<char>(random());
    }
    EXPECT(WriteFile(temp + "/garbage.onnx", garbage), "write");
    status = reader.Load(temp + "/garbage.onnx", Graph(toy, "decoder_model.onnx"),
                         Graph(toy, "decoder_with_past_model.onnx"), ToyOptions());
    EXPECT(status == Status::kLoadFailed, "garbage: %s", Name(status));
    // The encoder with its external data cut in half.
    EXPECT(WriteFile(temp + "/encoder_model.onnx", ReadFile(Graph(toy, "encoder_model.onnx"))), "copy");
    const std::string data = ReadFile(Graph(toy, "encoder_model.onnx.data"));
    EXPECT(WriteFile(temp + "/encoder_model.onnx.data", data.substr(0, data.size() / 2)), "truncate");
    status = reader.Load(temp + "/encoder_model.onnx", Graph(toy, "decoder_model.onnx"),
                         Graph(toy, "decoder_with_past_model.onnx"), ToyOptions());
    EXPECT(status == Status::kLoadFailed, "truncated external data: %s", Name(status));
    // The encoder without its external data file.
    EXPECT(std::remove((temp + "/encoder_model.onnx.data").c_str()) == 0, "remove");
    status = reader.Load(temp + "/encoder_model.onnx", Graph(toy, "decoder_model.onnx"),
                         Graph(toy, "decoder_with_past_model.onnx"), ToyOptions());
    EXPECT(status == Status::kLoadFailed, "missing external data: %s", Name(status));
    status = reader.Load(Graph(toy, "decoder_model.onnx"), Graph(toy, "encoder_model.onnx"),
                         Graph(toy, "decoder_with_past_model.onnx"), ToyOptions());
    EXPECT(status == Status::kBadModel, "swapped graphs: %s", Name(status));
    // A reader that failed to load loads again.
    status = LoadFolder(&reader, toy);
    EXPECT(status == Status::kOk, "a load after failed ones: %s %s", Name(status), reader.last_error().c_str());
    std::remove((temp + "/garbage.onnx").c_str());
    std::remove((temp + "/encoder_model.onnx").c_str());
    rmdir(temp.c_str());
    std::printf("bad paths: refused with statuses\n");
}

void TestWrongShapes(const std::string& models) {
    const std::vector<int32_t> source = Source(6, 3);
    struct Shape {
        const char* variant;
        Status load;
        Status translate;
    };
    const Shape shapes[] = {
        {"layers3", Status::kBadModel, Status::kNotLoaded},       // decoder_with_past has 3 layers, decoder_model 2
        {"missingpast", Status::kBadModel, Status::kNotLoaded},   // one past_key_values input missing
        {"int32ids", Status::kBadModel, Status::kNotLoaded},      // int32 ids
        {"flatlogits", Status::kBadModel, Status::kNotLoaded},    // [batch, vocab] logits (ORT infers the rank)
        {"heads", Status::kOk, Status::kBadModel},                // 6 heads of 8 declared, 4 of 12 given
        {"vocab65", Status::kBadModel, Status::kNotLoaded},       // decoder_model's logits over 65 tokens, not 64
        {"presenttwice", Status::kOk, Status::kRunFailed},        // a present one entry too long
    };
    for (const Shape& shape : shapes) {
        Reader reader;
        const Status load = LoadFolder(&reader, models + "/" + shape.variant);
        const std::string load_error = load == Status::kOk ? std::string() : reader.last_error();
        Translation out;
        const Status translate = reader.Translate(source.data(), source.size(), 4, 0, &out);
        const std::string error = load == Status::kOk ? reader.last_error() : load_error;
        EXPECT(load == shape.load && translate == shape.translate, "%s: load %s, translate %s (%s)", shape.variant,
               Name(load), Name(translate), error.c_str());
        std::printf("wrong shape %s: load %s, translate %s: %.120s\n", shape.variant, Name(load), Name(translate),
                    error.c_str());
    }
}

void TestCancel(const std::string& models) {
    {
        Reader reader;
        reader.Cancel();
        const Status status = LoadFolder(&reader, models + "/toy");
        Translation out;
        const std::vector<int32_t> source = Source(4, 4);
        EXPECT(status == Status::kCancelled && reader.Translate(source.data(), source.size(), 4, 0, &out) ==
                                                   Status::kCancelled,
               "cancel before load: %s", Name(status));
    }
    {
        // A finished translation, then cancel: the next one doesn't run.
        Reader reader;
        EXPECT(LoadFolder(&reader, models + "/toy") == Status::kOk, "load");
        const std::vector<int32_t> source = Source(8, 5);
        Translation out;
        EXPECT(reader.Translate(source.data(), source.size(), 4, 0, &out) == Status::kOk, "translate");
        reader.Cancel();
        reader.Cancel();
        EXPECT(reader.Translate(source.data(), source.size(), 4, 0, &out) == Status::kCancelled && out.ids.empty(),
               "cancel between segments");
    }

    const std::vector<int32_t> source = Source(253, 6);
    double run_ms = 0;
    {
        // The slow encoder uncancelled, to know how long its Run takes.
        Reader reader;
        const Status load = LoadFolder(&reader, models + "/slowrun");
        EXPECT(load == Status::kOk, "slowrun load: %s %s", Name(load), reader.last_error().c_str());
        Translation out;
        const Clock::time_point begun = Clock::now();
        const Status run = reader.Translate(source.data(), source.size(), 1, 1, &out);
        run_ms = Ms(begun, Clock::now());
        EXPECT(run == Status::kOk, "slowrun translate: %s %s", Name(run), reader.last_error().c_str());
    }
    for (const int at_ms : {150, 600}) {
        // Cancel at_ms into the encoder's Run, from another thread; meanwhile a second call is busy.
        Reader reader;
        EXPECT(LoadFolder(&reader, models + "/slowrun") == Status::kOk, "load");
        std::atomic<bool> finished{false};
        Status status = Status::kInternal;
        Clock::time_point ended;
        std::thread worker([&] {
            Translation out;
            status = reader.Translate(source.data(), source.size(), 4, 0, &out);
            ended = Clock::now();
            finished.store(true);
        });
        std::this_thread::sleep_for(std::chrono::milliseconds(at_ms));
        const bool running = !finished.load();
        Translation other;
        const Status busy = reader.Translate(source.data(), source.size(), 4, 0, &other);
        const Status busy_load = LoadFolder(&reader, models + "/slowrun");
        const Clock::time_point cancelled = Clock::now();
        reader.Cancel();
        worker.join();
        const double latency = Ms(cancelled, ended);
        EXPECT(running, "the slow Run ended before %d ms (%.0f ms uncancelled)", at_ms, run_ms);
        EXPECT(busy == Status::kBusy && busy_load == Status::kBusy, "a second call while one runs: %s, %s",
               Name(busy), Name(busy_load));
        EXPECT(status == Status::kCancelled, "cancel mid-Run: %s", Name(status));
        EXPECT(latency < 100.0, "the Run stopped %.1f ms after cancel()", latency);
        Translation after;
        EXPECT(reader.Translate(source.data(), source.size(), 4, 0, &after) == Status::kCancelled,
               "a call after cancel()");
        std::printf("cancel mid-Run: the encoder's Run takes %.0f ms uncancelled; cancelled %d ms in, it returned "
                    "%s %.2f ms after cancel()\n",
                    run_ms, at_ms, Name(status), latency);
    }

    // Cancel during a load, at points through it. ONNX Runtime reads the flag per initializer,
    // between optimizer passes and per node while prepacking, not while it plans the graph's
    // memory, so the time to stop depends on where the load is. Early in the load (0 % and 10 %,
    // in the optimizer passes) the flag must stop it: in well under half the load still to come,
    // which a cancel that only waits for the session to finish (no load-cancellation flag) can't
    // meet. Later points land in or after the planning and are only bounded by the whole load.
    const std::string slow_load = models + "/slowload";
    double load_ms = 0;
    {
        Reader reader;
        const Clock::time_point start = Clock::now();
        const Status load = LoadFolder(&reader, slow_load);
        load_ms = Ms(start, Clock::now());
        EXPECT(load == Status::kOk, "slowload load: %s %s", Name(load), reader.last_error().c_str());
    }
    for (const double share : {0.0, 0.1, 0.5, 0.9}) {
        Reader reader;
        Status status = Status::kInternal;
        Clock::time_point ended;
        std::thread loader([&] {
            status = LoadFolder(&reader, slow_load);
            ended = Clock::now();
        });
        const double wait = std::max(0.5, load_ms * share);
        std::this_thread::sleep_for(std::chrono::microseconds(static_cast<int64_t>(wait * 1000)));
        const Clock::time_point cancelled = Clock::now();
        reader.Cancel();
        loader.join();
        const double latency = Ms(cancelled, ended);
        EXPECT(status == Status::kCancelled, "cancel %.0f%% into the load: %s %s", share * 100, Name(status),
               reader.last_error().c_str());
        const double bound = share <= 0.1 ? 0.5 * (load_ms - wait) : load_ms;
        EXPECT(latency < bound, "cancelled %.0f%% into the load, it stopped %.1f ms after cancel(); the bound is %.0f ms "
               "(a whole load takes %.0f)", share * 100, latency, bound, load_ms);
        std::printf("cancel during load: a whole load takes %.0f ms; cancelled %.0f ms in, it returned %s %.1f ms "
                    "after cancel(): %.70s\n",
                    load_ms, wait, Name(status), latency, reader.last_error().c_str());
    }
}

// SentencePiece given an ONNX file (protobuf of another kind) as its model, in a second process
// (this binary again, with --tokenizer-only), as an abort there would end the whole run.
void TestTokenizerOnForeignFile(const char* self, const std::string& models) {
    const std::string model = models + "/toy/decoder_model.onnx";
    char* const args[] = {const_cast<char*>(self), const_cast<char*>("--tokenizer-only"),
                          const_cast<char*>(model.c_str()), nullptr};
    pid_t child = 0;
    const int spawned = posix_spawn(&child, self, nullptr, nullptr, args, environ);
    EXPECT(spawned == 0, "posix_spawn: %d", spawned);
    if (spawned != 0) {
        return;
    }
    int how = 0;
    waitpid(child, &how, 0);
    const bool exited = WIFEXITED(how);
    const int code = exited ? WEXITSTATUS(how) : -1;
    EXPECT(exited && code == static_cast<int>(Status::kLoadFailed), "an ONNX file as the tokenizer: %s %d",
           exited ? "exit" : "signal", exited ? code : WTERMSIG(how));
}

void TestTokenizer(const std::string& spm, const std::string& models) {
    Reader reader;
    std::vector<std::string> pieces;
    EXPECT(reader.Pieces("x", &pieces) == Status::kNotLoaded, "pieces before load");
    EXPECT(reader.LoadTokenizer("") == Status::kBadInput, "no path");
    EXPECT(reader.LoadTokenizer(spm + "/missing.model") == Status::kLoadFailed, "a missing model");
    EXPECT(reader.LoadTokenizer(models + "/toy") == Status::kLoadFailed, "a folder");
    const std::string temp = TempDir();
    EXPECT(WriteFile(temp + "/garbage.model", std::string(1000, '\x7f')), "write");
    EXPECT(reader.LoadTokenizer(temp + "/garbage.model") == Status::kLoadFailed, "garbage");
    std::remove((temp + "/garbage.model").c_str());
    rmdir(temp.c_str());
    const Status status = reader.LoadTokenizer(spm + "/toy.model");
    EXPECT(status == Status::kOk, "load: %s %s", Name(status), reader.last_error().c_str());

    std::ifstream in(spm + "/pieces.tsv");
    std::string line;
    int lines = 0;
    int matched = 0;
    while (std::getline(in, line)) {
        const std::vector<std::string> field = Split(line, '\t');
        if (field.size() != 2) {
            EXPECT(false, "a pieces line with %zu fields", field.size());
            continue;
        }
        const std::string text = Unhex(field[0]);
        std::vector<std::string> want;
        for (const std::string& hex : Split(field[1], ' ')) {
            if (!hex.empty()) {
                want.push_back(Unhex(hex));
            }
        }
        const Status encoded = reader.Pieces(text, &pieces);
        const bool same = encoded == Status::kOk && pieces == want;
        EXPECT(same, "line %d (%zu bytes): %s, %zu pieces, the wheel %zu", lines, text.size(), Name(encoded),
               pieces.size(), want.size());
        ++lines;
        matched += same ? 1 : 0;
    }
    for (const char* broken : {"\xC3\x28", "\xED\xA0\x80", "\xF4\x90\x80\x80", "\xC0\xAF", "\x80", "ab\xE0\xA4",
                               "\xF8\x88\x80\x80\x80"}) {
        EXPECT(reader.Pieces(broken, &pieces) == Status::kBadInput && pieces.empty(), "broken UTF-8 passed");
    }
    EXPECT(IsValidUtf8("\xE0\xA4\x95\xF0\x9F\x98\x80 plain") && IsValidUtf8(""), "valid UTF-8 refused");
    EXPECT(reader.Pieces(std::string(satori::reader::Tokenizer::kMaxTextBytes + 1, 'a'), &pieces) == Status::kBadInput,
           "a text over 64 KiB");
    EXPECT(reader.Pieces(std::string(1000, ' '), &pieces) == Status::kOk && pieces.empty(), "only spaces");
    std::printf("tokenizer: %d of %d lines give the wheel's pieces; broken UTF-8 refused\n", matched, lines);
}

}  // namespace

int main(int argc, char** argv) {
    if (argc == 3 && std::strcmp(argv[1], "--tokenizer-only") == 0) {
        Reader reader;
        return static_cast<int>(reader.LoadTokenizer(argv[2]));
    }
    if (argc != 4) {
        std::fprintf(stderr, "usage: %s <models dir> <expected.tsv> <spm dir>\n", argv[0]);
        return 2;
    }
    const std::string models = argv[1];
    const std::string expected = argv[2];
    const std::string spm = argv[3];
    TestTokenizerOnForeignFile(argv[0], models);
    TestBeamSearch();
    TestKvCache();
    TestReference(models, expected);
    TestRandomInputs(models);
    TestStepArena(models);
    TestBadInput(models);
    TestBadPaths(models);
    TestWrongShapes(models);
    TestCancel(models);
    TestTokenizer(spm, models);
    std::printf("%d checks, %d failed\n", g_checks, g_failures);
    return g_failures == 0 ? 0 : 1;
}
