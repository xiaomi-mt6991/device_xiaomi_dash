/*
 * SPDX-FileCopyrightText: The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.touch

import android.content.ComponentName
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.xiaomi.settings.R

class TouchReportRateTileService : TileService() {

    private val handler = Handler(Looper.getMainLooper())

    // Re-sync the tile if the main Settings toggle flips while QS is open.
    private val settingObserver =
        object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                refresh()
            }
        }

    override fun onStartListening() {
        super.onStartListening()
        runCatching {
            contentResolver.registerContentObserver(
                Settings.System.getUriFor(TouchReportRateService.SETTING_KEY),
                false,
                settingObserver,
            )
        }
        refresh()
    }

    override fun onStopListening() {
        runCatching { contentResolver.unregisterContentObserver(settingObserver) }
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        // Locked while a game is focused: power HAL owns the touchfeature
        // channels then, flipping HTSR would fight it.
        if (GameState.isGameActive(this)) {
            refresh()
            return
        }
        val forced = isForced()
        if (DEBUG) Log.d(TAG, "onClick: forced=$forced")
        Settings.System.putIntForUser(
            contentResolver,
            TouchReportRateService.SETTING_KEY,
            if (forced) 0 else 1,
            UserHandle.USER_CURRENT,
        )
        // User just tapped the tile — apply immediately.
        TouchReportRateService.applyReportRate(this)
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
        qsTile?.let {
            if (GameState.isGameActive(this)) {
                it.state = Tile.STATE_UNAVAILABLE
                it.subtitle = getString(R.string.touch_tile_gaming)
            } else {
                val forced = isForced()
                it.state = if (forced) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                it.subtitle =
                    if (forced) getString(R.string.touch_tile_forced)
                    else getString(R.string.touch_tile_auto)
            }
            it.updateTile()
        }
    }

    companion object {
        private const val TAG = "TouchRateTile"
        private val DEBUG = Log.isLoggable(TAG, Log.DEBUG)

        /** Ask SystemUI to re-listen so the tile re-syncs after a flip. */
        fun requestUpdate(context: Context) {
            try {
                TileService.requestListeningState(
                    context,
                    ComponentName(context, TouchReportRateTileService::class.java),
                )
            } catch (e: Exception) {
                // SystemUI isn't always reachable from here; the tile still
                // re-syncs on its next listen. Gated: an NPE stack on every
                // toggle is pure log spam, and it slowed the flip path.
                if (DEBUG) Log.w(TAG, "requestUpdate failed", e)
            }
        }
    }
}
