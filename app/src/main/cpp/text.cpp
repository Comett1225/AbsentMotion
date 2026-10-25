#include "text.h"
#include <algorithm>
#include <bit>
#include <cmath>
#include <cstdlib>
#include <map>
#include <mutex>
#include <string_view>
#include "textanim.h"
#include "include/core/SkCanvas.h"
#include "include/core/SkColorFilter.h"
#include "include/core/SkColorSpace.h"
#include "include/core/SkFont.h"
#include "include/core/SkFontMetrics.h"
#include "include/core/SkBlurTypes.h"
#include "include/core/SkMaskFilter.h"
#include "include/core/SkTypeface.h"
#include "include/core/SkPathBuilder.h"
#include "include/effects/SkImageFilters.h"
#ifdef __APPLE__
#include "include/ports/SkFontMgr_mac_ct.h"
#else
#include "include/ports/SkFontMgr_android.h"
#endif
#include "include/ports/SkFontScanner_FreeType.h"
#include "modules/skparagraph/include/ParagraphBuilder.h"
#include "modules/skparagraph/include/TypefaceFontProvider.h"
#include "modules/skunicode/include/SkUnicode_icu.h"
#include "unicode/uchar.h"

namespace am {
using namespace skia::textlayout;
struct TextLine {
    std::string content;
    float baseline = 0, wordSpacing = 0;
    bool rtl = false;
    std::unique_ptr<Paragraph> paragraph;
};
struct TextLayout {
    std::string content, family, path, align, emoji, flags;
    float size = 54, width = 512, height = 0, padTop = 0, letterSpacing = 0, lineSpacing = 1, lineHeight = 0;
    int weight = 400, index = 0;
    bool italic = false, imported = false;
    sk_sp<FontCollection> fonts;
    std::vector<TextLine> lines;
    std::unique_ptr<Paragraph> paragraph;
    bool inked = false;
    SkRect ink = SkRect::MakeEmpty();
    std::map<std::string, std::unique_ptr<Paragraph>> shown;
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
const char kEmojiFamily[] = "absent-emoji";
const char kFlagFamily[] = "absent-emoji-flags";
const char kEmojiLocale[] = "und-Zsye";
sk_sp<SkTypeface> EmojiFace(const std::string& path) {
    static std::mutex lock;
    static std::map<std::string, sk_sp<SkTypeface>> faces;
    std::lock_guard<std::mutex> hold(lock);
    if (auto found = faces.find(path); found != faces.end()) return found->second;
    const char* locale[] = {kEmojiLocale};
    sk_sp<SkTypeface> face = path.empty() ? systemFonts()->matchFamilyStyleCharacter(nullptr, SkFontStyle(), locale, 1, 0x1F600)
                                          : systemFonts()->makeFromFile(path.c_str());
    if (face) faces[path] = face;
    return face;
}
class EmojiFonts : public TypefaceFontProvider {
public:
    explicit EmojiFonts(std::vector<sk_sp<SkTypeface>> faces) : fFaces(std::move(faces)) {}
    sk_sp<SkTypeface> onMatchFamilyStyleCharacter(const char[], const SkFontStyle&, const char* bcp47[], int count,
                                                  SkUnichar character) const override {
        if (!std::any_of(bcp47, bcp47 + count, [](const char* tag) { return tag && std::string_view(tag) == kEmojiLocale; })) return nullptr;
        for (const auto& face : fFaces) {
            if (face && face->unicharToGlyph(character)) return face;
        }
        return nullptr;
    }
private:
    std::vector<sk_sp<SkTypeface>> fFaces;
};
std::vector<std::pair<size_t, size_t>> EmojiRuns(SkUnicode* unicode, const std::string& content) {
    std::vector<std::pair<size_t, size_t>> runs;
    if (!unicode) return runs;
    std::vector<int> starts;
    const std::vector<uint32_t> points = textanim::CodePoints(content, &starts);
    auto at = [&](size_t i) { return i < points.size() ? (SkUnichar)points[i] : -1; };
    auto keycap = [](SkUnichar c) { return c == '#' || c == '*' || (c >= '0' && c <= '9'); };
    for (size_t i = 0; i < points.size(); i++) {
        const SkUnichar c = at(i), next = at(i + 1);
        const bool flag = unicode->isRegionalIndicator(c) && unicode->isRegionalIndicator(next);
        const bool emoji = flag || (keycap(c) ? next == 0x20E3 || (next == 0xFE0F && at(i + 2) == 0x20E3)
                                              : unicode->isEmoji(c) && !unicode->isEmojiComponent(c) &&
                                                (c >= 0x1F000 || next == 0xFE0F || (next != 0xFE0E && u_hasBinaryProperty(c, UCHAR_EMOJI_PRESENTATION))));
        if (!emoji) continue;
        size_t end = i + (flag ? 2 : 1);
        while (end < points.size()) {
            const SkUnichar d = at(end);
            if (d == 0xFE0F || d == 0x20E3 || unicode->isEmojiModifier(d) || (d >= 0xE0020 && d <= 0xE007F)) end++;
            else if (d == 0x200D && end + 1 < points.size()) end += 2;
            else break;
        }
        const size_t from = starts[i], to = end < points.size() ? starts[end] : content.size();
        if (!runs.empty() && runs.back().second == from) runs.back().second = to;
        else runs.push_back({from, to});
        i = end - 1;
    }
    return runs;
}
std::unique_ptr<Paragraph> paragraph(TextLayout& text, const std::string& content,
                                     bool rtl, float wordSpacing, bool prepared, float height = 0, bool tabular = false) {
    TextStyle style;
    style.setColor(SK_ColorWHITE);
    style.setFontSize(text.size);
    style.setFontStyle(SkFontStyle(text.weight, SkFontStyle::kNormal_Width,
        text.italic ? SkFontStyle::kItalic_Slant : SkFontStyle::kUpright_Slant));
    style.setFontFamilies({SkString(text.family)});
    style.setLetterSpacing(text.letterSpacing * text.size);
    style.setWordSpacing(wordSpacing);
    if (tabular) style.addFontFeature(SkString("tnum"), 1);
    if (height > 0) {
        style.setHeight(height);
        style.setHeightOverride(true);
    }
    ParagraphStyle settings;
    settings.setTextStyle(style);
    settings.setTextDirection(rtl ? TextDirection::kRtl : TextDirection::kLtr);
    settings.setTextAlign(prepared ? TextAlign::kLeft : text.align == "center" ? TextAlign::kCenter : text.align == "right" ? TextAlign::kEnd :
        text.align == "justify" && !prepared ? TextAlign::kJustify : TextAlign::kStart);
    const sk_sp<SkUnicode> unicode = SkUnicodes::ICU::Make();
    auto builder = ParagraphBuilder::make(settings, text.fonts, unicode);
    TextStyle emoji = style;
    emoji.setFontFamilies({SkString(kEmojiFamily), SkString(kFlagFamily)});
    size_t done = 0;
    for (const auto& [from, to] : EmojiRuns(unicode.get(), content)) {
        builder->addText(content.data() + done, from - done);
        builder->pushStyle(emoji);
        builder->addText(content.data() + from, to - from);
        builder->pop();
        done = to;
    }
    builder->addText(content.data() + done, content.size() - done);
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
    const sk_sp<SkTypeface> emoji = EmojiFace(text.emoji), flags = text.flags.empty() ? nullptr : EmojiFace(text.flags);
    auto provider = sk_make_sp<EmojiFonts>(std::vector<sk_sp<SkTypeface>>{emoji, flags});
    if (face) {
        text.family = "light-motion-text";
        provider->registerTypeface(face, SkString(text.family));
    }
    if (emoji) provider->registerTypeface(emoji, SkString(kEmojiFamily));
    if (flags) provider->registerTypeface(flags, SkString(kFlagFamily));
    text.fonts->setAssetFontManager(provider);
    if (!text.lines.empty()) {
        for (auto& line : text.lines) line.paragraph = paragraph(text, line.content, line.rtl, line.wordSpacing, true);
    } else {
        if (!face) face = manager->matchFamilyStyle(text.family.c_str(), SkFontStyle(text.weight, 5,
            text.italic ? SkFontStyle::kItalic_Slant : SkFontStyle::kUpright_Slant));
        SkFont font(face, text.size);
        SkFontMetrics metrics;
        font.getMetrics(&metrics);
        const float natural = metrics.fDescent - metrics.fAscent + metrics.fLeading;
        text.lineHeight = text.lineSpacing != 1 && natural > 0 ? text.lineSpacing * natural / text.size : 0;
        text.paragraph = paragraph(text, text.content, false, 0, false, text.lineHeight);
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
    text->emoji = attr(n, "_emoji");
    text->flags = attr(n, "_emojiFlags");
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
namespace {
template <typename F> void EachParagraph(TextLayout& text, F&& at) {
    if (text.paragraph) at(*text.paragraph, -text.width / 2, -text.height / 2 + text.padTop);
    for (auto& line : text.lines) {
        float x = -text.width / 2;
        const float runWidth = line.paragraph->getMaxIntrinsicWidth();
        if (text.align == "center") x += (text.width - runWidth) / 2;
        else if (text.align == "right") x += text.width - runWidth;
        at(*line.paragraph, x, line.baseline - text.height / 2 - line.paragraph->getAlphabeticBaseline());
    }
}
template <typename F> void EachGlyph(TextLayout& text, F&& at) {
    EachParagraph(text, [&](Paragraph& paragraph, float x, float y) {
        paragraph.visit([&](int, const Paragraph::VisitorInfo* info) {
            if (!info) return;
            for (int i = 0; i < info->count; i++) {
                at(info->font, info->glyphs[i], x + info->origin.fX + info->positions[i].fX, y + info->origin.fY + info->positions[i].fY);
            }
        });
    });
}
bool Colored(const SkFont& font) {
    SkTypeface* face = font.getTypeface();
    if (!face) return false;
    thread_local std::map<SkTypefaceID, bool> known;
    if (known.size() > 512) known.clear();
    auto [entry, fresh] = known.try_emplace(face->uniqueID(), false);
    if (fresh) {
        for (const char* tag : {"COLR", "CBDT", "sbix", "SVG "}) {
            entry->second = entry->second || face->getTableSize(SkSetFourByteTag(tag[0], tag[1], tag[2], tag[3])) > 0;
        }
    }
    return entry->second;
}
void DrawRuns(SkCanvas* canvas, TextLayout& text, bool colored, const SkPaint& paint) {
    EachParagraph(text, [&](Paragraph& paragraph, float x, float y) {
        paragraph.visit([&](int, const Paragraph::VisitorInfo* info) {
            if (!info || Colored(info->font) != colored) return;
            canvas->drawGlyphs({info->glyphs, (size_t)info->count}, {info->positions, (size_t)info->count},
                               {x + info->origin.fX, y + info->origin.fY}, info->font, paint);
        });
    });
}
const SkRect& InkBounds(TextLayout& text) {
    if (text.inked) return text.ink;
    text.inked = true;
    text.ink = SkRect::MakeXYWH(-text.width / 2, -text.height / 2, text.width, text.height);
    EachGlyph(text, [&](const SkFont& font, SkGlyphID glyph, float x, float y) {
        SkRect bounds;
        font.getBounds({&glyph, 1}, {&bounds, 1}, nullptr);
        text.ink.join(bounds.makeOffset(x, y));
    });
    return text.ink;
}
}
namespace {
const char* const kTextIds[] = {"absent.textprogress", "absent.texttransform", "absent.textrandomizer", "absent.countupdown",
                                "absent.timecode"};

struct Piece {
    Paragraph* paragraph;
    float x, y;
    int base, line;
};

struct Glyph {
    SkFont font;
    SkGlyphID id;
    SkPoint at;
    SkRect bounds;
    int offset, line;
    SkMatrix matrix = SkMatrix::I();
    float alpha = 1, blur = 0;
    SkColor4f tint = {0, 0, 0, 0};
};

int Choice(const LayerEffect& e, const char* name, float t, int fallback, int last) {
    return std::clamp((int)std::lround(e.value(name, t, (float)fallback)), 0, last);
}

uint64_t Seed(const Layer& l, const LayerEffect& e, float t) {
    return textanim::Mix((uint64_t)l.id ^ textanim::Mix((uint64_t)std::llround((double)e.value("seed", t, 1) * 1000)));
}

std::string Shown(const Layer& l, float t, int fps, std::string text, bool* tabular) {
    const double begin = textanim::FrameMs(l.startTime, fps);
    const double now = textanim::FrameMs(l.startTime + (double)t * std::max(0, l.endTime - l.startTime), fps);
    const double local = (now - (std::floor(begin) == l.startTime ? begin : l.startTime)) / 1000.0;
    for (const LayerEffect& e : l.effects) {
        if (e.hidden) continue;
        if (e.id == "absent.countupdown") {
            text = textanim::CountNumbers(text, e.value("add", t, 0), e.value("multiply", t, 1), Choice(e, "decimals", t, 0, 4) - 1,
                                          Choice(e, "separator", t, 0, 3));
            *tabular = true;
        } else if (e.id == "absent.timecode") {
            const double clock = Choice(e, "source", t, 0, 1) == 1 ? now / 1000.0 : local;
            text = textanim::FormatTimecode(clock * e.value("speed", t, 1) + e.value("offset", t, 0), fps, Choice(e, "format", t, 0, 5));
            *tabular = true;
        } else if (e.id == "absent.textrandomizer") {
            const Property* custom = e.prop("characters");
            const int64_t tick = (int64_t)std::floor(local * std::max(0.0f, e.value("speed", t, 12)) + 1e-6);
            const float evolution = e.value("evolution", t, 0);
            const uint64_t seed = Seed(l, e, t) ^ (evolution == 0 ? 0 : textanim::Mix(std::bit_cast<uint32_t>(evolution)));
            text = textanim::Scramble(text, std::clamp(e.value("progress", t, 0), 0.0f, 1.0f), Choice(e, "direction", t, 0, 3),
                                      seed, tick, Choice(e, "charset", t, 0, 6), custom ? custom->text : "",
                                      e.value("keepSpaces", t, 1) >= 0.5f, std::clamp(e.value("amount", t, 1), 0.0f, 1.0f),
                                      e.value("start", t, 0), e.value("end", t, 1));
        }
    }
    return text;
}

Paragraph* Laid(TextLayout& text, int index, const std::string& content, bool tabular) {
    Paragraph* plain = text.lines.empty() ? text.paragraph.get() : text.lines[index].paragraph.get();
    if (!tabular && content == (text.lines.empty() ? text.content : text.lines[index].content)) return plain;
    auto& slot = text.shown[std::to_string(index) + (tabular ? "t" : "p") + content];
    if (!slot) {
        if (text.lines.empty()) slot = paragraph(text, content, false, 0, false, text.lineHeight, tabular);
        else slot = paragraph(text, content, text.lines[index].rtl, text.lines[index].wordSpacing, true, 0, tabular);
    }
    return slot.get();
}

std::vector<Piece> Pieces(TextLayout& text, const Layer& l, float t, int fps, std::string* shown) {
    if (text.shown.size() > 16 + 2 * text.lines.size()) text.shown.clear();
    std::string all = text.lines.empty() ? text.content : "";
    for (size_t i = 0; i < text.lines.size(); i++) all += (i ? "\n" : "") + text.lines[i].content;
    bool tabular = false;
    *shown = Shown(l, t, fps, all, &tabular);
    std::vector<Piece> pieces;
    if (text.lines.empty()) {
        pieces.push_back({Laid(text, 0, *shown, tabular), -text.width / 2, -text.height / 2 + text.padTop, 0, -1});
        return pieces;
    }
    size_t base = 0;
    for (size_t i = 0; i < text.lines.size() && base <= shown->size(); i++) {
        const size_t end = std::min(shown->find('\n', base), shown->size());
        Paragraph* p = Laid(text, (int)i, shown->substr(base, end - base), tabular);
        float x = -text.width / 2;
        const float run = p->getMaxIntrinsicWidth();
        if (text.align == "center") x += (text.width - run) / 2;
        else if (text.align == "right") x += text.width - run;
        pieces.push_back({p, x, text.lines[i].baseline - text.height / 2 - p->getAlphabeticBaseline(), (int)base, (int)i});
        base = end + 1;
    }
    return pieces;
}

std::vector<Glyph> Collect(const std::vector<Piece>& pieces) {
    std::vector<Glyph> glyphs;
    for (const Piece& piece : pieces) {
        piece.paragraph->visit([&](int line, const Paragraph::VisitorInfo* info) {
            if (!info) return;
            std::vector<SkRect> bounds(info->count);
            info->font.getBounds({info->glyphs, (size_t)info->count}, bounds, nullptr);
            for (int i = 0; i < info->count; i++) {
                const SkPoint at = {piece.x + info->origin.fX + info->positions[i].fX, piece.y + info->origin.fY + info->positions[i].fY};
                glyphs.push_back({info->font, info->glyphs[i], at, bounds[i].makeOffset(at.fX, at.fY), piece.base + (int)info->utf8Starts[i],
                                  piece.line >= 0 ? piece.line : line});
            }
        });
    }
    return glyphs;
}

std::vector<int> Units(const std::vector<Glyph>& glyphs, const textanim::Segments& seg, int kind, int* count) {
    std::vector<int> unit(glyphs.size(), -1);
    for (size_t i = 0; i < glyphs.size(); i++) {
        const int o = glyphs[i].offset;
        if (kind == 2) unit[i] = glyphs[i].line;
        else if (o >= 0 && o < (int)seg.character.size()) unit[i] = kind == 1 ? seg.word[o] : seg.character[o];
    }
    std::vector<int> ids(unit);
    std::sort(ids.begin(), ids.end());
    ids.erase(std::unique(ids.begin(), ids.end()), ids.end());
    if (!ids.empty() && ids.front() < 0) ids.erase(ids.begin());
    for (int& u : unit) u = u < 0 ? -1 : (int)(std::lower_bound(ids.begin(), ids.end(), u) - ids.begin());
    *count = (int)ids.size();
    return unit;
}

struct Look {
    SkVector move = {0, 0};
    float scale = 1, rotation = 0, alpha = 1, blur = 0;
    SkColor4f tint = {0, 0, 0, 0};
};

Look Progress(const LayerEffect& e, float t, float v) {
    Look k;
    const float eased = 1 - std::pow(1 - v, 3.0f), distance = e.value("distance", t, 40), back = v - 1;
    k.alpha = v;
    switch (Choice(e, "appearance", t, 1, 8)) {
        case 0: k.alpha = v > 0 ? 1 : 0; break;
        case 2: k.move.fY = distance * (1 - eased); break;
        case 3: k.move.fY = -distance * (1 - eased); break;
        case 4: k.move.fX = distance * (1 - eased); break;
        case 5: k.move.fX = -distance * (1 - eased); break;
        case 6: k.scale = 1 + 2.70158f * back * back * back + 1.70158f * back * back; k.alpha = std::min(1.0f, v * 4); break;
        case 7: k.scale = 2 - eased; break;
        case 8: k.blur = (1 - v) * std::max(distance, 0.0f) * 0.25f; break;
    }
    return k;
}

Look Transform(const LayerEffect& e, float t, float w) {
    w += (w * w * (3 - 2 * w) - w) * std::clamp(e.value("smoothness", t, 0), 0.0f, 1.0f);
    Look k;
    if (const Property* p = e.prop("position")) k.move = {p->anim.get(0, t, 0) * w, p->anim.get(1, t, 0) * w};
    k.scale = 1 + (e.value("scale", t, 1) - 1) * w;
    k.rotation = e.value("rotation", t, 0) * w;
    k.alpha = 1 + (std::clamp(e.value("opacity", t, 1), 0.0f, 1.0f) - 1) * w;
    if (const Property* p = e.prop("tint")) {
        k.tint = {p->anim.get(1, t, 1), p->anim.get(2, t, 1), p->anim.get(3, t, 1), std::clamp(p->anim.get(0, t, 0) * w, 0.0f, 1.0f)};
    }
    return k;
}

SkColor4f Blend(SkColor4f under, SkColor4f over) {
    const float a = 1 - (1 - under.fA) * (1 - over.fA);
    if (!(a > 0)) return under;
    const float u = under.fA * (1 - over.fA) / a, o = over.fA / a;
    return {under.fR * u + over.fR * o, under.fG * u + over.fG * o, under.fB * u + over.fB * o, a};
}

void Apply(const Layer& l, float t, const textanim::Segments& seg, std::vector<Glyph>* glyphs) {
    for (const LayerEffect& e : l.effects) {
        const bool progress = e.id == "absent.textprogress";
        if (e.hidden || (!progress && e.id != "absent.texttransform")) continue;
        int count = 0, steps = 0;
        const std::vector<int> unit = Units(*glyphs, seg, Choice(e, "unit", t, 0, 2), &count);
        const int direction = progress ? Choice(e, "direction", t, 0, 3)
                                       : e.value("randomOrder", t, 0) >= 0.5f ? textanim::kRandom : textanim::kFromStart;
        const std::vector<int> rank = textanim::Ranks(count, direction, Seed(l, e, t), &steps);
        std::vector<SkRect> box(count, SkRect::MakeEmpty());
        std::vector<SkPoint> fallback(count);
        for (size_t i = 0; i < glyphs->size(); i++) {
            if (unit[i] < 0) continue;
            box[unit[i]].join((*glyphs)[i].bounds);
            fallback[unit[i]] = (*glyphs)[i].at;
        }
        const float amount = std::clamp(e.value("progress", t, 1), 0.0f, 1.0f), softness = e.value("softness", t, 0);
        const float start = e.value("start", t, 0), end = e.value("end", t, 1), offset = e.value("offset", t, 0);
        const int shape = Choice(e, "shape", t, 0, 5);
        std::vector<Look> looks(count);
        std::vector<SkMatrix> matrices(count);
        for (int u = 0; u < count; u++) {
            looks[u] = progress ? Progress(e, t, textanim::Reveal(amount, rank[u], steps, softness) *
                                                     (1 - textanim::Reveal(std::clamp(start, 0.0f, 1.0f), rank[u], steps, softness)))
                                : Transform(e, t, textanim::RangeWeight(shape, rank[u], count, start + offset, end + offset));
            const SkPoint c = !progress && Choice(e, "anchor", t, 0, 1) == 1 ? SkPoint{0, 0}
                              : box[u].isEmpty() ? fallback[u] : box[u].center();
            matrices[u] = SkMatrix::Translate(-c.fX, -c.fY);
            matrices[u].postScale(looks[u].scale, looks[u].scale);
            matrices[u].postRotate(looks[u].rotation);
            matrices[u].postTranslate(c.fX + looks[u].move.fX, c.fY + looks[u].move.fY);
        }
        for (size_t i = 0; i < glyphs->size(); i++) {
            if (unit[i] < 0) continue;
            Glyph& g = (*glyphs)[i];
            const Look& k = looks[unit[i]];
            g.matrix.postConcat(matrices[unit[i]]);
            g.alpha *= k.alpha;
            g.blur = std::max(g.blur, k.blur);
            if (k.tint.fA > 0) g.tint = Blend(g.tint, k.tint);
        }
    }
}

std::vector<int> WordBreaks(const std::string& text) {
    std::vector<int> out;
    const std::vector<uint32_t> points = textanim::CodePoints(text);
    if (std::none_of(points.begin(), points.end(), textanim::Spaceless)) return out;
    std::vector<SkUnicode::Position> found;
    const sk_sp<SkUnicode> unicode = SkUnicodes::ICU::Make();
    if (unicode && unicode->getUtf8Words(text.data(), (int)text.size(), "th", &found)) {
        for (const SkUnicode::Position p : found) out.push_back((int)p);
    }
    return out;
}

std::vector<Glyph> Animate(const Layer& l, float t, int fps) {
    std::string shown;
    std::vector<Glyph> glyphs = Collect(Pieces(*l.text, l, t, fps, &shown));
    Apply(l, t, textanim::Split(shown, WordBreaks(shown)), &glyphs);
    return glyphs;
}

void DrawGlyphs(SkCanvas* canvas, const std::vector<Glyph>& glyphs, SkColor4f color, TextPart part) {
    const float opacity = color.fA;
    color.fA = 1;
    if (opacity < 1) canvas->saveLayerAlphaf(nullptr, opacity);
    const sk_sp<SkColorFilter> plain = SkColorFilters::Blend(color, nullptr, SkBlendMode::kSrcIn);
    for (const Glyph& g : glyphs) {
        if (!(g.alpha > 0.002f)) continue;
        const bool colored = Colored(g.font);
        if (part != TextPart::kAll && colored != (part == TextPart::kColor)) continue;
        SkPaint paint;
        paint.setAntiAlias(true);
        if (colored) {
            paint.setColor4f(color);
            if (g.tint.fA > 0) paint.setColorFilter(SkColorFilters::Blend(g.tint, nullptr, SkBlendMode::kSrcATop));
        } else if (g.tint.fA > 0) {
            SkColor4f mixed = Blend(color, g.tint);
            mixed.fA = 1;
            paint.setColorFilter(SkColorFilters::Blend(mixed, nullptr, SkBlendMode::kSrcIn));
        } else {
            paint.setColorFilter(plain);
        }
        paint.setAlphaf(std::min(g.alpha, 1.0f));
        if (g.blur > 0.05f && colored) paint.setImageFilter(SkImageFilters::Blur(g.blur, g.blur, nullptr));
        else if (g.blur > 0.05f) paint.setMaskFilter(SkMaskFilter::MakeBlur(kNormal_SkBlurStyle, g.blur));
        canvas->save();
        canvas->concat(g.matrix);
        canvas->drawGlyphs({&g.id, 1}, {&g.at, 1}, {0, 0}, g.font, paint);
        canvas->restore();
    }
    if (opacity < 1) canvas->restore();
}
}
bool TextEffect(const std::string& id) {
    return std::any_of(std::begin(kTextIds), std::end(kTextIds), [&](const char* k) { return id == k; });
}
bool TextAnimated(const Layer& layer) {
    return layer.text && std::any_of(layer.effects.begin(), layer.effects.end(), [](const LayerEffect& e) { return !e.hidden && TextEffect(e.id); });
}
SkPath TextGlyphPath(const Layer& layer, float t, int fps) {
    auto& text = *layer.text;
    layout(text);
    SkPathBuilder b;
    if (TextAnimated(layer)) {
        for (const Glyph& g : Animate(layer, t, fps)) {
            if (!(g.alpha > 0.002f) || Colored(g.font)) continue;
            SkMatrix m = SkMatrix::Translate(g.at.fX, g.at.fY);
            m.postConcat(g.matrix);
            if (auto path = g.font.getPath(g.id)) b.addPath(*path, m);
        }
        return b.detach();
    }
    EachGlyph(text, [&](const SkFont& font, SkGlyphID glyph, float x, float y) {
        if (Colored(font)) return;
        if (auto path = font.getPath(glyph)) b.addPath(*path, SkMatrix::Translate(x, y));
    });
    return b.detach();
}
void DrawText(SkCanvas* canvas, const Layer& layer, float t, const SkPaint& paint, int fps, TextPart part) {
    auto& text = *layer.text;
    layout(text);
    if (TextAnimated(layer)) return DrawGlyphs(canvas, Animate(layer, t, fps), paint.getColor4f(), part);
    if (part != TextPart::kColor) {
        SkPaint tint;
        tint.setColorFilter(SkColorFilters::Blend(paint.getColor4f(), nullptr, SkBlendMode::kSrcIn));
        const float scale = canvas->getTotalMatrix().getMinScale();
        if (scale > 0) {
            const float room = 2 / scale;
            const SkRect area = InkBounds(text).makeOutset(room, room);
            canvas->saveLayer(&area, &tint);
        } else {
            canvas->saveLayer(nullptr, &tint);
        }
        SkPaint white;
        white.setColor(SK_ColorWHITE);
        DrawRuns(canvas, text, false, white);
        canvas->restore();
    }
    if (part != TextPart::kPlain) DrawRuns(canvas, text, true, paint);
}
SkRect TextInkBounds(const Layer& layer) {
    auto& text = *layer.text;
    layout(text);
    return InkBounds(text);
}
}
