// Host test for grammar_sampler.cpp, the grammar handling behind InferenceEngine.Grammar. It runs
// on the development machine against the same llama.cpp checkout as the app, with no phone and no
// model weights: it loads only the vocabulary of llama.cpp's Gemma 4 test file
// (third_party/llama.cpp/models/ggml-vocab-gemma-4.gguf), a real 262k-token vocabulary.
//
// cmake and ninja come with the Android SDK ($ANDROID_HOME/cmake/<version>/bin):
//
//   cmake -S lib-llama-android/src/test/cpp -B /tmp/grammar-host -G Ninja -DCMAKE_BUILD_TYPE=Release
//   cmake --build /tmp/grammar-host --target grammar_sampler_test
//   /tmp/grammar-host/grammar_sampler_test third_party/llama.cpp/models/ggml-vocab-gemma-4.gguf
//
// Add --bench to also time grammar masks (see bench_masks).
//
// With no model weights there are no logits, so the walks below stand in for the model: each step
// lets the grammar mask the whole vocabulary and picks a random token it allows, until it picks
// end-of-generation. Whatever comes out must match the grammar.

#include <cmath>
#include <cstdio>
#include <exception>
#include <random>
#include <set>
#include <string>
#include <utility>
#include <vector>

#include <nlohmann/json.hpp>

#include "common.h"
#include "grammar_sampler.h"
#include "llama.h"

static int g_checks   = 0;
static int g_failures = 0;

#define CHECK(cond)                                                              \
    do {                                                                         \
        ++g_checks;                                                              \
        if (!(cond)) {                                                           \
            ++g_failures;                                                        \
            fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond);      \
        }                                                                        \
    } while (0)

// Planted in every grammar below; llama.cpp's log callback (logcat in the app) must never see it.
static const char * MARKER = "Anna-flat-viewing";

static std::vector<std::string> g_llama_logs;

static void capture_log(ggml_log_level /*level*/, const char * text, void * /*user*/) {
    g_llama_logs.emplace_back(text ? text : "");
}

static const std::string GBNF_YES_NO = std::string("root ::= \"yes\" | \"no\"  # ") + MARKER + "\n";

// Shaped like the todo extraction: enums built per call, bounded strings so the walks end.
static const std::string TODO_SCHEMA = R"({
  "type": "object",
  "properties": {
    "summary": {"type": "string", "maxLength": 24, "description": ")" + std::string(MARKER) + R"("},
    "todos": {
      "type": "array",
      "maxItems": 2,
      "items": {
        "type": "object",
        "properties": {
          "text": {"type": "string", "maxLength": 16},
          "kind": {"enum": ["task", "appointment"]},
          "when": {"enum": ["none", "T1", "T2"]},
          "segment": {"enum": ["S1", "S2", "S3"]},
          "certainty": {"enum": ["confirmed", "tentative"]}
        },
        "required": ["text", "kind", "when", "segment", "certainty"],
        "additionalProperties": false
      }
    }
  },
  "required": ["summary", "todos"],
  "additionalProperties": false
})";

static size_t utf8_length(const std::string & s) {
    size_t n = 0;
    for (unsigned char c : s) {
        if ((c & 0xC0) != 0x80) {
            ++n;
        }
    }
    return n;
}

static bool accepts(common_sampler * sampler, const std::vector<llama_token> & tokens) {
    try {
        for (const auto token : tokens) {
            common_sampler_accept(sampler, token, /* is_generated = */ true);
        }
        return true;
    } catch (const std::exception &) {
        return false;
    }
}

