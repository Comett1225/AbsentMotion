#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <random>
#include <vector>

#include "scene.h"
#include "effects.h"

namespace {

int failures = 0;

void Expect(bool ok, const char* what) {
    if (!ok) {
        std::fprintf(stderr, "FAIL %s\n", what);
        failures++;
    }
}

am::Keyframe Key(float t, float v, am::Easing::Type type = am::Easing::kLinear) {
    am::Keyframe k{t, {v, v * 2}, {}};
    k.easing.type = type;
    if (type == am::Easing::kCubicBezier) {
        k.easing.p[0] = 0.42f;
        k.easing.p[2] = 0.58f;
        k.easing.p[3] = 1;
    }
    return k;
}

bool Bitwise(float a, float b) { return std::memcmp(&a, &b, sizeof a) == 0; }

}

int main() {
#ifdef RENDER_BENCH_DEPTH3D
    am::Scene parsed;
    std::string error;
    Expect(am::ParseScene("<scene width=\"100\" height=\"100\"/>", &parsed, &error) && !parsed.depthOrder, "old scene depth default");
    Expect(am::ParseScene("<scene width=\"100\" height=\"100\" depthOrder=\"true\"/>", &parsed, &error) && parsed.depthOrder, "scene depth enabled");
    auto spatial = [](int id, float z) {
        am::Layer l;
        l.id = id;
        l.kind = "shape";
        l.endTime = 1000;
        l.location.value = {50, 50, z};
        return l;
    };
    am::Layer camera = spatial(9, -1000);
    camera.kind = "camera";
    am::Layer flatLayer = spatial(3, 0);
    am::Layer mask = spatial(5, 300);
    mask.clippingMask = true;
    std::vector<am::Layer> input = {spatial(1, 100), camera, spatial(2, 300), flatLayer, spatial(4, 100), mask, spatial(6, 300), spatial(7, 300)};
    std::vector<am::Layer> output;
    am::SeeLayers(input, 100, 100, true, 30, 0, true, &output);
    std::vector<int64_t> ids;
    for (const auto& l : output) ids.push_back(l.id);
    Expect(ids == std::vector<int64_t>({2, 9, 1, 3, 6, 7, 4, 5}), "depth groups, ties and mask");
    input[0].location.value[2] = 300;
    am::SeeLayers(input, 100, 100, true, 30, 0, true, &output);
    ids.clear();
    for (const auto& l : output) ids.push_back(l.id);
    Expect(ids[0] == 1 && ids[2] == 2, "depth ties keep order");
    am::Layer particles = spatial(8, 0);
    am::LayerEffect effect;
    effect.id = "absent.particles";
    am::Property depth;
    depth.name = "depth";
    depth.anim.value = {0};
    effect.props.push_back(depth);
    particles.effects.push_back(effect);
    input = {camera, spatial(1, 100), particles, spatial(2, 300)};
    am::SeeLayers(input, 100, 100, true, 30, 0, true, &output);
    Expect(output[1].id == 1 && output[2].id == 8 && output[3].id == 2, "flat particles split groups");
    input[2].effects[0].props[0].anim.value = {1};
    am::SeeLayers(input, 100, 100, true, 30, 0, true, &output);
    Expect(output[1].id == 2 && output[2].id == 1 && output[3].id == 8, "depth particles join group");
    particles.effects.clear();
    particles.force3d = true;
    input[2] = particles;
    am::SeeLayers(input, 100, 100, true, 30, 0, true, &output);
    Expect(output[1].id == 2 && output[2].id == 1 && output[3].id == 8, "flat explicit 3d layer joins group");
    am::Layer lamp = spatial(10, -200);
    lamp.kind = "light";
    lamp.lightType = "point";
    am::Property power;
    power.name = "intensity";
    power.anim.value = {2};
    am::Property falloff;
    falloff.name = "falloff";
    falloff.anim.value = {1};
    lamp.props = {power, falloff};
    auto lights = am::SceneLights({lamp, lamp, lamp, lamp, lamp}, 0);
    Expect(lights.count == 4, "lights capped at four");
    lights = am::SceneLights({lamp}, 0);
    const SkV3 nearLight = am::Illuminate(lights, {0,0,-1}, {50,50,0});
    const SkV3 farLight = am::Illuminate(lights, {0,0,-1}, {50,50,1000});
    Expect(nearLight.x > farLight.x && farLight.x > 0, "point falloff");
    lamp.lightType = "directional";
    lamp.tilt.value = {0,90};
    lights = am::SceneLights({lamp}, 0);
    const SkV3 turned = am::Illuminate(lights, {0,0,-1}, {0,0,0});
    Expect(turned.x < 0.001f, "directional rotation");
    lamp.lightType = "ambient";
    am::Property color;
    color.name = "color";
    color.anim.value = {1, 1, 0, 0};
    lamp.props.push_back(color);
    lights = am::SceneLights({lamp}, 0);
    const SkV3 red = am::Illuminate(lights, {0,0,-1}, {0,0,0});
    Expect(red.x == 2 && red.y == 0 && red.z == 0, "ambient colour and intensity");
    lamp.props[0].anim.value.clear();
    lamp.props[0].anim.keys = {{0, {0}, {}}, {1, {2}, {}}};
    lights = am::SceneLights({lamp}, 500);
    const SkV3 animated = am::Illuminate(lights, {0,0,-1}, {0,0,0});
    Expect(std::fabs(animated.x - 1) < 0.001f, "animated light intensity");
    lamp.hidden = true;
    Expect(am::SceneLights({lamp}, 0).count == 0, "hidden light ignored");
    {
        am::Layer tilted = camera;
        tilted.tilt.value = {20, 35};
        am::Layer near = spatial(11, 0);
        near.location.value = {60, 40, 0};
        am::Layer moved = near;
        moved.location.value = {63, 42, 0};
        const std::vector<am::Layer> before = {tilted, near}, after = {tilted, moved};
        std::vector<am::Layer> seenBefore, seenAfter;
        am::SeeLayers(before, 100, 100, true, 30, 0, true, &seenBefore);
        am::SeeLayers(after, 100, 100, true, 30, 0, true, &seenAfter);
        am::Placement from{}, to{};
        const auto find = [](const std::vector<am::Layer>& list) { return *std::find_if(list.begin(), list.end(), [](const am::Layer& l) { return l.id == 11; }); };
        const bool placed = am::SeenPlacement(find(seenBefore), 0, &from) && am::SeenPlacement(find(seenAfter), 0, &to);
        const SkVector want = to.location - from.location;
        const SkVector got = am::PlaneMatrix(before, near, 100, 100, 0, true).mapVector(3, 2);
        Expect(placed && want.length() > 0.1f && (got - want).length() < 0.02f * want.length(), "drag plane follows camera tilt");
    }
#endif
#ifdef RENDER_BENCH_PIECES
    {
        am::Scene sorted;
        std::string why;
        Expect(am::ParseScene("<scene width=\"100\" height=\"100\" depthOrder=\"true\"/>", &sorted, &why) && !sorted.pieceOrder, "old scene sorts by layer");
        Expect(am::ParseScene("<scene width=\"100\" height=\"100\" depthOrder=\"true\" depthSort=\"piece\"><shape id=\"1\" endTime=\"10\"/>"
                              "<shape id=\"2\" endTime=\"10\" mixPieces=\"false\"/></scene>", &sorted, &why) && sorted.pieceOrder,
               "scene sorts by piece");
        Expect(sorted.layers.size() == 2 && sorted.layers[0].mixPieces && !sorted.layers[1].mixPieces, "layer mixes unless turned off");
        auto at = [](int id, float z) {
            am::Layer l;
            l.id = id;
            l.kind = "shape";
            l.endTime = 1000;
            l.location.value = {50, 50, z};
            return l;
        };
        am::Layer eye = at(9, -1000);
        eye.kind = "camera";
        std::vector<am::Layer> list = {eye, at(1, 100), at(2, 300), at(3, 0), at(4, 100)}, out;
        am::SeeLayers(list, 100, 100, true, 30, 0, true, &out);
        std::vector<int> runs;
        for (const auto& l : out) runs.push_back(l.sight.run);
        Expect(out.size() == 5 && out[1].id == 2 && out[2].id == 1 && runs == std::vector<int>({0, 1, 1, 0, 2}), "2D layers split depth runs");
        Expect(std::fabs(out[1].sight.depth - 1300) < 0.01f && am::DepthPerPixel(out[1], 0) > 0, "seen depth is kept");
        Expect(!am::SeeLayers({at(1, 0), at(2, 0)}, 100, 100, true, 30, 0, true, &out, true), "flat layers stay on the flat path");
        am::Layer broken = at(5, 0);
        am::LayerEffect shards;
        shards.id = "absent.fracture3d";
        broken.effects.push_back(shards);
        Expect(!am::SeeLayers({at(1, 0), broken}, 100, 100, true, 30, 0, true, &out), "flat pieces stay flat by layer");
        Expect(am::SeeLayers({at(1, 0), broken}, 100, 100, true, 30, 0, true, &out, true) && out[0].sight.run == 0 && out[1].sight.run == 1,
               "flat pieces join a run by piece");
    }
#endif
#ifdef RENDER_BENCH_SPLIT
    {
        const auto cards = [](const char* extra, const char* place) {
            std::string cam = std::string("<camera id=\"9\" startTime=\"0\" endTime=\"1000\"><transform>") + place + "</transform></camera>";
            const char* card = "<shape id=\"%d\" startTime=\"0\" endTime=\"1000\" fillType=\"color\" s=\".rect\" threeD=\"true\"><transform><location value=\"160,160,0\"/>"
                               "<tilt value=\"0,%d\"/></transform><property name=\"size\" type=\"vec2\" value=\"70,55\"/></shape>";
            char a[400], b[400];
            std::snprintf(a, sizeof a, card, 1, 40);
            std::snprintf(b, sizeof b, card, 2, -40);
            return std::string("<scene width=\"320\" height=\"320\"") + extra + ">" + cam + a + b + "</scene>";
        };
        const char* ahead = "<location value=\"160,160,-277.13\"/>";
        const char* turned = "<location value=\"293.84,88.27,-231.82\"/><tilt value=\"15,30\"/>";
        const am::MediaPictures none = [](const std::string&, int64_t) { return sk_sp<SkImage>(); };
        am::Scene x;
        std::string why;
        for (const char* mode : {" depthOrder=\"true\"", " depthOrder=\"true\" depthSort=\"piece\""}) {
            Expect(am::ParseScene(cards(mode, ahead), &x, &why), "crossing scene parses");
            const int64_t left = am::TopLayerAt(x, 0, 130, 160, none), right = am::TopLayerAt(x, 0, 190, 160, none);
            Expect(left != right && (left == 1 || left == 2) && (right == 1 || right == 2), "each half of a crossing picks the card in front");
            Expect(am::TopLayerAt(x, 0, 159, 160, none) == left && am::TopLayerAt(x, 0, 161, 160, none) == right, "the pick changes at the crossing line");
            Expect(!am::WorldMatrix(x, x.layers[1], 0).isIdentity(), "a split layer keeps its matrix");
        }
        Expect(am::ParseScene(cards("", ahead), &x, &why), "flat crossing scene parses");
        Expect(am::TopLayerAt(x, 0, 130, 160, none) == 2 && am::TopLayerAt(x, 0, 190, 160, none) == 2, "without depth order the top card wins everywhere");
        Expect(am::ParseScene(cards(" depthOrder=\"true\"", turned), &x, &why), "turned crossing scene parses");
        std::vector<int64_t> picks;
        for (float px = 100; px <= 220; px += 4) picks.push_back(am::TopLayerAt(x, 0, px, 160, none));
        Expect(std::count(picks.begin(), picks.end(), 1) > 2 && std::count(picks.begin(), picks.end(), 2) > 2, "both cards stay pickable under a turned camera");
        std::vector<am::Layer> list = x.layers, seen;
        am::SeeLayers(list, 320, 320, true, 30, 0, true, &seen);
        am::SplitSeen(&seen, 0);
        Expect(seen.size() > list.size() && std::any_of(seen.begin(), seen.end(), [](const am::Layer& l) { return !l.sight.cuts.empty(); }), "crossing layers are split");
        list[1].location.value = {160, 160, std::nanf("")};
        am::SeeLayers(list, 320, 320, true, 30, 0, true, &seen);
        am::SplitSeen(&seen, 0);
        Expect(seen.size() <= list.size(), "a broken placement is not split");
    }
#endif

    am::Anim flat;
    flat.value = {3};
    Expect(flat.steady(-5, 5), "unkeyed");
    am::Anim hold;
    hold.keys = {Key(0, 0), Key(0.4f, 1, am::Easing::kCubicBezier), Key(1, 1, am::Easing::kCubicBezier)};
    Expect(hold.steady(0.4f, 0.9f), "hold after move");
    Expect(hold.steady(1, 3), "after last key");
    Expect(!hold.steady(0.39f, 0.5f), "window touches move");
    Expect(!hold.steady(0.2f, 0.3f), "inside move");
    Expect(!hold.steady(0.5f, 0.4f), "reversed window");
    am::Anim lead = hold;
    lead.hasLead = true;
    lead.lead = Key(-0.1f, 5);
    Expect(!lead.steady(-0.05f, -0.01f), "lead differs");
    lead.lead = Key(-0.1f, 0);
    Expect(!lead.steady(-0.05f, 0.1f), "lead equal but move follows");
    Expect(lead.steady(-0.2f, 0), "lead equal");
    am::Anim same;
    same.keys = {Key(0.2f, 1), Key(0.2f, 1), Key(0.6f, 1)};
    Expect(same.steady(0, 1), "duplicate key times");

    am::EffectRuntime effects;
    am::Layer layer;
    am::LayerEffect puppet;
    puppet.id = "absent.puppet";
    layer.effects.push_back(puppet);
    Expect(effects.steady(layer, 0, 1), "legacy puppet is steady");
    am::Property sway;
    sway.name = "sway";
    sway.anim.value = {1};
    layer.effects[0].props.push_back(sway);
    Expect(!effects.steady(layer, 0, 1), "sway puppet is not steady");
    layer.effects[0].props[0].anim.value = {0};
    Expect(effects.steady(layer, 0, 1), "disabled sway is steady");

    std::mt19937 rng(41);
    std::uniform_real_distribution<float> unit(-0.3f, 1.3f);
    const am::Easing::Type types[] = {am::Easing::kLinear, am::Easing::kCubicBezier, am::Easing::kBounce, am::Easing::kElastic,
                                      am::Easing::kCyclic, am::Easing::kStep, am::Easing::kElasticStep};
    int checked = 0, aligned = 0;
    for (int trial = 0; trial < 20000; trial++) {
        am::Anim a;
        const int n = (int)(rng() % 5);
        std::vector<float> times;
        for (int k = 0; k < n; k++) times.push_back(rng() % 4 == 0 && k ? times.back() : unit(rng));
        std::sort(times.begin(), times.end());
        for (int k = 0; k < n; k++) {
            am::Keyframe key = Key(times[k], (float)(rng() % 2), types[rng() % 7]);
            key.easing.p[0] = 0.25f;
            key.easing.p[1] = 0.5f;
            key.easing.count = 2;
            a.keys.push_back(key);
        }
        if (n && rng() % 3 == 0) {
            a.hasLead = true;
            a.lead = Key(times[0] - 0.05f, (float)(rng() % 2));
        }
        float from = unit(rng), to = unit(rng);
        if (n && rng() % 3 == 0) from = times[rng() % n];
        if (n && rng() % 3 == 0) to = times[rng() % n];
        if (from > to) std::swap(from, to);
        if (!a.steady(from, to)) continue;
        checked++;
        aligned += std::find(times.begin(), times.end(), from) != times.end() || std::find(times.begin(), times.end(), to) != times.end();
        for (int s = 0; s <= 64; s++) {
            const float t = s == 64 ? to : from + (to - from) * s / 64;
            for (int i = 0; i < 3; i++) Expect(Bitwise(a.get(i, t, 7), a.get(i, from, 7)), "property: steady window is constant");
        }
    }
    std::printf("steady_test: %d constant windows checked, %d on key times, %d failures\n", checked, aligned, failures);
    return failures ? 1 : 0;
}
