# Spektramat

**A spectral film darkroom for Android.**

[![License: GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-blue.svg)](LICENSE)
![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84.svg)
[![Preview](https://img.shields.io/badge/Release-Preview-orange.svg)](https://github.com/Knonoa/Spektramat/releases/tag/v0.1.0-preview.3)

[中文](README.md) · English · **[Download Android preview](https://github.com/Knonoa/Spektramat/releases/tag/v0.1.0-preview.3)** · [Watch the introduction](https://github.com/Knonoa/Spektramat/releases/download/v0.1.0-preview.1/spektramat-intro.mp4)

[![Spektramat: a spectral film darkroom for Android](docs/media/spektramat-cover.jpg)](https://github.com/Knonoa/Spektramat/releases/download/v0.1.0-preview.1/spektramat-intro.mp4)

Start with an original. Adjust exposure and color, choose a film, then work with paper and filtration under the enlarger. Spektramat brings that process to your phone: **25 films, 9 papers, reusable recipes and a complete photo engine.** Photo processing runs locally on your device.

It builds on the spectral film model from [spektrafilm](https://github.com/andreavolpato/spektrafilm), with the native reference implementation and film/paper assets from [SpektraLab](https://github.com/JamesQiu2005/SpektraLab). This is a preview release; the interface is primarily in Chinese.

## Adjust the original, then choose your film

![Darkroom: source adjustments, film selection and enlarger printing](docs/media/features-darkroom.jpg)

Five stages take the original toward your finished photograph:

| Adjustments | Film | Texture | Enlarger | Film Edge |
| --- | --- | --- | --- | --- |
| Exposure, white balance, curves, HSL, color grading and vignette | 25 films, film format and recipes | Grain, halation, inter-layer effects and paper glare where applicable | 9 papers or direct film scanning, print exposure and yellow/magenta filtration | Perforations, edge lettering, frame numbers, fog, light leaks, film gate and date back |

- **See the correction first.** The Adjustments tab shows changes on the original; move to the following stages to assess the film and print result.
- **Keep a look you love.** Save recipes, select multiple photos and apply or print in batches. Undo/redo, automatic saving, a histogram and original-image comparison support careful adjustments.
- **Take the print with you.** Render the full-resolution original to gallery-friendly JPEG by default, or choose 16-bit lossless PNG with a matching output-space ICC profile or a 32-bit floating-point linear DNG print that retains highlight and shadow range. The date back can be enabled independently of film edges.

## Make room for the photograph

Adjust in portrait, or turn the phone to place the photograph beside its controls. A bounded viewport keeps the image in place: pinch from **1× to 8×**, double-tap to zoom or reset, and pan while enlarged. Use “适合画框” (Fit) to reset or “查看大图” (Large View) to expand it.

![Spektramat: landscape workbench, project library and camera viewfinder](docs/media/features-workspace.jpg)

**Organization and capture belong in the same darkroom.**

- **Project library:** two-column cards with stacked thumbnails, date-based imports, selection, recipes and batch printing.
- **Phone camera:** GPU film preview, JPEG/RAW capture, printing after capture and composition guides; lens and manual controls follow device capabilities, as do 1080p / 720p / 480p video options.
- **Sony USB monitor:** USB live view, shooting parameters, remote shutter and original-file transfer bring the camera into a mobile workflow.
- **Light table:** drag, scale, rotate, snap and arrange photographs, adjust the backlight, inspect with a loupe and export the arrangement.
- **Online updates:** “检查更新” (Check for Updates), directly below Settings in the sidebar, reads GitHub Releases, shows release notes and download progress, and opens Android's installer. New versions trigger an in-app reminder, including subsequent preview releases. Downloads are checked for SHA-256, package identity and signing certificate.

## From spectrum to photograph

![Spectral photo engine: source corrections, film exposure and development, spectral printing, scanning and finishing](docs/media/algorithm-pipeline.svg)

The complete photo engine models film and paper using **81 wavelength samples**. Source corrections lead into film exposure and development, then spectral printing, scanning and finishing. Slide film or direct scanning can bypass the paper stage; enabled grain, halation and other effects participate in photo processing.

**GPU LUTs for quick editing; the complete engine for final photographs.** The workbench shares an unprocessed float32 linear proxy with a 1280-pixel long edge. Film/paper swatches use 128-pixel samples and cached 33³ LUTs baked by the full engine. Interactive edits reuse GPU color conversion; idle work can refine the cube to 65³ and produce a full-engine proof including spatial effects. Export rereads the original and runs the complete film, print and scan algorithm.

**Finished photographs are not automatically metered again.** The workbench, thumbnails and final prints retain the source exposure baseline, so white-studio, high-key and low-key scenes are not automatically pulled toward middle grey. Use **Adjustments → Exposure** to change brightness; print exposure compensation is also disabled so manual EV remains visible in the output. Fixed grey-card calibration for film and paper remains enabled, and each stock's tone and highlight response still affect the result.

Live view and video use LUTs baked from the complete per-pixel film, print and scan conversion. Live view includes dynamic metering and updates the LUT in the background when a recipe changes. These LUTs cannot represent spatial or stochastic effects such as halation, grain and diffusion; film edges and dates also belong to the finished photo. Captured JPEG/RAW originals retain their original content, and imported photographs are processed by the complete engine.

**Proxy preview, original export.** The workbench shows reduced previews and stores edit parameters separately. Export reopens the full-resolution original and renders it directly into float32 pixels, without upscaling a proxy or passing through F16 display buffers. Smaller export sizes are produced after full-resolution rendering. Original file bytes remain intact; decoded highlights and shadows are preserved before the film response. See the [precision and exposure notes](docs/照片曝光保留与高调修复-2026-11-13.zh-CN.md) (Chinese).

The Adjustments tab is a source-correction preview without film, paper, grain, film edges or dates. Reduced previews and full-size output can differ with sampling scale; random textures are not guaranteed to match Python pixel for pixel. Pinned upstream revisions, asset hashes and completed deterministic/distribution checks are documented in the [algorithm validation record](docs/算法对齐验证-2026-11-12.zh-CN.md), [GPU viewfinder notes](docs/相机GPU实时预览-2026-11-12.zh-CN.md) and [video LUT notes](docs/视频LUT实现与验证-2026-11-12.zh-CN.md) (Chinese).

**Two white-point modes.** Original retains the material's paper or film white. Full extension maps the material reference white to the output white point, independently of the photograph's brightest pixel. Floating-point processing retains negative and above-white values; screen display and JPEG/PNG encoding have their own range limits. Existing custom white targets remain intact until a new mode is selected.

See the [workbench preview implementation record](docs/工作台快速预览实施记录-2026-11-16.zh-CN.md) (Chinese) for proxy caching, LUT approximation limits and export storage behavior.

## Make your first print

Requires **Android 8.0+ and OpenGL ES 3.0**. Current builds include `arm64-v8a` and `x86_64`, without 32-bit ARM.

1. Download and install **`Spektramat-v0.1.0-preview.3.apk`** from the [Release Assets](https://github.com/Knonoa/Spektramat/releases/tag/v0.1.0-preview.3).
2. Open “冲印室” (Darkroom) and import from the gallery, files or another app. JPEG, 8/16-bit PNG, supported TIFF and RAW files are accepted. HEIF/AVIF are temporarily refused when no precision-preserving decoder is available.
3. Work through **调节 → 胶片 → 质感 → 放大机 → 片边** (Adjustments → Film → Texture → Enlarger → Film Edge), then tap “冲印” (Print) to select a size and JPEG (default), PNG or DNG format.

On Android 10 and newer, MediaStore saves prints under `Pictures/Spektramat`. JPEG and PNG are gallery-friendly; DNG needs a compatible viewer. Android 8/9 uses private `files/prints` storage with export through sharing. Camera, microphone and photo permissions are requested as features need them.

<details>
<summary>Preview identity and compatibility</summary>

This release uses a **debug-signed APK**: application ID `com.spektra.latent.dev`, in-app version `0.1.0-preview.3-dev`, Release tag `v0.1.0-preview.3`. It can coexist with the release package `com.spektra.latent`; updates require the same application ID and signing key.

Photo precision, exposure, proxy and GPU checks have run on Android emulators. The implementation notes record the latest export-format and job-resume checks; physical-device validation of this revision is still pending. Hardware JPEG/RAW capture, video encoding stability and Sony USB tethering still need further testing with this version; there is no fixed frame-rate or universal device-compatibility guarantee. Full processing takes time and memory. Some advanced upstream parameters, custom color spaces and illuminants have no UI controls yet. Processing stays in float32. DNG stores linear floating-point samples without an F16 display buffer; JPEG and PNG convert to their file ranges and bit depths only during final encoding. Formats without a precision-preserving decoder produce an explicit error.

On Android 10 and newer, the Sony monitor can copy extra originals to `Download/Spektramat` and save monitor recordings to `Movies/Spektramat`. On Android 8/9, monitor recordings use app-specific storage; copying originals to the public Downloads folder is unavailable.

Please report your device, Android version, reproduction steps and screenshots in [Issues](https://github.com/Knonoa/Spektramat/issues). Include a shareable sample and recipe for image-processing problems when possible.

</details>

JPEG uses 8-bit sRGB; PNG uses lossless 16-bit encoding with an ICC profile matching the output color space and encoding. DNG contains the finished film-processed image as 32-bit linear floats, preserving negative and above-white samples. It does not recreate camera sensor RAW, and viewers may apply their own exposure or tone mapping. Auto and TIFF are no longer output options; TIFF input remains supported.

## How originals and projects are kept

Imports **copy** originals into the app's private library. Edits are stored separately and never overwrite source photographs. Default imports reuse a project named `yyyy-MM-dd` for the local date; importing into a selected project keeps that destination. The **×** on the photo only removes its workbench entry.

Long-press a project to choose “从 App 中删除” (Remove from app: persistently hide it and keep internal originals; no restore UI yet), or “从硬盘中删除” (Delete from disk: a second confirmation removes that project's internal originals, thumbnails and edits, retaining failed items for retry). Both preserve import sources, exported prints and other projects' photographs in the same date directory. **Uninstalling clears the private library and settings.** See [project and date-directory behavior](docs/项目删除与日期目录-2026-11-13.zh-CN.md) for paths and legacy-project compatibility (Chinese).

## Watch the introduction

| Format | Poster | Introduction video |
| --- | --- | --- |
| Landscape | [View JPG](docs/media/spektramat-cover.jpg) | [Watch / download](https://github.com/Knonoa/Spektramat/releases/download/v0.1.0-preview.1/spektramat-intro.mp4) · [Repository file](docs/media/spektramat-intro.mp4) |
| Portrait | [View JPG](docs/media/spektramat-cover-portrait.jpg) | [Watch / download](https://github.com/Knonoa/Spektramat/releases/download/v0.1.0-preview.1/spektramat-intro-portrait.mp4) · [Repository file](docs/media/spektramat-intro-portrait.mp4) |

## Build from source

Use **JDK 17, Android SDK 35, NDK 27.2.12479018 and CMake 3.22.1**. Open the repository in Android Studio, or configure the SDK and run:

```powershell
git clone https://github.com/Knonoa/Spektramat.git
cd Spektramat
.\gradlew.bat :app:assembleDebug
```

On macOS / Linux, use `./gradlew :app:assembleDebug`. Output: `app/build/outputs/apk/debug/app-debug.apk`. Engine assets are included; normal builds need no rebaking. See the [development notes](docs/开发.md) and [reference parity tests](tools/parity/README.md) for the Kotlin/Compose app, C++20 engine and validation entry points.

## Open-source license and acknowledgements

Code is released under **[GNU GPL v3](LICENSE)**. Third-party data and components retain their own licenses. Spektramat's spectral workflow builds on the work of these projects and authors:

| Source | Contribution and license |
| --- | --- |
| **Andrea Volpato / [spektrafilm](https://github.com/andreavolpato/spektrafilm)** | Film process model and measured profiles. Profiles, derived LUTs and related encoded assets use **CC BY-SA 4.0**; see the [attribution notice](engine/src/main/assets/licenses/spektrafilm/NOTICE.txt), [license](engine/src/main/assets/licenses/spektrafilm/CC-BY-SA-4.0.txt) and [provenance/change record](engine/src/main/assets/licenses/spektrafilm/CHANGELOG.txt). |
| **[SpektraLab](https://github.com/JamesQiu2005/SpektraLab)** | Native reference implementation and product film/paper assets, with its [GPL v3 license](engine/src/main/assets/licenses/SpektraLab/GPL-3.0.txt) retained. Additional monochrome profiles are credited to **Hanze Qiu**. |
| **[LibRaw](https://www.libraw.org/), [libgphoto2](https://github.com/gphoto/libgphoto2), stb** | RAW decoding (LGPL / CDDL), Sony PTP protocol reference and image utilities. |
| **[Little CMS 2.17](https://github.com/mm2/Little-CMS), [LibTIFF 4.7.0](https://libtiff.gitlab.io/libtiff/)** | Native ICC floating-point colour conversion and TIFF 8/16-bit integer or 32-bit float samples. Floating-point DNG export bypasses F16 and 8-bit display buffers; JPEG/PNG convert only during final encoding. |
| **TeX Gyre Heros, DejaVu Sans Mono** | Film-edge fonts under the GUST Font License and DejaVu font license respectively. |

Full notices are in [licenses](engine/src/main/assets/licenses/), [fonts](engine/src/main/assets/fonts/), the [stb license](engine/src/main/cpp/third_party/STB_LICENSE), and the [Little CMS / LibTIFF licenses](engine/src/main/assets/licenses/). Redistribution must preserve copyright and data attribution, and provide source as required by applicable licenses.

This page shows real app screens with CC0 test photographs from raw.pixls.us; see the [media sources and video production record](docs/media/intro-production.md). Both posters use decorative backgrounds generated through grsai with **gpt-image-2.6**, composited with the real interface and icon; see the [poster production record](docs/media/cover-generation.md).
