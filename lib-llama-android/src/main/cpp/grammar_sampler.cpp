#include "grammar_sampler.h"

#include <exception>
#include <utility>

#include <nlohmann/json.hpp>

#include "json-schema-to-grammar.h"
#include "llama.h"

const char * grammar_status_name(grammar_status status) {
    switch (status) {
        case grammar_status::applied:            return "applied";
        case grammar_status::empty:              return "empty grammar";
        case grammar_status::unsupported_syntax: return "llguidance syntax is not built in";
        case grammar_status::schema_not_json:    return "JSON schema is not valid JSON";
        case grammar_status::schema_unsupported: return "JSON schema not supported by json_schema_to_grammar";
        case grammar_status::gbnf_invalid:       return "GBNF did not parse";
        case grammar_status::sampler_failed:     return "sampler init failed";
    }
    return "unknown";
}

static bool is_blank(const std::string & text) {
    return text.find_first_not_of(" \t\r\n") == std::string::npos;
}

grammar_status grammar_to_gbnf(const std::string & text, bool is_json_schema, std::string & gbnf) {
    gbnf.clear();
    if (is_blank(text)) {
        return grammar_status::empty;
    }

    if (!is_json_schema) {
        // common_sampler_init() aborts the process on this prefix unless llama.cpp was built
        // with LLAMA_LLGUIDANCE, which this library doesn't do.
        if (text.compare(0, 11, "%llguidance") == 0) {
            return grammar_status::unsupported_syntax;
        }
        gbnf = text;
        return grammar_status::applied;
    }

    // The exception messages quote the schema, so callers get a status rather than the message.
    nlohmann::ordered_json schema;
    try {
        schema = nlohmann::ordered_json::parse(text);
    } catch (const std::exception &) {
        return grammar_status::schema_not_json;
    }
    try {
        // force_gbnf: GBNF even if a later build turns llguidance on.
        gbnf = json_schema_to_grammar(schema, /* force_gbnf = */ true);
    } catch (const std::exception &) {
        gbnf.clear();
        return grammar_status::schema_unsupported;
    }
    return gbnf.empty() ? grammar_status::schema_unsupported : grammar_status::applied;
}

common_params_sampling grammar_params(const common_params_sampling & base) {
    common_params_sampling params = base;

    // With these values llama.cpp builds empty "?penalties" and "?dry" samplers.
    params.penalty_last_n  = 0;
    params.penalty_repeat  = 1.0f;
    params.penalty_freq    = 0.0f;
    params.penalty_present = 0.0f;
    params.dry_multiplier  = 0.0f;

    // The grammar type only matters for prefilling the generation prompt into the grammar, and
    // with no generation_prompt nothing is prefilled.
    params.grammar_lazy = false;
    params.grammar_triggers.clear();
    params.generation_prompt.clear();
    return params;
}

grammar_sampler_result grammar_sampler_init(const llama_model * model,
                                            const common_params_sampling & base,
                                            const std::string & text,
                                            const bool is_json_schema) {
    grammar_sampler_result result;

    std::string gbnf;
    result.status = grammar_to_gbnf(text, is_json_schema, gbnf);
    if (result.status != grammar_status::applied) {
        return result;
    }
    if (model == nullptr) {
        result.status = grammar_status::sampler_failed;
        return result;
    }

    try {
        // common_sampler_init() in this llama.cpp version leaves out a grammar that fails to parse
        // and says nothing, so parse it here first: llama_sampler_init_grammar() returns null then.
        // On failure the parser prints the grammar to stderr, which is /dev/null in an Android app.
        llama_sampler * probe = llama_sampler_init_grammar(llama_model_get_vocab(model), gbnf.c_str(), "root");
        if (probe == nullptr) {
            result.status = grammar_status::gbnf_invalid;
            return result;
        }
        llama_sampler_free(probe);

        common_params_sampling params = grammar_params(base);
        // OUTPUT_FORMAT for schemas, as llama-server does.
        params.grammar = common_grammar(
            is_json_schema ? COMMON_GRAMMAR_TYPE_OUTPUT_FORMAT : COMMON_GRAMMAR_TYPE_USER, std::move(gbnf));
        result.sampler = common_sampler_init(model, params);
    } catch (...) {
        result.sampler = nullptr;
    }

    // Older llama.cpp versions return null from common_sampler_init() for a bad grammar.
    result.status = result.sampler != nullptr ? grammar_status::applied : grammar_status::sampler_failed;
    return result;
}

grammar_status grammar_reply::begin(const llama_model * model,
                                    const common_params_sampling & base,
                                    const std::string & text,
                                    const bool is_json_schema) {
    end();
    const grammar_sampler_result result = grammar_sampler_init(model, base, text, is_json_schema);
    sampler_ = result.sampler;
    return result.status;
}

void grammar_reply::end() {
    if (sampler_ != nullptr) {
        common_sampler_free(sampler_);
        sampler_ = nullptr;
    }
}
