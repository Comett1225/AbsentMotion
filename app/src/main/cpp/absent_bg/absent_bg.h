#pragma once
#include <cstdint>
#include <memory>
#include <string>
#include <vector>
namespace absent {
struct Tensor {
    int c=0,h=0,w=0;
    std::vector<float> data;
    Tensor()=default;
    Tensor(int channels,int height,int width);
};
class Model {
public:
    explicit Model(const std::string& path);
    ~Model();
    Model(Model&&) noexcept;
    Model& operator=(Model&&) noexcept;
    int input_size() const;
    Tensor forward(const Tensor& input) const;
    std::vector<uint8_t> matte(const uint8_t* rgb,int width,int height) const;
private:
    struct Impl;
    std::unique_ptr<Impl> impl;
};
}
