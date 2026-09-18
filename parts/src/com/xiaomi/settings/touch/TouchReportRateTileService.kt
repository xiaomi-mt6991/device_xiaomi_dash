/*
 * SPDX-FileCopyrightText: The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.touch

import android.os.UserHandle
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.xiaomi.settings.R

class TouchReportRateTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        val forced = isForced()
        if (DEBUG) Log.d(TAG, "onClick: forced=$forced")
        Settings.System.putIntForUser(
            contentResolver,
            TouchReportRateService.SETTING_KEY,
            if (forced) 0 else 1,
            UserHandle.USER_CURRENT,
        )
        // force = true: user just tapped the tile — apply now even when the
        // persistent-across-reboot toggle is off.
        TouchReportRateService.applyReportRate(this, force = true)
        refresh()
    }

    private fun isForced(): Boolean =
        Settings.System.getIntForUser(
            contentResolver,
            TouchReportRateService.SETTING_KEY,
            1,
            UserHandle.USER_CURRENT,
        ) == 1

    private fun refresh() {
        val forced = isForced()
        qsTile?.let {
            it.state = if (forced) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            it.subtitle =
                if (forced) getString(R.string.touch_tile_forced)
                else getString(R.string.touch_tile_auto)
            it.updateTile()
        }
    }

    companion object {
        private const val TAG = "TouchRateTile"
        private val DEBUG = Log.isLoggable(TAG, Log.DEBUG)
    }
}
