#include "deepfilter.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <map>
#include <string>
#include <vector>

extern "C" {
struct kiss_fft_state;
struct DfComplex {
    float r;
    float i;
};
kiss_fft_state* rnn_fft_alloc(int nfft, void* mem, std::size_t* lenmem, int arch);
void rnn_fft_c(const kiss_fft_state* cfg, const DfComplex* fin, DfComplex* fout);
void rnn_ifft_c(const kiss_fft_state* cfg, const DfComplex* fin, DfComplex* fout);
void rnn_fft_free(const kiss_fft_state* cfg, int arch);
}

namespace deepfilter {
namespace {

constexpr int kFft = 960;
constexpr int kBins = kFft / 2 + 1;
constexpr int kErb = 32;
constexpr int kDf = 96;
constexpr int kOrder = 5;
constexpr int kCh = 64;
constexpr int kHidden = 256;
constexpr float kAlpha = 0.99f;
constexpr double kPi = 3.14159265358979323846;

struct Tensor {
    std::vector<int> dims;
    std::vector<float> values;
};

float Half(std::uint16_t h) {
    const float sign = (h & 0x8000u) ? -1.0f : 1.0f;
    const int exponent = (h >> 10) & 0x1f;
    const int mantissa = h & 0x3ff;
    if (exponent == 0) return sign * std::ldexp(float(mantissa), -24);
    if (exponent == 31) return mantissa ? NAN : sign * INFINITY;
    return sign * std::ldexp(float(mantissa | 0x400), exponent - 25);
}

struct Reader {
    const std::uint8_t* p;
    const std::uint8_t* end;
    bool ok = true;

    bool Take(void* out, std::size_t n) {
        if (!ok || std::size_t(end - p) < n) return ok = false;
        std::memcpy(out, p, n);
        p += n;
        return true;
    }
    std::uint32_t U32() { std::uint32_t v = 0; Take(&v, 4); return v; }
    std::uint16_t U16() { std::uint16_t v = 0; Take(&v, 2); return v; }
    std::uint8_t U8() { std::uint8_t v = 0; Take(&v, 1); return v; }
};

bool Parse(const std::uint8_t* data, std::size_t size, std::map<std::string, Tensor>* out) {
    Reader r{data, data + size};
    char magic[4] = {};
    if (!r.Take(magic, 4) || std::memcmp(magic, "AMDF", 4) != 0 || r.U32() != 1) return false;
    const std::uint32_t count = r.U32();
    if (count > 4096) return false;
    for (std::uint32_t t = 0; t < count && r.ok; ++t) {
        std::string name(r.U16(), '\0');
        if (!r.Take(name.data(), name.size())) return false;
        const int type = r.U8();
        const int rank = r.U8();
        if (type > 1 || rank > 4) return false;
        Tensor tensor;
        std::size_t n = 1;
        for (int i = 0; i < rank; ++i) {
            const std::uint32_t d = r.U32();
            if (d == 0 || d > (1u << 20)) return false;
            tensor.dims.push_back(int(d));
            n *= d;
            if (n > (1u << 22)) return false;
        }
        tensor.values.resize(n);
        if (type == 0) {
            if (!r.Take(tensor.values.data(), n * 4)) return false;
        } else {
            std::vector<std::uint16_t> half(n);
            if (!r.Take(half.data(), n * 2)) return false;
            for (std::size_t i = 0; i < n; ++i) tensor.values[i] = Half(half[i]);
        }
        for (float v : tensor.values) if (!std::isfinite(v)) return false;
        (*out)[name] = std::move(tensor);
    }
    return r.ok && r.p == r.end;
}

struct Builder {
    const std::map<std::string, Tensor>& weights;
    bool ok = true;

    std::vector<float> Get(const std::string& name, std::vector<int> dims) {
        std::size_t n = 1;
        for (int d : dims) n *= std::size_t(d);
        auto found = weights.find(name);
        if (found == weights.end() || found->second.dims != dims) {
            ok = false;
            return std::vector<float>(n, 0.0f);
        }
        return found->second.values;
    }
};

enum Act { kRelu, kSigmoid };

struct Conv {
    int in = 0, out = 0, kt = 1, kf = 1, groups = 1, stride = 1, fin = 0, fout = 0;
    bool transposed = false;
    Act act = kRelu;
    std::vector<float> w, pw, scale, bias;

