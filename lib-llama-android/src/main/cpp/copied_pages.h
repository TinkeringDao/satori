// Which pages of a model file's mapping only the tensors a load copied out of it lie on: pure
// arithmetic, which src/test/cpp/copied_pages_test.cpp checks on the host. ai_chat.cpp's hook of
// lib-llama-android/patches/0003-mapped-hook.patch gives those pages back (drop_pages.h).
#pragma once

#include <algorithm>
#include <cstddef>
#include <utility>
#include <vector>

namespace satori {

// A byte range of the file: first byte, end (one past the last).
using byte_range = std::pair<size_t, size_t>;

// The whole pages of the first `size` bytes of the file that no in-place tensor has a byte on and
// copied tensors cover: the copied ranges joined across gaps under a page (a tensor's alignment
// padding, or a small tensor used in place, whose pages are taken out below), shrunk inward to page
// boundaries, minus each in-place range widened outward to page boundaries. A page an in-place
// tensor shares with a copied one stays, as llama.cpp's own unmap_fragment and the kernel's
// posix_fadvise keep partial pages. Sorted and disjoint; both ends of each are multiples of `page`.
inline std::vector<byte_range> copied_only_pages(std::vector<byte_range> copied, std::vector<byte_range> in_place,
                                                 const size_t page, const size_t size) {
    std::vector<byte_range> out;
    if (page == 0) return out;
    std::sort(copied.begin(), copied.end());
    std::sort(in_place.begin(), in_place.end());

    std::vector<byte_range> joined;
    for (const auto & r : copied) {
        if (r.second <= r.first) continue;
        if (!joined.empty() && r.first < joined.back().second + page) {
            joined.back().second = std::max(joined.back().second, r.second);
        } else {
            joined.push_back(r);
        }
    }

    // In-place ranges widened to whole pages, joined where they meet.
    std::vector<byte_range> keep;
    for (const auto & r : in_place) {
        if (r.second <= r.first) continue;
        const byte_range k{r.first / page * page, (r.second + page - 1) / page * page};
        if (!keep.empty() && k.first <= keep.back().second) {
            keep.back().second = std::max(keep.back().second, k.second);
        } else {
            keep.push_back(k);
        }
    }

    size_t next = 0;
    const size_t limit = size / page * page;
    for (const auto & r : joined) {
        size_t first = (r.first + page - 1) / page * page;
        const size_t end = std::min(r.second, limit) / page * page;
        while (first < end) {
            while (next < keep.size() && keep[next].second <= first) next++;
            if (next == keep.size() || keep[next].first >= end) {
                out.emplace_back(first, end);
                break;
            }
            if (keep[next].first > first) out.emplace_back(first, keep[next].first);
            first = keep[next].second;
        }
    }
    return out;
}

} // namespace satori
