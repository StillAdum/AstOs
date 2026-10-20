#!/usr/bin/env python3
"""AstOs kernel identity gate — hard CI check for TB3-710F boot images.

Why this exists: the MT8127 kernel compiled from andrew264's source tree
builds fine but does not boot on real TB3-710F hardware — the tablet freezes
at the static Lenovo logo (kernel dies before userspace). The working
LineageOS 14.1 build for this tablet (aptivi-mobile, 2023-03-22) ships a
kernel DUMPED FROM A REAL TB3-710F instead (commit 7b762eb91, "Added zImage
dumped from TB3-710F"). AstOs pins the exact same binary, and this gate makes
it impossible to ship a ROM whose boot.img carries any other kernel.

MTK boot image layout produced by device/lenovo/TB3710F/mkbootimg.mk
(make_header prepends 512-byte MTK headers to kernel and ramdisk):

  [2048B ANDROID! header]
  [ 512B MTK KERNEL header][raw zImage]          <- kernel_size includes header
  [ 512B MTK ROOTFS header][gzip ramdisk]        <- starts on page boundary

Usage:
  check_bootimg_kernel.py FILE [FILE...]

Each FILE is either a raw boot.img or an OTA zip containing boot.img.
Exit 0 = every image carries the proven kernel; exit 1 = gate failure.
"""
import hashlib
import struct
import sys
import zipfile

# Raw zImage dumped from a real TB3-710F (aptivi-mobile 7b762eb91).
# sha256 of the file WITHOUT the 512-byte MTK KERNEL header.
PROVEN_RAW_SHA256 = "f862c0417d0aa984b74b1794b56f95c304a12a747097d74e7f44ea726a6c0450"
PROVEN_RAW_SIZE = 3773648

# The same dump WITH its 512-byte MTK KERNEL header, as committed upstream.
APTIVI_ZIMAGE_SHA256 = "10773efaa0bd51b9de663ca99253524815a193df0c127a46c61975b78897ccb3"

MTK_MAGIC = bytes.fromhex("88168858")
PAGE_SIZE = 2048
ANDROID_MAGIC = b"ANDROID!"


def load_boot_image(path):
    """Return (boot_img_bytes, human_label) from a boot.img or an OTA zip."""
    with open(path, "rb") as f:
        head = f.read(4)
    if head == b"PK\x03\x04":
        with zipfile.ZipFile(path) as z:
            candidates = [n for n in z.namelist()
                         if n == "boot.img" or n.endswith("/boot.img")]
            if not candidates:
                raise RuntimeError("%s: zip contains no boot.img" % path)
            candidates.sort()  # prefer top-level "boot.img"
            return z.read(candidates[0]), "%s:%s" % (path, candidates[0])
    with open(path, "rb") as f:
        return f.read(), path


def check_image(blob, label):
    """Verify one boot image; print details; return True/False."""
    if blob[:8] != ANDROID_MAGIC:
        print("FAIL %s: bad ANDROID magic: %r" % (label, blob[:8]))
        return False
    kernel_size, ramdisk_size, second_size = struct.unpack_from("<III", blob, 8)
    page_size = struct.unpack_from("<I", blob, 36)[0]
    if page_size != PAGE_SIZE:
        print("FAIL %s: unexpected page size %d" % (label, page_size))
        return False
    if kernel_size <= 512:
        print("FAIL %s: kernel_size %d too small" % (label, kernel_size))
        return False
    if ramdisk_size <= 512:
        print("FAIL %s: ramdisk_size %d too small" % (label, ramdisk_size))
        return False

    kstart = page_size
    if kstart + kernel_size > len(blob):
        print("FAIL %s: truncated kernel section" % label)
        return False
    kdata = blob[kstart:kstart + kernel_size]

    # MTK KERNEL sub-header
    if kdata[:4] != MTK_MAGIC:
        print("FAIL %s: kernel section lacks MTK magic" % label)
        return False
    if kdata[8:14] != b"KERNEL":
        print("FAIL %s: MTK kernel tag is %r, expected b'KERNEL'" % (label, kdata[8:14]))
        return False
    mtk_size = struct.unpack_from("<I", kdata, 4)[0]
    if mtk_size != kernel_size - 512:
        print("FAIL %s: MTK kernel size field %d != kernel_size-512 (%d)"
              % (label, mtk_size, kernel_size - 512))
        return False

    raw = kdata[512:512 + mtk_size]
    digest = hashlib.sha256(raw).hexdigest()
    ok_size = (len(raw) == PROVEN_RAW_SIZE)
    ok_sha = (digest == PROVEN_RAW_SHA256)

    # Ramdisk sanity: MTK ROOTFS header on the next page boundary.
    rstart = kstart + ((kernel_size + page_size - 1) // page_size) * page_size
    rdata = blob[rstart:rstart + ramdisk_size]
    ramdisk_ok = (rdata[:4] == MTK_MAGIC and rdata[8:14] == b"ROOTFS")

    if ok_sha and ok_size and ramdisk_ok:
        print("PASS %s: kernel size=%d sha256=%s ramdisk=MTK(ROOTFS) OK"
              % (label, len(raw), digest))
        return True

    print("FAIL %s:" % label)
    if not ok_size:
        print("  raw kernel size %d != proven %d" % (len(raw), PROVEN_RAW_SIZE))
    if not ok_sha:
        print("  kernel sha256  %s" % digest)
        print("  proven sha256 %s" % PROVEN_RAW_SHA256)
    if not ramdisk_ok:
        print("  ramdisk section lacks MTK ROOTFS header (got %r)" % rdata[:14])
    return False


def main():
    if len(sys.argv) < 2:
        print("usage: check_bootimg_kernel.py FILE [FILE...]", file=sys.stderr)
        return 2
    all_ok = True
    for path in sys.argv[1:]:
        try:
            blob, label = load_boot_image(path)
        except Exception as e:  # noqa: BLE001 — gate must fail loudly
            print("FAIL %s: %s" % (path, e))
            all_ok = False
            continue
        if not check_image(blob, label):
            all_ok = False
    if not all_ok:
        print("KERNEL IDENTITY GATE: FAILED — refusing to ship a non-proven kernel")
        return 1
    print("KERNEL IDENTITY GATE: PASS — boot image(s) carry the proven TB3-710F kernel")
    return 0


if __name__ == "__main__":
    sys.exit(main())