    void Run(const float* const* frames, float* y, float* tmp) const {
        const int ig = in / groups, og = out / groups, pad = kf / 2;
        float* z = pw.empty() ? y : tmp;
        std::fill(z, z + out * fout, 0.0f);
        if (!transposed) {
            for (int o = 0; o < out; ++o) {
                const int g = o / og;
                float* zo = z + o * fout;
                for (int ci = 0; ci < ig; ++ci) {
                    for (int dt = 0; dt < kt; ++dt) {
                        const float* x = frames[dt] + (g * ig + ci) * fin;
                        const float* wk = &w[(std::size_t(o * ig + ci) * kt + dt) * kf];
                        for (int k = 0; k < kf; ++k) {
                            const float wv = wk[k];
                            const int shift = pad - k;
                            const int lo = shift > 0 ? (shift + stride - 1) / stride : 0;
                            const int last = fin - 1 + shift;
                            const int hi = std::min(fout, last >= 0 ? last / stride + 1 : 0);
                            for (int j = lo; j < hi; ++j) zo[j] += wv * x[j * stride - shift];
                        }
                    }
                }
            }
        } else {
            for (int c = 0; c < in; ++c) {
                const int g = c / ig;
                const float* x = frames[0] + c * fin;
                for (int oo = 0; oo < og; ++oo) {
                    float* zo = z + (g * og + oo) * fout;
                    const float* wk = &w[std::size_t(c * og + oo) * kf];
                    for (int k = 0; k < kf; ++k) {
                        const float wv = wk[k];
                        for (int i = 0; i < fin; ++i) {
                            const int j = i * stride - pad + k;
                            if (j >= 0 && j < fout) zo[j] += wv * x[i];
                        }
                    }
                }
            }
        }
        if (!pw.empty()) {
            std::fill(y, y + out * fout, 0.0f);
            for (int o = 0; o < out; ++o) {
                float* yo = y + o * fout;
                for (int c = 0; c < out; ++c) {
                    const float wv = pw[o * out + c];
                    const float* zc = z + c * fout;
                    for (int j = 0; j < fout; ++j) yo[j] += wv * zc[j];
                }
            }
        }
        for (int o = 0; o < out; ++o) {
            float* yo = y + o * fout;
            for (int j = 0; j < fout; ++j) {
                const float v = yo[j] * scale[o] + bias[o];
                yo[j] = act == kRelu ? std::max(v, 0.0f) : 1.0f / (1.0f + std::exp(-v));
            }
        }
    }
};

Conv MakeConv(Builder& b, const std::string& name, int in, int out, int kt, int kf, int groups, int stride, int fin,
              bool transposed, bool pointwise, Act act = kRelu) {
    Conv c;
    c.in = in; c.out = out; c.kt = kt; c.kf = kf; c.groups = groups; c.stride = stride; c.fin = fin;
    c.transposed = transposed; c.act = act;
    c.fout = transposed ? (fin - 1) * stride - 2 * (kf / 2) + kf + kf / 2 : (fin + 2 * (kf / 2) - kf) / stride + 1;
    c.w = transposed ? b.Get(name + ".w", {in, out / groups, kt, kf}) : b.Get(name + ".w", {out, in / groups, kt, kf});
    if (pointwise) c.pw = b.Get(name + ".pw", {out, out, 1, 1});
    c.scale = b.Get(name + ".scale", {out});
    c.bias = b.Get(name + ".bias", {out});
    return c;
}

struct Linear {
    int groups = 1, in = 0, out = 0;
    std::vector<float> w;

    void Run(const float* x, float* y, bool relu) const {
        const int ig = in / groups, og = out / groups;
        std::fill(y, y + out, 0.0f);
        for (int g = 0; g < groups; ++g) {
            const float* wg = &w[std::size_t(g) * ig * og];
            float* yg = y + g * og;
            for (int i = 0; i < ig; ++i) {
                const float xi = x[g * ig + i];
                const float* row = wg + std::size_t(i) * og;
                for (int o = 0; o < og; ++o) yg[o] += xi * row[o];
            }
        }
        if (relu) for (int o = 0; o < out; ++o) y[o] = std::max(y[o], 0.0f);
    }
};

Linear MakeLinear(Builder& b, const std::string& name, int in, int out, int groups) {
    Linear l;
    l.groups = groups; l.in = in; l.out = out;
    l.w = b.Get(name, {groups, in / groups, out / groups});
    return l;
}

struct Gru {
    std::vector<float> wi, wh, bi, bh;

