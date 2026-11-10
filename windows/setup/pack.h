#pragma once

#include <windows.h>
#include <bcrypt.h>

#include <cstdint>
#include <cstring>
#include <stdexcept>
#include <string>

namespace am {

constexpr char kMagic[8] = {'A', 'M', 'P', 'A', 'C', 'K', '0', '1'};
constexpr uint32_t kChunk = 4u << 20;
constexpr const wchar_t* kSetupName = L"AbsentMotionSetup.exe";
constexpr const wchar_t* kAppName = L"AbsentMotion.exe";

#pragma pack(push, 1)
struct Trailer {
    uint64_t offset;
    uint64_t size;
    uint8_t sha256[32];
    char magic[8];
};

struct Header {
    char magic[8];
    uint32_t algorithm;
    uint32_t files;
    uint32_t chunks;
    uint32_t versionBytes;
    uint64_t rawTotal;
    uint64_t dataOffset;
};

struct FileEntry {
    uint64_t size;
    uint32_t firstChunk;
    uint32_t chunkCount;
    uint32_t pathChars;
};

struct ChunkEntry {
    uint64_t offset;
    uint64_t fileOffset;
    uint32_t file;
    uint32_t packed;
    uint32_t raw;
    uint32_t stored;
};
#pragma pack(pop)

inline std::string Narrow(const std::wstring& w) {
    if (w.empty()) return {};
    int n = WideCharToMultiByte(CP_UTF8, 0, w.data(), (int)w.size(), nullptr, 0, nullptr, nullptr);
    std::string s((size_t)n, '\0');
    WideCharToMultiByte(CP_UTF8, 0, w.data(), (int)w.size(), s.data(), n, nullptr, nullptr);
    return s;
}

inline std::wstring Widen(const std::string& s) {
    if (s.empty()) return {};
    int n = MultiByteToWideChar(CP_UTF8, 0, s.data(), (int)s.size(), nullptr, 0);
    std::wstring w((size_t)n, L'\0');
    MultiByteToWideChar(CP_UTF8, 0, s.data(), (int)s.size(), w.data(), n);
    return w;
}

inline std::wstring SystemMessage(DWORD code) {
    wchar_t* text = nullptr;
    DWORD n = FormatMessageW(FORMAT_MESSAGE_ALLOCATE_BUFFER | FORMAT_MESSAGE_FROM_SYSTEM | FORMAT_MESSAGE_IGNORE_INSERTS, nullptr, code, 0,
                             (LPWSTR)&text, 0, nullptr);
    std::wstring out = n ? std::wstring(text, n) : L"error " + std::to_wstring(code);
    if (text) LocalFree(text);
    while (!out.empty() && (out.back() == L'\n' || out.back() == L'\r' || out.back() == L' ' || out.back() == L'.')) out.pop_back();
    return out;
}

class Error : public std::runtime_error {
public:
    explicit Error(const std::wstring& m, DWORD c = 0) : std::runtime_error(Narrow(m)), message(m), code(c) {}
    std::wstring message;
    DWORD code;
};

[[noreturn]] inline void Fail(const std::wstring& what) { throw Error(what); }

[[noreturn]] inline void FailLast(const std::wstring& what) {
    DWORD code = GetLastError();
    throw Error(what + L": " + SystemMessage(code), code);
}

class Sha256 {
public:
    Sha256() {
        if (BCryptOpenAlgorithmProvider(&alg_, BCRYPT_SHA256_ALGORITHM, nullptr, 0) != 0 || BCryptCreateHash(alg_, &hash_, nullptr, 0, nullptr, 0, 0) != 0)
            Fail(L"SHA-256 is not available");
    }
    ~Sha256() {
        if (hash_) BCryptDestroyHash(hash_);
        if (alg_) BCryptCloseAlgorithmProvider(alg_, 0);
    }
    Sha256(const Sha256&) = delete;
    Sha256& operator=(const Sha256&) = delete;
    void Add(const void* p, size_t n) { BCryptHashData(hash_, (PUCHAR)p, (ULONG)n, 0); }
    void Finish(uint8_t out[32]) { BCryptFinishHash(hash_, out, 32, 0); }

private:
    BCRYPT_ALG_HANDLE alg_ = nullptr;
    BCRYPT_HASH_HANDLE hash_ = nullptr;
};

inline void ReadAt(HANDLE h, uint64_t at, void* p, uint32_t n) {
    OVERLAPPED o{};
    o.Offset = (DWORD)at;
    o.OffsetHigh = (DWORD)(at >> 32);
    DWORD got = 0;
    if (!ReadFile(h, p, n, &got, &o)) FailLast(L"Could not read the installer");
    if (got != n) Fail(L"The installer is incomplete. Download it again.");
}

inline void WriteAt(HANDLE h, uint64_t at, const void* p, uint32_t n) {
    OVERLAPPED o{};
    o.Offset = (DWORD)at;
    o.OffsetHigh = (DWORD)(at >> 32);
    DWORD put = 0;
    if (!WriteFile(h, p, n, &put, &o) || put != n) FailLast(L"Could not write a file");
}

inline uint64_t PayloadEnd(HANDLE h) {
    LARGE_INTEGER size{};
    GetFileSizeEx(h, &size);
    uint64_t end = (uint64_t)size.QuadPart;
    IMAGE_DOS_HEADER dos{};
    ReadAt(h, 0, &dos, sizeof dos);
    if (dos.e_magic != IMAGE_DOS_SIGNATURE) return end;
    IMAGE_NT_HEADERS64 nt{};
    ReadAt(h, (uint64_t)dos.e_lfanew, &nt, sizeof nt);
    if (nt.Signature != IMAGE_NT_SIGNATURE || nt.OptionalHeader.Magic != IMAGE_NT_OPTIONAL_HDR64_MAGIC) return end;
    const IMAGE_DATA_DIRECTORY& cert = nt.OptionalHeader.DataDirectory[IMAGE_DIRECTORY_ENTRY_SECURITY];
    if (cert.Size && (uint64_t)cert.VirtualAddress + cert.Size == end) end = cert.VirtualAddress;
    return end;
}

inline bool FindTrailer(HANDLE h, Trailer& t) {
    uint64_t end = PayloadEnd(h);
    for (uint64_t pad = 0; pad < 8 && end >= sizeof(Trailer) + pad; ++pad) {
        ReadAt(h, end - pad - sizeof(Trailer), &t, sizeof t);
        if (memcmp(t.magic, kMagic, 8) == 0) return true;
    }
    return false;
}

}
