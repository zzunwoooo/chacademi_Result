"""PetPaw textures: python gen_petpaw.py <outdir>
White/greyscale line work (coloured by material tint + colorOverLifetime) + per-palette baked paw / floor (gold-pink, shiny teal-violet)."""
import sys, os, math
import numpy as np
from PIL import Image, ImageDraw, ImageFilter

out = sys.argv[1]; os.makedirs(out, exist_ok=True)

def save(arr, name):
    Image.fromarray(np.clip(arr * 255 + 0.5, 0, 255).astype(np.uint8), 'RGBA').save(os.path.join(out, name + '.png'))

def rgba_from(rgb, a):
    h, w = a.shape
    o = np.zeros((h, w, 4), np.float32); o[..., :3] = rgb; o[..., 3] = a; return o

def grid(n):
    y, x = np.mgrid[0:n, 0:n].astype(np.float32); c = (n - 1) / 2
    return (x - c) / (n / 2), (y - c) / (n / 2)

def ramp(t, keys):  # t 0..1 → rgb by linear keys [(t, rgb)]
    t = np.clip(t, 0, 1); r = np.zeros(t.shape + (3,), np.float32)
    for (t0, c0), (t1, c1) in zip(keys[:-1], keys[1:]):
        m = (t >= t0) & (t <= t1); k = ((t - t0) / max(1e-6, t1 - t0))[m][:, None]
        r[m] = np.array(c0) * (1 - k) + np.array(c1) * k
    return r

# ---------------- line profile (U along the strip, V across): bright core + soft halo
W, H = 16, 64
v = (np.arange(H, dtype=np.float32) + 0.5) / H; d = np.abs(v - 0.5) * 2
a = np.clip(np.exp(-(d / 0.28) ** 2) * 0.55 + np.exp(-(d / 0.11) ** 2) * 0.75, 0, 1)
col = np.clip(0.75 + 0.25 * np.exp(-(d / 0.12) ** 2), 0, 1)
line = np.zeros((H, W, 4), np.float32); line[..., :3] = col[:, None, None]; line[..., 3] = a[:, None]
save(line, 'T_PP_Line')

# ---------------- rune strip 512x64: tiny glyphs + paw-dot triplets, drawn 3x then downsampled with glow
S = 3; RW, RH = 512 * S, 64 * S
img = Image.new('L', (RW, RH), 0); dr = ImageDraw.Draw(img)
cy = RH / 2; lw = int(2.2 * S)
cell = RW / 8
for i in range(8):
    cx = cell * (i + 0.5)
    if i % 2 == 0:  # paw-dot cluster: one oval + three dots
        dr.ellipse([cx - 9 * S, cy - 2 * S, cx + 9 * S, cy + 12 * S], fill=255)
        for k, dx in enumerate((-12, 0, 12)):
            dy = -10 if k == 1 else -6
            dr.ellipse([cx + dx * S - 4.5 * S, cy + dy * S - 5 * S, cx + dx * S + 4.5 * S, cy + dy * S + 4 * S], fill=255)
    else:  # arcane glyph: diamond + cross ticks
        r = 14 * S
        dr.line([(cx, cy - r), (cx + r * 0.7, cy), (cx, cy + r), (cx - r * 0.7, cy), (cx, cy - r)], fill=255, width=lw)
        dr.line([(cx - r * 1.3, cy), (cx - r * 0.95, cy)], fill=255, width=lw)
        dr.line([(cx + r * 0.95, cy), (cx + r * 1.3, cy)], fill=255, width=lw)
        dr.ellipse([cx - 3 * S, cy - 3 * S, cx + 3 * S, cy + 3 * S], fill=255)
    # small separator dots between cells
    sx = cell * (i + 1)
    dr.ellipse([sx - 2.5 * S, cy - 2.5 * S, sx + 2.5 * S, cy + 2.5 * S], fill=200)
img = img.resize((512, 64), Image.LANCZOS)
core = np.asarray(img, np.float32) / 255
glow = np.asarray(img.filter(ImageFilter.GaussianBlur(4)), np.float32) / 255
a = np.clip(core + glow * 0.9, 0, 1)
save(rgba_from(np.clip(0.8 + 0.2 * core, 0, 1)[..., None], a), 'T_PP_Rune')

# ---------------- paw (pad + 4 toes), per palette, 256 px from 1024 mask
def paw_mask(n):
    m = Image.new('L', (n, n), 0); dr = ImageDraw.Draw(m); u = n / 256
    # main pad: rounded heart-ish trapezoid made of 3 ellipses
    dr.ellipse([88 * u, 120 * u, 168 * u, 200 * u], fill=255)
    dr.ellipse([70 * u, 140 * u, 122 * u, 196 * u], fill=255)
    dr.ellipse([134 * u, 140 * u, 186 * u, 196 * u], fill=255)
    dr.rectangle([96 * u, 150 * u, 160 * u, 194 * u], fill=255)
    # toes (outer two lower + tilted, inner two higher)
    toes = [(58, 104, 22, 28, -28), (98, 70, 22, 30, -10), (158, 70, 22, 30, 10), (198, 104, 22, 28, 28)]
    for cx, cy, rx, ry, ang in toes:
        t = Image.new('L', (n, n), 0); td = ImageDraw.Draw(t)
        td.ellipse([(cx - rx) * u, (cy - ry) * u, (cx + rx) * u, (cy + ry) * u], fill=255)
        t = t.rotate(-ang, center=(cx * u, cy * u), resample=Image.BICUBIC)
        m = Image.fromarray(np.maximum(np.asarray(m), np.asarray(t)))
    return m

