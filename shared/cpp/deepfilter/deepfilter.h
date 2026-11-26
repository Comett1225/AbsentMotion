#pragma once

#include <cstddef>
#include <cstdint>
#include <memory>

namespace deepfilter {

constexpr int kHop = 480;
constexpr int kDelayFrames = 3;

struct Model;
struct State;

class Denoiser {
public:
    static std::unique_ptr<Denoiser> Load(const std::uint8_t* data, std::size_t size);
    ~Denoiser();
    void Process(const float* input, float* output);

private:
    Denoiser() = default;
    std::unique_ptr<Model> model_;
    std::unique_ptr<State> state_;
};

}