// Random walk through a grammar over the full vocabulary; see the header. Returns false on a dead
// end or when it doesn't end within max_steps.
static bool walk(const llama_vocab * vocab, const std::string & gbnf, const unsigned seed, const int max_steps,
                 std::string & out) {
    out.clear();
    llama_sampler * grammar = llama_sampler_init_grammar(vocab, gbnf.c_str(), "root");
    if (grammar == nullptr) {
        return false;
    }

    std::mt19937 rng(seed);
    const int n_vocab = llama_vocab_n_tokens(vocab);
    std::vector<llama_token_data> cur(n_vocab);
    bool ended = false;

    for (int step = 0; step < max_steps && !ended; ++step) {
        for (llama_token id = 0; id < n_vocab; ++id) {
            cur[id] = llama_token_data{ id, 0.0f, 0.0f };
        }
        llama_token_data_array candidates = { cur.data(), cur.size(), -1, false };
        llama_sampler_apply(grammar, &candidates);

        std::vector<llama_token> allowed;
        bool eog_allowed = false;
        for (size_t i = 0; i < candidates.size; ++i) {
            if (candidates.data[i].logit == -INFINITY) {
                continue;
            }
            if (llama_vocab_is_eog(vocab, candidates.data[i].id)) {
                eog_allowed = true;
            } else {
                allowed.push_back(candidates.data[i].id);
            }
        }

        // Ending as soon as the grammar allows it keeps the walks short.
        if (eog_allowed && (allowed.empty() || rng() % 2 == 0)) {
            ended = true;
        } else if (allowed.empty()) {
            break;
        } else {
            const llama_token token = allowed[rng() % allowed.size()];
            llama_sampler_accept(grammar, token);
            out += common_token_to_piece(vocab, token, true);
        }
    }

    llama_sampler_free(grammar);
    return ended;
}

static void test_conversion_statuses() {
    std::string gbnf;

    CHECK(grammar_to_gbnf("", false, gbnf) == grammar_status::empty);
    CHECK(grammar_to_gbnf(" \n\t", true, gbnf) == grammar_status::empty);
    CHECK(grammar_to_gbnf("%llguidance {}\nstart: \"x\"", false, gbnf) == grammar_status::unsupported_syntax);
    CHECK(gbnf.empty());

    CHECK(grammar_to_gbnf(GBNF_YES_NO, false, gbnf) == grammar_status::applied);
    CHECK(gbnf == GBNF_YES_NO);

    CHECK(grammar_to_gbnf(std::string("{\"type\": \"object\", ") + MARKER, true, gbnf) == grammar_status::schema_not_json);
    CHECK(gbnf.empty());
    CHECK(grammar_to_gbnf(std::string("{\"type\": \"") + MARKER + "\"}", true, gbnf) == grammar_status::schema_unsupported);
    // Only "#/..." refs resolve offline; json_schema_to_grammar rejects relative ones.
    CHECK(grammar_to_gbnf(std::string("{\"$ref\": \"definitions/") + MARKER + "\"}", true, gbnf) ==
          grammar_status::schema_unsupported);
    CHECK(gbnf.empty());

    CHECK(grammar_to_gbnf(TODO_SCHEMA, true, gbnf) == grammar_status::applied);
    CHECK(gbnf.find("root ::=") != std::string::npos);
}

static void test_bad_grammars_fall_back(const llama_model * model) {
    const common_params_sampling base;
    const std::vector<std::string> broken = {
        std::string("root ::= \"") + MARKER,                   // unterminated literal
        std::string("start ::= \"") + MARKER + "\"",           // no root rule
        std::string("root ::= ") + "undefined-" + MARKER,      // undefined rule
        std::string("root ::= root \"") + MARKER + "\" | \"a\"", // left recursion
    };
    for (const auto & gbnf : broken) {
        const auto result = grammar_sampler_init(model, base, gbnf, false);
        CHECK(result.status == grammar_status::gbnf_invalid);
        CHECK(result.sampler == nullptr);
    }

    const auto not_json = grammar_sampler_init(model, base, MARKER, true);
    CHECK(not_json.status == grammar_status::schema_not_json);
    CHECK(not_json.sampler == nullptr);

    const auto blank = grammar_sampler_init(model, base, "  ", false);
    CHECK(blank.status == grammar_status::empty);
    CHECK(blank.sampler == nullptr);

    const auto no_model = grammar_sampler_init(nullptr, base, GBNF_YES_NO, false);
    CHECK(no_model.status == grammar_status::sampler_failed);
    CHECK(no_model.sampler == nullptr);
}

