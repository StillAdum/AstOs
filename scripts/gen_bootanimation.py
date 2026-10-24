#!/usr/bin/env python3
"""
gen_bootanimation.py — the AstOs ASCII boot animation, v2 (orientation fix).

WHY PORTRAIT: the TB3-710F LCD is a portrait-scanned 600x1024 panel behind
ro.sf.hwrotation=270 (SurfaceFlinger rotates the landscape UI for it).
The device tree declares TARGET_SCREEN_WIDTH=600 / TARGET_SCREEN_HEIGHT=1024
and the MTK gralloc hands the bootanimation a portrait window, so our old
1024x600 landscape frames were center-cropped -> "boot animation not
landscape" on the real device.

So: we DESIGN everything in landscape (1024x600) exactly as it must read on
the glass, then pre-rotate each frame 90 degrees counter-clockwise and ship
600x1024 portrait frames with desc "600 1024 20".  The hwrotation pipeline
rotates them back to upright landscape on the glass.

If a future build ever reads sideways, flip ROT_SIGN below (-90) and
rebuild — that means the panel mount rotation went the other way.

Style: the boot animation is the one place that stays ASCII-art (blocky
figlet "AstOs" typing itself in, blinking cursor, ASCII spinner).  The
shell itself moved on to the pixel UI in AstOs 2.4.
"""
import os
import zipfile
from PIL import Image, ImageDraw, ImageFont

# ---- the only two knobs that matter ------------------------------------------
DESIGN_W, DESIGN_H = 1024, 600      # what we draw (landscape, as on glass)
ROT_SIGN = 90                       # CCW pre-rotation for hwrotation=270
FPS = 20

FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf"

BG = (20, 16, 10)
FG = (255, 184, 77)       # amber
FG_SOFT = (242, 217, 160)  # cream
SHADE = (122, 96, 66)      # dim amber for the spinner rail

# ---- the same 5-line blocky font as before (keep the brand) ------------------
FONT5 = {
    'A': ["  #  ", " # # ", "#####", "#   #", "#   #"],
    'B': ["#### ", "#   #", "#### ", "#   #", "#### "],
    'C': [" ####", "#    ", "#    ", "#    ", " ####"],
    'D': ["#### ", "#   #", "#   #", "#   #", "#### "],
    'E': ["#####", "#    ", "#### ", "#    ", "#####"],
    'F': ["#####", "#    ", "#### ", "#    ", "#    "],
    'G': [" ####", "#    ", "#  ##", "#   #", " ####"],
    'H': ["#   #", "#   #", "#####", "#   #", "#   #"],
    'I': ["#####", "  #  ", "  #  ", "  #  ", "#####"],
    'J': ["#####", "   # ", "   # ", "#  # ", " ##  "],
    'K': ["#   #", "#  # ", "###  ", "#  # ", "#   #"],
    'L': ["#    ", "#    ", "#    ", "#    ", "#####"],
    'M': ["#   #", "## ##", "# # #", "#   #", "#   #"],
    'N': ["#   #", "##  #", "# # #", "#  ##", "#   #"],
    'O': [" ### ", "#   #", "#   #", "#   #", " ### "],
    'P': ["#### ", "#   #", "#### ", "#    ", "#    "],
    'Q': [" ### ", "#   #", "#   #", " ### ", "    #"],
    'R': ["#### ", "#   #", "#### ", "#  # ", "#   #"],
    'S': [" ####", "#    ", " ### ", "    #", "#### "],
    'T': ["#####", "  #  ", "  #  ", "  #  ", "  #  "],
    'U': ["#   #", "#   #", "#   #", "#   #", " ### "],
    'V': ["#   #", "#   #", "#   #", " # # ", "  #  "],
    'W': ["#   #", "#   #", "# # #", "## ##", "#   #"],
    'X': ["#   #", " # # ", "  #  ", " # # ", "#   #"],
    'Y': ["#   #", " # # ", "  #  ", "  #  ", "  #  "],
    'Z': ["#####", "   # ", "  #  ", " #   ", "#####"],
    ' ': ["     ", "     ", "     ", "     ", "     "],
    's': [" ####", "#    ", " ### ", "    #", "#### "],
    't': ["  #  ", "  #  ", "#####", "  #  ", "  #  "],
    'o': ["     ", " ### ", "#   #", "#   #", " ### "],
}

