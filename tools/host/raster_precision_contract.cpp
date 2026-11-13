#include "engine_image.hpp"
#include "third_party/lcms2/include/lcms2.h"
#include <tiffio.h>
#include <zlib.h>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <stdexcept>
#include <vector>

using Bytes=std::vector<uint8_t>;
void need(bool ok,const char* what) { if(!ok) throw std::runtime_error(what); }
void u32(Bytes& b,uint32_t n) { b.push_back(n>>24); b.push_back(n>>16); b.push_back(n>>8); b.push_back(n); }
void chunk(Bytes& b,const char* type,const Bytes& data) {
    u32(b,(uint32_t)data.size()); size_t start=b.size(); b.insert(b.end(),type,type+4); b.insert(b.end(),data.begin(),data.end());
    u32(b,(uint32_t)crc32(0,b.data()+start,(uInt)(4+data.size())));
}
Bytes profile() {
    cmsCIExyY white{.3127,.329,1}; cmsCIExyYTRIPLE prim{{.64,.33,1},{.30,.60,1},{.15,.06,1}};
    auto g=cmsBuildGamma(nullptr,1); cmsToneCurve* curves[3]={g,g,g}; auto p=cmsCreateRGBProfile(&white,&prim,curves); cmsFreeToneCurve(g);
    cmsUInt32Number size=0; cmsSaveProfileToMem(p,nullptr,&size); Bytes b(size); cmsSaveProfileToMem(p,b.data(),&size); cmsCloseProfile(p); return b;
}
Bytes compress(const Bytes& b) { uLongf n=compressBound((uLong)b.size()); Bytes out(n); need(compress2(out.data(),&n,b.data(),(uLong)b.size(),9)==Z_OK,"zlib"); out.resize(n); return out; }
void png(const std::string& path,bool icc) {
    Bytes b{137,80,78,71,13,10,26,10},ihdr; u32(ihdr,2);u32(ihdr,1);ihdr.insert(ihdr.end(),{16,2,0,0,0});chunk(b,"IHDR",ihdr);
    if(icc) { Bytes data{'l','i','n','e','a','r',0,0}; auto z=compress(profile()); data.insert(data.end(),z.begin(),z.end());chunk(b,"iCCP",data); }
    Bytes raw{0}; for(uint16_t v:{uint16_t(32768),uint16_t(32769)}) for(int c=0;c<3;++c) { raw.push_back(v>>8);raw.push_back(v); }
    chunk(b,"IDAT",compress(raw));chunk(b,"IEND",{}); auto f=std::fopen(path.c_str(),"wb");need(f,"open PNG");need(std::fwrite(b.data(),1,b.size(),f)==b.size(),"write PNG");std::fclose(f);
}
void tiff(const std::string& path,bool floating,bool tiled,bool separate,bool icc) {
    auto t=TIFFOpen(path.c_str(),"w");need(t,"write TIFF");
    TIFFSetField(t,TIFFTAG_IMAGEWIDTH,2);TIFFSetField(t,TIFFTAG_IMAGELENGTH,1);TIFFSetField(t,TIFFTAG_SAMPLESPERPIXEL,3);
    TIFFSetField(t,TIFFTAG_BITSPERSAMPLE,floating?32:16);TIFFSetField(t,TIFFTAG_SAMPLEFORMAT,floating?SAMPLEFORMAT_IEEEFP:SAMPLEFORMAT_UINT);
    TIFFSetField(t,TIFFTAG_PHOTOMETRIC,PHOTOMETRIC_RGB);TIFFSetField(t,TIFFTAG_PLANARCONFIG,separate?PLANARCONFIG_SEPARATE:PLANARCONFIG_CONTIG);
    TIFFSetField(t,TIFFTAG_COMPRESSION,COMPRESSION_LZW); auto p=profile(); if(icc)TIFFSetField(t,TIFFTAG_ICCPROFILE,(uint32_t)p.size(),p.data());
    if(tiled){TIFFSetField(t,TIFFTAG_TILEWIDTH,16);TIFFSetField(t,TIFFTAG_TILELENGTH,16);}else TIFFSetField(t,TIFFTAG_ROWSPERSTRIP,1);
    const int channels=separate?1:3; const float v[6]={-.25001f,.50001f,2.0001f,-.25002f,.50002f,2.0002f};
    for(int plane=0;plane<(separate?3:1);++plane) {
        if(floating) { std::vector<float> row(tiled?16*16*channels:2*channels);for(int x=0;x<2;++x)for(int c=0;c<channels;++c)row[x*channels+c]=v[x*3+(separate?plane:c)];
            need((tiled?TIFFWriteTile(t,row.data(),0,0,0,plane):TIFFWriteScanline(t,row.data(),0,plane))>=0,"float TIFF pixels"); }
        else { std::vector<uint16_t> row(tiled?16*16*channels:2*channels);for(int x=0;x<2;++x)for(int c=0;c<channels;++c)row[x*channels+c]=32768+x;
            need((tiled?TIFFWriteTile(t,row.data(),0,0,0,plane):TIFFWriteScanline(t,row.data(),0,plane))>=0,"uint TIFF pixels"); }
    } TIFFClose(t);
}
spk::android_io::LinearImage decode(const std::string& p) {
    std::string error;spk::android_io::LinearImage out;need(!spk::android_io::is_raw(p),"ordinary raster classified as RAW");
    if(!spk::android_io::decode_linear(p,0,out,error))throw std::runtime_error(error);need(out.width==2&&out.height==1,"size");return out;
}
int main(int argc,char** argv) { try {
    need(argc==2,"fixture directory");std::filesystem::create_directories(argv[1]);std::string dir=argv[1];
    int checks=0;
    for(bool icc:{false,true}) { auto p=dir+(icc?"/icc16.png":"/srgb16.png");png(p,icc);auto image=decode(p);
        need(image.rgb[3]>image.rgb[0],"16-bit PNG values collapsed");
        if(icc) need(std::abs(image.rgb[0]-32768/65535.f)<2e-5f,"ICC ignored");++checks; }
    for(bool floating:{false,true})for(bool tiled:{false,true})for(bool separate:{false,true})for(bool icc:{false,true}) {
        auto p=dir+"/test"+std::to_string(checks)+".tif";tiff(p,floating,tiled,separate,icc);auto image=decode(p);
        if(floating){need(std::abs(image.rgb[0]+.25001f)<(icc?1e-4f:1e-7f),"float shadow clipped");need(std::abs(image.rgb[2]-2.0001f)<(icc?1e-4f:1e-7f),"float highlight clipped");need(std::abs(image.rgb[4]-.50002f)<(icc?1e-4f:1e-7f),"float precision lost");}
        else need(image.rgb[3]>image.rgb[0],"16-bit TIFF values collapsed");++checks;
    }
    std::printf("PASS %d native raster precision cases (16-bit PNG, ICC, TIFF16/float32, strips/tiles, planes)\n",checks);return 0;
}catch(const std::exception& e){std::fprintf(stderr,"FAIL: %s\n",e.what());return 1;} }


