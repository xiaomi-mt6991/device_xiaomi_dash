/*
 * SPDX-FileCopyrightText: 2023-2025 Paranoid Android
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.esimswitcher

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

class BootCompletedReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "EsimSwitcher"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Received boot completed intent: ${intent.action}")
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            return
        }
        // Internal resource may be missing on some framework builds.
        // On failure keep the current component state instead of guessing,
        // so a non-eUICC device never gets a working modem toggle and a
        // real eSIM device never loses it.
        val slots =
            runCatching {
                context.resources.getIntArray(
                    com.android.internal.R.array.non_removable_euicc_slots,
                )
            }.onFailure { e ->
                Log.w(TAG, "non_removable_euicc_slots unavailable, keeping state", e)
                return
            }.getOrNull() ?: return
        val hasNonRemovableEuicc = slots.isNotEmpty()
        Log.i(TAG, "eSIM supported: $hasNonRemovableEuicc")

        setComponentEnabled(
            context,
            EsimSettingsActivity::class.java.name,
            hasNonRemovableEuicc,
        )

        if (hasNonRemovableEuicc) {
            EsimController.getInstance(context).onBootCompleted()
        }
    }

    private fun setComponentEnabled(context: Context, component: String, enabled: Boolean) {
        val name = ComponentName(context, component)
        val pm = context.packageManager
        val newState =
            if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }

        if (pm.getComponentEnabledSetting(name) != newState) {
            pm.setComponentEnabledSetting(name, newState, PackageManager.DONT_KILL_APP)
        }
    }
}
