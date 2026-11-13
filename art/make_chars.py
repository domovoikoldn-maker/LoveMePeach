# Два оригинальных взрослых персонажа (девушка и парень) в векторе -> PNG с прозрачностью.
# Голова рисуется в «большой» системе координат (ширина 800) и вставляется уменьшенной.
import math, subprocess, sys
import numpy as np
from PIL import Image, ImageFilter

OUT = "#36254F"; SKIN_LINE = "#9B5A4A"; LW = 4.2

def f(p): return f"{p[0]:.1f},{p[1]:.1f}"
def sym(start, segs, cx):
    mp = lambda p: (2 * cx - p[0], p[1])
    d = [f"M {f(start)}"]; pts = [start] + [s[2] for s in segs]
    for c1, c2, e in segs: d.append(f"C {f(c1)} {f(c2)} {f(e)}")
    for i in range(len(segs) - 1, -1, -1):
        c1, c2, e = segs[i]; d.append(f"C {f(mp(c2))} {f(mp(c1))} {f(mp(pts[i]))}")
    return " ".join(d) + " Z"
def star(cx, cy, r1, r2, rot=-90):
    pts = []
    for i in range(10):
        r = r1 if i % 2 == 0 else r2; a = math.radians(rot + i * 36)
        pts.append(f"{cx + r * math.cos(a):.1f},{cy + r * math.sin(a):.1f}")
    return "M " + " L ".join(pts) + " Z"

COMMON_DEFS = '''
  <filter id="blur2" x="-50%" y="-50%" width="200%" height="200%"><feGaussianBlur stdDeviation="2"/></filter>
  <filter id="blur4" x="-50%" y="-50%" width="200%" height="200%"><feGaussianBlur stdDeviation="4"/></filter>
  <filter id="blur7" x="-50%" y="-50%" width="200%" height="200%"><feGaussianBlur stdDeviation="7"/></filter>
  <filter id="blur14" x="-80%" y="-80%" width="260%" height="260%"><feGaussianBlur stdDeviation="14"/></filter>
  <linearGradient id="skinG" gradientUnits="userSpaceOnUse" x1="0" y1="140" x2="0" y2="420">
    <stop offset="0" stop-color="#FFEDE0"/><stop offset="1" stop-color="#FFDCC8"/></linearGradient>
  <linearGradient id="skinB" x1="0" y1="0" x2="1" y2="0">
    <stop offset="0" stop-color="#FFE6D6"/><stop offset="0.6" stop-color="#FFE0CE"/><stop offset="1" stop-color="#F2C4AE"/></linearGradient>
  <linearGradient id="scleraG" x1="0" y1="0" x2="0" y2="1">
    <stop offset="0" stop-color="#D9D2EC"/><stop offset="0.45" stop-color="#FFFFFF"/><stop offset="1" stop-color="#FFFFFF"/></linearGradient>
'''

# ---------------- голова (общая часть) ----------------
SCLERA = "M 296,288 C 296,256 322,246 340,246 C 360,246 376,260 376,286 C 376,314 360,332 338,332 C 314,332 296,316 296,288 Z"
def eye_group(gid, iris_id, lashes_male=False):
    lash = "" if lashes_male else '''
  <path d="M 290,284 L 276,272 L 293,268 Z" fill="#2B1A3A"/>
  <path d="M 294,300 L 282,297 L 294,290 Z" fill="#2B1A3A"/>
  <path d="M 306,325 Q 322,337 346,334" fill="none" stroke="#2B1A3A" stroke-width="2.6" stroke-linecap="round"/>'''
    top = ('<path d="M 290,282 C 296,252 322,240 342,240 C 362,240 378,252 382,276 C 372,260 358,252 340,252 C 320,252 304,262 296,286 Z" fill="#2B1A3A"/>'
           if lashes_male else
           '<path d="M 286,288 C 290,248 320,233 342,233 C 366,233 382,250 385,279 C 374,258 358,248 340,248 C 320,248 302,260 296,290 Z" fill="#2B1A3A"/>')
    return f'''
<g id="{gid}">
  <path d="{SCLERA}" fill="url(#scleraG)"/>
  <g clip-path="url(#eyeClip)">
    <ellipse cx="337" cy="293" rx="30" ry="37" fill="url(#{iris_id})"/>
    <ellipse cx="337" cy="293" rx="30" ry="37" fill="none" stroke="#1E1A3A" stroke-width="3.5" opacity="0.7"/>
    <ellipse cx="337" cy="291" rx="10" ry="14" fill="#1A1028"/>
    <ellipse cx="337" cy="256" rx="38" ry="22" fill="#1A1028" opacity="0.45"/>
    <ellipse cx="337" cy="318" rx="17" ry="8" fill="#FFFFFF" opacity="0.45" filter="url(#blur2)"/>
  </g>
  {top}{lash}
  <g fill="#FFFFFF"><ellipse cx="323" cy="273" rx="9" ry="10"/><circle cx="351" cy="308" r="4.2" opacity="0.9"/></g>
</g>'''

