#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace am::textanim {

enum Shape { kSquare, kRampUp, kRampDown, kTriangle, kRound, kSmooth };
enum Direction { kFromStart, kFromEnd, kFromCentre, kRandom };
enum Charset { kLetters, kUpper, kLower, kNumbers, kLettersNumbers, kSymbols, kCustom };
enum Timecode { kHMSF, kHMS, kMS, kMSHundredths, kSHundredths, kFrames };

struct Wiggle {
    float x = 0, y = 0, angle = 0;
};

struct Segments {
    std::vector<int> character, word;
    int characters = 0, words = 0;
};

uint64_t Mix(uint64_t x);
std::vector<uint32_t> CodePoints(const std::string& utf8, std::vector<int>* starts = nullptr);
std::string Utf8(uint32_t c);
bool Space(uint32_t c);
bool Spaceless(uint32_t c);
Segments Split(const std::string& utf8, const std::vector<int>& wordBreaks = {});
std::vector<int> Ranks(int count, int direction, uint64_t seed, int* steps);
float Reveal(float progress, int rank, int steps, float softness);
float RangeWeight(int shape, int index, int count, float start, float end);
std::string CountNumbers(const std::string& text, double add, double multiply, int decimals, int separator);
double FrameMs(double ms, int fps);
std::string FormatTimecode(double seconds, double fps, int format);
std::string Scramble(const std::string& text, float progress, int direction, uint64_t seed, int64_t tick, int charset,
                     const std::string& custom, bool keepSpaces, float amount = 1, float start = 0, float end = 1);

std::vector<float> TypeTimes(const std::string& text, float speed, float pause);
std::vector<float> BlipTimes(const std::string& text, float speed, float pause, int every);
bool Picked(int index, uint64_t seed, float seconds, float fraction, float every);
Wiggle Shake(int style, int index, uint64_t seed, float seconds, float speed, float cycles, float spread, float amount, float turn);

}