static void test_applied_grammar_constrains_and_free_text_does_not(const llama_model * model) {
    const llama_vocab * vocab = llama_model_get_vocab(model);
    const common_params_sampling base;
    const auto maybe = common_tokenize(vocab, "maybe", false, false);
    const auto yes = common_tokenize(vocab, "yes", false, false);

    auto rejecting = grammar_sampler_init(model, base, GBNF_YES_NO, false);
    CHECK(rejecting.status == grammar_status::applied);
    CHECK(rejecting.sampler != nullptr);
    if (rejecting.sampler) {
        CHECK(!accepts(rejecting.sampler, maybe));
        common_sampler_free(rejecting.sampler);
    }

    auto accepting = grammar_sampler_init(model, base, GBNF_YES_NO, false);
    CHECK(accepting.sampler != nullptr);
    if (accepting.sampler) {
        // The grammar is complete after "yes", so end-of-generation is accepted too.
        CHECK(accepts(accepting.sampler, yes));
        CHECK(accepts(accepting.sampler, { llama_vocab_eos(vocab) }));
        common_sampler_free(accepting.sampler);
    }

    auto schema = grammar_sampler_init(model, base, TODO_SCHEMA, true);
    CHECK(schema.status == grammar_status::applied);
    CHECK(schema.sampler != nullptr);
    if (schema.sampler) {
        CHECK(!accepts(schema.sampler, maybe));
        common_sampler_free(schema.sampler);
    }

    // The free-text sampler ai_chat.cpp uses without a grammar takes anything.
    common_params_sampling free_params = base;
    common_sampler * free_text = common_sampler_init(model, free_params);
    CHECK(free_text != nullptr);
    if (free_text) {
        CHECK(accepts(free_text, maybe));
        CHECK(accepts(free_text, yes));
        common_sampler_free(free_text);
    }
}

// Free-text settings with every repetition penalty on. Settings allows a repeat penalty up to 2.0
// (SettingsViewModel), and updateSamplerConfig puts it into g_sampler_params.
static common_params_sampling penalized_settings() {
    common_params_sampling base;
    base.temp            = 0.3f;
    base.top_k           = 40;
    base.top_p           = 0.95f;
    base.min_p           = 0.05f;
    base.penalty_repeat  = 1.5f;
    base.penalty_freq    = 0.5f;
    base.penalty_present = 0.5f;
    return base;
}

static void test_grammar_params_drop_penalties() {
    common_params_sampling base = penalized_settings();
    base.dry_multiplier = 0.8f;
    base.generation_prompt = "<start_of_turn>model\n";
    base.grammar_lazy = true;

    const common_params_sampling params = grammar_params(base);
    CHECK(params.penalty_repeat == 1.0f);
    CHECK(params.penalty_freq == 0.0f);
    CHECK(params.penalty_present == 0.0f);
    CHECK(params.penalty_last_n == 0);
    CHECK(params.dry_multiplier == 0.0f);
    CHECK(!params.grammar_lazy);
    CHECK(params.generation_prompt.empty());

    // The rest of the free-text settings stay.
    CHECK(params.temp == base.temp);
    CHECK(params.top_k == base.top_k);
    CHECK(params.top_p == base.top_p);
    CHECK(params.min_p == base.min_p);
    CHECK(params.seed == base.seed);
}

