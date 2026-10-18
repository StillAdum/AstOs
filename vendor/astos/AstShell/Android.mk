LOCAL_PATH := $(call my-dir)

# AstShell — the AstOs home assistant shell.
# The unsigned release APK is produced by the Gradle build in the
# workflow and copied here before the ROM build. It is re-signed with
# the ROM's platform key (LOCAL_CERTIFICATE := PLATFORM) so it can hold
# signature-level permissions (WRITE_SECURE_SETTINGS, MEDIA_CONTENT_CONTROL)
# as a privileged system app.
include $(CLEAR_VARS)
LOCAL_MODULE := AstShell
LOCAL_MODULE_CLASS := APPS
LOCAL_MODULE_TAGS := optional
LOCAL_SRC_FILES := AstShell.apk
LOCAL_MODULE_SUFFIX := $(COMMON_ANDROID_PACKAGE_SUFFIX)
LOCAL_PRIVILEGED_MODULE := true
LOCAL_CERTIFICATE := PLATFORM
include $(BUILD_PREBUILT)
