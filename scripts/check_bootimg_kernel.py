#!/usr/bin/env python3
"""AstOs boot gates for TB3-710F (MT8127) — hard CI checks.

Two levels of checking:

 A) boot.img structural validation (accepts a raw boot.img, or any OTA zip
    containing boot.img):
      * ANDROID! magic, page size 2048, MTK KERNEL / ROOTFS sub-headers
        (88 16 88 58) exactly as produced by device/lenovo/TB3710F/mkbootimg.mk
      * the load addresses this device's LK expects (kernel 0x80008000,
        ramdisk 0x84000000, tags 0x80000100)
      * the exact BOARD_KERNEL_CMDLINE
      * sane compiled-kernel size (mt8127_defconfig zImage is ~4.5 MB)
      * the ramdisk cpio contains init + fstab.mt8127

 B) OTA-zip boot gates — the three historical AstOs boot-killers:
      1. libstlport.so must be present in system/lib with the pinned sha256.
         The MTK display/camera blobs (libdpframework, lib3a, libcam*,
         libimageio*) are linked against it; without it the display pipeline
         dies during HWC init ("Fixed Android failing to boot because of
         missing libstlport.so" — community LOS changelog for this device).
      2. The updater-script's set_metadata for /system/bin/surfaceflinger
         must carry capabilities 0x0. With file capabilities, surfaceflinger
         is AT_SECURE at exec and the linker SANITIZES LD_PRELOAD — but
         init.environ.rc exports libxlog.so:libmtk_symbols.so, which the MTK
         mali blobs need. Unpatched builds froze at the Lenovo logo forever
         (fixed by the device tree's
         patches/system/core/0001-Remove-CAP_SYS_NICE-from-surfaceflinger).
      3. system/bin/surfaceflinger must exist in the payload.

Usage:  check_bootimg_kernel.py FILE [FILE...]   (boot.img or OTA zip)
Exit 0 = all files pass; exit 1 = gate failure (build must not ship).
"""
import gzip
import hashlib
import re
import struct
import sys
import zipfile

# --- device constants (from BoardConfig.mk) -----------------------------------
PAGE_SIZE = 2048
MTK_MAGIC = bytes.fromhex("88168858")
ANDROID_MAGIC = b"ANDROID!"
KERNEL_ADDR = 0x80008000    # base 0x80000000 + BOARD_KERNEL_OFFSET 0x8000
RAMDISK_ADDR = 0x84000000   # base 0x80000000 + BOARD_RAMDISK_OFFSET 0x4000000
TAGS_ADDR = 0x80000100      # base 0x80000000 + BOARD_KERNEL_TAGS_OFFSET 0x100
EXPECT_CMDLINE = "androidboot.hardware=mt8127 androidboot.selinux=permissive"
KERNEL_SIZE_MIN = 3_000_000   # mt8127_defconfig zImage compiles to ~4.56 MB
KERNEL_SIZE_MAX = 6_500_000

# libstlport.so shipped by the known-good community LOS build (2023-03-23),
# byte-identical to what AstOs vendors in vendor/astos/prebuilts/.
STLPORT_SHA256 = "0267d702674aa88ee28c90c96eaefa82602b50912dd8994667c75bd8582bc935"


def fail(label, msg):
    print("FAIL %s: %s" % (label, msg))


def load(path):
    """Return (boot_img_bytes, ota_zip_or_None, label).

    The zip handle is intentionally left open — the caller still needs to
    read payload members; the process is short-lived so GC/exit closes it.
    """
    with open(path, "rb") as f:
        head = f.read(4)
    if head == b"PK\x03\x04":
        z = zipfile.ZipFile(path)
        try:
            names = [n for n in z.namelist() if n == "boot.img" or n.endswith("/boot.img")]
            if not names:
                z.close()
                raise RuntimeError("zip contains no boot.img")
            names.sort()
            return z.read(names[0]), z, path
        except Exception:
            z.close()
            raise
    with open(path, "rb") as f:
        return f.read(), None, path


def cpio_has_entry(cpio_bytes, name):
    return (name.encode() + b"\x00") in cpio_bytes


