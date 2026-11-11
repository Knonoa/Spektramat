# 潜影 Latent

装在手机里的胶片工作站：把数字影像当作落在胶片上的光，按 [spektrafilm](https://github.com/andreavolpato/spektrafilm)
的光谱模型曝光、冲洗、印相。产品怎么设计、分几期做，见 [docs/产品蓝图.md](docs/产品蓝图.md)。

## 现在能做什么

| 部分 | 状态 |
| --- | --- |
| 片库：从相册、文件、其他 App 导入（JPEG/PNG/HEIC 与 RAW），按卷显示 | 可用 |
| 暗房：原片 / 胶片 / 印相三道工序，GPU 实时预览，撤销重做，自动保存 | 可用 |
| 冲印：后台全尺寸出片，存进相册 `Pictures/Latent`，屏幕一角的小指示显示进度 | 可用 |
| 相机：胶片实时取景、按设备能力列出镜头和手动项、拍 JPEG / RAW 进片库、带胶片效果的录像 | 可用（RAW 写文件未能在模拟器上验证） |
| 联机拍摄、曲线/HSL/色彩分级、裁切、配方、批量 | 见蓝图分期 |

以上都只在模拟器（x86_64，SwiftShader 软件渲染）上验证过，还没有上过真机。相机各项分别验证到哪一步，见蓝图第 7 节的表。

## 构建

需要 Android SDK（compileSdk 35）、NDK 27.2.12479018、CMake 3.22.1、JDK 17。

```bash
./gradlew :app:assembleDebug
```

调试包的包名是 `com.spektra.latent.dev`，可以和正式包并存。

相机用的是 CameraX 1.5.3（各家手机的兼容处理在它里面；RAW 拍摄从 1.5 起才有），它要求 Android Gradle 插件 8.6 以上，
所以工程用的是 8.6.1。再新的 CameraX 1.6 需要 compileSdk 36 和插件 8.9，暂时没有跟。

在模拟器上试相机时注意：Android 12（API 31）的 64 位模拟器镜像，相机驱动一压 JPEG 就会崩溃，拍照在它上面验证不了，
取景和录像可以；Android 16 的镜像拍照正常。先给权限可以省掉弹窗：

```bash
adb shell pm grant com.spektra.latent.dev android.permission.CAMERA
adb shell pm grant com.spektra.latent.dev android.permission.RECORD_AUDIO
```

## 检验

GPU 预览和 CPU 成片用的是同一份胶片数据。下面这个检查在电脑上（MSVC）把两条路径逐一对比：
25 款胶片的颜色相差不超过 1 个 8 位色阶，颗粒的均值和幅度一致，数据库里没有滤色数据的胶片/相纸组合印出来的灰是中性的。

```bash
pwsh -NoProfile -File tools/host/run.ps1
```

输出以 `FAST_VS_ENGINE_FAILURES 0` 结尾才算通过。

## 往调试包里放测试照片

调试包启动时会把自己外部目录 `inbox` 里的文件导入片库，然后删掉它们，不用经过系统选择器：

```bash
adb push 照片.jpg /sdcard/Android/data/com.spektra.latent.dev/files/inbox/
```

## 目录

```
engine/   C++ 胶片引擎（spektrafilm 的移植）、RAW 解码（内置 + LibRaw）、JNI、胶片数据
          src/main/shared/shaders/latent.glsl 同时编译成着色器和 C++，预览和成片共用
app/      Kotlin + Jetpack Compose：界面、片库、GPU 管线、相机
          camera/  镜头与能力探测（Lenses）、相机状态与拍摄（CameraRig）
          gl/      暗房预览（FilmPreview）、取景与录像（CameraFilm）共用同一个 GL 上下文和同一份胶片表
tools/    电脑上的检验程序
docs/     产品蓝图
```

## 许可

胶片模型与数据来自 spektrafilm 及其衍生项目（GPL-3.0 / CC-BY-SA-4.0），LibRaw 为 LGPL-2.1 / CDDL-1.0。
分发本 App 时须以 GPL-3.0 提供源码，并保留各自的许可文本。
