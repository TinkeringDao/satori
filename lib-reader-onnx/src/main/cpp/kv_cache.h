// The decoder's attention caches for one segment, in buffers allocated once per segment, so the
// per-token loop never asks ONNX Runtime's arena for them (D3) and their memory goes back when
// the segment ends.
//
// - Self-attention: two buffers, `past` (fed to decoder_with_past) and `present` (the Run writes
//   into it). Each holds, per layer and per key and value, up to beam x heads x max_len x head_dim
//   floats; a step uses the dense prefix [n, heads, len, head_dim]. After a step the children's
//   rows are gathered from present into past in beam order; when the beams go on unchanged, the
//   two buffers swap instead.
// - Cross-attention: decoder_model's first step computes it once for the source; it is copied to
//   every beam slot and fed unchanged to every step. decoder_with_past re-emits it unchanged; the
//   reader binds that output to this same buffer, so nothing is copied or allocated for it.
//
// Every size is checked against the limits below and against overflow before anything is
// allocated; an allocation that fails is kOutOfMemory.

#pragma once

#include <cstddef>
#include <cstdint>
#include <memory>

#include "reader_status.h"

namespace satori::reader {

class KvCache {
public:
    struct Shape {
        size_t layers = 0;
        size_t heads = 0;
        size_t head_dim = 0;
        size_t beams = 0;
        size_t max_len = 0;     // self-attention entries at most: the generated-token cap
        size_t source_len = 0;  // cross-attention entries
    };

    // The bounds of a shape the reader accepts from a model: far above IndicTrans2's (18 layers, 8
    // heads of 64, 256 positions) and low enough that no product overflows.
    static constexpr size_t kMaxLayers = 64;
    static constexpr size_t kMaxHeads = 64;
    static constexpr size_t kMaxHeadDim = 1024;
    static constexpr size_t kMaxLen = 4096;
    // 1.5 GiB for the three buffers together, so a model with odd shapes fails instead of taking
    // the phone's memory.
    static constexpr size_t kMaxBytes = size_t{3} << 29;

    Status Allocate(const Shape& shape);

    const Shape& shape() const { return shape_; }

    // The cross cache of `layer` (kv 0 = key, 1 = value): beams x [heads, source_len, head_dim].
    float* cross(size_t layer, size_t kv) const;
    // The self cache buffers of `layer` and `kv`.
    float* past(size_t layer, size_t kv) const;
    float* present(size_t layer, size_t kv) const;

    // Copies one beam's cross cache ([heads, source_len, head_dim] floats) into every beam slot.
    Status FillCross(size_t layer, size_t kv, const float* one_beam, size_t floats);

    // Puts each child's self cache into past: child j takes row parents[j] of `source`, whose
    // layer and kv blocks are source[layer * 2 + kv] (each [rows, heads, len, head_dim]).
    // source == nullptr means present(): after a decoder_with_past step. When the children are
    // exactly the rows in order, past and present swap instead of copying.
    Status Gather(const float* const* source, size_t rows, size_t len, const int32_t* parents,
                  size_t children);

private:
    struct FreeDeleter {
        void operator()(float* p) const;
    };
    using Buffer = std::unique_ptr<float, FreeDeleter>;

    size_t self_block() const;   // floats of one layer's key or value in a self buffer
    size_t cross_block() const;  // floats of one layer's key or value in the cross buffer

    Shape shape_{};
    Buffer self_a_;
    Buffer self_b_;
    Buffer cross_;
    float* past_ = nullptr;
    float* present_ = nullptr;
};

// a * b, or false when it would overflow size_t.
bool MultiplyChecked(size_t a, size_t b, size_t* out);

}  // namespace satori::reader
