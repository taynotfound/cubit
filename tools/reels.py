"""Cuts 9:16 promo reels (TikTok, Reels, Shorts) from tools/record-demo.sh recordings: the cube cropped to fill the
frame, captions, the game's own music with a slide, chime or fanfare at each logged tap and win, and an end card.

Usage: python3 tools/reels.py <recordings folder> <output folder> <repo root>
"""
import random, subprocess, sys, os
from PIL import Image, ImageDraw, ImageFont

SP = sys.argv[1]; OUT = sys.argv[2]; REPO = sys.argv[3]
FONT = f"{REPO}/app/src/main/assets/fonts/BarlowCondensed-Bold.ttf"
FONT2 = f"{REPO}/app/src/main/assets/fonts/Barlow-Medium.ttf"
RAW = f"{REPO}/app/src/main/res/raw"
W, H = 1080, 1920
NAVY, PANEL, YELLOW, PINK, WHITE = (14, 27, 61), (23, 42, 87), (255, 199, 44), (255, 61, 127), (244, 246, 251)

def font(sz, f=FONT): return ImageFont.truetype(f, sz)

def caption(path, lines, accent=YELLOW):
    """A transparent full frame with one caption panel in the band above the cube."""
    im = Image.new("RGBA", (W, H), (0, 0, 0, 0)); d = ImageDraw.Draw(im)
    f = font(88 if max(len(l) for l in lines) <= 20 else 72)
    hs = [d.textbbox((0, 0), l, font=f) for l in lines]
    lh = max(b[3] - b[1] for b in hs) + 14
    tw = max(b[2] - b[0] for b in hs)
    bw, bh = tw + 80, lh * len(lines) + 44
    x0, y0 = (W - bw) // 2, 330
    d.rounded_rectangle([x0, y0, x0 + bw, y0 + bh], 18, fill=NAVY + (235,))
    d.rectangle([x0, y0 + 18, x0 + 10, y0 + bh - 18], fill=accent)
    for i, l in enumerate(lines):
        b = d.textbbox((0, 0), l, font=f)
        d.text(((W - (b[2] - b[0])) // 2 - b[0], y0 + 22 + i * lh - b[1]), l, font=f, fill=WHITE)
    im.save(path)

def endcard(path):
    im = Image.new("RGB", (W, H), NAVY); d = ImageDraw.Draw(im)
    icon = Image.open(f"{REPO}/docs/img/icon-512.png").convert("RGBA").resize((360, 360), Image.LANCZOS)
    im.paste(icon, ((W - 360) // 2, 470), icon)
    def center(t, y, f, c):
        b = d.textbbox((0, 0), t, font=f); d.text(((W - (b[2] - b[0])) // 2 - b[0], y), t, font=f, fill=c)
    center("CUBIT", 880, font(190), WHITE)
    center("FREE THE PINK ARROW", 1090, font(78), PINK)
    center("Free · No ads · No accounts · Android", 1210, font(48, FONT2), WHITE)
    d.rounded_rectangle([170, 1330, W - 170, 1450], 10, fill=YELLOW)
    center("GET IT ON F-DROID", 1352, font(72), NAVY)
    center("or GitHub · taynotfound.github.io/cubit", 1490, font(42, FONT2), (174, 184, 208))
    im.save(path)

def events(name):
    start = float(open(f"{SP}/start_{name}.txt").read())
    out = []
    for line in open(f"{SP}/log_{name}.txt"):
        p = line.split()
        if len(p) > 5 and "CubitDemo" in line: out.append((float(p[0]) - start, p[-1]))
    return out

def reel(name, src, t0, t1, caps, speed=1.0):
    """caps: [(start, end, [lines])] in reel time. Gameplay is src from t0 to t1 (recording time), sped up by speed."""
    dur = (t1 - t0) / speed; END = 2.6; total = dur + END
    inputs = ["-ss", str(t0), "-t", str(t1 - t0), "-i", f"{SP}/raw_{src}.mp4"]
    vf = [f"[0:v]setpts=(PTS-STARTPTS)/{speed},crop=686:1220:17:210,scale={W}:{H}:flags=lanczos,fps=30,format=yuv420p[g0]"]
    last = "g0"
    for i, (a, b, lines) in enumerate(caps):
        p = f"{SP}/cap_{name}_{i}.png"; caption(p, lines, PINK if i == len(caps) - 1 else YELLOW)
        inputs += ["-loop", "1", "-t", str(dur), "-i", p]
    n = len(caps)  # inputs: gameplay 0, captions 1..n, end card n+1, then audio
    for i, (a, b, lines) in enumerate(caps):
        b = min(b, dur)
        vf.append(f"[{i+1}:v]format=rgba,fade=in:st={a}:d=0.25:alpha=1,fade=out:st={max(a, b-0.25)}:d=0.25:alpha=1[c{i}]")
        vf.append(f"[{last}][c{i}]overlay=0:0:enable='between(t,{a},{b})'[g{i+1}]"); last = f"g{i+1}"
    ec = f"{SP}/endcard.png"; endcard(ec)
    inputs += ["-loop", "1", "-t", str(END + 0.5), "-i", ec]
    vf.append(f"[{n+1}:v]fps=30,format=yuv420p[e]")
    vf.append(f"[{last}][e]xfade=transition=fade:duration=0.5:offset={dur - 0.5}[v]")
    # Audio: the play loop, plus each logged tap, bomb and win at its moment.
    ai = n + 2
    inputs += ["-stream_loop", "-1", "-i", f"{RAW}/music_play.ogg", "-i", f"{RAW}/music_menu.ogg"]
    af = [f"[{ai}:a]atrim=0:{dur},volume=0.55,afade=out:st={dur-0.6}:d=0.6[m]", f"[{ai+1}:a]atrim=0:{END},volume=0.6,adelay={int((dur-0.3)*1000)}:all=1[mm]"]
    mix = ["[m]", "[mm]"]; j = ai + 2; rnd = random.Random(7)
    for t, kind in events(src):
        rt = (t - t0) / speed
        if rt < 0 or rt > dur - 0.2: continue
        files = {"tap": [f"sfx_slide_{rnd.randint(1,3)}.ogg"], "bomb": ["sfx_hint.ogg", f"sfx_slide_{rnd.randint(1,3)}.ogg"], "win": ["sfx_win.ogg"]}[kind]
        for f in files:
            inputs += ["-i", f"{RAW}/{f}"]
            vol = 1.0 if kind == "win" else 0.8
            af.append(f"[{j}:a]volume={vol},adelay={int(rt*1000)}:all=1[s{j}]"); mix.append(f"[s{j}]"); j += 1
    af.append(f"{''.join(mix)}amix=inputs={len(mix)}:normalize=0:dropout_transition=0,alimiter=limit=0.9,atrim=0:{total}[a]")
    out = f"{OUT}/cubit-{name}.mp4"
    cmd = ["ffmpeg", "-v", "error", "-y", *inputs, "-filter_complex", ";".join(vf + af), "-map", "[v]", "-map", "[a]",
           "-c:v", "libx264", "-preset", "slow", "-crf", "19", "-profile:v", "high", "-pix_fmt", "yuv420p", "-r", "30",
           "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart", "-t", str(total), out]
    subprocess.run(cmd, check=True)
    print(out, round(total, 1), "s")

ev = lambda n: [t for t, k in events(n) if k == "win"]
win = ev("level")[0]
reel("hook", "level", 3.2, win + 3.6, [
    (0.0, 3.4, ["CAN YOU FREE", "THE PINK ARROW?"]),
    (3.6, 8.4, ["TAP AN ARROW.", "IT SLIDES OUT, IF", "NOTHING'S IN THE WAY"]),
    (8.6, 12.4, ["KEEP GOING FOR COMBOS"]),
    (12.6, win - 3.2 - 0.1, ["DIG DOWN TO THE PINK ONE"]),
    (win - 3.2 + 0.2, win + 3.6 - 3.2, ["FREED."]),
])
win = ev("clean")[0]
reel("clean", "clean", 3.2, win + 3.0, [
    (0.0, 4.0, ["NO TIMER. NO LIVES.", "JUST THE CUBE."]),
    (4.2, 9.6, ["CLEAN MODE"]),
    (9.8, win - 3.2 - 0.1, ["WEIRDLY SATISFYING"]),
    (win - 3.2 + 0.2, win + 3.0 - 3.2, ["AND NOT ONE AD"]),
])
reel("bombs", "bombs", 3.0, 20.0, [
    (0.0, 3.6, ["ORANGE CORE?", "THAT'S A BOMB"]),
    (3.8, 8.6, ["ONE TAP. EVERY ARROW", "TOUCHING IT. GONE."]),
    (8.8, 13.0, ["BOMBS SET OFF BOMBS"]),
    (13.2, 17.0, ["NEW: CHAIN REACTIONS"]),
])
win = ev("endless")[0]
reel("endless", "endless", 3.0, win + 13.0, [
    (0.0, 3.6, ["HOW MANY CUBES", "CAN YOU CLEAR?"]),
    (3.8, 8.6, ["EACH ONE A LITTLE BIGGER"]),
    (8.8, (win - 3.0) / 1.35 - 0.2, ["YOUR LIVES CARRY OVER"]),
    ((win - 3.0) / 1.35 + 0.2, (win + 13.0 - 3.0) / 1.35, ["NEW: ENDLESS MODE"]),
], speed=1.35)
