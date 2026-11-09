// Gives a model file's pages back to the kernel, for ai_chat.cpp: the pages of the tensors a load
// copied out of its mapping (drop_copied, the hook of lib-llama-android/patches/0003-mapped-hook.patch),
// and all of a file's cached pages for a debug build's cold load (drop_file_pages). Linux and
// Android only.
//
// Models live under the app's Android/data, which apps reach through FUSE with a BPF backing: a
// mapping there maps the lower file's pages, while posix_fadvise reaches only the FUSE file's own
// cache, which holds none of them. Measured on the emulator (Android 15, 6.6, in an app's mount
// namespace): after posix_fadvise(DONTNEED) 64 of 64 MB were still cached, and Gemma 3 1B's copied
// tensors' 707 MB stayed after posix_fadvise or MADV_DONTNEED alone, while madvise(MADV_PAGEOUT)
// gave back all of them. MADV_PAGEOUT acts only on the pages present in the calling process's own
// mapping that no other mapping holds, and only for a file the process owns or may write
// (can_do_file_pageout); mincore tells the truth only about such a file too (can_do_mincore).
#pragma once

#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <fcntl.h>
#include <string>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

#include "copied_pages.h"

#ifndef MADV_PAGEOUT
#define MADV_PAGEOUT 21
#endif

