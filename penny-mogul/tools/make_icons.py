#!/usr/bin/env python3
"""Generate the launcher icons (adaptive + legacy) for Penny Mogul.

Output goes to android/res/mipmap-*. Needs Pillow and a bold TTF font.
Run: python3 tools/make_icons.py
"""
import math
import os
import sys

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "android", "res")
SS = 3  # supersampling factor

FONT_CANDIDATES = [
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
    "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf",
]


def font(size):
    for p in FONT_CANDIDATES:
        if os.path.exists(p):
            return ImageFont.truetype(p, size)
    sys.exit("no bold font found for the $ glyph")


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def background(n):
    """Diagonal indigo -> deep navy gradient with a soft cyan glow."""
    img = Image.new("RGB", (n, n))
    px = img.load()
    c1, c2 = (92, 70, 230), (11, 15, 38)
    for y in range(n):
        for x in range(n):
            t = (x * 0.35 + y * 0.65) / n
            px[x, y] = lerp(c1, c2, min(1, t * 1.25))
    glow = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    ImageDraw.Draw(glow).ellipse([n * 0.45, -n * 0.1, n * 1.15, n * 0.55], fill=(0, 209, 255, 90))
    glow = glow.filter(ImageFilter.GaussianBlur(n * 0.12))
    img = Image.alpha_composite(img.convert("RGBA"), glow)
    return img


def foreground(n):
    """Gold coin with a $ and a green growth arrow. Content kept inside the
    adaptive-icon safe zone (centre ~61% of the canvas)."""
    img = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    cx, cy = n * 0.5, n * 0.5
    r = n * 0.255

    # soft drop shadow
    sh = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    ImageDraw.Draw(sh).ellipse([cx - r, cy - r + n * 0.03, cx + r, cy + r + n * 0.03], fill=(0, 0, 20, 140))
    img = Image.alpha_composite(img, sh.filter(ImageFilter.GaussianBlur(n * 0.02)))
    d = ImageDraw.Draw(img)

    # growth arrow behind the coin (rises to the upper right)
    g = (46, 230, 166, 255)
    pts = [(n * 0.22, n * 0.66), (n * 0.38, n * 0.52), (n * 0.48, n * 0.60), (n * 0.74, n * 0.30)]
    w = int(n * 0.035)
    d.line(pts, fill=g, width=w, joint="curve")
    for p in (pts[0], pts[-1]):
        d.ellipse([p[0] - w / 2, p[1] - w / 2, p[0] + w / 2, p[1] + w / 2], fill=g)
    ax, ay = pts[-1]
    d.polygon([(ax + n * 0.06, ay - n * 0.06), (ax - n * 0.07, ay - n * 0.015), (ax + n * 0.015, ay + n * 0.07)], fill=g)

    # coin: rim -> face gradient
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(255, 176, 32, 255))
    face = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    fd = ImageDraw.Draw(face)
    r2 = r * 0.86
    fd.ellipse([cx - r2, cy - r2, cx + r2, cy + r2], fill=(255, 190, 48, 255))
    steps = 60
    for i in range(steps):
        t = i / (steps - 1)
        col = lerp((255, 233, 128), (255, 190, 48), t) + (255,)
        rr = r2 * (1 - t * 0.55)
        ox, oy = -r2 * 0.18 * (1 - t), -r2 * 0.22 * (1 - t)
        fd.ellipse([cx + ox - rr, cy + oy - rr, cx + ox + rr, cy + oy + rr], fill=col)
    mask = Image.new("L", (n, n), 0)
    ImageDraw.Draw(mask).ellipse([cx - r2, cy - r2, cx + r2, cy + r2], fill=255)
    img.paste(face, (0, 0), mask)
    d = ImageDraw.Draw(img)
    d.ellipse([cx - r2, cy - r2, cx + r2, cy + r2], outline=(214, 140, 10, 255), width=max(1, int(n * 0.008)))

    # the $ glyph
    f = font(int(r * 1.45))
    bbox = d.textbbox((0, 0), "$", font=f)
    tw, th = bbox[2] - bbox[0], bbox[3] - bbox[1]
    d.text((cx - tw / 2 - bbox[0], cy - th / 2 - bbox[1]), "$", font=f, fill=(150, 88, 0, 255))
    return img


def save(img, path, size):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.resize((size, size), Image.LANCZOS).save(path, optimize=True)


def rounded(img, radius_frac):
    n = img.size[0]
    m = Image.new("L", (n, n), 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, n - 1, n - 1], radius=int(n * radius_frac), fill=255)
    out = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    out.paste(img, (0, 0), m)
    return out


def main():
    big = 432 * SS
    bg = background(big)
    fg = foreground(big)

    # adaptive layers (108dp @ xxxhdpi = 432px)
    save(bg.convert("RGB"), os.path.join(RES, "mipmap-xxxhdpi", "ic_launcher_background.png"), 432)
    save(fg, os.path.join(RES, "mipmap-xxxhdpi", "ic_launcher_foreground.png"), 432)

    # legacy icon: the whole composite, coin enlarged a bit, in a rounded square
    comp = Image.alpha_composite(bg, fg)
    crop = int(big * 0.14)
    comp = comp.crop((crop, crop, big - crop, big - crop)).resize((big, big), Image.LANCZOS)
    comp = rounded(comp, 0.22)
    for name, px in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
        save(comp, os.path.join(RES, "mipmap-" + name, "ic_launcher.png"), px)

    # preview for humans
    prev = os.path.join(ROOT, "docs", "icon-preview.png")
    os.makedirs(os.path.dirname(prev), exist_ok=True)
    save(comp, prev, 512)
    print("icons written")


if __name__ == "__main__":
    main()
