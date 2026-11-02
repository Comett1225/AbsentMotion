#include "absent_bg.h"
#include <algorithm>
#include <cmath>
#include <cstring>
#include <fstream>
#include <limits>
#include <stdexcept>
#if defined(__aarch64__)
#include <arm_neon.h>
#endif
namespace absent {
Tensor::Tensor(int channels,int height,int width):c(channels),h(height),w(width) {
    if(c<1||h<1||w<1||int64_t(c)*h*w>100000000) throw std::runtime_error("Tensor size out of range");
    data.resize(size_t(c)*h*w);
}
static float half_to_float(uint16_t v) {
    int sign=v>>15,exponent=(v>>10)&31,mantissa=v&1023;
    float x=exponent==0?std::ldexp(float(mantissa),-24):exponent==31?(mantissa?std::numeric_limits<float>::quiet_NaN():std::numeric_limits<float>::infinity()):std::ldexp(float(mantissa+1024),exponent-25);
    return sign?-x:x;
}
struct Node {
    int kind=0,a=0,b=-1,ci=0,co=0,k=0,s=0,g=0,act=0;
    std::vector<float> weights,bias;
};
struct Model::Impl {
    int size=320;
    std::vector<Node> nodes;
};
static uint32_t read_u32(std::ifstream& f) {
    uint8_t b[4];f.read(reinterpret_cast<char*>(b),4);
    if(!f) throw std::runtime_error("Truncated model");
    return uint32_t(b[0])|(uint32_t(b[1])<<8)|(uint32_t(b[2])<<16)|(uint32_t(b[3])<<24);
}
static float read_float(std::ifstream& f) {
    uint32_t b=read_u32(f);float v;std::memcpy(&v,&b,4);
    if(!std::isfinite(v)) throw std::runtime_error("Nonfinite model weight");
    return v;
}
Model::Model(const std::string& path):impl(new Impl) {
    std::ifstream f(path,std::ios::binary);
    char magic[4];f.read(magic,4);
    if(!f||std::memcmp(magic,"ABG1",4)!=0) throw std::runtime_error("Invalid ABG1 model");
    auto ver=read_u32(f);impl->size=read_u32(f);auto count=read_u32(f),dt=read_u32(f);
    if((ver!=1&&ver!=2)||impl->size<32||impl->size>1024||count>512||count==0||dt>2) throw std::runtime_error("Unsupported model header");
    uint64_t total_weights=0;
    for(uint32_t i=0;i<count;++i) {
        Node n;n.kind=int32_t(read_u32(f));n.a=int32_t(read_u32(f));n.b=int32_t(read_u32(f));read_u32(f);
        if(n.kind<0||n.kind>11||n.a<0||n.a>int(i)||n.b>int(i)||((n.kind==1||n.kind==2||n.kind==4||n.kind==9)&&n.b<0)) throw std::runtime_error("Invalid graph");
        if(n.kind==0) {
            n.ci=read_u32(f);n.co=read_u32(f);n.k=read_u32(f);n.s=read_u32(f);n.g=read_u32(f);n.act=read_u32(f);auto cnt=read_u32(f);
            if(n.ci<1||n.ci>4096||n.co<1||n.co>4096||(n.k!=1&&n.k!=3&&n.k!=5&&n.k!=7)||n.s<1||n.s>2||n.g<1||n.ci%n.g||n.co%n.g||n.act>1||cnt!=uint64_t(n.co)*(n.ci/n.g)*n.k*n.k) throw std::runtime_error("Invalid convolution");
            total_weights+=cnt;
            if(total_weights>16000000) throw std::runtime_error("Model exceeds weight limit");
            n.bias.resize(n.co);for(auto& v:n.bias)v=read_float(f);
            n.weights.resize(cnt);
            if(dt==0)for(auto& v:n.weights)v=read_float(f);
            if(dt==1)for(auto& v:n.weights){uint8_t b[2];f.read(reinterpret_cast<char*>(b),2);v=half_to_float(uint16_t(b[0])|(uint16_t(b[1])<<8));if(!std::isfinite(v))throw std::runtime_error("Nonfinite half weight");}
            if(dt==2) {
                std::vector<float> scales(n.co);for(auto& v:scales){v=read_float(f);if(v<=0)throw std::runtime_error("Invalid quantization scale");}
                for(uint32_t j=0;j<cnt;++j){int8_t v=0;f.read(reinterpret_cast<char*>(&v),1);n.weights[j]=float(v)*scales[j/(cnt/n.co)];}
            }
        }
        if(n.kind==11){n.ci=read_u32(f);if(n.ci<1||n.ci>4096)throw std::runtime_error("Affine channel limit");n.weights.resize(n.ci);n.bias.resize(n.ci);for(auto& v:n.weights)v=read_float(f);for(auto& v:n.bias)v=read_float(f);}
        if(!f) throw std::runtime_error("Truncated weights");
        impl->nodes.push_back(std::move(n));
    }
    char extra;
    if(f.read(&extra,1)) throw std::runtime_error("Trailing model data");
}
Model::~Model()=default;
Model::Model(Model&&) noexcept=default;
Model& Model::operator=(Model&&) noexcept=default;
int Model::input_size() const{return impl->size;}
static Tensor convolve(const Tensor& x,const Node& n) {
    if(x.c!=n.ci)throw std::runtime_error("Convolution channel mismatch");
    int h=(x.h+2*(n.k/2)-n.k)/n.s+1,w=(x.w+2*(n.k/2)-n.k)/n.s+1;
    Tensor out(n.co,h,w);int nc=n.ci/n.g,oc=n.co/n.g,pad=n.k/2;
#if defined(__aarch64__)
    if(n.k==1&&n.s==1&&n.g==1) {
        int pixels=h*w,o=0;
        for(;o+3<n.co;o+=4) {
            int j=0;
            for(;j+3<pixels;j+=4) {
                float32x4_t a=vdupq_n_f32(n.bias[o]),b=vdupq_n_f32(n.bias[o+1]),c=vdupq_n_f32(n.bias[o+2]),d=vdupq_n_f32(n.bias[o+3]);
                for(int ic=0;ic<n.ci;++ic) {
                    auto v=vld1q_f32(x.data.data()+size_t(ic)*pixels+j);
                    a=vfmaq_n_f32(a,v,n.weights[o*n.ci+ic]);b=vfmaq_n_f32(b,v,n.weights[(o+1)*n.ci+ic]);c=vfmaq_n_f32(c,v,n.weights[(o+2)*n.ci+ic]);d=vfmaq_n_f32(d,v,n.weights[(o+3)*n.ci+ic]);
                }
                if(n.act){auto z=vdupq_n_f32(0);a=vmaxq_f32(a,z);b=vmaxq_f32(b,z);c=vmaxq_f32(c,z);d=vmaxq_f32(d,z);}
                vst1q_f32(out.data.data()+size_t(o)*pixels+j,a);vst1q_f32(out.data.data()+size_t(o+1)*pixels+j,b);vst1q_f32(out.data.data()+size_t(o+2)*pixels+j,c);vst1q_f32(out.data.data()+size_t(o+3)*pixels+j,d);
            }
            for(;j<pixels;++j)for(int q=0;q<4;++q){float v=n.bias[o+q];for(int ic=0;ic<n.ci;++ic)v+=x.data[size_t(ic)*pixels+j]*n.weights[(o+q)*n.ci+ic];out.data[size_t(o+q)*pixels+j]=n.act?std::max(v,0.f):v;}
        }
        for(;o<n.co;++o)for(int j=0;j<pixels;++j){float v=n.bias[o];for(int ic=0;ic<n.ci;++ic)v+=x.data[size_t(ic)*pixels+j]*n.weights[o*n.ci+ic];out.data[size_t(o)*pixels+j]=n.act?std::max(v,0.f):v;}
        return out;
    }
#endif
    for(int o=0;o<n.co;++o) {
        float* dst=out.data.data()+size_t(o)*h*w;
        std::fill(dst,dst+size_t(h)*w,n.bias[o]);
        for(int ic=0;ic<nc;++ic)for(int ky=0;ky<n.k;++ky)for(int kx=0;kx<n.k;++kx) {
            float weight=n.weights[((o*nc+ic)*n.k+ky)*n.k+kx];
            const float* src=x.data.data()+size_t((o/oc)*nc+ic)*x.h*x.w;
            for(int y=0;y<h;++y) {
                int iy=y*n.s+ky-pad;if(iy<0||iy>=x.h)continue;
                float* row=dst+size_t(y)*w;const float* in=src+size_t(iy)*x.w;
                int j=0;
                while(j<w&&j*n.s+kx-pad<0)++j;
#if defined(__aarch64__)
                float32x4_t ww=vdupq_n_f32(weight);
                for(;j+3<w&&(j+3)*n.s+kx-pad<x.w;++j) {
                    int ix=j*n.s+kx-pad;
                    if(n.s==2&&ix+7>=x.w)break;
                    float32x4_t v=n.s==1?vld1q_f32(in+ix):vld2q_f32(in+ix).val[0];
                    vst1q_f32(row+j,vfmaq_f32(vld1q_f32(row+j),v,ww));j+=3;
                }
#endif
                for(;j<w;++j){int ix=j*n.s+kx-pad;if(ix>=0&&ix<x.w)row[j]+=in[ix]*weight;}
            }
        }
        if(n.act)for(int j=0;j<h*w;++j)dst[j]=std::max(dst[j],0.f);
    }
    return out;
}
Tensor Model::forward(const Tensor& input) const {
    if(input.c!=3||input.h!=impl->size||input.w!=impl->size)throw std::runtime_error("Expected fixed-size RGB CHW tensor");
    std::vector<Tensor> vals(impl->nodes.size()+1);vals[0]=input;
    std::vector<int> uses(vals.size(),0);
    for(const auto& n:impl->nodes){++uses[n.a];if(n.b>=0)++uses[n.b];}
    for(size_t i=0;i<impl->nodes.size();++i) {
        const auto& n=impl->nodes[i];const auto& x=vals[n.a];Tensor out;
        if(n.kind==0)out=convolve(x,n);
        else if(n.kind==1){const auto& z=vals[n.b];if(x.c!=z.c||x.h!=z.h||x.w!=z.w)throw std::runtime_error("Add shape mismatch");out=x;for(size_t j=0;j<out.data.size();++j)out.data[j]+=z.data[j];}
        else if(n.kind==2){const auto& z=vals[n.b];if(x.h!=z.h||x.w!=z.w)throw std::runtime_error("Concat shape mismatch");out=Tensor(x.c+z.c,x.h,x.w);std::copy(x.data.begin(),x.data.end(),out.data.begin());std::copy(z.data.begin(),z.data.end(),out.data.begin()+x.data.size());}
        else if(n.kind==3){out=x;for(auto& v:out.data)v=std::max(v,0.f);}
        else if(n.kind==4){const auto& z=vals[n.b];out=Tensor(x.c,z.h,z.w);for(int c=0;c<x.c;++c)for(int y=0;y<out.h;++y)for(int j=0;j<out.w;++j)out.data[(size_t(c)*out.h+y)*out.w+j]=x.data[(size_t(c)*x.h+y*x.h/out.h)*x.w+j*x.w/out.w];}
        else if(n.kind==8){out=Tensor(x.c,1,1);for(int c=0;c<x.c;++c){double sum=0;for(int j=0;j<x.h*x.w;++j)sum+=x.data[size_t(c)*x.h*x.w+j];out.data[c]=float(sum/(x.h*x.w));}}
        else if(n.kind==9){const auto& z=vals[n.b];if(x.c!=z.c||!((x.h==z.h&&x.w==z.w)||(z.h==1&&z.w==1)||(x.h==1&&x.w==1)))throw std::runtime_error("Multiply broadcast mismatch");out=Tensor(x.c,std::max(x.h,z.h),std::max(x.w,z.w));for(int c=0;c<out.c;++c)for(int j=0;j<out.h*out.w;++j)out.data[size_t(c)*out.h*out.w+j]=x.data[size_t(c)*x.h*x.w+(x.h*x.w==1?0:j)]*z.data[size_t(c)*z.h*z.w+(z.h*z.w==1?0:j)];}
        else if(n.kind==11){if(x.c!=n.ci)throw std::runtime_error("Affine channel mismatch");out=x;for(int c=0;c<x.c;++c)for(int j=0;j<x.h*x.w;++j)out.data[size_t(c)*x.h*x.w+j]=x.data[size_t(c)*x.h*x.w+j]*n.weights[c]+n.bias[c];}
        else {out=x;for(auto& v:out.data){if(n.kind==5)v=std::clamp(v,0.f,6.f);else if(n.kind==6)v=v*std::clamp(v+3,0.f,6.f)/6;else if(n.kind==7)v=std::clamp(v+3,0.f,6.f)/6;else if(n.kind==10)v=1/(1+std::exp(-v));else throw std::runtime_error("Unknown operation");}}
        vals[i+1]=std::move(out);
        if(--uses[n.a]==0)vals[n.a]=Tensor();
        if(n.b>=0&&--uses[n.b]==0)vals[n.b]=Tensor();
    }
    return std::move(vals.back());
}
static float sample(const float* a,int h,int w,float y,float x) {
    y=std::max(0.f,std::min(y,float(h-1)));x=std::max(0.f,std::min(x,float(w-1)));
    int y0=int(y),x0=int(x),y1=std::min(y0+1,h-1),x1=std::min(x0+1,w-1);float fy=y-y0,fx=x-x0;
    return (a[y0*w+x0]*(1-fx)+a[y0*w+x1]*fx)*(1-fy)+(a[y1*w+x0]*(1-fx)+a[y1*w+x1]*fx)*fy;
}
static std::vector<float> boxmean(const std::vector<float>& a,int h,int w) {
    std::vector<float> tmp(a.size()),out(a.size());
    for(int y=0;y<h;++y){double sum=0;for(int j=-4;j<=4;++j)sum+=a[y*w+std::clamp(j,0,w-1)];for(int x=0;x<w;++x){tmp[y*w+x]=float(sum/9);sum+=a[y*w+std::min(x+5,w-1)]-a[y*w+std::max(x-4,0)];}}
    for(int x=0;x<w;++x){double sum=0;for(int j=-4;j<=4;++j)sum+=tmp[std::clamp(j,0,h-1)*w+x];for(int y=0;y<h;++y){out[y*w+x]=float(sum/9);sum+=tmp[std::min(y+5,h-1)*w+x]-tmp[std::max(y-4,0)*w+x];}}
    return out;
}
std::vector<uint8_t> Model::matte(const uint8_t* rgb,int width,int height) const {
    if(!rgb||width<1||height<1||int64_t(width)*height>100000000)throw std::runtime_error("Invalid RGB image dimensions");
    int size=impl->size;float scale=std::min(float(size)/width,float(size)/height);
    int nw=std::max(1,int(width*scale+.5f)),nh=std::max(1,int(height*scale+.5f)),left=(size-nw)/2,top=(size-nh)/2;
    Tensor input(3,size,size);std::fill(input.data.begin(),input.data.end(),.5f);
    std::vector<float> gray(size_t(nh)*nw);
    if(nw*2<=width&&nh*2<=height) {
        for(int y=0;y<nh;++y)for(int x=0;x<nw;++x) {
            int y0=int(int64_t(y)*height/nh),y1=std::max(y0+1,int(int64_t(y+1)*height/nh)),x0=int(int64_t(x)*width/nw),x1=std::max(x0+1,int(int64_t(x+1)*width/nw));
            double sum[3]={0,0,0};
            for(int sy=y0;sy<y1;++sy)for(int sx=x0;sx<x1;++sx)for(int c=0;c<3;++c)sum[c]+=rgb[(size_t(sy)*width+sx)*3+c];
            float g=0,k=1.f/(255.f*float((y1-y0)*(x1-x0)));
            for(int c=0;c<3;++c){float v=float(sum[c])*k;input.data[(size_t(c)*size+top+y)*size+left+x]=v;g+=v*(c==0?.299f:c==1?.587f:.114f);}
            gray[y*nw+x]=g;
        }
    } else for(int y=0;y<nh;++y)for(int x=0;x<nw;++x) {
        float sy=std::max((y+.5f)*height/nh-.5f,0.f),sx=std::max((x+.5f)*width/nw-.5f,0.f);
        int y0=std::min(int(sy),height-1),x0=std::min(int(sx),width-1),y1=std::min(y0+1,height-1),x1=std::min(x0+1,width-1);float fy=sy-y0,fx=sx-x0;
        float g=0;
        for(int c=0;c<3;++c){float v=((rgb[(size_t(y0)*width+x0)*3+c]*(1-fx)+rgb[(size_t(y0)*width+x1)*3+c]*fx)*(1-fy)+(rgb[(size_t(y1)*width+x0)*3+c]*(1-fx)+rgb[(size_t(y1)*width+x1)*3+c]*fx)*fy)/255;input.data[(size_t(c)*size+top+y)*size+left+x]=v;g+=v*(c==0?.299f:c==1?.587f:.114f);}
        gray[y*nw+x]=g;
    }
    Tensor alpha=forward(input);
    if(alpha.c!=1)throw std::runtime_error("Expected one alpha channel");
    for(auto& v:alpha.data)v=1.f/(1.f+std::exp(-v));
    std::vector<float> low(gray.size()),ip(gray.size()),ii(gray.size());
    for(int y=0;y<nh;++y)for(int x=0;x<nw;++x){int j=y*nw+x;low[j]=sample(alpha.data.data(),alpha.h,alpha.w,(top+y+.5f)*alpha.h/size-.5f,(left+x+.5f)*alpha.w/size-.5f);ip[j]=gray[j]*low[j];ii[j]=gray[j]*gray[j];}
    auto mi=boxmean(gray,nh,nw),mp=boxmean(low,nh,nw),mip=boxmean(ip,nh,nw),mii=boxmean(ii,nh,nw);
    for(size_t j=0;j<gray.size();++j){ip[j]=(mip[j]-mi[j]*mp[j])/(mii[j]-mi[j]*mi[j]+.001f);ii[j]=mp[j]-ip[j]*mi[j];}
    auto a=boxmean(ip,nh,nw),b=boxmean(ii,nh,nw);
    std::vector<uint8_t> out(size_t(width)*height);
    for(int y=0;y<height;++y)for(int x=0;x<width;++x){size_t j=size_t(y)*width+x;float g=(rgb[j*3]*.299f+rgb[j*3+1]*.587f+rgb[j*3+2]*.114f)/255;float sy=(y+.5f)*nh/height-.5f,sx=(x+.5f)*nw/width-.5f;float v=sample(a.data(),nh,nw,sy,sx)*g+sample(b.data(),nh,nw,sy,sx);out[j]=uint8_t(std::floor(std::clamp(v,0.f,1.f)*255+.5f));}
    return out;
}
}
