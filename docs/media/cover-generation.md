# Spektramat 横版与竖版发布海报生成记录

- 日期：2026-11-13。
- 图像服务及技能：**grsai**，统一入口 `scripts/grsai.py image`。调用前已检查 `models`、`image --help` 与技能的 `references/api_image.md`。
- 两次请求均使用精确模型 **`gpt-image-2.6`**，未替换为 flare、sunburst 或其他版本；`--quality auto`，并通过 `--images` 提交下列两张用户授权参考图。
- 横版请求 `--size 2048x1152`；竖版另行请求 `--size 1152x2048`，按竖构图重新生成背景，并非裁切横版成品。
- 标准 2.6 在脚本模型表中标为 1K。最终海报不宣称模型原生 2K：背景使用 Lanczos 重采样，真实截图、图标与文字在最终尺寸两倍的画布合成后缩小到交付尺寸。

## 输入来源

1. 已确定的应用图标：仓内 `docs/media/app-icon-source.png`，2048×2048。两版成品仅缩放并加圆角遮罩，未让模型重绘该图标。此源图也可用于 `tools/generate_launcher_icons.py` 重新生成启动图标。
2. 真实应用截图：本地验证记录 `build/lab-adjustments-validation/05-direct-controls.png`，945×2100。两版成品仅裁去顶部 58 像素的 Android 状态栏、等比缩放并加通用手机边框；未重画或添加应用控件、评分、商店徽章。
3. 下方琥珀胶片与奶油白纸面为 AI 生成的装饰主视觉，不作为应用实际输出样张。

