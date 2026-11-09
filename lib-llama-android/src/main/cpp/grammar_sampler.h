#pragma once

// Grammar-constrained sampling for one reply. Plain C++ without JNI or Android logging, so the
// host test in src/test/cpp can build it against the same llama.cpp checkout.

#include <string>

#include "sampling.h"

// What happened to a requested grammar. Anything but `applied` means the reply is free text.
enum class grammar_status {
    applied,             // the sampler enforces the grammar
    empty,               // the grammar text is blank
    unsupported_syntax,  // a %llguidance grammar: this build leaves LLAMA_LLGUIDANCE off
    schema_not_json,     // the JSON schema does not parse as JSON
    schema_unsupported,  // json_schema_to_grammar() rejected the schema
    gbnf_invalid,        // llama.cpp could not parse the GBNF, or it has no `root` rule
    sampler_failed,      // common_sampler_init() failed
};

// A short description for logs. Never includes the grammar itself.
const char * grammar_status_name(grammar_status status);

// Turns the requested grammar into GBNF. A JSON schema goes through llama.cpp's
// json_schema_to_grammar(); GBNF passes through unchanged. The text must be standard UTF-8, not
// JNI's Modified UTF-8: nlohmann::json rejects the surrogates that Modified UTF-8 writes for a
// character outside the BMP, such as an emoji. Doesn't parse the GBNF: that needs the vocabulary
// (see grammar_sampler_init). Never throws.
grammar_status grammar_to_gbnf(const std::string & text, bool is_json_schema, std::string & gbnf);

// The sampling settings of a grammar reply: those of `base`, the free-text sampler, without its
// repetition penalties (repeat, frequency, presence and DRY). Temperature, top-k, top-p and min-p
// stay. A structured reply repeats its keys and enum values by design ("kind": "task" in every
// todo), and a penalty would push a value already written below another value the grammar also
// allows: a wrong answer that still parses. No lazy grammar and no prefill either: the grammar
// covers the whole generated reply.
common_params_sampling grammar_params(const common_params_sampling & base);

struct grammar_sampler_result {
    common_sampler * sampler = nullptr;  // owned by the caller (common_sampler_free); set only when applied
    grammar_status   status  = grammar_status::empty;
};

// Builds a sampler for the grammar with grammar_params(base). Never throws: on any failure the
// sampler is null and the status says why, so the caller can keep sampling free text.
grammar_sampler_result grammar_sampler_init(const llama_model * model,
                                            const common_params_sampling & base,
                                            const std::string & text,
                                            bool is_json_schema);

// The grammar of the reply being generated, if it has one. ai_chat.cpp keeps one for the engine's
// single reply. begin() first ends the previous reply's grammar, so a grammar never outlives its
// reply, and a reply that doesn't begin one is free text. end() frees it as soon as the reply stops.
class grammar_reply {
  public:
    grammar_reply() = default;
    ~grammar_reply() { end(); }
    grammar_reply(const grammar_reply &) = delete;
    grammar_reply & operator=(const grammar_reply &) = delete;

    // Ends the previous reply's grammar, then applies `text` to this reply (see
    // grammar_sampler_init). With any status but `applied` this reply is free text.
    grammar_status begin(const llama_model * model, const common_params_sampling & base,
                         const std::string & text, bool is_json_schema);

    // Frees the grammar: the rest of the reply and later replies are free text. Safe to repeat.
    void end();

    // True while a grammar constrains the reply.
    bool active() const { return sampler_ != nullptr; }

    // The sampler for the next token: the grammar's while one is active, else `free_text`, which is
    // passed in because updateSamplerConfig may replace the free-text sampler during a reply.
    common_sampler * sampler(common_sampler * free_text) const { return sampler_ ? sampler_ : free_text; }

  private:
    common_sampler * sampler_ = nullptr;
};