// What grammar_params prevents: once a reply has written "task" for one todo, a repeat penalty
// pushes "task" below "appointment" for the next one, though both fit the grammar. The free-text
// sampler is the control (its pick flips, so the check isn't vacuous); the grammar sampler keeps
// "task". Greedy (temperature 0) makes the pick the top logit after the penalties.
static void test_grammar_sampler_keeps_repeated_values(const llama_model * model) {
    const llama_vocab * vocab = llama_model_get_vocab(model);
    common_params_sampling base = penalized_settings();
    base.temp = 0.0f;

    const llama_token task  = common_tokenize(vocab, "task", false, false).front();
    const llama_token other = common_tokenize(vocab, "appointment", false, false).front();
    CHECK(task != other);

    // What the sampler's chain picks from {task: 2.0, other: 1.9} after it has seen `task` once.
    // is_generated = false feeds the token to the chain (the penalties) and not to the grammar.
    const auto pick = [&](common_sampler * sampler) {
        common_sampler_accept(sampler, task, /* is_generated = */ false);
        std::vector<llama_token_data> cur = { { task, 2.0f, 0.0f }, { other, 1.9f, 0.0f } };
        llama_token_data_array candidates = { cur.data(), cur.size(), -1, false };
        llama_sampler_apply(common_sampler_get(sampler), &candidates);
        return candidates.selected >= 0 ? candidates.data[candidates.selected].id : LLAMA_TOKEN_NULL;
    };

    common_params_sampling free_params = base;
    common_sampler * free_text = common_sampler_init(model, free_params);
    const auto grammar = grammar_sampler_init(model, base, "root ::= \"task\" | \"appointment\"", false);
    CHECK(free_text != nullptr);
    CHECK(grammar.status == grammar_status::applied);
    if (free_text != nullptr && grammar.sampler != nullptr) {
        CHECK(pick(free_text) == other);
        CHECK(pick(grammar.sampler) == task);
    }
    common_sampler_free(free_text);
    common_sampler_free(grammar.sampler);
}

// Every reply's settings as ai_chat.cpp's sampler_params() gives them, at temperature [temp]:
// top-k 40, top-p 0.95, min-p 0.05, no repeat penalty. The JVM tests read those defaults back from
// ai_chat.cpp (InferenceEngineImplTest, LlamaAndroidDataSourceTest).
static common_params_sampling app_settings(const float temp) {
    common_params_sampling base;
    base.temp           = temp;
    base.top_k          = 40;
    base.top_p          = 0.95f;
    base.min_p          = 0.05f;
    base.penalty_repeat = 1.0f;
    return base;
}

using token_odds = std::vector<std::pair<llama_token, float>>;

// The logits of a model that gives each token of [odds] its probability and the rest almost none.
static void fill_logits(std::vector<llama_token_data> & cur, const token_odds & odds) {
    for (size_t id = 0; id < cur.size(); ++id) {
        cur[id] = llama_token_data{ (llama_token) id, -30.0f, 0.0f };
    }
    for (const auto & [token, p] : odds) {
        cur[token].logit = logf(p);
    }
}

static bool grammar_allows(llama_sampler * grammar, const llama_token token) {
    llama_token_data one = { token, 1.0f, 0.0f };
    llama_token_data_array one_token = { &one, 1, -1, false };
    llama_sampler_apply(grammar, &one_token);
    return one_token.data[0].logit != -INFINITY;
}

// common_sampler_sample's steps (common/sampling.cpp) on these logits, as it needs a context for a
// model's own: [chain] over the whole vocabulary, the [grammar]'s check of the token it picked, and
// only if that fails the grammar's mask over the whole vocabulary ([masked] counts them), then
// [chain] again. Without a grammar, free text.
static llama_token sample_at(llama_sampler * chain, llama_sampler * grammar, const token_odds & odds,
                             std::vector<llama_token_data> & cur, int & masked) {
    fill_logits(cur, odds);
    llama_token_data_array candidates = { cur.data(), cur.size(), -1, false };
    llama_sampler_apply(chain, &candidates);
    const llama_token picked = candidates.data[candidates.selected].id;
    if (grammar == nullptr || grammar_allows(grammar, picked)) {
        return picked;
    }
    ++masked;
    fill_logits(cur, odds);
    candidates = { cur.data(), cur.size(), -1, false };
    llama_sampler_apply(grammar, &candidates);
    llama_sampler_apply(chain, &candidates);
    return candidates.data[candidates.selected].id;
}

