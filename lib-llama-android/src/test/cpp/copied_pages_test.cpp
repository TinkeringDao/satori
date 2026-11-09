// Host test for copied_pages.h, the rule behind dropcopied (LoadSettings.kt; ai_chat.cpp's on_mapped
// on lib-llama-android/patches/0003-mapped-hook.patch): which whole pages of a model file's mapping
// only the tensors a load copied lie on, which are the ones given back. It runs on the development
// machine with no phone and no weights, on the edge cases and on the tensor tables of Gemma 3 1B and
// Gemma 4 E2B (lib-llama-android/src/test/resources/gguf/*.tensors.tsv), laid out as their GGUF
// headers place them: 32-byte aligned from the data start each header gives.
//
// cmake and ninja come with the Android SDK ($ANDROID_HOME/cmake/<version>/bin):
//
//   cmake -S lib-llama-android/src/test/cpp -B /tmp/grammar-host -G Ninja -DCMAKE_BUILD_TYPE=Release
//   cmake --build /tmp/grammar-host --target copied_pages_test
//   /tmp/grammar-host/copied_pages_test lib-llama-android/src/test/resources/gguf

#include <cstdio>
#include <fstream>
#include <regex>
#include <sstream>
#include <string>
#include <vector>

#include "copied_pages.h"

using satori::byte_range;
using satori::copied_only_pages;

static int g_checks = 0;
static int g_failures = 0;

#define CHECK(cond)                                                              \
    do {                                                                         \
        ++g_checks;                                                              \
        if (!(cond)) {                                                           \
            ++g_failures;                                                        \
            fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond);      \
        }                                                                        \
    } while (0)

static size_t total(const std::vector<byte_range> &ranges) {
    size_t bytes = 0;
    for (const auto &r : ranges) bytes += r.second - r.first;
    return bytes;
}

static void edge_cases() {
    const size_t P = 4096;
    // A copied tensor exactly on pages.
    auto out = copied_only_pages({{0, 2 * P}}, {}, P, 1 << 20);
    CHECK(out.size() == 1 && out[0] == byte_range(0, 2 * P));
    // Partial pages at both ends stay.
    out = copied_only_pages({{100, 2 * P + 100}}, {}, P, 1 << 20);
    CHECK(out.size() == 1 && out[0] == byte_range(P, 2 * P));
    // A page an in-place tensor has one byte on stays.
    out = copied_only_pages({{0, 3 * P}}, {{P + 10, P + 11}}, P, 1 << 20);
    CHECK(out.size() == 2 && out[0] == byte_range(0, P) && out[1] == byte_range(2 * P, 3 * P));
    // Two copied tensors with padding between them share their boundary page.
    out = copied_only_pages({{0, P + 100}, {P + 128, 3 * P}}, {}, P, 1 << 20);
    CHECK(out.size() == 1 && out[0] == byte_range(0, 3 * P));
    // ... unless an in-place tensor lies in the gap.
    out = copied_only_pages({{0, P + 100}, {P + 600, 3 * P}}, {{P + 128, P + 600}}, P, 1 << 20);
    CHECK(out.size() == 2 && out[0] == byte_range(0, P) && out[1] == byte_range(2 * P, 3 * P));
    // A norm used in place between two copied tensors keeps the pages it touches.
    out = copied_only_pages({{0, 10000}, {14608, 30000}}, {{10016, 14600}}, P, 1 << 20);
    CHECK(out.size() == 2 && out[0] == byte_range(0, 2 * P) && out[1] == byte_range(4 * P, 7 * P));
    // A tensor both copied and used in place (a repacked tied output) keeps all its pages.
    out = copied_only_pages({{0, 1 << 20}}, {{0, 1 << 20}}, P, 1 << 20);
    CHECK(out.empty());
    // Nothing beyond the file's last whole page.
    out = copied_only_pages({{0, 10 * P}}, {}, P, 5 * P + 7);
    CHECK(out.size() == 1 && out[0] == byte_range(0, 5 * P));
    // A tensor smaller than a page gives nothing back.
    out = copied_only_pages({{10, 4000}}, {}, P, 1 << 20);
    CHECK(out.empty());
    // Empty and reversed ranges are no tensors.
    out = copied_only_pages({{P, P}, {3 * P, 2 * P}}, {}, P, 1 << 20);
    CHECK(out.empty());
    // 16 KiB pages, as some phones have.
    out = copied_only_pages({{100, 70000}}, {}, 16384, 1 << 20);
    CHECK(out.size() == 1 && out[0] == byte_range(16384, 65536));
    // No page size, no pages.
    CHECK(copied_only_pages({{0, 1 << 20}}, {}, 0, 1 << 20).empty());
}