    void Step(const float* x, float* h, float* gi, float* gh) const {
        constexpr int n = kHidden;
        std::copy(bi.begin(), bi.end(), gi);
        std::copy(bh.begin(), bh.end(), gh);
        for (int i = 0; i < n; ++i) {
            const float xi = x[i], hi = h[i];
            const float* a = &wi[std::size_t(i) * 3 * n];
            const float* c = &wh[std::size_t(i) * 3 * n];
            for (int j = 0; j < 3 * n; ++j) { gi[j] += xi * a[j]; gh[j] += hi * c[j]; }
        }
        for (int j = 0; j < n; ++j) {
            const float r = 1.0f / (1.0f + std::exp(-(gi[j] + gh[j])));
            const float z = 1.0f / (1.0f + std::exp(-(gi[n + j] + gh[n + j])));
            const float c = std::tanh(gi[2 * n + j] + r * gh[2 * n + j]);
            h[j] = (1.0f - z) * c + z * h[j];
        }
    }
};

std::vector<float> Transpose(const std::vector<float>& m, int rows, int cols) {
    std::vector<float> t(m.size());
    for (int r = 0; r < rows; ++r) for (int c = 0; c < cols; ++c) t[std::size_t(c) * rows + r] = m[std::size_t(r) * cols + c];
    return t;
}

Gru MakeGru(Builder& b, const std::string& name, int layer) {
    const std::string l = std::to_string(layer);
    Gru g;
    g.wi = Transpose(b.Get(name + ".weight_ih_l" + l, {3 * kHidden, kHidden}), 3 * kHidden, kHidden);
    g.wh = Transpose(b.Get(name + ".weight_hh_l" + l, {3 * kHidden, kHidden}), 3 * kHidden, kHidden);
    g.bi = b.Get(name + ".bias_ih_l" + l, {3 * kHidden});
    g.bh = b.Get(name + ".bias_hh_l" + l, {3 * kHidden});
    return g;
}

}

struct Model {
    std::vector<int> band, width;
    std::vector<float> window;
    Conv erb0, erb1, erb2, erb3, df0, df1;
    Conv conv3p, convt3, conv2p, convt2, conv1p, convt1, conv0p, conv0out, dfConvp;
    Linear fcEmb, encIn, encOut, decIn, decOut, dfIn, dfSkip, dfOut;
    Gru enc, dec0, dec1, df0g, df1g;
};

struct State {
    kiss_fft_state* fft = nullptr;
    std::vector<float> analysis = std::vector<float>(kHop), synthesis = std::vector<float>(kHop);
    std::vector<float> meanNorm = std::vector<float>(kErb), unitNorm = std::vector<float>(kDf);
    std::vector<float> erbHist = std::vector<float>(3 * kErb), specHist = std::vector<float>(3 * 2 * kDf);
    std::vector<float> c0Hist = std::vector<float>(5 * kCh * kDf);
    std::vector<float> spec = std::vector<float>(5 * kBins * 2);
    std::vector<float> hEnc = std::vector<float>(kHidden), hDec0 = std::vector<float>(kHidden), hDec1 = std::vector<float>(kHidden);
    std::vector<float> hDf0 = std::vector<float>(kHidden), hDf1 = std::vector<float>(kHidden);
    std::vector<float> e0 = std::vector<float>(kCh * 32), e1 = std::vector<float>(kCh * 16), e2 = std::vector<float>(kCh * 8);
    std::vector<float> e3 = std::vector<float>(kCh * 8), c1 = std::vector<float>(kCh * 48);
    std::vector<float> tmp = std::vector<float>(kCh * kDf), sum = std::vector<float>(kCh * 32), up = std::vector<float>(kCh * 32);
    std::vector<float> flat = std::vector<float>(kCh * 48), emb = std::vector<float>(512), embEnc = std::vector<float>(512);
    std::vector<float> x = std::vector<float>(kHidden), gi = std::vector<float>(3 * kHidden), gh = std::vector<float>(3 * kHidden);
    std::vector<float> mask = std::vector<float>(kErb), coef = std::vector<float>(kDf * kOrder * 2), conv = std::vector<float>(10 * kDf);
    std::vector<DfComplex> in = std::vector<DfComplex>(kFft), out = std::vector<DfComplex>(kFft);
    long frames = 0;

