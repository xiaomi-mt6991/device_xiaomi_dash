# Copyright 2026 The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
#
# Source-built (Kleaf/Bazel) kernel configuration for dash.
#
# The Android build does NOT build the kernel: build it manually first with
# the bazel invocation documented in kernel/README.md, then every artifact
# below is consumed from the bazel-bin output tree.

# Bazel output of //kernel_device_modules-6.6 (relative to the Android root).
DASH_KLEAF_OUT := kernel/xiaomi/dash/bazel-bin/kernel_device_modules-6.6

# Stock prebuilt kernel tree (device/xiaomi/dash-kernel @ cnb). Only the
# modules the Kleaf configuration does not build come from here - see the
# fallback paths in kernel/modules.mk.
KERNEL_PATH := $(DEVICE_PATH)-kernel

# Tell the build system where the kernel lives; TARGET_FORCE_PREBUILT_KERNEL
# keeps it from trying to build it itself.
TARGET_NO_KERNEL := false
TARGET_KERNEL_SOURCE := kernel/xiaomi/dash/kernel-6.6
TARGET_KERNEL_CONFIG := gki_defconfig
TARGET_KERNEL_VERSION := 6.6
TARGET_FORCE_PREBUILT_KERNEL := true

# Kernel image, exported UAPI headers and dtb come straight from the Kleaf
# output. The dtb directory is the source-built mediatek/ tree (dash.dtb);
# it replaces the stock 01_dtbdump_MT6991.dtb.
TARGET_PREBUILT_KERNEL := $(DASH_KLEAF_OUT)/mgk_64_k66_kernel_aarch64.user/Image.lz4
TARGET_PREBUILT_KERNEL_HEADERS := $(DASH_KLEAF_OUT)/mgk_64_k66_merged_uapi_headers.user/kernel-uapi-headers.tar.gz
BOARD_PREBUILT_DTBIMAGE_DIR := $(DASH_KLEAF_OUT)/mgk_64_k66.user/mediatek

ifeq ($(wildcard $(TARGET_PREBUILT_KERNEL)),)
$(warning Kleaf kernel image not found at $(TARGET_PREBUILT_KERNEL) - run the bazel build first, see device/xiaomi/dash/kernel/README.md)
endif

# Partition module sets and load order (generated from the stock-derived
# module lists; source-built paths + one prebuilt fallback).
include $(DEVICE_PATH)/kernel/modules.mk
