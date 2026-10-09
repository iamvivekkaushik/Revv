LOCAL_PATH := $(call my-dir)

# The read-only probe of a legacy Wi-Fi driver's hotspot channel (Wireless Extensions). CarPlay's
# module builds the same probe under its own name; two libraries of one name cannot share an APK.
include $(CLEAR_VARS)
LOCAL_MODULE := aa_hotspot_radio
LOCAL_SRC_FILES := local_hotspot_radio.c
LOCAL_CFLAGS := -Wall -Wextra -Werror
include $(BUILD_SHARED_LIBRARY)
