"""The "Viewarr" bubble wordmark (since 0.24): Baloo 2 ExtraBold in the mark's cyan -> pink, a soft lit core, a
deeper lower edge, a gloss along every letter's top edge and a shadow beneath. Rendered on transparency so the app's
splash and the banner use the same picture."""
import os, urllib.request
from PIL import Image, ImageDraw, ImageFont, ImageFilter, ImageChops

CY, PK = (0x00, 0xE5, 0xFF), (0xFF, 0x33, 0x66)
FONT_URL = 'https://github.com/google/fonts/raw/main/ofl/baloo2/Baloo2%5Bwght%5D.ttf'

def font(path, size):
    if not os.path.exists(path):
        urllib.request.urlretrieve(FONT_URL, path)
    f = ImageFont.truetype(path, size)
    ax = f.get_variation_axes()
    f.set_variation_by_axes([800 if (a['name'] in (b'Weight', 'Weight')) else a['default'] for a in ax])
    return f

def wordmark(font_path, size=200, word='Viewarr'):
    """RGBA image of the wordmark, cropped to it (with room for the shadow)."""
    f = font(font_path, size)
    tb = ImageDraw.Draw(Image.new('L', (1, 1))).textbbox((0, 0), word, font=f)
    pad = int(size * 0.18)
    W, H = tb[2] - tb[0] + 2 * pad, tb[3] - tb[1] + 2 * pad
    tx, ty = pad - tb[0], pad - tb[1]
    m = Image.new('L', (W, H), 0); ImageDraw.Draw(m).text((tx, ty), word, font=f, fill=255)
    gx0, gy0, gx1, gy1 = pad, pad, W - pad, H - pad
    k = size / 200
    out = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    # shadow
    sh = m.filter(ImageFilter.GaussianBlur(12 * k)).point(lambda v: int(v * 0.6))
    shadow = Image.new('RGBA', (W, H), (0, 0, 0, 0)); shadow.paste((0, 0, 0, 255), (int(4 * k), int(12 * k)), sh)
    out = Image.alpha_composite(out, shadow)
    # body gradient
    g = Image.new('RGB', (W, H)); gp = g.load(); span = (gx1 - gx0) + (gy1 - gy0)
    for y in range(H):
        for x in range(W):
            t = min(1, max(0, ((x - gx0) + (y - gy0)) / span)); gp[x, y] = tuple(int(CY[i] + (PK[i] - CY[i]) * t) for i in range(3))
    core = m.filter(ImageFilter.MinFilter(max(3, int(11 * k) | 1))).filter(ImageFilter.GaussianBlur(8 * k))
    body = Image.composite(Image.blend(g, Image.new('RGB', (W, H), (255, 255, 255)), 0.18), g, core)
    low = Image.new('L', (W, H), 0); lp = low.load(); y0 = gy0 + (gy1 - gy0) * 0.6
    for y in range(int(y0), gy1 + 1):
        a = int(70 * (y - y0) / ((gy1 - gy0) * 0.4))
        for x in range(W): lp[x, y] = a
    body.paste((40, 0, 60), (0, 0), ImageChops.multiply(low, m))
    # gloss along each letter's top edge
    inset = m.filter(ImageFilter.MinFilter(max(3, int(11 * k) | 1)))
    gloss = None
    for d, a in ((10, 230), (20, 150), (30, 70)):
        t = ImageChops.subtract(inset, ImageChops.offset(inset, 0, int(d * k))).point(lambda v, a=a: int(v * a / 255))
        gloss = t if gloss is None else ImageChops.lighter(gloss, t)
    gloss = ImageChops.multiply(gloss, inset).filter(ImageFilter.GaussianBlur(2.5 * k)).point(lambda v: int(v * 0.85))
    body.paste((255, 255, 255), (0, 0), gloss)
    word_layer = Image.new('RGBA', (W, H), (0, 0, 0, 0)); word_layer.paste(body, (0, 0), m)
    return Image.alpha_composite(out, word_layer), pad