def head(face, front_hair, hair_grad, hair_light, hair_dark, iris_stops, male, extra_front="", extra_face=""):
    iris = f'''<linearGradient id="irisG" x1="0" y1="0" x2="0" y2="1">{iris_stops}</linearGradient>'''
    # глаза уже «взрослые»: ниже и уже, чем у чиби
    eye_t = "translate(337,292) scale(0.92,0.70) translate(-337,-292)"
    eyes = f'''<g transform="translate(0,6)"><use xlink:href="#eyeL" transform="{eye_t}"/>
<g transform="translate(800,0) scale(-1,1)"><use xlink:href="#eyeL" transform="{eye_t}"/></g></g>'''
    if male:
        brows = '''<g fill="none" stroke="#2A2E48" stroke-width="7" stroke-linecap="round">
  <path d="M 294,240 Q 330,226 372,236"/><path d="M 506,240 Q 470,226 428,236"/></g>'''
        mouth = '''<path d="M 382,374 Q 400,382 418,372" fill="none" stroke="#8A3E44" stroke-width="3.4" stroke-linecap="round"/>'''
        nose = '<path d="M 402,322 L 408,346 L 398,348" fill="none" stroke="#C98670" stroke-width="2.8" stroke-linecap="round" stroke-linejoin="round"/>'
    else:
        brows = '''<g fill="none" stroke="#4A2F9C" stroke-width="4.2" stroke-linecap="round" opacity="0.85">
  <path d="M 298,238 Q 334,222 370,232"/><path d="M 502,238 Q 466,222 430,232"/></g>'''
        mouth = '''<path d="M 380,372 Q 400,380 420,372" fill="none" stroke="#B8485E" stroke-width="3.2" stroke-linecap="round"/>
<path d="M 386,376 Q 400,390 414,376 Q 400,382 386,376 Z" fill="#E77890"/>
<ellipse cx="404" cy="381" rx="4" ry="1.6" fill="#FFFFFF" opacity="0.7"/>'''
        nose = '<path d="M 400,334 l 4,8" fill="none" stroke="#D08C7A" stroke-width="2.8" stroke-linecap="round"/>'
    defs = f'''{iris}
  <linearGradient id="hairG" gradientUnits="userSpaceOnUse" x1="0" y1="40" x2="0" y2="480">{hair_grad}</linearGradient>
  <clipPath id="faceClip"><path d="{face}"/></clipPath>
  <clipPath id="eyeClip"><path d="{SCLERA}"/></clipPath>
  <clipPath id="frontHairClip"><path d="{front_hair}"/></clipPath>
  {eye_group("eyeL", "irisG", lashes_male=male)}'''
    body = f'''
<path d="{face}" fill="url(#skinG)" stroke="{SKIN_LINE}" stroke-width="3.4"/>
<g clip-path="url(#faceClip)">
  <path d="{front_hair}" fill="#EDB29E" opacity="0.7" transform="translate(0,13)"/>
  <ellipse cx="530" cy="300" rx="24" ry="100" fill="#EBA993" opacity="0.5" filter="url(#blur7)"/>
  <ellipse cx="400" cy="420" rx="70" ry="12" fill="#EBA993" opacity="0.45" filter="url(#blur4)"/>
  <ellipse cx="306" cy="346" rx="28" ry="12" fill="#FF8098" opacity="{0.18 if male else 0.38}" filter="url(#blur7)"/>
  <ellipse cx="494" cy="346" rx="28" ry="12" fill="#FF8098" opacity="{0.18 if male else 0.38}" filter="url(#blur7)"/>
  {extra_face}
</g>
{eyes}
{nose}
{mouth}
<path d="{front_hair}" fill="url(#hairG)" stroke="{OUT}" stroke-width="{LW}" stroke-linejoin="round"/>
<g clip-path="url(#frontHairClip)">
  <ellipse cx="206" cy="310" rx="46" ry="190" fill="{hair_dark}" opacity="0.38" filter="url(#blur14)"/>
  <ellipse cx="594" cy="310" rx="46" ry="190" fill="{hair_dark}" opacity="0.48" filter="url(#blur14)"/>
  <ellipse cx="400" cy="236" rx="150" ry="26" fill="{hair_dark}" opacity="0.30" filter="url(#blur7)"/>
  <ellipse cx="330" cy="100" rx="120" ry="36" fill="{hair_light}" opacity="0.45" filter="url(#blur14)"/>
  <path d="M 214,196 Q 400,74 586,196 Q 400,118 214,196 Z" fill="{hair_light}" opacity="0.8"/>
  {extra_front}
</g>
<path d="{front_hair}" fill="none" stroke="{OUT}" stroke-width="{LW}" stroke-linejoin="round"/>
{brows}'''
    return defs, body

