#!/usr/bin/env python3
"""
gen_bootanimation.py — the AstOs ASCII boot animation.

1024x600 @ 20 fps, two parts:
  part0 (once):  the ASCII "AstOs" figlet logo types itself in with a
                 blinking block cursor, then the tagline "YOUR OWN OS"
  part1 (loop):  the logo pulses gently, an ASCII spinner spins, dots march

Palette: warm amber on near-black (the no-blue AstOs identity).
Output: vendor/astos/bootanimation/bootanimation.zip
"""
import os
import zipfile
from PIL import Image, ImageDraw, ImageFont

W, H, FPS = 1024, 600, 20
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf"

BG = (20, 16, 10)
FG = (255, 184, 77)       # amber
FG_SOFT = (242, 217, 160)  # cream

# ---- the same 5-line figlet font as the shell (keep them in sync) ----------
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
    # lowercase glyphs for the mixed-case "AstOs" brand
    's': [" ####", "#    ", " ### ", "    #", "#### "],
    't': ["  #  ", "  #  ", "#####", "  #  ", "  #  "],
    'o': ["     ", " ### ", "#   #", "#   #", " ### "],
}


def render_figlet(text):
    rows = ["", "", "", "", ""]
    for ch in text:
        g = FONT5.get(ch, FONT5[' '])
        for r in range(5):
            rows[r] += g[r] + " "
    return rows


def draw_ascii(img_draw, rows, origin_x, origin_y, cell, color, max_cols=None):
    """Draw figlet rows as colored blocks of 'cell' px (blocky ASCII look)."""
    for r, line in enumerate(rows):
        if max_cols is not None:
            line = line[:max_cols]
        for c, ch in enumerate(line):
            if ch == '#':
                x = origin_x + c * cell
                y = origin_y + r * cell
                img_draw.rectangle([x, y, x + cell - 1, y + cell - 1], fill=color)


def frame_logo_typing(progress, tag_progress, cursor_on):
    img = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(img)
    rows = render_figlet("AstOs")
    cell = 14
    lw = len(rows[0]) * cell
    lx = (W - lw) // 2
    ly = (H - 5 * cell) // 2 - 60

    total_hashes = sum(line.count('#') for line in rows)
    shown = int(total_hashes * min(1.0, progress))
    # reveal left-to-right, top-to-bottom
    count = 0
    done = False
    for r, line in enumerate(rows):
        for c, ch in enumerate(line):
            if ch == '#':
                count += 1
                if count > shown:
                    done = True
                    break
                x = lx + c * cell
                y = ly + r * cell
                d.rectangle([x, y, x + cell - 1, y + cell - 1], fill=FG)
        if done:
            break

    # blinking block cursor at the write position
    if not done and cursor_on:
        cx_ = lx
        cy_ = ly
        count = 0
        for r, line in enumerate(rows):
            for c, ch in enumerate(line):
                if ch == '#':
                    if count >= shown:
                        cx_ = lx + c * cell
                        cy_ = ly + r * cell
                        count = -1
                        break
                    count += 1
            if count == -1:
                break
        d.rectangle([cx_, cy_, cx_ + cell - 1, cy_ + cell - 1], fill=FG_SOFT)

    if done:
        # tagline typing
        tag = "YOUR OWN OPERATING SYSTEM"
        f = ImageFont.truetype(FONT, 28)
        shown_chars = int(len(tag) * min(1.0, tag_progress))
        d.text(((W - d.textlength(tag, font=f)) // 2, ly + 5 * cell + 90),
               tag[:shown_chars] + ("_" if cursor_on else ""), font=f, fill=FG_SOFT)
    return img


def frame_pulse(pulse, spinner_char, dots):
    img = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(img)
    rows = render_figlet("AstOs")
    cell = 14
    lx = (W - len(rows[0]) * cell) // 2
    ly = (H - 5 * cell) // 2 - 60
    color = FG if pulse else FG_SOFT
    for r, line in enumerate(rows):
        for c, ch in enumerate(line):
            if ch == '#':
                x = lx + c * cell
                y = ly + r * cell
                d.rectangle([x, y, x + cell - 1, y + cell - 1], fill=color)

    f = ImageFont.truetype(FONT, 28)
    f2 = ImageFont.truetype(FONT, 24)
    d.text(((W - d.textlength("booting", font=f)) // 2, ly + 5 * cell + 90),
           "booting", font=f, fill=FG_SOFT)
    d.text(((W - d.textlength(spinner_char + " " + dots, font=f2)) // 2,
            ly + 5 * cell + 130), spinner_char + " " + dots, font=f2, fill=FG)
    return img


def main():
    out_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                           "..", "vendor", "astos", "bootanimation")
    work = "/tmp/bootanim"
    os.makedirs(os.path.join(work, "part0"), exist_ok=True)
    os.makedirs(os.path.join(work, "part1"), exist_ok=True)

    # ---- part0: logo types in (72 frames @ 20fps = 3.6s), tagline, hold ----
    n0 = 72
    for i in range(n0):
        progress = min(1.0, i / 48.0)               # logo by frame 48
        tag_progress = max(0.0, min(1.0, (i - 54) / 14.0))
        cursor_on = (i // 4) % 2 == 0
        frame_logo_typing(progress, tag_progress, cursor_on).save(
            os.path.join(work, "part0", f"{i:04d}.png"), optimize=True)

    # ---- part1: pulse + spinner loop (40 frames = 2s) ----
    spin = "|/-\\"
    n1 = 40
    for i in range(n1):
        pulse = (i // 10) % 2 == 0
        frame_pulse(pulse, spin[i % 4], "." * (1 + (i // 5) % 6)).save(
            os.path.join(work, "part1", f"{i:04d}.png"), optimize=True)

    # ---- desc.txt ----
    desc = f"{W} {H} {FPS}\np 1 8 part0\np 0 0 part1\n"

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
    # sanity
    with zipfile.ZipFile(out_zip) as z:
        names = z.namelist()
        assert names[0] == "desc.txt", "desc.txt must be first"
        assert "part0/0000.png" in names and "part1/0039.png" in names
        print(f"OK: {len(names)} entries; desc.txt first; parts verified")


if __name__ == "__main__":
    main()
