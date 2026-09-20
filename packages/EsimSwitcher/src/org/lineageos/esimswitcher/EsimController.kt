/*
 * SPDX-FileCopyrightText: 2025 Paranoid Android
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.esimswitcher

import android.content.Context
import android.os.SystemProperties
import android.telephony.SubscriptionManager
import android.util.Log
import java.io.File

class EsimController private constructor(private val context: Context) {

    companion object {
        private const val TAG = "EsimController"
        private const val STATE_TIMEOUT_MS = 3000L

        @Volatile private var instance: EsimController? = null
        fun getInstance(context: Context): EsimController =
            instance ?: synchronized(this) {
                instance ?: EsimController(context.applicationContext).also { instance = it }
            }
    }

    fun onBootCompleted() {
        Log.d(TAG, "onBootCompleted")
    }

    fun getEsimActive(): Boolean {
        val subscriptionManager =
            context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
        return subscriptionManager?.activeSubscriptionInfoList?.any { it.isEmbedded } ?: false
    }

    fun getEsimEnabled(): Boolean {
        runCatching { SystemProperties.set("ctl.start", "vendor.esim_state") }
            .onFailure { e ->
                Log.w(TAG, "ctl.start vendor.esim_state failed", e)
                return false
            }

        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < STATE_TIMEOUT_MS) {
            val state = runCatching { SystemProperties.get("sys.esim.state", "") }.getOrDefault("")
            if (state.isNotEmpty()) {
                Log.i(TAG, "getEsimEnabled state: $state")
                return state == "enabled"
            }
            try {
                Thread.sleep(100)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        Log.e(TAG, "getEsimEnabled: timeout")
        return false
    }

    fun setEsimEnabled(isEnabled: Boolean) {
        Log.i(TAG, "setEsimEnabled: $isEnabled")
        SystemProperties.set(
            "ctl.start",
            if (isEnabled) "vendor.esim_enable" else "vendor.esim_disable"
        )
    }

    fun dispose() {
        Log.d(TAG, "dispose")
    }
}
