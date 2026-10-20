# Kernel build for Xiaomi dash (Redmi Turbo 5 Max)

This directory contains the configuration to consume a **source-built kernel**
(built with Kleaf/Bazel) in the Android device tree. The Android build itself
does **not** build the kernel; you must build it manually first, then the
prebuilt artifacts are consumed from `bazel-bin/`.

## Repository layout (after `repo sync` with the provided local_manifest.xml)

```
<android-root>/
├── kernel/xiaomi/dash/                # Kleaf workspace root
│   ├── kernel-6.6/                    ← Content-Creation-Club/android_kernel_xiaomi_dash (dash-w-oss)
│   ├── kernel_device_modules-6.6/     ← Content-Creation-Club/android_kernel_xiaomi_dash_device_modules (dash-w-oss)
│   ├── build/kernel/                  ← Content-Creation-Club/android_kernel_xiaomi_dash_build (dash-w-oss)
│   ├── build/bazel_mgk_rules/         ← Content-Creation-Club/android_kernel_xiaomi_dash_bazel_mgk_rules (lineage-23.2)
│   ├── vendor/mediatek/kernel_modules/← Content-Creation-Club/android_kernel_xiaomi_dash_mtk_modules (dash-w-oss)
│   ├── WORKSPACE         → build/bazel_mgk_rules/kleaf/bazel.WORKSPACE (symlink)
│   └── .bazelrc          → build/kernel/kleaf/common.bazelrc          (symlink)
├── device/xiaomi/dash/                # This device tree
├── device/xiaomi/dash-kernel/         # Prebuilt kernel modules (fallback)
└── toolchain/pgo-profiles/            # From LineageOS manifest (groups=pdk)
```

## Required environment

```bash
export KERNEL_VERSION=kernel-6.6
export DEFCONFIG_OVERLAYS=dash
# Optional: reproducible build
export SOURCE_DATE_EPOCH=$(date +%s)
```

The `DEFCONFIG_OVERLAYS=dash` switch selects the dash-specific module list in
`kernel_device_modules-6.6/kernel/kleaf/mgk_64.bzl` — this matches the stock
module sets your prebuilt kernel shipped with.

## Build command (run from the Android root)

```bash
cd kernel/xiaomi/dash
../../../tools/bazel \
  --noenable_bzlmod \
  --//build/bazel_mgk_rules:kernel_version=6.6 \
  build \
  //kernel_device_modules-6.6:mgk_64_k66_kernel_aarch64.user \
  //kernel_device_modules-6.6:mgk_64_k66.user \
  //kernel_device_modules-6.6:mgk_64_k66_customer_modules_install.user \
  //kernel_device_modules-6.6:mgk_64_k66_merged_uapi_headers.user
```

> **Note**: The `--noenable_bzlmod` flag is required because the mgk rules still
> use legacy `WORKSPACE`. The `--//build/bazel_mgk_rules:kernel_version=6.6`
> overrides the mgk default (`6.1`).

### Expected outputs

After a successful build, the following artifacts exist under
`kernel/xiaomi/dash/bazel-bin/kernel_device_modules-6.6/` (this is
`DASH_KLEAF_OUT` in `BoardConfigKernel.mk`):

| Artifact | Path |
|---|---|
| Kernel image (lz4) | `mgk_64_k66_kernel_aarch64.user/Image.lz4` |
| Merged UAPI headers | `mgk_64_k66_merged_uapi_headers.user/kernel-uapi-headers.tar.gz` |
| DTB (mediatek/dash.dtb) | `mgk_64_k66.user/mediatek/dash.dtb` |
| Customer modules (flat) | `mgk_64_k66_customer_modules_install.user/*.ko` |
| GKI/common/ext modules (nested) | `mgk_64_k66.user/<source-relative-path>/*.ko` |

The `BOARD_PREBUILT_DTBIMAGE_DIR` points to `mgk_64_k66.user/mediatek/`
(single file `dash.dtb`), which **replaces** the stock `01_dtbdump_MT6991.dtb`
from the prebuilt kernel tree.

## Module partition split

The generated `kernel/modules.mk` defines four module sets that exactly match
the stock-derived partition layout your device tree shipped with
(prebuilt `device/xiaomi/dash-kernel` @ cnb):

