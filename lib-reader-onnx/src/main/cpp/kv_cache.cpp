#include "kv_cache.h"

#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <utility>

#include "beam_search.h"

namespace satori::reader {

bool MultiplyChecked(size_t a, size_t b, size_t* out) {
    if (a != 0 && b > SIZE_MAX / a) {
        return false;
    }
    *out = a * b;
    return true;
}

void KvCache::FreeDeleter::operator()(float* p) const { std::free(p); }

namespace {

// malloc rather than new[]: nothing touches the pages until a step writes them, so a segment
// commits only the part of the cache its tokens use.
float* AllocateFloats(size_t floats) {
    size_t bytes = 0;
    if (floats == 0 || !MultiplyChecked(floats, sizeof(float), &bytes)) {
        return nullptr;
    }
    return static_cast<float*>(std::malloc(bytes));
}

}  // namespace

size_t KvCache::self_block() const { return shape_.beams * shape_.heads * shape_.max_len * shape_.head_dim; }

size_t KvCache::cross_block() const { return shape_.beams * shape_.heads * shape_.source_len * shape_.head_dim; }

Status KvCache::Allocate(const Shape& shape) {
    self_a_.reset();
    self_b_.reset();
    cross_.reset();
    past_ = nullptr;
    present_ = nullptr;
    shape_ = Shape{};
    if (shape.layers == 0 || shape.layers > kMaxLayers || shape.heads == 0 || shape.heads > kMaxHeads ||
        shape.head_dim == 0 || shape.head_dim > kMaxHeadDim) {
        return Status::kBadModel;
    }
    if (shape.beams == 0 || shape.beams > static_cast<size_t>(kMaxBeam) || shape.max_len == 0 ||
        shape.max_len > kMaxLen || shape.source_len == 0 || shape.source_len > kMaxLen) {
        return Status::kBadInput;
    }
    // The bounds above keep heads x head_dim and beams x layers x 2 small; the rest is checked.
    size_t per_beam_self = 0;
    size_t per_beam_cross = 0;
    size_t self_total = 0;
    size_t cross_total = 0;
    size_t bytes = 0;
    const bool fits = MultiplyChecked(shape.heads * shape.head_dim, shape.max_len, &per_beam_self) &&
                      MultiplyChecked(shape.heads * shape.head_dim, shape.source_len, &per_beam_cross) &&
                      MultiplyChecked(per_beam_self, shape.beams * shape.layers * 2, &self_total) &&
                      MultiplyChecked(per_beam_cross, shape.beams * shape.layers * 2, &cross_total) &&
                      self_total <= SIZE_MAX / 2 && 2 * self_total <= SIZE_MAX - cross_total &&
                      MultiplyChecked(2 * self_total + cross_total, sizeof(float), &bytes);
    if (!fits || bytes > kMaxBytes) {
        return Status::kOutOfMemory;
    }
    Buffer a(AllocateFloats(self_total));
    Buffer b(AllocateFloats(self_total));
    Buffer c(AllocateFloats(cross_total));
    if (!a || !b || !c) {
        return Status::kOutOfMemory;
    }
    shape_ = shape;
    self_a_ = std::move(a);
    self_b_ = std::move(b);
    cross_ = std::move(c);
    past_ = self_a_.get();
    present_ = self_b_.get();
    return Status::kOk;
}

float* KvCache::cross(size_t layer, size_t kv) const {
    if (!cross_ || layer >= shape_.layers || kv > 1) {
        return nullptr;
    }
    return cross_.get() + (layer * 2 + kv) * cross_block();
}

float* KvCache::past(size_t layer, size_t kv) const {
    if (past_ == nullptr || layer >= shape_.layers || kv > 1) {
        return nullptr;
    }
    return past_ + (layer * 2 + kv) * self_block();
}

float* KvCache::present(size_t layer, size_t kv) const {
    if (present_ == nullptr || layer >= shape_.layers || kv > 1) {
        return nullptr;
    }
    return present_ + (layer * 2 + kv) * self_block();
}

Status KvCache::FillCross(size_t layer, size_t kv, const float* one_beam, size_t floats) {
    float* slots = cross(layer, kv);
    if (slots == nullptr || one_beam == nullptr || floats != shape_.heads * shape_.source_len * shape_.head_dim) {
        return Status::kInternal;
    }
    for (size_t beam = 0; beam < shape_.beams; ++beam) {
        std::memcpy(slots + beam * floats, one_beam, floats * sizeof(float));
    }
    return Status::kOk;
}

Status KvCache::Gather(const float* const* source, size_t rows, size_t len, const int32_t* parents,
                       size_t children) {
    if (past_ == nullptr || parents == nullptr || rows == 0 || rows > shape_.beams || children == 0 ||
        children > shape_.beams || len == 0 || len > shape_.max_len) {
        return Status::kInternal;
    }
    bool in_order = source == nullptr && children == rows;
    for (size_t child = 0; child < children; ++child) {
        if (parents[child] < 0 || static_cast<size_t>(parents[child]) >= rows) {
            return Status::kInternal;
        }
        in_order = in_order && static_cast<size_t>(parents[child]) == child;
    }
    if (in_order) {
        std::swap(past_, present_);
        return Status::kOk;
    }
    const size_t row_floats = shape_.heads * len * shape_.head_dim;
    for (size_t layer = 0; layer < shape_.layers; ++layer) {
        for (size_t kv = 0; kv < 2; ++kv) {
            const float* from = source == nullptr ? present(layer, kv) : source[layer * 2 + kv];
            float* to = past(layer, kv);
            if (from == nullptr || to == nullptr) {
                return Status::kInternal;
            }
            for (size_t child = 0; child < children; ++child) {
                std::memcpy(to + child * row_floats, from + static_cast<size_t>(parents[child]) * row_floats,
                            row_floats * sizeof(float));
            }
        }
    }
    return Status::kOk;
}

}  // namespace satori::reader
