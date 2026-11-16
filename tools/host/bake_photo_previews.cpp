// Offline default cubes for the workbench. The app checks the complete recipe
// before choosing one, so customized film/paper controls still bake on demand.
#include <filesystem>
#include <fstream>
#include <set>
#include <tuple>
#include <cstdio>
#include "lt_photo_preview.hpp"

int main(int argc, char** argv) {
    if (argc != 3) return 2;
    std::ifstream input(argv[1], std::ios::binary);
    const std::vector<uint8_t> bin((std::istreambuf_iterator<char>(input)), {});
    auto& context = spk::EngineContext::instance();
    if (bin.empty() || !context.init(bin.data(), bin.size())) return 2;
    const std::filesystem::path directory(argv[2]);
    std::filesystem::create_directories(directory);
    std::ofstream index(directory / "entries.tsv");
    if (!index) return 2;
    using Recipe = std::tuple<std::string, std::string, bool>;
    std::set<Recipe> recipes;
    const auto* defaultPaper = context.data.paper("kodak_portra_endura");
    for (const auto& film : context.data.films) {
        // Starting with the product's Portra/Endura look, withFilm keeps a
        // compatible current paper and changes it for BW/cinema materials.
        const bool compatible = defaultPaper && defaultPaper->bw == film.bw && defaultPaper->cine == film.cine;
        const std::string paper = film.positive || compatible || film.target_print.empty()
            ? "kodak_portra_endura" : film.target_print;
        recipes.emplace(film.stock, paper, film.positive);
        // A stock's reference pairing is also common after loading a recipe.
        if (!film.target_print.empty()) recipes.emplace(film.stock, film.target_print, film.positive);
    }
    for (const auto& paper : context.data.papers) {
        if (!paper.bw && !paper.cine) recipes.emplace("kodak_portra_400", paper.stock, false);
    }
    int count = 0;
    for (const auto& [film, paper, scan] : recipes) for (int white = 0; white < 2; ++white) {
        spk::RenderParams params;
        params.film_stock = film; params.print_stock = paper; params.scan_film = scan;
        params.scanner_white_correction = white != 0;
        params.scanner_full_white_extension = white != 0;
        params.scanner_white_level = white ? 1.f : .98f;
        spk::Engine engine(context.data, context.cam16);
        lt::VideoLut lut; std::string error;
        if (!lt::bake_photo_preview_lut(engine, params, 33, lut, error)) {
            std::fprintf(stderr, "Default cube failed: %s / %s: %s\n", film.c_str(), paper.c_str(), error.c_str());
            return 1;
        }
        const std::string name = film + "__" + paper + (scan ? "__scan" : "__print") + (white ? "__expanded.rgb" : "__original.rgb");
        std::ofstream file(directory / name, std::ios::binary);
        file.write(reinterpret_cast<const char*>(lut.rgb.data()), lut.rgb.size() * sizeof(float));
        if (!file) return 1;
        index << film << '\t' << paper << '\t' << (scan ? "true" : "false") << '\t'
              << (white ? "expanded" : "original") << '\t' << name << '\n';
        std::printf("DEFAULT %d %s\n", ++count, name.c_str());
        std::fflush(stdout);
    }
    return index ? 0 : 1;
}