def check_boot_image(blob, label):
    """Level A. Returns True/False."""
    ok = True
    if blob[:8] != ANDROID_MAGIC:
        return fail(label, "bad ANDROID magic: %r" % blob[:8]) or False
    (kernel_size, kernel_addr, ramdisk_size, ramdisk_addr, second_size,
     _second_addr, tags_addr, page_size) = struct.unpack_from("<8I", blob, 8)
    cmdline = blob[64:64 + 512].rstrip(b"\x00").decode(errors="replace").strip()

    if page_size != PAGE_SIZE:
        fail(label, "page size %d != %d" % (page_size, PAGE_SIZE)); ok = False
    if kernel_addr != KERNEL_ADDR:
        fail(label, "kernel_addr 0x%08x != 0x%08x" % (kernel_addr, KERNEL_ADDR)); ok = False
    if ramdisk_addr != RAMDISK_ADDR:
        fail(label, "ramdisk_addr 0x%08x != 0x%08x" % (ramdisk_addr, RAMDISK_ADDR)); ok = False
    if tags_addr != TAGS_ADDR:
        fail(label, "tags_addr 0x%08x != 0x%08x" % (tags_addr, TAGS_ADDR)); ok = False
    if cmdline != EXPECT_CMDLINE:
        fail(label, "cmdline %r != %r" % (cmdline, EXPECT_CMDLINE)); ok = False
    if not (KERNEL_SIZE_MIN <= kernel_size <= KERNEL_SIZE_MAX):
        fail(label, "kernel size %d outside [%d, %d]"
             % (kernel_size, KERNEL_SIZE_MIN, KERNEL_SIZE_MAX)); ok = False
    if second_size != 0:
        fail(label, "unexpected second_size %d (DTB must not be in boot.img)" % second_size); ok = False

    kstart = page_size
    kdata = blob[kstart:kstart + kernel_size]
    if len(kdata) < kernel_size:
        fail(label, "truncated kernel section"); return False
    if kdata[:4] != MTK_MAGIC or kdata[8:14] != b"KERNEL":
        fail(label, "kernel section lacks MTK KERNEL sub-header"); ok = False
    mtk_size = struct.unpack_from("<I", kdata, 4)[0]
    if mtk_size != kernel_size - 512:
        fail(label, "MTK kernel size field %d != kernel_size-512 (%d)"
             % (mtk_size, kernel_size - 512)); ok = False

    rstart = kstart + ((kernel_size + page_size - 1) // page_size) * page_size
    rdata = blob[rstart:rstart + ramdisk_size]
    if len(rdata) < ramdisk_size:
        fail(label, "truncated ramdisk section"); ok = False
    elif rdata[:4] != MTK_MAGIC or rdata[8:14] != b"ROOTFS":
        fail(label, "ramdisk section lacks MTK ROOTFS sub-header"); ok = False
    else:
        try:
            cpio = gzip.decompress(rdata[512:])
        except Exception as e:  # noqa: BLE001
            fail(label, "ramdisk not gzip: %s" % e); return False
        for entry in ("init", "fstab.mt8127", "init.environ.rc"):
            if not cpio_has_entry(cpio, entry):
                fail(label, "ramdisk missing '%s'" % entry); ok = False
    if ok:
        print("PASS boot.img %s: MTK layout, addrs, cmdline, kernel %d B, ramdisk OK"
              % (label, kernel_size))
    return ok


def check_zip_boot_gates(z, label):
    """Level B. Returns True/False."""
    ok = True
    names = set(z.namelist())

    # 1. libstlport.so present with the pinned sha
    stl_name = "system/lib/libstlport.so"
    if stl_name not in names:
        fail(label, "MISSING %s — MTK display/camera blobs cannot load" % stl_name)
        ok = False
    else:
        blob = z.read(stl_name)
        digest = hashlib.sha256(blob).hexdigest()
        if digest != STLPORT_SHA256:
            fail(label, "%s sha256 %s != pinned %s" % (stl_name, digest, STLPORT_SHA256))
            ok = False
        else:
            print("PASS %s: libstlport.so present, sha256 pinned OK" % label)

    # 2. surfaceflinger present
    if "system/bin/surfaceflinger" not in names:
        fail(label, "MISSING system/bin/surfaceflinger")
        ok = False

    # 3. updater-script: surfaceflinger must carry capabilities 0x0
    up = z.read("META-INF/com/google/android/updater-script").decode(errors="replace")
    m = re.search(r'set_metadata\("/system/bin/surfaceflinger".*?"capabilities",\s*0x([0-9a-fA-F]+)', up)
    if not m:
        fail(label, "no set_metadata for surfaceflinger in updater-script")
        ok = False
    else:
        caps = int(m.group(1), 16)
        if caps != 0:
            fail(label, "surfaceflinger capabilities 0x%x — MUST be 0x0 "
                 "(file caps make it AT_SECURE, the linker sanitizes LD_PRELOAD, "
                 "the MTK mali blobs cannot load and the tablet freezes at the "
                 "Lenovo logo; apply patches/system/core/0001)" % caps)
            ok = False
        else:
            print("PASS %s: surfaceflinger capabilities 0x0 in updater-script" % label)
    return ok


def main():
    if len(sys.argv) < 2:
        print("usage: check_bootimg_kernel.py FILE [FILE...]", file=sys.stderr)
        return 2
    all_ok = True
    for path in sys.argv[1:]:
        try:
            boot, z, label = load(path)
        except Exception as e:  # noqa: BLE001
            print("FAIL %s: %s" % (path, e))
            all_ok = False
            continue
        if not check_boot_image(boot, label):
            all_ok = False
        if z is not None and not check_zip_boot_gates(z, label):
            all_ok = False
    if not all_ok:
        print("BOOT GATE: FAILED — this ROM must NOT ship")
        return 1
    print("BOOT GATE: PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
