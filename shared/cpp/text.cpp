#include "text.h"
#include <algorithm>
#include <cctype>
#include <bit>
#include <cmath>
#include <cstdlib>
#include <map>
#include <mutex>
#include <optional>
#include <string>
#include <string_view>
#include <sys/stat.h>
#include "textanim.h"
#include "warp.h"
#include "include/core/SkContourMeasure.h"
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
#include "include/effects/SkRuntimeEffect.h"
#include "include/pathops/SkPathOps.h"
#ifdef __APPLE__
#include "include/ports/SkFontMgr_mac_ct.h"
#elif defined(_WIN32)
#include "include/ports/SkTypeface_win.h"
#elif defined(__ANDROID__)
#include "include/ports/SkFontMgr_android.h"
#else
#include "include/ports/SkFontMgr_fontconfig.h"
#endif
#include "include/ports/SkFontScanner_FreeType.h"
#ifdef AM_FREETYPE_FILES
#include "include/ports/SkFontMgr_empty.h"
#endif
#include "modules/skparagraph/include/ParagraphBuilder.h"
#include "modules/skparagraph/include/TypefaceFontProvider.h"
#include "modules/skunicode/include/SkUnicode_icu.h"
#include "unicode/uchar.h"

namespace am {
using namespace skia::textlayout;
struct TextLine {
    std::string content;
    float baseline = 0, wordSpacing = 0;
    bool rtl = false, split = false;
    long start = -1;
    std::unique_ptr<Paragraph> paragraph;
};
struct TextLayout {
    std::string content, family, path, align, emoji, flags, locale;
    float size = 54, width = 512, height = 0, letterSpacing = 0, lineSpacing = 1;
    int weight = 400, index = 0;
    bool italic = false, imported = false, axes = false, wrap = true, pixel = false, androidLayout = false;
    sk_sp<FontCollection> fonts;
    std::vector<TextLine> lines;
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
#elif defined(_WIN32)
    static auto manager = SkFontMgr_New_DirectWrite();
#elif defined(__ANDROID__)
    static auto manager = SkFontMgr_New_Android(nullptr, SkFontScanner_Make_FreeType());
#else
    static auto manager = SkFontMgr_New_FontConfig(nullptr, SkFontScanner_Make_FreeType());
#endif
    return manager;
}
sk_sp<SkFontMgr> FileFonts(const sk_sp<SkFontMgr>& fallback) {
#ifdef AM_FREETYPE_FILES
    static auto manager = SkFontMgr_New_Custom_Empty();
    return manager ? manager : fallback;
#else
    return fallback;
#endif
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
                                          : FileFonts(systemFonts())->makeFromFile(path.c_str());
    if (face) faces[path] = face;
    return face;
}
sk_sp<SkTypeface> TextFace(const TextLayout& text, const sk_sp<SkFontMgr>& manager) {
    struct stat info {};
    const bool found = stat(text.path.c_str(), &info) == 0;
#ifdef __APPLE__
    const long nanos = (long)info.st_mtimespec.tv_nsec;
#elif defined(_WIN32)
    const long nanos = 0;
#else
    const long nanos = (long)info.st_mtim.tv_nsec;
#endif
    const std::string stamp = found ? std::to_string(info.st_size) + ":" + std::to_string(info.st_ino) + ":" + std::to_string(info.st_mtime) + "." + std::to_string(nanos) : "";
    struct Cached {
        sk_sp<SkTypeface> face;
        std::vector<SkFontParameters::Variation::Axis> axes;
        bool weighted = false;
        uint64_t used = 0;
    };
    static std::mutex lock;
    static std::map<std::string, Cached> faces;
    static uint64_t clock = 0;
    const auto find = [&](const std::string& key, Cached* out) {
        std::lock_guard<std::mutex> hold(lock);
        auto found = faces.find(key);
        if (found == faces.end()) return false;
        found->second.used = ++clock;
        *out = found->second;
        return true;
    };
    const auto keep = [&](const std::string& key, Cached entry) {
        std::lock_guard<std::mutex> hold(lock);
        if (faces.size() >= 64 && !faces.count(key)) {
            faces.erase(std::min_element(faces.begin(), faces.end(), [](const auto& a, const auto& b) { return a.second.used < b.second.used; }));
        }
        entry.used = ++clock;
        faces[key] = std::move(entry);
    };
    const std::string file = text.path + "\n" + std::to_string(text.index) + "\n" + stamp;
    Cached base;
    if (!find(file, &base)) {
        base.face = FileFonts(manager)->makeFromFile(text.path.c_str(), text.index);
        if (!base.face) return nullptr;
        const int count = base.face->getVariationDesignParameters({});
        if (count > 0 && count <= 64) {
            base.axes.resize(count);
            base.face->getVariationDesignParameters(base.axes);
            base.weighted = std::any_of(base.axes.begin(), base.axes.end(), [](const auto& a) { return a.tag == SkSetFourByteTag('w', 'g', 'h', 't'); });
        }
        keep(file, base);
    }
    if (base.axes.empty() || (text.imported && !text.axes)) return base.face;
    const std::string key = file + "\n" + (base.weighted ? std::to_string(text.weight) : "-");
    Cached made;
    if (find(key, &made)) return made.face;
    std::vector<SkFontArguments::VariationPosition::Coordinate> coordinates;
    for (const auto& axis : base.axes) {
        const float value = axis.tag == SkSetFourByteTag('w', 'g', 'h', 't') ? text.weight : axis.def;
        coordinates.push_back({axis.tag, std::clamp(value, axis.min, axis.max)});
    }
    SkFontArguments arguments;
    arguments.setVariationDesignPosition({coordinates.data(), (int)coordinates.size()});
    made.face = base.face->makeClone(arguments);
    if (!made.face) made.face = base.face;
    keep(key, made);
    return made.face;
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
                                     bool rtl, float wordSpacing, bool prepared, bool tabular = false) {
    TextStyle style;
    style.setColor(SK_ColorWHITE);
    style.setFontSize(text.size);
    style.setFontStyle(SkFontStyle(text.weight, SkFontStyle::kNormal_Width,
        text.italic ? SkFontStyle::kItalic_Slant : SkFontStyle::kUpright_Slant));
    style.setFontFamilies({SkString(text.family)});
    style.setLetterSpacing(text.pixel ? std::round(text.letterSpacing * text.size) : text.letterSpacing * text.size);
    style.setWordSpacing(wordSpacing);
    if (!text.locale.empty()) style.setLocale(SkString(text.locale));
    if (tabular) style.addFontFeature(SkString("tnum"), 1);
    if (text.pixel) {
        style.setSubpixel(false);
        style.setFontHinting(SkFontHinting::kNone);
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
    result->layout(prepared ? std::max(text.width, (text.size * 2 + std::fabs(text.letterSpacing * text.size)) * content.size())
                            : text.width + text.letterSpacing * text.size);
    return result;
}
float Natural(TextLayout& text, const std::string& content, bool rtl) {
    return content.empty() ? 0 : paragraph(text, content, rtl, 0, true)->getMaxIntrinsicWidth();
}
struct Span {
    size_t from, to;
};
bool SpaceBefore(const std::string& s, size_t from, size_t end, size_t* at) {
    if (end > from && s[end - 1] == ' ') *at = end - 1;
    else if (end >= from + 3 && s.compare(end - 3, 3, "\xE3\x80\x80") == 0) *at = end - 3;
    else return false;
    return true;
}
bool Broken(const std::string& s, Span span) { return span.to > span.from && s[span.to - 1] == '\n'; }
size_t Unbroken(const std::string& s, size_t from, size_t end) {
    if (end > from && s[end - 1] == '\n') end--;
    if (end > from && s[end - 1] == '\r') end--;
    return end;
}
bool Overflows(TextLayout& text, const std::string& content, bool rtl) {
    size_t at;
    return SpaceBefore(content, 0, Unbroken(content, 0, content.size()), &at) && Natural(text, content.substr(0, at), rtl) > text.width + 0.5f;
}
float Spread(TextLayout& text, const std::string& content, bool rtl) {
    const auto spaces = std::count(content.begin(), content.end(), ' ');
    return spaces ? (text.width - Natural(text, content, rtl)) / spaces : 0;
}
std::string Visible(std::string raw) {
    size_t at;
    if (SpaceBefore(raw, 0, raw.size(), &at)) raw.resize(at);
    else if (!raw.empty() && std::isspace((unsigned char)raw.back())) raw.pop_back();
    if (!raw.empty() && raw.back() == '\r') raw.pop_back();
    return raw;
}
bool RightToLeft(const std::string& s, size_t from, size_t to) {
    for (uint32_t c : textanim::CodePoints(s.substr(from, to - from))) {
        const UCharDirection d = u_charDirection((UChar32)c);
        if (d == U_LEFT_TO_RIGHT) return false;
        if (d == U_RIGHT_TO_LEFT || d == U_RIGHT_TO_LEFT_ARABIC) return true;
    }
    return false;
}
bool Rewrapped(TextLayout& text, const std::string& src, size_t from, size_t to, bool rtl, std::vector<TextLine>* made, std::vector<Span>* spans) {
    const size_t close = Unbroken(src, from, to);
    if (close <= from) return false;
    auto p = paragraph(text, src.substr(from, close - from), rtl, 0, false);
    const size_t count = p->lineNumber();
    if (count == 0) return false;
    size_t next = from + p->getActualTextRange(0, true).start;
    for (size_t j = 0; j < count; j++) {
        const size_t start = next;
        next = j + 1 < count ? from + p->getActualTextRange((int)j + 1, true).start : to;
        if (start > next || next > to) return false;
        TextLine line;
        line.content = Visible(src.substr(start, next - start));
        line.rtl = rtl;
        line.split = true;
        line.wordSpacing = text.align == "justify" && j + 1 < count ? Spread(text, line.content, rtl) : 0;
        made->push_back(std::move(line));
        spans->push_back({start, next});
    }
    return true;
}
void Respace(TextLayout& text, const std::string& src, std::vector<TextLine>& lines, std::vector<Span>& spans) {
    const auto hard = [&](size_t i) { return i + 1 == lines.size() || Broken(src, spans[i]); };
    for (size_t i = 0; i < lines.size();) {
        const Span s = spans[i];
        const bool rtl = lines[i].rtl;
        const size_t end = Unbroken(src, s.from, s.to);
        size_t run = end;
        for (size_t at; SpaceBefore(src, s.from, run, &at);) run = at;
        if (run == end || !Overflows(text, lines[i].content, rtl)) {
            i++;
            continue;
        }
        int count = 0;
        for (size_t at = run; at < end; at += src[at] == ' ' ? 1 : 3) count++;
        const float before = Natural(text, src.substr(s.from, run - s.from), rtl);
        const std::vector<int> rows = textanim::SpaceRows(before, (Natural(text, src.substr(s.from, end - s.from), rtl) - before) / count, count, text.width);
        if (rows.size() < 2) {
            i++;
            continue;
        }
        std::vector<size_t> cut{s.from};
        size_t at = run;
        for (int n : rows) {
            for (int k = 0; k < n; k++) at += src[at] == ' ' ? 1 : 3;
            cut.push_back(at);
        }
        size_t last = i;
        while (!hard(last)) last++;
        const bool soft = last > i;
        const size_t keep = soft ? rows.size() - 1 : rows.size();
        std::vector<TextLine> made;
        std::vector<Span> placed;
        for (size_t r = 0; r < keep; r++) {
            TextLine part;
            part.content = src.substr(cut[r], cut[r + 1] - cut[r]);
            part.rtl = rtl;
            part.split = true;
            part.wordSpacing = text.align == "justify" && (soft || r + 1 < keep) ? Spread(text, part.content, rtl) : 0;
            made.push_back(std::move(part));
            placed.push_back({cut[r], r + 1 < keep || soft ? cut[r + 1] : s.to});
        }
        const bool merged = soft && Rewrapped(text, src, cut[keep], spans[last].to, rtl, &made, &placed);
        if (soft && !merged) {
            made.resize(keep);
            placed.resize(keep);
            last = i;
            TextLine rest;
            rest.content = src.substr(cut[keep], end - cut[keep]);
            rest.rtl = rtl;
            rest.split = true;
            made.push_back(std::move(rest));
            placed.push_back({cut[keep], s.to});
        }
        const size_t added = made.size();
        lines.erase(lines.begin() + i, lines.begin() + last + 1);
        spans.erase(spans.begin() + i, spans.begin() + last + 1);
        lines.insert(lines.begin() + i, std::make_move_iterator(made.begin()), std::make_move_iterator(made.end()));
        spans.insert(spans.begin() + i, placed.begin(), placed.end());
        i += merged ? keep : added;
    }
}
bool Located(TextLayout& text, std::vector<Span>* spans) {
    const std::string& src = text.content;
    size_t byte = 0;
    long unit = 0;
    for (const TextLine& line : text.lines) {
        if (line.start < unit) return false;
        while (unit < line.start && byte < src.size()) {
            const unsigned char c = src[byte];
            const size_t length = c < 0x80 ? 1 : c < 0xE0 ? 2 : c < 0xF0 ? 3 : 4;
            unit += length == 4 ? 2 : 1;
            byte += length;
        }
        if (unit != line.start || byte > src.size() || src.compare(byte, line.content.size(), line.content) != 0) return false;
        if (!spans->empty()) spans->back().to = byte;
        spans->push_back({byte, src.size()});
    }
    return true;
}
void Unhang(TextLayout& text) {
    std::vector<Span> spans;
    if (Located(text, &spans)) {
        Respace(text, text.content, text.lines, spans);
        return;
    }
    std::string joined;
    spans.clear();
    for (const TextLine& line : text.lines) {
        spans.push_back({joined.size(), joined.size() + line.content.size() + 1});
        joined += line.content + "\n";
    }
    Respace(text, joined, text.lines, spans);
}
void SplitParagraph(TextLayout& text, Paragraph& p) {
    const int count = (int)p.lineNumber();
    const std::string& src = text.content;
    std::vector<TextLine> lines(count);
    std::vector<Span> spans(count);
    bool rtl = false;
    for (int i = 0; i < count; i++) {
        const size_t from = p.getActualTextRange(i, true).start;
        const size_t to = i + 1 < count ? p.getActualTextRange(i + 1, true).start : src.size();
        if (from > to || to > src.size()) return;
        if (i == 0 || Broken(src, spans[i - 1])) rtl = RightToLeft(src, from, std::min(src.find('\n', from), src.size()));
        lines[i].content = Visible(src.substr(from, to - from));
        lines[i].rtl = rtl;
        spans[i] = {from, to};
    }
    for (int i = 0; i + 1 < count && text.align == "justify"; i++) {
        if (!Broken(src, spans[i])) lines[i].wordSpacing = Spread(text, lines[i].content, lines[i].rtl);
    }
    if (text.wrap) Respace(text, src, lines, spans);
    text.lines = std::move(lines);
}
float Rounded(double v) { return v >= 0 ? (float)std::floor(v + 0.5) : -(float)std::floor(-v + 0.5); }
void Stack(TextLayout& text, SkFontMetrics m, const SkTypeface* face) {
    if (text.lines.empty()) text.lines.emplace_back();
    for (auto& line : text.lines) line.paragraph = paragraph(text, line.content, line.rtl, line.wordSpacing, true);
    SkString family;
    if (face) face->getFamilyName(&family);
    if (family.equals("Roboto") || family.equals("Roboto Mono")) {
        m.fTop = std::min(m.fTop, -text.size * (family.equals("Roboto") ? 2163 : 2146) / 2048);
        m.fBottom = std::max(m.fBottom, text.size * 555 / 2048);
    }
    const float top = std::floor(text.pixel ? m.fTop : std::min(m.fTop, m.fAscent)), bottom = std::ceil(text.pixel ? m.fBottom : std::max(m.fBottom, m.fDescent));
    float v = 0;
    for (size_t i = 0; i < text.lines.size(); i++) {
        LineMetrics line;
        const bool measured = text.lines[i].paragraph->getLineMetricsAt(0, &line);
        const float above = i == 0 ? top : std::floor(std::min(m.fAscent, measured ? (float)-line.fAscent : 0.f) + 0.5f);
        const float below = i + 1 == text.lines.size() ? bottom : std::floor(std::max(m.fDescent, measured ? (float)line.fDescent : 0.f) + 0.5f);
        text.lines[i].baseline = v - above;
        v += below - above + (i + 1 < text.lines.size() ? Rounded((below - above) * (text.lineSpacing - 1.0)) : 0.f);
    }
    text.height = v;
}
void layout(TextLayout& text) {
    if (text.fonts) return;
    auto manager = systemFonts();
    text.fonts = sk_make_sp<FontCollection>();
    text.fonts->setDefaultFontManager(manager,
#ifdef __APPLE__
        "Helvetica"
#elif defined(_WIN32)
        "Arial"
#else
        "sans-serif"
#endif
    );
    sk_sp<SkTypeface> face = text.path.empty() ? nullptr : TextFace(text, manager);
    const sk_sp<SkTypeface> emoji = EmojiFace(text.emoji), flags = text.flags.empty() ? nullptr : EmojiFace(text.flags);
    auto provider = sk_make_sp<EmojiFonts>(std::vector<sk_sp<SkTypeface>>{emoji, flags});
    if (face) {
        text.family = "light-motion-text";
        provider->registerTypeface(face, SkString(text.family));
    }
    if (emoji) provider->registerTypeface(emoji, SkString(kEmojiFamily));
    if (flags) provider->registerTypeface(flags, SkString(kFlagFamily));
    text.fonts->setAssetFontManager(provider);
    if (!face) face = manager->matchFamilyStyle(text.family.c_str(), SkFontStyle(text.weight, 5,
        text.italic ? SkFontStyle::kItalic_Slant : SkFontStyle::kUpright_Slant));
    SkFont font(face, text.size);
    SkFontMetrics metrics;
    font.getMetrics(&metrics);
    if (!text.lines.empty()) {
        if (text.wrap) Unhang(text);
    } else {
        SplitParagraph(text, *paragraph(text, text.content, false, 0, !text.wrap));
    }
    Stack(text, metrics, face.get());
}
}
void AlightText(TextLayout& text, bool transformed) { text.androidLayout = !transformed; }
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
    text->locale = attr(n, "_locale");
    text->imported = attr(n, "font").starts_with("imported?");
    text->axes = attr(n, "_fontAxes") == "true";
    text->pixel = attr(n, "textMetrics") == "pixel";
    for (const auto& child : n.children) {
        if (child->name == "effect" && attr(*child, "id") == "absent.textpath" && attr(*child, "hidden") != "true") text->wrap = false;
    }
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
        for (const auto& line : prepared->children) {
            TextLine l;
            l.content = line->text;
            l.rtl = attr(*line, "rtl") == "true";
            l.wordSpacing = number(*line, "wordSpacing", 0);
            l.start = line->attr("from") ? std::strtol(line->attr("from"), nullptr, 10) : -1;
            text->lines.push_back(std::move(l));
        }
    }
    return text;
}
namespace {
bool OnPathBounds(const Layer& layer, float t, SkRect* bounds);
}
SkPath TextPath(const Layer& layer, float t) {
    auto& text = *layer.text;
    layout(text);
    SkRect placed;
    if (OnPathBounds(layer, t, &placed)) return SkPath::Rect(placed);
    return SkPath::Rect(SkRect::MakeXYWH(-text.width / 2, -text.height / 2, text.width, text.height));
}
namespace {
float Run(const TextLayout& text, const TextLine& line, Paragraph& p) {
    if (line.rtl || !(text.letterSpacing < 0)) return p.getMaxIntrinsicWidth();
    float run = 0;
    bool drawn = false;
    p.visit([&](int, const Paragraph::VisitorInfo* info) {
        if (!info) return;
        run += info->advanceX;
        drawn = drawn || info->count > 0;
    });
    return drawn ? run - text.letterSpacing * text.size / 2 : 0;
}
float LineX(const TextLayout& text, const TextLine& line, Paragraph& p) {
    float x = -text.width / 2;
    const float run = Run(text, line, p);
    if (text.align == "center") x += (text.width - (text.androidLayout ? (float)((int)run & ~1) : run)) / 2;
    else if (text.align == "right") x += text.width - run;
    return line.split && line.rtl ? x + run - p.getLongestLine() : x;
}
float Baseline(const TextLayout& text, Paragraph& p) {
    return text.pixel ? std::floor(p.getAlphabeticBaseline() + 0.5f) : p.getAlphabeticBaseline();
}
template <typename F> void EachParagraph(TextLayout& text, F&& at) {
    for (auto& line : text.lines) at(*line.paragraph, LineX(text, line, *line.paragraph), line.baseline - text.height / 2 - Baseline(text, *line.paragraph));
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
            SkFont font = info->font;
            font.setBaselineSnap(false);
            canvas->drawGlyphs({info->glyphs, (size_t)info->count}, {info->positions, (size_t)info->count},
                               {x + info->origin.fX, y + info->origin.fY}, font, paint);
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
                                "absent.timecode", "absent.textpath", "absent.textshake", "absent.textblips", "absent.fontweight"};

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
    float advance = 0;
    SkPath piece;
    int part = -1;
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
            text = textanim::FormatTimecode(clock * e.value("speed", t, 1) + e.value("offset", t, 0), fps, Choice(e, "format", t, 0, 6));
            *tabular = true;
        } else if (e.id == "absent.textprogress" && e.value("style", t, 0) >= 0.5f) {
            const std::vector<uint32_t> points = textanim::CodePoints(text);
            const int n = (int)points.size();
            const int from = std::clamp((int)std::lround(e.value("start", t, 0) * n), 0, n);
            const int to = std::clamp((int)std::lround(e.value("progress", t, 1) * n), from, n);
            std::string kept;
            for (int i = from; i < to; i++) kept += textanim::Utf8(points[i]);
            text = kept;
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
    if (!tabular && content == text.lines[index].content) return text.lines[index].paragraph.get();
    auto& slot = text.shown[std::to_string(index) + (tabular ? "t" : "p") + content];
    if (!slot) slot = paragraph(text, content, text.lines[index].rtl, text.lines[index].wordSpacing, true, tabular);
    return slot.get();
}

