#include "config.h"
#include "pack.h"
#include "ui.h"

#include <compressapi.h>
#include <msi.h>
#include <restartmanager.h>
#include <shellapi.h>
#include <shlobj.h>
#include <shobjidl.h>
#include <tlhelp32.h>

#include <algorithm>
#include <atomic>
#include <functional>
#include <memory>
#include <mutex>
#include <numeric>
#include <set>
#include <thread>
#include <vector>

namespace am {
namespace {

enum class Mode { Install, Update, Uninstall };

struct Options {
    Mode mode = Mode::Install;
    std::wstring dir;
    std::wstring name = AM_DEFAULT_NAME;
    std::wstring result;
    std::wstring log;
    std::wstring payload;
    DWORD waitPid = 0;
    bool launch = true;
    bool quiet = false;
    bool fromTemp = false;
    bool probe = false;
};

struct Cancelled {};

struct Stop {
    std::string reason;
    std::wstring detail;
};

constexpr const char* kNoClose = "the app did not close";
constexpr const char* kInUse = "files were in use";
constexpr const char* kSpace = "not enough disk space";
constexpr const char* kPayload = "the update file was missing or damaged";
constexpr const char* kOld = "the old version could not be removed";
constexpr const char* kPlace = "the new version could not be put in place";

[[noreturn]] void StopWith(const char* reason, const std::wstring& detail) { throw Stop{reason, detail}; }

class Handle {
public:
    explicit Handle(HANDLE h = INVALID_HANDLE_VALUE) : h_(h) {}
    ~Handle() {
        if (Valid()) CloseHandle(h_);
    }
    Handle(const Handle&) = delete;
    Handle& operator=(const Handle&) = delete;
    HANDLE Get() const { return h_; }
    bool Valid() const { return h_ && h_ != INVALID_HANDLE_VALUE; }

private:
    HANDLE h_;
};

struct Payload {
    std::wstring path;
    uint64_t base = 0;
    uint64_t size = 0;
    uint8_t sha[32]{};
    Header header{};
    std::wstring version;
    std::vector<FileEntry> files;
    std::vector<std::wstring> paths;
    std::vector<ChunkEntry> chunks;
};

std::wstring g_log;
std::mutex g_logLock;

void Log(const std::wstring& line) {
    if (g_log.empty()) return;
    std::lock_guard<std::mutex> l(g_logLock);
    SYSTEMTIME t;
    GetLocalTime(&t);
    wchar_t stamp[32];
    swprintf_s(stamp, L"%02u:%02u:%02u.%03u ", t.wHour, t.wMinute, t.wSecond, t.wMilliseconds);
    std::string text = Narrow(stamp + line) + "\r\n";
    HANDLE h = CreateFileW(g_log.c_str(), FILE_APPEND_DATA, FILE_SHARE_READ | FILE_SHARE_WRITE, nullptr, OPEN_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (h == INVALID_HANDLE_VALUE) return;
    DWORD n = 0;
    WriteFile(h, text.data(), (DWORD)text.size(), &n, nullptr);
    CloseHandle(h);
}

std::wstring Known(REFKNOWNFOLDERID id) {
    PWSTR p = nullptr;
    std::wstring s;
    if (SUCCEEDED(SHGetKnownFolderPath(id, KF_FLAG_CREATE, nullptr, &p))) s = p;
    CoTaskMemFree(p);
    return s;
}

std::wstring Join(const std::wstring& a, const std::wstring& b) { return a.empty() || a.back() == L'\\' ? a + b : a + L"\\" + b; }

std::wstring Full(const std::wstring& p) {
    std::wstring buf(32768, L'\0');
    DWORD n = GetFullPathNameW(p.c_str(), (DWORD)buf.size(), buf.data(), nullptr);
    std::wstring s = n && n < buf.size() ? buf.substr(0, n) : p;
    while (s.size() > 3 && (s.back() == L'\\' || s.back() == L'/')) s.pop_back();
    return s;
}

std::wstring Long(const std::wstring& p) { return p.rfind(L"\\\\?\\", 0) == 0 ? p : L"\\\\?\\" + p; }

bool Exists(const std::wstring& p) { return GetFileAttributesW(Long(p).c_str()) != INVALID_FILE_ATTRIBUTES; }

std::wstring Quote(const std::wstring& s) { return L"\"" + s + L"\""; }

std::wstring SelfPath() {
    std::wstring s(32768, L'\0');
    DWORD n = GetModuleFileNameW(nullptr, s.data(), (DWORD)s.size());
    s.resize(n);
    return s;
}

std::wstring Parent(const std::wstring& p) {
    size_t k = p.find_last_of(L'\\');
    return k == std::wstring::npos ? p : p.substr(0, k);
}

std::wstring KeyName(const std::wstring& name) {
    std::wstring k;
    for (wchar_t c : name)
        if (iswalnum(c)) k += c;
    return k.empty() ? L"AbsentMotion" : k;
}

std::wstring UninstallKey(const Options& o) { return L"Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\" + KeyName(o.name); }

bool StartsWithFolder(const std::wstring& path, const std::wstring& folder) {
    std::wstring prefix = folder + L"\\";
    return path.size() > prefix.size() && CompareStringOrdinal(path.c_str(), (int)prefix.size(), prefix.c_str(), (int)prefix.size(), TRUE) == CSTR_EQUAL;
}

std::wstring ReadString(HKEY base, const wchar_t* sub, const wchar_t* value) {
    std::wstring buf(2048, L'\0');
    DWORD size = (DWORD)(buf.size() * sizeof(wchar_t));
    if (RegGetValueW(base, sub, value, RRF_RT_REG_SZ, nullptr, buf.data(), &size) != ERROR_SUCCESS) return {};
    buf.resize(wcslen(buf.c_str()));
    return buf;
}

std::wstring NameFor(const std::wstring& root) {
    HKEY base = nullptr;
    if (RegOpenKeyExW(HKEY_CURRENT_USER, L"Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall", 0, KEY_READ, &base) != ERROR_SUCCESS) return {};
    std::wstring found;
    wchar_t sub[256];
    for (DWORD i = 0;; ++i) {
        DWORD n = 256;
        if (RegEnumKeyExW(base, i, sub, &n, nullptr, nullptr, nullptr, nullptr) != ERROR_SUCCESS) break;
        std::wstring where = ReadString(base, sub, L"InstallLocation"), name = ReadString(base, sub, L"DisplayName");
        while (!where.empty() && where.back() == L'\\') where.pop_back();
        if (name.empty() || ReadString(base, sub, L"UninstallString").find(kSetupName) == std::wstring::npos) continue;
        if (CompareStringOrdinal(where.c_str(), -1, root.c_str(), -1, TRUE) == CSTR_EQUAL) {
            found = name;
            break;
        }
    }
    RegCloseKey(base);
    return found;
}

Options Parse() {
    Options o;
    bool named = false;
    int argc = 0;
    LPWSTR* argv = CommandLineToArgvW(GetCommandLineW(), &argc);
    for (int i = 1; i < argc; ++i) {
        std::wstring a = argv[i];
        auto next = [&]() { return i + 1 < argc ? std::wstring(argv[++i]) : std::wstring(); };
        if (a == L"--update") o.mode = Mode::Update;
        else if (a == L"--uninstall") o.mode = Mode::Uninstall;
        else if (a == L"--probe") o.probe = true;
        else if (a == L"--quiet") o.quiet = true;
        else if (a == L"--no-launch") o.launch = false;
        else if (a == L"--from-temp") o.fromTemp = true;
        else if (a == L"--dir") o.dir = Full(next());
        else if (a == L"--name") {
            o.name = next();
            named = true;
        }
        else if (a == L"--wait-pid") o.waitPid = (DWORD)_wtoi64(next().c_str());
        else if (a == L"--result") o.result = next();
        else if (a == L"--log") o.log = next();
        else if (a == L"--payload") o.payload = Full(next());
    }
    LocalFree(argv);
    if (o.dir.empty()) o.dir = o.mode == Mode::Uninstall ? Parent(SelfPath()) : Join(Known(FOLDERID_UserProgramFiles), o.name);
    if (!named && o.mode != Mode::Install) {
        std::wstring found = NameFor(o.dir);
        if (!found.empty()) o.name = found;
    }
    return o;
}

HANDLE OpenRead(const std::wstring& p) {
    return CreateFileW(p.c_str(), GENERIC_READ, FILE_SHARE_READ, nullptr, OPEN_EXISTING, FILE_FLAG_RANDOM_ACCESS, nullptr);
}

Payload ReadPayload(const std::wstring& self) {
    Payload p;
    p.path = self;
    Handle h(OpenRead(self));
    if (!h.Valid()) FailLast(L"Could not open the installer");
    Trailer t{};
    if (!FindTrailer(h.Get(), t)) Fail(L"This installer has no AbsentMotion inside. Download it again.");
    p.base = t.offset;
    p.size = t.size;
    memcpy(p.sha, t.sha256, sizeof p.sha);
    ReadAt(h.Get(), p.base, &p.header, sizeof p.header);
    if (memcmp(p.header.magic, kMagic, sizeof kMagic) != 0 || p.header.dataOffset > p.size) Fail(L"The installer is damaged. Download it again.");
    std::vector<uint8_t> table((size_t)(p.header.dataOffset - sizeof(Header)));
    if (!table.empty()) ReadAt(h.Get(), p.base + sizeof(Header), table.data(), (uint32_t)table.size());
    size_t at = 0;
    auto take = [&](void* out, size_t n) {
        if (at + n > table.size()) Fail(L"The installer is damaged. Download it again.");
        memcpy(out, table.data() + at, n);
        at += n;
    };
    std::string v(p.header.versionBytes, '\0');
    if (!v.empty()) take(v.data(), v.size());
    p.version = Widen(v);
    p.files.resize(p.header.files);
    p.paths.resize(p.header.files);
    for (uint32_t i = 0; i < p.header.files; ++i) {
        take(&p.files[i], sizeof(FileEntry));
        std::wstring s(p.files[i].pathChars, L'\0');
        take(s.data(), s.size() * sizeof(wchar_t));
        if (s.empty() || s.find(L"..") != std::wstring::npos || s.find(L':') != std::wstring::npos || s[0] == L'\\' || s.find(L'/') != std::wstring::npos)
            Fail(L"The installer is damaged. Download it again.");
        p.paths[i] = s;
    }
    p.chunks.resize(p.header.chunks);
    if (!p.chunks.empty()) take(p.chunks.data(), p.chunks.size() * sizeof(ChunkEntry));
    for (const ChunkEntry& c : p.chunks)
        if (c.file >= p.files.size() || c.raw > kChunk || c.packed > kChunk + kChunk / 2 || c.offset + c.packed > p.size) Fail(L"The installer is damaged. Download it again.");
    return p;
}

void Verify(const Payload& p) {
    Handle h(CreateFileW(p.path.c_str(), GENERIC_READ, FILE_SHARE_READ, nullptr, OPEN_EXISTING, FILE_FLAG_SEQUENTIAL_SCAN, nullptr));
    if (!h.Valid()) FailLast(L"Could not open the installer");
    Sha256 sha;
    std::vector<uint8_t> buf(8u << 20);
    uint64_t left = p.size, at = p.base;
    while (left) {
        uint32_t n = (uint32_t)std::min<uint64_t>(left, buf.size());
        ReadAt(h.Get(), at, buf.data(), n);
        sha.Add(buf.data(), n);
        at += n;
        left -= n;
    }
    uint8_t got[32];
    sha.Finish(got);
    if (memcmp(got, p.sha, sizeof got) != 0) Fail(L"The installer is damaged. Download it again.");
}

void MakeDirs(const Payload& p, const std::wstring& dest) {
    std::set<std::wstring> dirs;
    for (const std::wstring& rel : p.paths)
        for (size_t k = rel.find(L'\\'); k != std::wstring::npos; k = rel.find(L'\\', k + 1)) dirs.insert(rel.substr(0, k));
    if (!CreateDirectoryW(Long(dest).c_str(), nullptr) && GetLastError() != ERROR_ALREADY_EXISTS) FailLast(L"Could not make the app folder");
    for (const std::wstring& d : dirs)
        if (!CreateDirectoryW(Long(Join(dest, d)).c_str(), nullptr) && GetLastError() != ERROR_ALREADY_EXISTS) FailLast(L"Could not make a folder");
}

HANDLE CreateOut(const std::wstring& path) {
    return CreateFileW(Long(path).c_str(), GENERIC_WRITE, 0, nullptr, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
}

void Extract(const Payload& p, const std::wstring& dest, Shared& s) {
    MakeDirs(p, dest);
    size_t n = p.files.size();
    std::vector<HANDLE> open(n, INVALID_HANDLE_VALUE);
    std::unique_ptr<std::atomic<uint32_t>[]> left(new std::atomic<uint32_t>[n]);
    for (size_t i = 0; i < n; ++i) {
        left[i] = p.files[i].chunkCount;
        if (p.files[i].chunkCount == 1) continue;
        HANDLE h = CreateOut(Join(dest, p.paths[i]));
        if (h == INVALID_HANDLE_VALUE) {
            for (HANDLE o : open)
                if (o != INVALID_HANDLE_VALUE) CloseHandle(o);
            FailLast(L"Could not write " + p.paths[i]);
        }
        if (p.files[i].chunkCount == 0) CloseHandle(h);
        else open[i] = h;
    }
    std::vector<uint32_t> order(p.chunks.size());
    std::iota(order.begin(), order.end(), 0u);
    std::stable_sort(order.begin(), order.end(), [&](uint32_t a, uint32_t b) { return p.chunks[a].raw > p.chunks[b].raw; });
    uint32_t biggest = 0;
    for (const ChunkEntry& c : p.chunks) biggest = std::max(biggest, c.packed);
    std::atomic<size_t> next{0};
    std::atomic<uint64_t> done{0};
    std::atomic<bool> stop{false};
    std::mutex errorLock;
    std::wstring error;
    s.Progress(0, p.header.rawTotal);
    auto work = [&] {
        DECOMPRESSOR_HANDLE d = nullptr;
        try {
            Handle in(OpenRead(p.path));
            if (!in.Valid()) FailLast(L"Could not open the installer");
            if (!CreateDecompressor(p.header.algorithm, nullptr, &d)) FailLast(L"Could not start unpacking");
            std::vector<uint8_t> packed(biggest ? biggest : 1), raw(kChunk);
            for (;;) {
                if (stop || s.CloseRequested()) break;
                size_t k = next++;
                if (k >= order.size()) break;
                const ChunkEntry& c = p.chunks[order[k]];
                ReadAt(in.Get(), p.base + c.offset, packed.data(), c.packed);
                const uint8_t* data = packed.data();
                if (!c.stored) {
                    SIZE_T got = 0;
                    if (!Decompress(d, packed.data(), c.packed, raw.data(), c.raw, &got) || got != c.raw) Fail(L"The installer is damaged. Download it again.");
                    data = raw.data();
                }
                const FileEntry& f = p.files[c.file];
                if (f.chunkCount == 1) {
                    Handle out(CreateOut(Join(dest, p.paths[c.file])));
                    if (!out.Valid()) FailLast(L"Could not write " + p.paths[c.file]);
                    WriteAt(out.Get(), 0, data, c.raw);
                } else {
                    WriteAt(open[c.file], c.fileOffset, data, c.raw);
                    if (--left[c.file] == 0) {
                        CloseHandle(open[c.file]);
                        open[c.file] = INVALID_HANDLE_VALUE;
                    }
                }
                s.Progress(done += c.raw, p.header.rawTotal);
            }
        } catch (const Error& e) {
            std::lock_guard<std::mutex> l(errorLock);
            if (error.empty()) error = e.message;
            stop = true;
        }
        if (d) CloseDecompressor(d);
    };
    unsigned count = std::clamp(std::thread::hardware_concurrency(), 2u, 12u);
    std::vector<std::thread> threads;
    for (unsigned i = 0; i < count; ++i) threads.emplace_back(work);
    for (std::thread& t : threads) t.join();
    for (HANDLE& h : open)
        if (h != INVALID_HANDLE_VALUE) {
            CloseHandle(h);
            h = INVALID_HANDLE_VALUE;
        }
    if (!error.empty()) Fail(error);
    if (s.CloseRequested()) throw Cancelled{};
}

uint64_t CountFiles(const std::wstring& dir) {
    uint64_t n = 0;
    WIN32_FIND_DATAW fd;
    HANDLE f = FindFirstFileExW(Long(Join(dir, L"*")).c_str(), FindExInfoBasic, &fd, FindExSearchNameMatch, nullptr, FIND_FIRST_EX_LARGE_FETCH);
    if (f == INVALID_HANDLE_VALUE) return 0;
    do {
        std::wstring name = fd.cFileName;
        if (name == L"." || name == L"..") continue;
        if ((fd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) && !(fd.dwFileAttributes & FILE_ATTRIBUTE_REPARSE_POINT)) n += CountFiles(Join(dir, name));
        else ++n;
    } while (FindNextFileW(f, &fd));
    FindClose(f);
    return n;
}

void DeleteTree(const std::wstring& dir, const std::function<void()>& tick = {}) {
    WIN32_FIND_DATAW fd;
    HANDLE f = FindFirstFileExW(Long(Join(dir, L"*")).c_str(), FindExInfoBasic, &fd, FindExSearchNameMatch, nullptr, FIND_FIRST_EX_LARGE_FETCH);
    if (f != INVALID_HANDLE_VALUE) {
        do {
            std::wstring name = fd.cFileName;
            if (name == L"." || name == L"..") continue;
            std::wstring p = Join(dir, name);
            if ((fd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) && !(fd.dwFileAttributes & FILE_ATTRIBUTE_REPARSE_POINT)) {
                DeleteTree(p, tick);
                continue;
            }
            if (fd.dwFileAttributes & FILE_ATTRIBUTE_READONLY) SetFileAttributesW(Long(p).c_str(), FILE_ATTRIBUTE_NORMAL);
            if (fd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) RemoveDirectoryW(Long(p).c_str());
            else DeleteFileW(Long(p).c_str());
            if (tick) tick();
        } while (FindNextFileW(f, &fd));
        FindClose(f);
    }
    RemoveDirectoryW(Long(dir).c_str());
}

bool MoveRetry(const std::wstring& from, const std::wstring& to, DWORD ms) {
    ULONGLONG end = GetTickCount64() + ms;
    for (;;) {
        if (MoveFileExW(Long(from).c_str(), Long(to).c_str(), 0)) return true;
        DWORD e = GetLastError();
        if (GetTickCount64() >= end || (e != ERROR_ACCESS_DENIED && e != ERROR_SHARING_VIOLATION && e != ERROR_LOCK_VIOLATION)) {
            SetLastError(e);
            return false;
        }
        Sleep(150);
    }
}

constexpr const wchar_t* kListName = L"AbsentMotionSetup.files";

std::wstring Lower(std::wstring s) {
    if (!s.empty()) CharLowerBuffW(s.data(), (DWORD)s.size());
    return s;
}

void FilesUnder(const std::wstring& dir, const std::wstring& rel, std::vector<std::wstring>& out) {
    WIN32_FIND_DATAW fd;
    HANDLE f = FindFirstFileExW(Long(Join(dir, L"*")).c_str(), FindExInfoBasic, &fd, FindExSearchNameMatch, nullptr, FIND_FIRST_EX_LARGE_FETCH);
    if (f == INVALID_HANDLE_VALUE) return;
    do {
        std::wstring name = fd.cFileName;
        if (name == L"." || name == L"..") continue;
        std::wstring sub = rel.empty() ? name : rel + L"\\" + name;
        if ((fd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) && !(fd.dwFileAttributes & FILE_ATTRIBUTE_REPARSE_POINT)) FilesUnder(Join(dir, name), sub, out);
        else out.push_back(sub);
    } while (FindNextFileW(f, &fd));
    FindClose(f);
}

void WriteList(const std::wstring& root, const Payload& p) {
    std::string text;
    for (const std::wstring& rel : p.paths) text += Narrow(rel) + "\n";
    Handle h(CreateFileW(Long(Join(root, kListName)).c_str(), GENERIC_WRITE, 0, nullptr, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr));
    DWORD n = 0;
    if (!h.Valid() || !WriteFile(h.Get(), text.data(), (DWORD)text.size(), &n, nullptr)) Log(L"could not write the file list: " + SystemMessage(GetLastError()));
}

bool ReadList(const std::wstring& root, std::set<std::wstring>& out) {
    Handle h(CreateFileW(Long(Join(root, kListName)).c_str(), GENERIC_READ, FILE_SHARE_READ, nullptr, OPEN_EXISTING, 0, nullptr));
    if (!h.Valid()) return false;
    LARGE_INTEGER size{};
    if (!GetFileSizeEx(h.Get(), &size) || size.QuadPart > (64ll << 20)) return false;
    std::string text((size_t)size.QuadPart, '\0');
    DWORD n = 0;
    if (!ReadFile(h.Get(), text.data(), (DWORD)text.size(), &n, nullptr)) return false;
    text.resize(n);
    size_t at = 0;
    while (at < text.size()) {
        size_t end = text.find('\n', at);
        if (end == std::string::npos) end = text.size();
        std::string line = text.substr(at, end - at);
        if (!line.empty() && line.back() == '\r') line.pop_back();
        if (!line.empty()) out.insert(Lower(Widen(line)));
        at = end + 1;
    }
    return true;
}

bool OwnedByApp(const std::wstring& rel, const std::set<std::wstring>& known) {
    std::wstring l = Lower(rel);
    return known.count(l) || l == Lower(kListName) || l == Lower(kSetupName) || l == Lower(kAppName) || l.rfind(L"app\\", 0) == 0 || l.rfind(L"runtime\\", 0) == 0;
}

std::vector<std::wstring> AddedFiles(const std::wstring& root, const std::set<std::wstring>& known) {
    std::vector<std::wstring> all, added;
    FilesUnder(root, L"", all);
    for (const std::wstring& rel : all)
        if (!OwnedByApp(rel, known)) added.push_back(rel);
    return added;
}

int MoveFiles(const std::wstring& from, const std::wstring& to, const std::vector<std::wstring>& files) {
    int moved = 0;
    for (const std::wstring& rel : files) {
        std::wstring target = Join(to, rel);
        if (Exists(target)) continue;
        for (size_t k = rel.find(L'\\'); k != std::wstring::npos; k = rel.find(L'\\', k + 1)) CreateDirectoryW(Long(Join(to, rel.substr(0, k))).c_str(), nullptr);
        if (MoveFileExW(Long(Join(from, rel)).c_str(), Long(target).c_str(), 0)) ++moved;
        else Log(L"could not keep " + rel + L": " + SystemMessage(GetLastError()));
    }
    return moved;
}

std::wstring Lockers(const std::wstring& folder) {
    std::vector<std::wstring> rel;
    FilesUnder(folder, L"", rel);
    std::vector<std::wstring> paths;
    std::vector<LPCWSTR> ptrs;
    paths.reserve(rel.size());
    for (const std::wstring& r : rel) paths.push_back(Join(folder, r));
    for (const std::wstring& p : paths) ptrs.push_back(p.c_str());
    DWORD session = 0;
    WCHAR key[CCH_RM_SESSION_KEY + 1] = {};
    if (ptrs.empty() || RmStartSession(&session, 0, key) != ERROR_SUCCESS) return {};
    std::set<std::wstring> names;
    if (RmRegisterResources(session, (UINT)ptrs.size(), ptrs.data(), 0, nullptr, 0, nullptr) == ERROR_SUCCESS) {
        UINT needed = 0, count = 0;
        DWORD reasons = 0;
        std::vector<RM_PROCESS_INFO> info;
        DWORD st = RmGetList(session, &needed, &count, nullptr, &reasons);
        if (st == ERROR_MORE_DATA && needed) {
            info.resize(needed);
            count = needed;
            st = RmGetList(session, &needed, &count, info.data(), &reasons);
        }
        if (st == ERROR_SUCCESS)
            for (UINT i = 0; i < count; ++i)
                if (info[i].Process.dwProcessId != GetCurrentProcessId()) names.insert(info[i].strAppName[0] ? info[i].strAppName : L"a program");
    }
    RmEndSession(session);
    std::wstring out;
    for (const std::wstring& n : names) out += (out.empty() ? L"" : L", ") + n;
    if (!out.empty()) Log(L"files are open in: " + out);
    return out;
}

std::wstring InUse(const std::wstring& folder) {
    std::wstring who = Lockers(folder);
    return who.empty() ? L"" : L"\nOpen in: " + who;
}

std::vector<int> VersionParts(const std::wstring& v) {
    std::vector<int> parts;
    int cur = -1;
    for (wchar_t c : v) {
        if (iswdigit(c)) cur = (cur < 0 ? 0 : cur) * 10 + (c - L'0');
        else if (cur >= 0) {
            parts.push_back(cur);
            cur = -1;
        }
    }
    if (cur >= 0) parts.push_back(cur);
    return parts;
}

bool Older(const std::wstring& a, const std::wstring& b) {
    std::vector<int> x = VersionParts(a), y = VersionParts(b);
    if (x.empty() || y.empty()) return false;
    x.resize(std::max(x.size(), y.size()), 0);
    y.resize(x.size(), 0);
    return x < y;
}

bool FromFolder(DWORD pid, const std::wstring& folder) {
    Handle h(OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, pid));
    if (!h.Valid()) return false;
    std::wstring buf(32768, L'\0');
    DWORD n = (DWORD)buf.size();
    if (!QueryFullProcessImageNameW(h.Get(), 0, buf.data(), &n)) return false;
    return StartsWithFolder(buf.substr(0, n), folder);
}

std::vector<DWORD> Running(const std::wstring& folder) {
    std::vector<DWORD> out;
    Handle snap(CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0));
    if (!snap.Valid()) return out;
    PROCESSENTRY32W e{sizeof e};
    DWORD self = GetCurrentProcessId();
    for (BOOL ok = Process32FirstW(snap.Get(), &e); ok; ok = Process32NextW(snap.Get(), &e))
        if (e.th32ProcessID != self && FromFolder(e.th32ProcessID, folder)) out.push_back(e.th32ProcessID);
    return out;
}

void WaitClosed(DWORD pid, const std::wstring& folder, DWORD ms) {
    ULONGLONG end = GetTickCount64() + ms;
    if (pid) {
        Handle h(OpenProcess(SYNCHRONIZE, FALSE, pid));
        if (h.Valid()) WaitForSingleObject(h.Get(), ms);
    }
    while (!Running(folder).empty() && GetTickCount64() < end) Sleep(100);
}

bool AppWindowVisible(const std::wstring& folder) {
    struct Ctx {
        const std::wstring* folder;
        bool found;
    } ctx{&folder, false};
    EnumWindows(
        [](HWND h, LPARAM l) -> BOOL {
            Ctx* c = reinterpret_cast<Ctx*>(l);
            if (!IsWindowVisible(h)) return TRUE;
            RECT r;
            GetWindowRect(h, &r);
            if (r.right - r.left < 320 || r.bottom - r.top < 200) return TRUE;
            DWORD pid = 0;
            GetWindowThreadProcessId(h, &pid);
            if (!FromFolder(pid, *c->folder)) return TRUE;
            c->found = true;
            return FALSE;
        },
        reinterpret_cast<LPARAM>(&ctx));
    return ctx.found;
}

void Launch(const std::wstring& folder) {
    std::wstring exe = Join(folder, kAppName), cmd = Quote(exe);
    STARTUPINFOW si{sizeof si};
    PROCESS_INFORMATION pi{};
    if (!CreateProcessW(exe.c_str(), cmd.data(), nullptr, nullptr, FALSE, 0, nullptr, folder.c_str(), &si, &pi)) FailLast(L"Could not open AbsentMotion");
    CloseHandle(pi.hThread);
    CloseHandle(pi.hProcess);
}

void ResultLine(const std::wstring& path, const std::string& line, bool fresh = false) {
    if (path.empty()) return;
    std::string text = line + "\r\n";
    for (int attempt = 0; attempt < 20; ++attempt) {
        Handle h(CreateFileW(path.c_str(), fresh ? GENERIC_WRITE : FILE_APPEND_DATA, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, nullptr,
                             fresh ? CREATE_ALWAYS : OPEN_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr));
        if (h.Valid()) {
            DWORD n = 0;
            WriteFile(h.Get(), text.data(), (DWORD)text.size(), &n, nullptr);
            FlushFileBuffers(h.Get());
            Log(L"result: " + Widen(line));
            return;
        }
        Sleep(50);
    }
}

void WriteResult(const std::wstring& path, const std::string& text) { ResultLine(path, text, true); }

void Stage(const Options& o, const char* name) { ResultLine(o.result, std::string("stage ") + name); }

void PostClose(const std::vector<DWORD>& pids) {
    EnumWindows(
        [](HWND h, LPARAM l) -> BOOL {
            const auto* list = reinterpret_cast<const std::vector<DWORD>*>(l);
            DWORD pid = 0;
            GetWindowThreadProcessId(h, &pid);
            if (IsWindowVisible(h) && std::find(list->begin(), list->end(), pid) != list->end()) PostMessageW(h, WM_CLOSE, 0, 0);
            return TRUE;
        },
        reinterpret_cast<LPARAM>(&pids));
}

bool CloseAll(const std::wstring& folder) {
    std::vector<DWORD> left = Running(folder);
    if (left.empty()) return true;
    Log(L"asking " + std::to_wstring(left.size()) + L" programs from the app folder to close");
    PostClose(left);
    ULONGLONG end = GetTickCount64() + 5000;
    while (!(left = Running(folder)).empty() && GetTickCount64() < end) Sleep(100);
    for (DWORD pid : left) {
        Handle h(OpenProcess(PROCESS_TERMINATE | SYNCHRONIZE, FALSE, pid));
        bool ended = h.Valid() && TerminateProcess(h.Get(), 1);
        if (ended) WaitForSingleObject(h.Get(), 5000);
        Log(L"ended " + std::to_wstring(pid) + (ended ? L"" : L" failed: " + SystemMessage(GetLastError())));
    }
    end = GetTickCount64() + 5000;
    while (!(left = Running(folder)).empty() && GetTickCount64() < end) Sleep(100);
    return left.empty();
}

bool Writable(const std::wstring& folder) {
    std::wstring probe = Join(folder, L".absentmotion-" + std::to_wstring(GetCurrentProcessId()) + L".tmp");
    Handle h(CreateFileW(Long(probe).c_str(), GENERIC_WRITE, 0, nullptr, CREATE_NEW, FILE_ATTRIBUTE_TEMPORARY | FILE_FLAG_DELETE_ON_CLOSE, nullptr));
    return h.Valid();
}

std::wstring SystemFile(const wchar_t* name) {
    std::wstring sys(MAX_PATH, L'\0');
    sys.resize(GetSystemDirectoryW(sys.data(), (UINT)sys.size()));
    return Join(sys, name);
}

DWORD RunAndWait(const std::wstring& exe, std::wstring cmd, DWORD ms) {
    STARTUPINFOW si{};
    si.cb = sizeof si;
    PROCESS_INFORMATION pi{};
    if (!CreateProcessW(exe.c_str(), cmd.data(), nullptr, nullptr, FALSE, CREATE_NO_WINDOW, nullptr, nullptr, &si, &pi)) return GetLastError() | 0x40000000;
    CloseHandle(pi.hThread);
    DWORD code = WAIT_TIMEOUT;
    if (WaitForSingleObject(pi.hProcess, ms) == WAIT_OBJECT_0) GetExitCodeProcess(pi.hProcess, &code);
    CloseHandle(pi.hProcess);
    return code;
}

std::wstring MsiProperty(const wchar_t* code, const wchar_t* name) {
    std::wstring buf(2048, L'\0');
    DWORD n = (DWORD)buf.size();
    if (MsiGetProductInfoExW(code, nullptr, MSIINSTALLCONTEXT_USERUNMANAGED, name, buf.data(), &n) != ERROR_SUCCESS) return L"?";
    buf.resize(n);
    return buf;
}

std::wstring FindMsi(const std::wstring& root) {
    std::vector<std::pair<std::wstring, std::wstring>> found;
    wchar_t code[39];
    for (DWORD i = 0; MsiEnumRelatedProductsW(AM_UPGRADE_CODE, 0, i, code) == ERROR_SUCCESS; ++i) {
        std::wstring where = MsiProperty(code, INSTALLPROPERTY_INSTALLLOCATION);
        Log(L"MSI product " + std::wstring(code) + L" at " + where);
        if (where == L"?") continue;
        found.emplace_back(code, where.empty() ? where : Full(where));
    }
    for (const auto& f : found)
        if (!f.second.empty() && CompareStringOrdinal(f.second.c_str(), -1, root.c_str(), -1, TRUE) == CSTR_EQUAL) return f.first;
    if (found.size() == 1 && found[0].second.empty()) return found[0].first;
    return {};
}

bool RemoveMsi(const std::wstring& code, const std::wstring& root, const std::wstring& logDir) {
    std::wstring msiexec = SystemFile(L"msiexec.exe"), log = Join(logDir, L"update-msi-remove.log");
    for (int attempt = 0;; ++attempt) {
        std::wstring cmd = Quote(msiexec) + L" /x " + code + L" INSTALLDIR=" + Quote(root + L"\\") + L" MSIRESTARTMANAGERCONTROL=Disable /qn /norestart /L*v " + Quote(log);
        DWORD exit = RunAndWait(msiexec, cmd, 900000);
        Log(L"msiexec /x " + code + L" exit " + std::to_wstring(exit));
        if (exit == ERROR_SUCCESS || exit == ERROR_SUCCESS_REBOOT_REQUIRED || exit == ERROR_SUCCESS_REBOOT_INITIATED || exit == ERROR_UNKNOWN_PRODUCT) return true;
        if (exit == ERROR_INSTALL_ALREADY_RUNNING && attempt < 40) {
            Sleep(3000);
            continue;
        }
        return false;
    }
}

std::wstring InstalledVersion(const std::wstring& root) {
    Handle h(CreateFileW(Long(Join(root, L"app\\AbsentMotion.cfg")).c_str(), GENERIC_READ, FILE_SHARE_READ, nullptr, OPEN_EXISTING, 0, nullptr));
    if (!h.Valid()) return L"";
    std::string text(64 * 1024, '\0');
    DWORD n = 0;
    ReadFile(h.Get(), text.data(), (DWORD)text.size(), &n, nullptr);
    text.resize(n);
    const std::string key = "-Djpackage.app-version=";
    size_t at = text.find(key);
    if (at == std::string::npos) return L"";
    size_t end = text.find_first_of("\r\n", at);
    return Widen(text.substr(at + key.size(), end == std::string::npos ? std::string::npos : end - at - key.size()));
}

void SetString(HKEY k, const wchar_t* name, const std::wstring& v) {
    RegSetValueExW(k, name, 0, REG_SZ, reinterpret_cast<const BYTE*>(v.c_str()), (DWORD)((v.size() + 1) * sizeof(wchar_t)));
}

void SetDword(HKEY k, const wchar_t* name, DWORD v) { RegSetValueExW(k, name, 0, REG_DWORD, reinterpret_cast<const BYTE*>(&v), sizeof v); }

void Register(const Options& o, const std::wstring& version, uint64_t bytes) {
    HKEY k = nullptr;
    LSTATUS st = RegCreateKeyExW(HKEY_CURRENT_USER, UninstallKey(o).c_str(), 0, nullptr, 0, KEY_SET_VALUE, nullptr, &k, nullptr);
    if (st != ERROR_SUCCESS) Fail(L"Could not add AbsentMotion to Apps: " + SystemMessage((DWORD)st));
    std::wstring setup = Join(o.dir, kSetupName), exe = Join(o.dir, kAppName);
    std::wstring tail = L" --dir " + Quote(o.dir) + (o.name == L"AbsentMotion" ? L"" : L" --name " + Quote(o.name));
    SetString(k, L"DisplayName", o.name);
    SetString(k, L"DisplayVersion", version);
    SetString(k, L"Publisher", L"AbsentMotion");
    SetString(k, L"DisplayIcon", exe + L",0");
    SetString(k, L"InstallLocation", o.dir + L"\\");
    SetString(k, L"UninstallString", Quote(setup) + L" --uninstall" + tail);
    SetString(k, L"QuietUninstallString", Quote(setup) + L" --uninstall --quiet" + tail);
    SetDword(k, L"NoModify", 1);
    SetDword(k, L"NoRepair", 1);
    if (bytes) SetDword(k, L"EstimatedSize", (DWORD)(bytes / 1024));
    std::vector<int> parts = VersionParts(version);
    if (parts.size() >= 2) {
        SetDword(k, L"VersionMajor", (DWORD)parts[0]);
        SetDword(k, L"VersionMinor", (DWORD)parts[1]);
    }
    SYSTEMTIME t;
    GetLocalTime(&t);
    wchar_t date[16];
    swprintf_s(date, L"%04u%02u%02u", t.wYear, t.wMonth, t.wDay);
    SetString(k, L"InstallDate", date);
    RegCloseKey(k);
}

constexpr const wchar_t* kExtensions[] = {L".absproj", L".amproj"};

std::wstring ProgId(const Options& o) { return KeyName(o.name) + L".project"; }

bool ClassExists(const std::wstring& progId) {
    HKEY k = nullptr;
    if (progId.empty() || RegOpenKeyExW(HKEY_CLASSES_ROOT, progId.c_str(), 0, KEY_READ, &k) != ERROR_SUCCESS) return false;
    RegCloseKey(k);
    return true;
}

void SetKeyDefault(const std::wstring& sub, const std::wstring& value) {
    HKEY k = nullptr;
    if (RegCreateKeyExW(HKEY_CURRENT_USER, sub.c_str(), 0, nullptr, 0, KEY_SET_VALUE, nullptr, &k, nullptr) != ERROR_SUCCESS) return;
    SetString(k, nullptr, value);
    RegCloseKey(k);
}

void Associate(const Options& o) {
    const std::wstring id = ProgId(o), exe = Join(o.dir, kAppName), classes = L"Software\\Classes\\";
    SetKeyDefault(classes + id, o.name + L" project");
    SetKeyDefault(classes + id + L"\\DefaultIcon", exe + L",0");
    SetKeyDefault(classes + id + L"\\shell\\open\\command", Quote(exe) + L" \"%1\" %*");
    for (const wchar_t* ext : kExtensions) {
        const std::wstring key = classes + ext;
        std::wstring current = ReadString(HKEY_CURRENT_USER, key.c_str(), nullptr);
        if (current.empty() || current == id || !ClassExists(current)) SetKeyDefault(key, id);
        HKEY k = nullptr;
        if (RegCreateKeyExW(HKEY_CURRENT_USER, (key + L"\\OpenWithProgids").c_str(), 0, nullptr, 0, KEY_SET_VALUE, nullptr, &k, nullptr) == ERROR_SUCCESS) {
            RegSetValueExW(k, id.c_str(), 0, REG_NONE, nullptr, 0);
            RegCloseKey(k);
        }
        Log(L"association " + std::wstring(ext) + L": was '" + current + L"', now '" + ReadString(HKEY_CURRENT_USER, key.c_str(), nullptr) + L"'");
    }
    SHChangeNotify(SHCNE_ASSOCCHANGED, SHCNF_IDLIST, nullptr, nullptr);
}

void Unassociate(const Options& o) {
    const std::wstring id = ProgId(o), classes = L"Software\\Classes\\";
    for (const wchar_t* ext : kExtensions) {
        const std::wstring key = classes + ext;
        HKEY k = nullptr;
        if (RegOpenKeyExW(HKEY_CURRENT_USER, key.c_str(), 0, KEY_READ | KEY_SET_VALUE, &k) == ERROR_SUCCESS) {
            if (ReadString(HKEY_CURRENT_USER, key.c_str(), nullptr) == id) RegDeleteValueW(k, nullptr);
            RegCloseKey(k);
        }
        if (RegOpenKeyExW(HKEY_CURRENT_USER, (key + L"\\OpenWithProgids").c_str(), 0, KEY_SET_VALUE, &k) == ERROR_SUCCESS) {
            RegDeleteValueW(k, id.c_str());
            RegCloseKey(k);
        }
    }
    RegDeleteTreeW(HKEY_CURRENT_USER, (classes + id).c_str());
    SHChangeNotify(SHCNE_ASSOCCHANGED, SHCNF_IDLIST, nullptr, nullptr);
}

std::vector<std::wstring> LinkPlaces(const Options& o) {
    return {Join(Known(FOLDERID_Programs), o.name + L".lnk"), Join(Known(FOLDERID_Desktop), o.name + L".lnk")};
}

void MakeLink(const std::wstring& lnk, const std::wstring& folder) {
    std::wstring exe = Join(folder, kAppName);
    IShellLinkW* link = nullptr;
    if (FAILED(CoCreateInstance(CLSID_ShellLink, nullptr, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&link)))) return;
    link->SetPath(exe.c_str());
    link->SetWorkingDirectory(folder.c_str());
    link->SetIconLocation(exe.c_str(), 0);
    link->SetDescription(L"AbsentMotion");
    IPersistFile* file = nullptr;
    if (SUCCEEDED(link->QueryInterface(IID_PPV_ARGS(&file)))) {
        file->Save(lnk.c_str(), TRUE);
        file->Release();
    }
    link->Release();
}

std::wstring LinkTarget(const std::wstring& lnk) {
    std::wstring out;
    IShellLinkW* link = nullptr;
    if (FAILED(CoCreateInstance(CLSID_ShellLink, nullptr, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&link)))) return out;
    IPersistFile* file = nullptr;
    if (SUCCEEDED(link->QueryInterface(IID_PPV_ARGS(&file)))) {
        if (SUCCEEDED(file->Load(lnk.c_str(), STGM_READ))) {
            wchar_t buf[MAX_PATH * 2] = {};
            if (SUCCEEDED(link->GetPath(buf, MAX_PATH * 2, nullptr, SLGP_RAWPATH))) out = buf;
        }
        file->Release();
    }
    link->Release();
    return out;
}

Screen Working(const std::wstring& headline, const std::wstring& detail) {
    Screen s;
    s.headline = headline;
    s.detail = detail;
    s.progress = true;
    return s;
}

int Fault(const Options& o, Shared& s, const std::wstring& headline, const std::wstring& message) {
    Log(L"failed: " + message);
    Screen f;
    f.look = Look::Failed;
    f.headline = headline;
    f.detail = message;
    f.primary = L"Close";
    s.Show(f);
    if (!o.quiet) s.WaitChoice();
    s.Quit();
    return 1;
}

bool AskWhileRunning(const Options& o, Shared& s) {
    while (!Running(o.dir).empty()) {
        if (o.quiet) {
            WaitClosed(0, o.dir, 30000);
            return Running(o.dir).empty();
        }
        Screen q;
        q.look = Look::Question;
        q.headline = o.name + L" is open";
        q.detail = L"Close it, then try again.";
        q.primary = L"Try again";
        q.secondary = L"Cancel";
        s.Show(q);
        if (s.WaitChoice() != 1) return false;
    }
    return true;
}

bool AskOlder(const Options& o, Shared& s, const std::wstring& installed, const std::wstring& version) {
    if (installed.empty() || !Older(version, installed)) return true;
    Log(L"installed " + installed + L" is newer than " + version);
    if (o.quiet) return false;
    Screen q;
    q.look = Look::Question;
    q.headline = L"A newer version is installed";
    q.detail = o.name + L" " + installed + L" is installed. This installer has the older version " + version + L".";
    q.primary = L"Install the older version";
    q.secondary = L"Cancel";
    s.Show(q);
    return s.WaitChoice() == 1;
}

bool MoveAside(const Options& o, Shared& s, const std::wstring& root, const std::wstring& old) {
    for (;;) {
        if (MoveRetry(root, old, o.quiet ? 20000 : 3000)) return true;
        DWORD e = GetLastError();
        if (o.quiet) {
            SetLastError(e);
            return false;
        }
        std::wstring who = Lockers(root);
        Screen q;
        q.look = Look::Question;
        q.headline = L"Files of " + o.name + L" are in use";
        q.detail = who.empty() ? L"Close the programs that use them, then try again." : L"Close " + who + L", then try again.";
        q.primary = L"Try again";
        q.secondary = L"Cancel";
        s.Show(q);
        if (s.WaitChoice() != 1) {
            SetLastError(e);
            return false;
        }
    }
}

int Install(const Options& o, Shared& s, const Payload& p) {
    const std::wstring root = o.dir, staging = root + L".new", old = root + L".old";
    const bool existed = Exists(Join(root, kAppName));
    const std::wstring headline = existed ? L"Reinstalling " + o.name : L"Installing " + o.name;
    if (existed && !AskOlder(o, s, InstalledVersion(root), p.version)) {
        WriteResult(o.result, "fail a newer version is already installed");
        if (o.quiet) return Fault(o, s, o.name + L" was not installed", L"A newer version is already installed.");
        s.Quit();
        return 2;
    }
    Log(L"start install " + p.version + L" into " + root + L", files " + std::to_wstring(p.files.size()));
    s.Show(Working(headline, L"Checking the download"));
    try {
        Verify(p);
        if (Exists(staging)) DeleteTree(staging);
        if (Exists(old)) DeleteTree(old);
        s.Show(Working(headline, L""));
        ULONGLONG t0 = GetTickCount64();
        Extract(p, staging, s);
        Log(L"unpacked in " + std::to_wstring(GetTickCount64() - t0) + L" ms");
        Screen w = Working(headline, L"");
        w.closable = false;
        if (existed && !AskWhileRunning(o, s)) {
            DeleteTree(staging);
            s.Quit();
            return 2;
        }
        w.headline = L"Finishing up";
        w.detail = L"";
        s.Show(w);
        if (existed && !MoveAside(o, s, root, old)) {
            DWORD e = GetLastError();
            DeleteTree(staging);
            Fail(L"AbsentMotion is still in use: " + SystemMessage(e) + InUse(root));
        }
        s.Show(w);
        if (!MoveRetry(staging, root, 20000)) {
            DWORD e = GetLastError();
            if (existed) MoveFileExW(Long(old).c_str(), Long(root).c_str(), 0);
            SetLastError(e);
            FailLast(L"Could not put the new version in place");
        }
        Log(L"swapped in " + std::to_wstring(GetTickCount64() - t0) + L" ms");
        WriteList(root, p);
        if (existed) {
            std::set<std::wstring> known;
            if (!ReadList(old, known)) for (const std::wstring& rel : p.paths) known.insert(Lower(rel));
            int kept = MoveFiles(old, root, AddedFiles(old, known));
            if (kept) Log(L"kept " + std::to_wstring(kept) + L" files you added");
        }
        Register(o, p.version, p.header.rawTotal);
        Associate(o);
        if (!existed)
            for (const std::wstring& l : LinkPlaces(o)) MakeLink(l, root);
        WriteResult(o.result, "exit 0");
        if (o.launch) {
            w.headline = L"Opening " + o.name;
            s.Show(w);
            Launch(root);
            ULONGLONG end = GetTickCount64() + 60000;
            while (!AppWindowVisible(root) && GetTickCount64() < end) Sleep(100);
            Log(L"app window after " + std::to_wstring(GetTickCount64() - t0) + L" ms");
        } else if (!o.quiet) {
            Screen d;
            d.look = Look::Done;
            d.headline = o.name + L" is installed";
            d.primary = L"Close";
            s.Show(d);
            s.WaitChoice();
        }
        s.Quit();
        if (Exists(old)) DeleteTree(old);
        Log(L"done");
        return 0;
    } catch (const Cancelled&) {
        Log(L"cancelled");
        s.Quit();
        DeleteTree(staging);
        return 2;
    } catch (const Error& e) {
        if (Exists(staging)) DeleteTree(staging);
        WriteResult(o.result, "fail " + Narrow(e.message));
        return Fault(o, s, o.name + L" was not installed", e.message);
    }
}

void TryLaunch(const std::wstring& root) {
    if (!Exists(Join(root, kAppName))) return;
    try {
        Launch(root);
    } catch (const Error& e) {
        Log(L"could not open the app again: " + e.message);
    }
}

const char* WriteFailure(const Error& e) {
    if (e.code == ERROR_DISK_FULL || e.code == ERROR_HANDLE_DISK_FULL) return kSpace;
    return e.code ? kPlace : kPayload;
}

int Update(const Options& o, Shared& s) {
    const std::wstring root = o.dir, staging = root + L".new", old = root + L".old";
    const std::wstring logDir = o.log.empty() ? Parent(g_log) : Parent(o.log);
    Screen w = Working(L"Updating " + o.name, L"Checking the update");
    w.closable = false;
    s.Show(w);
    bool moved = false, msiRemoved = false, desktop = false;
    std::wstring msi, oldVersion;
    ULONGLONG t0 = GetTickCount64();
    try {
        Stage(o, "verify");
        Payload p;
        try {
            if (o.payload.empty()) Fail(L"No update file was given");
            p = ReadPayload(o.payload);
            Verify(p);
        } catch (const Error& e) {
            StopWith(kPayload, e.message);
        }
        Log(L"start update " + p.version + L" into " + root + L", files " + std::to_wstring(p.files.size()));
        w.headline = L"Updating to " + p.version;
        w.detail = L"";
        s.Show(w);
        if (!Exists(Join(root, kAppName))) StopWith(kPlace, L"There is no AbsentMotion in " + root);
        if (!Writable(Parent(root))) StopWith(kPlace, L"Windows does not allow changes in " + Parent(root));
        oldVersion = InstalledVersion(root);
        if (Exists(staging)) DeleteTree(staging);
        if (Exists(old)) DeleteTree(old);
        ULARGE_INTEGER space{};
        if (GetDiskFreeSpaceExW(Parent(root).c_str(), &space, nullptr, nullptr) && space.QuadPart < p.header.rawTotal + (64ull << 20))
            StopWith(kSpace, L"There is not enough free space on the disk");
        msi = FindMsi(root);
        try {
            Extract(p, staging, s);
        } catch (const Error& e) {
            StopWith(WriteFailure(e), e.message);
        }
        Log(L"unpacked in " + std::to_wstring(GetTickCount64() - t0) + L" ms");
        Stage(o, "close");
        w.detail = L"Closing AbsentMotion";
        s.Show(w);
        if (o.waitPid) {
            Handle app(OpenProcess(SYNCHRONIZE, FALSE, o.waitPid));
            if (app.Valid() && WaitForSingleObject(app.Get(), 60000) == WAIT_TIMEOUT) Log(L"the app did not quit by itself");
        }
        for (ULONGLONG end = GetTickCount64() + 3000; !Running(root).empty() && GetTickCount64() < end;) Sleep(100);
        if (!CloseAll(root)) StopWith(kNoClose, L"AbsentMotion did not close");
        w.headline = L"Finishing up";
        w.detail = L"";
        s.Show(w);
        if (!msi.empty()) {
            Stage(o, "msi");
            const std::wstring msiLink = Join(Known(FOLDERID_Desktop), std::wstring(L"AbsentMotion.lnk")), target = LinkTarget(msiLink);
            desktop = Exists(msiLink) && (target.empty() || StartsWithFolder(Full(target), root));
            if (!MoveRetry(root, old, 20000)) StopWith(kInUse, L"Files of AbsentMotion were still in use: " + SystemMessage(GetLastError()) + InUse(root));
            moved = true;
            if (!RemoveMsi(msi, root, logDir)) {
                if (MoveRetry(old, root, 20000)) moved = false;
                StopWith(kOld, L"Windows Installer did not remove the old version");
            }
            msiRemoved = true;
        }
        Stage(o, "swap");
        if (!moved) {
            if (!MoveRetry(root, old, 20000)) StopWith(kInUse, L"Files of AbsentMotion were still in use: " + SystemMessage(GetLastError()) + InUse(root));
            moved = true;
        }
        if (!MoveRetry(staging, root, 20000)) {
            std::wstring why = SystemMessage(GetLastError());
            if (MoveRetry(old, root, 20000)) moved = false;
            StopWith(kPlace, L"Could not put the new version in place: " + why);
        }
        Log(L"swapped in " + std::to_wstring(GetTickCount64() - t0) + L" ms");
        WriteList(root, p);
        {
            std::set<std::wstring> known;
            if (!ReadList(old, known)) for (const std::wstring& rel : p.paths) known.insert(Lower(rel));
            int kept = MoveFiles(old, root, AddedFiles(old, known));
            if (kept) Log(L"kept " + std::to_wstring(kept) + L" files you added");
        }
        Stage(o, "register");
        try {
            Register(o, p.version, p.header.rawTotal);
        } catch (const Error& e) {
            Log(L"could not update the Apps entry: " + e.message);
        }
        std::vector<std::wstring> links = LinkPlaces(o);
        if (msiRemoved || !StartsWithFolder(LinkTarget(links[0]), root)) MakeLink(links[0], root);
        if (msiRemoved && desktop) MakeLink(links[1], root);
        Associate(o);
        ResultLine(o.result, "ok " + Narrow(p.version));
        if (o.launch) {
            w.headline = L"Opening " + o.name;
            s.Show(w);
            TryLaunch(root);
            ULONGLONG end = GetTickCount64() + 60000;
            while (!AppWindowVisible(root) && GetTickCount64() < end) Sleep(100);
            Log(L"app window after " + std::to_wstring(GetTickCount64() - t0) + L" ms");
        }
        s.Quit();
        if (Exists(old)) DeleteTree(old);
        Log(L"done");
        return 0;
    } catch (const Stop& f) {
        Log(L"failed: " + Widen(f.reason) + L": " + f.detail);
        if (moved && !Exists(root) && Exists(old)) MoveRetry(old, root, 20000);
        if (Exists(staging)) DeleteTree(staging);
        if (msiRemoved || (!msi.empty() && Exists(Join(root, kAppName)) && FindMsi(root).empty())) {
            try {
                Register(o, oldVersion.empty() ? L"unknown" : oldVersion, 0);
            } catch (const Error&) {
            }
            MakeLink(LinkPlaces(o)[0], root);
            Associate(o);
        }
        ResultLine(o.result, std::string("fail ") + f.reason);
        if (o.launch) TryLaunch(root);
        Screen fail;
        fail.look = Look::Failed;
        fail.headline = L"The update did not finish";
        fail.detail = f.detail + L"\nYour current version is still installed.";
        fail.closable = false;
        s.Show(fail);
        Sleep(2500);
        s.Quit();
        return 1;
    } catch (const Cancelled&) {
        Log(L"cancelled");
        if (moved && !Exists(root) && Exists(old)) MoveRetry(old, root, 20000);
        if (Exists(staging)) DeleteTree(staging);
        ResultLine(o.result, "fail the update was cancelled");
        if (o.launch) TryLaunch(root);
        s.Quit();
        return 2;
    }
}

int Uninstall(const Options& o, Shared& s) {
    const std::wstring root = o.dir, gone = root + L".remove";
    Log(L"uninstall " + root);
    if (!Exists(Join(root, kAppName)) || !Exists(Join(root, kSetupName)))
        return Fault(o, s, o.name + L" was not removed", L"This folder is not an AbsentMotion install:\n" + root);
    if (!o.quiet) {
        Screen q;
        q.look = Look::Question;
        q.headline = L"Remove " + o.name + L"?";
        q.detail = L"Your projects and settings stay on this PC, so they are there if you install it again.";
        q.primary = L"Remove";
        q.secondary = L"Cancel";
        s.Show(q);
        if (s.WaitChoice() != 1) {
            s.Quit();
            return 1;
        }
    }
    if (!AskWhileRunning(o, s)) {
        s.Quit();
        return 1;
    }
    Screen w = Working(L"Removing " + o.name, L"Removing files");
    w.closable = false;
    s.Show(w);
    int kept = 0;
    try {
        if (Exists(gone)) DeleteTree(gone);
        if (Exists(root) && !MoveRetry(root, gone, 10000)) {
            DWORD e = GetLastError();
            Fail(L"Could not remove the app folder: " + SystemMessage(e) + InUse(root));
        }
        std::set<std::wstring> known;
        std::vector<std::wstring> added;
        if (ReadList(gone, known)) added = AddedFiles(gone, known);
        if (!added.empty() && CreateDirectoryW(Long(root).c_str(), nullptr)) {
            kept = MoveFiles(gone, root, added);
            Log(L"kept " + std::to_wstring(kept) + L" files you added in " + root);
        }
        uint64_t total = std::max<uint64_t>(1, CountFiles(gone)), done = 0;
        s.Progress(0, total);
        DeleteTree(gone, [&] { s.Progress(++done, total); });
        for (const std::wstring& l : LinkPlaces(o))
            if (StartsWithFolder(LinkTarget(l), root)) DeleteFileW(l.c_str());
        RegDeleteTreeW(HKEY_CURRENT_USER, UninstallKey(o).c_str());
        Unassociate(o);
        Log(L"removed");
    } catch (const Error& e) {
        return Fault(o, s, o.name + L" was not removed", e.message);
    }
    if (!o.quiet) {
        Screen d;
        d.look = Look::Done;
        d.headline = o.name + L" was removed";
        d.detail = kept ? L"Your projects and settings are still on this PC. Files you added to its folder are still in " + root + L"."
                        : L"Your projects and settings are still on this PC.";
        d.primary = L"Close";
        s.Show(d);
        s.WaitChoice();
    }
    s.Quit();
    return 0;
}

bool RelaunchFromTemp(const Options& o) {
    std::wstring tmp(MAX_PATH + 1, L'\0');
    tmp.resize(GetTempPathW((DWORD)tmp.size(), tmp.data()));
    std::wstring copy = Join(tmp, L"AbsentMotionSetup-" + std::to_wstring(GetCurrentProcessId()) + L".exe");
    if (!CopyFileW(SelfPath().c_str(), copy.c_str(), FALSE)) return false;
    std::wstring cmd = Quote(copy) + L" --uninstall --from-temp --dir " + Quote(o.dir) + L" --name " + Quote(o.name) + (o.quiet ? L" --quiet" : L"") +
                       (o.log.empty() ? L"" : L" --log " + Quote(o.log));
    STARTUPINFOW si{sizeof si};
    PROCESS_INFORMATION pi{};
    if (!CreateProcessW(copy.c_str(), cmd.data(), nullptr, nullptr, FALSE, 0, nullptr, tmp.c_str(), &si, &pi)) return false;
    CloseHandle(pi.hThread);
    CloseHandle(pi.hProcess);
    return true;
}

void DeleteSelfLater() {
    std::wstring sys(MAX_PATH, L'\0');
    sys.resize(GetSystemDirectoryW(sys.data(), (UINT)sys.size()));
    std::wstring cmd = Quote(Join(sys, L"cmd.exe")) + L" /d /c ping -n 3 127.0.0.1 >nul & del /f /q " + Quote(SelfPath());
    STARTUPINFOW si{sizeof si};
    PROCESS_INFORMATION pi{};
    if (CreateProcessW(nullptr, cmd.data(), nullptr, nullptr, FALSE, CREATE_NO_WINDOW, nullptr, sys.c_str(), &si, &pi)) {
        CloseHandle(pi.hThread);
        CloseHandle(pi.hProcess);
    }
}

int Run(HINSTANCE instance) {
    Options o = Parse();
    if (o.probe) return 0;
    SetCurrentDirectoryW(Parent(SelfPath()).c_str());
    if (o.log.empty()) {
        std::wstring tmp(MAX_PATH + 1, L'\0');
        tmp.resize(GetTempPathW((DWORD)tmp.size(), tmp.data()));
        g_log = Join(tmp, L"AbsentMotion-setup.log");
    } else {
        g_log = o.log;
    }
    if (o.mode == Mode::Update) ResultLine(o.result, "started", true);
    CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);
    if (o.mode == Mode::Uninstall && !o.fromTemp && RelaunchFromTemp(o)) return 0;
    Shared s;
    s.title = o.name + (o.mode == Mode::Uninstall ? L" Uninstall" : (o.mode == Mode::Update ? L" Update" : L" Setup"));
    Payload p;
    std::wstring broken;
    if (o.mode == Mode::Install) {
        try {
            p = ReadPayload(o.payload.empty() ? SelfPath() : o.payload);
            s.version = L"Version " + p.version;
        } catch (const Error& e) {
            broken = e.message;
        }
    }
    Handle lock(CreateMutexW(nullptr, FALSE, (L"Local\\AbsentMotionSetup-" + KeyName(o.name)).c_str()));
    if (lock.Valid() && WaitForSingleObject(lock.Get(), o.mode == Mode::Install ? 0 : 120000) == WAIT_TIMEOUT) {
        if (o.mode == Mode::Update) {
            Log(L"another update of " + o.dir + L" was still running");
            ResultLine(o.result, std::string("fail ") + kInUse);
            CoUninitialize();
            return 1;
        }
        HWND other = FindWindowW(L"AbsentMotionSetupWindow", nullptr);
        if (other) SetForegroundWindow(other);
        return 0;
    }
    int code = 0;
    std::thread worker([&] {
        CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);
        if (!broken.empty()) {
            WriteResult(o.result, "fail " + Narrow(broken));
            code = Fault(o, s, o.name + L" was not installed", broken);
        } else {
            code = o.mode == Mode::Uninstall ? Uninstall(o, s) : (o.mode == Mode::Update ? Update(o, s) : Install(o, s, p));
        }
        s.Quit();
        CoUninitialize();
    });
    if (!o.quiet) RunWindow(instance, s);
    worker.join();
    if (lock.Valid()) ReleaseMutex(lock.Get());
    if (o.fromTemp) DeleteSelfLater();
    CoUninitialize();
    return code;
}

}
}

int APIENTRY wWinMain(HINSTANCE instance, HINSTANCE, PWSTR, int) {
    try {
        return am::Run(instance);
    } catch (...) {
        return 3;
    }
}
