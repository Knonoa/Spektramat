// Exercise the production grain kernel and RNG against the Python reference.
// Include the implementation only in this host test to expose the private
// stage through Engine's existing test/table friend, without an Android API.
#include <filesystem>
#include <fstream>
#include <cstdio>
#include "spektra_engine.cpp"

namespace lt {
struct EngineAccess {
    static void grain(spk::Engine& e, int w, int h, const spk::RenderParams& p,
                      double pixel_um, float* ch[3]) {
        e.impl_->setup(p);
        e.impl_->grain(w, h, p, pixel_um, ch, nullptr);
    }
    static std::vector<float> glare(spk::Engine& e, int w, int h, const spk::RenderParams& params) {
        spk::RenderParams p=params;
        p.glare_active=false;
        p.output_gamut.algorithm="off";
        p.unsharp_mask=false;
        p.scanner_lens_blur=0;
        e.impl_->setup(p);
        e.impl_->setup_color_reference(p);
        const size_t n=(size_t)w*h;
        std::vector<float> planes[3], baseline;
        float* ch[3];
        for(int c=0;c<3;c++){planes[c].assign(n,.5f);ch[c]=planes[c].data();}
        e.impl_->scan_stage(w,h,p,ch,nullptr,nullptr,nullptr);
        baseline=planes[0];
        for(int c=0;c<3;c++)planes[c].assign(n,.5f);
        p.glare_active=true;
        e.impl_->scan_stage(w,h,p,ch,nullptr,nullptr,nullptr);
        const auto matrix=spk::scan_output_matrix(spk::output_color_space(p.output_color_space),e.impl_->scan->illu_xyz);
        double white[3];matrix.apply(e.impl_->scan->illu_xyz,white);
        for(size_t i=0;i<n;i++)baseline[i]=(float)((planes[0][i]-baseline[i])/white[0]);
        return baseline;
    }
};
}

template<class T> void dump(const std::filesystem::path& path, const std::vector<T>& values) {
    std::ofstream out(path, std::ios::binary);
    out.write(reinterpret_cast<const char*>(values.data()), values.size() * sizeof(T));
}

int main(int argc, char** argv) {
    if (argc != 3) return 2;
    std::ifstream input(argv[1], std::ios::binary);
    std::vector<uint8_t> bytes((std::istreambuf_iterator<char>(input)), {});
    auto& ctx = spk::EngineContext::instance();
    if (!ctx.init(bytes.data(), bytes.size())) return 1;
    const std::filesystem::path output(argv[2]);
    std::filesystem::create_directories(output);
    constexpr int w = 512, h = 512;
    const size_t n = (size_t)w * h;
    spk::Engine engine(ctx.data, ctx.cam16);
    for (int test = 0; test < 6; test++) {
        spk::RenderParams p;
        const char* names[] = {"portra-layers", "portra-single", "portra-single4", "ektachrome-layers", "hp5-layers", "portra-micro"};
        if (test == 3) p.film_stock = "kodak_ektachrome_100";
        if (test == 4) p.film_stock = "ilford_hp5_plus_400";
        p.grain_sublayers = test != 1 && test != 2;
        p.grain.n_sub_layers = test == 2 ? 4 : 1;
        const double pixel_um = test == 5 ? .25 : 5;
        // Isolate micro-structure from the much wider dye-cloud PSF at this
        // sub-micrometre sampling; the other five cases use the stock PSF.
        if (test == 5) p.grain.blur_dye_clouds_um = 0;
        p.seed = 37;
        const auto* film = ctx.data.film(p.film_stock);
        std::vector<float> clean(n * 3);
        for (int c = 0; c < 3; c++) {
            double lo = 1e9, hi = -1e9;
            for (size_t e = c; e < film->density_curves.size(); e += 3)
                if (std::isfinite(film->density_curves[e])) { lo = std::min(lo, (double)film->density_curves[e]); hi = std::max(hi, (double)film->density_curves[e]); }
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                const double fractions[] = {.03, .25, .65, .95};
                clean[((size_t)y * w + x) * 3 + c] = (float)((hi-lo) * fractions[(y >= h/2)*2 + (x >= w/2)]);
            }
        }
        std::vector<float> planes[3];
        float* ch[3];
        for (int c=0;c<3;c++) {
            planes[c].resize(n); ch[c]=planes[c].data();
            for (size_t i=0;i<n;i++) planes[c][i]=clean[i*3+c];
        }
        lt::EngineAccess::grain(engine,w,h,p,pixel_um,ch);
        std::vector<float> grained(n*3), repeated(n*3);
        for(size_t i=0;i<n;i++)for(int c=0;c<3;c++)grained[i*3+c]=planes[c][i];
        for(size_t i=0;i<n;i++)for(int c=0;c<3;c++)planes[c][i]=clean[i*3+c];
        lt::EngineAccess::grain(engine,w,h,p,pixel_um,ch);
        for(size_t i=0;i<n;i++)for(int c=0;c<3;c++)repeated[i*3+c]=planes[c][i];
        if (grained != repeated) { std::fprintf(stderr,"seed not reproducible\n"); return 1; }
        dump(output/(std::string(names[test])+"-clean.bin"),clean);
        dump(output/(std::string(names[test])+".bin"),grained);
        std::printf("%s ready; fixed seed reproducible\n",names[test]);
    }
    constexpr size_t count=300000;
    for(int test=0;test<7;test++) {
        spk::Rng rng(9876+test);
        std::vector<double> values(count);
        for(double& v:values) {
            if(test==0)v=rng.poisson(.3);
            if(test==1)v=rng.poisson(35);
            if(test==2)v=rng.poisson(1000);
            if(test==3)v=rng.binomial(20,.2);
            if(test==4)v=rng.binomial(100,.3);
            if(test==5)v=rng.binomial(1000,.98);
            if(test==6)v=rng.lognormal_mean_std(.03,.021);
        }
        dump(output/("sampler-"+std::to_string(test)+".bin"),values);
    }
    for(int test=0;test<2;test++) {
        spk::RenderParams p;
        p.seed=91;
        if(test==1){p.glare_percent=.08f;p.glare_roughness=1.2f;p.glare_blur=2;}
        dump(output/("glare-"+std::to_string(test)+".bin"),lt::EngineAccess::glare(engine,w,h,p));
    }
}
