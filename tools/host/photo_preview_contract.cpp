#include <cstdio>
#include <fstream>
#include <random>
#include "lt_photo_preview.hpp"

int main(int argc, char** argv) {
    if (argc < 2) return 2;
    std::ifstream file(argv[1], std::ios::binary);
    std::vector<uint8_t> bin((std::istreambuf_iterator<char>(file)), {});
    auto& ctx = spk::EngineContext::instance();
    if (bin.empty() || !ctx.init(bin.data(), bin.size())) return 2;
    if (argc == 9 && std::string(argv[2]) == "--bake") {
        spk::RenderParams p;
        p.film_stock = argv[3]; p.print_stock = argv[4]; p.scan_film = std::stoi(argv[5]) != 0;
        p.scanner_white_correction = std::stoi(argv[6]) != 0;
        p.scanner_full_white_extension = p.scanner_white_correction; p.scanner_white_level = 1;
        spk::Engine engine(ctx.data, ctx.cam16);
        lt::VideoLut lut; std::string error;
        if (!lt::bake_photo_preview_lut(engine,p,std::stoi(argv[7]),lut,error)) {
            std::printf("FAIL %s\n",error.c_str());return 1;
        }
        std::ofstream output(argv[8],std::ios::binary);
        output.write(reinterpret_cast<const char*>(lut.rgb.data()),lut.rgb.size()*sizeof(float));
        if (!output) return 1;
        std::printf("BAKED %s %s scan=%d white=%d size=%d\n",p.film_stock.c_str(),p.print_stock.c_str(),p.scan_film,p.scanner_white_correction,lut.size);
        return 0;
    }
    int failures = 0;
    for (float value : {-.5f, -.02f, 0.f, .001f, .18f, 1.f, 20.f, 512.f}) {
        const float roundtrip = lt::photo_preview_unshape(lt::photo_preview_shape(value));
        failures += std::abs(roundtrip-value) > std::max(1e-5f, std::abs(value)*2e-6f);
    }
    for (int scenario = 0; scenario < 5; ++scenario) {
        spk::Engine engine(ctx.data, ctx.cam16);
        spk::RenderParams p;
        p.film_stock = scenario == 2 ? "kodak_ektachrome_100" : "kodak_portra_400";
        p.scan_film = scenario == 2;
        p.scanner_white_correction = scenario != 0;
        p.scanner_white_level = 1;
        p.scanner_full_white_extension = scenario != 0;
        if (scenario == 3) p.output_color_space = "Display P3";
        if (scenario == 4) p.output_color_space = "ProPhoto RGB";
        const auto q = lt::photo_preview_pointwise_params(p);
        failures += q.auto_exposure || q.print_exposure_compensation ||
            q.scanner_white_correction != p.scanner_white_correction ||
            q.scanner_full_white_extension != p.scanner_full_white_extension ||
            q.output_color_space != p.output_color_space;
        for (int size : {33, 65}) {
            lt::VideoLut lut;
            std::string error;
            if (!lt::bake_photo_preview_lut(engine, p, size, lut, error)) {
                std::printf("FAIL bake %s\n", error.c_str()); ++failures; continue;
            }
            constexpr int w=32, h=16, count=w*h;
            std::vector<float> input(count*3), exact(count*3);
            std::mt19937 rng(7919); std::uniform_real_distribution<float> random(0,1);
            for (int i=0; i<count*3; ++i) input[i] = lt::video_decode(random(rng)) * 4;
            const spk::TapFn tap = [&](const char* name,const float* r,const float* g,const float* b,int,int) {
                if (std::strcmp(name,"rgb_out_linear")) return;
                for (int i=0;i<count;++i) { exact[3*i]=r[i];exact[3*i+1]=g[i];exact[3*i+2]=b[i]; }
            };
            if (!engine.render(input.data(),w,h,-1,q,nullptr,error,nullptr,tap)) return 2;
            double maximum=0,squared=0;
            auto display=[](float v) { v=std::clamp(v,0.f,1.f);return v<=.0031308f?12.92f*v:1.055f*std::pow(v,1/2.4f)-.055f; };
            for (int i=0;i<count;++i) {
                float result[3];lt::sample_photo_preview_lut(lut,&input[3*i],result);
                for(int c=0;c<3;++c) { const double delta=std::abs(display(result[c])-display(exact[3*i+c]));
                    maximum=std::max(maximum,delta);squared+=delta*delta; }
            }
            const double rms=std::sqrt(squared/(count*3));
            std::printf("photo cube %d^3 scenario=%d max=%.3f RMS=%.3f codes\n",size,scenario,maximum*255,rms*255);
            // Small proxy previews are explicitly approximate. The main cube
            // should remain close while the 33 cube prioritizes list speed.
            failures += size==65 ? rms>3.0/255 : rms>8.0/255;
        }
    }
    std::printf("PHOTO_PREVIEW_FAILURES %d\n",failures);
    return failures ? 1 : 0;
}
