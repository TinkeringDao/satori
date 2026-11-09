// The reader's SentencePiece: model.SRC, used as IndicTransTokenizer uses it. Its _src_tokenize
// splits the two language tags off the preprocessed line and calls EncodeAsPieces on the rest,
// with the model's own normalizer and no extra options; that rest is what Pieces() takes. The tags,
// the pieces' ids through dict.SRC.json and the closing </s> are the Kotlin side's (PieceIds).
// The same calls serve the debug tokenizer selftest (V0b), which compares the pieces with
// references made off this machine.

#pragma once

#include <cstddef>
#include <memory>
#include <string>
#include <string_view>
#include <vector>

#include "reader_status.h"

namespace sentencepiece {
class SentencePieceProcessor;
}

namespace satori::reader {

class Tokenizer {
public:
    // A segment's text is far below this; longer input is refused rather than encoded.
    static constexpr size_t kMaxTextBytes = size_t{1} << 16;

    Tokenizer();
    ~Tokenizer();
    Tokenizer(const Tokenizer&) = delete;
    Tokenizer& operator=(const Tokenizer&) = delete;

    // kLoadFailed with SentencePiece's message for a file it can't read or parse.
    Status Load(const std::string& path, std::string* error);
    bool loaded() const { return processor_ != nullptr; }

    // The pieces of `text` (UTF-8). kBadInput for text that is not valid UTF-8 or too long:
    // SentencePiece's own handling of broken UTF-8 is where its known crash fixes were, so broken
    // bytes never reach it.
    Status Pieces(std::string_view text, std::vector<std::string>* pieces, std::string* error) const;

private:
    std::unique_ptr<sentencepiece::SentencePieceProcessor> processor_;
};

// Well-formed UTF-8 as Unicode defines it: no overlong forms, no surrogates, nothing above
// U+10FFFF, no truncated sequence.
bool IsValidUtf8(std::string_view text);

}  // namespace satori::reader
