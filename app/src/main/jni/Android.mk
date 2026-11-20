# SPDX-License-Identifier: GPL-3.0-or-later
LOCAL_PATH := $(call my-dir)
PREBUILT := $(LOCAL_PATH)/../../../prebuilt/$(TARGET_ARCH_ABI)

ifeq ($(wildcard $(PREBUILT)/lib/libmpv.so),)
$(error Missing $(PREBUILT)/lib/libmpv.so - run tools/build-libmpv.sh and tools/import-libmpv.sh)
endif

# Prebuilt LGPL libraries (libmpv + FFmpeg), packaged into the APK.
SV_PREBUILT_LIBS := mpv avcodec avformat avutil avfilter swresample swscale
ifneq ($(wildcard $(PREBUILT)/lib/libavdevice.so),)
SV_PREBUILT_LIBS += avdevice
endif

define sv_add_prebuilt
include $$(CLEAR_VARS)
LOCAL_MODULE := $(1)
LOCAL_SRC_FILES := $$(PREBUILT)/lib/lib$(1).so
include $$(PREBUILT_SHARED_LIBRARY)
endef
$(foreach lib,$(SV_PREBUILT_LIBS),$(eval $(call sv_add_prebuilt,$(lib))))

include $(CLEAR_VARS)
LOCAL_MODULE := sideview
LOCAL_SRC_FILES := mpv_jni.cpp
LOCAL_C_INCLUDES := $(PREBUILT)/include
LOCAL_CPPFLAGS := -std=c++17 -Wall
LOCAL_SHARED_LIBRARIES := $(SV_PREBUILT_LIBS)
LOCAL_LDLIBS := -llog -landroid
include $(BUILD_SHARED_LIBRARY)
