#ifndef FACES_YUNET_H
#define FACES_YUNET_H

#include <cstddef>
#include <cstdint>
#include <memory>
#include <vector>

namespace faces {

struct Face {
    float x = 0, y = 0, width = 0, height = 0, score = 0;
};

class Detector {
public:
    Detector(const std::uint8_t* onnx, std::size_t size);
    ~Detector();
    Detector(const Detector&) = delete;
    Detector& operator=(const Detector&) = delete;

    std::vector<Face> detect(const std::uint8_t* rgb, int width, int height, float threshold) const;

private:
    struct Graph;
    std::unique_ptr<Graph> graph_;
};

}

#endif
