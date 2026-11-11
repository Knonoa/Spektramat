# LibRaw（vendored，请勿就地修改）

| 项 | 值 |
| --- | --- |
| 上游 | https://github.com/LibRaw/LibRaw |
| 版本 | 0.22.3（tag `0.22.3`，`LIBRAW_VERSION_TAIL = Release`） |
| 获取方式 | `https://codeload.github.com/LibRaw/LibRaw/tar.gz/refs/tags/0.22.3` |
| 引入日期 | 2026-06-16 |
| 许可 | LGPL-2.1 或 CDDL-1.0（二选一，见 `LICENSE.LGPL` / `LICENSE.CDDL`） |

仓库只保留构建所需的子集，其余（`samples/`、`doc/`、`RawSpeed/`、`RawSpeed3/`、
DNG SDK、GoPro、autotools/Makefile、IDE 工程文件）未引入：

```
libraw/     公开头文件（libraw.h、libraw_types.h、libraw_const.h、libraw_version.h …）
src/        实现（82 个 .cpp，含 decoders / decompressors / demosaic / postprocessing …）
internal/   内部头文件（dcraw_defs.h、var_defines.h …）
```

`src/demosaic/` 只有 LibRaw 自带的 AHD / AAHD / DCB / DHT / VNG / PPG / X-Trans
实现。LibRaw 单独分发、采用 GPL 的 “demosaic packs”（AMaZE、LMMSE 等）**没有**
引入，因此本目录整体仍处于 LGPL-2.1 / CDDL-1.0 之下。若将来要加入那些算法，
必须重新评估整个二进制的许可，而不是只加几个文件。

## 构建方式

见同目录 `CMakeLists.txt`：以静态库 `rawlib` 编入 `libspektra.so`。构建时
**不定义** `USE_JPEG`、`USE_LCMS`、`USE_LCMS2`、`USE_DNGSDK`、`USE_RAWSPEED`，
因此：

- 不依赖 libjpeg / lcms2 / DNG SDK / RawSpeed；
- 内嵌 JPEG 预览只做码流提取（`unpack_thumb`），解码交给 stb_image；
- 相机色彩走 LibRaw 内置的 `cam_xyz`/`forwardmatrix` + `use_camera_matrix` 路径。

LibRaw 明确不建议用 `-ffast-math` 编译；本工程与胶片引擎一致，使用
`-fno-fast-math`。

## 升级步骤

1. 下载新 tag 的源码包，替换 `libraw/`、`src/`、`internal/` 三个目录；
2. 同步 `COPYRIGHT`、`LICENSE.*`、`Changelog.txt`；
3. 更新本文件里的版本与日期；
4. 跑 `tools/functional-audit/run.ps1`（宿主机用 MSVC 编译同一份源码，含
   `libraw_backend_test.cpp`）、设备端 `RawBackendTest`，以及真机 RAW 样片回归。

## 构建时必须排除的文件

`src/` 下有三个 `*_ph.cpp`（preprocessing、postprocessing、write）。它们是 LibRaw 为
“不带后处理”的裁剪版准备的占位实现，与真正的实现定义同名函数——其中 `dcraw_process()`
直接返回 `LIBRAW_NOT_IMPLEMENTED`。和真实现一起编译时，要么链接报重复符号，要么链接器
悄悄选中占位版本，结果是所有 RAW 都“冲洗失败”。`CMakeLists.txt` 与宿主机测试脚本都已
排除它们；升级替换 `src/` 后不要改回“全部 `*.cpp`”。

适配层 `raw_libraw.cpp` 依赖两处非公开细节，升级时要复核：`libraw_internal_data`
（读数据错误计数与条带位置）和 `get_decoder_info()` 返回的解码函数名
`"packed_dng_load_raw()"`（用于截断检测）。