N = 1024
m = paw_mask(N)
m = m.filter(ImageFilter.GaussianBlur(14)).point(lambda q: 255 if q > 120 else 0)   # smooth-union the blobs
m = m.filter(ImageFilter.GaussianBlur(2))
m256 = m.resize((256, 256), Image.LANCZOS)
fill = np.asarray(m256, np.float32) / 255
bl = np.asarray(m256.filter(ImageFilter.GaussianBlur(9)), np.float32) / 255
inner = np.clip((bl - 0.5) / 0.45, 0, 1) * fill                       # 0 at the edge → 1 deep inside
halo = np.asarray(m256.filter(ImageFilter.GaussianBlur(10)), np.float32) / 255
edge = np.clip(fill - np.clip(inner / 0.12, 0, 1), 0, 1)             # thin bright rim just inside the edge
PAL = {
    'Gold':  dict(core=(1, 0.98, 0.92), mid=(1, 0.82, 0.62), deep=(1, 0.55, 0.62), halo=(1, 0.62, 0.55)),
    'Shiny': dict(core=(0.92, 1, 1), mid=(0.45, 0.95, 0.95), deep=(0.62, 0.42, 1.0), halo=(0.5, 0.55, 1.0)),
}
for k, p in PAL.items():
    t = inner
    rgb = ramp(t, [(0, p['deep']), (0.35, p['mid']), (1, p['core'])])
    rgb = rgb * (1 - edge[..., None]) + np.array(p['core']) * edge[..., None]
    a_body = fill * (0.82 + 0.18 * np.maximum(edge, t))
    a_h = halo * 0.5 * (1 - fill)
    rgb = rgb * fill[..., None] + np.array(p['halo']) * (1 - fill)[..., None]
    save(rgba_from(rgb, np.clip(a_body + a_h, 0, 1)), 'T_PP_Paw_' + k)

    # floor glow 256: soft disk brighter toward a rim at r 0.82, colour gradient
    x, y = grid(256); r = np.sqrt(x * x + y * y)
    a = np.clip(0.35 * np.exp(-(r / 0.55) ** 2) + 0.55 * np.exp(-((r - 0.82) / 0.1) ** 2), 0, 1) * (r < 1)
    rgb = ramp(r / 1.0, [(0, p['core']), (0.5, p['mid']), (1, p['deep'])])
    save(rgba_from(rgb, a * np.clip((1 - r) / 0.08, 0, 1)), 'T_PP_Floor_' + k)

# ---------------- shock ring 256: sharp outer edge, soft trailing inside
x, y = grid(256); r = np.sqrt(x * x + y * y)
a = np.where(r < 0.93, np.exp(-((0.93 - r) / 0.14) ** 2) * 0.8, np.exp(-((r - 0.93) / 0.025) ** 2))
save(rgba_from(np.ones_like(r)[..., None], np.clip(a, 0, 1) * (r < 1)), 'T_PP_Shock')

# ---------------- 4-point star 64
x, y = grid(64); ax, ay = np.abs(x), np.abs(y)
star = np.exp(-(ax * 9) ** 2 / (1e-3 + ay + 0.02) * 0.08) * np.exp(-(ay / 0.9) ** 2) + np.exp(-(ay * 9) ** 2 / (1e-3 + ax + 0.02) * 0.08) * np.exp(-(ax / 0.9) ** 2)
star = np.clip(star * 0.8 + np.exp(-((x * x + y * y) / 0.02)) * 0.9, 0, 1)
save(rgba_from(np.ones_like(x)[..., None], star), 'T_PP_Star4')

# ---------------- dot 32 / glow 64
x, y = grid(32); r = np.sqrt(x * x + y * y)
save(rgba_from(np.ones_like(r)[..., None], np.clip(np.exp(-(r / 0.35) ** 2) + 0.3 * np.exp(-(r / 0.75) ** 2), 0, 1) * (r < 1)), 'T_PP_Dot')
x, y = grid(64); r = np.sqrt(x * x + y * y)
save(rgba_from(np.ones_like(r)[..., None], np.clip(np.exp(-(r / 0.45) ** 2), 0, 1) * np.clip((1 - r) / 0.2, 0, 1)), 'T_PP_Glow')

# ---------------- ink disk 128 (alpha-blended dark accent under the circle)
x, y = grid(128); r = np.sqrt(x * x + y * y)
ink = rgba_from(np.array([0.08, 0.05, 0.1])[None, None, :] * np.ones_like(r)[..., None], np.clip(np.exp(-(r / 0.62) ** 2) * 0.9, 0, 1) * np.clip((1 - r) / 0.15, 0, 1))
save(ink, 'T_PP_Ink')
print('ok')
