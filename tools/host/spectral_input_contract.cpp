// Dump runtime spectral setup for comparison with upstream compute_hanatos2025_tc_lut.
#include <fstream>
#include <filesystem>
#include <cstdio>
#include "spectral_input.hpp"

int main(int argc,char** argv) {
    if(argc!=3)return 2;
    std::ifstream src(argv[1],std::ios::binary);
    std::vector<uint8_t> bytes((std::istreambuf_iterator<char>(src)),{});
    spk::EngineData data;spk::load_engine_data(bytes.data(),bytes.size(),data);
    const auto& film=*data.film("kodak_portra_400");
    for(int test=0;test<7;test++) {
        spk::SpectralInputParams p;
        const char* name="uv-ir";
        if(test==0){p.filter_uv[0]=.8f;p.filter_ir[0]=.6f;}
        if(test==1){name="adaptation-off";p.adaptation_window=false;}
        if(test==2){name="surface-blur";p.adaptation_surface=true;p.spectral_gaussian_blur=2.3f;}
        if(test==3){name="mallett";p.method="mallett2019";}
        if(test==4){name="input-gamut-off";p.gamut_active=false;}
        if(test==5){name="input-gamut-oklch";p.gamut_algorithm="oklch";}
        if(test==6){name="input-gamut-knee";p.gamut_knee[0]=.15f;p.gamut_knee[2]=4;}
        auto setup=spk::build_spectral_input(data,film,p);
        std::filesystem::create_directories(argv[2]);
        std::ofstream out(std::filesystem::path(argv[2])/(std::string(name)+".bin"),std::ios::binary);
        if(setup.use_mallett)out.write((const char*)setup.mallett,sizeof(setup.mallett));
        else out.write((const char*)setup.tc.data(),setup.tc.size()*sizeof(float));
        std::printf("%s ready\n",name);
    }
}
