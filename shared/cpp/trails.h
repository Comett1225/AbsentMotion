#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <functional>
#include <map>
#include <string>
#include <utility>
#include <vector>

#include "include/core/SkImage.h"

namespace am {

struct Trails {
    using Key = std::pair<std::string, int64_t>;
    using Shrink = std::function<sk_sp<SkImage>(const sk_sp<SkImage>&, int, int)>;
    struct Layer {
        std::vector<int64_t> tags;
        std::map<int64_t, sk_sp<SkImage>> frames;
    };

    size_t layerBudget = size_t(24) << 20;
    size_t budget = size_t(48) << 20;
    std::map<Key, Layer> layers;

    static size_t Bytes(const Layer& layer) {
        size_t bytes = 0;
        for (const auto& [tag, image] : layer.frames) bytes += (size_t)4 * image->width() * image->height();
        return bytes;
    }

    size_t bytes() const {
        size_t bytes = 0;
        for (const auto& [key, layer] : layers) bytes += Bytes(layer);
        return bytes;
    }

    bool keep(const Key& key, int64_t tag, const sk_sp<SkImage>& image, const Shrink& shrink) {
        auto found = layers.find(key);
        if (found == layers.end() || !image || image->width() < 1 || image->height() < 1) return false;
        Layer& layer = found->second;
        if (layer.frames.count(tag)) return true;
        const double full = 4.0 * image->width() * image->height() * (layer.tags.size() + 1);
        const double fit = std::min(1.0, std::sqrt(layerBudget / full));
        const int w = std::max(1, (int)(image->width() * fit)), h = std::max(1, (int)(image->height() * fit));
        const size_t need = (size_t)4 * w * h;
        if (Bytes(layer) + need > layerBudget) layer.frames.clear();
        if (this->bytes() + need > budget) return false;
        auto small = w == image->width() && h == image->height() ? image : shrink(image, w, h);
        if (!small) return false;
        layer.frames[tag] = std::move(small);
        return true;
    }

    std::vector<int64_t> plan(const Key& key, int64_t shown, const std::vector<int64_t>& tags, const sk_sp<SkImage>& current,
                              const Shrink& shrink) {
        if (tags.empty()) {
            layers.erase(key);
            return {};
        }
        Layer& layer = layers[key];
        layer.tags = tags;
        std::erase_if(layer.frames, [&](const auto& frame) {
            return frame.first != shown && std::find(tags.begin(), tags.end(), frame.first) == tags.end();
        });
        this->keep(key, shown, current, shrink);
        std::vector<int64_t> missing;
        for (int64_t tag : tags) {
            if (!layer.frames.count(tag) && std::find(missing.begin(), missing.end(), tag) == missing.end()) missing.push_back(tag);
        }
        return missing;
    }

    sk_sp<SkImage> at(const std::string& uri, int64_t layerId, int copy) const {
        auto found = layers.find({uri, layerId});
        if (found == layers.end()) return nullptr;
        const Layer& layer = found->second;
        if (copy < 1 || copy > (int)layer.tags.size() || layer.frames.empty()) return nullptr;
        const int64_t tag = layer.tags[copy - 1];
        auto after = layer.frames.lower_bound(tag);
        if (after == layer.frames.end()) return std::prev(after)->second;
        if (after->first == tag || after == layer.frames.begin()) return after->second;
        auto before = std::prev(after);
        return tag - before->first <= after->first - tag ? before->second : after->second;
    }

    void drop(const Key& key) { layers.erase(key); }
    void dropUri(const std::string& uri) {
        std::erase_if(layers, [&](const auto& layer) { return layer.first.first == uri; });
    }
    void clear() { layers.clear(); }
};

}