std::vector<Piece> Pieces(TextLayout& text, const Layer& l, float t, int fps, std::string* shown) {
    if (text.shown.size() > 16 + 2 * text.lines.size()) text.shown.clear();
    std::string all;
    for (size_t i = 0; i < text.lines.size(); i++) all += (i ? "\n" : "") + text.lines[i].content;
    bool tabular = false;
    *shown = Shown(l, t, fps, all, &tabular);
    std::vector<Piece> pieces;
    size_t base = 0;
    for (size_t i = 0; i < text.lines.size() && base <= shown->size(); i++) {
        const size_t end = std::min(shown->find('\n', base), shown->size());
        Paragraph* p = Laid(text, (int)i, shown->substr(base, end - base), tabular);
        pieces.push_back({p, LineX(text, text.lines[i], *p), text.lines[i].baseline - text.height / 2 - Baseline(text, *p), (int)base, (int)i});
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
            std::vector<SkScalar> widths(info->count);
            info->font.getBounds({info->glyphs, (size_t)info->count}, bounds, nullptr);
            info->font.getWidths({info->glyphs, (size_t)info->count}, widths);
            for (int i = 0; i < info->count; i++) {
                const SkPoint at = {piece.x + info->origin.fX + info->positions[i].fX, piece.y + info->origin.fY + info->positions[i].fY};
                glyphs.push_back({info->font, info->glyphs[i], at, bounds[i].makeOffset(at.fX, at.fY), piece.base + (int)info->utf8Starts[i],
                                  piece.line >= 0 ? piece.line : line});
                glyphs.back().advance = widths[i];
                glyphs.back().font.setBaselineSnap(false);
                glyphs.back().font.setSubpixel(true);
            }
        });
    }
    return glyphs;
}

