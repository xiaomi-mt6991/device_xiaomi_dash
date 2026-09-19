/*
 * SPDX-FileCopyrightText: The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.lights

import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity

/** Hosts the Back light effects page (Settings IA entry). */
class BacklightActivity : CollapsingToolbarBaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (DEBUG) Log.d(TAG, "onCreate")
        BacklightService.start(this)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(
                    com.android.settingslib.collapsingtoolbar.R.id.content_frame,
                    BacklightFragment(),
                    TAG,
                )
                .commit()
        }
    }

    override fun onResume() {
        super.onResume()
        startService(Intent(this, BacklightService::class.java))
    }

    companion object {
        private const val TAG = "Backlight"
        private val DEBUG = Log.isLoggable(TAG, Log.DEBUG)
    }
}
