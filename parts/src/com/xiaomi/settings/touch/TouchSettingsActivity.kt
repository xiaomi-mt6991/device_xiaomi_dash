/*
 * SPDX-FileCopyrightText: The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.touch

import android.os.Bundle
import android.util.Log
import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity

/**
 * Hosts the HTSR detail fragment. The inline switch on the parent row still
 * flips forced HTSR via HtsrSwitchProvider; tapping the title opens this
 * page where the persistent toggle lives.
 */
class TouchSettingsActivity : CollapsingToolbarBaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (DEBUG) Log.d(TAG, "onCreate")
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(
                    com.android.settingslib.collapsingtoolbar.R.id.content_frame,
                    TouchSettingsFragment(),
                    TAG,
                )
                .commit()
        }
    }

    companion object {
        private const val TAG = "TouchSettings"
        private val DEBUG = Log.isLoggable(TAG, Log.DEBUG)
    }
}
