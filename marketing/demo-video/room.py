import subprocess, math, json, sys
import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageFilter
W, H, FPS = 1080, 1350, 30
TXT = (246, 246, 248); MUT = (210, 210, 216)
F = "/usr/share/fonts/opentype/inter/"
_f = {}
def font(w, s):
    if (w, s) not in _f: _f[(w, s)] = ImageFont.truetype(F + f"Inter-{w}.otf", s)
    return _f[(w, s)]
P = "com.home.tvremote_ScreenshotTest_"

# ---- the room ----
SRC = Image.open("room_up.jpg").convert("RGB")                  # 3072x4080
SRC.paste(SRC.crop((700, 770, 2260, 815)).resize((1560, 70)), (700, 805))   # cover the screen-off message with the glass just above it
QUAD = [(223, 110), (2735, 100), (2723, 1540), (240, 1547)]     # TL TR BR BL of the screen
CROP = (0, 0, 3072, 3840); S0 = W / 3072                          # whole width, 4:5
R = SRC.crop(CROP).resize((int(3072 * S0 * 1.06) + 1, int(3840 * S0 * 1.06) + 1), Image.LANCZOS); RS = S0 * 1.06
RB = R.filter(ImageFilter.GaussianBlur(5))                         # blurred room → screen reflections
QR = [(x * RS, y * RS) for x, y in QUAD]
FOC = (sum(p[0] for p in QR) / 4, sum(p[1] for p in QR) / 4 + 40)   # push in toward the TV

def coeffs(dst, src):   # perspective: maps output (dst) points to source (src) points
    A = []; B = []
    for (x, y), (u, v) in zip(dst, src):
        A += [[x, y, 1, 0, 0, 0, -u * x, -u * y], [0, 0, 0, x, y, 1, -v * x, -v * y]]; B += [u, v]
    return np.linalg.solve(np.array(A, float), np.array(B, float)).tolist()

def room_frame(screen, z):
    """screen: 1920x1080 RGB (None = leave the real TV as it is). z: zoom ≥ 1."""
    cw, ch = W * 1.06 / z, H * 1.06 / z
    x0 = min(max(FOC[0] - cw / 2, 0), R.width - cw); y0 = min(max(FOC[1] * 0.35 - ch * 0.0, 0), R.height - ch)
    y0 = min(max(0, (R.height - ch) * 0.0 + (1 - 1 / z) * FOC[1]), R.height - ch)
    box = (x0, y0, x0 + cw, y0 + ch); k = W / cw
    img = R.resize((W, H), Image.BILINEAR, box=box)
    if screen is None: return img
    q = [((x - x0) * k, (y - y0) * k) for x, y in QR]
    c = coeffs(q, [(0, 0), (1920, 0), (1920, 1080), (0, 1080)])
    warped = screen.transform((W, H), Image.PERSPECTIVE, c, Image.BICUBIC)
    mask = Image.new("L", (W, H), 0); ImageDraw.Draw(mask).polygon(q, fill=255); mask = mask.filter(ImageFilter.GaussianBlur(0.8))
    refl = RB.resize((W, H), Image.BILINEAR, box=box)
    a = np.asarray(warped, np.float32); r = np.asarray(refl, np.float32)
    lit = np.clip(a * 0.94 + np.maximum(r - 18, 0) * 0.32, 0, 255).astype(np.uint8)   # glass picks up the room
    img.paste(Image.fromarray(lit), (0, 0), mask); return img

# ---- phone ----
def rounded(im, r):
    m = Image.new("L", im.size, 0); ImageDraw.Draw(m).rounded_rectangle([0, 0, im.width, im.height], r, fill=255)
    out = Image.new("RGBA", im.size); out.paste(im.convert("RGBA"), (0, 0), m); return out