截图内的 Fujifilm X-T4 测试照片来自 raw.pixls.us 的 CC0 / Public Domain 样片（entry 3918），经本地 RAW-to-JPEG 转换和 App 显示处理；原始链接、校验值与许可证据见 [媒体来源记录](intro-production.md#photographic-source-material) 及 [来源清单](intro-source/sources.json)。

参考图 SHA-256：

- 图标：`f6c4c55d1762e64f056f9942e259c87195ee714338e776d77c6b3c16fd1a6352`
- 截图：`46d66bfce71fe0638916889129857ae1ca163e34bc02a51bbafd76ad50bd96f5`

## 成品与用法

| 成品 | 尺寸 / 比例 | 原始生成背景尺寸 | JPEG 大小 | 建议用途 |
| --- | --- | --- | --- | --- |
| `docs/media/spektramat-cover.jpg` | 2048×1152 / 16:9 | 1672×941 | 440092 字节，约 430 KiB | GitHub README、Release 说明、横版社交预览 |
| `docs/media/spektramat-cover-portrait.jpg` | 1152×2048 / 9:16 | 941×1672 | 436006 字节，约 426 KiB | 竖版海报、手机分享、竖屏发布素材 |

两版均为渐进式 JPEG、quality 91、4:4:4 色度、优化压缩。已用 `view_image` 分别检查原始背景及最终 JPEG：比例正确，标题、中英文及真实界面清楚，无虚构功能或商店评分。

必要公开素材为两张最终 JPEG、仓内应用图标源图与本生成记录。生成背景、无损合成版和重排脚本保存在本地 `build/release-media/`：

- `spektramat-generated-background.png`、`spektramat-generated-background-portrait.png`：保留服务原始返回分辨率的背景。
- `spektramat-cover.png`、`spektramat-cover-portrait.png`：无损合成成品。
- `compose_cover.py`、`compose_cover_portrait.py`：本地重排脚本；提示词另存为 `cover-prompt.txt`、`cover-portrait-prompt.txt`。

重排字体为 Windows Segoe UI / Segoe UI Light 与 Microsoft YaHei / Microsoft YaHei Light；字体文件不随交付分发。本记录用于来源审计与说明制作方式；公开仓库不包含全部本地截图、生成背景及重排脚本，不能仅凭全新克隆精确重排海报。重新生成还会受到图像模型随机性影响；正常 Android 构建不依赖这些媒体工作文件。

两版共用文案：

> ANDROID / 光谱胶片暗房<br>
> Spektramat<br>
> 口袋里的数字暗房<br>
> A spectral film darkroom for Android<br>
> 原片调节 · 胶片模拟 · 全尺寸出片

## 横版生成提示词

```text
Create a premium editorial launch-cover BACKGROUND for Spektramat, an Android spectral film darkroom. Landscape 16:9, requested 2048 x 1152. Use the attached image 1 (the real Spektramat black film-canister and amber pixel-film app icon) and image 2 (the real app screen) as product identity and palette references only.
Art direction: warm cream-white art paper, linen gray, graphite, honey amber translucent 35 mm film, restrained analogue-photography luxury. A modern photographic darkroom interpreted as a quiet designer still life, highly realistic materials, soft broad daylight, warm cinematic grazing shadows, delicate paper grain. No people, no camera product, no neon, no saturated rainbow, no fake software interface.
Composition is crucial: keep the upper 80 percent of the left 62 percent almost empty, clean light warm ivory with only a subtle softly lit paper texture; this is negative space for a large title and bilingual product copy to be typeset later. The rightmost 38 percent should be a softly shaded light linen-gray plane, also mostly clear, where an exact real portrait screenshot will be composited later. Do NOT create a phone, tablet, screen, interface, or floating application window.
In the lower 18 percent, make a slender elegant loose S-curve of transparent honey-amber 35mm negative film with precise perforated edges, a very small unbranded graphite film cassette, a hint of a brushed aluminum darkroom tool, and realistic soft contact shadows. The film should feel tactile, beautiful and sophisticated, never cluttered; lower-right objects can be partly out of frame. Keep the center and left-middle free of props. Let one thin amber ribbon catch light along the bottom edge. The feel is calm, precise, warm and expensive, with generous whitespace and gallery-quality photography.
Do not render any words, letters, numbers, captions, logos, badges, ratings, watermarks, or invented app features. No visible images from the screenshots should be reproduced in the background; they are reference only. Do not reproduce the icon in the background. The exact app icon, app screenshot and sharp title will be added separately after generation.
```

## 竖版生成提示词

```text
Create a premium editorial launch-cover BACKGROUND for Spektramat, an Android spectral film darkroom. Portrait 9:16, requested 1152 x 2048. Use the attached image 1 (the real Spektramat black film-canister and amber pixel-film app icon) and image 2 (the real app screen) as product identity and palette references only.
Art direction: warm cream-white art paper, linen gray, graphite, honey amber translucent 35mm film, restrained analogue-photography luxury. A modern photographic darkroom interpreted as a quiet designer still life, highly realistic materials, soft broad daylight, warm cinematic grazing shadows, delicate paper grain. No people, no camera product, no neon, no saturated rainbow, no fake software interface.
Composition is crucial: the entire top 35 percent should be almost empty, clean light warm ivory with only subtle paper texture; it will hold a large product title and bilingual copy added separately. Below that, keep the middle 56 percent a clear softly shaded ivory plane, especially the central portrait area; an exact real app screenshot will be composited there later. Do NOT create a phone, tablet, screen, interface or floating application window.
At the bottom edge, in the lowest 10 percent only, place a slender elegant loose S-curve of transparent honey-amber 35mm negative film with precise perforated edges, a very small unbranded graphite film cassette cropped along the lower right edge, and realistic soft contact shadows. The film should feel tactile, beautiful and sophisticated, never cluttered. Keep the center unobstructed. Let one thin amber ribbon catch the light at the lower edge. The feel is calm, precise, warm and expensive, with generous whitespace and gallery-quality photography.
Do not render any words, letters, numbers, captions, logos, badges, ratings, watermarks or invented app features. No visible images from the screenshot should be reproduced in the background; it is reference only. Do not reproduce the icon in the background. The exact app icon, app screenshot and sharp title will be added separately after generation.
```

本记录不包含密钥、认证信息、本地用户名或临时下载签名 URL。
