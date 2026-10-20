#include "textanim.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <numeric>

namespace am::textanim {
namespace {

bool Between(uint32_t c, uint32_t a, uint32_t b) { return c >= a && c <= b; }

bool Indic(uint32_t c) { return Between(c, 0x0900, 0x0DFF); }

bool Virama(uint32_t c) { return Indic(c) && (c & 0x7F) == 0x4D; }

bool Extends(uint32_t c) {
    if (Indic(c)) {
        const uint32_t b = c & 0x7F;
        return b <= 0x03 || b == 0x3C || Between(b, 0x3E, 0x4F) || Between(b, 0x51, 0x57) || b == 0x62 || b == 0x63 ||
               c == 0x093A || c == 0x093B;
    }
    return Between(c, 0x0300, 0x036F) || Between(c, 0x0483, 0x0489) || Between(c, 0x0591, 0x05BD) || c == 0x05BF ||
           c == 0x05C1 || c == 0x05C2 || c == 0x05C4 || c == 0x05C5 || c == 0x05C7 || Between(c, 0x0610, 0x061A) ||
           Between(c, 0x064B, 0x065F) || c == 0x0670 || Between(c, 0x06D6, 0x06DC) || Between(c, 0x06DF, 0x06E4) ||
           c == 0x06E7 || c == 0x06E8 || Between(c, 0x06EA, 0x06ED) || c == 0x0E31 || Between(c, 0x0E34, 0x0E3A) ||
           Between(c, 0x0E47, 0x0E4E) || c == 0x0EB1 || Between(c, 0x0EB4, 0x0EBC) || Between(c, 0x0EC8, 0x0ECE) ||
           Between(c, 0x1160, 0x11FF) || Between(c, 0x1AB0, 0x1AFF) || Between(c, 0x1DC0, 0x1DFF) || c == 0x200C ||
           c == 0x200D || Between(c, 0x20D0, 0x20FF) || Between(c, 0x302A, 0x302F) || c == 0x3099 || c == 0x309A ||
           Between(c, 0xFE00, 0xFE0F) || Between(c, 0xFE20, 0xFE2F) || Between(c, 0x1F3FB, 0x1F3FF) ||
           Between(c, 0xE0020, 0xE007F) || Between(c, 0xE0100, 0xE01EF);
}

bool Regional(uint32_t c) { return Between(c, 0x1F1E6, 0x1F1FF); }

bool Digit(char c) { return c >= '0' && c <= '9'; }

bool Word(char c) { return Digit(c) || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (unsigned char)c >= 0x80; }

std::string Format(double v, int decimals, int separator) {
    char buffer[64];
    snprintf(buffer, sizeof buffer, "%.*f", decimals, std::clamp(v, -1e15, 1e15));
    std::string s = buffer;
    if (s[0] == '-' && s.find_first_of("123456789") == std::string::npos) s.erase(0, 1);
    const bool negative = s[0] == '-';
    if (negative) s.erase(0, 1);
    const size_t point = s.find('.');
    std::string whole = s.substr(0, point), out;
    const char group = separator == 1 ? ',' : separator == 2 ? '.' : ' ';
    for (size_t i = 0; i < whole.size(); i++) {
        if (separator > 0 && i > 0 && (whole.size() - i) % 3 == 0) out += group;
        out += whole[i];
    }
    if (point != std::string::npos) out += (separator == 2 ? ',' : '.') + s.substr(point + 1);
    return negative ? "-" + out : out;
}

}

uint64_t Mix(uint64_t x) {
    x += 0x9e3779b97f4a7c15ull;
    x = (x ^ (x >> 30)) * 0xbf58476d1ce4e5b9ull;
    x = (x ^ (x >> 27)) * 0x94d049bb133111ebull;
    return x ^ (x >> 31);
}

std::vector<uint32_t> CodePoints(const std::string& s, std::vector<int>* starts) {
    std::vector<uint32_t> out;
    for (size_t i = 0; i < s.size();) {
        const unsigned char b = s[i];
        const int length = b < 0x80 ? 1 : (b >> 5) == 6 ? 2 : (b >> 4) == 14 ? 3 : (b >> 3) == 30 ? 4 : 1;
        uint32_t c = length == 1 ? (b < 0x80 ? b : 0xFFFD) : b & (0x7F >> length);
        bool valid = i + length <= s.size();
        for (int k = 1; valid && k < length; k++) {
            const unsigned char next = s[i + k];
            valid = (next & 0xC0) == 0x80;
            c = (c << 6) | (next & 0x3F);
        }
        if (starts) starts->push_back((int)i);
        out.push_back(valid ? c : 0xFFFD);
        i += valid ? length : 1;
    }
    return out;
}

std::string Utf8(uint32_t c) {
    std::string s;
    if (c < 0x80) {
        s += (char)c;
    } else if (c < 0x800) {
        s += (char)(0xC0 | (c >> 6));
        s += (char)(0x80 | (c & 0x3F));
    } else if (c < 0x10000) {
        s += (char)(0xE0 | (c >> 12));
        s += (char)(0x80 | ((c >> 6) & 0x3F));
        s += (char)(0x80 | (c & 0x3F));
    } else {
        s += (char)(0xF0 | (c >> 18));
        s += (char)(0x80 | ((c >> 12) & 0x3F));
        s += (char)(0x80 | ((c >> 6) & 0x3F));
        s += (char)(0x80 | (c & 0x3F));
    }
    return s;
}

bool Space(uint32_t c) {
    return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == 0x0B || c == 0x0C || c == 0x85 || c == 0xA0 ||
           c == 0x1680 || Between(c, 0x2000, 0x200A) || c == 0x2028 || c == 0x2029 || c == 0x202F || c == 0x205F || c == 0x3000;
}

Segments Split(const std::string& text) {
    Segments s;
    s.character.assign(text.size(), -1);
    s.word.assign(text.size(), -1);
    std::vector<int> starts;
    const std::vector<uint32_t> cps = CodePoints(text, &starts);
    int regional = 0;
    bool inWord = false, wordHead = false;
    for (size_t i = 0; i < cps.size(); i++) {
        const uint32_t c = cps[i], previous = i ? cps[i - 1] : 0;
        const bool joins = i > 0 && (Extends(c) || previous == 0x200D || Virama(previous) || (previous == '\r' && c == '\n') ||
                                     (Regional(c) && regional % 2 == 1));
        regional = Regional(c) ? (joins ? regional + 1 : 1) : 0;
        if (!joins) {
            s.characters++;
            wordHead = !Space(c);
            if (wordHead && !inWord) s.words++;
            inWord = wordHead;
        }
        const int end = i + 1 < cps.size() ? starts[i + 1] : (int)text.size();
        for (int b = starts[i]; b < end; b++) {
            s.character[b] = s.characters - 1;
            s.word[b] = wordHead ? s.words - 1 : -1;
        }
    }
    return s;
}

std::vector<int> Ranks(int count, int direction, uint64_t seed, int* steps) {
    std::vector<int> ranks(std::max(count, 0));
    std::iota(ranks.begin(), ranks.end(), 0);
    *steps = count;
    if (count <= 0) return ranks;
    if (direction == kFromEnd) {
        for (int& r : ranks) r = count - 1 - r;
    } else if (direction == kFromCentre) {
        for (int i = 0; i < count; i++) ranks[i] = (int)std::floor(std::fabs(i - (count - 1) * 0.5));
        *steps = *std::max_element(ranks.begin(), ranks.end()) + 1;
    } else if (direction == kRandom) {
        std::vector<int> order(count);
        std::iota(order.begin(), order.end(), 0);
        std::stable_sort(order.begin(), order.end(), [&](int a, int b) { return Mix(seed ^ Mix(a + 1)) < Mix(seed ^ Mix(b + 1)); });
        for (int k = 0; k < count; k++) ranks[order[k]] = k;
    }
    return ranks;
}

float Reveal(float progress, int rank, int steps, float softness) {
    if (steps <= 0) return 1;
    const float soft = std::max(softness, 0.0f);
    return std::clamp((progress * (steps + soft) - rank) / (1 + soft), 0.0f, 1.0f);
}

float RangeWeight(int shape, int index, int count, float start, float end) {
    if (count <= 0) return 0;
    if (start > end) std::swap(start, end);
    const float a = (float)index / count, b = (float)(index + 1) / count, x = (a + b) / 2;
    if (shape == kSquare) return std::clamp((std::min(b, end) - std::max(a, start)) * count, 0.0f, 1.0f);
    const float width = end - start;
    if (shape == kRampUp || shape == kRampDown) {
        const float u = width > 1e-6f ? std::clamp((x - start) / width, 0.0f, 1.0f) : (x >= start ? 1.0f : 0.0f);
        return shape == kRampUp ? u : 1 - u;
    }
    if (x < start || x > end || !(width > 1e-6f)) return 0;
    const float u = (x - start) / width;
    if (shape == kTriangle) return 1 - std::fabs(2 * u - 1);
    if (shape == kRound) return std::sqrt(std::max(0.0f, 1 - (2 * u - 1) * (2 * u - 1)));
    return 0.5f - 0.5f * std::cos(6.2831853f * u);
}

std::string CountNumbers(const std::string& text, double add, double multiply, int decimals, int separator) {
    std::string out;
    for (size_t i = 0; i < text.size();) {
        const bool sign = text[i] == '-' && i + 1 < text.size() && Digit(text[i + 1]) && (i == 0 || (!Word(text[i - 1]) && text[i - 1] != '.'));
        if (!Digit(text[i]) && !sign) {
            out += text[i++];
            continue;
        }
        size_t j = i + (sign ? 1 : 0), lead = j;
        std::string digits = sign ? "-" : "";
        while (j < text.size() && Digit(text[j])) digits += text[j++];
        while (j - lead <= 3 && j + 3 < text.size() && text[j] == ',' && Digit(text[j + 1]) && Digit(text[j + 2]) && Digit(text[j + 3]) &&
               (j + 4 >= text.size() || !Digit(text[j + 4]))) {
            digits += text.substr(j + 1, 3);
            j += 4;
            lead = j - 3;
        }
        int places = 0;
        if (j + 1 < text.size() && text[j] == '.' && Digit(text[j + 1])) {
            digits += text[j++];
            while (j < text.size() && Digit(text[j])) digits += text[j++], places++;
        }
        const double value = strtod(digits.c_str(), nullptr) * multiply + add;
        out += Format(value, decimals < 0 ? places : std::min(decimals, 6), separator);
        i = j;
    }
    return out;
}

std::string FormatTimecode(double seconds, double fps, int format) {
    if (!std::isfinite(seconds)) seconds = 0;
    const bool negative = seconds < 0;
    const double s = std::min(std::fabs(seconds), 3.6e8);
    const long long rate = std::max(1LL, std::llround(fps));
    const long long frames = (long long)std::floor(s * rate + 1e-6), whole = (long long)std::floor(s + 1e-6);
    const long long hundredths = (long long)std::floor(s * 100 + 1e-6);
    char buffer[64];
    switch (format) {
        case kHMS: snprintf(buffer, sizeof buffer, "%02lld:%02lld:%02lld", whole / 3600, whole / 60 % 60, whole % 60); break;
        case kMS: snprintf(buffer, sizeof buffer, "%02lld:%02lld", whole / 60, whole % 60); break;
        case kMSHundredths:
            snprintf(buffer, sizeof buffer, "%02lld:%02lld.%02lld", hundredths / 6000, hundredths / 100 % 60, hundredths % 100);
            break;
        case kSHundredths: snprintf(buffer, sizeof buffer, "%lld.%02lld", hundredths / 100, hundredths % 100); break;
        case kFrames: snprintf(buffer, sizeof buffer, "%lld", frames); break;
        default:
            snprintf(buffer, sizeof buffer, "%02lld:%02lld:%02lld:%02lld", frames / (3600 * rate), frames / (60 * rate) % 60,
                     frames / rate % 60, frames % rate);
    }
    return (negative && (format == kFrames ? frames : hundredths) > 0 ? "-" : "") + std::string(buffer);
}

std::string Scramble(const std::string& text, float progress, int direction, uint64_t seed, int64_t tick, int charset,
                     const std::string& custom, bool keepSpaces) {
    static const char* const kSets[] = {"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz", "ABCDEFGHIJKLMNOPQRSTUVWXYZ",
                                        "abcdefghijklmnopqrstuvwxyz", "0123456789",
                                        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789", "!#$%&*+-=?@^~<>/|\\{}[]"};
    std::vector<uint32_t> pool;
    if (charset == kCustom) {
        for (uint32_t c : CodePoints(custom)) if (!Space(c)) pool.push_back(c);
    }
    if (pool.empty()) pool = CodePoints(kSets[std::clamp(charset, 0, 5)]);
    const Segments seg = Split(text);
    std::vector<std::pair<int, int>> clusters;
    for (int b = 0; b < (int)text.size(); b++) {
        if (b == 0 || seg.character[b] != seg.character[b - 1]) clusters.push_back({b, b});
        clusters.back().second = b + 1;
    }
    std::vector<int> eligible(clusters.size(), -1);
    int count = 0;
    for (size_t k = 0; k < clusters.size(); k++) {
        const uint32_t c = CodePoints(text.substr(clusters[k].first, clusters[k].second - clusters[k].first))[0];
        if (c != '\n' && c != '\r' && !(keepSpaces && Space(c))) eligible[k] = count++;
    }
    int steps = 0;
    const std::vector<int> ranks = Ranks(count, direction, seed, &steps);
    std::string out;
    for (size_t k = 0; k < clusters.size(); k++) {
        const int e = eligible[k];
        if (e < 0 || Reveal(progress, ranks[e], steps, 0) >= 1) {
            out += text.substr(clusters[k].first, clusters[k].second - clusters[k].first);
            continue;
        }
        const uint64_t h = Mix(seed ^ Mix((uint64_t)k * 0x632be59bd9b4e019ull + 1) ^ Mix((uint64_t)tick + 0x51ed27ull));
        out += Utf8(pool[h % pool.size()]);
    }
    return out;
}

}
