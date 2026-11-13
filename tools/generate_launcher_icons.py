#!/usr/bin/env python3
"""Generate dark-background launcher icons from the approved square artwork.

Requires Pillow. Example (from the repository root):
    python tools/generate_launcher_icons.py docs/media/app-icon-source.png

The entire input is scaled to 72dp on Android's 108dp foreground canvas.
Only the original's dark padding reaches outside the launcher mask. Bright
artwork is optically centered, preserving the canister and the full film end.
The source file is read only; generated PNGs and XML live under --res.
"""

from __future__ import annotations

import argparse
import hashlib
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFont


DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
BACKGROUND = (1, 1, 1, 255)
FOREGROUND_DP = 108
ARTWORK_DP = 72


def artwork_center(source: Image.Image) -> tuple[float, float]:
    """Ignore the black padding when centering this dark-background design."""
    rgb = source.convert("RGB")
    channels = rgb.split()
    bright = ImageChops.lighter(ImageChops.lighter(channels[0], channels[1]), channels[2])
    bounds = bright.point(lambda value: 255 if value > 50 else 0).getbbox()
    if bounds is None:
        return source.width / 2, source.height / 2
    left, top, right, bottom = bounds
    return (left + right) / 2, (top + bottom) / 2


def foreground(source: Image.Image, size: int, center: tuple[float, float]) -> Image.Image:
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    edge = round(size * ARTWORK_DP / FOREGROUND_DP)
    scaled = source.resize((edge, edge), Image.Resampling.LANCZOS)
    origin = (round(size / 2 - center[0] * edge / source.width),
              round(size / 2 - center[1] * edge / source.height))
    canvas.alpha_composite(scaled, origin)
    return canvas


def visible_icon(source: Image.Image, size: int, center: tuple[float, float]) -> Image.Image:
    # Supersample both centering and masks; legacy and adaptive have the same
    # visible 72dp content instead of letting Android magnify the source 1.5x.
    full_size = size * 6
    layer = foreground(source, full_size, center)
    full = Image.new("RGBA", layer.size, BACKGROUND)
    full.alpha_composite(layer)
    inset = full_size // 6
    return full.crop((inset, inset, full_size - inset, full_size - inset)).resize(
        (size, size), Image.Resampling.LANCZOS,
    )


def masked(icon: Image.Image, shape: str) -> Image.Image:
    size = icon.width
    mask = Image.new("L", (size * 4, size * 4), 0)
    draw = ImageDraw.Draw(mask)
    box = (0, 0, mask.width - 1, mask.height - 1)
    if shape == "circle":
        draw.ellipse(box, fill=255)
    else:
        draw.rounded_rectangle(box, radius=mask.width * .22, fill=255)
    output = icon.copy()
    output.putalpha(mask.resize(icon.size, Image.Resampling.LANCZOS))
    return output


def save_preview(resources: Path, path: Path) -> None:
    # Inspect the actual generated xxxhdpi resources, including the foreground's
    # integer positioning, rather than drawing a separate idealized mock-up.
    with Image.open(resources / "mipmap-xxxhdpi/ic_launcher.png") as image:
        legacy = image.convert("RGBA").resize((216, 216), Image.Resampling.LANCZOS)
    with Image.open(resources / "mipmap-xxxhdpi/ic_launcher_round.png") as image:
        legacy_round = image.convert("RGBA").resize((216, 216), Image.Resampling.LANCZOS)
    with Image.open(resources / "drawable-xxxhdpi/ic_launcher_foreground.png") as image:
        layer = image.convert("RGBA")
    composite = Image.new("RGBA", layer.size, BACKGROUND)
    composite.alpha_composite(layer)
    inset = layer.width // 6
    adaptive = composite.crop((inset, inset, layer.width - inset, layer.height - inset)).resize(
        (216, 216), Image.Resampling.LANCZOS,
    )
    samples = [
        ("Legacy square", legacy),
        ("Legacy round", legacy_round),
        ("Adaptive circle", masked(adaptive, "circle")),
        ("Adaptive rounded", masked(adaptive, "rounded")),
    ]
    preview = Image.new("RGB", (1080, 350), (42, 44, 48))
    draw = ImageDraw.Draw(preview)
    try:
        font = ImageFont.truetype("C:/Windows/Fonts/arial.ttf", 20)
        small = ImageFont.truetype("C:/Windows/Fonts/arial.ttf", 16)
    except OSError:
        font = ImageFont.load_default()
        small = font
    for index, (label, sample) in enumerate(samples):
        x = index * 270 + 27
        preview.paste(sample, (x, 24), sample)
        draw.text((index * 270 + 135, 260), label, fill=(235, 235, 235), font=font, anchor="mm")
    draw.text((540, 309), "108dp foreground / 72dp complete source; artwork inside the 66dp safe circle",
              fill=(185, 189, 195), font=small, anchor="mm")
    path.parent.mkdir(parents=True, exist_ok=True)
    preview.save(path)


def main() -> None:
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("--res", type=Path, default=root / "app/src/main/res")
    parser.add_argument("--preview", type=Path, default=root / "build/lab-adjustments-validation/icon-preview.png")
    args = parser.parse_args()
    before = hashlib.sha256(args.source.read_bytes()).hexdigest()
    with Image.open(args.source) as image:
        source = image.convert("RGBA")
    if source.width != source.height:
        raise ValueError("The approved launcher artwork must be square")
    center = artwork_center(source)
    for density, factor in DENSITIES.items():
        legacy = args.res / f"mipmap-{density}"
        drawable = args.res / f"drawable-{density}"
        legacy.mkdir(parents=True, exist_ok=True)
        drawable.mkdir(parents=True, exist_ok=True)
        icon = visible_icon(source, round(48 * factor), center)
        icon.save(legacy / "ic_launcher.png", optimize=True)
        masked(icon, "circle").save(legacy / "ic_launcher_round.png", optimize=True)
        foreground(source, round(FOREGROUND_DP * factor), center).save(
            drawable / "ic_launcher_foreground.png", optimize=True,
        )
    adaptive = args.res / "mipmap-anydpi-v26"
    adaptive.mkdir(parents=True, exist_ok=True)
    xml = ('<?xml version="1.0" encoding="utf-8"?>\n'
           '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
           '    <background android:drawable="@color/ic_launcher_background" />\n'
           '    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n'
           '</adaptive-icon>\n')
    for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
        (adaptive / name).write_text(xml, encoding="utf-8")
    values = args.res / "values"
    values.mkdir(parents=True, exist_ok=True)
    (values / "ic_launcher_colors.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<resources>\n    <color name="ic_launcher_background">#010101</color>\n</resources>\n',
        encoding="utf-8",
    )
    save_preview(args.res, args.preview)
    if hashlib.sha256(args.source.read_bytes()).hexdigest() != before:
        raise RuntimeError("The source artwork changed during generation")
    print(f"Source SHA-256 unchanged: {before}")
    print(f"Source: {source.width}x{source.height}; artwork center: {center}")
    print("Generated 5 legacy densities, 5 foreground densities and 2 adaptive icons")
    print(f"Preview: {args.preview.resolve()}")


if __name__ == "__main__":
    main()
