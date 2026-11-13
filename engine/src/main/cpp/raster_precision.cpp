#include "raster_precision.hpp"
#include "image_orientation.hpp"
#include "third_party/lcms2/include/lcms2.h"
#include <tiffio.h>
#include <zlib.h>
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <limits>
#include <memory>
#include "third_party/stb_image.h"

namespace spk::android_io {
namespace {
using Bytes = std::vector<uint8_t>;
struct Profile { cmsHPROFILE p = nullptr; ~Profile() { if (p) cmsCloseProfile(p); } };
struct TiffCloser { void operator()(TIFF* p) const { if (p) TIFFClose(p); } };
using Tiff = std::unique_ptr<TIFF, TiffCloser>;

uint32_t be32(const uint8_t* p) { return (uint32_t)p[0] << 24 | (uint32_t)p[1] << 16 | (uint32_t)p[2] << 8 | p[3]; }
bool read(const std::string& path, Bytes& b, std::string& e) {
    std::FILE* f = std::fopen(path.c_str(), "rb");
    if (!f) { e = "cannot open image"; return false; }
    std::fseek(f, 0, SEEK_END); long size = std::ftell(f); std::rewind(f);
    if (size <= 0 || size > std::numeric_limits<int>::max()) { std::fclose(f); e = "invalid image size"; return false; }
    b.resize((size_t)size); bool ok = std::fread(b.data(), 1, b.size(), f) == b.size(); std::fclose(f);
    if (!ok) e = "image read failed";
    return ok;
}
bool tiff_signature(const uint8_t* b, size_t n) {
    return n >= 4 && ((b[0]=='I' && b[1]=='I' && (b[2]==42 || b[2]==43) && b[3]==0) ||
        (b[0]=='M' && b[1]=='M' && b[2]==0 && (b[3]==42 || b[3]==43)));
}
bool png_signature(const Bytes& b) { return b.size() >= 8 && std::memcmp(b.data(), "\x89PNG\r\n\x1a\n", 8) == 0; }
bool jpeg_signature(const Bytes& b) { return b.size() >= 2 && b[0]==255 && b[1]==216; }

// ICC APP2 pieces may appear out of order; incomplete profiles must not silently
// turn into assumed sRGB. 16 MiB is ample for photographic RGB profiles.
bool jpeg_profile(const Bytes& b, Bytes& profile, std::string& error) {
    std::array<Bytes,256> chunks; int total = 0; size_t pos = 2;
    while (pos < b.size()) {
        if (b[pos++] != 255) break;
        while (pos < b.size() && b[pos] == 255) ++pos;
        if (pos == b.size()) break;
        const int marker = b[pos++];
        if (marker == 0xda || marker == 0xd9) break;
        if (marker == 1 || (marker >= 0xd0 && marker <= 0xd7)) continue;
        if (b.size()-pos < 2) { error="truncated JPEG metadata"; return false; }
        size_t len = (size_t)b[pos]<<8 | b[pos+1];
        if (len < 2 || len > b.size()-pos) { error="truncated JPEG segment"; return false; }
        if (marker >= 0xc0 && marker <= 0xcf && marker != 0xc4 && marker != 0xc8 && marker != 0xcc && len >= 8 && b[pos+7] == 4) {
            error="CMYK JPEG needs a native CMYK float decoder; convert to RGB TIFF or PNG first"; return false;
        }
        if (marker == 0xe2 && len >= 16 && std::memcmp(b.data()+pos+2, "ICC_PROFILE\0", 12)==0) {
            int seq=b[pos+14], count=b[pos+15];
            if (count == 0 || seq == 0 || seq > count || (total && total != count) || !chunks[seq].empty()) {
                error="invalid JPEG ICC chunks"; return false;
            }
            total=count; chunks[seq].assign(b.begin()+pos+16,b.begin()+pos+len);
        }
        pos += len;
    }
    for (int i=1;i<=total;++i) {
        if (chunks[i].empty() || profile.size()+chunks[i].size()>16*1024*1024) { error="incomplete JPEG ICC profile"; return false; }
        profile.insert(profile.end(),chunks[i].begin(),chunks[i].end());
    }
    return true;
}

struct PngColor { Bytes icc; double gamma=0; bool srgb=false; bool chroma=false; int orientation=1; cmsCIExyY white{}; cmsCIExyYTRIPLE primaries{}; };
bool png_profile(const Bytes& b, PngColor& c, std::string& error) {
    for (size_t pos=8; b.size()-pos>=12;) {
        const size_t len=be32(b.data()+pos); if (len>b.size()-pos-12) { error="truncated PNG metadata"; return false; }
        const auto p=b.data()+pos+8; const auto type=b.data()+pos+4;
        if (std::memcmp(type,"iCCP",4)==0) {
            size_t k=0; while(k<len && p[k]) ++k;
            if (k==0 || k>79 || k+2>=len || p[k+1]!=0) { error="invalid PNG ICC profile"; return false; }
            uLongf size=16*1024*1024; c.icc.resize(size);
            if (uncompress(c.icc.data(),&size,p+k+2,(uLong)(len-k-2))!=Z_OK || !size) { error="invalid compressed PNG ICC profile"; return false; }
            c.icc.resize(size);
        } else if (std::memcmp(type,"sRGB",4)==0 && len==1) c.srgb=true;
        else if (std::memcmp(type,"eXIf",4)==0) c.orientation=tiff_orientation(p,len).value;
        else if (std::memcmp(type,"gAMA",4)==0 && len==4) c.gamma=be32(p)/100000.0;
        else if (std::memcmp(type,"cHRM",4)==0 && len==32) {
            auto xy=[&](int i) { return cmsCIExyY{be32(p+i)/100000.0,be32(p+i+4)/100000.0,1.0}; };
            c.white=xy(0); c.primaries={xy(8),xy(16),xy(24)}; c.chroma=true;
        }
        pos += len+12;
    }
    return true;
}

cmsHPROFILE linear_srgb() {
    cmsCIExyY white{.3127,.3290,1}; cmsCIExyYTRIPLE primaries{{.64,.33,1},{.30,.60,1},{.15,.06,1}};
    cmsToneCurve* g=cmsBuildGamma(nullptr,1.0); cmsToneCurve* curves[3]={g,g,g};
    auto p=cmsCreateRGBProfile(&white,&primaries,curves); cmsFreeToneCurve(g); return p;
}
bool colour(std::vector<float>& rgb, cmsHPROFILE profile, bool gray, std::string& error) {
    if (!profile) { error="cannot read image ICC profile"; return false; }
    const auto signature=cmsGetColorSpace(profile);
    if (signature != (gray ? cmsSigGrayData : cmsSigRgbData)) { error="unsupported ICC sample colour space"; return false; }
    Profile dest{linear_srgb()};
    auto transform=cmsCreateTransform(profile,gray ? TYPE_GRAY_FLT : TYPE_RGB_FLT,dest.p,TYPE_RGB_FLT,
        INTENT_RELATIVE_COLORIMETRIC,cmsFLAGS_NOOPTIMIZE | cmsFLAGS_NOCACHE);
    if (!transform) { error="cannot create float ICC transform"; return false; }
    if (gray) {
        std::vector<float> input(rgb.size()/3);
        for(size_t i=0;i<input.size();++i) input[i]=rgb[i*3];
        cmsDoTransform(transform,input.data(),rgb.data(),(cmsUInt32Number)input.size());
    } else cmsDoTransform(transform,rgb.data(),rgb.data(),(cmsUInt32Number)(rgb.size()/3));
    cmsDeleteTransform(transform);
    for(float v:rgb) if(!std::isfinite(v)) { error="image contains non-finite samples"; return false; }
    return true;
}
void orient(std::vector<float>& rgb,int& w,int& h,int orientation) {
    orientation=spk::raw::clamped_orientation(orientation); if(orientation==1) return;
    const bool swap=spk::raw::orientation_swaps_axes(orientation); int ow=swap?h:w,oh=swap?w:h;
    std::vector<float> out(rgb.size());
    for(int y=0;y<oh;++y) for(int x=0;x<ow;++x) {
        int sx,sy; spk::raw::oriented_source(x,y,orientation,w,h,sx,sy);
        std::copy_n(rgb.data()+((size_t)sy*w+sx)*3,3,out.data()+((size_t)y*ow+x)*3);
    }
    rgb.swap(out); w=ow; h=oh;
}
bool dimensions(uint32_t w,uint32_t h) { return w>0 && h>0 && w<=100000 && h<=100000 && (uint64_t)w*h<=256ULL*1024*1024; }
bool tiff_info(TIFF* t,uint32_t& w,uint32_t& h,uint16_t& photo,uint16_t& bits,uint16_t& spp,uint16_t& format,std::string& error) {
    TIFFGetFieldDefaulted(t,TIFFTAG_PHOTOMETRIC,&photo); TIFFGetFieldDefaulted(t,TIFFTAG_BITSPERSAMPLE,&bits);
    TIFFGetFieldDefaulted(t,TIFFTAG_SAMPLESPERPIXEL,&spp); TIFFGetFieldDefaulted(t,TIFFTAG_SAMPLEFORMAT,&format);
    if(!TIFFGetField(t,TIFFTAG_IMAGEWIDTH,&w)||!TIFFGetField(t,TIFFTAG_IMAGELENGTH,&h)||!dimensions(w,h)) { error="invalid TIFF dimensions"; return false; }
    const bool gray=photo==PHOTOMETRIC_MINISBLACK || photo==PHOTOMETRIC_MINISWHITE;
    if ((photo!=PHOTOMETRIC_RGB && !gray) || spp<(gray?1:3) || spp>16 ||
        !((format==SAMPLEFORMAT_UINT && (bits==8 || bits==16)) || (format==SAMPLEFORMAT_IEEEFP && bits==32))) {
        error="TIFF sample layout is not supported without precision loss"; return false;
    }
    return true;
}
bool decode_tiff(const std::string& path,LinearImage& out,std::string& error) {
    Tiff t(TIFFOpen(path.c_str(),"r")); if(!t) { error="cannot read TIFF"; return false; }
    uint32_t w=0,h=0; uint16_t photo=0,bits=0,spp=0,format=0;
    if(!tiff_info(t.get(),w,h,photo,bits,spp,format,error)) return false;
    uint16_t planar=PLANARCONFIG_CONTIG,orientation=1; TIFFGetFieldDefaulted(t.get(),TIFFTAG_PLANARCONFIG,&planar); TIFFGetFieldDefaulted(t.get(),TIFFTAG_ORIENTATION,&orientation);
    if(planar!=PLANARCONFIG_CONTIG && planar!=PLANARCONFIG_SEPARATE) { error="unsupported TIFF planes"; return false; }
    const bool gray=photo!=PHOTOMETRIC_RGB; const int channels=gray?1:3,bytes=bits/8;
    uint16_t extra_count=0; uint16_t* extras=nullptr; int alpha=-1;
    if(TIFFGetField(t.get(),TIFFTAG_EXTRASAMPLES,&extra_count,&extras))
        for(int i=0;i<extra_count;++i) if(extras[i]==EXTRASAMPLE_ASSOCALPHA) { alpha=spp-extra_count+i; break; }
    if(alpha>=0 && planar==PLANARCONFIG_SEPARATE) { error="premultiplied planar TIFF is not supported without precision loss"; return false; }
    std::vector<float> rgb((size_t)w*h*3);
    auto sample=[&](const uint8_t* p) { if(format==SAMPLEFORMAT_IEEEFP) { float v; std::memcpy(&v,p,4); return v; }
        if(bits==16) { uint16_t v; std::memcpy(&v,p,2); return v/65535.0f; } return *p/255.0f; };
    auto pixel=[&](uint32_t x,uint32_t y,int plane,const uint8_t* p) {
        float* dest=rgb.data()+((size_t)y*w+x)*3;
        for(int c=0;c<(planar==PLANARCONFIG_CONTIG?channels:1);++c) {
            float v=sample(p+c*bytes); if(photo==PHOTOMETRIC_MINISWHITE) v=1-v;
            if(alpha>=0) { const float a=sample(p+alpha*bytes); v=a!=0?v/a:0; }
            dest[planar==PLANARCONFIG_CONTIG?c:plane]=v;
        }
    };
    const int planes=planar==PLANARCONFIG_SEPARATE?channels:1;
    if(TIFFIsTiled(t.get())) {
        uint32_t tw=0,th=0; TIFFGetField(t.get(),TIFFTAG_TILEWIDTH,&tw); TIFFGetField(t.get(),TIFFTAG_TILELENGTH,&th);
        const tmsize_t size=TIFFTileSize(t.get()),row=TIFFTileRowSize(t.get());
        if(!tw||!th||size<=0||row<(tmsize_t)tw*bytes*(planar==PLANARCONFIG_CONTIG?spp:1)) { error="invalid TIFF tile"; return false; }
        Bytes tile((size_t)size);
        for(int plane=0;plane<planes;++plane) for(uint32_t y=0;y<h;y+=th) for(uint32_t x=0;x<w;x+=tw) {
            if(TIFFReadTile(t.get(),tile.data(),x,y,0,(uint16_t)plane)<0) { error="TIFF tile decode failed"; return false; }
            for(uint32_t yy=0;yy<std::min(th,h-y);++yy) for(uint32_t xx=0;xx<std::min(tw,w-x);++xx)
                pixel(x+xx,y+yy,plane,tile.data()+yy*row+xx*bytes*(planar==PLANARCONFIG_CONTIG?spp:1));
        }
    } else {
        const tmsize_t size=TIFFScanlineSize(t.get());
        if(size<(tmsize_t)w*bytes*(planar==PLANARCONFIG_CONTIG?spp:1)) { error="invalid TIFF scanline"; return false; }
        Bytes row((size_t)size);
        for(int plane=0;plane<planes;++plane) for(uint32_t y=0;y<h;++y) {
            if(TIFFReadScanline(t.get(),row.data(),y,(uint16_t)plane)<0) { error="TIFF scanline decode failed"; return false; }
            for(uint32_t x=0;x<w;++x) pixel(x,y,plane,row.data()+x*bytes*(planar==PLANARCONFIG_CONTIG?spp:1));
        }
    }
    if(gray) for(size_t i=0;i<rgb.size();i+=3) rgb[i+1]=rgb[i+2]=rgb[i];
    uint32_t icc_size=0; void* icc=nullptr; Profile profile;
    const bool tagged=TIFFGetField(t.get(),TIFFTAG_ICCPROFILE,&icc_size,&icc)!=0;
    if(tagged) profile.p=cmsOpenProfileFromMem(icc,icc_size);
    else if(format==SAMPLEFORMAT_IEEEFP) { // Untagged floating TIFF is linear, not display sRGB.
        for(float v:rgb) if(!std::isfinite(v)) { error="image contains non-finite samples"; return false; }
    } else profile.p=cmsCreate_sRGBProfile();
    if(profile.p || tagged) {
        // Untagged grayscale samples are neutral sRGB; tagged gray profiles use one float channel.
        if(!colour(rgb,profile.p,tagged && gray,error)) return false;
    }
    int iw=(int)w,ih=(int)h; orient(rgb,iw,ih,orientation);
    out.width=iw; out.height=ih; out.rgb=std::move(rgb); out.developer="raster-float"; return true;
}
}

bool precision_raster(const std::string& path) {
    uint8_t b[8]{}; auto f=std::fopen(path.c_str(),"rb"); if(!f) return false;
    size_t n=std::fread(b,1,8,f); std::fclose(f);
    return tiff_signature(b,n) || (n>=2 && b[0]==255 && b[1]==216) || (n>=4 && std::memcmp(b,"\x89PNG",4)==0);
}
bool raster_probe(const std::string& path,int& width,int& height,std::string& error) {
    Bytes b; if(!read(path,b,error)) return false;
    int orientation=image_orientation(b.data(),b.size()).value;
    if(tiff_signature(b.data(),b.size())) {
        Tiff t(TIFFOpen(path.c_str(),"r")); if(!t) { error="cannot read TIFF"; return false; }
        uint32_t w=0,h=0; uint16_t photo=0,bits=0,spp=0,format=0,o=1;
        if(!tiff_info(t.get(),w,h,photo,bits,spp,format,error)) return false;
        TIFFGetFieldDefaulted(t.get(),TIFFTAG_ORIENTATION,&o); orientation=o; width=(int)w; height=(int)h;
    } else { int n=0; if(!stbi_info_from_memory(b.data(),(int)b.size(),&width,&height,&n)) { error="unsupported raster"; return false; } }
    if(orientation>=5 && orientation<=8) std::swap(width,height); return true;
}
bool raster_decode(const std::string& path,int max_edge,LinearImage& out,std::string& error) {
    Bytes b; if(!read(path,b,error)) return false;
    LinearImage image;
    if(tiff_signature(b.data(),b.size())) {
        if(!decode_tiff(path,image,error)) return false;
    } else {
        int w=0,h=0,n=0; if(!stbi_info_from_memory(b.data(),(int)b.size(),&w,&h,&n) || !dimensions(w,h)) { error="invalid raster dimensions"; return false; }
        std::vector<float> rgb((size_t)w*h*3);
        if(stbi_is_16_bit_from_memory(b.data(),(int)b.size())) {
            auto p=stbi_load_16_from_memory(b.data(),(int)b.size(),&w,&h,&n,3); if(!p) { error="16-bit raster decode failed"; return false; }
            for(size_t i=0;i<rgb.size();++i) rgb[i]=p[i]/65535.0f; stbi_image_free(p);
        } else {
            auto p=stbi_load_from_memory(b.data(),(int)b.size(),&w,&h,&n,3); if(!p) { error="raster decode failed"; return false; }
            for(size_t i=0;i<rgb.size();++i) rgb[i]=p[i]/255.0f; stbi_image_free(p);
        }
        Bytes icc; PngColor png; Profile profile;
        if(jpeg_signature(b) && !jpeg_profile(b,icc,error)) return false;
        if(png_signature(b)) { if(!png_profile(b,png,error)) return false; icc=std::move(png.icc); }
        if(!icc.empty()) profile.p=cmsOpenProfileFromMem(icc.data(),(cmsUInt32Number)icc.size());
        else if(!png.srgb && (png.gamma>0 || png.chroma)) {
            cmsCIExyY white=png.chroma?png.white:cmsCIExyY{.3127,.3290,1};
            cmsCIExyYTRIPLE prim=png.chroma?png.primaries:cmsCIExyYTRIPLE{{.64,.33,1},{.30,.60,1},{.15,.06,1}};
            const double srgb_curve[5]={2.4,1/1.055,.055/1.055,1/12.92,.04045};
            auto g=png.gamma>0?cmsBuildGamma(nullptr,1/png.gamma):cmsBuildParametricToneCurve(nullptr,4,srgb_curve);
            cmsToneCurve* curves[3]={g,g,g}; profile.p=cmsCreateRGBProfile(&white,&prim,curves); cmsFreeToneCurve(g);
        } else profile.p=cmsCreate_sRGBProfile();
        const bool gray=!icc.empty() && profile.p && cmsGetColorSpace(profile.p)==cmsSigGrayData && n<=2;
        if(!colour(rgb,profile.p,gray,error)) return false;
        orient(rgb,w,h,png_signature(b)?png.orientation:image_orientation(b.data(),b.size()).value);
        image.width=w; image.height=h; image.rgb=std::move(rgb); image.developer="raster-float";
    }
    if(max_edge<=0 || std::max(image.width,image.height)<=max_edge) out=std::move(image);
    else out=scaled_linear(image,max_edge);
    return true;
}
}
