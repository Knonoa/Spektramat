#include "raw_libraw.hpp"
#include "engine_image.hpp"
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <filesystem>
#include <stdexcept>
#include <vector>

using Bytes = std::vector<uint8_t>;
void need(bool ok, const char* message) { if (!ok) throw std::runtime_error(message); }
void word(Bytes& b, uint16_t n) { b.push_back(n & 255); b.push_back(n >> 8); }
void dword(Bytes& b, uint32_t n) { word(b, n & 65535); word(b, n >> 16); }
struct Tag { uint16_t tag, type; uint32_t count; Bytes bytes; };
Bytes shorts(std::initializer_list<uint16_t> values) { Bytes b; for (auto v : values) word(b, v); return b; }
Bytes longs(std::initializer_list<uint32_t> values) { Bytes b; for (auto v : values) dword(b, v); return b; }

// Minimal, valid uncompressed Bayer DNG. Every sample remains a 16-bit word;
// black=512 and white=16383 model a common 14-bit sensor's code range.
void dng(const std::string& path, uint16_t sample) {
    Bytes matrix;
    for (int r = 0; r < 3; ++r) for (int c = 0; c < 3; ++c) { dword(matrix, r == c ? 1 : 0); dword(matrix, 1); }
    Bytes neutral; for (int c = 0; c < 3; ++c) { dword(neutral, 1); dword(neutral, 1); }
    std::vector<Tag> tags{
        {256,3,1,shorts({64})}, {257,3,1,shorts({64})}, {258,3,1,shorts({16})},
        {259,3,1,shorts({1})}, {262,3,1,shorts({32803})}, {273,4,1,longs({0})},
        {277,3,1,shorts({1})}, {278,3,1,shorts({64})}, {279,4,1,longs({8192})},
        {33421,3,2,shorts({2,2})}, {33422,1,4,Bytes{0,1,1,2}},
        {50706,1,4,Bytes{1,4,0,0}}, {50714,3,1,shorts({512})},
        {50717,3,1,shorts({16383})}, {50721,5,9,matrix}, {50728,5,3,neutral},
    };
    const uint32_t start = 8 + 2 + (uint32_t)tags.size() * 12 + 4;
    Bytes output{'I','I',42,0,8,0,0,0}; word(output, (uint16_t)tags.size());
    Bytes extra;
    for (auto& tag : tags) {
        if (tag.tag == 273) tag.bytes = longs({start});
        word(output, tag.tag); word(output, tag.type); dword(output, tag.count);
        if (tag.bytes.size() <= 4) { auto b=tag.bytes; b.resize(4); output.insert(output.end(),b.begin(),b.end()); }
        else { dword(output,start + 8192 + (uint32_t)extra.size()); extra.insert(extra.end(),tag.bytes.begin(),tag.bytes.end()); }
    }
    dword(output,0);
    for (int i=0; i<64*64; ++i) word(output,sample);
    output.insert(output.end(),extra.begin(),extra.end());
    auto f=std::fopen(path.c_str(),"wb"); need(f,"create synthetic DNG");
    need(std::fwrite(output.data(),1,output.size(),f)==output.size(),"write synthetic DNG"); std::fclose(f);
}

spk::android_io::LinearImage decode(const std::string& path, int edge=0) {
    spk::android_io::LinearImage out; std::string error;
    if (!spk::raw::libraw_decode(path,edge,out,error)) throw std::runtime_error(error);
    need(out.width == (edge ? edge : 64) && out.height == (edge ? edge : 64), "RAW dimensions");
    need(std::all_of(out.rgb.begin(),out.rgb.end(),[](float v){return std::isfinite(v);}),"RAW nonfinite sample");
    return out;
}

int main(int argc,char** argv) { try {
    need(argc==2,"fixture directory"); std::filesystem::create_directories(argv[1]); std::string dir=argv[1];
    const std::array<uint16_t,4> codes{480,8192,8193,20000};
    std::array<spk::android_io::LinearImage,4> images;
    for(size_t i=0;i<codes.size();++i) { auto p=dir+"/raw-"+std::to_string(codes[i])+".dng"; dng(p,codes[i]); images[i]=decode(p); }
    need(*std::max_element(images[0].rgb.begin(),images[0].rgb.end())<0,"RAW black subtraction clipped negative samples");
    need(*std::min_element(images[3].rgb.begin(),images[3].rgb.end())>1,"RAW normalization clipped above-white samples");
    for(size_t i=0;i<images[1].rgb.size();++i) need(images[2].rgb[i]>images[1].rgb[i],"RAW adjacent code values collapsed");
    auto proxy=decode(dir+"/raw-20000.dng",16);
    need(*std::min_element(proxy.rgb.begin(),proxy.rgb.end())>1,"RAW proxy scaling clipped highlights");
    std::puts("PASS RAW float precision: shadows, highlights, adjacent codes, proxy range"); return 0;
} catch(const std::exception& e) { std::fprintf(stderr,"FAIL: %s\n",e.what()); return 1; } }