// 0.7.2 C.2: what greedy decoding (temperature 0) would change against today's 0.3, at a decision
// where the model is torn. A to-do list's first token: "]" (no to-dos) at .50, "{" (a to-do) at
// .45, prose the grammar never allows at .05. Each reply gets a sampler of its own, as
// processUserPrompt makes one, so a random seed of its own: at 0.3 the to-do comes in about 41% of
// replies ((.45/.50)^(1/.3) against 1), and greedy always takes the mode, the same answer each
// time. Both answers parse. Where prose is the model's first choice, the grammar's mask comes in at
// either temperature, and greedy takes the best token the grammar allows. A name, free text, at a
// tie between two words (.55/.40): the second word in about a quarter of replies at 0.3, never
// greedy. Greedy never loses a valid answer and never needs the mask where the mode is valid; what
// it costs is that a mode of "no to-do" is the answer every time, which only the phone can weigh.
static void test_temperature_at_a_near_tie(const llama_model * model) {
    const llama_vocab * vocab = llama_model_get_vocab(model);
    std::vector<llama_token_data> cur(llama_vocab_n_tokens(vocab));
    const std::string schema = R"({"type": "object", "properties": {"items": {"type": "array", "maxItems": 2, "items": {
        "type": "object", "properties": {"text": {"type": "string", "maxLength": 8}}, "required": ["text"],
        "additionalProperties": false}}}, "required": ["items"], "additionalProperties": false})";
    std::string gbnf;
    CHECK(grammar_to_gbnf(schema, true, gbnf) == grammar_status::applied);
    const auto one_token = [&](const char * text) {
        const auto tokens = common_tokenize(vocab, text, false, false);
        return tokens.size() == 1 ? tokens[0] : LLAMA_TOKEN_NULL;
    };
    const llama_token close_list = one_token("]");
    const llama_token open_item  = one_token("{");
    const llama_token prose      = one_token("Sure");
    const llama_token word_a     = one_token("Budget");
    const llama_token word_b     = one_token("Planning");
    const bool tokens_found = close_list != LLAMA_TOKEN_NULL && open_item != LLAMA_TOKEN_NULL &&
                              prose != LLAMA_TOKEN_NULL && word_a != LLAMA_TOKEN_NULL && word_b != LLAMA_TOKEN_NULL;
    CHECK(tokens_found);
    if (!tokens_found) {
        return;
    }
    // The grammar as far as the reply has got: `{"items": [`.
    const auto after_open_list = [&]() {
        llama_sampler * grammar = llama_sampler_init_grammar(vocab, gbnf.c_str(), "root");
        for (const auto token : common_tokenize(vocab, "{\"items\": [", false, false)) {
            CHECK(grammar_allows(grammar, token));
            llama_sampler_accept(grammar, token);
        }
        return grammar;
    };

    const int runs = 600;
    for (const float temp : { 0.3f, 0.0f }) {
        int items = 0, tie_masked = 0, prose_masked = 0, second_word = 0, unused = 0;
        std::set<llama_token> tie_answers, masked_answers;
        for (int run = 0; run < runs; ++run) {
            const auto reply = grammar_sampler_init(model, app_settings(temp), schema, true);
            CHECK(reply.status == grammar_status::applied);
            if (reply.sampler == nullptr) {
                return;
            }
            llama_sampler * grammar = after_open_list();
            const llama_token tie = sample_at(common_sampler_get(reply.sampler), grammar,
                                              { { close_list, 0.50f }, { open_item, 0.45f }, { prose, 0.05f } }, cur, tie_masked);
            CHECK(tie == close_list || tie == open_item);
            items += tie == open_item;
            tie_answers.insert(tie);
            const llama_token masked = sample_at(common_sampler_get(reply.sampler), grammar,
                                                 { { prose, 0.60f }, { open_item, 0.25f }, { close_list, 0.15f } }, cur, prose_masked);
            CHECK(masked == close_list || masked == open_item);
            masked_answers.insert(masked);
            llama_sampler_free(grammar);
            common_sampler_free(reply.sampler);

            common_params_sampling free_text_settings = app_settings(temp);
            common_sampler * name = common_sampler_init(model, free_text_settings);
            second_word += sample_at(common_sampler_get(name), nullptr,
                                     { { word_a, 0.55f }, { word_b, 0.40f }, { prose, 0.05f } }, cur, unused) == word_b;
            common_sampler_free(name);
        }
        printf("temp %.1f: to-do list near a tie (.50/.45): an item in %d of %d replies (distinct: %zu, masks: %d); "
               "prose first (.60): %d of %d masked (distinct: %zu); a name near a tie (.55/.40): the second word in %d\n",
               temp, items, runs, tie_answers.size(), tie_masked, prose_masked, runs, masked_answers.size(), second_word);
        if (temp == 0.0f) {
            CHECK(items == 0 && tie_answers.size() == 1);
            CHECK(tie_masked == 0);
            CHECK(prose_masked == runs);
            CHECK(masked_answers.size() == 1 && *masked_answers.begin() == open_item);
            CHECK(second_word == 0);
        } else {
            CHECK(items > runs * 0.33 && items < runs * 0.50);
            CHECK(tie_answers.size() == 2);
            CHECK(masked_answers.size() == 2);
            CHECK(second_word > runs * 0.17 && second_word < runs * 0.32);
        }
    }
}

