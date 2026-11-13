#!/usr/bin/env python3
"""Render the two Spektramat introduction videos from genuine app captures.

Requires Python 3.10+, Pillow, numpy, opencv-python and imageio-ffmpeg.
Run: python tools/create_intro_video.py --orientation both
The 1.3 MB of curated source captures are in docs/media/intro-source.
All temporary video/audio and QA frames go into the ignored build directory.
"""
from __future__ import annotations

import argparse
from functools import lru_cache
import math
import os
from pathlib import Path
import shutil
import subprocess
import wave

import cv2
import imageio_ffmpeg
import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageFilter

ROOT = Path(__file__).resolve().parents[1]
MEDIA = ROOT / "docs/media"
ASSETS = MEDIA / "intro-source"
BUILD = ROOT / "build/intro-media"
FPS = 24
IVORY = (244, 239, 229)
INK = "#29271f"
MUTED = "#787267"
AMBER = "#bc791f"
FONT_DIR = Path(os.environ.get("WINDIR", "C:/Windows")) / "Fonts"

SCENES = [
    dict(duration=4.5, key="hello", label="FILM COLOR, IN YOUR HANDS", title=["让色彩，", "慢慢显影。"],
         en=["Film color. A little closer."], body=["你的 Android 胶片色彩工作站"],
         detail="Your film color workstation on Android", source="hero.jpg"),
    dict(duration=5.5, key="film", label="01 / FIND YOUR FILM", title=["选择胶片", "找到色彩性格"],
         en=["Choose a film.", "Find your color."], body=["彩色负片 · 反转片 · 电影负片 · 黑白负片"],
         detail="Color negative · slide · cinema · black & white", source="film.mp4"),
    dict(duration=5.5, key="adjust", label="02 / SHAPE THE LIGHT", title=["从曝光开始", "把细节调回来"],
         en=["Exposure, temperature, tint.", "Make the photograph yours."], body=["调节曝光、色温与色调，随时查看变化"],
         detail="Fine control, with a visible result", source="adjust.mp4"),
    dict(duration=6.0, key="compare", label="03 / SEE EVERY CHANGE", title=["原图与效果", "一划即见"],
         en=["Slide to compare.", "See every change."], body=["滑动分隔线 · 长按查看原图 · 双指缩放"],
         detail="Split view · hold for original · pinch to zoom", source="compare.mp4"),
    dict(duration=5.0, key="wide", label="04 / ROOM TO CREATE", title=["转过屏幕", "展开工作台"],
         en=["A wider view.", "More room to create."], body=["横竖屏自适应，让照片与调节各得其所"],
         detail="A responsive workspace for every orientation", source="workbench-wide.jpg"),
    dict(duration=5.0, key="projects", label="05 / KEEP YOUR WORK TOGETHER", title=["按项目整理", "留住每次创作"],
         en=["Your photographs.", "Beautifully organized."], body=["导入照片按日期归档，也可整理到自己的项目"],
         detail="Daily imports and personal projects", source="projects.jpg"),
    dict(duration=6.0, key="camera", label="06 / PREVIEW IN THE MOMENT", title=["实时看见", "胶片的方向"],
         en=["Frame with film color.", "Capture the moment."], body=["实时取景与视频：GPU LUT", "照片成片：完整 Spektrafilm 算法"],
         detail="Live preview & video: GPU LUT\nFinished photos: full Spektrafilm pipeline", source="camera.jpg"),
    dict(duration=4.5, key="print", label="07 / DEVELOP THE FINAL PHOTOGRAPH", title=["完整冲印", "让照片成为作品"],
         en=["Develop the full photograph.", "Save the result."], body=["JPEG / 浮点 TIFF · 后台冲印"],
         detail="JPEG / float TIFF · background development", source="print.jpg"),
    dict(duration=4.0, key="end", label="ANDROID PREVIEW", title=["Spektramat"],
         en=["Film color. Made personal."], body=["Android 预览版"],
         detail="github.com/Knonoa/Spektramat", source="hero.jpg"),
]
DURATION = sum(s["duration"] for s in SCENES)


