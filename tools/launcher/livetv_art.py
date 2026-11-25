"""The launcher's Live TV tile background: a wall of glowing screens in perspective, a LIVE badge, signal arcs."""
import random, sys
import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

W, H = 1920, 1080
random.seed(7)
yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)

def glow(cx, cy, r, rgb, strength):
    d = np.sqrt((xx - cx) ** 2 + (yy - cy) ** 2) / r
    a = np.clip(1 - d, 0, 1) ** 2 * strength
    return a[..., None] * np.array(rgb, np.float32)[None, None, :]

bg = np.zeros((H, W, 3), np.float32) + np.array([7, 10, 22], np.float32)
bg += glow(W * 0.78, H * 0.42, W * 0.55, (225, 29, 72), 0.55)
bg += glow(W * 0.95, H * 0.05, W * 0.45, (37, 99, 235), 0.6)
bg += glow(W * 0.55, H * 0.95, W * 0.5, (124, 58, 237), 0.35)
img = Image.fromarray(np.clip(bg, 0, 255).astype(np.uint8)).convert("RGBA")

# The wall: a grid of screens drawn flat, then tilted into perspective.
cols, rows = 7, 5
sw, sh, gap = 300, 170, 26
wall = Image.new("RGBA", (cols * (sw + gap) + gap, rows * (sh + gap) + gap), (0, 0, 0, 0))
d = ImageDraw.Draw(wall)
palettes = [((16, 185, 129), (6, 78, 59)), ((239, 68, 68), (127, 29, 29)), ((245, 158, 11), (120, 53, 15)),
            ((59, 130, 246), (30, 58, 138)), ((168, 85, 247), (76, 29, 149)), ((20, 184, 166), (19, 78, 74)),
            ((236, 72, 153), (131, 24, 67))]
for r in range(rows):
    for c in range(cols):
        x0, y0 = gap + c * (sw + gap), gap + r * (sh + gap)
        top, bottom = random.choice(palettes)
        screen = Image.new("RGBA", (sw, sh))
        grad = np.linspace(0, 1, sh)[:, None, None]
        arr = (np.array(top) * (1 - grad) + np.array(bottom) * grad) * np.ones((1, sw, 1))
        # A soft shape in each picture (a figure, a pitch, a horizon) so they read as programmes.
        k = random.random()
        cx, cy = random.uniform(0.3, 0.7) * sw, random.uniform(0.35, 0.7) * sh
        gy, gx = np.mgrid[0:sh, 0:sw]
        blob = np.clip(1 - np.sqrt((gx - cx) ** 2 + ((gy - cy) * 1.4) ** 2) / (sh * 0.55), 0, 1)
        arr = arr * (0.55 + 0.45 * blob[..., None]) + (40 if k > 0.5 else 0) * blob[..., None]
        if k < 0.3:  # a horizon line
            arr[int(sh * 0.66):, :, :] *= 0.6
        screen = Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8)).convert("RGBA")
        mask = Image.new("L", (sw, sh), 0)
        ImageDraw.Draw(mask).rounded_rectangle((0, 0, sw - 1, sh - 1), 14, fill=255)
        wall.paste(screen, (x0, y0), mask)
        # A progress bar along the bottom of some, as the guide shows.
        if random.random() < 0.5:
            p = random.uniform(0.2, 0.9)
            d.rounded_rectangle((x0 + 14, y0 + sh - 16, x0 + 14 + (sw - 28) * p, y0 + sh - 10), 3, fill=(255, 255, 255, 220))
        d.rounded_rectangle((x0, y0, x0 + sw - 1, y0 + sh - 1), 14, outline=(255, 255, 255, 40), width=2)

ww, wh = wall.size
# Perspective: the far (right) edge shorter, receding to the right.
dst = [(0, 0), (ww, wh * 0.22), (ww, wh * 0.78), (0, wh)]  # where the wall's corners land (tl, tr, br, bl)
def coeffs(src, dst):
    m = []
    for (x, y), (u, v) in zip(dst, src):
        m.append([x, y, 1, 0, 0, 0, -u * x, -u * y]); m.append([0, 0, 0, x, y, 1, -v * x, -v * y])
    A = np.array(m, np.float64); B = np.array([c for p in src for c in p], np.float64)
    return np.linalg.solve(A, B)
src = [(0, 0), (ww, 0), (ww, wh), (0, wh)]
tilted = wall.transform((ww, wh), Image.PERSPECTIVE, coeffs(src, dst), Image.BICUBIC)
scale = 0.98 * W / ww
tilted = tilted.resize((int(ww * scale), int(wh * scale)), Image.LANCZOS)
ox, oy = int(W * 0.03), int((H - tilted.size[1]) / 2)
# Its glow first (the screens lighting the room), then the screens a little dimmed so the badge stands out.
halo = tilted.filter(ImageFilter.GaussianBlur(40))
img.alpha_composite(halo, (ox, oy))
img.alpha_composite(tilted, (ox, oy))
img.alpha_composite(Image.new("RGBA", img.size, (5, 8, 18, 70)))

# The LIVE badge with its glow and signal arcs.
font = ImageFont.truetype(sys.argv[2], 92)
try: font.set_variation_by_axes([800])
except Exception: pass
text = "LIVE"
tw = font.getbbox(text)[2]
bw, bh = tw + 190, 150
bx, by = int(W * 0.5 - bw / 2), int(H * 0.47 - bh / 2)
layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
ld = ImageDraw.Draw(layer)
ld.rounded_rectangle((bx, by, bx + bw, by + bh), bh // 2, fill=(225, 29, 72, 255))
img.alpha_composite(layer.filter(ImageFilter.GaussianBlur(45)))
img.alpha_composite(layer.filter(ImageFilter.GaussianBlur(14)))
img.alpha_composite(layer)
d2 = ImageDraw.Draw(img)
dot = 34; dx, dy = bx + 58, by + bh // 2
d2.ellipse((dx - dot // 2, dy - dot // 2, dx + dot // 2, dy + dot // 2), fill=(255, 255, 255, 255))
d2.text((dx + 40, dy), text, font=font, fill=(255, 255, 255, 255), anchor="lm")
arcs = Image.new("RGBA", img.size, (0, 0, 0, 0))
ad = ImageDraw.Draw(arcs)
acx, acy = bx + bw // 2, by - 30
for i, r in enumerate((70, 120, 170)):
    ad.arc((acx - r, acy - r, acx + r, acy + r), 215, 325, fill=(255, 255, 255, 230 - i * 55), width=12)
img.alpha_composite(arcs.filter(ImageFilter.GaussianBlur(10)))
img.alpha_composite(arcs)

img.convert("RGB").resize((1280, 720), Image.LANCZOS).save(sys.argv[1], quality=90)
print("ok")