// The per-reply lifetime ai_chat.cpp relies on: a grammar constrains its own reply only.
static void test_grammar_lasts_one_reply(const llama_model * model) {
    const llama_vocab * vocab = llama_model_get_vocab(model);
    const common_params_sampling base;
    const auto maybe = common_tokenize(vocab, "maybe", false, false);

    common_params_sampling free_params = base;
    common_sampler * free_text = common_sampler_init(model, free_params);
    CHECK(free_text != nullptr);
    if (free_text == nullptr) {
        return;
    }

    grammar_reply reply;
    CHECK(!reply.active());
    CHECK(reply.sampler(free_text) == free_text);

    // A reply with a grammar samples under it...
    CHECK(reply.begin(model, base, GBNF_YES_NO, false) == grammar_status::applied);
    CHECK(reply.active());
    CHECK(reply.sampler(free_text) != free_text);
    CHECK(!accepts(reply.sampler(free_text), maybe));

    // ...until it ends; the next reply, without a grammar, is free text.
    reply.end();
    CHECK(!reply.active());
    CHECK(reply.sampler(free_text) == free_text);
    CHECK(accepts(reply.sampler(free_text), maybe));
    reply.end();
    CHECK(!reply.active());

    // A new reply's grammar replaces the last one, even if that reply never ended.
    CHECK(reply.begin(model, base, GBNF_YES_NO, false) == grammar_status::applied);
    CHECK(reply.begin(model, base, "root ::= \"maybe\"", false) == grammar_status::applied);
    CHECK(accepts(reply.sampler(free_text), maybe));

    // A rejected grammar leaves its reply free text, not under the last reply's grammar.
    CHECK(reply.begin(model, base, GBNF_YES_NO, false) == grammar_status::applied);
    CHECK(reply.begin(model, base, std::string("root ::= \"") + MARKER, false) == grammar_status::gbnf_invalid);
    CHECK(!reply.active());
    CHECK(reply.sampler(free_text) == free_text);

    reply.end();
    common_sampler_free(free_text);
}

