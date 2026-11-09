#include "spm_tokenizer.h"

#include <utility>

#include "sentencepiece_processor.h"

namespace satori::reader {

Tokenizer::Tokenizer() = default;
Tokenizer::~Tokenizer() = default;

Status Tokenizer::Load(const std::string& path, std::string* error) {
    processor_.reset();
    if (path.empty()) {
        *error = "no tokenizer path";
        return Status::kBadInput;
    }
    auto processor = std::make_unique<sentencepiece::SentencePieceProcessor>();
    const sentencepiece::util::Status status = processor->Load(path);
    if (!status.ok()) {
        *error = status.ToString();
        return Status::kLoadFailed;
    }
    processor_ = std::move(processor);
    return Status::kOk;
}

Status Tokenizer::Pieces(std::string_view text, std::vector<std::string>* pieces, std::string* error) const {
    if (pieces == nullptr) {
        return Status::kInternal;
    }
    pieces->clear();
    if (processor_ == nullptr) {
        *error = "tokenizer not loaded";
        return Status::kNotLoaded;
    }
    if (text.size() > kMaxTextBytes) {
        *error = "text longer than " + std::to_string(kMaxTextBytes) + " bytes";
        return Status::kBadInput;
    }
    if (!IsValidUtf8(text)) {
        *error = "text is not valid UTF-8";
        return Status::kBadInput;
    }
    const sentencepiece::util::Status status = processor_->Encode(text, pieces);
    if (!status.ok()) {
        pieces->clear();
        *error = status.ToString();
        return Status::kRunFailed;
    }
    return Status::kOk;
}

bool IsValidUtf8(std::string_view text) {
    const auto* bytes = reinterpret_cast<const unsigned char*>(text.data());
    const size_t size = text.size();
    size_t i = 0;
    while (i < size) {
        const unsigned lead = bytes[i];
        if (lead < 0x80) {
            ++i;
            continue;
        }
        size_t length = 0;
        unsigned code = 0;
        unsigned lowest = 0;
        if (lead >= 0xC2 && lead <= 0xDF) {
            length = 2;
            code = lead & 0x1Fu;
            lowest = 0x80;
        } else if (lead >= 0xE0 && lead <= 0xEF) {
            length = 3;
            code = lead & 0x0Fu;
            lowest = 0x800;
        } else if (lead >= 0xF0 && lead <= 0xF4) {
            length = 4;
            code = lead & 0x07u;
            lowest = 0x10000;
        } else {
            return false;
        }
        if (size - i < length) {
            return false;
        }
        for (size_t k = 1; k < length; ++k) {
            const unsigned next = bytes[i + k];
            if ((next & 0xC0u) != 0x80u) {
                return false;
            }
            code = (code << 6) | (next & 0x3Fu);
        }
        if (code < lowest || code > 0x10FFFF || (code >= 0xD800 && code <= 0xDFFF)) {
            return false;
        }
        i += length;
    }
    return true;
}

}  // namespace satori::reader
