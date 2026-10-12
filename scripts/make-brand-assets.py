#!/usr/bin/env python3
"""Generates the TV banner and legacy launcher icons from one definition of the brand mark.

Run from the repository root:   python3 scripts/make-brand-assets.py
Requires Pillow. The wordmark uses the Inter typeface (SIL Open Font License) if installed, else DejaVu.
The adaptive-icon vector (res/drawable/ic_launcher_foreground.xml) uses the same geometry in a 108dp viewport.
"""
import os
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.join(os.path.dirname(__file__), "..")
RES = os.path.join(ROOT, "app", "src", "main", "res")
SS = 4  # supersampling for smooth edges

BG = (0x0B, 0x0F, 0x17)
C1 = (0x5B, 0x7C, 0xFF)   # brand gradient start
C2 = (0x2D, 0xD4, 0xBF)   # brand gradient end


def font(size, bold=True):
    for path in (
        "/usr/share/fonts/opentype/inter/Inter-SemiBold.otf" if bold else "/usr/share/fonts/opentype/inter/Inter-Regular.otf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf" if bold else "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
    ):
        if os.path.exists(path):
            return ImageFont.truetype(path, size)
    return ImageFont.load_default()


def gradient(size, a, b):
    w, h = size
    img = Image.new("RGB", size)
    px = img.load()
    for y in range(h):
        for x in range(w):
            t = (x / max(w - 1, 1) * 0.55 + y / max(h - 1, 1) * 0.45)
            px[x, y] = tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))
    return img


def line(d, p0, p1, width, fill):
    d.line([p0, p1], fill=fill, width=int(width))
    r = width / 2
    for x, y in (p0, p1):
        d.ellipse([x - r, y - r, x + r, y + r], fill=fill)


def mark_glyph(d, ox, oy, s, fill=(255, 255, 255, 255)):
    """The 'install' glyph: an arrow landing in a tray, in a square of side s at (ox, oy)."""
    w = s * 0.085 * 1.25
    P = lambda x, y: (ox + x * s, oy + y * s)
    line(d, P(0.5, 0.16), P(0.5, 0.58), w, fill)
    line(d, P(0.3, 0.42), P(0.5, 0.62), w, fill)
    line(d, P(0.5, 0.62), P(0.7, 0.42), w, fill)
    line(d, P(0.2, 0.70), P(0.2, 0.80), w, fill)
    line(d, P(0.2, 0.80), P(0.8, 0.80), w, fill)
    line(d, P(0.8, 0.80), P(0.8, 0.70), w, fill)


def brand_tile(size, radius_frac=0.26, border=True):
    big = size * SS
    tile = gradient((big, big), C1, C2).convert("RGBA")
    mask = Image.new("L", (big, big), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, big - 1, big - 1], radius=int(big * radius_frac), fill=255)
    tile.putalpha(mask)
    d = ImageDraw.Draw(tile)
    inset = big * 0.19
    mark_glyph(d, inset, inset, big - 2 * inset)
    if border:
        ImageDraw.Draw(tile).rounded_rectangle([1, 1, big - 2, big - 2], radius=int(big * radius_frac), outline=(255, 255, 255, 60), width=SS)
    return tile.resize((size, size), Image.LANCZOS)


def glow(canvas, center, radius, color, alpha):
    layer = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    d.ellipse([center[0] - radius, center[1] - radius, center[0] + radius, center[1] + radius], fill=color + (alpha,))
    layer = layer.filter(ImageFilter.GaussianBlur(radius / 2.2))
    canvas.alpha_composite(layer)


def banner(path, w=640, h=360):
    img = Image.new("RGBA", (w, h), BG + (255,))
    glow(img, (int(w * 0.12), int(-h * 0.05)), int(w * 0.5), (0x1A, 0x2A, 0x52), 190)
    glow(img, (int(w * 0.95), int(h * 1.05)), int(w * 0.4), (0x12, 0x3A, 0x44), 120)
    tile = brand_tile(int(h * 0.34))
    x0, y0 = int(w * 0.09), int(h * 0.33)
    img.alpha_composite(tile, (x0, y0))
    d = ImageDraw.Draw(img)
    d.text((x0 + tile.width + int(w * 0.04), y0 - int(h * 0.005)), "TV Essentials", font=font(int(h * 0.125)), fill=(243, 246, 251, 255))
    d.text((x0 + tile.width + int(w * 0.04), y0 + int(h * 0.15)), "Your apps, made simple.", font=font(int(h * 0.065), bold=False), fill=(182, 192, 210, 255))
    img.convert("RGB").save(path, optimize=True)


def launcher(path, size):
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    img.alpha_composite(brand_tile(size, 0.22))
    img.save(path, optimize=True)


def main():
    os.makedirs(os.path.join(RES, "drawable-nodpi"), exist_ok=True)
    banner(os.path.join(RES, "drawable-nodpi", "tv_banner.png"))
    for folder, px in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
        os.makedirs(os.path.join(RES, f"mipmap-{folder}"), exist_ok=True)
        launcher(os.path.join(RES, f"mipmap-{folder}", "ic_launcher.png"), px)
    print("brand assets written under", os.path.relpath(RES, ROOT))


if __name__ == "__main__":
    main()