std::vector<int> Units(const std::vector<Glyph>& glyphs, const textanim::Segments& seg, int kind, bool letters, int* count) {
    std::vector<int> unit(glyphs.size(), -1);
    const bool cut = kind == 3 && std::any_of(glyphs.begin(), glyphs.end(), [](const Glyph& g) { return g.part >= 0; });
    int next = 0, last = -1;
    for (size_t i = 0; i < glyphs.size(); i++) {
        const int o = glyphs[i].offset;
        if (kind == 2) unit[i] = glyphs[i].line;
        else if (cut && glyphs[i].part >= 0) {
            unit[i] = next++;
            last = -1;
        } else if (o >= 0 && o < (int)seg.character.size()) {
            unit[i] = kind == 1 || (letters && seg.word[o] < 0) ? seg.word[o] : seg.character[o];
            if (cut && unit[i] >= 0) {
                if (unit[i] != last) next++;
                last = unit[i];
                unit[i] = next - 1;
            }
        }
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
    float scale = 1, verticalStretch = 1, rotation = 0, alpha = 1, blur = 0;
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
    k.verticalStretch = std::max(0.0f, 1 + e.value("stretch", t, 0) * w);
    k.rotation = e.value("rotation", t, 0) * w;
    k.alpha = 1 + (std::clamp(e.value("opacity", t, 1), 0.0f, 1.0f) - 1) * w;
    if (const Property* p = e.prop("tint")) {
        k.tint = {p->anim.get(1, t, 1), p->anim.get(2, t, 1), p->anim.get(3, t, 1), std::clamp(p->anim.get(0, t, 0) * w, 0.0f, 1.0f)};
    }
    return k;
}

float AlightWeight(const LayerEffect& e, float t, int shape, int rank, int count) {
    const float overlap = std::max(0.0f, e.value("overlap", t, 0)), span = 1 + overlap, total = count + 1 + 2 * overlap;
    const float phase = e.value("offset", t, 0);
    const float from = total * (e.value("start", t, 0) - phase), to = total * (e.value("end", t, 1) - phase) - span;
    float w = 0;
    if (shape == textanim::kSquare) {
        w = std::min(std::clamp((to - rank) / span, 0.0f, 1.0f), std::clamp((rank + span - from) / span, 0.0f, 1.0f));
    } else {
        const float middle = (from + to) / 2 - span, half = (to - from + span) / 2;
        const float d = half > 1e-6f ? (rank - middle) / half : (rank == middle ? 0.0f : 2.0f);
        if (shape == textanim::kTriangle) w = std::max(0.0f, 1 - std::fabs(d));
        else if (shape == textanim::kSmooth) w = std::exp(-4.9348022f * d * d);
        else if (shape == textanim::kRound) w = std::fabs(d) < 1 ? std::sqrt(1 - d * d) : 0;
        else w = std::clamp(shape == textanim::kRampUp ? (d + 1) / 2 : (1 - d) / 2, 0.0f, 1.0f);
    }
    w = std::pow(w, 1 + 0.8f * std::clamp(e.value("easeIn", t, 0), -1.0f, 1.0f));
    return 1 - std::pow(1 - w, 1 + 0.8f * std::clamp(e.value("easeOut", t, 0), -1.0f, 1.0f));
}

SkColor4f Blend(SkColor4f under, SkColor4f over) {
    const float a = 1 - (1 - under.fA) * (1 - over.fA);
    if (!(a > 0)) return under;
    const float u = under.fA * (1 - over.fA) / a, o = over.fA / a;
    return {under.fR * u + over.fR * o, under.fG * u + over.fG * o, under.fB * u + over.fB * o, a};
}

void Type(const LayerEffect& e, float t, float seconds, const std::string& shown, const textanim::Segments& seg, std::vector<Glyph>* glyphs) {
    if (e.value("reveal", t, 1) < 0.5f) return;
    const std::vector<float> at = textanim::TypeTimes(shown, e.value("speed", 0, 30), e.value("pause", 0, 0.25f));
    const float now = seconds - e.value("delay", 0, 0) + 1e-4f;
    for (Glyph& g : *glyphs) {
        const int c = g.offset >= 0 && g.offset < (int)seg.character.size() ? seg.character[g.offset] : -1;
        if (c >= 0 && c < (int)at.size() && at[c] > now) g.alpha = 0;
    }
}

void Apply(const Layer& l, float t, float seconds, const std::string& shown, const textanim::Segments& seg, std::vector<Glyph>* glyphs) {
    const LayerEffect* blips = nullptr;
    for (const LayerEffect& e : l.effects) {
        if (!e.hidden && e.id == "absent.textblips") blips = &e;
    }
    for (const LayerEffect& e : l.effects) {
        if (&e == blips) {
            Type(e, t, seconds, shown, seg, glyphs);
            continue;
        }
        const bool progress = e.id == "absent.textprogress", shake = e.id == "absent.textshake";
        if (e.hidden || (!progress && !shake && e.id != "absent.texttransform") || (progress && e.value("style", t, 0) >= 0.5f)) continue;
        int count = 0, steps = 0;
        const bool alight = !progress && !shake && Choice(e, "version", t, 0, 1) == 1;
        const std::vector<int> unit = Units(*glyphs, seg, Choice(e, "unit", t, 0, 3), alight, &count);
        const int direction = progress ? Choice(e, "direction", t, 0, 3)
                                       : e.value("randomOrder", t, 0) >= 0.5f ? textanim::kRandom : textanim::kFromStart;
        std::vector<int> rank = textanim::Ranks(count, direction, Seed(l, e, t), &steps);
        if (alight && direction == textanim::kRandom) rank = textanim::ShuffledRanks(count, e.value("seed", t, 1));
        std::vector<SkRect> box(count, SkRect::MakeEmpty());
        std::vector<SkPoint> fallback(count);
        for (size_t i = 0; i < glyphs->size(); i++) {
            if (unit[i] < 0) continue;
            box[unit[i]].join((*glyphs)[i].bounds);
            fallback[unit[i]] = (*glyphs)[i].at;
        }
        const float amount = std::clamp(e.value("progress", t, 1), 0.0f, 1.0f), softness = e.value("softness", t, 0);
        const float start = e.value("start", t, 0), end = e.value("end", t, 1), offset = e.value("offset", t, 0);
        const int shape = Choice(e, "shape", t, 0, 5), style = Choice(e, "style", t, 1, 4);
        const uint64_t seed = Seed(l, e, t);
        const float speed = e.value("speed", t, 24), cycles = e.value("cycles", t, 1), spread = e.value("spread", t, 30);
        const float size = e.value("amount", t, 4), turn = e.value("rotation", t, 0), letters = e.value("letters", t, 1), every = e.value("every", t, 0.6f);
        std::vector<Look> looks(count);
        std::vector<SkMatrix> matrices(count);
        for (int u = 0; u < count; u++) {
            if (shake) {
                const float w = textanim::RangeWeight(textanim::kSquare, u, count, start, end) *
                                (textanim::Picked(u, seed, seconds, letters, every) ? 1.0f : 0.0f);
                const textanim::Wiggle g = textanim::Shake(style, u, seed, seconds, speed, cycles, spread, size, style == 4 ? e.value("tilt", t, 15) : turn);
                looks[u].move = {g.x * w, g.y * w};
                looks[u].rotation = g.angle * w;
            } else {
                looks[u] = progress ? Progress(e, t, textanim::Reveal(amount, rank[u], steps, softness) *
                                                         (1 - textanim::Reveal(std::clamp(start, 0.0f, 1.0f), rank[u], steps, softness)))
                                    : Transform(e, t, alight ? AlightWeight(e, t, shape, rank[u], count)
                                                             : textanim::RangeWeight(shape, rank[u], count, start + offset, end + offset));
            }
            const SkPoint c = !progress && Choice(e, "anchor", t, 0, 1) == 1 ? SkPoint{0, 0}
                              : box[u].isEmpty() ? fallback[u] : box[u].center();
            matrices[u] = SkMatrix::Translate(-c.fX, -c.fY);
            matrices[u].postScale(looks[u].scale, looks[u].scale * looks[u].verticalStretch);
            matrices[u].postRotate(looks[u].rotation);
            matrices[u].postTranslate(c.fX + looks[u].move.fX, c.fY * looks[u].verticalStretch + looks[u].move.fY);
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

const LayerEffect* PathEffect(const Layer& l) {
    const LayerEffect* found = nullptr;
    for (const LayerEffect& e : l.effects) {
        if (!e.hidden && e.id == "absent.textpath") found = &e;
    }
    return found;
}

SkPoint PointOf(const LayerEffect& e, const char* name, float t, SkPoint fallback) {
    const Property* p = e.prop(name);
    return p ? SkPoint{p->anim.get(0, t, fallback.fX), p->anim.get(1, t, fallback.fY)} : fallback;
}

thread_local int gGuideDepth = 0;

SkPath Guide(const Layer& l, const LayerEffect& e, float t) {
    const int shape = Choice(e, "shape", t, 0, 2);
    if (shape == 2) {
        SkPath below;
        if (gGuideDepth > 3) return below;
        gGuideDepth++;
        const bool found = TextGuideBelow(l, t, &below);
        gGuideDepth--;
        return found ? below : SkPath();
    }
    SkPathBuilder b;
    if (shape == 1) {
        const SkPoint c = PointOf(e, "center", t, {0, 0});
        const float r = std::fabs(e.value("radius", t, 220));
        if (!(r > 0) || !std::isfinite(r) || !c.isFinite()) return {};
        const float w = SK_ScalarRoot2Over2;
        b.moveTo(c.fX, c.fY - r);
        b.conicTo({c.fX + r, c.fY - r}, {c.fX + r, c.fY}, w);
        b.conicTo({c.fX + r, c.fY + r}, {c.fX, c.fY + r}, w);
        b.conicTo({c.fX - r, c.fY + r}, {c.fX - r, c.fY}, w);
        b.conicTo({c.fX - r, c.fY - r}, {c.fX, c.fY - r}, w);
        b.close();
        return b.detach();
    }
    b.moveTo(PointOf(e, "start", t, {-300, 80}));
    b.cubicTo(PointOf(e, "control1", t, {-200, -120}), PointOf(e, "control2", t, {200, -120}), PointOf(e, "end", t, {300, 80}));
    return b.detach();
}

void OnPath(const Layer& l, float t, std::vector<Glyph>* glyphs) {
    const LayerEffect* e = PathEffect(l);
    if (!e || glyphs->empty()) return;
    const SkPath guide = Guide(l, *e, t);
    if (guide.isEmpty() || !guide.isFinite()) return;
    std::vector<sk_sp<SkContourMeasure>> parts;
    float length = 0;
    SkContourMeasureIter contours(guide, false, 4);
    while (sk_sp<SkContourMeasure> contour = contours.next()) {
        if (!(contour->length() > 0)) continue;
        length += contour->length();
        parts.push_back(std::move(contour));
    }
    if (parts.empty() || !std::isfinite(length)) return;
    const bool closed = parts.size() == 1 && parts[0]->isClosed();
    const bool reverse = e->value("reverse", t, 0) >= 0.5f, turn = e->value("rotate", t, 1) >= 0.5f;
    warp::Start top;
    if (closed) {
        constexpr int kSamples = 256;
        std::vector<float> xs(kSamples), ys(kSamples);
        for (int i = 0; i < kSamples; i++) {
            SkPoint p = {0, 0};
            parts[0]->getPosTan(length * i / kSamples, &p, nullptr);
            xs[i] = p.fX;
            ys[i] = p.fY;
        }
        top = warp::TopStart(xs, ys, length);
    }
    const bool backward = closed ? top.flip != reverse : reverse;
    auto place = [&](float d, SkPoint* at, SkVector* tangent) {
        for (size_t i = 0; i < parts.size(); i++) {
            const float part = parts[i]->length();
            if (d <= part || i + 1 == parts.size()) return parts[i]->getPosTan(std::min(d, part), at, tangent);
            d -= part;
        }
        return false;
    };
    const int align = Choice(*e, "align", t, 1, 2);
    const float position = e->value("position", t, 0.5f) * length, shift = e->value("baseline", t, 0);
    struct Extent {
        float left = INFINITY, right = -INFINITY, base = 0;
        bool seen = false;
    };
    std::map<int, Extent> lines;
    std::map<std::pair<int, int>, Extent> clusters;
    for (const Glyph& g : *glyphs) {
        Extent& line = lines[g.line];
        if (!line.seen) line.base = g.at.fY;
        line.seen = true;
        line.left = std::min(line.left, g.at.fX);
        line.right = std::max(line.right, g.at.fX + g.advance);
        Extent& cluster = clusters[{g.line, g.offset}];
        cluster.left = std::min(cluster.left, g.at.fX);
        cluster.right = std::max(cluster.right, g.at.fX + g.advance);
    }
    const float first = lines.begin()->second.base + (std::isfinite(shift) ? shift : 0);
    for (Glyph& g : *glyphs) {
        const Extent& line = lines[g.line];
        const Extent& cluster = clusters[{g.line, g.offset}];
        const float mid = (cluster.left + cluster.right) / 2;
        const float along = position + mid - warp::Anchor(align, line.left, line.right);
        const warp::Spot spot = closed ? warp::Along(top.at + (backward ? length / 2 - along : along - length / 2), length, true)
                                       : warp::Along(along, length, false);
        SkPoint at;
        SkVector tangent;
        if (!place(closed || !reverse ? spot.at : length - spot.at, &at, &tangent)) continue;
        if (backward) tangent = -tangent;
        at += tangent * spot.beyond;
        if (!turn) tangent = {1, 0};
        const SkVector normal = {-tangent.fY, tangent.fX};
        g.matrix.postConcat(SkMatrix::MakeAll(tangent.fX, normal.fX, at.fX - tangent.fX * mid - normal.fX * first,
                                              tangent.fY, normal.fY, at.fY - tangent.fY * mid - normal.fY * first, 0, 0, 1));
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

std::vector<SkPath> Contours(const SkPath& path) {
    struct Outline {
        SkPath path;
        SkRect box;
        float area;
    };
    std::vector<Outline> outlines;
    SkPathBuilder b;
    std::vector<SkPoint> points;
    const auto finish = [&] {
        SkPath made = b.detach();
        if (points.size() > 2) {
            float area = 0;
            for (size_t i = 0; i < points.size(); i++) area += points[i].cross(points[(i + 1) % points.size()]);
            outlines.push_back({made, made.getBounds(), area / 2});
        }
        points.clear();
    };
    SkPath::Iter it(path, false);
    SkPoint p[4];
    for (SkPath::Verb v; (v = it.next(p)) != SkPath::kDone_Verb;) {
        if (v == SkPath::kMove_Verb) {
            finish();
            b.moveTo(p[0]);
            points.push_back(p[0]);
        } else if (v == SkPath::kLine_Verb) {
            b.lineTo(p[1]);
            points.push_back(p[1]);
        } else if (v == SkPath::kQuad_Verb) {
            b.quadTo(p[1], p[2]);
            points.insert(points.end(), {p[1], p[2]});
        } else if (v == SkPath::kConic_Verb) {
            b.conicTo(p[1], p[2], it.conicWeight());
            points.insert(points.end(), {p[1], p[2]});
        } else if (v == SkPath::kCubic_Verb) {
            b.cubicTo(p[1], p[2], p[3]);
            points.insert(points.end(), {p[1], p[2], p[3]});
        } else if (v == SkPath::kClose_Verb) {
            b.close();
        }
    }
    finish();
    if (outlines.empty()) return {};
    const float outer = std::max_element(outlines.begin(), outlines.end(), [](const Outline& a, const Outline& c) { return std::fabs(a.area) < std::fabs(c.area); })->area;
    std::vector<SkPath> pieces;
    std::vector<int> index(outlines.size(), -1);
    for (size_t i = 0; i < outlines.size(); i++) {
        if ((outlines[i].area > 0) != (outer > 0)) continue;
        index[i] = (int)pieces.size();
        pieces.push_back(outlines[i].path);
    }
    for (size_t i = 0; i < outlines.size(); i++) {
        if (index[i] >= 0) continue;
        int best = -1;
        for (size_t j = 0; j < outlines.size(); j++) {
            if (index[j] < 0 || !outlines[j].box.contains(outlines[i].box)) continue;
            if (best < 0 || outlines[j].box.width() * outlines[j].box.height() < outlines[best].box.width() * outlines[best].box.height()) best = (int)j;
        }
        if (best >= 0) pieces[index[best]] = SkPathBuilder(pieces[index[best]]).addPath(outlines[i].path).detach();
        else pieces.push_back(outlines[i].path);
    }
    return pieces;
}

void Cut(const Layer& l, float t, std::vector<Glyph>* glyphs) {
    if (std::none_of(l.effects.begin(), l.effects.end(), [&](const LayerEffect& e) {
            return !e.hidden && e.id == "absent.texttransform" && Choice(e, "version", t, 0, 1) == 1 && Choice(e, "unit", t, 0, 3) == 3; })) return;
    std::vector<Glyph> cut;
    int part = 0;
    for (const Glyph& g : *glyphs) {
        const std::optional<SkPath> path = Colored(g.font) ? std::nullopt : g.font.getPath(g.id);
        if (!path || path->isEmpty()) {
            cut.push_back(g);
            continue;
        }
        for (const SkPath& piece : Contours(Simplify(*path).value_or(*path))) {
            Glyph made = g;
            made.piece = piece;
            made.part = part++;
            made.bounds = piece.getBounds().makeOffset(g.at.fX, g.at.fY);
            cut.push_back(std::move(made));
        }
    }
    *glyphs = std::move(cut);
}

std::vector<Glyph> Animate(const Layer& l, float t, int fps) {
    std::string shown;
    std::vector<Glyph> glyphs = Collect(Pieces(*l.text, l, t, fps, &shown));
    Cut(l, t, &glyphs);
    Apply(l, t, (float)(t * std::max(0, l.endTime - l.startTime) / 1000.0), shown, textanim::Split(shown, WordBreaks(shown)), &glyphs);
    OnPath(l, t, &glyphs);
    return glyphs;
}

bool OnPathBounds(const Layer& layer, float t, SkRect* bounds) {
    if (!PathEffect(layer)) return false;
    SkRect placed = SkRect::MakeEmpty();
    for (const Glyph& g : Animate(layer, t, ProjectFps())) {
        SkRect box = g.piece.getBounds();
        if (g.part < 0) g.font.getBounds({&g.id, 1}, {&box, 1}, nullptr);
        placed.join(g.matrix.mapRect(box.makeOffset(g.at.fX, g.at.fY)));
    }
    if (placed.isEmpty() || !placed.isFinite()) return false;
    *bounds = placed.makeOutset(2, 2);
    return true;
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
        if (g.part >= 0) {
            canvas->translate(g.at.fX, g.at.fY);
            canvas->drawPath(g.piece, paint);
        } else {
            canvas->drawGlyphs({&g.id, 1}, {&g.at, 1}, {0, 0}, g.font, paint);
        }
        canvas->restore();
    }
    if (opacity < 1) canvas->restore();
}
}
bool TextEffect(const std::string& id) {
    return std::any_of(std::begin(kTextIds), std::end(kTextIds), [&](const char* k) { return id == k; });
}
bool TextAnimated(const Layer& layer) {
    return layer.text && std::any_of(layer.effects.begin(), layer.effects.end(), [](const LayerEffect& e) { return !e.hidden && TextEffect(e.id) && e.id != "absent.fontweight"; });
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
            if (g.part >= 0) b.addPath(g.piece, m);
            else if (auto path = g.font.getPath(g.id)) b.addPath(*path, m);
        }
        return b.detach();
    }
    EachGlyph(text, [&](const SkFont& font, SkGlyphID glyph, float x, float y) {
        if (Colored(font)) return;
        if (auto path = font.getPath(glyph)) b.addPath(*path, SkMatrix::Translate(x, y));
    });
    return b.detach();
}
SkPath TextHitPath(const Layer& layer, float t) {
    layout(*layer.text);
    if (!PathEffect(layer)) return TextPath(layer, t);
    SkPathBuilder b;
    for (const Glyph& g : Animate(layer, t, ProjectFps())) {
        if (!(g.alpha > 0.002f)) continue;
        SkFontMetrics metrics;
        g.font.getMetrics(&metrics);
        SkPoint q[4];
        SkRect::MakeLTRB(g.at.fX, g.at.fY + metrics.fAscent, g.at.fX + g.advance, g.at.fY + metrics.fDescent).toQuad(q);
        g.matrix.mapPoints(q);
        if ((q[1] - q[0]).cross(q[2] - q[1]) < 0) std::swap(q[1], q[3]);
        b.addPolygon({q, 4}, true);
    }
    SkPath path = b.detach();
    return path.isEmpty() ? TextPath(layer, t) : path;
}
sk_sp<SkImageFilter> AlightLift(const SkMatrix& m, SkPoint at) {
    if (!m.isScaleTranslate() || m.getScaleY() == 0) return nullptr;
    const float y = m.mapPoint(at).fY, lift = y - std::floor(y + 0.5f);
    if (std::fabs(lift) < 0.01f) return nullptr;
    return SkImageFilters::MatrixTransform(SkMatrix::Translate(0, lift / m.getScaleY()), SkSamplingOptions(SkFilterMode::kLinear), nullptr);
}
sk_sp<SkColorFilter> AlightEdges() {
    static const sk_sp<SkColorFilter> filter = SkRuntimeEffect::MakeForColorFilter(SkString("half4 main(half4 c) { return half4(c.rgb * c.a, c.a); }")).effect->makeColorFilter(nullptr);
    return filter;
}
void DrawText(SkCanvas* canvas, const Layer& layer, float t, const SkPaint& paint, int fps, TextPart part) {
    auto& text = *layer.text;
    layout(text);
    if (TextAnimated(layer)) {
        const std::vector<Glyph> glyphs = Animate(layer, t, fps);
        const bool alight = layer.alight && part != TextPart::kColor;
        if (alight) {
            SkPaint edges;
            edges.setColorFilter(AlightEdges());
            if (text.androidLayout && !glyphs.empty()) edges.setImageFilter(AlightLift(canvas->getTotalMatrix(), glyphs[0].matrix.mapPoint(glyphs[0].at)));
            canvas->saveLayer(nullptr, &edges);
        }
        DrawGlyphs(canvas, glyphs, paint.getColor4f(), alight && part == TextPart::kAll ? TextPart::kPlain : part);
        if (alight) canvas->restore();
        if (alight && part == TextPart::kAll) DrawGlyphs(canvas, glyphs, paint.getColor4f(), TextPart::kColor);
        return;
    }
    if (part != TextPart::kColor) {
        SkPaint tint;
        const sk_sp<SkColorFilter> color = SkColorFilters::Blend(paint.getColor4f(), nullptr, SkBlendMode::kSrcIn);
        tint.setColorFilter(layer.alight ? AlightEdges()->makeComposed(color) : color);
        if (text.androidLayout) {
            SkPoint first = {0, 0};
            bool found = false;
            EachGlyph(text, [&](const SkFont&, SkGlyphID, float x, float y) {
                if (!found) first = {x, y};
                found = true;
            });
            if (found) tint.setImageFilter(AlightLift(canvas->getTotalMatrix(), first));
        }
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
sk_sp<SkTypeface> MonoFace(int32_t character) {
    static const sk_sp<SkTypeface> mono = [] {
        for (const char* family : {"monospace", "Menlo", "Courier New"}) {
            if (sk_sp<SkTypeface> face = systemFonts()->matchFamilyStyle(family, SkFontStyle())) return face;
        }
        return systemFonts()->legacyMakeTypeface(nullptr, SkFontStyle());
    }();
    if (!mono || mono->unicharToGlyph(character)) return mono;
    static std::mutex lock;
    static std::map<int32_t, sk_sp<SkTypeface>> fallbacks;
    std::lock_guard<std::mutex> hold(lock);
    if (fallbacks.size() >= 1024) fallbacks.clear();
    auto [entry, fresh] = fallbacks.try_emplace(character, mono);
    if (fresh) {
        if (sk_sp<SkTypeface> face = systemFonts()->matchFamilyStyleCharacter("monospace", SkFontStyle(), nullptr, 0, character)) entry->second = face;
    }
    return entry->second;
}
SkRect TextInkBounds(const Layer& layer) {
    auto& text = *layer.text;
    layout(text);
    return InkBounds(text);
}
}