@lru_cache(maxsize=80)
def font(size: int, kind="regular"):
    choices = {
        "regular": ["msyh.ttc", "DejaVuSans.ttf"],
        "bold": ["msyhbd.ttc", "DejaVuSans-Bold.ttf"],
        "english": ["segoeui.ttf", "DejaVuSans.ttf"],
        "brand": ["georgia.ttf", "DejaVuSerif.ttf"],
    }[kind]
    for name in choices:
        for path in [FONT_DIR / name, Path("/usr/share/fonts/truetype/dejavu") / name]:
            if path.exists():
                return ImageFont.truetype(str(path), size)
    raise RuntimeError("Install a Chinese font (Microsoft YaHei), or update font() for your system.")


def text(draw, xy, value, size, fill=INK, kind="regular", anchor=None):
    draw.text(xy, value, font=font(size, kind), fill=fill, anchor=anchor, stroke_width=0)


def lines(draw, x, y, values, size, fill=INK, kind="regular", gap=None, center=False):
    step = gap or int(size * 1.36)
    for value in values:
        text(draw, (x, y), value, size, fill, kind, "mt" if center else None)
        y += step
    return y


def background(size):
    w, h = size
    # A quiet amber light on paper, generated mathematically, not from stock media.
    yy, xx = np.mgrid[0:h, 0:w]
    glow = np.exp(-(((xx / w - .86) / .48) ** 2 + ((yy / h - .42) / .65) ** 2))
    cool = np.exp(-(((xx / w - .08) / .55) ** 2 + ((yy / h - .95) / .4) ** 2))
    arr = np.zeros((h, w, 3), np.float32) + np.array(IVORY)
    arr += glow[..., None] * np.array([1, -8, -25])
    arr += cool[..., None] * np.array([-5, -3, -1])
    return Image.fromarray(np.uint8(np.clip(arr, 0, 255)), "RGB")


def shadow_plate(size, box, radius=30):
    layer = Image.new("RGBA", size)
    d = ImageDraw.Draw(layer)
    x, y, w, h = box
    d.rounded_rectangle((x - 8, y + 12, x + w + 8, y + h + 18), radius=radius, fill=(73, 57, 32, 38))
    return layer.filter(ImageFilter.GaussianBlur(22))