namespace satori {

// RssFile of this process, in kB (/proc/self/status); -1 when it can't be read.
inline int64_t rss_file_kb() {
    FILE * f = fopen("/proc/self/status", "re");
    if (f == nullptr) return -1;
    char line[256];
    int64_t kb = -1;
    while (fgets(line, sizeof line, f) != nullptr) {
        if (strncmp(line, "RssFile:", 8) == 0) kb = atoll(line + 8);
    }
    fclose(f);
    return kb;
}

inline int64_t now_us() {
    timespec t{};
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (int64_t) t.tv_sec * 1000000 + t.tv_nsec / 1000;
}

// Whether this process owns the file open as `fd` or may write it: then MADV_PAGEOUT may act on its
// pages, and mincore tells the truth about them.
inline bool own_or_writable(const int fd) {
    struct stat st{};
    if (fstat(fd, &st) != 0) return false;
    if (st.st_uid == getuid()) return true;
    const std::string self_fd = "/proc/self/fd/" + std::to_string(fd);
    return access(self_fd.c_str(), W_OK) == 0;
}

// The bytes of [addr, addr + len) of a mapping in the page cache (mincore); -1 when it can't be read.
inline int64_t cached_bytes(const uint8_t * addr, const size_t len, const size_t page) {
    std::vector<unsigned char> vec((len + page - 1) / page, 0);
    if (mincore(const_cast<uint8_t *>(addr), len, vec.data()) != 0) return -1;
    int64_t cached = 0;
    for (unsigned char v : vec) cached += (v & 1) ? (int64_t) page : 0;
    return cached;
}

struct drop_result {
    int64_t copied_bytes = 0;    // bytes of the tensors the load copied
    int64_t dropped_bytes = 0;   // bytes of the whole pages given back
    int64_t cached_bytes = -1;   // of those, still in the page cache afterwards (mincore); -1 unknown
    int32_t ranges = 0;
    int32_t error = 0;           // errno of the first call that failed, 0 for none
    bool    pageout = false;     // whether MADV_PAGEOUT may act (the file is the app's, or writable)
    int64_t rss_file_before_kb = -1;
    int64_t rss_file_after_kb = -1;
    int64_t us = 0;
};

// Gives back the whole pages of a mapping of `size` bytes of the file open as `fd` that only the
// `copied` tensors lie on (copied_only_pages): madvise(MADV_PAGEOUT) reclaims them from the page
// cache (EINVAL, a kernel before 5.4, is passed over), MADV_DONTNEED then unmaps whatever is left
// (a page pageout skipped), and posix_fadvise(DONTNEED) drops it where the descriptor's cache is
// the file's (internal storage, plain FUSE). The mapping stays: a read of those bytes would come
// back from the file, never wrong. Pages an `in_place` tensor has a byte on are never touched.
inline drop_result drop_copied(void * addr, const size_t size, const int fd, const std::vector<byte_range> & copied,
                               const std::vector<byte_range> & in_place) {
    drop_result out;
    const int64_t start = now_us();
    out.rss_file_before_kb = rss_file_kb();
    for (const auto & r : copied) out.copied_bytes += (int64_t) (r.second - r.first);
    const long page_size = sysconf(_SC_PAGESIZE);
    const size_t page = page_size > 0 ? (size_t) page_size : 4096;
    const auto pages = copied_only_pages(copied, in_place, page, size);
    out.pageout = own_or_writable(fd);
    auto * base = static_cast<uint8_t *>(addr);
    for (const auto & p : pages) {
        const size_t len = p.second - p.first;
        if (madvise(base + p.first, len, MADV_PAGEOUT) != 0 && errno != EINVAL && out.error == 0) out.error = errno;
        if (madvise(base + p.first, len, MADV_DONTNEED) != 0 && out.error == 0) out.error = errno;
        const int advised = posix_fadvise(fd, (off_t) p.first, (off_t) len, POSIX_FADV_DONTNEED);
        if (advised != 0 && out.error == 0) out.error = advised;
        out.dropped_bytes += (int64_t) len;
        out.ranges++;
    }
    if (out.pageout) {
        out.cached_bytes = 0;
        for (const auto & p : pages) {
            const int64_t cached = cached_bytes(base + p.first, p.second - p.first, page);
            if (cached < 0) {
                out.cached_bytes = -1;
                break;
            }
            out.cached_bytes += cached;
        }
    }
    out.rss_file_after_kb = rss_file_kb();
    out.us = now_us() - start;
    return out;
}

// drop_copied for the ranges as the hook of 0003-mapped-hook.patch hands them over: pairs of first
// and end offsets, n_copied and n_in_place pairs. Nothing is given back when nothing was copied.
inline drop_result drop_copied_pairs(void * addr, const size_t size, const int fd, const size_t * copied, const size_t n_copied,
                                     const size_t * in_place, const size_t n_in_place) {
    std::vector<byte_range> copied_ranges;
    std::vector<byte_range> in_place_ranges;
    copied_ranges.reserve(n_copied);
    in_place_ranges.reserve(n_in_place);
    for (size_t i = 0; i < n_copied; i++) copied_ranges.emplace_back(copied[2 * i], copied[2 * i + 1]);
    for (size_t i = 0; i < n_in_place; i++) in_place_ranges.emplace_back(in_place[2 * i], in_place[2 * i + 1]);
    return drop_copied(addr, size, fd, copied_ranges, in_place_ranges);
}

// Adds the drop of another file of the same load to `total`.
inline void add_drop(drop_result & total, const drop_result & drop) {
    total.copied_bytes += drop.copied_bytes;
    total.dropped_bytes += drop.dropped_bytes;
    total.cached_bytes = total.cached_bytes < 0 || drop.cached_bytes < 0 ? -1 : total.cached_bytes + drop.cached_bytes;
    total.ranges += drop.ranges;
    if (total.error == 0) total.error = drop.error;
    total.pageout = total.pageout && drop.pageout;
    total.rss_file_after_kb = drop.rss_file_after_kb;
    total.us += drop.us;
}

struct file_drop_result {
    int64_t size_bytes = 0;
    int64_t cached_before_bytes = -1;  // the file's bytes in the page cache before (mincore); -1 unknown
    int64_t cached_after_bytes = -1;   // and after
    int32_t error = 0;                 // errno of the first call that failed, 0 for none
    bool    seen = false;              // whether mincore tells the truth about the file (pageout may act)
    int64_t us = 0;
};

// Drops a file's pages from the page cache, for a debug build's cold load and its pre-read probe. A
// fresh mapping has none of them present, and MADV_PAGEOUT acts only on present pages, so each
// piece of 8 MiB is looked at with mincore, its cached pages are mapped in by one read each (a minor
// fault: they are cached, so nothing is read from flash, and MADV_RANDOM keeps a fault from reading
// ahead), and the piece is paged out; then posix_fadvise drops what is left where it can (internal
// storage, plain FUSE). Only pages no other mapping holds go: a model in memory keeps its own. A file
// that is neither the app's nor writable by it gets posix_fadvise alone: its pages can't be seen, and
// a read of every page would read the whole file in.
inline file_drop_result drop_file_pages(const char * path) {
    file_drop_result out;
    const int64_t start = now_us();
    const int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        out.error = errno;
        out.us = now_us() - start;
        return out;
    }
    struct stat st{};
    if (fstat(fd, &st) != 0) {
        out.error = errno;
        close(fd);
        out.us = now_us() - start;
        return out;
    }
    out.size_bytes = st.st_size;
    const long page_size = sysconf(_SC_PAGESIZE);
    const size_t page = page_size > 0 ? (size_t) page_size : 4096;
    const size_t size = (size_t) st.st_size;
    out.seen = size > 0 && own_or_writable(fd);
    if (out.seen) {
        void * map = mmap(nullptr, size, PROT_READ, MAP_SHARED, fd, 0);
        if (map == MAP_FAILED) {
            out.error = errno;
            out.seen = false;
        } else {
            auto * base = static_cast<uint8_t *>(map);
            if (madvise(base, size, MADV_RANDOM) != 0 && out.error == 0) out.error = errno;
            const size_t piece = (size_t) 8 << 20;
            std::vector<unsigned char> vec(piece / page, 0);
            out.cached_before_bytes = 0;
            volatile uint8_t sink = 0;
            for (size_t at = 0; at < size; at += piece) {
                const size_t len = std::min(piece, size - at);
                if (mincore(base + at, len, vec.data()) != 0) {
                    if (out.error == 0) out.error = errno;
                    out.cached_before_bytes = -1;
                    break;
                }
                for (size_t i = 0; i < (len + page - 1) / page; i++) {
                    if (vec[i] & 1) {
                        sink = sink + base[at + i * page];
                        out.cached_before_bytes += (int64_t) page;
                    }
                }
                if (madvise(base + at, len, MADV_PAGEOUT) != 0 && errno != EINVAL && out.error == 0) out.error = errno;
            }
            (void) sink;
            munmap(map, size);
        }
    }
    const int advised = posix_fadvise(fd, 0, 0, POSIX_FADV_DONTNEED);
    if (advised != 0 && out.error == 0) out.error = advised;
    if (out.seen) {
        // A fresh mapping, nothing in it touched: what is cached now.
        void * map = mmap(nullptr, size, PROT_READ, MAP_SHARED, fd, 0);
        if (map != MAP_FAILED) {
            out.cached_after_bytes = cached_bytes(static_cast<uint8_t *>(map), size, page);
            munmap(map, size);
        }
    }
    close(fd);
    out.us = now_us() - start;
    return out;
}

} // namespace satori
