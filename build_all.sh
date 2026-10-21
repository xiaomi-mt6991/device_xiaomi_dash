#!/bin/bash
# Build script for Xiaomi dash (Redmi Turbo 5 Max) with Kleaf kernel
# Run this on your build server - handles everything automatically

set -euo pipefail

BUILD_DIR="/serverhive/therealmharc1/trm"
MANIFEST_URL="https://github.com/Evolution-X/manifest"
MANIFEST_BRANCH="cnb"
LOCAL_MANIFEST_URL="https://raw.githubusercontent.com/xiaomi-mt6991/local_manifests/bka-prebuilt/local_manifest.xml"

echo "=== Setting up build environment ==="
cd "$BUILD_DIR"

# Clean previous repo state
echo "Cleaning previous repo state..."
rm -rf .repo

# Initialize repo
echo "Initializing repo..."
repo init -u https://github.com/Evolution-X/manifest -b cnb --git-lfs

# Add local manifest
echo "Adding local manifest..."
mkdir -p .repo/local_manifests
curl -sSL -o .repo/local_manifests/local_manifest.xml https://raw.githubusercontent.com/xiaomi-mt6991/local_manifests/bka-prebuilt/local_manifest.xml

# Sync everything
echo "Syncing sources (this takes a while)..."
repo sync --force-sync -j8

# Install bazelisk if not present
if ! command -v bazelisk &> /dev/null; then
    echo "Installing bazelisk..."
    curl -L -o bazelisk https://github.com/bazelbuild/bazelisk/releases/download/v1.25.0/bazelisk-linux-amd64
    chmod +x bazelisk
fi

# Build kernel
echo "Building kernel..."
cd kernel/xiaomi/dash
../../bazelisk build --enable_workspace --enable_bzlmod=false \
  --//build/bazel_mgk_rules:kernel_version=6.6 \
  //kernel_device_modules-6.6:mgk_64_k66_kernel_aarch64.user \
  //kernel_device_modules-6.6:mgk_64_k66.user \
  //kernel_device_modules-6.6:mgk_64_k66_customer_modules_install.user \
  //kernel_device_modules-6.6:mgk_64_k66_merged_uapi_headers.user

echo "=== Kernel build complete ==="
echo "Artifacts in: kernel/xiaomi/dash/bazel-bin/kernel_device_modules-6.6/"
ls -lh bazel-bin/kernel_device_modules-6.6/mgk_64_k66_kernel_aarch64.user/Image.lz4
ls -lh bazel-bin/kernel_device_modules-6.6/mgk_64_k66_merged_uapi_headers.user/kernel-uapi-headers.tar.gz
ls -lh bazel-bin/kernel_device_modules-6.6/mgk_64_k66.user/mediatek/dash.dtb