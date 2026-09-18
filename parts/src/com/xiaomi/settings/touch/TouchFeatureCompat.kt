/*
 * SPDX-FileCopyrightText: The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.touch

import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.ServiceManager
import android.util.Log
import vendor.xiaomi.hw.touchfeature.ITouchFeature

/**
 * Thin wrapper over the AIDL ITouchFeature service.
 *
 * The kernel-side HTSR knob (/proc/xm_htc_report_rate) and the touchfeature
 * GAME/ACTIVE modes are separate channels: power-mode.cpp raises both while
 * a game runs. The HTSR toggle must drive both as well, otherwise forced-ON
 * (proc 240 alone) feels weaker than game mode (touchfeature 1).
 * Turning the toggle off must reset the touchfeature side or the high rate
 * silently survives.
 *
 * The service is registered by init at boot; a toggle arriving in the
 * same instant can see it briefly absent. Retries run on a background
 * thread with a few seconds of backoff so the request lands even then.
 */
object TouchFeatureCompat {

    private const val TAG = "TouchFeatureCompat"
    private val DEBUG = Log.isLoggable(TAG, Log.DEBUG)

    private const val SERVICE_NAME = "vendor.xiaomi.hw.touchfeature.ITouchFeature/default"
    private const val TOUCH_ID = 0
    private const val TOUCH_GAME_MODE = 0
    private const val TOUCH_ACTIVE_MODE = 202

    private const val RETRY_DELAY_MS = 200L
    private const val RETRY_MAX_DELAY_MS = 1600L
    private const val RETRY_MAX_ATTEMPTS = 5

    private val retryThread = HandlerThread(TAG).apply { start() }
    private val retryHandler = Handler(retryThread.looper)

    private var pendingEnabled = false
    private var retryAttempt = 0

    private val retry =
        object : Runnable {
            override fun run() {
                if (apply(pendingEnabled)) return
                retryAttempt++
                if (retryAttempt > RETRY_MAX_ATTEMPTS) {
                    Log.e(TAG, "giving up: touchfeature service never appeared")
                    return
                }
                val delay = (RETRY_DELAY_MS shl (retryAttempt - 1)).coerceAtMost(RETRY_MAX_DELAY_MS)
                retryHandler.postDelayed(this, delay)
            }
        }

    fun setGameMode(enabled: Boolean) {
        // Always runs off-caller: the two setModeValue binders would
        // otherwise stretch the Settings toggle round-trip (switch stays
        // greyed) and block the service main thread on boot/screen-on.
        retryHandler.removeCallbacks(retry)
        pendingEnabled = enabled
        retryAttempt = 0
        retryHandler.post(retry)
    }

    private fun apply(enabled: Boolean): Boolean {
        val service = getService() ?: return false
        val value = if (enabled) 1 else 0
        // setModeValue returns a status int (power HAL treats result < 0 as
        // failure); an exception-free call can still have failed, and the
        // retry ladder must keep going then.
        val gameRet =
            runCatching { service.setModeValue(TOUCH_ID, TOUCH_GAME_MODE, value) }
                .onFailure { e -> Log.w(TAG, "setModeValue GAME failed", e) }
                .getOrNull() ?: return false
        val activeRet =
            runCatching { service.setModeValue(TOUCH_ID, TOUCH_ACTIVE_MODE, value) }
                .onFailure { e -> Log.w(TAG, "setModeValue ACTIVE failed", e) }
                .getOrNull() ?: return false
        val ok = gameRet >= 0 && activeRet >= 0
        if (DEBUG) Log.d(TAG, "setGameMode($enabled) game=$gameRet active=$activeRet ok=$ok")
        return ok
    }

    private fun getService(): ITouchFeature? =
        runCatching {
            val binder: IBinder? = ServiceManager.getService(SERVICE_NAME)
            binder?.let { ITouchFeature.Stub.asInterface(it) }
        }.onFailure {
            Log.w(TAG, "touchfeature service unavailable", it)
        }.getOrNull()
}
