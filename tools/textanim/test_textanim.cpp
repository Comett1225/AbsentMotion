#include "frames.h"
#include "textanim.h"

#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <limits>
#include <set>
#include <string>
#include <vector>

using namespace am::textanim;

static int failures = 0;

static void check(bool ok, const char* what) {
    if (!ok) {
        printf("FAIL %s\n", what);
        failures++;
    }
}

static bool near(float a, float b) { return std::fabs(a - b) < 1e-4f; }

static std::vector<std::string> pieces(const std::string& text, const std::vector<int>& unit) {
    std::vector<std::string> out;
    for (size_t b = 0; b < text.size(); b++) {
        if (unit[b] < 0) continue;
        if ((int)out.size() <= unit[b]) out.resize(unit[b] + 1);
        out[unit[b]] += text[b];
    }
    return out;
}

int main() {
    {
        const std::string text = "été";
        const Segments s = Split(text);
        check(s.characters == 3, "combining acute stays with its letter");
        check(pieces(text, s.character)[0] == "é", "first cluster holds e and the accent");
    }
    {
        const std::string family = "\U0001F468‍\U0001F469‍\U0001F467";
        const std::string text = "a" + family + "\U0001F44D\U0001F3FD" + "\U0001F1EC\U0001F1E7\U0001F1EB\U0001F1F7" + "❤️";
        const Segments s = Split(text);
        const auto c = pieces(text, s.character);
        check(s.characters == 6, "emoji family, skin tone, two flags and a heart are one character each");
        check(c.size() == 6 && c[1] == family, "zero width joiner sequence is one character");
        check(c.size() == 6 && c[2] == "\U0001F44D\U0001F3FD", "skin tone modifier joins the emoji");
        check(c.size() == 6 && c[3] == "\U0001F1EC\U0001F1E7" && c[4] == "\U0001F1EB\U0001F1F7", "regional indicators pair into flags");
        check(c.size() == 6 && c[5] == "❤️", "variation selector joins");
    }
    {
        const std::string text = "क्षि";
        check(Split(text).characters == 1, "devanagari conjunct with a vowel sign is one character");
        check(Split("กิน").characters == 2, "thai vowel above joins its consonant");
    }
    {
        const std::string text = "  Hello,  wide\tworld\nمرحبا بك ";
        const Segments s = Split(text);
        const auto w = pieces(text, s.word);
        check(s.words == 5, "five words");
        check(w.size() == 5 && w[0] == "Hello," && w[1] == "wide" && w[2] == "world", "words split on spaces, tabs and newlines");
        check(w.size() == 5 && w[3] == "مرحبا" && w[4] == "بك", "right-to-left words keep logical order");
        check(s.word[0] == -1 && s.word[text.size() - 1] == -1, "spaces belong to no word");
        check(Split("مرحبا").characters == 5, "arabic letters are characters in logical order");
        check(Split("بَب").characters == 2, "arabic harakat join their letter");
    }
    {
        check(Split("a\r\nb").characters == 3, "CR LF is one character");
        check(Split("").characters == 0 && Split("").words == 0, "empty text has no units");
        check(Split("\xff" "a").characters == 2, "a bad byte counts as one character");
    }
    {
        int steps = 0;
        auto r = Ranks(5, kFromStart, 1, &steps);
        check(steps == 5 && r == std::vector<int>({0, 1, 2, 3, 4}), "from start");
        r = Ranks(5, kFromEnd, 1, &steps);
        check(steps == 5 && r == std::vector<int>({4, 3, 2, 1, 0}), "from end");
        r = Ranks(5, kFromCentre, 1, &steps);
        check(steps == 3 && r == std::vector<int>({2, 1, 0, 1, 2}), "from centre, odd");
        r = Ranks(4, kFromCentre, 1, &steps);
        check(steps == 2 && r == std::vector<int>({1, 0, 0, 1}), "from centre, even");
        r = Ranks(30, kRandom, 7, &steps);
        check(steps == 30 && std::set<int>(r.begin(), r.end()).size() == 30, "random order is a permutation");
        check(r == Ranks(30, kRandom, 7, &steps), "random order repeats for a seed");
        check(r != Ranks(30, kRandom, 8, &steps), "another seed gives another order");
        Ranks(0, kFromCentre, 1, &steps);
        check(steps == 0, "no units, no steps");
    }
    {
        check(near(Reveal(0, 0, 4, 0), 0) && near(Reveal(1, 3, 4, 0), 1), "reveal starts hidden and ends shown");
        check(near(Reveal(0.5f, 1, 4, 0), 1) && near(Reveal(0.5f, 2, 4, 0), 0), "half way shows half the units");
        check(near(Reveal(0.5f, 2, 4, 2), (0.5f * 6 - 2) / 3), "softness spreads each unit over more steps");
        check(near(Reveal(1, 3, 4, 10), 1) && near(Reveal(0, 0, 4, 10), 0), "soft reveal still ends shown");
        check(near(Reveal(0.3f, 0, 0, 0), 1), "nothing to reveal");
    }
    {
        check(near(RangeWeight(kSquare, 0, 4, 0, 1), 1) && near(RangeWeight(kSquare, 3, 4, 0, 1), 1), "square covers the full range");
        check(near(RangeWeight(kSquare, 1, 4, 0, 0.375f), 0.5f), "square covers part of a unit");
        check(near(RangeWeight(kSquare, 3, 4, 0, 0.5f), 0), "square outside");
        check(near(RangeWeight(kSquare, 1, 4, 0.375f, 0), 0.5f), "square with start after end");
        check(near(RangeWeight(kRampUp, 0, 4, 0, 1), 0.125f) && near(RangeWeight(kRampUp, 3, 4, 0, 1), 0.875f), "ramp up rises");
        check(near(RangeWeight(kRampUp, 3, 4, 0, 0.5f), 1) && near(RangeWeight(kRampUp, 0, 4, 0.5f, 1), 0), "ramp up holds 1 after the end, 0 before the start");
        check(near(RangeWeight(kRampDown, 0, 4, 0, 1), 0.875f) && near(RangeWeight(kRampDown, 3, 4, 0, 0.5f), 0), "ramp down falls");
        check(near(RangeWeight(kTriangle, 1, 2, 0, 1), 0.5f) && near(RangeWeight(kTriangle, 0, 1, 0, 1), 1), "triangle peaks in the middle");
        check(near(RangeWeight(kRound, 0, 1, 0, 1), 1) && near(RangeWeight(kRound, 1, 2, 0, 1), std::sqrt(0.75f)), "round is a half circle");
        check(near(RangeWeight(kSmooth, 0, 1, 0, 1), 1) && near(RangeWeight(kSmooth, 1, 2, 0, 1), 0.5f), "smooth is a bell");
        check(near(RangeWeight(kTriangle, 3, 4, 0, 0.5f), 0) && near(RangeWeight(kSmooth, 0, 4, 0.5f, 1), 0), "shapes are 0 outside");
        check(near(RangeWeight(kRampUp, 2, 4, 0.5f, 0.5f), 1) && near(RangeWeight(kRampUp, 1, 4, 0.5f, 0.5f), 0), "zero width ramp is a step");
        check(near(RangeWeight(kSquare, 0, 0, 0, 1), 0), "no units");
    }
    {
        check(CountNumbers("Score: 100", 0, 0.5, -1, 0) == "Score: 50", "multiply");
        check(CountNumbers("$1,250.50 now", 10, 1, -1, 1) == "$1,260.50 now", "thousands and decimals read and written");
        check(CountNumbers("1234567", 0, 1, 2, 2) == "1.234.567,00", "dot groups with comma decimals");
        check(CountNumbers("1234567", 0, 1, 0, 3) == "1 234 567", "space groups");
        check(CountNumbers("2-3 and -4", 0, 2, -1, 0) == "4-6 and -8", "minus only in front of a number");
        check(CountNumbers("0.5", -0.5, 1, 1, 0) == "0.0" && CountNumbers("1", -1.0001, 1, 2, 0) == "0.00", "no minus zero");
        check(CountNumbers("v1.2.3", 1, 1, -1, 0) == "v2.2.4", "version numbers count part by part");
        check(CountNumbers("no numbers here", 5, 2, -1, 1) == "no numbers here", "text without numbers stays");
        check(CountNumbers("1.23456789", 0, 1, -1, 0) == "1.23456789", "as typed preserves more than six decimals");
        check(CountNumbers("7", 0.6, 1, 0, 0) == "8", "rounds to the decimals asked for");
        check(CountNumbers(std::string(400, '9'), 0, 0, -1, 0) == "0", "overflowing input multiplied by zero stays numeric");
        check(CountNumbers("١ 100", 5, 1, -1, 0) == "١ 105", "other scripts pass through");
    }
    {
        check(FormatTimecode(3723.5, 30, kHMSF) == "01:02:03:15", "HH:MM:SS:FF");
        check(FormatTimecode(3723.5, 30, kHMS) == "01:02:03", "HH:MM:SS");
        check(FormatTimecode(3723.5, 30, kMS) == "62:03", "MM:SS counts minutes past an hour");
        check(FormatTimecode(61.257, 30, kMSHundredths) == "01:01.25", "MM:SS.hh");
        check(FormatTimecode(9.5, 30, kSHundredths) == "9.50", "SS.hh");
        check(FormatTimecode(2, 24, kFrames) == "48", "frames");
        check(FormatTimecode(-1.5, 30, kMSHundredths) == "-00:01.50", "negative time");
        check(FormatTimecode(-0.1, 30, kHMS) == "00:00:00", "no negative zero in whole seconds");
        check(FormatTimecode(-0.01, 30, kHMSF) == "00:00:00:00", "no negative zero below one frame");
        check(FormatTimecode(1, std::numeric_limits<double>::quiet_NaN(), kFrames) == "30", "invalid frame rate has a finite fallback");
        check(FormatTimecode(-0.001, 30, kMSHundredths) == "00:00.00", "no minus zero");
        check(FormatTimecode(1.0 / 3, 30, kHMSF) == "00:00:00:10", "frame boundaries are exact");
    }
    {
        const auto drawn = [](long long frame, int fps) { return (double)(frame * 1000 / fps); };
        bool frames = true, nearly = true, countdown = true, layer = true;
        for (int fps : {24, 25, 30, 50, 60}) {
            for (long long f = 0; f < 200000; f += 7) {
                const double ms = drawn(f, fps), exact = FrameMs(ms, fps) / 1000;
                frames &= FormatTimecode(exact, fps, kFrames) == std::to_string(f);
                nearly &= FrameMs(ms + 0.04, fps) == FrameMs(ms, fps) && FrameMs(ms - 0.04, fps) == FrameMs(ms, fps);
                countdown &= FormatTimecode(600 - exact, fps, kFrames) == std::to_string(600LL * fps - f);
                layer &= FormatTimecode(exact - FrameMs(drawn(f / 3, fps), fps) / 1000, fps, kFrames) == std::to_string(f - f / 3);
            }
        }
        check(frames, "the timecode shows the frame being drawn, also between whole milliseconds");
        check(nearly, "a frame time read back from a float lands on the same frame");
        check(countdown, "a countdown shows every frame");
        check(layer, "layer time counts whole frames from a layer that starts on any frame");
    }
    {
        const auto runs = [](int fps, double rate, int first, int frames) {
            std::vector<int> out;
            double last = -1;
            for (int f = first; f < first + frames; f++) {
                const double held = am::HeldMs((double)(f * 1000LL / fps), (double)(first * 1000LL / fps), rate);
                if (out.empty() || held != last) out.push_back(0);
                out.back()++;
                last = held;
            }
            return out;
        };
        check(runs(30, 15, 0, 12) == std::vector<int>(6, 2), "Frame Rate 15 holds every frame twice at 30 fps");
        check(runs(30, 10, 0, 12) == std::vector<int>(4, 3), "Frame Rate 10 holds 3-3-3 at 30 fps");
        check(runs(24, 12, 0, 12) == std::vector<int>(6, 2), "Frame Rate 12 holds 2-2-2 at 24 fps");
        check(runs(60, 15, 0, 16) == std::vector<int>(4, 4), "a 60 fps export holds Frame Rate 15 for four frames");
        check(runs(30, 30, 0, 9) == std::vector<int>(9, 1), "Frame Rate equal to the project rate changes every frame");
        check(runs(30, 15, 1, 12) == std::vector<int>(6, 2), "a layer starting on frame 1 also holds 2-2-2");
        bool jitter = true;
        for (long long f = 0; f < 30000; f++) jitter &= (long long)std::floor(30 * am::LatestMs((double)(f * 1000 / 30)) / 1000) == f;
        check(jitter, "a 30 Hz random jitter moves on every frame at 30 fps");
    }
    {
        const std::string text = "Hi there\nyou";
        check(Scramble(text, 1, kFromStart, 1, 0, kLetters, "", true) == text, "settled text is the original");
        const std::string a = Scramble(text, 0, kFromStart, 1, 3, kNumbers, "", true);
        check(a.size() == text.size() && a[2] == ' ' && a[8] == '\n', "spaces and line breaks stay");
        bool digits = true;
        for (size_t i = 0; i < a.size(); i++) if (i != 2 && i != 8) digits &= a[i] >= '0' && a[i] <= '9';
        check(digits, "number set gives digits");
        check(a == Scramble(text, 0, kFromStart, 1, 3, kNumbers, "", true), "the same tick repeats");
        check(a != Scramble(text, 0, kFromStart, 1, 4, kNumbers, "", true), "the next tick changes");
        const std::string half = Scramble(text, 0.5f, kFromStart, 1, 3, kLetters, "", true);
        check(half.substr(0, 5) == "Hi th" && half.substr(9) != "you", "half way settles the first half");
        check(Scramble(text, 0, kFromStart, 1, 3, kLetters, "", false)[2] != ' ', "spaces scramble when asked");
        const std::string star = Scramble("ab", 0, kFromStart, 1, 0, kCustom, "★", true);
        check(star == "★★", "custom characters");
        check(Scramble(text, 0, kFromStart, 1, 0, kCustom, "x", true, 0) == text, "zero amount preserves the text");
        check(Scramble("abcd", 0, kFromStart, 1, 0, kCustom, "x", true, 1, 0.25f, 0.75f) == "axxd", "only the selected range scrambles");
        check(Scramble("ábcd", 0, kFromStart, 1, 0, kCustom, "x", true, 1, 0.25f, 0.75f) == "áxxd", "range counts graphemes instead of bytes");
        check(Scramble("abcd", 0, kFromStart, 1, 0, kCustom, "x", true, 1, 0.5f, 0.5f) == "abcd", "empty randomizer range leaves text alone");
        check(Scramble("é", 0, kFromStart, 1, 0, kCustom, "x", true) == "x", "a cluster scrambles as one character");
    }
    if (failures) return 1;
    printf("textanim: all checks passed\n");
    return 0;
}