// Why InferenceEngineImpl sends text as UTF-8 bytes: JNI's GetStringUTFChars gives Modified UTF-8,
// which writes U+1F600 as two 3-byte surrogates, and nlohmann::json rejects those, so the schema
// would not apply. Standard UTF-8 converts.
static void test_schema_needs_standard_utf8() {
    const std::string utf8     = "\xF0\x9F\x98\x80";                 // U+1F600 in UTF-8
    const std::string modified = "\xED\xA0\xBD\xED\xB8\x80";         // the same in Modified UTF-8
    const auto schema = [](const std::string & value) {
        return std::string(R"({"type": "object", "properties": {"label": {"enum": ["a )") + value +
               R"("]}}, "required": ["label"]})";
    };
    std::string gbnf;
    CHECK(grammar_to_gbnf(schema(utf8), true, gbnf) == grammar_status::applied);
    CHECK(grammar_to_gbnf(schema(modified), true, gbnf) == grammar_status::schema_not_json);
}

static void test_walks_follow_the_gbnf(const llama_model * model) {
    const llama_vocab * vocab = llama_model_get_vocab(model);
    std::string gbnf;
    CHECK(grammar_to_gbnf(GBNF_YES_NO, false, gbnf) == grammar_status::applied);

    for (unsigned seed = 1; seed <= 4; ++seed) {
        std::string out;
        CHECK(walk(vocab, gbnf, seed, 16, out));
        CHECK(out == "yes" || out == "no");
    }
}

static void test_walks_follow_the_schema(const llama_model * model) {
    const llama_vocab * vocab = llama_model_get_vocab(model);
    std::string gbnf;
    CHECK(grammar_to_gbnf(TODO_SCHEMA, true, gbnf) == grammar_status::applied);

    const std::set<std::string> kinds = { "task", "appointment" };
    const std::set<std::string> whens = { "none", "T1", "T2" };
    const std::set<std::string> segments = { "S1", "S2", "S3" };
    const std::set<std::string> certainties = { "confirmed", "tentative" };
    const std::set<std::string> todo_keys = { "text", "kind", "when", "segment", "certainty" };

    size_t todos_seen = 0;
    size_t walks_ended = 0;
    for (unsigned seed = 1; seed <= 6; ++seed) {
        std::string out;
        const bool ended = walk(vocab, gbnf, seed, 600, out);
        CHECK(ended);
        if (!ended) {
            continue;
        }
        ++walks_ended;
        if (seed == 1) {
            printf("sample schema walk: %s\n", out.c_str());
        }

        nlohmann::json reply;
        try {
            reply = nlohmann::json::parse(out);
        } catch (const std::exception &) {
            CHECK(!"the walk's output parses as JSON");
            continue;
        }

        CHECK(reply.is_object());
        CHECK(reply.size() == 2);
        CHECK(reply.contains("summary") && reply["summary"].is_string());
        CHECK(reply.contains("todos") && reply["todos"].is_array());
        if (!reply.contains("summary") || !reply.contains("todos") || !reply["todos"].is_array()) {
            continue;
        }
        CHECK(reply["summary"].is_string() && utf8_length(reply["summary"].get<std::string>()) <= 24);
        CHECK(reply["todos"].size() <= 2);
        for (const auto & todo : reply["todos"]) {
            ++todos_seen;
            CHECK(todo.is_object() && todo.size() == todo_keys.size());
            for (const auto & [key, value] : todo.items()) {
                CHECK(todo_keys.count(key) == 1);
                CHECK(value.is_string());
            }
            CHECK(kinds.count(todo.value("kind", "")) == 1);
            CHECK(whens.count(todo.value("when", "")) == 1);
            CHECK(segments.count(todo.value("segment", "")) == 1);
            CHECK(certainties.count(todo.value("certainty", "")) == 1);
            CHECK(utf8_length(todo.value("text", "")) <= 16);
        }
    }
    printf("schema walks: %zu of 6 ended, %zu todos checked\n", walks_ended, todos_seen);
    // Some walk must have produced a todo, or the enum checks above never ran.
    CHECK(todos_seen > 0);
}

