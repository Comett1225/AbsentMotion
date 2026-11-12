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
#include "include/core/SkSize.h"

namespace am {

struct Trails {
    using Key = std::pair<std::string, int64_t>;
    using Shrink = std::function<sk_sp<SkImage>(const sk_sp<SkImage>&, int, int)>;
    struct Layer {
        std::vector<int64_t> tags;
        std::map<int64_t, float> want;
        std::map<int64_t, sk_sp<SkImage>> frames;
        int width = 0, height = 0;
        float gain = 1;
        double fit = 1;
    };
    static constexpr float kSlack = 1.25f, kThin = 0.7f, kSpare = 2.0f;

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

    static SkISize Size(const Layer& layer, int64_t tag) {
        const auto found = layer.want.find(tag);
        const double scale = std::min(1.0, (found == layer.want.end() ? 1.0 : found->second) * layer.fit);
        return {std::max(1, (int)(layer.width * scale)), std::max(1, (int)(layer.height * scale))};
    }

    size_t share() const { return std::min(layerBudget, budget / std::max<size_t>(layers.size(), 1)); }

    void fit(Layer& layer, int width, int height) const {
        layer.width = width;
        layer.height = height;
        const double room = this->share() / (4.0 * std::max(width, 1) * std::max(height, 1));
        const auto cost = [&](double fit) {
            double sum = 0;
            for (const auto& [tag, want] : layer.want) sum += std::pow(std::min(1.0, want * fit), 2);
            return sum;
        };
        double low = 0, high = kSpare;
        if (cost(high) > room) {
            for (int i = 0; i < 24; i++) (cost((low + high) / 2) > room ? high : low) = (low + high) / 2;
            high = low;
        }
        layer.fit = high;
    }

    static sk_sp<SkImage> Reduce(sk_sp<SkImage> image, int width, int height, const Shrink& shrink) {
        while (image && image->width() >= width * 2 && image->height() >= height * 2 && image->width() > 1 && image->height() > 1) {
            image = shrink(image, image->width() / 2, image->height() / 2);
        }
        return !image || (image->width() == width && image->height() == height) ? image : shrink(image, width, height);
    }

    static void Tidy(Layer& layer, const Shrink& shrink, float slack) {
        for (auto& [tag, image] : layer.frames) {
            const SkISize to = Size(layer, tag);
            if (image->width() <= to.width() * slack && image->height() <= to.height() * slack) continue;
            if (auto small = Reduce(image, std::min(to.width(), image->width()), std::min(to.height(), image->height()), shrink)) image = std::move(small);
        }
    }

    static bool Thin(const Layer& layer, int64_t tag) {
        const auto found = layer.frames.find(tag);
        return found == layer.frames.end() || found->second->width() < Size(layer, tag).width() * kThin;
    }

    bool keep(const Key& key, int64_t tag, const sk_sp<SkImage>& image, const Shrink& shrink) {
        auto found = layers.find(key);
        if (found == layers.end() || !image || image->width() < 1 || image->height() < 1) return false;
        Layer& layer = found->second;
        if (image->width() != layer.width || image->height() != layer.height) this->fit(layer, image->width(), image->height());
        if (!Thin(layer, tag)) return true;
        layer.frames.erase(tag);
        const SkISize to = Size(layer, tag);
        const size_t need = (size_t)4 * to.width() * to.height();
        const size_t room = this->share();
        if (Bytes(layer) + need > room) Tidy(layer, shrink, 1);
        if (Bytes(layer) + need > room) layer.frames.clear();
        if (this->bytes() + need > budget) {
            for (auto& [other, held] : layers) {
                if (&held == &layer || held.width < 1 || held.height < 1) continue;
                this->fit(held, held.width, held.height);
                Tidy(held, shrink, 1);
            }
            if (this->bytes() + need > budget) return false;
        }
        auto small = Reduce(image, to.width(), to.height(), shrink);
        if (!small) return false;
        layer.frames[tag] = std::move(small);
        return true;
    }

    std::vector<int64_t> plan(const Key& key, int64_t shown, const std::vector<int64_t>& tags, float step, const sk_sp<SkImage>& current,
                              const Shrink& shrink) {
        if (tags.empty()) {
            layers.erase(key);
            return {};
        }
        Layer& layer = layers[key];
        layer.tags = tags;
        layer.want.clear();
        const auto need = [&](int copy) { return layer.gain * std::pow(std::max(step, 0.01f), (float)copy); };
        for (size_t i = 0; i < tags.size(); i++) layer.want[tags[i]] = std::max(layer.want[tags[i]], need((int)i + 1));
        layer.want[shown] = std::max(layer.want[shown], need(1));
        this->fit(layer, current ? current->width() : layer.width, current ? current->height() : layer.height);
        std::erase_if(layer.frames, [&](const auto& frame) { return !layer.want.count(frame.first); });
        Tidy(layer, shrink, kSlack);
        this->keep(key, shown, current, shrink);
        std::vector<int64_t> missing;
        for (int64_t tag : tags) {
            if (Thin(layer, tag) && std::find(missing.begin(), missing.end(), tag) == missing.end()) missing.push_back(tag);
        }
        return missing;
    }

    sk_sp<SkImage> at(const std::string& uri, int64_t layerId, int copy, float gain) {
        auto found = layers.find({uri, layerId});
        if (found == layers.end()) return nullptr;
        Layer& layer = found->second;
        layer.gain = std::clamp(gain, 1.0f, 8.0f);
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
