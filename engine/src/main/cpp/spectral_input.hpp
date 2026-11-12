// Runtime counterpart of spectral_upsampling.py and gamut_compression.py.
#pragma once
#include <algorithm>
#include <array>
#include <cmath>
#include <limits>
#include <vector>
#include "parallel.hpp"
#include "spektra_engine.hpp"

namespace spk {
struct SpectralInputSetup {
    std::vector<float> tc;
    double mallett[9] = {};
    bool use_mallett = false;
    int size = 0;
};

namespace spectral_detail {
inline double knee(double v, const float p[3]) {
    if (v <= p[0]) return v;
    const double scale = p[1] - p[0], x = (v - p[0]) / scale;
    return p[0] + scale * x / std::pow(1 + std::pow(x, p[2]), 1.0 / p[2]);
}
inline void tc_to_xy(double u, double v, double& x, double& y) {
    const double r = std::sqrt(u); x = 1 - r; y = v * r;
}
inline void xy_to_tc(double x, double y, double& u, double& v) {
    const double a = 1 - x;
    u = std::clamp(a * a, 0.0, 1.0);
    v = std::clamp(y / std::max(a, 1e-10), 0.0, 1.0);
}
inline void xyz_to_lab(const double xyz[3], double lab[3]) {
    const double l = std::cbrt(.8189330101 * xyz[0] + .3618667424 * xyz[1] - .1288597137 * xyz[2]);
    const double m = std::cbrt(.0329845436 * xyz[0] + .9293118715 * xyz[1] + .0361456387 * xyz[2]);
    const double s = std::cbrt(.0482003018 * xyz[0] + .2643662691 * xyz[1] + .6338517070 * xyz[2]);
    lab[0] = .2104542553*l + .7936177850*m - .0040720468*s;
    lab[1] = 1.9779984951*l - 2.4285922050*m + .4505937099*s;
    lab[2] = .0259040371*l + .7827717662*m - .8086757660*s;
}
inline void lab_to_xyz(const double lab[3], double xyz[3]) {
    // Inverse matrices used by colour.Oklab_to_XYZ (full inverse precision).
    double l = .9999999984505198*lab[0] + .3963377921737679*lab[1] + .2158037580607588*lab[2];
    double m = 1.0000000088817607*lab[0] - .1055613423236563*lab[1] - .0638541747717059*lab[2];
    double s = 1.0000000546724109*lab[0] - .0894841820949657*lab[1] - 1.2914855378640917*lab[2];
    l=l*l*l; m=m*m*m; s=s*s*s;
    xyz[0]=1.2270138511035211*l-.5577999806518222*m+.2812561489664678*s;
    xyz[1]=-.0405801784232806*l+1.1122568696168300*m-.0716766786656012*s;
    xyz[2]=-.0763812845057069*l-.4214819784180127*m+1.5861632204407947*s;
}
inline void compress_xy(const EngineData& data, const Illuminant& il,
                        const SpectralInputParams& p, double& x, double& y) {
    if (!p.gamut_active) return;
    if (p.gamut_algorithm == "oklch") {
        const double xyz[3] = {x / std::max(y,1e-12), 1, (1-x-y) / std::max(y,1e-12)};
        double lab[3]; xyz_to_lab(xyz, lab);
        const double C=std::hypot(lab[1],lab[2]), h=std::atan2(lab[2],lab[1]);
        const int nl=(int)data.input_oklch_l.size(), nh=(int)data.input_oklch_h.size();
        const double L=std::clamp(lab[0],(double)data.input_oklch_l.front(),(double)data.input_oklch_l.back());
        const double li=(L-data.input_oklch_l.front())/(data.input_oklch_l.back()-data.input_oklch_l.front())*(nl-1);
        const int l0=std::clamp((int)std::floor(li),0,nl-2); const double lf=li-l0;
        const double hi=(h-data.input_oklch_h.front())/(data.input_oklch_h[1]-data.input_oklch_h[0]);
        const int hb=(int)std::floor(hi), h0=(hb%nh+nh)%nh, h1=(h0+1)%nh; const double hf=hi-hb;
        auto at=[&](int l,int a){return data.input_oklch_cmax[(size_t)l*nh+a];};
        const double cm=std::max((at(l0,h0)*(1-hf)+at(l0,h1)*hf)*(1-lf)+(at(l0+1,h0)*(1-hf)+at(l0+1,h1)*hf)*lf,1e-9);
        const double cn=knee(C/cm,p.gamut_knee)*cm;
        lab[1]=cn*std::cos(h); lab[2]=cn*std::sin(h);
        double out[3]; lab_to_xyz(lab,out); const double sum=std::max(out[0]+out[1]+out[2],1e-12);
        x=out[0]/sum; y=out[1]/sum; return;
    }
    const double dx=x-il.xy[0],dy=y-il.xy[1],dist=std::hypot(dx,dy);
    if(dist<1e-9) return;
    const double ux=dx/std::max(dist,1e-12),uy=dy/std::max(dist,1e-12);
    double boundary=std::numeric_limits<double>::infinity();
    for(size_t i=0;i+3<data.input_locus_xy.size();i+=2) {
        const double ax=data.input_locus_xy[i],ay=data.input_locus_xy[i+1];
        const double ex=data.input_locus_xy[i+2]-ax,ey=data.input_locus_xy[i+3]-ay;
        const double den=ux*ey-uy*ex;
        if(std::abs(den)<=1e-12) continue;
        const double ox=il.xy[0]-ax,oy=il.xy[1]-ay;
        const double t=(-ox*ey+oy*ex)/den,s=(-ox*uy+oy*ux)/den;
        if(t>1e-9&&s>=0&&s<=1) boundary=std::min(boundary,t);
    }
    const double d=knee(dist/std::max(boundary,1e-12),p.gamut_knee)*boundary;
    x=il.xy[0]+ux*d; y=il.xy[1]+uy*d;
}
inline double surface(double u,double v,const float* p,double cu,double cv) {
    const double x=u-cu,y=v-cv,x2=x*x,y2=y*y;
    const double raw=p[1]*x+p[2]*y+p[3]*x2+p[4]*y2+p[5]*x*y+
        p[6]*x2*x+p[7]*y2*y+p[8]*x2*y+p[9]*x*y2+p[10]*x2*x2+
        p[11]*y2*y2+p[12]*x2*x*y+p[13]*x2*y2+p[14]*x*y2*y;
    return raw/std::sqrt(1+(raw*.5)*(raw*.5));
}
inline int reflect(int x,int n){while(x<0||x>=n)x=x<0?-x-1:2*n-x-1;return x;}
}

inline SpectralInputSetup build_spectral_input(const EngineData& data,const FilmProfile& film,const SpectralInputParams& p) {
    SpectralInputSetup out;
    if(p.method!="hanatos2025"&&p.method!="mallett2019") throw std::string("invalid rgb_to_raw_method");
    if(p.gamut_algorithm!="xy"&&p.gamut_algorithm!="oklch") throw std::string("invalid input gamut algorithm");
    auto finite=[](float v){return std::isfinite(v);};
    for(float v:p.filter_uv)if(!finite(v))throw std::string("nonfinite camera UV filter");
    for(float v:p.filter_ir)if(!finite(v))throw std::string("nonfinite camera IR filter");
    if(!finite(p.spectral_gaussian_blur)||p.spectral_gaussian_blur<0||p.spectral_gaussian_blur>100)
        throw std::string("invalid spectral Gaussian blur");
    if(!finite(p.gamut_knee[0])||!finite(p.gamut_knee[1])||!finite(p.gamut_knee[2])||
       p.gamut_knee[0]<0||p.gamut_knee[0]>=1||p.gamut_knee[1]<=p.gamut_knee[0]||p.gamut_knee[2]<=0)
        throw std::string("invalid input gamut knee");
    if(p.adaptation_surface&&!film.adaptation_surface.empty()&&film.adaptation_surface.size()!=45)
        throw std::string("unsupported adaptation surface model");
    const bool original=p.method=="hanatos2025"&&p.filter_uv[0]<=0&&p.filter_ir[0]<=0&&
        p.adaptation_window&&!p.adaptation_surface&&p.spectral_gaussian_blur==0&&p.gamut_active&&
        p.gamut_algorithm=="xy"&&p.gamut_knee[0]==0&&p.gamut_knee[1]==1&&p.gamut_knee[2]==6;
    out.size=film.tc_size;
    if(original) return out; // use the identical precomputed default table
    if(data.format_version<4||data.hanatos_spectra.empty()) throw std::string("runtime spectral settings require engine asset v4");
    const int nw=data.n_wl,L=data.hanatos_size; out.size=L;
    const auto& il=data.illuminants[film.ref_illu];
    std::vector<double> sens(film.sensitivity.begin(),film.sensitivity.end());
    if(p.filter_uv[0]>0||p.filter_ir[0]>0) {
        if(p.filter_uv[2]<=0||p.filter_ir[2]<=0)throw std::string("spectral filter width must be positive");
        double norm[3]={},den[3]={};std::vector<double> band(nw);
        for(int w=0;w<nw;w++) {
            const double uv=std::clamp((double)p.filter_uv[0],0.0,1.0),ir=std::clamp((double)p.filter_ir[0],0.0,1.0);
            band[w]=(1-uv+uv*(std::erf((data.wavelengths[w]-p.filter_uv[1])/p.filter_uv[2])*.5+.5))*
                    (1-ir+ir*(std::erf((data.wavelengths[w]-p.filter_ir[1])/-p.filter_ir[2])*.5+.5));
            for(int c=0;c<3;c++){norm[c]+=sens[w*3+c]*il.values[w]*band[w];den[c]+=sens[w*3+c]*il.values[w];}
        }
        for(int w=0;w<nw;w++)for(int c=0;c<3;c++)sens[w*3+c]*=band[w]*den[c]/norm[c];
    }
    if(p.method=="mallett2019") {
        out.use_mallett=true;double midgreen=0;
        for(int w=0;w<nw;w++)midgreen+=il.values[w]*.184*sens[w*3+1];
        for(int a=0;a<3;a++)for(int c=0;c<3;c++)for(int w=0;w<nw;w++)
            out.mallett[c*3+a]+=data.mallett_basis[w*3+a]*il.values[w]*sens[w*3+c]/midgreen;
        // colour.RGB_to_RGB(sRGB, sRGB) still multiplies the published
        // rounded XYZ matrices; it is measurably different from identity.
        const double srgb_roundtrip[9]={.99999174,0,.00002316,
                                       .00002167,1.00004032,-.00000794,
                                       .00000038,.00001192,1.00000355};
        double converted[9]={};
        for(int c=0;c<3;c++)for(int a=0;a<3;a++)for(int k=0;k<3;k++)
            converted[c*3+a]+=out.mallett[c*3+k]*srgb_roundtrip[k*3+a];
        std::copy(converted,converted+9,out.mallett);
        return out;
    }
    if(p.adaptation_window&&!film.adaptation_window.empty()) {
        if(film.adaptation_window.size()!=4) throw std::string("unsupported adaptation window model");
        const auto& a=film.adaptation_window;double norm[3]={},den[3]={};std::vector<double> win(nw);
        for(int w=0;w<nw;w++){
            win[w]=(.5+.5*std::erf((data.wavelengths[w]-a[0])/(a[1]*std::sqrt(2.0))))*
                   (.5-.5*std::erf((data.wavelengths[w]-a[2])/(a[3]*std::sqrt(2.0))));
            for(int c=0;c<3;c++){norm[c]+=sens[w*3+c]*il.values[w]*win[w];den[c]+=sens[w*3+c]*il.values[w];}
        }
        for(int w=0;w<nw;w++)for(int c=0;c<3;c++)sens[w*3+c]*=win[w]*den[c]/norm[c];
    }
    std::vector<double> blurred_sens=sens;
    if(p.spectral_gaussian_blur>0) {
        const double sigma=p.spectral_gaussian_blur;const int radius=(int)(4*sigma+.5);
        std::vector<double> kernel(2*radius+1);double sum=0;
        for(int j=-radius;j<=radius;j++){kernel[j+radius]=std::exp(-.5*j*j/(sigma*sigma));sum+=kernel[j+radius];}
        // Move the linear Gaussian through the spectral dot product. Reflect
        // padding's transpose is accumulated explicitly at each endpoint.
        std::fill(blurred_sens.begin(),blurred_sens.end(),0.0);
        for(int w=0;w<nw;w++)for(int j=-radius;j<=radius;j++)for(int c=0;c<3;c++)
            blurred_sens[spectral_detail::reflect(w+j,nw)*3+c]+=sens[w*3+c]*kernel[j+radius]/sum;
    }
    std::vector<double> lut((size_t)L*L*3);double cu,cv;
    spectral_detail::xy_to_tc(il.xy[0],il.xy[1],cu,cv);
    parallel_for(L,[&](int begin,int end){for(int i=begin;i<end;i++)for(int j=0;j<L;j++) {
        const size_t k=(size_t)i*L+j;
        for(int c=0;c<3;c++){
            double raw=0;for(int w=0;w<nw;w++)raw+=data.hanatos_spectra[k*nw+w]*blurred_sens[w*3+c];
            if(p.adaptation_surface&&!film.adaptation_surface.empty()) {
                raw*=std::pow(2.0,spectral_detail::surface((double)i/(L-1),(double)j/(L-1),film.adaptation_surface.data()+c*15,cu,cv));
            }
            lut[k*3+c]=raw;
        }
    }});
    out.tc.resize(lut.size());
    parallel_for(L,[&](int begin,int end){for(int i=begin;i<end;i++)for(int j=0;j<L;j++) {
        double x,y,u=(double)i/(L-1),v=(double)j/(L-1);
        if(p.gamut_active){spectral_detail::tc_to_xy(u,v,x,y);spectral_detail::compress_xy(data,il,p,x,y);spectral_detail::xy_to_tc(x,y,u,v);}
        const double fi=u*(L-1),fj=v*(L-1);const int i0=std::min((int)fi,L-2),j0=std::min((int)fj,L-2);
        const double a=fi-i0,b=fj-j0;
        for(int c=0;c<3;c++)out.tc[((size_t)i*L+j)*3+c]=(float)(
            (lut[((size_t)i0*L+j0)*3+c]*(1-b)+lut[((size_t)i0*L+j0+1)*3+c]*b)*(1-a)+
            (lut[((size_t)(i0+1)*L+j0)*3+c]*(1-b)+lut[((size_t)(i0+1)*L+j0+1)*3+c]*b)*a);
    }});
    return out;
}
} // namespace spk
