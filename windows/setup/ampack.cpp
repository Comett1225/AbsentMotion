#include "pack.h"

#include <compressapi.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <filesystem>
#include <mutex>
#include <thread>
#include <vector>

namespace fs = std::filesystem;
using namespace am;

namespace {

struct Source {
    fs::path full;
    std::wstring rel;
    uint64_t size;
};

std::vector<uint8_t> ReadPart(const fs::path& p, uint64_t at, uint32_t n) {
    HANDLE h = CreateFileW(p.c_str(), GENERIC_READ, FILE_SHARE_READ, nullptr, OPEN_EXISTING, FILE_FLAG_SEQUENTIAL_SCAN, nullptr);
    if (h == INVALID_HANDLE_VALUE) FailLast(L"Could not open " + p.wstring());
    std::vector<uint8_t> b(n);
    try {
        if (n) ReadAt(h, at, b.data(), n);
    } catch (...) {
        CloseHandle(h);
        throw;
    }
    CloseHandle(h);
    return b;
}

class Out {
public:
    explicit Out(const fs::path& p) {
        h_ = CreateFileW(p.c_str(), GENERIC_WRITE, 0, nullptr, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
        if (h_ == INVALID_HANDLE_VALUE) FailLast(L"Could not write " + p.wstring());
    }
    ~Out() { CloseHandle(h_); }
    void Put(const void* p, size_t n, bool hashed = true) {
        const uint8_t* b = static_cast<const uint8_t*>(p);
        if (hashed) sha_.Add(b, n);
        while (n) {
            DWORD part = (DWORD)std::min<size_t>(n, 1u << 30), put = 0;
            if (!WriteFile(h_, b, part, &put, nullptr) || put != part) FailLast(L"Could not write the setup file");
            b += part;
            n -= part;
        }
    }
    void Finish(uint8_t out[32]) { sha_.Finish(out); }

private:
    HANDLE h_ = INVALID_HANDLE_VALUE;
    Sha256 sha_;
};

}

int wmain(int argc, wchar_t** argv) {
    if (argc < 5) {
        fwprintf(stderr, L"usage: ampack <app folder> <version> <setup stub> <output> [--xpress] [--payload-only]\n");
        return 2;
    }
    try {
        auto start = std::chrono::steady_clock::now();
        fs::path app = argv[1], stub = argv[3], output = argv[4];
        std::wstring version = argv[2];
        DWORD algorithm = COMPRESS_ALGORITHM_LZMS;
        bool payloadOnly = false;
        for (int i = 5; i < argc; ++i) {
            if (std::wstring(argv[i]) == L"--xpress") algorithm = COMPRESS_ALGORITHM_XPRESS_HUFF;
            if (std::wstring(argv[i]) == L"--payload-only") payloadOnly = true;
        }
        std::vector<Source> src;
        for (const auto& e : fs::recursive_directory_iterator(app)) {
            if (!e.is_regular_file()) continue;
            std::wstring rel = fs::relative(e.path(), app).wstring();
            std::replace(rel.begin(), rel.end(), L'/', L'\\');
            src.push_back({e.path(), rel, (uint64_t)e.file_size()});
        }
        std::sort(src.begin(), src.end(), [](const Source& a, const Source& b) { return _wcsicmp(a.rel.c_str(), b.rel.c_str()) < 0; });
        if (std::find_if(src.begin(), src.end(), [](const Source& s) { return _wcsicmp(s.rel.c_str(), L"AbsentMotion.exe") == 0; }) == src.end())
            Fail(L"the app folder has no AbsentMotion.exe");
        for (const Source& s : src)
            if (_wcsicmp(s.rel.c_str(), kSetupName) == 0) Fail(L"the app folder already has " + std::wstring(kSetupName));
        src.push_back({stub, kSetupName, (uint64_t)fs::file_size(stub)});

        std::vector<FileEntry> files;
        std::vector<ChunkEntry> chunks;
        uint64_t raw = 0;
        for (uint32_t i = 0; i < (uint32_t)src.size(); ++i) {
            FileEntry f{};
            f.size = src[i].size;
            f.firstChunk = (uint32_t)chunks.size();
            f.chunkCount = (uint32_t)((f.size + kChunk - 1) / kChunk);
            f.pathChars = (uint32_t)src[i].rel.size();
            for (uint32_t k = 0; k < f.chunkCount; ++k) {
                ChunkEntry c{};
                c.fileOffset = (uint64_t)k * kChunk;
                c.file = i;
                c.raw = (uint32_t)std::min<uint64_t>(kChunk, f.size - c.fileOffset);
                chunks.push_back(c);
            }
            files.push_back(f);
            raw += f.size;
        }

        std::vector<std::vector<uint8_t>> data(chunks.size());
        std::atomic<size_t> next{0};
        std::atomic<bool> failed{false};
        std::mutex errorLock;
        std::wstring error;
        auto work = [&] {
            COMPRESSOR_HANDLE c = nullptr;
            try {
                if (!CreateCompressor(algorithm, nullptr, &c)) FailLast(L"Could not start compressing");
                for (;;) {
                    size_t k = next++;
                    if (k >= chunks.size() || failed) break;
                    ChunkEntry& ch = chunks[k];
                    std::vector<uint8_t> in = ReadPart(src[ch.file].full, ch.fileOffset, ch.raw);
                    std::vector<uint8_t> out(ch.raw + ch.raw / 4 + 65536);
                    SIZE_T need = 0;
                    if (!Compress(c, in.data(), in.size(), out.data(), out.size(), &need)) {
                        if (GetLastError() != ERROR_INSUFFICIENT_BUFFER) FailLast(L"Could not compress");
                        out.resize(need);
                        if (!Compress(c, in.data(), in.size(), out.data(), out.size(), &need)) FailLast(L"Could not compress");
                    }
                    if (need >= ch.raw) {
                        ch.stored = 1;
                        ch.packed = ch.raw;
                        data[k] = std::move(in);
                    } else {
                        out.resize(need);
                        ch.packed = (uint32_t)need;
                        data[k] = std::move(out);
                    }
                }
            } catch (const Error& e) {
                std::lock_guard<std::mutex> l(errorLock);
                if (error.empty()) error = e.message;
                failed = true;
            }
            if (c) CloseCompressor(c);
        };
        unsigned count = std::max(2u, std::thread::hardware_concurrency());
        std::vector<std::thread> threads;
        for (unsigned i = 0; i < count; ++i) threads.emplace_back(work);
        for (std::thread& t : threads) t.join();
        if (failed) Fail(error);

        std::string v = Narrow(version);
        Header h{};
        memcpy(h.magic, kMagic, sizeof kMagic);
        h.algorithm = algorithm;
        h.files = (uint32_t)files.size();
        h.chunks = (uint32_t)chunks.size();
        h.versionBytes = (uint32_t)v.size();
        h.rawTotal = raw;
        uint64_t tables = sizeof(Header) + v.size() + chunks.size() * sizeof(ChunkEntry);
        for (const FileEntry& f : files) tables += sizeof(FileEntry) + (uint64_t)f.pathChars * sizeof(wchar_t);
        h.dataOffset = tables;
        uint64_t at = tables;
        for (ChunkEntry& c : chunks) {
            c.offset = at;
            at += c.packed;
        }

        std::vector<uint8_t> stubBytes = ReadPart(stub, 0, (uint32_t)fs::file_size(stub));
        Out out(output);
        if (!payloadOnly) out.Put(stubBytes.data(), stubBytes.size(), false);
        out.Put(&h, sizeof h);
        out.Put(v.data(), v.size());
        for (size_t i = 0; i < files.size(); ++i) {
            out.Put(&files[i], sizeof(FileEntry));
            out.Put(src[i].rel.data(), src[i].rel.size() * sizeof(wchar_t));
        }
        out.Put(chunks.data(), chunks.size() * sizeof(ChunkEntry));
        for (const auto& d : data) out.Put(d.data(), d.size());
        Trailer t{};
        t.offset = payloadOnly ? 0 : stubBytes.size();
        t.size = at;
        out.Finish(t.sha256);
        memcpy(t.magic, kMagic, sizeof kMagic);
        out.Put(&t, sizeof t, false);
        double seconds = std::chrono::duration<double>(std::chrono::steady_clock::now() - start).count();
        wprintf(L"%zu files, %llu bytes raw, %llu bytes packed (%.1f%%), %zu chunks, %s, %.1f s -> %s\n", files.size(), (unsigned long long)raw,
                (unsigned long long)(at - tables), raw ? 100.0 * (double)(at - tables) / (double)raw : 0.0, chunks.size(),
                algorithm == COMPRESS_ALGORITHM_LZMS ? L"LZMS" : L"XPRESS_HUFF", seconds, output.c_str());
        return 0;
    } catch (const Error& e) {
        fwprintf(stderr, L"ampack: %ls\n", e.message.c_str());
        return 1;
    } catch (const std::exception& e) {
        fwprintf(stderr, L"ampack: %hs\n", e.what());
        return 1;
    }
}
