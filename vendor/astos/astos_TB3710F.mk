#
# AstOs — a warm, voice-first home assistant ROM
# for the Lenovo Tab 3 Essential (TB3-710F)
#
# Base: LineageOS 14.1 (Android 7.1.2) via the community device tree.
# This product inherits everything from the device config and layers the
# AstOs identity on top: boot animation, wallpaper, zram, AstShell.
#

$(call inherit-product, device/lenovo/TB3710F/lineage.mk)

# Single-home kiosk: drop Trebuchet so AstShell is the ONLY home app —
# no home chooser ever appears, the tablet boots straight into AstOs.
PRODUCT_PACKAGES := $(filter-out Trebuchet,$(PRODUCT_PACKAGES))

# --- AstOs identity ---------------------------------------------------------

# Warm boot animation (1024x600, espresso -> terracotta bloom)
TARGET_BOOTANIMATION := vendor/astos/bootanimation/bootanimation.zip

# Static resource overlay: default wallpaper (warm cream)
DEVICE_PACKAGE_OVERLAYS := vendor/astos/overlay $(DEVICE_PACKAGE_OVERLAYS)

# zram swap init script (kernel has CONFIG_ZRAM=y, huge win on 1 GB RAM)
PRODUCT_COPY_FILES += \
    vendor/astos/configs/astos.rc:system/etc/init/astos.rc \
    vendor/astos/configs/default-permissions-astos.xml:system/etc/default-permissions/astos.xml

# The AstShell home-assistant dashboard and the AstSetup first-boot
# wizard, both installed as privileged system apps (re-signed with the
# platform key at build time)
PRODUCT_PACKAGES += \
    AstShell \
    AstSetup

# --- Behaviour tuning --------------------------------------------------------

PRODUCT_PROPERTY_OVERRIDES += \
    ro.astos.version=3.3.0 \
    ro.astos.device=TB3-710F \
    ro.config.low_ram=true

# --- Product identity ---------------------------------------------------------

PRODUCT_NAME := astos_TB3710F
PRODUCT_DEVICE := TB3710F
