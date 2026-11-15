"""
Generates the gritty MOVIES / TV SERIES poster buttons for the home screen,
in the style of the Forbidden Streams VOD logo.

    python3 art/make_posters.py

Needs Pillow + numpy, and the Inter Black and FreeMono Bold fonts (paths below).
Writes app/src/main/res/drawable-nodpi/poster_movies.jpg and poster_series.jpg.
"""
import math
import os
import random

import numpy as np
from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LOGO = os.path.join(ROOT, "art", "logo_source.jpg")
OUT_DIR = os.path.join(ROOT, "app", "src", "main", "res", "drawable-nodpi")
TITLE_FONT = "/usr/share/fonts/opentype/inter/Inter-Black.otf"
SUB_FONT = "/usr/share/fonts/truetype/freefont/FreeMonoBold.ttf"

# Logo palette
GREEN = (108, 205, 78)
GLOW = (144, 225, 93)
RED = (176, 26, 28)  # logo red, nudged brighter so it holds up on a TV
BONE = (241, 241, 236)
BG = (15, 26, 27)
SCREEN = (34, 49, 56)

W, H = 1600, 2400          # work size (2x)
OUT = (800, 1200)          # shipped size, 2:3


def noise(size, scale, seed):
    """Smooth-ish value noise in 0..1."""
    rng = np.random.default_rng(seed)
    small = rng.random((max(2, size[1] // scale), max(2, size[0] // scale))).astype(np.float32)
    img = Image.fromarray((small * 255).astype(np.uint8)).resize(size, Image.BICUBIC)
    return np.asarray(img).astype(np.float32) / 255.0


def grain(size, amount, seed):
    rng = np.random.default_rng(seed)
    return rng.normal(0, amount, (size[1], size[0])).astype(np.float32)


def distress(layer, seed, strength=0.55, speckle=0.12):
    """Eat holes into a layer's alpha so it looks worn and chipped."""
    a = np.asarray(layer.split()[-1]).astype(np.float32) / 255.0
    n = noise(layer.size, 18, seed) * 0.6 + noise(layer.size, 4, seed + 1) * 0.4
    worn = np.clip((n - (1 - strength)) * 4.0, 0, 1)          # big chipped patches
    rng = np.random.default_rng(seed + 2)
    pits = (rng.random(a.shape) < speckle).astype(np.float32)  # fine pitting
    pits = np.asarray(Image.fromarray((pits * 255).astype(np.uint8)).filter(ImageFilter.MaxFilter(3))).astype(np.float32) / 255.0
    a = a * (1 - worn * 0.85) * (1 - pits * 0.7)
    # scratches
    sc = Image.new("L", layer.size, 0)
    d = ImageDraw.Draw(sc)
    r = random.Random(seed)
    for _ in range(26):
        x, y = r.randrange(layer.size[0]), r.randrange(layer.size[1])
        ang = r.uniform(-0.5, 0.5) + (math.pi / 2 if r.random() < 0.3 else 0)
        ln = r.randrange(80, 420)
        d.line([(x, y), (x + math.cos(ang) * ln, y + math.sin(ang) * ln)], fill=255, width=r.choice([2, 3, 4]))
    a = a * (1 - np.asarray(sc).astype(np.float32) / 255.0 * 0.9)
    out = layer.copy()
    out.putalpha(Image.fromarray(np.clip(a * 255, 0, 255).astype(np.uint8)))
    return out


def glitch(layer, seed, bands=7, max_shift=46, split=10):
    """Horizontal slice offsets plus an RGB split, like the logo's CRT text."""
    r = random.Random(seed)
    out = layer.copy()
    for _ in range(bands):
        y = r.randrange(0, layer.size[1] - 20)
        h = r.randrange(6, 40)
        dx = r.randint(-max_shift, max_shift)
        band = layer.crop((0, y, layer.size[0], y + h))
        out.paste((0, 0, 0, 0), (0, y, layer.size[0], y + h))
        out.alpha_composite(band, (dx, y))
    a = out.split()[-1]
    red = Image.new("RGBA", out.size, (255, 40, 60, 0)); red.putalpha(a.point(lambda v: v * 0.55))
    cyan = Image.new("RGBA", out.size, (40, 230, 255, 0)); cyan.putalpha(a.point(lambda v: v * 0.45))
    base = Image.new("RGBA", out.size, (0, 0, 0, 0))
    base.alpha_composite(red, (-split, 0))
    base.alpha_composite(cyan, (split, 0))
    base.alpha_composite(out)
    return base


def background(accent, seed):
    logo = Image.open(LOGO).convert("RGB")
    lw, lh = logo.size
    # Grimy, peeling wall from the left side of the logo
    wall = logo.crop((0, 0, int(lw * 0.27), int(lh * 0.405))).resize((W, H), Image.BICUBIC)
    wall = wall.filter(ImageFilter.GaussianBlur(1.2))
    arr = np.asarray(wall).astype(np.float32)
    arr *= 0.55
    # Dark-teal grade
    arr = arr * 0.7 + np.array(BG, dtype=np.float32) * 0.3
    # Accent glow from the middle (where the icon sits)
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    d = np.sqrt(((xx - W / 2) / W) ** 2 + ((yy - H * 0.42) / H) ** 2)
    glow = np.clip(1 - d * 2.2, 0, 1)[..., None] ** 1.6
    arr = arr + glow * np.array(accent, dtype=np.float32) * 0.38
    # Grain
    arr += grain((W, H), 9, seed)[..., None]
    # Vignette
    vig = np.clip(1 - (d * 1.25) ** 2, 0.25, 1)[..., None]
    arr *= vig
    return Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8)).convert("RGBA")


def clapperboard():
    lay = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(lay)
    cx, cy = W // 2, int(H * 0.44)
    bw, bh = 820, 600
    x0, y0 = cx - bw // 2, cy - bh // 2 + 90
    # Board
    d.rounded_rectangle((x0, y0, x0 + bw, y0 + bh), 34, fill=(22, 24, 24, 255), outline=BONE + (255,), width=18)
    for i, fy in enumerate([0.36, 0.62]):
        y = y0 + int(bh * fy)
        d.line((x0 + 50, y, x0 + bw - 50, y), fill=BONE + (230,), width=12)
    d.line((x0 + bw // 2, y0 + int(bh * 0.36), x0 + bw // 2, y0 + int(bh * 0.62)), fill=BONE + (230,), width=12)
    # Hinged stick, tilted open
    stick = Image.new("RGBA", (bw + 40, 170), (0, 0, 0, 0))
    sd = ImageDraw.Draw(stick)
    sd.rounded_rectangle((0, 0, bw + 40, 170), 20, fill=(22, 24, 24, 255), outline=BONE + (255,), width=16)
    for k in range(-2, 9):
        px = k * 130
        sd.polygon([(px, 170), (px + 70, 170), (px + 150, 0), (px + 80, 0)], fill=BONE + (255,))
    sd.rounded_rectangle((0, 0, bw + 40, 170), 20, outline=BONE + (255,), width=16)
    stick = stick.rotate(14, resample=Image.BICUBIC, expand=True)
    lay.alpha_composite(stick, (x0 - 34, y0 - stick.size[1] + 40))
    # Lower stripe bar on the board top
    bar = Image.new("RGBA", (bw, 140), (0, 0, 0, 0))
    bd = ImageDraw.Draw(bar)
    bd.rectangle((0, 0, bw, 140), fill=(22, 24, 24, 255))
    for k in range(-2, 9):
        px = k * 130
        bd.polygon([(px, 140), (px + 70, 140), (px + 140, 0), (px + 70, 0)], fill=BONE + (255,))
    lay.alpha_composite(bar, (x0, y0 + 10))
    d.rounded_rectangle((x0, y0, x0 + bw, y0 + bh), 34, outline=BONE + (255,), width=18)
    return lay


def crt_tv():
    lay = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(lay)
    cx, cy = W // 2, int(H * 0.45)
    bw, bh = 900, 660
    x0, y0 = cx - bw // 2, cy - bh // 2 + 40
    # Antenna
    d.line((cx, y0, cx - 230, y0 - 300), fill=BONE + (255,), width=16)
    d.line((cx, y0, cx + 200, y0 - 320), fill=BONE + (255,), width=16)
    d.ellipse((cx - 70, y0 - 60, cx + 70, y0 + 30), fill=BONE + (255,))
    # Legs
    d.line((x0 + 150, y0 + bh, x0 + 90, y0 + bh + 110), fill=BONE + (255,), width=22)
    d.line((x0 + bw - 150, y0 + bh, x0 + bw - 90, y0 + bh + 110), fill=BONE + (255,), width=22)
    # Cabinet
    d.rounded_rectangle((x0, y0, x0 + bw, y0 + bh), 60, fill=(26, 28, 27, 255), outline=BONE + (255,), width=18)
    # Screen
    sx0, sy0, sx1, sy1 = x0 + 60, y0 + 60, x0 + bw - 230, y0 + bh - 60
    d.rounded_rectangle((sx0, sy0, sx1, sy1), 70, fill=SCREEN + (255,), outline=BONE + (255,), width=12)
    # Static / glitch lines on the screen
    r = random.Random(7)
    for _ in range(26):
        y = r.randrange(sy0 + 30, sy1 - 30)
        x = r.randrange(sx0 + 30, sx1 - 200)
        d.line((x, y, x + r.randrange(60, 320), y), fill=(GLOW if r.random() < 0.5 else BONE) + (r.randrange(90, 200),), width=r.choice([4, 6, 8]))
    # Knobs + speaker grille
    kx = x0 + bw - 115
    for ky in (y0 + 150, y0 + 300):
        d.ellipse((kx - 52, ky - 52, kx + 52, ky + 52), fill=(22, 24, 24, 255), outline=BONE + (255,), width=12)
        d.line((kx, ky, kx + 30, ky - 30), fill=BONE + (255,), width=10)
    for gy in range(y0 + 410, y0 + bh - 60, 30):
        d.line((kx - 60, gy, kx + 60, gy), fill=BONE + (220,), width=10)
    return lay


def ring(seed):
    lay = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(lay)
    cx, cy, r = W // 2, int(H * 0.44), 600
    t = 96
    d.ellipse((cx - r, cy - r, cx + r, cy + r), outline=RED + (255,), width=t)
    k = (r - t / 2) / math.sqrt(2)
    d.line((cx - k, cy - k, cx + k, cy + k), fill=RED + (255,), width=t)
    lay = distress(lay, seed, strength=0.36, speckle=0.04)
    # Faint red bloom, like the logo's screen glow
    bloom = lay.filter(ImageFilter.GaussianBlur(36))
    bloom.putalpha(bloom.split()[-1].point(lambda v: v * 0.85))
    out = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    out.alpha_composite(bloom)
    out.alpha_composite(lay)
    return out


def slime(seed):
    """Toxic green goo dripping from the top edge."""
    r = random.Random(seed)
    m = Image.new("L", (W, H), 0)
    d = ImageDraw.Draw(m)
    # Goo band along the top with a lumpy lower edge
    pts = [(0, 0)]
    x = 0
    while x <= W:
        pts.append((x, r.randrange(70, 150)))
        x += r.randrange(60, 140)
    pts += [(W, 0)]
    d.polygon(pts, fill=255)
    # Drips
    for _ in range(9):
        x = r.randrange(40, W - 40)
        w = r.randrange(26, 60)
        ln = r.randrange(160, 620)
        d.rounded_rectangle((x - w // 2, 60, x + w // 2, 60 + ln), w // 2, fill=255)
        d.ellipse((x - w * 0.8, 60 + ln - w, x + w * 0.8, 60 + ln + w * 0.7), fill=255)
    m = m.filter(ImageFilter.GaussianBlur(7)).point(lambda v: 255 if v > 120 else 0).filter(ImageFilter.GaussianBlur(2))
    a = np.asarray(m).astype(np.float32) / 255.0
    # Shade: darker body, bright highlight on one side
    shade = noise((W, H), 30, seed) * 0.5 + 0.5
    col = np.zeros((H, W, 3), np.float32)
    col[:] = np.array(GREEN, np.float32) * 0.48
    col += (shade[..., None] - 0.5) * 80
    hi = np.asarray(m.filter(ImageFilter.GaussianBlur(10))).astype(np.float32) / 255.0
    edge = np.clip(a - hi, 0, 1)
    col += (np.roll(np.clip(hi - 0.55, 0, 1), -10, axis=1)[..., None] * np.array(GLOW, np.float32) * 0.75)
    col -= edge[..., None] * 60
    out = np.dstack([np.clip(col, 0, 255), a * 255]).astype(np.uint8)
    goo = Image.fromarray(out, "RGBA")
    shadow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    shadow.putalpha(m.filter(ImageFilter.GaussianBlur(18)).point(lambda v: v * 0.5))
    res = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    res.alpha_composite(shadow, (0, 14))
    res.alpha_composite(goo)
    return res


def text_layer(text, font_path, size, fill, y, max_width, stroke=0):
    lay = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(lay)
    s = size
    while True:
        f = ImageFont.truetype(font_path, s)
        bbox = d.textbbox((0, 0), text, font=f, stroke_width=stroke)
        if bbox[2] - bbox[0] <= max_width or s < 40:
            break
        s -= 6
    tw = bbox[2] - bbox[0]
    d.text(((W - tw) // 2 - bbox[0], y), text, font=f, fill=fill, stroke_width=stroke, stroke_fill=(5, 10, 10, 255))
    return lay


def scanlines(img):
    lines = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(lines)
    for y in range(0, H, 8):
        d.line((0, y, W, y), fill=(0, 0, 0, 70), width=3)
    img.alpha_composite(lines)
    return img


def poster(title, subtitle, icon, title_color, accent, seed, out_name):
    random.seed(seed)
    img = background(accent, seed)

    ic = distress(icon, seed + 10, strength=0.40, speckle=0.05)
    ic = glitch(ic, seed + 11, bands=6, max_shift=40, split=9)
    img.alpha_composite(ic)
    img.alpha_composite(ring(seed + 20))
    img.alpha_composite(slime(seed + 30))

    # Dark band behind the title for legibility
    band = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    bd = ImageDraw.Draw(band)
    for i in range(520):
        bd.line((0, H - 520 + i, W, H - 520 + i), fill=BG + (int(235 * (i / 520) ** 0.8),))
    img.alpha_composite(band)

    t = text_layer(title, TITLE_FONT, 300, title_color + (255,), int(H * 0.775), W - 160, stroke=10)
    t = distress(t, seed + 40, strength=0.32, speckle=0.05)
    t = glitch(t, seed + 41, bands=5, max_shift=34, split=8)
    img.alpha_composite(t)

    st = text_layer(subtitle, SUB_FONT, 92, GLOW + (255,), int(H * 0.905), W - 200, stroke=6)
    img.alpha_composite(distress(st, seed + 50, strength=0.25, speckle=0.04))

    img = scanlines(img)
    # Final grade: slight green tint in highlights + extra grain
    arr = np.asarray(img.convert("RGB")).astype(np.float32)
    arr += grain((W, H), 6, seed + 60)[..., None]
    out = Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8)).resize(OUT, Image.LANCZOS)
    out = out.filter(ImageFilter.UnsharpMask(radius=1.2, percent=60, threshold=2))
    path = os.path.join(OUT_DIR, out_name)
    out.save(path, quality=88, optimize=True, progressive=True)
    print("wrote", path, os.path.getsize(path) // 1024, "KB")


if __name__ == "__main__":
    os.makedirs(OUT_DIR, exist_ok=True)
    poster("MOVIES", "now showing", clapperboard(), GREEN, GREEN, 101, "poster_movies.jpg")
    poster("TV SERIES", "binge the forbidden", crt_tv(), BONE, GLOW, 202, "poster_series.jpg")
