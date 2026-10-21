#!/bin/bash
# Build script for Xiaomi dash (Redmi Turbo 5 Max) with Kleaf kernel
# Clones all repos directly - no repo/AOSP manifest needed

set -euo pipefail

BUILD_DIR="/serverhive/therealmharc1/trm"
KERNEL_DIR="$BUILD_DIR/kernel/xiaomi/dash"

echo "=== Setting up build environment ==="
cd "$BUILD_DIR"

# Install bazelisk if not present
if ! command -v bazelisk &> /dev/null; then
    echo "Installing bazelisk..."
    curl -L -o bazelisk https://github.com/bazelbuild/bazelisk/releases/download/v1.25.0/bazelisk-linux-amd64
    chmod +x bazelisk
fi

echo "=== Cloning repos ==="

# Clone kernel repos
mkdir -p kernel/xiaomi/dash
cd kernel/xiaomi/dash

repos=(
    "kernel-6.6:https://github.com/Content-Creation-Club/android_kernel_xiaomi_dash:dash-w-oss"
    "kernel_device_modules-6.6:https://github.com/Content-Creation-Club/android_kernel_xiaomi_dash_device_modules:dash-w-oss"
    "build/kernel:https://github.com/Content-Creation-Club/android_kernel_xiaomi_dash_build:dash-w-oss"
    "build/bazel_mgk_rules:https://github.com/Content-Creation-Club/android_kernel_xiaomi_dash_bazel_mgk_rules:lineage-23.2"
    "vendor/mediatek/kernel_modules:https://github.com/Content-Creation-Club/android_kernel_xiaomi_dash_mtk_modules:dash-w-oss"
)

for repo in "${repos[@]}"; do
    IFS=':' read -r path url branch <<< "$repo"
    if [ ! -d "$path/.git" ]; then
        echo "Cloning $path..."
        git clone --depth=1 --branch "$branch" "$url" "$path"
    else
        echo "$path already exists, skipping"
    fi
done

# Clone prebuilts
cd "$KERNEL_DIR"

prebuilts=(
    "prebuilts/bazel/linux-x86_64:https://android.googlesource.com/platform/prebuilts/bazel/linux-x86_64:main"
    "prebuilts/kernel-build-tools:https://android.googlesource.com/kernel/prebuilts/build-tools:main-kernel"
)

for repo in "${prebuilts[@]}"; do
    IFS=':' read -r path url branch <<< "$repo"
    if [ ! -d "$path/.git" ]; then
        echo "Cloning $path..."
        git clone --depth=1 --branch "$branch" "$url" "$path"
    fi
done

# Clone external deps
cd "$KERNEL_DIR"

externals=(
    "external/bazel-skylib:https://github.com/bazelbuild/bazel-skylib:main"
    "external/bazelbuild-rules_license:https://github.com/bazelbuild/rules_license:main"
    "external/bazelbuild-rules_pkg:https://github.com/bazelbuild/rules_pkg:main"
    "external/bazelbuild-rules_rust:https://github.com/bazelbuild/rules_rust:main"
    "external/bazelbuild-rules_python:https://github.com/bazelbuild/rules_python:main"
    "external/bazelbuild-rules_java:https://github.com/bazelbuild/rules_java:main"
    "external/bazelbuild-rules_cc:https://github.com/bazelbuild/rules_cc:main"
    "external/bazelbuild-rules_android:https://github.com/bazelbuild/rules_android:main"
    "external/bazelbuild-rules_go:https://github.com/bazelbuild/rules_go:main"
    "external/bazelbuild-rules_testing:https://github.com/bazelbuild/rules_testing:main"
    "external/stardoc:https://github.com/bazelbuild/stardoc:main"
    "external/bazelbuild-platforms:https://github.com/bazelbuild/platforms:main"
)

for repo in "${externals[@]}"; do
    IFS=':' read -r path url branch <<< "$repo"
    if [ ! -d "$path/.git" ]; then
        echo "Cloning $path..."
        git clone --depth=1 --branch "$branch" "$url" "$path"
    fi
done

# Setup symlinks
echo "Setting up symlinks..."
cd "$KERNEL_DIR"
ln -sf build/bazel_mgk_rules/kleaf/bazel.WORKSPACE WORKSPACE
ln -sf build/bazel_mgk_rules/kleaf/common.bazelrc .bazelrc

# Install bazelisk if needed
if ! command -v bazelisk &> /dev/null; then
    echo "Installing bazelisk..."
    curl -L -o bazelisk https://github.com/bazelbuild/bazelisk/releases/download/v1.25.0/bazelisk-linux-amd64
    chmod +x bazelisk
fi

# Build kernel
echo "Building kernel..."
./bazelisk build --enable_workspace --enable_bzlmod=false \
  --//build/bazel_mgk_rules:kernel_version=6.6 \
  //kernel_device_modules-6.6:mgk_64_k66_kernel_aarch64.user \
  //kernel_device_modules-6.6:mgk_64_k66.user \
  //kernel_device_modules-6.6:mgk_64_k66_customer_modules_install.user \
  //kernel_device_modules-6.6:mgk_64_k66_merged_uapi_headers.user

echo "=== Kernel build complete ==="
echo "Artifacts in: bazel-bin/kernel_device_modules-6.6/"
ls -lh bazel-bin/kernel_device_modules-6.6/mgk_64_k66_kernel_aarch64.user/Image.lz4
ls -lh bazel-bin/kernel_device_modules-6.6/mgk_64_k66_merged_uapi_headers.user/kernel-uapi-headers.tar.gz
ls -lh bazel-bin/kernel_device_modules-6.6/mgk_64_k66.user/mediatek/dash.dtb