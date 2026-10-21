#!/bin/bash
# Build script for Xiaomi dash source-built kernel (Kleaf/Bazel)
# Run from ANDROID_ROOT (top of source tree)
#
# Usage:
#   ./device/xiaomi/dash/build_kernel.sh           # build kernel only
#   ./device/xiaomi/dash/build_kernel.sh all       # build kernel + Android

set -euo pipefail

ANDROID_ROOT="${ANDROID_ROOT:-$(pwd)}"
DEVICE_DIR="${ANDROID_ROOT}/device/xiaomi/dash"
KERNEL_DIR="${ANDROID_ROOT}/kernel/xiaomi/dash"

# Find bazel: prefer system bazel (7+), fallback to prebuilt, fallback to bazelisk
find_bazel() {
    # 1. Check for system bazel 7+
    if command -v bazel >/dev/null 2>&1; then
        local ver=$(bazel --version 2>/dev/null | grep -oE '[0-9]+' | head -1)
        if [[ -n "$ver" && "$ver" -ge 7 ]]; then
            echo "bazel"
            return 0
        fi
    fi

    # 2. Check prebuilt bazel
    local prebuilt="${ANDROID_ROOT}/prebuilts/bazel/linux-x86_64/bazel"
    if [[ -x "$prebuilt" ]]; then
        echo "$prebuilt"
        return 0
    fi

    # 3. Check tools/bazel wrapper
    local wrapper="${ANDROID_ROOT}/tools/bazel"
    if [[ -x "$wrapper" ]]; then
        echo "$wrapper"
        return 0
    fi

    # 4. Try bazelisk
    if command -v bazelisk >/dev/null 2>&1; then
        echo "bazelisk"
        return 0
    fi

    return 1
}

BAZEL=$(find_bazel) || {
    echo "ERROR: No bazel 7+ found. Install with:"
    echo "  sudo apt install bazel        # Ubuntu/Debian"
    echo "  or: npm install -g @bazel/bazelisk"
    exit 1
}
echo "Using bazel: $BAZEL"

# Required env for Kleaf
export KERNEL_VERSION=kernel-6.6
export DEFCONFIG_OVERLAYS=dash
# export SOURCE_DATE_EPOCH=$(date +%s)  # uncomment for reproducible builds

KERNEL_TARGETS=(
    "//kernel_device_modules-6.6:mgk_64_k66_kernel_aarch64.user"
    "//kernel_device_modules-6.6:mgk_64_k66.user"
    "//kernel_device_modules-6.6:mgk_64_k66_customer_modules_install.user"
    "//kernel_device_modules-6.6:mgk_64_k66_merged_uapi_headers.user"
)

# Use --noenable_bzlmod only if bazel supports it (7+)
BAZEL_FLAGS=(
    --noenable_bzlmod
    --//build/bazel_mgk_rules:kernel_version=6.6
)

usage() {
    cat <<EOF
Usage: $0 [kernel|all|clean|check]

  kernel   Build kernel only (default)
  all      Build kernel, then Android (m lineage_dash)
  clean    Clean bazel output (bazel clean --expunge)
  check    Verify kernel artifacts exist

Environment:
  ANDROID_ROOT   Android source root (default: pwd)
  BAZEL_JOBS     Parallel jobs for bazel (default: nproc)
EOF
}

build_kernel() {
    echo "=== Building kernel in ${KERNEL_DIR} ==="

    cd "${KERNEL_DIR}"

    # Check for workspace symlinks
    for link in WORKSPACE .bazelrc; do
        if [[ ! -e "${link}" ]]; then
            echo "WARNING: ${KERNEL_DIR}/${link} missing - check local_manifest.xml linkfiles"
        fi
    done

    local jobs="${BAZEL_JOBS:-$(nproc)}"
    "${BAZEL}" "${BAZEL_FLAGS[@]}" --jobs="${jobs}" build "${KERNEL_TARGETS[@]}"
}

build_android() {
    echo "=== Building Android (lineage_dash) ==="
    cd "${ANDROID_ROOT}"
    source build/envsetup.sh
    m lineage_dash
}

check_artifacts() {
    echo "=== Checking kernel artifacts ==="
    local out="${KERNEL_DIR}/bazel-bin/kernel_device_modules-6.6"
    local artifacts=(
        "mgk_64_k66_kernel_aarch64.user/Image.lz4"
        "mgk_64_k66_merged_uapi_headers.user/kernel-uapi-headers.tar.gz"
        "mgk_64_k66.user/mediatek/dash.dtb"
    )
    local all_ok=true
    for a in "${artifacts[@]}"; do
        if [[ -f "${out}/${a}" ]]; then
            echo "  OK  ${a}"
        else
            echo "  MISSING ${a}"
            all_ok=false
        fi
    done
    ${all_ok} && echo "All artifacts present" || echo "Some artifacts missing - run 'kernel' first"
}

clean_bazel() {
    echo "=== Cleaning bazel ==="
    cd "${KERNEL_DIR}"
    "${BAZEL}" clean --expunge
}

cmd="${1:-kernel}"
case "${cmd}" in
    kernel) build_kernel ;;
    all) build_kernel && build_android ;;
    check) check_artifacts ;;
    clean) clean_bazel ;;
    -h|--help|help) usage ;;
    *) echo "Unknown command: ${cmd}"; usage; exit 1 ;;
esac