// A table "name<TAB>type<TAB>bytes" in file order, laid out from data_start with 32-byte alignment,
// split by LoadSettings.TIED_OUTPUT_LAYERS into the copied and those used in place. Returns false
// when the file can't be read.
static bool table(const std::string &path, const size_t data_start, std::vector<byte_range> &copied,
                  std::vector<byte_range> &in_place, size_t &end) {
    static const std::regex layers(
            "blk\\.[0-9]+\\.(attn_q|attn_k|attn_v|attn_output|ffn_gate|ffn_up|ffn_down|inp_gate|proj)\\.weight");
    std::ifstream in(path);
    if (!in) return false;
    std::string line;
    size_t offset = 0;
    while (std::getline(in, line)) {
        if (line.empty() || line[0] == '#') continue;
        std::istringstream fields(line);
        std::string name, type;
        size_t bytes = 0;
        std::getline(fields, name, '\t');
        std::getline(fields, type, '\t');
        fields >> bytes;
        const size_t at = data_start + offset;
        (std::regex_search(name, layers) ? copied : in_place).emplace_back(at, at + bytes);
        offset = (offset + bytes + 31) / 32 * 32;
    }
    end = data_start + offset;
    return true;
}

// The model's table at both page sizes: the counts, and that every page given back lies inside the
// copied tensors and holds no byte of a tensor used in place.
static void model(const std::string &path, const size_t data_start, const size_t copied_count, const size_t ranges_4k,
                  const double low_mib, const double high_mib) {
    std::vector<byte_range> copied, in_place;
    size_t end = 0;
    if (!table(path, data_start, copied, in_place, end)) {
        fprintf(stderr, "FAIL: %s can't be read\n", path.c_str());
        ++g_failures;
        return;
    }
    CHECK(copied.size() == copied_count);
    for (const size_t page : {(size_t) 4096, (size_t) 16384}) {
        const auto pages = copied_only_pages(copied, in_place, page, end);
        bool aligned = true, inside = true, clear = true;
        for (const auto &p : pages) {
            aligned = aligned && p.first % page == 0 && p.second % page == 0 && p.first < p.second;
            for (const auto &k : in_place) clear = clear && (k.second <= p.first || k.first >= p.second);
            // Inside the copied tensors, padding between two of them included.
            for (size_t at = p.first; at < p.second; at += page) {
                bool covered = false;
                for (const auto &c : copied) covered = covered || (c.first <= at + page - 1 && at < c.second + 32);
                inside = inside && covered;
            }
        }
        CHECK(aligned);
        CHECK(inside);
        CHECK(clear);
        const double mib = total(pages) / 1048576.0;
        if (page == 4096) {
            CHECK(pages.size() == ranges_4k);
            CHECK(mib >= low_mib && mib <= high_mib);
        }
        printf("%s, %zu KiB pages: %zu copied tensors (%.2f MiB); %zu ranges, %.2f MiB given back\n",
               path.c_str(), page / 1024, copied.size(), total(copied) / 1048576.0, pages.size(), mib);
    }
}

int main(int argc, char **argv) {
    edge_cases();
    if (argc > 1) {
        const std::string dir = argv[1];
        // The data starts the two GGUF headers give, from the files on the phone.
        model(dir + "/gemma-3-1b-it-Q8_0.tensors.tsv", 6533120, 182, 78, 706.5, 707.03);
        model(dir + "/gemma-4-E2B_q4_0-it.tensors.tsv", 15815552, 275, 141, 998.5, 999.42);
    } else {
        printf("No tensor tables given: the edge cases only\n");
    }
    printf("%d checks, %d failed\n", g_checks, g_failures);
    return g_failures == 0 ? 0 : 1;
}
