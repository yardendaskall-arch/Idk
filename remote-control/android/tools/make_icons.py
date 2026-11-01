#!/usr/bin/env python3
"""Generates the launcher icons (needs Pillow). Run from the android/ folder."""
import os
from PIL import Image, ImageDraw

BG = (14, 27, 43, 255)
WHITE = (232, 238, 244, 255)
SCREEN = (18, 38, 58, 255)
ACCENT = (76, 154, 255, 255)
SS = 4  # supersampling


def glyph(size, fraction):
    """Laptop + pointer arrow on a transparent canvas; the artwork spans `fraction` of the canvas."""
    big = size * SS
    img = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    scale = big * fraction / 100.0
    off = big * (1 - fraction) / 2.0

    def P(x, y):
        return (off + x * scale, off + y * scale)

    d.rounded_rectangle([P(17, 24), P(83, 64)], radius=4 * scale, fill=SCREEN, outline=WHITE, width=int(5 * scale))
    d.polygon([P(8, 70), P(92, 70), P(85, 78), P(15, 78)], fill=WHITE)
    arrow = [(50, 36), (50, 72), (59, 64), (65, 78), (72, 75), (66, 61), (78, 61)]
    d.polygon([P(*p) for p in arrow], fill=ACCENT, outline=WHITE)
    d.line([P(*p) for p in arrow + [arrow[0]]], fill=WHITE, width=max(1, int(2 * scale)), joint="curve")
    return img.resize((size, size), Image.LANCZOS)


def legacy(size):
    big = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    mask = Image.new("L", (size * SS, size * SS), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, size * SS - 1, size * SS - 1], radius=int(size * SS * 0.22), fill=255)
    mask = mask.resize((size, size), Image.LANCZOS)
    bg = Image.new("RGBA", (size, size), BG)
    big.paste(bg, (0, 0), mask)
    big.alpha_composite(glyph(size, 0.86))
    return big


os.makedirs("res/drawable-nodpi", exist_ok=True)
glyph(432, 0.60).save("res/drawable-nodpi/ic_launcher_foreground.png")   # adaptive: keep inside the safe zone
for folder, px in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
    os.makedirs("res/mipmap-%s" % folder, exist_ok=True)
    legacy(px).save("res/mipmap-%s/ic_launcher.png" % folder)
print("icons written")