- `BOARD_SYSTEM_KERNEL_MODULES` / `_LOAD` — 77 modules for `system_dlkm`
- `BOARD_VENDOR_KERNEL_MODULES` / `_LOAD` — 235 load-order modules, 257 installed
- `BOARD_VENDOR_RAMDISK_KERNEL_MODULES` / `_LOAD` — 237 load-order, 261 installed
- `BOARD_VENDOR_RAMDISK_RECOVERY_KERNEL_MODULES_LOAD` — 252 modules (recovery subset)

**Load lists preserve exact stock order (including duplicates).** Install sets
are de-duplicated: load-order entries first, then any extra `.ko` files present
in the partition directory.

### Source-built vs. prebuilt fallback

Almost every module comes from the Kleaf output. **One exception**:

- `dio_dma_mapper.ko` (vendor_dlkm) — declared only for `goya`/`klee` overlays
  in `mgk_64.bzl`, not for `dash`. The stock prebuilt `.ko` from
  `device/xiaomi/dash-kernel/modules/vendor_dlkm/` is used instead.

### Modules explicitly **dropped** (not in any install/load list)

The `dash.config` fragment (`kernel_device_modules-6.6/kernel/configs/dash.config`)
disables these configs, so the Kleaf build produces no `.ko` for them. Their
stock copies (vermagic `6.6.118-android15-8-g…-4k`) would be rejected by the
source-built kernel (`6.6.144`, `CONFIG_LOCALVERSION="-4k-like-the-wind-reborn"`)
anyway:

```
CONFIG_XIAOMI_IO_MONITOR is not set
CONFIG_XIAOMI_LB is not set
CONFIG_XIAOMI_METIS is not set
CONFIG_XIAOMI_MI_SCHEDULE is not set
CONFIG_XIAOMI_XR_QI is not set
CONFIG_XIAOMI_XR_QS is not set
# CONFIG_TLS is not set (tls.ko in system_dlkm)
```

If you need these, enable the configs in `dash.config` **and** declare their
outputs in `mgk_64.bzl` / `modules.bzl` so they build from source with matching
vermagic. Do not rely on the prebuilt copies.

## AutoFDO (PGO) profile

The `mgk_64_k66` target in `device_modules/BUILD.bazel` specifies:

```python
clang_autofdo_profile = "@dash_kernel_profiles//:aarch64/android15-6.6/kernel.afdo"
```

This is provided by the `toolchain/pgo-profiles/kernel` repo from the LineageOS
manifest (`groups=pdk`, path `toolchain/pgo-profiles`). Ensure it is present in
your source tree (synced by the platform manifest) or the build will fail with
a missing repository error.

## Verification after build

Before running the Android build, confirm the artifacts exist:

```bash
ls -lh kernel/xiaomi/dash/bazel-bin/kernel_device_modules-6.6/mgk_64_k66_kernel_aarch64.user/Image.lz4
ls -lh kernel/xiaomi/dash/bazel-bin/kernel_device_modules-6.6/mgk_64_k66_merged_uapi_headers.user/kernel-uapi-headers.tar.gz
ls -lh kernel/xiaomi/dash/bazel-bin/kernel_device_modules-6.6/mgk_64_k66.user/mediatek/dash.dtb
```

The Android build (`BoardConfigKernel.mk`) will emit a **warning** (not error)
if `Image.lz4` is missing, to help diagnose a forgotten kernel build.

## Updating module lists

If the stock prebuilt module set changes (e.g. new OTA), regenerate:

```bash
cd /path/to/dash-adapt
bash generate_modules_mk.sh
```

The script reads the stock `modules.load*` from `device/xiaomi/dash-kernel` and
resolves every name to a Kleaf output path using the same resolution maps
(Yorokobi-verified, GKI nested, dash-overlay customer, source-presence).

## References

- YorokobiMaster's working tree (source-built): `https://github.com/YorokobiMaster/android_device_xiaomi_dash`
- Kleaf documentation: `build/kernel/kleaf/docs/impl.md`
- MGK rules: `https://github.com/Content-Creation-Club/android_kernel_xiaomi_dash_bazel_mgk_rules`