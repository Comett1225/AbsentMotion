#include <cmath>
#include <cstdio>
#include <string>
#include <vector>
#include "scene.h"

namespace {
int checks = 0, failures = 0;
void Expect(float actual, float expected, const char* label) {
    checks++;
    if (!std::isfinite(actual) || std::fabs(actual - expected) > .001f) {
        failures++;
        std::fprintf(stderr, "%s: got %g, expected %g\n", label, actual, expected);
    }
}
std::string Link(int layer, const char* property, int component = 0, const char* extra = "") {
    return "<valueLink layer=\"" + std::to_string(layer) + "\" kind=\"transform\" property=\"" + property + "\" sourceComponent=\"" + std::to_string(component) + "\" " + extra + "/>";
}
am::Scene Parse(const std::string& layers) {
    am::Scene scene;
    std::string error;
    if (!am::ParseScene("<scene width=\"512\" height=\"512\" fps=\"30\">" + layers + "</scene>", &scene, &error)) {
        std::fprintf(stderr, "%s\n", error.c_str());
        failures++;
    }
    return scene;
}
std::string Layer(int id, const std::string& transform, const std::string& extra = "", const char* attrs = "") {
    return "<shape id=\"" + std::to_string(id) + "\" startTime=\"0\" endTime=\"2000\" " + attrs + "><transform>" + transform + "</transform>" + extra + "</shape>";
}
}

int main() {
    const std::string source = Layer(1, "<location><kf t=\"0\" v=\"10,20,30\"/><kf t=\"1\" v=\"110,220,330\"/></location>");
    auto scene = Parse(source + Layer(2, "<location value=\"7,8,9\">" + Link(1, "location", 0, "multiply=\"2\" add=\"3\" delay=\"0.5\"") + "</location>"));
    Expect(scene.layers[1].location.get(0, .5f, 0), 73, "multiply add delay");
    Expect(scene.layers[1].location.get(1, .5f, 0), 8, "unlinked component");
    Expect(scene.layers[1].location.get(0, 0, 0), 23, "delay clamps keys");
    Expect(scene.layers[1].location.steady(0, 1), 0, "links are animated");
    auto same = Parse(Layer(1, "<location value=\"3,17,0\">" + Link(1, "location", 1) + "</location><scale value=\"1,1\">" + Link(1, "rotation", 0, "multiply=\"0.1\"") + "</scale><rotation value=\"20\"/>"));
    Expect(same.layers[0].location.get(0, .5f, 0), 17, "same layer axes");
    Expect(same.layers[0].scale.get(0, .5f, 0), -2, "same layer properties");
    auto chain = Parse(source + Layer(2, "<rotation>" + Link(1, "location") + "</rotation>") + Layer(3, "<opacity>" + Link(2, "rotation", 0, "multiply=\"0.01\"") + "</opacity>"));
    Expect(chain.layers[2].opacity.get(0, .5f, 0), .6f, "link chain");
    auto cycle = Parse(Layer(1, "<rotation value=\"7\">" + Link(2, "rotation") + "</rotation>") + Layer(2, "<rotation value=\"9\">" + Link(1, "rotation") + "</rotation>"));
    Expect(cycle.layers[0].rotation.get(0, .5f, 0), 7, "cycle fallback");
    Expect(cycle.layers[1].rotation.get(0, .5f, 0), 9, "cycle fallback other");
    auto missing = Parse(Layer(1, "<rotation value=\"7\">" + Link(99, "rotation") + "</rotation>"));
    Expect(missing.layers[0].rotation.get(0, .5f, 0), 7, "missing source");
    auto parent = Parse(Layer(1, "<location value=\"100,200,0\"/><scale value=\"2,2\"/>") + Layer(2, "<location value=\"10,20,0\"/>", "", "parent=\"1\"") + Layer(3, "<rotation>" + Link(2, "location") + "</rotation>"));
    Expect(parent.layers[2].rotation.get(0, .5f, 0), -120, "parenting");
    auto parentSame = Parse(Layer(9, "<rotation value=\"10\"/><scale value=\"2,2\"/>") + Layer(1, "<rotation value=\"20\"/><scale value=\"1,1\">" + Link(1, "rotation", 0, "multiply=\"0.1\"") + "</scale>", "", "parent=\"9\""));
    Expect(parentSame.layers[1].scale.get(0, .5f, 0), -3, "same layer with parent");
    auto effect = Parse(Layer(1, "<rotation value=\"10\"/>", "<effect id=\"absent.spin\"><property name=\"rpm\" value=\"10\"/></effect>") + Layer(2, "<rotation>" + Link(1, "rotation") + "</rotation>"));
    Expect(effect.layers[1].rotation.get(0, .5f, 0), 70, "motion effects");
    auto param = Parse(source + Layer(2, "", "<effect id=\"absent.spin\"><property name=\"rpm\" value=\"2\">" + Link(1, "location", 0, "multiply=\"0.5\"") + "</property></effect>") + Layer(3, "<rotation><valueLink layer=\"2\" kind=\"effect\" property=\"rpm\" effect=\"0\"/></rotation>"));
    Expect(param.layers[1].effects[0].value("rpm", .5f, 0), 30, "effect target");
    Expect(param.layers[2].rotation.get(0, .5f, 0), -30, "effect source");
    float shown[3];
    Expect(am::PropertyNumbers(scene.layers[1], -1, "location", 1000, shown), 1, "number query");
    Expect(shown[0], 73, "query matches render");
    auto missingParam = Parse(Layer(1, "", "<effect id=\"absent.spin\"/>") + Layer(2, "<rotation><valueLink layer=\"1\" kind=\"effect\" effect=\"0\" property=\"rpm\" fallback=\"60\"/></rotation>"));
    Expect(missingParam.layers[1].rotation.get(0, .5f, 0), -60, "legacy effect fallback");
    auto crowded = Parse(Layer(1, "", "<effect id=\"absent.gridrepeat\"><property name=\"count\" value=\"150\"/><property name=\"copyDelay\" value=\"0.1\"/></effect>"));
    std::vector<am::RepeatCopy> capped;
    am::RepeatCopies(crowded.layers[0], 1000, {}, &capped);
    Expect(capped.size() <= 256, 1, "copy cap");
    for (const char* id : {"linearrepeat", "radialrepeat", "gridrepeat", "repeatalongpath"}) {
        const std::string head = "<effect id=\"absent." + std::string(id) + "\"><property name=\"count\" value=\"3\"/>";
        const std::string extra = "<property name=\"copyDelay\" value=\"0.2\"/><property name=\"randomPosition\" value=\"20,30\"/><property name=\"randomRotation\" value=\"45\"/><property name=\"randomScale\" value=\"0.3\"/><property name=\"randomOpacity\" value=\"0.6\"/>";
        auto repeated = Parse(Layer(1, "", head + extra + "</effect>"));
        am::RepeatSpace space;
        space.below = SkPath::Rect(SkRect::MakeWH(300, 300));
        std::vector<am::RepeatCopy> a, b;
        am::RepeatCopies(repeated.layers[0], 1000, space, &a);
        am::RepeatCopies(repeated.layers[0], 1000, space, &b);
        Expect(a.empty(), 0, id);
        for (size_t i = 0; i < a.size(); i++) {
            Expect(a[i].shiftMs, -(float)(a.size() - i - 1) * 200, "copy delay");
            Expect(a[i].local == b[i].local, 1, "deterministic transform");
            Expect(a[i].alpha, b[i].alpha, "deterministic opacity");
        }
    }
    std::printf("link_test: %d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