    ~State() { if (fft) rnn_fft_free(fft, 0); }
};

namespace {

void Network(const Model& m, State& s) {
    const float* erbFrames[3] = {&s.erbHist[0], &s.erbHist[kErb], &s.erbHist[2 * kErb]};
    const float* specFrames[3] = {&s.specHist[0], &s.specHist[2 * kDf], &s.specHist[4 * kDf]};
    const float* one[1];
    m.erb0.Run(erbFrames, s.e0.data(), s.tmp.data());
    one[0] = s.e0.data(); m.erb1.Run(one, s.e1.data(), s.tmp.data());
    one[0] = s.e1.data(); m.erb2.Run(one, s.e2.data(), s.tmp.data());
    one[0] = s.e2.data(); m.erb3.Run(one, s.e3.data(), s.tmp.data());
    const std::size_t plane = std::size_t(kCh) * kDf;
    std::memmove(s.c0Hist.data(), s.c0Hist.data() + plane, 4 * plane * sizeof(float));
    float* c0 = s.c0Hist.data() + 4 * plane;
    m.df0.Run(specFrames, c0, s.tmp.data());
    one[0] = c0; m.df1.Run(one, s.c1.data(), s.tmp.data());
    for (int c = 0; c < kCh; ++c) for (int f = 0; f < 48; ++f) s.flat[f * kCh + c] = s.c1[c * 48 + f];
    m.fcEmb.Run(s.flat.data(), s.emb.data(), true);
    for (int c = 0; c < kCh; ++c) for (int f = 0; f < 8; ++f) s.emb[f * kCh + c] += s.e3[c * 8 + f];
    m.encIn.Run(s.emb.data(), s.x.data(), true);
    m.enc.Step(s.x.data(), s.hEnc.data(), s.gi.data(), s.gh.data());
    m.encOut.Run(s.hEnc.data(), s.embEnc.data(), true);

    m.decIn.Run(s.embEnc.data(), s.x.data(), true);
    m.dec0.Step(s.x.data(), s.hDec0.data(), s.gi.data(), s.gh.data());
    m.dec1.Step(s.hDec0.data(), s.hDec1.data(), s.gi.data(), s.gh.data());
    m.decOut.Run(s.hDec1.data(), s.flat.data(), true);
    float* sum = s.sum.data();
    float* up = s.up.data();
    one[0] = s.e3.data(); m.conv3p.Run(one, sum, s.tmp.data());
    for (int c = 0; c < kCh; ++c) for (int f = 0; f < 8; ++f) sum[c * 8 + f] += s.flat[f * kCh + c];
    one[0] = sum; m.convt3.Run(one, up, s.tmp.data());
    one[0] = s.e2.data(); m.conv2p.Run(one, sum, s.tmp.data());
    for (int i = 0; i < kCh * 8; ++i) sum[i] += up[i];
    one[0] = sum; m.convt2.Run(one, up, s.tmp.data());
    one[0] = s.e1.data(); m.conv1p.Run(one, sum, s.tmp.data());
    for (int i = 0; i < kCh * 16; ++i) sum[i] += up[i];
    one[0] = sum; m.convt1.Run(one, up, s.tmp.data());
    one[0] = s.e0.data(); m.conv0p.Run(one, sum, s.tmp.data());
    for (int i = 0; i < kCh * 32; ++i) sum[i] += up[i];
    one[0] = sum; m.conv0out.Run(one, s.mask.data(), s.tmp.data());

    m.dfIn.Run(s.embEnc.data(), s.x.data(), true);
    m.df0g.Step(s.x.data(), s.hDf0.data(), s.gi.data(), s.gh.data());
    m.df1g.Step(s.hDf0.data(), s.hDf1.data(), s.gi.data(), s.gh.data());
    m.dfSkip.Run(s.embEnc.data(), s.x.data(), false);
    for (int i = 0; i < kHidden; ++i) s.x[i] += s.hDf1[i];
    m.dfOut.Run(s.x.data(), s.coef.data(), false);
    const float* c0Frames[5];
    for (int i = 0; i < 5; ++i) c0Frames[i] = s.c0Hist.data() + i * plane;
    m.dfConvp.Run(c0Frames, s.conv.data(), s.tmp.data());
    for (int f = 0; f < kDf; ++f)
        for (int c = 0; c < 2 * kOrder; ++c) s.coef[f * 10 + c] = std::tanh(s.coef[f * 10 + c]) + s.conv[c * kDf + f];
}

}

std::unique_ptr<Denoiser> Denoiser::Load(const std::uint8_t* data, std::size_t size) {
    std::map<std::string, Tensor> weights;
    if (!data || !Parse(data, size, &weights)) return nullptr;
    Builder b{weights};
    auto m = std::make_unique<Model>();
    const std::vector<float> widths = b.Get("erb", {kErb});
    for (int b = 0; b < kErb; ++b) {
        const int n = int(widths[b]);
        if (n < 1 || int(m->band.size()) + n > kBins) return nullptr;
        m->width.push_back(n);
        m->band.insert(m->band.end(), n, b);
    }
    if (int(m->band.size()) != kBins) return nullptr;
    m->window.resize(kFft);
    for (int i = 0; i < kFft; ++i) {
        const double s = std::sin(0.5 * kPi * (i + 0.5) / (kFft / 2));
        m->window[i] = float(std::sin(0.5 * kPi * s * s));
    }
    m->erb0 = MakeConv(b, "enc.erb_conv0", 1, kCh, 3, 3, 1, 1, 32, false, false);
    m->erb1 = MakeConv(b, "enc.erb_conv1", kCh, kCh, 1, 3, kCh, 2, 32, false, true);
    m->erb2 = MakeConv(b, "enc.erb_conv2", kCh, kCh, 1, 3, kCh, 2, 16, false, true);
    m->erb3 = MakeConv(b, "enc.erb_conv3", kCh, kCh, 1, 3, kCh, 1, 8, false, true);
    m->df0 = MakeConv(b, "enc.df_conv0", 2, kCh, 3, 3, 2, 1, kDf, false, true);
    m->df1 = MakeConv(b, "enc.df_conv1", kCh, kCh, 1, 3, kCh, 2, kDf, false, true);
    m->conv3p = MakeConv(b, "erb_dec.conv3p", kCh, kCh, 1, 1, kCh, 1, 8, false, false);
    m->convt3 = MakeConv(b, "erb_dec.convt3", kCh, kCh, 1, 3, kCh, 1, 8, false, true);
    m->conv2p = MakeConv(b, "erb_dec.conv2p", kCh, kCh, 1, 1, kCh, 1, 8, false, false);
    m->convt2 = MakeConv(b, "erb_dec.convt2", kCh, kCh, 1, 3, kCh, 2, 8, true, true);
    m->conv1p = MakeConv(b, "erb_dec.conv1p", kCh, kCh, 1, 1, kCh, 1, 16, false, false);
    m->convt1 = MakeConv(b, "erb_dec.convt1", kCh, kCh, 1, 3, kCh, 2, 16, true, true);
    m->conv0p = MakeConv(b, "erb_dec.conv0p", kCh, kCh, 1, 1, kCh, 1, 32, false, false);
    m->conv0out = MakeConv(b, "erb_dec.conv0_out", kCh, 1, 1, 3, 1, 1, 32, false, false, kSigmoid);
    m->dfConvp = MakeConv(b, "df_dec.df_convp", kCh, 2 * kOrder, 5, 1, 2, 1, kDf, false, true);
    m->fcEmb = MakeLinear(b, "enc.df_fc_emb.0.weight", kCh * 48, 512, 32);
    m->encIn = MakeLinear(b, "enc.emb_gru.linear_in.0.weight", 512, kHidden, 16);
    m->encOut = MakeLinear(b, "enc.emb_gru.linear_out.0.weight", kHidden, 512, 16);
    m->decIn = MakeLinear(b, "erb_dec.emb_gru.linear_in.0.weight", 512, kHidden, 16);
    m->decOut = MakeLinear(b, "erb_dec.emb_gru.linear_out.0.weight", kHidden, 512, 16);
    m->dfIn = MakeLinear(b, "df_dec.df_gru.linear_in.0.weight", 512, kHidden, 8);
    m->dfSkip = MakeLinear(b, "df_dec.df_skip.weight", 512, kHidden, 16);
    m->dfOut = MakeLinear(b, "df_dec.df_out.0.weight", kHidden, kDf * kOrder * 2, 16);
    m->enc = MakeGru(b, "enc.emb_gru.gru", 0);
    m->dec0 = MakeGru(b, "erb_dec.emb_gru.gru", 0);
    m->dec1 = MakeGru(b, "erb_dec.emb_gru.gru", 1);
    m->df0g = MakeGru(b, "df_dec.df_gru.gru", 0);
    m->df1g = MakeGru(b, "df_dec.df_gru.gru", 1);
    if (!b.ok) return nullptr;
    auto s = std::make_unique<State>();
    s->fft = rnn_fft_alloc(kFft, nullptr, nullptr, 0);
    if (!s->fft) return nullptr;
    for (int i = 0; i < kErb; ++i) s->meanNorm[i] = -60.0f + (-30.0f) * i / (kErb - 1);
    for (int i = 0; i < kDf; ++i) s->unitNorm[i] = 0.001f + (0.0001f - 0.001f) * i / (kDf - 1);
    std::unique_ptr<Denoiser> d(new Denoiser());
    d->model_ = std::move(m);
    d->state_ = std::move(s);
    return d;
}

Denoiser::~Denoiser() = default;

void Denoiser::Process(const float* input, float* output) {
    const Model& m = *model_;
    State& s = *state_;
    for (int i = 0; i < kHop; ++i) {
        s.in[i] = {s.analysis[i] * m.window[i], 0.0f};
        s.in[kHop + i] = {input[i] * m.window[kHop + i], 0.0f};
    }
    std::copy(input, input + kHop, s.analysis.begin());
    rnn_fft_c(s.fft, s.in.data(), s.out.data());
    std::memmove(s.spec.data(), s.spec.data() + 2 * kBins, 4 * 2 * kBins * sizeof(float));
    float* x = s.spec.data() + 4 * 2 * kBins;
    for (int f = 0; f < kBins; ++f) { x[2 * f] = s.out[f].r; x[2 * f + 1] = s.out[f].i; }

    const bool started = ++s.frames > 2;
    if (started) {
        std::memmove(s.erbHist.data(), s.erbHist.data() + kErb, 2 * kErb * sizeof(float));
        std::memmove(s.specHist.data(), s.specHist.data() + 2 * kDf, 4 * kDf * sizeof(float));
    }
    float* erb = s.erbHist.data() + 2 * kErb;
    float* feat = s.specHist.data() + 4 * kDf;
    std::fill(erb, erb + kErb, 0.0f);
    for (int f = 0; f < kBins; ++f) erb[m.band[f]] += x[2 * f] * x[2 * f] + x[2 * f + 1] * x[2 * f + 1];
    for (int b = 0; b < kErb; ++b) {
        const float v = std::log10(erb[b] / m.width[b] + 1e-10f) * 10.0f;
        s.meanNorm[b] = v * (1.0f - kAlpha) + s.meanNorm[b] * kAlpha;
        erb[b] = (v - s.meanNorm[b]) / 40.0f;
    }
    for (int f = 0; f < kDf; ++f) {
        const float re = x[2 * f], im = x[2 * f + 1];
        s.unitNorm[f] = std::sqrt(re * re + im * im) * (1.0f - kAlpha) + s.unitNorm[f] * kAlpha;
        const float scale = 1.0f / std::sqrt(s.unitNorm[f]);
        feat[f] = re * scale;
        feat[kDf + f] = im * scale;
    }
    if (!started) {
        std::fill(erb, erb + kErb, 0.0f);
        std::fill(feat, feat + 2 * kDf, 0.0f);
        std::fill(output, output + kHop, 0.0f);
        return;
    }
    Network(m, s);

    const float* spec = s.spec.data();
    const float* mid = spec + 2 * 2 * kBins;
    for (int f = 0; f < kBins; ++f) {
        float re, im;
        if (f < kDf) {
            re = im = 0.0f;
            for (int n = 0; n < kOrder; ++n) {
                const float sr = spec[n * 2 * kBins + 2 * f], si = spec[n * 2 * kBins + 2 * f + 1];
                const float cr = s.coef[f * 10 + 2 * n], ci = s.coef[f * 10 + 2 * n + 1];
                re += sr * cr - si * ci;
                im += sr * ci + si * cr;
            }
        } else {
            const float g = s.mask[m.band[f]];
            re = mid[2 * f] * g;
            im = mid[2 * f + 1] * g;
        }
        s.in[f] = {re, f == 0 || f == kBins - 1 ? 0.0f : im};
        if (f > 0 && f < kBins - 1) s.in[kFft - f] = {re, -im};
    }
    rnn_ifft_c(s.fft, s.in.data(), s.out.data());
    for (int i = 0; i < kHop; ++i) {
        output[i] = s.out[i].r * m.window[i] + s.synthesis[i];
        s.synthesis[i] = s.out[kHop + i].r * m.window[kHop + i];
    }
}

}
