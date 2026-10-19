#!/usr/bin/env python3
"""
fix_ota_assert.py — make the AstOs ROM zip's device assert match reality.

The community device tree sets TARGET_OTA_ASSERT_DEVICE := TB3-710F
(hyphenated marketing name), but the device — and every TWRP build for it —
reports ro.product.device == "TB3710F". Flashing died with:

    E3004: This package is for device: TB3-710F; this Device is TB3710F

This script rewrites the first assert(...) line of
META-INF/com/google/android/updater-script inside the finished OTA zip so
it accepts BOTH names (and a couple of other TB3-710 family variants),
whatever prop the recovery reads. Run on the zip after 50-build-rom.sh
copies it out, BEFORE release. Idempotent.

Usage: fix_ota_assert.py <rom.zip>
"""
import re
import shutil
import sys
import zipfile

UPDATER = "META-INF/com/google/android/updater-script"

# Every name this family of devices/recoveries may report. The assert
# accepts any of these for ro.product.device / ro.build.product /
# ro.product.name.
DEVICE_NAMES = ["TB3710F", "TB3-710F", "TB3-710X", "TB3-710N", "TB3-710I"]

NEW_ASSERT = (
    "assert("
    + " || ".join(
        f'getprop("ro.product.device") == "{n}" '
        f'|| getprop("ro.build.product") == "{n}" '
        f'|| getprop("ro.product.name") == "{n}"'
        for n in DEVICE_NAMES
    )
    + ' || abort("E3004: This package is for device: '
    + " / ".join(DEVICE_NAMES)
    + '; this device is " + getprop("ro.product.device") + ".");'
)


def main():
    if len(sys.argv) != 2:
        print(__doc__)
        sys.exit(2)
    path = sys.argv[1]

    with zipfile.ZipFile(path, "r") as zin:
        names = zin.namelist()
        if UPDATER not in names:
            print(f"FATAL: {UPDATER} not found in {path}")
            sys.exit(1)
        script = zin.read(UPDATER).decode("utf-8", "replace")
        old_lines = script.splitlines()

        # Show the current assert for the build log.
        first_assert = next(
            (ln for ln in old_lines if ln.strip().startswith("assert(")), None
        )
        print("current assert line:")
        print("  " + (first_assert.strip()[:200] if first_assert else "<none>"))

        if NEW_ASSERT.rstrip(");") in (first_assert or ""):
            print("updater-script already patched — nothing to do")
            return

        # Replace ONLY the first device-assert line (the E3004 one).
        replaced = False
        new_lines = []
        for ln in old_lines:
            if not replaced and ln.strip().startswith("assert(") and "E3004" in ln:
                new_lines.append(NEW_ASSERT)
                replaced = True
            else:
                new_lines.append(ln)
        if not replaced:
            # Some trees emit the device assert without an E3004 literal
            # (plain equality chain). Replace the first assert that checks
            # ro.product.device / ro.build.product.
            for i, ln in enumerate(new_lines):
                if ln.strip().startswith("assert(") and (
                    'getprop("ro.product.device")' in ln
                    or 'getprop("ro.build.product")' in ln
                ):
                    new_lines[i] = NEW_ASSERT
                    replaced = True
                    break
        if not replaced:
            # Prepend our assert if none exists at all.
            new_lines.insert(0, NEW_ASSERT)
        new_script = "\n".join(new_lines) + "\n"

        tmp = path + ".tmp"
        with zipfile.ZipFile(tmp, "w") as zout:
            for item in zin.infolist():
                if item.filename == UPDATER:
                    zi = zipfile.ZipInfo(item.filename, date_time=item.date_time)
                    zi.compress_type = item.compress_type
                    zout.writestr(zi, new_script)
                else:
                    # Stream-copy every other entry byte-for-byte (keeps
                    # update-binary storage + all payloads untouched).
                    with zin.open(item, "r") as src, zout.open(item, "w") as dst:
                        shutil.copyfileobj(src, dst, 1024 * 1024)

    shutil.move(tmp, path)

    # Verify what we wrote.
    with zipfile.ZipFile(path, "r") as z:
        data = z.read(UPDATER).decode("utf-8", "replace")
    ok = all(n in data for n in ("TB3710F", "TB3-710F"))
    print("new assert line:")
    print("  " + next(l for l in data.splitlines() if "E3004" in l)[:200])
    print(f"VERIFY: {'PASS — assert now accepts TB3710F and TB3-710F' if ok else 'FAIL'}")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