def render(svg, name, width_px):
    open(f"{name}.svg", "w", encoding="utf-8").write(svg)
    subprocess.run(["rsvg-convert", "-w", str(width_px), f"{name}.svg", "-o", f"{name}_raw.png"], check=True)
    im = Image.open(f"{name}_raw.png").convert("RGBA")
    l, t, r, b = im.getchannel("A").point(lambda a: 255 if a > 8 else 0).getbbox()
    im = im.crop((max(l - 4, 0), max(t - 4, 0), min(r + 4, im.width), b))   # ноги — на нижнем краю
    arr = np.asarray(im).astype(np.float32) / 255.0
    rgb, a = arr[..., :3], arr[..., 3]; h, w = a.shape
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    rgb = np.clip(rgb * (1.05 - 0.10 * (yy / h) - 0.05 * (xx / w))[..., None], 0, 1)
    a_img = Image.fromarray((a * 255).astype(np.uint8)); sh = Image.new("L", a_img.size, 0); sh.paste(a_img, (6, 6))
    rim = np.clip(a - np.asarray(sh).astype(np.float32) / 255.0, 0, 1) * a
    rim = np.asarray(Image.fromarray((rim * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(2))).astype(np.float32) / 255.0
    rgb = np.clip(rgb + rim[..., None] * np.array([1.0, 0.96, 0.92]) * 0.28, 0, 1)
    im = Image.fromarray((np.dstack([rgb, a]) * 255 + 0.5).astype(np.uint8), "RGBA")
    im.save(f"{name}.png", optimize=True)
    print(name, im.size)
    return im

HEAD_T = "translate(42,42) scale(0.52)"   # голова 800-системы -> тело шириной 500, центр x=250
MIRROR = 'transform="translate(500,0) scale(-1,1)"'

# =====================================================================
#                               ДЕВУШКА
# =====================================================================
F_FACE = "M 272,215 C 272,300 330,402 400,414 C 470,402 528,300 528,215 C 526,125 274,125 272,215 Z"
F_FRONT = sym((400, 48), [
    ((290, 50), (202, 130), (202, 262)),
    ((202, 380), (214, 470), (246, 520)),
    ((250, 440), (262, 340), (268, 226)),
    ((272, 240), (286, 254), (301, 256)),
    ((308, 244), (316, 228), (320, 214)),
    ((328, 232), (346, 248), (364, 252)),
    ((370, 240), (376, 226), (378, 212)),
    ((382, 236), (390, 258), (400, 272)),
], 400)
f_defs, f_head = head(
    F_FACE, F_FRONT,
    '<stop offset="0" stop-color="#9273EC"/><stop offset="0.6" stop-color="#7250D2"/><stop offset="1" stop-color="#9A5BD8"/>',
    "#D8C9FF", "#3A2690",
    '<stop offset="0" stop-color="#6B2A12"/><stop offset="0.45" stop-color="#E8781E"/><stop offset="0.8" stop-color="#FFC23D"/><stop offset="1" stop-color="#FFE27A"/>',
    male=False,
    extra_front='''<g fill="none" stroke="#4A2F9C" stroke-width="3" stroke-linecap="round" opacity="0.7">
    <path d="M 320,214 C 322,172 334,126 354,94"/><path d="M 378,212 C 376,160 382,112 396,80"/>
    <path d="M 480,214 C 478,172 466,126 446,94"/><path d="M 422,212 C 424,160 418,112 404,80"/>
    <path d="M 224,300 C 220,380 228,450 244,510"/><path d="M 576,300 C 580,380 572,450 556,510"/></g>''')

F_BACK = sym((250, 74), [
    ((186, 70), (146, 116), (148, 196)),
    ((150, 290), (122, 400), (124, 500)),
    ((126, 570), (140, 620), (162, 650)),
    ((166, 600), (176, 540), (182, 470)),
    ((206, 470), (232, 470), (250, 470)),
], 250)
# тело (левая половина, зеркалится)
F_DRESS = sym((250, 368), [
    ((236, 352), (218, 326), (206, 306)),      # вырез
    ((196, 310), (184, 314), (176, 320)),      # плечо
    ((166, 350), (166, 386), (174, 416)),      # бок груди
    ((182, 448), (202, 478), (206, 506)),      # талия
    ((210, 540), (176, 572), (172, 616)),      # бедро
    ((170, 650), (172, 682), (176, 704)),      # подол сбоку
    ((204, 712), (232, 714), (250, 714)),
], 250)
F_CHEST = sym((250, 300), [((230, 300), (214, 300), (200, 300)), ((190, 306), (180, 312), (172, 322)),
                           ((176, 360), (220, 380), (250, 386))], 250)
F_NECK = "M 232,250 L 268,250 L 270,310 L 230,310 Z"
F_LEG = "M 178,690 C 172,780 182,880 194,990 C 190,1060 192,1150 204,1330 L 226,1330 C 234,1180 238,1060 234,990 C 244,880 250,790 248,700 Z"
F_BOOT = "M 196,1160 L 236,1160 C 238,1260 236,1340 236,1410 C 240,1440 240,1470 226,1478 L 200,1478 C 186,1470 188,1440 194,1410 C 196,1340 194,1260 196,1160 Z"
F_SLEEVE = "M 176,318 C 154,326 144,360 140,420 C 136,500 130,580 128,662 L 160,666 C 164,590 172,510 178,450 C 182,400 188,350 176,318 Z"
F_HAND = "M 130,656 C 124,686 130,712 146,714 C 162,714 166,690 160,660 Z"
F_JACKET = "M 206,304 C 194,308 180,314 174,322 C 170,370 172,420 176,462 L 200,462 C 196,420 194,380 196,340 C 198,326 202,314 206,304 Z"

female = f'''<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" viewBox="0 30 500 1452" width="500" height="1452">
<defs>{COMMON_DEFS}
  <linearGradient id="backHairG" gradientUnits="userSpaceOnUse" x1="0" y1="60" x2="0" y2="660">
    <stop offset="0" stop-color="#6A4BC6"/><stop offset="0.55" stop-color="#5A3CB4"/><stop offset="0.85" stop-color="#A055C8"/><stop offset="1" stop-color="#F472B4"/></linearGradient>
  <linearGradient id="dressG" gradientUnits="userSpaceOnUse" x1="0" y1="300" x2="0" y2="720">
    <stop offset="0" stop-color="#2E2556"/><stop offset="1" stop-color="#3D2F86"/></linearGradient>
  <linearGradient id="dressSide" x1="0" y1="0" x2="1" y2="0">
    <stop offset="0" stop-color="#000" stop-opacity="0.35"/><stop offset="0.25" stop-color="#000" stop-opacity="0"/>
    <stop offset="0.75" stop-color="#000" stop-opacity="0"/><stop offset="1" stop-color="#000" stop-opacity="0.45"/></linearGradient>
  <linearGradient id="stockG" x1="0" y1="0" x2="1" y2="0">
    <stop offset="0" stop-color="#3B3452"/><stop offset="0.45" stop-color="#5B5378"/><stop offset="1" stop-color="#2A2440"/></linearGradient>
  <clipPath id="neckClipF"><path d="{F_NECK}"/></clipPath>
  <clipPath id="dressClip"><path d="{F_DRESS}"/></clipPath>
  <clipPath id="legClip"><path d="{F_LEG}"/></clipPath>
  <clipPath id="bootClip"><path d="{F_BOOT}"/></clipPath>
  <clipPath id="sleeveClip"><path d="{F_SLEEVE}"/></clipPath>
  <clipPath id="backHairClip"><path d="{F_BACK}"/></clipPath>
  <g id="legHalf">
    <path d="{F_LEG}" fill="url(#skinB)" stroke="{SKIN_LINE}" stroke-width="2.6"/>
    <g clip-path="url(#legClip)">
      <rect x="160" y="820" width="100" height="520" fill="url(#stockG)"/>
      <rect x="160" y="820" width="100" height="14" fill="#FF5C9E"/>
      <ellipse cx="210" cy="1000" rx="7" ry="80" fill="#8E86B0" opacity="0.6" filter="url(#blur4)"/>
      <rect x="160" y="690" width="100" height="40" fill="#C98670" opacity="0.5" filter="url(#blur4)"/>
    </g>
    <path d="{F_LEG}" fill="none" stroke="{OUT}" stroke-width="3.4" stroke-linejoin="round"/>
    <path d="{F_BOOT}" fill="#26213A" stroke="{OUT}" stroke-width="3.6" stroke-linejoin="round"/>
    <g clip-path="url(#bootClip)">
      <ellipse cx="208" cy="1300" rx="6" ry="120" fill="#6F6894" opacity="0.8" filter="url(#blur4)"/>
      <rect x="180" y="1160" width="70" height="12" fill="#FF5C9E"/>
      <rect x="180" y="1464" width="70" height="20" fill="#FF5C9E"/>
    </g>
    <path d="{F_BOOT}" fill="none" stroke="{OUT}" stroke-width="3.6" stroke-linejoin="round"/>
  </g>
  <g id="armHalf">
    <path d="{F_HAND}" fill="url(#skinB)" stroke="{SKIN_LINE}" stroke-width="2.6" stroke-linejoin="round"/>
    <path d="{F_SLEEVE}" fill="#FAF8FF" stroke="{OUT}" stroke-width="3.6" stroke-linejoin="round"/>
    <g clip-path="url(#sleeveClip)">
      <path d="M 160,666 C 164,590 172,510 178,450 C 182,400 188,350 176,318 L 168,330 C 172,380 162,500 150,666 Z" fill="#DDD6F3"/>
      <rect x="120" y="470" width="80" height="9" fill="#FF5C9E"/>
      <rect x="120" y="640" width="80" height="30" fill="#FF5C9E"/>
      <ellipse cx="146" cy="420" rx="8" ry="40" fill="#FFFFFF" filter="url(#blur4)"/>
    </g>
    <path d="{F_SLEEVE}" fill="none" stroke="{OUT}" stroke-width="3.6" stroke-linejoin="round"/>
    <path d="{F_JACKET}" fill="#FAF8FF" stroke="{OUT}" stroke-width="3.6" stroke-linejoin="round"/>
    <path d="M 176,452 L 200,452 L 200,462 L 176,462 Z" fill="#FF5C9E"/>
  </g>
  {f_defs}
</defs>

<!-- волосы сзади -->
<path d="{F_BACK}" fill="url(#backHairG)" stroke="{OUT}" stroke-width="3.6" stroke-linejoin="round"/>
<g clip-path="url(#backHairClip)">
  <ellipse cx="250" cy="420" rx="90" ry="200" fill="#2A1B66" opacity="0.5" filter="url(#blur14)"/>
  <g fill="none" stroke="#3E2A8C" stroke-width="2.2" opacity="0.55"><path d="M 140,260 C 128,380 132,520 158,640"/><path d="M 360,260 C 372,380 368,520 342,640"/></g>
</g>

<!-- ноги -->
<g transform="translate(0,700) scale(1,0.8) translate(0,-700)"><use xlink:href="#legHalf"/><use xlink:href="#legHalf" {MIRROR}/></g>

<!-- шея и плечи -->
<path d="{F_NECK}" fill="url(#skinB)" stroke="{SKIN_LINE}" stroke-width="2.4"/>
<ellipse cx="250" cy="262" rx="26" ry="12" fill="#E3A08C" opacity="0.8" filter="url(#blur2)" clip-path="url(#neckClipF)"/>
<path d="{F_CHEST}" fill="url(#skinB)" stroke="{SKIN_LINE}" stroke-width="2.4"/>
<path d="M 212,316 Q 228,322 242,318 M 288,316 Q 272,322 258,318" fill="none" stroke="#D99A86" stroke-width="2" stroke-linecap="round"/>

<!-- платье -->
<path d="{F_DRESS}" fill="url(#dressG)" stroke="{OUT}" stroke-width="3.8" stroke-linejoin="round"/>
<g clip-path="url(#dressClip)">
  <rect x="160" y="300" width="180" height="420" fill="url(#dressSide)"/>
  <!-- объём груди: мягкие блики и тени под ней -->
  <ellipse cx="214" cy="392" rx="26" ry="20" fill="#7B6BC8" opacity="0.55" filter="url(#blur7)"/>
  <ellipse cx="286" cy="392" rx="26" ry="20" fill="#7B6BC8" opacity="0.45" filter="url(#blur7)"/>
  <path d="M 182,426 Q 214,446 246,424 M 318,426 Q 286,446 254,424" fill="none" stroke="#161230" stroke-width="3" stroke-linecap="round" opacity="0.8"/>
  <path d="M 250,372 L 250,420" stroke="#161230" stroke-width="2.4" opacity="0.6"/>
  <!-- пояс и талия -->
  <path d="M 196,496 Q 250,506 304,496 L 304,512 Q 250,522 196,512 Z" fill="#FF5C9E"/>
  <circle cx="250" cy="508" r="7" fill="#FFD84D" stroke="#B9791A" stroke-width="2"/>
  <ellipse cx="200" cy="600" rx="10" ry="60" fill="#7B6BC8" opacity="0.4" filter="url(#blur7)"/>
  <path d="M 222,560 L 214,712 M 278,560 L 286,712" stroke="#1E1840" stroke-width="2.4" opacity="0.7"/>
  <path d="M 172,696 Q 250,720 328,696" fill="none" stroke="#FF5C9E" stroke-width="4"/>
</g>
<path d="{F_DRESS}" fill="none" stroke="{OUT}" stroke-width="3.8" stroke-linejoin="round"/>
<!-- кулон -->
<path d="M 222,312 Q 250,346 278,312" fill="none" stroke="#FFD84D" stroke-width="1.6"/>
<path d="{star(250, 346, 7, 3.2)}" fill="#FFD84D" stroke="#B9791A" stroke-width="1.4"/>

<!-- руки и жакет -->
<use xlink:href="#armHalf"/><use xlink:href="#armHalf" {MIRROR}/>

<!-- голова -->
<g transform="rotate(-2 250 260)"><g transform="{HEAD_T}">
{f_head}
<path d="M 394,54 C 372,8 424,-18 458,10 C 432,6 410,22 414,54 Z" fill="url(#hairG)" stroke="{OUT}" stroke-width="{LW}" stroke-linejoin="round"/>
<path d="{star(530, 176, 25, 11.5, -100)}" fill="#FFD84D" stroke="#B9791A" stroke-width="3.4" stroke-linejoin="round"/>
</g></g>
</svg>'''

# =====================================================================
#                                ПАРЕНЬ
# =====================================================================
M_FACE = "M 274,212 C 274,300 318,388 372,414 Q 400,424 428,414 C 482,388 526,300 526,212 C 524,122 276,122 274,212 Z"
M_FRONT = sym((400, 60), [
    ((300, 50), (214, 110), (206, 230)),
    ((200, 290), (210, 340), (230, 380)),     # висок
    ((238, 330), (250, 280), (262, 240)),
    ((268, 260), (280, 278), (296, 290)),     # прядь на лоб
    ((300, 260), (306, 236), (316, 214)),
    ((326, 246), (346, 268), (366, 276)),
    ((366, 248), (370, 226), (374, 206)),
    ((384, 238), (394, 262), (400, 270)),
], 400)
m_defs, m_head = head(
    M_FACE, M_FRONT,
    '<stop offset="0" stop-color="#3C4A78"/><stop offset="0.6" stop-color="#2A3460"/><stop offset="1" stop-color="#1E2648"/>',
    "#8FB8E8", "#0E1430",
    '<stop offset="0" stop-color="#0E3A40"/><stop offset="0.45" stop-color="#1F9AA0"/><stop offset="0.85" stop-color="#5FE0D0"/><stop offset="1" stop-color="#B8FFF0"/>',
    male=True,
    extra_front='''<g fill="none" stroke="#141C3C" stroke-width="3" stroke-linecap="round" opacity="0.75">
    <path d="M 316,214 C 320,170 332,126 356,96"/><path d="M 374,206 C 374,160 382,116 398,86"/>
    <path d="M 296,290 C 280,230 284,170 306,120"/><path d="M 470,230 C 470,180 456,130 430,96"/>
    <path d="M 520,240 C 530,190 522,140 500,110"/></g>''')
# правая сторона чёлки у парня асимметрична: прядь, заходящая на лоб
M_LOCK = "M 430,90 C 476,120 512,170 514,236 C 500,214 486,200 470,194 C 474,230 470,262 458,286 C 452,240 440,206 420,186 Z"

M_BACK = sym((250, 66), [((192, 66), (146, 104), (146, 170)), ((146, 200), (152, 216), (166, 226)),
                         ((200, 230), (236, 230), (250, 230))], 250)
M_NECK = "M 228,250 L 272,250 L 276,318 L 224,318 Z"
M_SHIRT = sym((250, 330), [((236, 330), (222, 318), (214, 304)), ((196, 312), (170, 318), (156, 330)),
                           ((156, 420), (176, 520), (192, 620)), ((220, 628), (240, 628), (250, 628))], 250)
M_JACKET = "M 214,302 C 194,306 170,314 154,328 C 150,420 160,520 172,640 L 214,640 C 210,560 206,470 210,400 C 214,360 222,330 228,316 Z"
M_SLEEVE = "M 156,326 C 132,336 120,380 116,440 C 112,520 108,600 108,668 L 146,672 C 150,600 156,520 162,460 C 168,400 176,350 156,326 Z"
M_HAND = "M 110,664 C 102,694 110,722 128,724 C 146,724 152,698 146,668 Z"
M_LEG = "M 190,612 C 186,760 190,1000 196,1404 L 244,1404 C 246,1100 250,900 250,640 C 230,630 206,622 190,612 Z"
M_SHOE = "M 190,1386 C 180,1410 172,1452 180,1476 L 250,1476 C 254,1452 252,1414 246,1386 Z"

male_svg = f'''<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" viewBox="0 36 500 1440" width="500" height="1440">
<defs>{COMMON_DEFS}
  <linearGradient id="jeansG" x1="0" y1="0" x2="1" y2="0">
    <stop offset="0" stop-color="#2B3354"/><stop offset="0.5" stop-color="#3E4A78"/><stop offset="1" stop-color="#232A48"/></linearGradient>
  <linearGradient id="jacketG" gradientUnits="userSpaceOnUse" x1="0" y1="300" x2="0" y2="680">
    <stop offset="0" stop-color="#2A2638"/><stop offset="1" stop-color="#1C1928"/></linearGradient>
  <clipPath id="legClip"><path d="{M_LEG}"/></clipPath>
  <clipPath id="neckClipM"><path d="{M_NECK}"/></clipPath>
  <clipPath id="shirtClip"><path d="{M_SHIRT}"/></clipPath>
  <clipPath id="sleeveClip"><path d="{M_SLEEVE}"/></clipPath>
  <clipPath id="jacketClip"><path d="{M_JACKET}"/></clipPath>
  <clipPath id="shoeClip"><path d="{M_SHOE}"/></clipPath>
  <g id="legHalf">
    <path d="{M_LEG}" fill="url(#jeansG)" stroke="{OUT}" stroke-width="3.6" stroke-linejoin="round"/>
    <g clip-path="url(#legClip)">
      <path d="M 214,700 Q 220,900 222,1400" fill="none" stroke="#5A689C" stroke-width="2" opacity="0.6"/>
      <path d="M 200,980 Q 222,990 244,976 M 200,1004 Q 222,1012 246,1000" fill="none" stroke="#1A2038" stroke-width="2.4" opacity="0.6"/>
      <rect x="180" y="1350" width="80" height="56" fill="#1A2038" opacity="0.5"/>
    </g>
    <path d="{M_SHOE}" fill="#FDFCFF" stroke="{OUT}" stroke-width="3.6" stroke-linejoin="round"/>
    <g clip-path="url(#shoeClip)">
      <rect x="170" y="1460" width="90" height="20" fill="#2FC2B8"/>
      <ellipse cx="240" cy="1430" rx="8" ry="40" fill="#CFC7EE" filter="url(#blur4)"/>
      <path d="M 196,1404 L 238,1404 M 194,1416 L 240,1416" stroke="#2FC2B8" stroke-width="3" stroke-linecap="round"/>
    </g>
    <path d="{M_SHOE}" fill="none" stroke="{OUT}" stroke-width="3.6" stroke-linejoin="round"/>
  </g>
  <g id="armHalf">
    <path d="{M_HAND}" fill="url(#skinB)" stroke="{SKIN_LINE}" stroke-width="2.6" stroke-linejoin="round"/>
    <path d="{M_SLEEVE}" fill="url(#jacketG)" stroke="{OUT}" stroke-width="3.6" stroke-linejoin="round"/>
    <g clip-path="url(#sleeveClip)">
      <ellipse cx="128" cy="440" rx="8" ry="60" fill="#5A5478" opacity="0.8" filter="url(#blur4)"/>
      <rect x="100" y="644" width="80" height="30" fill="#141220"/>
      <path d="M 120,540 Q 136,552 156,546" fill="none" stroke="#141220" stroke-width="2.4"/>
    </g>
    <path d="{M_SLEEVE}" fill="none" stroke="{OUT}" stroke-width="3.6" stroke-linejoin="round"/>
    <path d="{M_JACKET}" fill="url(#jacketG)" stroke="{OUT}" stroke-width="3.6" stroke-linejoin="round"/>
    <g clip-path="url(#jacketClip)">
      <path d="M 228,316 L 196,420 L 210,430 Z" fill="#3A3550"/>
      <rect x="150" y="620" width="80" height="24" fill="#141220"/>
      <path d="M 176,520 L 204,520" stroke="#2FC2B8" stroke-width="3" stroke-linecap="round"/>
    </g>
    <path d="{M_JACKET}" fill="none" stroke="{OUT}" stroke-width="3.6" stroke-linejoin="round"/>
  </g>
  {m_defs}
</defs>

<path d="{M_BACK}" fill="#1E2648" stroke="{OUT}" stroke-width="3.6" stroke-linejoin="round"/>
<g transform="translate(0,620) scale(1,0.8) translate(0,-620)"><use xlink:href="#legHalf"/><use xlink:href="#legHalf" {MIRROR}/></g>
<path d="{M_NECK}" fill="url(#skinB)" stroke="{SKIN_LINE}" stroke-width="2.4"/>
<ellipse cx="250" cy="266" rx="28" ry="14" fill="#E3A08C" opacity="0.85" filter="url(#blur2)" clip-path="url(#neckClipM)"/>
<path d="M 254,282 l 4,10" stroke="#D99A86" stroke-width="2" stroke-linecap="round"/>
<path d="{M_SHIRT}" fill="#F6F7FB" stroke="{OUT}" stroke-width="3.6" stroke-linejoin="round"/>
<g clip-path="url(#shirtClip)">
  <ellipse cx="250" cy="300" rx="50" ry="22" fill="#C9CCE0" filter="url(#blur4)"/>
  <path d="M 216,400 Q 232,410 248,402 M 284,400 Q 268,410 252,402" fill="none" stroke="#C9CCE0" stroke-width="2.4"/>
  <circle cx="250" cy="470" r="30" fill="none" stroke="#2FC2B8" stroke-width="5"/>
  <path d="M 236,470 L 246,458 L 254,478 L 264,466" fill="none" stroke="#2FC2B8" stroke-width="4" stroke-linecap="round" stroke-linejoin="round"/>
  <path d="M 208,540 Q 250,552 292,540" fill="none" stroke="#C9CCE0" stroke-width="2.4"/>
  <rect x="150" y="600" width="200" height="30" fill="#2A2638"/>
  <rect x="236" y="602" width="28" height="22" rx="3" fill="#B9BED4" stroke="#6A6F88" stroke-width="2"/>
</g>
<path d="{M_SHIRT}" fill="none" stroke="{OUT}" stroke-width="3.6" stroke-linejoin="round"/>
<use xlink:href="#armHalf"/><use xlink:href="#armHalf" {MIRROR}/>

<g transform="rotate(2 250 260)"><g transform="{HEAD_T}">
{m_head}
<path d="M 380,64 C 360,20 400,0 430,18 C 410,22 398,40 404,62 Z" fill="url(#hairG)" stroke="{OUT}" stroke-width="{LW}" stroke-linejoin="round"/>
<path d="M 524,300 L 530,318 L 524,330" fill="none" stroke="#FFFFFF" stroke-width="0"/>
</g></g>
</svg>'''

render(female, "female", 760)
render(male_svg, "male", 760)
# превью рядом на фоне «стола»
a, b = Image.open("female.png"), Image.open("male.png")
H = 700
a = a.resize((round(a.width * H / a.height), H), Image.LANCZOS); b = b.resize((round(b.width * H / b.height), H), Image.LANCZOS)
bg = Image.new("RGBA", (a.width + b.width + 90, H + 40), (120, 110, 100, 255))
bg.alpha_composite(a, (30, 20)); bg.alpha_composite(b, (a.width + 60, 20)); bg.convert("RGB").save("pair_preview.png")
