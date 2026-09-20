/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.light

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.preference.PreferenceManager
import com.xiaomi.settings.R

class BacklightTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        val enabled = LightService.isEnabled(this)
        if (DEBUG) Log.d(TAG, "onClick: enabled=$enabled")
        PreferenceManager.getDefaultSharedPreferences(this).edit()
            .putBoolean("light_enable", !enabled)
            .apply()
        if (!enabled) {
            LightService.start(this)
        } else {
            stopService(Intent(this, LightService::class.java))
            LedManager.turnOff()
        }
        refresh()
    }

    private fun refresh() {
        val enabled = LightService.isEnabled(this)
        qsTile?.let {
            it.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            it.subtitle =
                if (enabled) getString(R.string.light_tile_on)
                else getString(R.string.light_tile_off)
            it.updateTile()
        }
    }

    companion object {
        private const val TAG = "BacklightTile"
        private val DEBUG = Log.isLoggable(TAG, Log.DEBUG)
    }
}
