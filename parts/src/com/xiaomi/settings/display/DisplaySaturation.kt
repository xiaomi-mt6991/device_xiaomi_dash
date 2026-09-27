/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.display

import android.content.Context
import android.hardware.display.ColorDisplayManager
import android.util.Log
import androidx.preference.PreferenceManager

/**
 * Display saturation slider backend (testers asked for it; the HAL has
 * no saturation knob and Evo ships no LiveDisplay UI).
 *
 * Uses the official global path: ColorDisplayManager.setSaturationLevel
 * (0-100, 100 = full) drives SurfaceFlinger's saturation transform
 * LIVE via the color_display service — no reboot, no root. Needs the
 * CONTROL_DISPLAY_COLOR_TRANSFORMS permission (platform-signed +
 * priv-app, both true for this app).
 *
 * Range is 0-100 despite the earlier 0-150 attempt: the service's
 * GlobalSaturationTintController.setMatrix clamps anything above 100
 * to 100 and then installs the *identity* matrix, so 101-150 was a
 * silent no-op (the device's own `cmd color_display set-saturation`
 * enforces 0-100 as well). A boost past stock is only possible via a
 * color-mode change, not this transform.
 */
object DisplaySaturation {

    private const val TAG = "DisplaySaturation"

    const val KEY = "display_saturation"
    const val DEFAULT = 100

    fun get(context: Context): Int =
        PreferenceManager.getDefaultSharedPreferences(context).getInt(KEY, DEFAULT)

    fun apply(context: Context, level: Int = get(context)): Boolean =
        runCatching {
            val manager = context.getSystemService(ColorDisplayManager::class.java)
                ?: throw IllegalStateException("color_display service not published yet")
            manager.setSaturationLevel(level.coerceIn(0, 100))
        }.onFailure { e -> Log.w(TAG, "setSaturationLevel failed", e) }.isSuccess
}