// --bench: what one full-vocabulary grammar mask costs. common_sampler_sample() only checks the
// sampled token against the grammar and masks the whole vocabulary when that token doesn't fit,
// so this is the cost of each such resample. Inside an enum it grows with the enum's size.
static double mask_ms(const llama_vocab * vocab, const std::string & schema, const std::string & prefix) {
    std::string gbnf;
    if (grammar_to_gbnf(schema, true, gbnf) != grammar_status::applied) {
        return -1;
    }
    llama_sampler * grammar = llama_sampler_init_grammar(vocab, gbnf.c_str(), "root");
    if (grammar == nullptr) {
        return -1;
    }
    double ms = -1;
    try {
        for (const auto token : common_tokenize(vocab, prefix, false, false)) {
            llama_sampler_accept(grammar, token);
        }
        const int n_vocab = llama_vocab_n_tokens(vocab);
        std::vector<llama_token_data> cur(n_vocab);
        for (llama_token id = 0; id < n_vocab; ++id) {
            cur[id] = llama_token_data{ id, 0.0f, 0.0f };
        }
        llama_token_data_array candidates = { cur.data(), cur.size(), -1, false };
        const int64_t start = ggml_time_us();
        llama_sampler_apply(grammar, &candidates);
        ms = (ggml_time_us() - start) / 1000.0;
    } catch (const std::exception &) {
        ms = -1;
    }
    llama_sampler_free(grammar);
    return ms;
}

static void bench_masks(const llama_model * model) {
    const llama_vocab * vocab = llama_model_get_vocab(model);
    const std::string text_schema = R"({"type":"object","properties":{"text":{"type":"string"}},"required":["text"]})";
    printf("mask inside a free string: %.1f ms\n", mask_ms(vocab, text_schema, "{\"text\": \"Call"));
    for (const int n : { 10, 100, 500 }) {
        std::string schema = R"({"type":"object","properties":{"segment":{"enum":[)";
        for (int i = 1; i <= n; ++i) {
            schema += (i > 1 ? ",\"S" : "\"S") + std::to_string(i) + "\"";
        }
        schema += R"(]}},"required":["segment"]})";
        printf("mask inside an enum of %3d ids: %.1f ms\n", n, mask_ms(vocab, schema, "{\"segment\": \"S"));
    }
}

static void test_logs_never_quote_the_grammar() {
    bool leaked = false;
    for (const auto & line : g_llama_logs) {
        if (line.find(MARKER) != std::string::npos) {
            leaked = true;
        }
    }
    CHECK(!leaked);
}

int main(int argc, char ** argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: %s <path to ggml-vocab-gemma-4.gguf> [--bench]\n", argv[0]);
        return 2;
    }
    const bool bench = argc > 2 && std::string(argv[2]) == "--bench";

    llama_log_set(capture_log, nullptr);
    llama_backend_init();

    llama_model_params params = llama_model_default_params();
    params.vocab_only = true;
    llama_model * model = llama_model_load_from_file(argv[1], params);
    if (model == nullptr) {
        fprintf(stderr, "could not load the vocabulary from %s\n", argv[1]);
        return 2;
    }
    printf("vocabulary: %d tokens\n", llama_vocab_n_tokens(llama_model_get_vocab(model)));

    test_conversion_statuses();
    test_bad_grammars_fall_back(model);
    test_applied_grammar_constrains_and_free_text_does_not(model);
    test_grammar_params_drop_penalties();
    test_grammar_sampler_keeps_repeated_values(model);
    test_temperature_at_a_near_tie(model);
    test_grammar_lasts_one_reply(model);
    test_schema_needs_standard_utf8();
    test_walks_follow_the_gbnf(model);
    test_walks_follow_the_schema(model);
    test_logs_never_quote_the_grammar();
    if (bench) {
        bench_masks(model);
    }

    llama_model_free(model);
    llama_backend_free();

    printf("%d checks, %d failed\n", g_checks, g_failures);
    return g_failures == 0 ? 0 : 1;
}