class Source:
    def __init__(self, name):
        self.path = ASSETS / name
        self.video = self.path.suffix == ".mp4"
        self.last = -1
        self.frame = None
        self.static = None
        if self.video:
            self.cap = cv2.VideoCapture(str(self.path))
            if not self.cap.isOpened():
                raise RuntimeError(f"Cannot decode {self.path}")
            self.fps = self.cap.get(cv2.CAP_PROP_FPS)
            self.count = int(self.cap.get(cv2.CAP_PROP_FRAME_COUNT))
            self.duration = self.count / self.fps
            self.width = int(self.cap.get(cv2.CAP_PROP_FRAME_WIDTH))
            self.height = int(self.cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
        else:
            self.static = Image.open(self.path).convert("RGB")
            self.width, self.height = self.static.size

    def at(self, t, scene_duration):
        if not self.video:
            return self.static
        target = min(self.count - 1, int(max(0, t) / scene_duration * (self.count - 1)))
        if target < self.last:
            self.cap.set(cv2.CAP_PROP_POS_FRAMES, target)
            self.last = target - 1
        while self.last < target:
            ok, frame = self.cap.read()
            if not ok:
                break
            self.last += 1
            self.frame = Image.fromarray(cv2.cvtColor(frame, cv2.COLOR_BGR2RGB))
        return self.frame

    def close(self):
        if self.video:
            self.cap.release()


class Composition:
    def __init__(self, orientation):
        self.portrait = orientation == "portrait"
        self.size = (1080, 1920) if self.portrait else (1920, 1080)
        self.sources = [Source(s["source"]) for s in SCENES]
        self.plates = []
        self.boxes = []
        self.masks = []
        self.static_screens = {}
        self.starts = []
        start = 0
        for i, scene in enumerate(SCENES):
            self.starts.append(start)
            start += scene["duration"]
            plate, box = self.plate(i, scene)
            self.plates.append(plate)
            self.boxes.append(box)
            mask = Image.new("L", box[2:])
            ImageDraw.Draw(mask).rounded_rectangle((0, 0, box[2]-1, box[3]-1), radius=24 if scene["key"] != "wide" else 16, fill=255)
            self.masks.append(mask)
            if not self.sources[i].video:
                self.static_screens[i] = self.sources[i].static.resize(box[2:], Image.Resampling.LANCZOS)

    def plate(self, i, scene):
        p = self.portrait
        w, h = self.size
        base = background(self.size)
        d = ImageDraw.Draw(base)
        key = scene["key"]
        if p:
            text(d, (76, 60), "S P E K T R A M A T", 25, MUTED, "english")
            d.rounded_rectangle((814, 53, 1004, 100), radius=24, outline="#c5bba8", width=1)
            text(d, (909, 76), "ANDROID", 19, MUTED, "english", "mm")
            text(d, (78, 151), scene["label"], 23, AMBER, "english")
            if key == "hello":
                text(d, (75, 194), "Spektramat", 96, INK, "brand")
                text(d, (78, 330), "让色彩，慢慢显影。", 50, INK, "bold")
                text(d, (80, 413), "Film color. A little closer.", 31, MUTED, "english")
                box = (265, 536, 550, 1182)
                text(d, (540, 1785), scene["body"][0], 31, INK, "regular", "mt")
                text(d, (540, 1840), scene["detail"], 22, MUTED, "english", "mt")
            elif key == "end":
                text(d, (540, 305), "Spektramat", 100, INK, "brand", "mt")
                text(d, (540, 465), "让色彩，慢慢显影。", 48, INK, "bold", "mt")
                text(d, (540, 551), scene["en"][0], 32, MUTED, "english", "mt")
                box = (335, 705, 410, 881)
                text(d, (540, 1648), "Android 预览版", 38, AMBER, "regular", "mt")
                text(d, (540, 1735), "github.com/Knonoa/Spektramat", 31, INK, "english", "mt")
                text(d, (540, 1800), "v0.1.0-preview.1", 25, MUTED, "english", "mt")
            elif key == "wide":
                lines(d, 78, 214, scene["title"], 68, kind="bold", gap=95)
                lines(d, 80, 440, scene["en"], 37, MUTED, "english", gap=52)
                # The full landscape UI stays horizontal, never cropped into a phone.
                box = (55, 760, 970, 527)
                d.rounded_rectangle((75, 1405, 1005, 1650), radius=28, fill="#eee5d5", outline="#d9ccb5", width=1)
                text(d, (112, 1447), "照片更大，操作更从容", 39, INK, "bold")
                text(d, (112, 1530), "横竖屏自适应工作台", 32, MUTED)
                text(d, (80, 1770), "A responsive workspace for every orientation", 29, MUTED, "english")
            else:
                lines(d, 78, 210, scene["title"], 64, kind="bold", gap=88)
                lines(d, 80, 419, scene["en"], 33, MUTED, "english", gap=44)
                if key == "camera":
                    box = (308, 655, 464, 997)
                    lines(d, 540, 1730, scene["body"], 29, INK, gap=49, center=True)
                    text(d, (540, 1850), "Emulator capture · GPU preview is an approximation", 21, MUTED, "english", "mt")
                else:
                    box = (260, 590, 560, 1204)
                    body = scene["body"][0]
                    # Keep captions inside mobile safe margins.
                    bs = 27 if len(body) < 25 else 25
                    text(d, (540, 1834), body, bs, INK, "regular", "mt")
        else:
            text(d, (118, 65), "S P E K T R A M A T", 24, MUTED, "english")
            text(d, (1800, 65), "ANDROID PREVIEW", 21, MUTED, "english", "rt")
            if key != "wide":
                text(d, (120, 201), scene["label"], 22, AMBER, "english")
            if key == "hello":
                text(d, (112, 262), "Spektramat", 118, INK, "brand")
                text(d, (120, 442), "让色彩，慢慢显影。", 58, INK, "bold")
                text(d, (124, 546), scene["en"][0], 35, MUTED, "english")
                text(d, (124, 698), scene["body"][0], 29, INK)
                text(d, (124, 752), scene["detail"], 25, MUTED, "english")
                box = (1272, 95, 420, 903)
            elif key == "end":
                text(d, (112, 286), "Spektramat", 124, INK, "brand")
                text(d, (122, 470), "让色彩，慢慢显影。", 57, INK, "bold")
                text(d, (125, 570), scene["en"][0], 33, MUTED, "english")
                text(d, (125, 721), "Android 预览版 · v0.1.0-preview.1", 28, AMBER)
                text(d, (125, 785), "github.com/Knonoa/Spektramat", 31, INK, "english")
                box = (1300, 145, 370, 795)
            elif key == "wide":
                text(d, (120, 109), scene["label"], 18, AMBER, "english")
                text(d, (118, 151), "转过屏幕，展开工作台", 55, INK, "bold")
                text(d, (122, 242), "A wider view. More room to create.", 29, MUTED, "english")
                box = (278, 318, 1364, 741)
                # Leave the existing full native app view intact.
            else:
                lines(d, 120, 273, scene["title"], 62, kind="bold", gap=88)
                lines(d, 124, 509, scene["en"], 35, MUTED, "english", gap=50)
                lines(d, 124, 719, scene["body"], 28, INK, gap=49)
                lines(d, 124, 823, scene["detail"].split("\n"), 24, MUTED, "english", gap=36)
                box = (1272, 95, 420, 903)
                if key == "camera":
                    text(d, (124, 956), "模拟器实录 · 取景为近似，空间效果以成片为准", 21, MUTED)
        # Device decoration belongs to the video; every pixel within its screen is a capture.
        base = Image.alpha_composite(base.convert("RGBA"), shadow_plate(self.size, box))
        d = ImageDraw.Draw(base)
        x, y, bw, bh = box
        d.rounded_rectangle((x-5, y-5, x+bw+5, y+bh+5), radius=29, fill="#faf8f2", outline="#cec5b7", width=2)
        if not p and key != "wide":
            text(d, (120, 1011), "实际 App 界面  /  REAL APP CAPTURE", 18, MUTED, "english")
        return base.convert("RGB"), box

    def frame(self, idx, local):
        s = SCENES[idx]
        base = self.plates[idx].copy()
        x, y, w, h = self.boxes[idx]
        screen = self.static_screens.get(idx)
        if screen is None:
            screen = self.sources[idx].at(local, s["duration"]).resize((w, h), Image.Resampling.LANCZOS)
        base.paste(screen, (x, y), self.masks[idx])
        return base

    def close(self):
        for source in self.sources:
            source.close()


def synth_music(destination):
    """Original low-volume ambient score: analytic tones, no sampled audio."""
    sr = 48000
    n = int(DURATION * sr)
    left = np.zeros(n, np.float64)
    right = np.zeros(n, np.float64)
    chords = [[48, 55, 59, 62, 67], [45, 52, 55, 59, 64],
              [41, 48, 52, 55, 60], [43, 50, 55, 57, 62]]
    rng = np.random.default_rng(20261113)
    def add_tone(start, length, midi, level, pan=.5, pluck=False):
        begin = int(start * sr)
        count = min(int(length * sr), n-begin)
        if count <= 0:
            return
        t = np.arange(count) / sr
        hz = 440 * 2 ** ((midi-69) / 12)
        if pluck:
            env = (1-np.exp(-t*75)) * np.exp(-t*1.35)
        else:
            env = np.minimum(1, t/1.5) * np.minimum(1, (length-t)/1.9)
            env = np.maximum(env, 0) ** 1.4
        phase = .003 * np.sin(2*np.pi*.12*t)
        signal = (np.sin(2*np.pi*hz*t+phase) + .15*np.sin(2*np.pi*hz*2*t) + .04*np.sin(2*np.pi*hz*3*t)) * env * level
        left[begin:begin+count] += signal * math.sqrt(1-pan)
        right[begin:begin+count] += signal * math.sqrt(pan)
    for block, start in enumerate(np.arange(0, DURATION, 8)):
        chord = chords[block % len(chords)]
        for j, midi in enumerate(chord):
            add_tone(float(start), 9, midi, .023 if j == 0 else .012, .18+j*.15)
        for j in range(8):
            add_tone(float(start)+j*.92+.3, 3.6, chord[(j*2)%5]+12, .011, .3+.4*rng.random(), True)
    # Delicate, original transition chimes rather than recorded interface sounds.
    cursor = 0
    for scene in SCENES[:-1]:
        cursor += scene["duration"]
        add_tone(cursor, .5, 83, .012, .65, True)
    t = np.arange(n) / sr
    fade = np.minimum(1, t/2.2) * np.minimum(1, (DURATION-t)/2.5)
    stereo = np.stack((left, right), axis=1) * fade[:, None]
    stereo *= .18 / max(float(np.max(np.abs(stereo))), .001)
    pcm = np.int16(np.clip(stereo, -.95, .95)*32767)
    with wave.open(str(destination), "wb") as wav:
        wav.setnchannels(2); wav.setsampwidth(2); wav.setframerate(sr); wav.writeframes(pcm.tobytes())


def subtitles():
    def tc(seconds):
        ms = round(seconds*1000)
        return f"{ms//3600000:02}:{ms//60000%60:02}:{ms//1000%60:02},{ms%1000:03}"
    chunks = []
    start = 0.
    for i, s in enumerate(SCENES):
        cn = "，".join(s["title"]).replace("，，", "，")
        en = " ".join(s["en"])
        if s["key"] == "camera":
            cn = "实时取景与视频使用 GPU LUT；照片成片使用完整 Spektrafilm 算法。"
            en = "GPU LUT for live preview and video; full Spektrafilm pipeline for finished photos."
        elif s["key"] == "end":
            cn = "Spektramat · Android 预览版 · github.com/Knonoa/Spektramat"
            en = "Spektramat · Android preview · Film color. Made personal."
        chunks.append(f"{i+1}\n{tc(start)} --> {tc(start+s['duration'])}\n{cn}\n{en}\n")
        start += s["duration"]
    (MEDIA / "spektramat-intro.srt").write_text("\n".join(chunks), encoding="utf-8")


def render(orientation, preview_only=False):
    comp = Composition(orientation)
    suffix = "-portrait" if orientation == "portrait" else ""
    BUILD.mkdir(parents=True, exist_ok=True)
    qa = BUILD / ("qa-"+orientation)
    qa.mkdir(exist_ok=True)
    # Keyframes are rendered through exactly the same compositor as the video.
    keys = []
    for i, s in enumerate(SCENES):
        frame = comp.frame(i, s["duration"]*.62)
        path = qa / f"{i+1:02}-{s['key']}.jpg"
        frame.save(path, quality=91)
        keys.append(frame)
    comp.frame(0, 3).save(MEDIA / f"spektramat-intro{suffix}-poster.jpg", quality=94, optimize=True)
    thumb_w = 480 if not comp.portrait else 270
    thumb_h = 270 if not comp.portrait else 480
    sheet = Image.new("RGB", (thumb_w*3, (thumb_h+45)*3), IVORY)
    draw = ImageDraw.Draw(sheet)
    for i, frame in enumerate(keys):
        x, y = i%3*thumb_w, i//3*(thumb_h+45)
        sheet.paste(frame.resize((thumb_w, thumb_h), Image.Resampling.LANCZOS), (x,y))
        text(draw, (x+14,y+thumb_h+10), f"{i+1:02}  {SCENES[i]['key']}", 18, MUTED, "english")
    sheet.save(BUILD / f"intro-contact-{orientation}.jpg", quality=92)
    if preview_only:
        comp.close(); return
    # Reset decoders after QA seeks, then stream frames into FFmpeg.
    for source in comp.sources:
        if source.video:
            source.cap.set(cv2.CAP_PROP_POS_FRAMES, 0); source.last=-1; source.frame=None
    ff = imageio_ffmpeg.get_ffmpeg_exe()
    raw_out = BUILD / f"intro-{orientation}-silent.mp4"
    music = BUILD / f"intro-{orientation}-ambient.wav"
    synth_music(music)
    cmd = [ff, "-y", "-f", "rawvideo", "-vcodec", "rawvideo", "-pix_fmt", "rgb24",
           "-s", f"{comp.size[0]}x{comp.size[1]}", "-r", str(FPS), "-i", "-", "-an",
           "-c:v", "libx264", "-preset", "veryfast", "-crf", "21", "-maxrate", "3500k", "-bufsize", "7000k",
           "-pix_fmt", "yuv420p", "-threads", "4", "-movflags", "+faststart", str(raw_out)]
    log = (BUILD / f"encode-{orientation}.log").open("wb")
    process = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=log)
    previous_idx = 0
    previous_final = None
    last = None
    for f in range(round(DURATION*FPS)):
        t = f/FPS
        idx = max(i for i, start in enumerate(comp.starts) if start <= t+1e-7)
        local = t-comp.starts[idx]
        if idx != previous_idx:
            previous_final = last
            previous_idx = idx
        frame = comp.frame(idx, local)
        if local < .32 and previous_final is not None:
            a = local/.32
            a = a*a*(3-2*a)
            frame = Image.blend(previous_final, frame, a)
        d = ImageDraw.Draw(frame)
        w,h = comp.size
        d.line((70,h-23,w-70,h-23), fill="#d9cfbd", width=2)
        d.line((70,h-23,70+int((w-140)*t/DURATION),h-23), fill="#c1832b", width=3)
        # A short paper-colored fade-in and fade-out avoids a hard black flash.
        if t < .45:
            frame = Image.blend(Image.new("RGB",comp.size,IVORY), frame, t/.45)
        if t > DURATION-.5:
            frame = Image.blend(Image.new("RGB",comp.size,IVORY), frame, (DURATION-t)/.5)
        process.stdin.write(frame.tobytes())
        last = frame
        if f % (FPS*5) == 0:
            print(f"{orientation}: {t:.0f}/{DURATION:.0f}s", flush=True)
    process.stdin.close()
    code = process.wait()
    log.close()
    comp.close()
    if code:
        raise RuntimeError(f"FFmpeg encoding failed, see {log.name}")
    output = MEDIA / f"spektramat-intro{suffix}.mp4"
    subprocess.run([ff,"-y","-i",str(raw_out),"-i",str(music),"-map","0:v:0","-map","1:a:0",
                    "-c:v","copy","-c:a","aac","-b:a","128k","-ar","48000","-t",str(DURATION),
                    "-movflags","+faststart","-metadata","title=Spektramat — Android Preview",
                    "-metadata","comment=Real app captures; original synthesized ambient score",str(output)],
                   check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    print(f"Created {output} ({output.stat().st_size/1024/1024:.2f} MiB)", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--orientation", choices=["landscape","portrait","both"], default="both")
    parser.add_argument("--preview-only", action="store_true")
    args = parser.parse_args()
    MEDIA.mkdir(parents=True,exist_ok=True)
    subtitles()
    orientations = ["landscape","portrait"] if args.orientation == "both" else [args.orientation]
    for orientation in orientations:
        render(orientation,args.preview_only)


if __name__ == "__main__":
    main()