# content kept compact + centered with generous margins: even a wrong
# rotation guess yields a complete (sideways) logo, never a cropped one.
CELL = 16                             # px per block
LOGO_X_CENTER = DESIGN_W // 2
LOGO_Y_CENTER = (DESIGN_H // 2) - 70  # slightly above middle for the tagline


def render_figlet(text):
    rows = ["", "", "", "", ""]
    for ch in text:
        g = FONT5.get(ch, FONT5[' '])
        for r in range(5):
            rows[r] += g[r] + " "
    return rows


def logo_origin(rows):
    lw = len(rows[0]) * CELL
    lx = LOGO_X_CENTER - lw // 2
    ly = LOGO_Y_CENTER - (5 * CELL) // 2
    return lx, ly


def draw_blocks(d, rows, shown, color, soft_blocks=None):
    """Reveal 'shown' hashes left-to-right / top-to-bottom."""
    count = 0
    for r, line in enumerate(rows):
        for c, ch in enumerate(line):
            if ch != '#':
                continue
            count += 1
            if count > shown:
                return count, None, None   # next block pos not reached yet
            x, y = _block_xy(rows, r, c)
            d.rectangle([x, y, x + CELL - 1, y + CELL - 1], fill=color)
    return count, None, None


def _block_xy(rows, r, c):
    lx, ly = logo_origin(rows)
    return lx + c * CELL, ly + r * CELL


def total_hashes(rows):
    return sum(line.count('#') for line in rows)


def frame_design(progress, tag_progress, cursor_on, pulse_on,
                 spinner, dots):
    """Design in LANDSCAPE coords (1024x600) — how it must read on glass."""
    img = Image.new("RGB", (DESIGN_W, DESIGN_H), BG)
    d = ImageDraw.Draw(img)
    rows = render_figlet("AstOs")
    total = total_hashes(rows)

    if progress < 1.0:
        # type-in phase
        shown = int(total * max(0.0, min(1.0, progress)))
        draw_blocks(d, rows, shown, FG)
        # blinking block cursor at the write head
        if cursor_on and shown < total:
            nxt = shown + 1
            count = 0
            for r, line in enumerate(rows):
                for c, ch in enumerate(line):
                    if ch != '#':
                        continue
                    count += 1
                    if count == nxt:
                        x, y = _block_xy(rows, r, c)
                        d.rectangle([x, y, x + CELL - 1, y + CELL - 1],
                                    fill=FG_SOFT)
    else:
        # fully typed: gentle pulse
        color = FG if pulse_on else FG_SOFT
        draw_blocks(d, rows, total, color)

    # tagline (typed after the logo)
    tag = "YOUR OWN OPERATING SYSTEM"
    f = ImageFont.truetype(FONT, 30)
    shown_chars = int(len(tag) * max(0.0, min(1.0, tag_progress)))
    text = tag[:shown_chars] + ("_" if (cursor_on and progress >= 1.0
                                        and shown_chars < len(tag)) else "")
    if text:
        tw = d.textlength(text, font=f)
        d.text(((DESIGN_W - tw) // 2, LOGO_Y_CENTER + 5 * CELL // 2 + 90),
               text, font=f, fill=FG_SOFT)

    # spinner + marching dots (ASCII, boot-anim-only as allowed)
    f2 = ImageFont.truetype(FONT, 26)
    line = (spinner + "  " + dots) if progress >= 1.0 else ""
    if line:
        tw = d.textlength(line, font=f2)
        d.text(((DESIGN_W - tw) // 2, LOGO_Y_CENTER + 5 * CELL // 2 + 140),
               line, font=f2, fill=FG)

    # bottom micro-caption: version + "booting"
    f3 = ImageFont.truetype(FONT, 22)
    cap = "AstOs 2.4  -  booting"
    tw = d.textlength(cap, font=f3)
    d.text(((DESIGN_W - tw) // 2, DESIGN_H - 64), cap, font=f3, fill=SHADE)
    return img


def to_panel(design):
    """Rotate the landscape design into the portrait panel format."""
    return design.rotate(ROT_SIGN, expand=True)


def main():
    out_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                           "..", "vendor", "astos", "bootanimation")
    work = "/tmp/bootanim24"
    os.makedirs(os.path.join(work, "part0"), exist_ok=True)
    os.makedirs(os.path.join(work, "part1"), exist_ok=True)

    spin = "|/-\\"

    # ---- part0: logo types in (60 frames @ 20fps = 3s) + tagline + hold ----
    n0 = 60
    for i in range(n0):
        logo_p = min(1.0, i / 36.0)               # logo by frame 36
        tag_p = max(0.0, min(1.0, (i - 42) / 12.0))
        cursor = (i // 4) % 2 == 0
        design = frame_design(logo_p, tag_p, cursor, False, spin[0], ".")
        to_panel(design).save(os.path.join(work, "part0", f"{i:04d}.png"),
                              optimize=True)

    # ---- part1: pulse + spinner loop (40 frames = 2s) ----
    n1 = 40
    for i in range(n1):
        pulse = (i // 10) % 2 == 0
        dots = "." * (1 + (i // 5) % 6)
        design = frame_design(1.0, 1.0, False, pulse,
                              spin[i % 4], dots)
        to_panel(design).save(os.path.join(work, "part1", f"{i:04d}.png"),
                              optimize=True)

    # ---- desc.txt: portrait panel dims (see header) ----
    desc = f"600 1024 {FPS}\np 1 8 part0\np 0 0 part1\n"

    # ---- pack (desc.txt first, entries STORED) ----
    out_zip = os.path.normpath(os.path.join(out_dir, "bootanimation.zip"))
    if os.path.exists(out_zip):
        os.remove(out_zip)
    with zipfile.ZipFile(out_zip, "w") as z:
        zi = zipfile.ZipInfo("desc.txt")
        zi.compress_type = zipfile.ZIP_STORED
        z.writestr(zi, desc)
        for part in ("part0", "part1"):
            for name in sorted(os.listdir(os.path.join(work, part))):
                zi = zipfile.ZipInfo(f"{part}/{name}")
                zi.compress_type = zipfile.ZIP_STORED
                with open(os.path.join(work, part, name), "rb") as fh:
                    z.writestr(zi, fh.read())

    size = os.path.getsize(out_zip)
    print(f"bootanimation.zip: {size/1024:.0f} KB")
    print(desc)

    # sanity: dims + order + completeness
    with zipfile.ZipFile(out_zip) as z:
        names = z.namelist()
        assert names[0] == "desc.txt", "desc.txt must be first"
        assert "part0/0000.png" in names and "part1/0039.png" in names
        from PIL import Image as I
        with z.open("part0/0000.png") as fh:
            im = I.open(fh)
            assert im.size == (600, 1024), f"frame must be 600x1024, got {im.size}"
        assert z.read("desc.txt").decode().startswith("600 1024")
        print(f"OK: {len(names)} entries; portrait 600x1024; desc first")


if __name__ == "__main__":
    main()
