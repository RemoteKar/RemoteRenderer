# -*- coding: utf-8 -*-
"""Draws the mod icon (mod/resources/assets/remoterenderer/icon.png, 256 x 256): an isometric block drawn as a GPU
triangle mesh, with a bent two-bone joint and a swinging chain (rigs / physics) in front of it."""
import math
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parents[1]
S = 4  # supersampling
W = 256 * S


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(len(a)))


def main():
    img = Image.new('RGBA', (W, W), (0, 0, 0, 0))
    # rounded background with a vertical gradient
    bg = Image.new('RGBA', (W, W))
    top, bottom = (28, 34, 64), (12, 14, 30)
    for y in range(W):
        ImageDraw.Draw(bg).line([(0, y), (W, y)], fill=lerp(top, bottom, y / W) + (255,))
    mask = Image.new('L', (W, W), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, W - 1, W - 1], radius=46 * S, fill=255)
    img.paste(bg, (0, 0), mask)
    d = ImageDraw.Draw(img)

    # isometric block
    cx, cy, r = 118 * S, 120 * S, 70 * S
    up = (cx, cy - r)
    left, right = (cx - r * math.cos(math.radians(30)), cy - r / 2), (cx + r * math.cos(math.radians(30)), cy - r / 2)
    mid = (cx, cy)
    bl, br, bot = (left[0], left[1] + r), (right[0], right[1] + r), (cx, cy + r)
    faces = [([up, right, mid, left], (64, 214, 200)), ([left, mid, bot, bl], (36, 150, 170)), ([mid, right, br, bot], (24, 104, 140))]
    for poly, col in faces:
        d.polygon(poly, fill=col + (255,))
    # the faces as a triangle mesh (instanced draw)
    edge = (230, 255, 250, 150)
    for poly, _ in faces:
        a, b, c, e = poly
        n = 3
        for i in range(1, n):
            t = i / n
            d.line([lerp(a, e, t), lerp(b, c, t)], fill=edge, width=2 * S)
            d.line([lerp(a, b, t), lerp(e, c, t)], fill=edge, width=2 * S)
        d.line([a, c], fill=edge, width=2 * S)
        d.line(poly + [poly[0]], fill=(240, 255, 252, 230), width=3 * S)

    # a bent joint (two bones and a smooth elbow), front right
    joint = [(150 * S, 150 * S), (186 * S, 182 * S), (218 * S, 160 * S)]
    for width, col in ((22, (255, 176, 64)), (14, (255, 214, 120))):
        pts = []
        for i in range(21):
            t = i / 20
            # quadratic bezier through the elbow: the skinned bend
            p0, p1, p2 = joint
            x = (1 - t) ** 2 * p0[0] + 2 * (1 - t) * t * p1[0] + t * t * p2[0]
            y = (1 - t) ** 2 * p0[1] + 2 * (1 - t) * t * p1[1] + t * t * p2[1]
            pts.append((x, y))
        d.line(pts, fill=col + (255,), width=width * S, joint='curve')
    for p in (joint[0], joint[2]):
        d.ellipse([p[0] - 11 * S, p[1] - 11 * S, p[0] + 11 * S, p[1] + 11 * S], fill=(255, 236, 190, 255), outline=(120, 70, 20, 255), width=2 * S)

    # a swinging chain (hair / cape physics), left
    x, y = 58 * S, 66 * S
    pts = [(x, y)]
    for k in range(5):
        ang = math.radians(100 + k * 14)
        x += math.cos(ang) * 22 * S
        y += math.sin(ang) * 22 * S
        pts.append((x, y))
    d.line(pts, fill=(200, 120, 255, 255), width=8 * S, joint='curve')
    for k, p in enumerate(pts):
        rr = (7 - k * 0.6) * S
        d.ellipse([p[0] - rr, p[1] - rr, p[0] + rr, p[1] + rr], fill=(236, 200, 255, 255))

    glow = img.filter(ImageFilter.GaussianBlur(3 * S))
    out = Image.alpha_composite(glow, img).resize((256, 256), Image.LANCZOS)
    path = ROOT / 'mod/resources/assets/remoterenderer/icon.png'
    path.parent.mkdir(parents=True, exist_ok=True)
    out.save(path)
    print('wrote', path)


if __name__ == '__main__':
    main()
