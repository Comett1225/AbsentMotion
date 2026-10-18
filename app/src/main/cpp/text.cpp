#include "text.h"
#include <algorithm>
#include <cstdlib>
#include "include/core/SkCanvas.h"
#include "include/core/SkColorFilter.h"
#include "include/core/SkColorSpace.h"
#include "include/core/SkFont.h"
#include "include/core/SkFontMetrics.h"
#include "include/core/SkTypeface.h"
#include "include/core/SkPathBuilder.h"
#ifdef __APPLE__
#include "include/ports/SkFontMgr_mac_ct.h"
#else
#include "include/ports/SkFontMgr_android.h"
#endif
#include "include/ports/SkFontScanner_FreeType.h"
#include "modules/skparagraph/include/ParagraphBuilder.h"
#include "modules/skparagraph/include/TypefaceFontProvider.h"
#include "modules/skunicode/include/SkUnicode_icu.h"

namespace am {
using namespace skia::textlayout;
struct TextLine {
    std::string content;
    float baseline = 0, wordSpacing = 0;
    bool rtl = false;
    std::unique_ptr<Paragraph> paragraph;
};
struct TextLayout {
    std::string content, family, path, align;
    float size = 54, width = 512, height = 0, padTop = 0, letterSpacing = 0, lineSpacing = 1;
    int weight = 400, index = 0;
    bool italic = false, imported = false;
    sk_sp<FontCollection> fonts;
    std::vector<TextLine> lines;
    std::unique_ptr<Paragraph> paragraph;
};
namespace {
std::string attr(const XmlNode& n, const char* key, const char* fallback = "") {
    auto s = n.attr(key); return s ? s : fallback;
}
float number(const XmlNode& n, const char* key, float fallback) {
    auto s = n.attr(key); return s ? strtof(s, nullptr) : fallback;
}
sk_sp<SkFontMgr> systemFonts() {
#ifdef __APPLE__
    static auto manager = SkFontMgr_New_CoreText(nullptr);
#else
    static auto manager = SkFontMgr_New_Android(nullptr, SkFontScanner_Make_FreeType());
#endif
    return manager;
}
std::unique_ptr<Paragraph> paragraph(TextLayout& text, const std::string& content,
                                     bool rtl, float wordSpacing, bool prepared) {
    TextStyle style;
    style.setColor(SK_ColorWHITE);
    style.setFontSize(text.size);
    style.setFontStyle(SkFontStyle(text.weight, SkFontStyle::kNormal_Width,
        text.italic ? SkFontStyle::kItalic_Slant : SkFontStyle::kUpright_Slant));
    style.setFontFamilies({SkString(text.family)});
    style.setLetterSpacing(text.letterSpacing * text.size);
    style.setWordSpacing(wordSpacing);
    style.setHeight(text.lineSpacing);
    ParagraphStyle settings;
    settings.setTextStyle(style);
    settings.setTextDirection(rtl ? TextDirection::kRtl : TextDirection::kLtr);
    settings.setTextAlign(prepared ? TextAlign::kLeft : text.align == "center" ? TextAlign::kCenter : text.align == "right" ? TextAlign::kEnd :
        text.align == "justify" && !prepared ? TextAlign::kJustify : TextAlign::kStart);
    auto builder = ParagraphBuilder::make(settings, text.fonts, SkUnicodes::ICU::Make());
    builder->addText(content.c_str(), content.size());
    auto result = builder->Build();
    result->layout(prepared ? std::max(text.width, text.size * content.size() * 2) : text.width);
    return result;
}
void layout(TextLayout& text) {
    if (text.fonts) return;
    auto manager = systemFonts();
    text.fonts = sk_make_sp<FontCollection>();
    text.fonts->setDefaultFontManager(manager,
#ifdef __APPLE__
        "Helvetica"
#else
        "sans-serif"
#endif
    );
    sk_sp<SkTypeface> face;
    if (!text.path.empty()) face = manager->makeFromFile(text.path.c_str(), text.index);
    if (face && !text.imported) {
        const int count = face->getVariationDesignParameters({});
        if (count > 0 && count <= 64) {
            std::vector<SkFontParameters::Variation::Axis> axes(count);
            face->getVariationDesignParameters(axes);
            std::vector<SkFontArguments::VariationPosition::Coordinate> coordinates;
            for (const auto& axis : axes) {
                const float value = axis.tag == SkSetFourByteTag('w', 'g', 'h', 't') ? text.weight : axis.def;
                coordinates.push_back({axis.tag, std::clamp(value, axis.min, axis.max)});
            }
            SkFontArguments arguments;
            arguments.setVariationDesignPosition({coordinates.data(), (int)coordinates.size()});
            if (auto instance = face->makeClone(arguments)) face = instance;
        }
    }
    if (face) {
        auto provider = sk_make_sp<TypefaceFontProvider>();
        text.family = "light-motion-text";
        provider->registerTypeface(face, SkString(text.family));
        text.fonts->setAssetFontManager(provider);
    }
    if (!text.lines.empty()) {
        for (auto& line : text.lines) line.paragraph = paragraph(text, line.content, line.rtl, line.wordSpacing, true);
    } else {
        text.paragraph = paragraph(text, text.content, false, 0, false);
        if (!face) face = manager->matchFamilyStyle(text.family.c_str(), SkFontStyle(text.weight, 5,
            text.italic ? SkFontStyle::kItalic_Slant : SkFontStyle::kUpright_Slant));
        SkFont font(face, text.size);
        SkFontMetrics metrics;
        font.getMetrics(&metrics);
        text.padTop = std::max(0.f, metrics.fAscent - metrics.fTop);
        text.height = text.paragraph->getHeight() + text.padTop + std::max(0.f, metrics.fBottom - metrics.fDescent);
    }
}
}
std::shared_ptr<TextLayout> ParseText(const XmlNode& n) {
    auto text = std::make_shared<TextLayout>();
    if (auto content = n.child("content")) text->content = content->text;
    text->size = std::max(.03f, number(n, "size", 18) * 3);
    text->width = std::max(1.f, number(n, "wrapWidth", 512));
    text->align = attr(n, "align", "left");
    text->path = attr(n, "_fontPath");
    text->family = attr(n, "_fontFamily", "sans-serif");
    text->weight = number(n, "_fontWeight", 400);
    text->index = number(n, "_fontIndex", 0);
    text->italic = attr(n, "_fontItalic") == "true";
    text->imported = attr(n, "font").starts_with("imported?");
    for (const auto& child : n.children) {
        if (child->name != "effect" || attr(*child, "id") != "com.alightcreative.effects.textspacing" || attr(*child, "hidden") == "true") continue;
        for (const auto& p : child->children) {
            if (attr(*p, "name") == "letterspacing") text->letterSpacing = number(*p, "value", 0);
            if (attr(*p, "name") == "linespacing") text->lineSpacing = number(*p, "value", 1);
        }
        break;
    }
    text->letterSpacing = number(n, "_letterSpacing", text->letterSpacing);
    text->lineSpacing = number(n, "_lineSpacing", text->lineSpacing);
    if (auto prepared = n.child("_layout")) {
        text->height = number(*prepared, "height", text->size);
        for (const auto& line : prepared->children) {
            TextLine l;
            l.content = line->text;
            l.baseline = number(*line, "baseline", text->size);
            l.rtl = attr(*line, "rtl") == "true";
            l.wordSpacing = number(*line, "wordSpacing", 0);
            text->lines.push_back(std::move(l));
        }
    }
    return text;
}
SkPath TextPath(const Layer& layer, float) {
    auto& text = *layer.text;
    layout(text);
    return SkPath::Rect(SkRect::MakeXYWH(-text.width / 2, -text.height / 2, text.width, text.height));
}
SkPath TextGlyphPath(const Layer& layer) {
    auto& text = *layer.text;
    layout(text);
    SkPathBuilder b;
    auto add = [&](Paragraph& paragraph, float x, float y) {
        paragraph.visit([&](int, const Paragraph::VisitorInfo* info) {
            if (!info) return;
            for (int i = 0; i < info->count; i++) {
                if (auto glyph = info->font.getPath(info->glyphs[i])) {
                    b.addPath(*glyph, SkMatrix::Translate(x + info->origin.fX + info->positions[i].fX, y + info->origin.fY + info->positions[i].fY));
                }
            }
        });
    };
    if (text.paragraph) add(*text.paragraph, -text.width / 2, -text.height / 2 + text.padTop);
    for (auto& line : text.lines) {
        float x = -text.width / 2;
        const float runWidth = line.paragraph->getMaxIntrinsicWidth();
        if (text.align == "center") x += (text.width - runWidth) / 2;
        else if (text.align == "right") x += text.width - runWidth;
        add(*line.paragraph, x, line.baseline - text.height / 2 - line.paragraph->getAlphabeticBaseline());
    }
    return b.detach();
}
void DrawText(SkCanvas* canvas, const Layer& layer, float, const SkPaint& paint) {
    auto& text = *layer.text;
    layout(text);
    SkPaint tint;
    tint.setColorFilter(SkColorFilters::Blend(paint.getColor4f(), nullptr, SkBlendMode::kSrcIn));
    canvas->saveLayer(nullptr, &tint);
    if (text.paragraph) text.paragraph->paint(canvas, -text.width / 2, -text.height / 2 + text.padTop);
    for (auto& line : text.lines) {
        float x = -text.width / 2;
        const float runWidth = line.paragraph->getMaxIntrinsicWidth();
        if (text.align == "center") x += (text.width - runWidth) / 2;
        else if (text.align == "right") x += text.width - runWidth;
        line.paragraph->paint(canvas, x, line.baseline - text.height / 2 - line.paragraph->getAlphabeticBaseline());
    }
    canvas->restore();
}
}
