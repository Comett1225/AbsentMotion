#pragma once
#include <vector>
#include "layer.h"

class Warp : public ncnn::Layer {
public:
    Warp();
    int forward(const std::vector<ncnn::Mat>& input, std::vector<ncnn::Mat>& output, const ncnn::Option& opt) const override;
};
