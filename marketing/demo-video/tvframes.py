from PIL import Image, ImageDraw, ImageFont, ImageFilter
F = "/usr/share/fonts/opentype/inter/"
def font(w, s): return ImageFont.truetype(F + f"Inter-{w}.otf", s)
W, H = 1920, 1080
APPS = [("N", (178, 7, 16), (255, 255, 255)), ("yt", (255, 255, 255), (230, 33, 23)), ("P", (15, 40, 70), (255, 255, 255)), ("D+", (12, 22, 80), (255, 255, 255))]

def grad(top, bot):
    im = Image.new("RGB", (W, H)); d = ImageDraw.Draw(im)
    for y in range(H):
        a = y / H; d.line([(0, y), (W, y)], fill=tuple(int(top[i] + (bot[i] - top[i]) * a) for i in range(3)))
    return im

def mark(d, x, y, w, h, kind, r=22):
    txt, bg, fg = APPS[kind]
    d.rounded_rectangle([x, y, x + w, y + h], r, fill=bg)
    cx, cy = x + w / 2, y + h / 2
    if txt == "yt":
        d.rounded_rectangle([cx - w * .22, cy - h * .16, cx + w * .22, cy + h * .16], int(h * .08), fill=fg)
        d.polygon([(cx - w * .06, cy - h * .09), (cx - w * .06, cy + h * .09), (cx + w * .08, cy)], fill=(255, 255, 255))
    else:
        f = font("Bold", int(h * .42)); tw = d.textlength(txt, font=f); d.text((cx - tw / 2, cy - h * .27), txt, font=f, fill=fg)

def card(d, x, y, w, h, c1, c2, prog=None):
    im = Image.new("RGB", (w, h)); dd = ImageDraw.Draw(im)
    for k in range(w + h):
        a = k / (w + h); dd.line([(k, 0), (k - h, h)], fill=tuple(int(c1[i] + (c2[i] - c1[i]) * a) for i in range(3)), width=2)
    m = Image.new("L", (w, h), 0); ImageDraw.Draw(m).rounded_rectangle([0, 0, w - 1, h - 1], 18, fill=255)
    d._image.paste(im, (x, y), m)
    if prog: d.rounded_rectangle([x + 20, y + h - 22, x + w - 20, y + h - 14], 4, fill=(80, 80, 92)); d.rounded_rectangle([x + 20, y + h - 22, x + 20 + (w - 40) * prog, y + h - 14], 4, fill=(255, 255, 255))

PAL = [((70, 90, 160), (30, 30, 60)), ((160, 90, 60), (60, 30, 40)), ((40, 120, 110), (20, 40, 60)), ((120, 60, 140), (40, 20, 60)), ((170, 140, 70), (70, 40, 30)), ((60, 110, 170), (20, 30, 70))]

def home():
    im = grad((26, 30, 44), (8, 8, 12)); d = ImageDraw.Draw(im)
    d.text((80, 50), "Home", font=font("SemiBold", 40), fill=(235, 235, 240)); d.text((1740, 52), "8:04", font=font("Medium", 38), fill=(200, 200, 210))
    d.text((80, 150), "Apps", font=font("Medium", 30), fill=(170, 170, 180))
    for i in range(4): mark(d, 80 + i * 290, 200, 260, 170, i)
    for i, lab in enumerate(["Live TV", "Settings"]):
        x = 80 + (4 + i) * 290; d.rounded_rectangle([x, 200, x + 260, 370], 22, fill=(48, 52, 64)); f = font("SemiBold", 32); d.text((x + 130 - d.textlength(lab, font=f) / 2, 265), lab, font=f, fill=(225, 225, 232))
    d.text((80, 450), "Continue watching", font=font("Medium", 30), fill=(170, 170, 180))
    for i in range(4): card(d, 80 + i * 440, 500, 400, 225, *PAL[i], prog=[.3, .7, .5, .15][i])
    d.text((80, 790), "Recommended", font=font("Medium", 30), fill=(170, 170, 180))
    for i in range(4): card(d, 80 + i * 440, 840, 400, 225, *PAL[(i + 2) % 6])
    return im

def app(kind, name, bg, accent):
    im = grad(bg, tuple(max(0, c - 10) for c in bg)); d = ImageDraw.Draw(im)
    mark(d, 80, 50, 90, 70, kind, 16); d.text((195, 62), name, font=font("SemiBold", 40), fill=(240, 240, 244))
    card(d, 80, 170, 1760, 500, tuple(min(255, int(c * 1.0)) for c in accent), bg)
    d.rounded_rectangle([140, 540, 360, 610], 14, fill=(255, 255, 255)); d.text((185, 553), "▶  Play", font=font("Bold", 34), fill=(20, 20, 24))
    d.text((80, 720), "Trending now", font=font("Medium", 30), fill=(190, 190, 200))
    for i in range(5): card(d, 80 + i * 360, 770, 330, 190, *PAL[(i + kind) % 6])
    return im

def search():
    im = app(1, "YouTube", (16, 16, 18), (60, 20, 20)); d = ImageDraw.Draw(im)
    d.rectangle([0, 140, W, H], fill=(16, 16, 18))
    d.rounded_rectangle([360, 200, 1560, 300], 50, fill=(38, 38, 42), outline=(90, 90, 96), width=3)
    d.ellipse([400, 230, 436, 266], outline=(200, 200, 205), width=5); d.line([(430, 262), (446, 278)], fill=(200, 200, 205), width=6)
    rows = ["abcdefghi", "jklmnopqr", "stuvwxyz "]
    for r, row in enumerate(rows):
        for c, ch in enumerate(row):
            x = 560 + c * 92; y = 400 + r * 92; d.rounded_rectangle([x, y, x + 80, y + 80], 12, fill=(32, 32, 36))
            f = font("Medium", 34); d.text((x + 40 - d.textlength(ch, font=f) / 2, y + 18), ch, font=f, fill=(120, 120, 128))
    return im

off = Image.new("RGB", (W, H), (6, 6, 8)); d = ImageDraw.Draw(off)
for k in range(400): d.line([(900 + k, 0), (1300 + k, H)], fill=(10, 10, 13))
off.save("tv_off.png"); home().save("tv_home.png")
app(0, "Netflix", (8, 8, 8), (120, 10, 16)).save("tv_netflix.png")
app(1, "YouTube", (16, 16, 18), (90, 20, 24)).save("tv_youtube.png")
app(2, "Prime Video", (14, 24, 38), (20, 90, 150)).save("tv_prime.png")
search().save("tv_search.png")
im = grad((10, 10, 12), (4, 4, 6)); d = ImageDraw.Draw(im); card(d, 0, 0, 1920, 1080, (150, 110, 60), (14, 18, 30))
sh = Image.new("RGBA", (1920, 1080)); ImageDraw.Draw(sh).rectangle([0, 600, 1920, 1080], fill=(0, 0, 0, 120)); im = Image.alpha_composite(im.convert("RGBA"), sh.filter(ImageFilter.GaussianBlur(80))).convert("RGB"); d = ImageDraw.Draw(im)
d.text((120, 640), "Ted Lasso", font=font("Bold", 120), fill=(255, 255, 255)); d.text((124, 790), "Season 1 · Episode 1", font=font("Medium", 40), fill=(225, 225, 230))
d.rounded_rectangle([120, 880, 330, 900], 6, fill=(255, 255, 255, 90)); d.rounded_rectangle([120, 880, 140, 900], 6, fill=(255, 255, 255))
im.save("tv_show.png"); print("ok")
