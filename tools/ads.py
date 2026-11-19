"""Still ads for Reddit (and anything else that takes images): the cube with a headline and a call to action, in the
house look. Square (1080×1080), portrait (1080×1350) and landscape (1200×628).

Usage: python3 tools/ads.py <cube.png> <output folder> <repo root>
<cube.png> is a transparent render of the cube, e.g. a screenshot of play.html's canvas with its page background off.
"""
import sys
from PIL import Image, ImageDraw, ImageFont, ImageFilter

CUBE, OUT, REPO = sys.argv[1:4]
BOLD = f"{REPO}/app/src/main/assets/fonts/BarlowCondensed-Bold.ttf"
BODY = f"{REPO}/app/src/main/assets/fonts/Barlow-Medium.ttf"
NAVY, PANEL, YELLOW, PINK, WHITE, MUTED = (14, 27, 61), (23, 42, 87), (255, 199, 44), (255, 61, 127), (244, 246, 251), (174, 184, 208)

cube = Image.open(CUBE).convert("RGBA")
cube = cube.crop(cube.getbbox())

# Each ad: a headline (pink words in [brackets]), a line under it, and the button text.
COPY = {
    "pink": ("Free the [pink] arrow.", "A cube of 140 arrows. Tap one and it slides off, if nothing's in the way.", "Play free in your browser"),
    "daily": ("One cube. Everyone. [Today.]", "A new daily puzzle, the same for everyone. Can you hit par?", "Play today's cube"),
    "noads": ("No ads. No accounts. [Just the cube.]", "A free, open source puzzle game for Android, also playable in your browser.", "Try it now"),
}

def rich(d, xy, text, f, base=WHITE):
    x, y = xy
    for i, part in enumerate(text.replace("]", "[").split("[")):
        if not part: continue
        d.text((x, y), part, font=f, fill=PINK if i % 2 else base)
        x += d.textlength(part, font=f)

def wrap(d, text, f, width):
    words, lines, cur = text.split(), [], ""
    for w in words:
        t = (cur + " " + w).strip()
        if d.textlength(t.replace("[", "").replace("]", ""), font=f) <= width: cur = t
        else: lines.append(cur); cur = w
    return lines + [cur]

def make(name, W, H, headline, sub, cta, layout):
    im = Image.new("RGBA", (W, H), NAVY + (255,))
    d = ImageDraw.Draw(im)
    wide = layout == "wide"
    pad = int(W * 0.065) if not wide else int(H * 0.09)
    tw = int(W * 0.47) if wide else W - 2 * pad
    # brand mark
    s = int(H * 0.05) if wide else int(W * 0.028)
    d.rectangle([pad, pad, pad + s, pad + s], fill=YELLOW)
    d.text((pad + s * 1.5, pad - s * 0.28), "CUBIT", font=ImageFont.truetype(BOLD, int(s * 1.5)), fill=WHITE)
    # headline, line under it, button: text first, the cube takes whatever room is left
    hs = int(H * 0.1) if wide else int(W * 0.085)
    hf = ImageFont.truetype(BOLD, hs)
    y = pad + s * 2.6
    for line in wrap(d, headline.upper(), hf, tw):
        rich(d, (pad, y), line, hf); y += hs * 1.02
    sf = ImageFont.truetype(BODY, int(hs * 0.36))
    y += hs * 0.2
    for line in wrap(d, sub, sf, tw if wide else int(tw * 0.9)):
        d.text((pad, y), line, font=sf, fill=MUTED); y += hs * 0.48
    bf = ImageFont.truetype(BOLD, int(hs * 0.42))
    bw = d.textlength(cta.upper(), font=bf) + hs * 0.9; bh = hs * 0.8
    by = H - pad - bh if wide else y + hs * 0.35
    d.rounded_rectangle([pad, by, pad + bw, by + bh], int(bh * 0.12), fill=YELLOW)
    d.text((pad + hs * 0.45, by + bh * 0.17), cta.upper(), font=bf, fill=NAVY)
    if wide:
        left = pad + tw + pad * 0.5; side = int(min(W - left - pad * 0.3, H * 0.92)); cx, cy = int((left + W) / 2), H // 2
    else:
        top = by + bh + pad * 0.6; side = int(min(W * 0.8, H - top - pad * 0.4)); cx, cy = W // 2, int((top + H) / 2)
    glow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    ImageDraw.Draw(glow).ellipse([cx - side * 0.6, cy - side * 0.6, cx + side * 0.6, cy + side * 0.6], fill=(31, 79, 191, 110))
    base = Image.new("RGBA", (W, H), NAVY + (255,)); base.alpha_composite(glow.filter(ImageFilter.GaussianBlur(side * 0.12)))
    c = cube.copy(); c.thumbnail((side, side), Image.LANCZOS)
    base.alpha_composite(c, (cx - c.width // 2, cy - c.height // 2))
    # text layer on top of the cube layer: redraw the text image with a transparent background
    txt = Image.new("RGBA", (W, H), (0, 0, 0, 0)); txt.paste(im, (0, 0), mask=text_mask(im))
    base.alpha_composite(txt)
    base.convert("RGB").save(f"{OUT}/ad-{name}.png", optimize=True)

def text_mask(im):
    """Everything drawn on the navy background (text, mark, button) as a mask."""
    bg = Image.new("RGBA", im.size, NAVY + (255,))
    from PIL import ImageChops
    diff = ImageChops.difference(im, bg).convert("L")
    return diff.point(lambda v: 255 if v > 6 else int(v * 40))

for key, (h, s, c) in COPY.items():
    make(f"{key}-square", 1080, 1080, h, s, c, "tall")
    make(f"{key}-portrait", 1080, 1350, h, s, c, "tall")
    make(f"{key}-wide", 1200, 628, h, s, c, "wide")
