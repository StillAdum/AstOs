LOCAL_PATH := $(call my-dir)

# AstSetup — the AstOs first-boot setup wizard.
# Built by the Gradle app build in the workflow, re-signed here with the
# ROM's platform key (same treatment as AstShell) so it can write
# device_provisioned / user_setup_complete and the shared Settings.Global
# values on completion. Privileged system app.
include $(CLEAR_VARS)
LOCAL_MODULE := AstSetup
LOCAL_MODULE_CLASS := APPS
LOCAL_MODULE_TAGS := optional
LOCAL_SRC_FILES := AstSetup.apk
LOCAL_MODULE_SUFFIX := $(COMMON_ANDROID_PACKAGE_SUFFIX)
LOCAL_PRIVILEGED_MODULE := true
LOCAL_CERTIFICATE := platform
include $(BUILD_PREBUILT)