_c = {}
def phone(name, h):
    k = (name, h)
    if k not in _c:
        s = Image.open(P + name + ".png").convert("RGB"); w = int(s.width * h / s.height); s = s.resize((w, h), Image.LANCZOS)
        pad = max(10, 14 * h // 1000); body = Image.new("RGBA", (w + 2 * pad, h + 2 * pad), (0, 0, 0, 0))
        ImageDraw.Draw(body).rounded_rectangle([0, 0, body.width - 1, body.height - 1], 58 * h // 1000, fill=(30, 30, 34))
        body.alpha_composite(rounded(s, 46 * h // 1000), (pad, pad))
        sh = Image.new("RGBA", (body.width + 120, body.height + 120), (0, 0, 0, 0)); a = Image.new("RGBA", body.size, (0, 0, 0, 170))
        sh.paste(a, (60, 78), body.split()[3]); sh = sh.filter(ImageFilter.GaussianBlur(26)); sh.alpha_composite(body, (60, 60)); _c[k] = (sh, pad)
    return _c[k]
PH = 520
def put_phone(img, name, h=PH, xy=None):
    sh, pad = phone(name, h); xy = xy or (W - sh.width + 60 - 44, 1150 - sh.height + 60)
    img.alpha_composite(sh, (xy[0] - 60, xy[1] - 60)); return (xy[0] + pad, xy[1] + pad, h / 1000)
def php(o, x, y): return (o[0] + x * o[2], o[1] + y * o[2])

# ---- captions / scrims ----
SCRIM = Image.new("RGBA", (W, H), (0, 0, 0, 0))
for y in range(H):
    a = max(0, (y - 880) / 400); ImageDraw.Draw(SCRIM).line([(0, y), (W, y)], fill=(8, 8, 10, int(235 * min(1, a) ** 1.2)))
def caption(img, title, sub):
    img.alpha_composite(SCRIM); d = ImageDraw.Draw(img)
    tw = d.textlength(title, font=font("Bold", 56)); d.text(((W - tw) / 2, 1178), title, font=font("Bold", 56), fill=TXT)
    if sub: sw = d.textlength(sub, font=font("Regular", 31)); d.text(((W - sw) / 2, 1256), sub, font=font("Regular", 31), fill=MUT)
def dim(img, a): img.alpha_composite(Image.new("RGBA", (W, H), (6, 6, 8, a)))
def centre_text(img, lines, small, y):
    d = ImageDraw.Draw(img)
    for l in lines: tw = d.textlength(l, font=font("Bold", 74)); d.text(((W - tw) / 2, y), l, font=font("Bold", 74), fill=TXT); y += 94
    sw = d.textlength(small, font=font("Regular", 34)); d.text(((W - sw) / 2, y + 30), small, font=font("Regular", 34), fill=MUT)

# ---- touches ----
def tap(img, t, at, xy, scale=1.0):
    dt = t - at
    if dt < -0.25 or dt > 0.75: return
    o = Image.new("RGBA", img.size, (0, 0, 0, 0)); d = ImageDraw.Draw(o); x, y = xy
    if dt < 0.35:
        a = int(150 * min(1, (dt + 0.25) / 0.15)); r = (30 if dt < 0 else 26) * scale
        d.ellipse([x - r, y - r, x + r, y + r], fill=(20, 20, 24, a), outline=(255, 255, 255, min(255, a + 80)), width=4)
    if dt >= 0:
        p = dt / 0.75; r = (26 + 52 * p) * scale; a = int(200 * (1 - p)); d.ellipse([x - r, y - r, x + r, y + r], outline=(255, 255, 255, a), width=max(3, int(5 * scale)))
    img.alpha_composite(o)
def finger(img, xy, a=150, r=22):
    o = Image.new("RGBA", img.size, (0, 0, 0, 0)); x, y = xy
    ImageDraw.Draw(o).ellipse([x - r, y - r, x + r, y + r], fill=(20, 20, 24, a), outline=(255, 255, 255, min(255, a + 80)), width=4); img.alpha_composite(o)
def arrow(scr, xy, scale=1.0):   # drawn on the 1920 TV image
    x, y = xy; s = 64 * scale
    pts = [(0, 0), (0, 1.0), (0.27, 0.76), (0.45, 1.12), (0.6, 1.05), (0.42, 0.7), (0.78, 0.7)]
    poly = [(x + px * s, y + py * s) for px, py in pts]; d = ImageDraw.Draw(scr)
    d.polygon([(px + 5, py + 7) for px, py in poly], fill=(0, 0, 0)); d.polygon(poly, fill=(255, 255, 255), outline=(20, 20, 24), width=3)
def tvtap(scr, t, at, xy):
    o = Image.new("RGBA", scr.size, (0, 0, 0, 0)); tap(o, t, at, xy, 2.0); s2 = scr.convert("RGBA"); s2.alpha_composite(o); return s2.convert("RGB")
_tv = {}
def tvimg(n):
    if n not in _tv: _tv[n] = Image.open(f"tv_{n}.png").convert("RGB").resize((1920, 1080), Image.LANCZOS)
    return _tv[n].copy()
def ease(p): p = min(1, max(0, p)); return 0.5 - 0.5 * math.cos(math.pi * p)
def bez(pts, p):
    while len(pts) > 1: pts = [(a[0] + (b[0] - a[0]) * p, a[1] + (b[1] - a[1]) * p) for a, b in zip(pts, pts[1:])]
    return pts[0]

# ---- scenes: fn(t, z) ----
def s_title(t, z):
    img = room_frame(None, z).convert("RGBA"); dim(img, 120); img.alpha_composite(SCRIM)
    centre_text(img, ["Your phone", "is the remote"], "LG & Samsung TVs", 1010); return img
def s_power(t, z):
    on = ease((t - 1.25) / 0.8); img = room_frame(None, z)
    if on > 0: img = Image.blend(img, room_frame(tvimg("home"), z), on)
    img = img.convert("RGBA"); caption(img, "Turns the TV on", "One tap, from the couch")
    o = put_phone(img, "demoRemote" if t >= 1.7 else "demoOff"); tap(img, t, 1.0, php(o, 225, 420), 1.5); return img
PATH = [(225, 560), (300, 470), (330, 380), (240, 330), (150, 400)]
CARD = (516, 814, 400, 225)            # The Bear in "Continue watching"
def s_touch(t, z):
    opened = t >= 3.0; p = ease((t - 0.4) / 1.9)
    if opened: scr = tvimg("bear")
    else:
        scr = tvimg("home"); d = ImageDraw.Draw(scr)
        if t >= 2.6: d.rounded_rectangle([CARD[0] - 10, CARD[1] - 10, CARD[0] + CARD[2] + 10, CARD[1] + CARD[3] + 10], 22, outline=(255, 255, 255), width=8)
        arrow(scr, bez([(1150, 420), (1650, 380), (1550, 760), (1000, 640), (716, 900)], p), 0.9 if 0 <= t - 2.6 < 0.15 else 1.0)
    img = room_frame(scr, z).convert("RGBA"); caption(img, "Point like a mouse", "Drag on the touchpad, tap to open")
    o = put_phone(img, "demoDisney" if opened else "demoRemote"); f = bez(PATH, p)
    if 0.25 < t < 2.4: finger(img, php(o, *f))
    tap(img, t, 2.6, php(o, *PATH[-1])); return img
APPT = [(0.6, 0, "netflix", "demoNetflix"), (2.0, 1, "youtube", "demoYoutube"), (3.4, 2, "prime", "demoPrime")]
def s_apps(t, z):
    tn, pn = "bear", "demoDisney"
    for at, k, a, b in APPT:
        if t >= at + 0.2: tn, pn = a, b
    img = room_frame(tvimg(tn), z).convert("RGBA"); caption(img, "Jump between apps", "One tap each, no scrolling")
    o = put_phone(img, pn)
    for at, k, _, _ in APPT: tap(img, t, at, php(o, 55 + k * 113, 182))
    return img
VT = [0.5, 0.9, 1.3]
def s_buttons(t, z):
    home = t >= 3.0; scr = tvimg("home" if home else "prime"); n = sum(1 for v in VT if t >= v + 0.1)
    if VT[0] + 0.1 <= t < 2.7:
        d = ImageDraw.Draw(scr); lvl = 12 + n; x0, y0, x1, y1 = 1700, 120, 1810, 640
        d.rounded_rectangle([x0, y0, x1, y1], 22, fill=(20, 20, 24)); top = y0 + 30 + (y1 - y0 - 110) * (1 - lvl / 30)
        d.rounded_rectangle([x0 + 30, y0 + 30, x1 - 30, y1 - 80], 12, fill=(70, 70, 78)); d.rounded_rectangle([x0 + 30, top, x1 - 30, y1 - 80], 12, fill=(255, 255, 255))
        f = font("SemiBold", 40); s_ = str(lvl); d.text(((x0 + x1) / 2 - d.textlength(s_, font=f) / 2, y1 - 66), s_, font=f, fill=(240, 240, 244))
    img = room_frame(scr, z).convert("RGBA"); caption(img, "Every button you need", "Volume up three, then Home")
    o = put_phone(img, "demoRemote" if home else "demoPrime")
    for v in VT: tap(img, t, v, php(o, 270, 945))
    tap(img, t, 2.8, php(o, 178, 866)); return img
QUERY = "dune trailer"; T0K = 0.5; DK = 0.17; ENTER = T0K + len(QUERY) * DK + 0.3
ROWS = ["qwertyuiop", "asdfghjkl", "zxcvbnm"]
def keypos(ch):
    if ch == " ": return (225, 925)
    if ch == "\n": return (408, 925)
    for r, row in enumerate(ROWS):
        if ch in row: i = row.index(ch); off = [0, 22, 66][r]; return (27 + off + i * 44.5, 700 + r * 75)
def keyboard(img, o, down=None):
    d = ImageDraw.Draw(img); sc = o[2]; X = lambda x, y: php(o, x, y)
    a = X(0, 640); b = X(450, 1000); d.rectangle([a[0], a[1], b[0], b[1] - 2], fill=(232, 234, 238))
    f = font("Regular", max(9, int(26 * sc)))
    def key(cx, cy, w, label, fill=(255, 255, 255), col=(30, 30, 34)):
        p0 = X(cx - w / 2, cy - 30); p1 = X(cx + w / 2, cy + 30)
        d.rounded_rectangle([p0[0], p0[1], p1[0], p1[1]], int(8 * sc), fill=(200, 204, 214) if label == down else fill)
        c = X(cx, cy); d.text((c[0] - d.textlength(label, font=f) / 2, c[1] - 9 * sc * 1.6), label, font=f, fill=col)
    for r, row in enumerate(ROWS):
        for ch in row: x, y = keypos(ch); key(x, y, 40, ch)
    key(40, 850, 58, "⇧", (210, 214, 222)); key(410, 850, 58, "⌫", (210, 214, 222))
    key(48, 925, 76, "?123", (210, 214, 222)); key(225, 925, 230, " "); key(408, 925, 70, "⏎", (66, 120, 230), (255, 255, 255))
def s_type(t, z):
    n = int(max(0, min(len(QUERY), (t - T0K) / DK + 1))) if t >= T0K else 0
    done = t >= ENTER + 0.2; q = QUERY[:n]
    scr = tvimg("results" if done else "search%d" % n)
    img = room_frame(scr, z).convert("RGBA"); caption(img, "Type with your phone", "Not letter by letter with arrow keys")
    o = put_phone(img, "demoTyping"); d = ImageDraw.Draw(img); fp = font("Regular", max(10, int(15 * o[2] / 0.6 * 0.95)))
    x0, y0 = php(o, 60, 247); x1, y1 = php(o, 300, 275)
    if n: d.rectangle([x0, y0, x1, y1], fill=(255, 255, 255)); d.text((x0 + 2, y0 + 1), q, font=fp, fill=(20, 20, 24))
    if not done:
        cx = x0 + 2 + (d.textlength(q, font=fp) if n else 0)
        if int(t * 2.5) % 2 == 0 or n < len(QUERY): d.line([(cx + 1, y0 + 2), (cx + 1, y1 - 4)], fill=(60, 110, 230), width=2)
        k = int((t - T0K) / DK) if t >= T0K else -1
        cur = QUERY[k] if 0 <= k < len(QUERY) and (t - T0K) % DK < 0.12 else None
        keyboard(img, o, cur if cur != " " else " ")
        for i, ch in enumerate(QUERY): tap(img, t, T0K + i * DK, php(o, *keypos(ch)), 0.55)
        tap(img, t, ENTER, php(o, *keypos("\n")), 0.7)
    return img
TIMES = ["10:00", "9:59", "9:58", "9:57", "9:56"]
def s_timer(t, z):
    k = int(t - 2.1) if t >= 2.1 else -1
    scr = tvimg("results") if k < 0 else tvimg("timer%d" % min(4, k))
    img = room_frame(scr, z).convert("RGBA"); caption(img, "A timer everyone can see", "Full-screen countdown on the TV")
    pn = "demoYoutube" if t < 0.8 else ("demoTimerOpen" if t < 2.2 else "demoTimerRunning")
    o = put_phone(img, pn)
    if pn == "demoTimerRunning":
        d = ImageDraw.Draw(img); a = php(o, 614, 0); x0, y0 = php(o, 614 - 450, 252); x1, y1 = php(o, 660 - 450, 274)
        d.rectangle([x0, y0, x1, y1], fill=(255, 255, 255)); d.text((x0, y0 - 1), TIMES[min(4, max(0, k))], font=font("Bold", max(10, int(15 * o[2] / 0.6 * 0.95))), fill=(20, 20, 24))
    tap(img, t, 0.5, php(o, 272, 866)); tap(img, t, 1.8, php(o, 172, 885)); return img
CHOOSE = (115, 536, 326, 124)   # "Light & funny" on the TV page
def s_ontv(t, z):
    on = t >= 1.0; p = ease((t - 1.3) / 1.4)
    scr = tvimg("choose" if on else "home")
    if on:
        d = ImageDraw.Draw(scr); x, y, w, h = CHOOSE
        if t >= 3.0: d.rounded_rectangle([x - 8, y - 8, x + w + 8, y + h + 8], 30, outline=(255, 255, 255), width=8)
        arrow(scr, (1300 + (x + w / 2 - 1300) * p, 300 + (y + h / 2 - 300) * p), 0.9 if 0 <= t - 3.0 < 0.15 else 1.0)
    img = room_frame(scr, z).convert("RGBA"); caption(img, "Choose together on the big screen", "Turn on “On TV”, your phone becomes the pointer")
    o = put_phone(img, "demoOnTvTouchpad" if on else "picksChooseLight")
    tap(img, t, 0.6, php(o, 393, 30))
    f = (300 + (170 - 300) * p, 330 + (560 - 330) * p)
    if 1.2 < t < 2.8: finger(img, php(o, *f))
    tap(img, t, 3.0, php(o, 170, 560)); return img
def s_voice(t, z):
    img = room_frame(tvimg("show"), z).convert("RGBA"); dim(img, 110); caption(img, "Or just say it", "“Something light for the two of us”")
    sh, pad = phone("demoListening", 960); put_phone(img, "demoListening", 960, ((W - sh.width + 120) // 2, 150)); return img
def s_picks(t, z):
    p = ease((t - 0.5) / 1.4); pos = (960 + (150 - 960) * p, 420 + (803 - 420) * p)
    scr = tvtap(tvimg("picks"), t, 2.2, (pos[0], pos[1] + 8)); arrow(scr, (pos[0] - 8, pos[1] - 12), 0.92 if 0 <= t - 2.2 < 0.15 else 1.0)
    img = room_frame(scr, z).convert("RGBA"); caption(img, "Five picks, on the big screen", "From the services you already have")
    o = put_phone(img, "demoOnTvPicks"); f = (320 + (170 - 320) * p, 300 + (600 - 300) * p)
    if 0.4 < t < 2.0: finger(img, php(o, *f))
    tap(img, t, 2.2, php(o, 170, 600)); return img
def s_play(t, z):
    img = room_frame(Image.blend(tvimg("playing"), tvimg("show"), ease((t - 1.0) / 0.6)), z).convert("RGBA")
    caption(img, "Tap Play, it opens", "Straight to the show"); put_phone(img, "demoPlaying"); return img
def s_end(t, z):
    img = room_frame(tvimg("show"), z).convert("RGBA"); dim(img, 150); img.alpha_composite(SCRIM)
    centre_text(img, ["What should", "we watch?"], "Phone remote · LG & Samsung · Free, no ads", 1000); return img

SC = [(3.0, s_title, []), (4.2, s_power, [1.0]), (4.8, s_touch, [2.6]), (4.8, s_apps, [a[0] for a in APPT]), (4.4, s_buttons, VT + [2.8]),
      (ENTER + 3.4, s_type, [T0K + i * DK for i in range(len(QUERY))] + [ENTER]), (5.4, s_timer, [0.5, 1.8]), (4.6, s_ontv, [0.6, 3.0]),
      (4.2, s_picks, [2.2]), (3.8, s_play, []), (3.4, s_voice, []), (3.6, s_end, [])]
XF = 0.45; starts = [0.0]
for d_, _, _ in SC[:-1]: starts.append(starts[-1] + d_ - XF)
total = starts[-1] + SC[-1][0]
json.dump({"len": total, "taps": [starts[i] + x for i, (_, _, ts) in enumerate(SC) for x in ts]}, open("timing.json", "w"))
T0 = starts[0]
def zoom(T): return 1.0 + 0.06 * T / total                        # one continuous slow push-in
def render(i, T): return SC[i][1](T - starts[i], zoom(T)).convert("RGB")

if "--sheet" in sys.argv:
    pick = [(2, 3.5), (4, 1.5), (5, 1.6), (5, 3.5), (6, 3.3), (8, 1.4)]
    sh = Image.new("RGB", (270 * 6, 338))
    for k, (i, t) in enumerate(pick): sh.paste(render(i, starts[i] + t).resize((270, 338)), (k * 270, 0))
    sh.save("sheet_room.png"); render(2, starts[2] + 2.7).save("full_touch.png"); print("sheet", round(total, 1)); sys.exit()
ff = subprocess.Popen(["ffmpeg", "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}", "-r", str(FPS), "-i", "-",
                       "-c:v", "libx264", "-crf", "18", "-preset", "medium", "-pix_fmt", "yuv420p", "demo_room.mp4"], stdin=subprocess.PIPE)
n = int(total * FPS)
for k in range(n):
    T = k / FPS; act = [i for i in range(len(SC)) if starts[i] <= T < starts[i] + SC[i][0]]
    f = Image.blend(render(act[0], T), render(act[1], T), (T - starts[act[1]]) / XF) if len(act) == 2 else render(act[0] if act else len(SC) - 1, T)
    ff.stdin.write(f.tobytes())
ff.stdin.close(); ff.wait(); print("frames", n, "seconds", round(total, 2))
