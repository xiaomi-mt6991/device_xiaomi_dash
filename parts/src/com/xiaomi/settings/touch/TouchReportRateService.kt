/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.touch

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import com.xiaomi.settings.utils.FileUtils

class TouchReportRateService : Service() {

    private val handler = Handler(Looper.getMainLooper())

    private val settingObserver =
        object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                if (DEBUG) Log.d(TAG, "SettingObserver: onChange")
                // Silent: every putInt originates from our own switch/tile
                // flips, which already notify exactly once via their direct
                // apply. Notifying here too is what double-refreshes (blinks)
                // the Settings row a few ms after the flip.
                applyReportRate(this@TouchReportRateService, notify = false)
            }
        }

    private val screenStateReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (DEBUG) Log.d(TAG, "onReceive: ${intent.action}")
                if (intent.action == Intent.ACTION_SCREEN_ON) {
                    // The touch driver resets the rate on resume after this
                    // broadcast is delivered; re-asserting here (and again
                    // after RESUME_SETTLE_DELAY_MS so the reset cannot win)
                    // makes the forced value stick across unlock and first
                    // unlock after reboot alike. Persistence is always on.
                    // Silent: Settings already shows this value, notifying
                    // would just blink the row while the user watches it.
                    if (DEBUG) Log.d(TAG, "Screen on, restoring touch report rate")
                    applyReportRate(this@TouchReportRateService, notify = false)
                    handler.postDelayed(
                        { applyReportRate(this@TouchReportRateService, notify = false) },
                        RESUME_SETTLE_DELAY_MS,
                    )
                } else if (intent.action == Intent.ACTION_SCREEN_OFF) {
                    // Screen off: full HTSR off (proc + touchfeature) so the
                    // touch IC is not held at max through sleep. Screen-on
                    // re-asserts per the toggle. Silent.
                    if (DEBUG) Log.d(TAG, "Screen off, turning HTSR off")
                    handler.removeCallbacksAndMessages(null)
                    applyScreenOff()
                }
            }
        }

    private val packageReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != Intent.ACTION_PACKAGE_CHANGED) return
                // Game-list edits arrive with no power HAL exit event when
                // the delisted app is focused, so TF stays pinned at 1 with
                // HTSR OFF until something resets it. If no game is focused
                // now, re-assert the OFF baseline silently. Skipped when a
                // real game is focused (framework owns the channels then).
                if (DEBUG) Log.d(TAG, "package changed, reconciling touch rate")
                val forced =
                    Settings.System.getIntForUser(
                        context.contentResolver,
                        SETTING_KEY,
                        DEFAULT_VALUE,
                        UserHandle.USER_CURRENT,
                    ) == 1
                if (!forced && !GameState.isGameActive(context)) {
                    applyReportRate(context, notify = false)
                }
            }
        }

    override fun onCreate() {
        super.onCreate()
        if (DEBUG) Log.d(TAG, "onCreate")
        contentResolver.registerContentObserver(
            Settings.System.getUriFor(SETTING_KEY),
            false,
            settingObserver,
            UserHandle.USER_CURRENT,
        )
        registerReceiver(
            screenStateReceiver,
            IntentFilter(Intent.ACTION_SCREEN_ON).apply {
                addAction(Intent.ACTION_SCREEN_OFF)
            },
            Context.RECEIVER_NOT_EXPORTED,
        )
        registerReceiver(
            packageReceiver,
            IntentFilter(Intent.ACTION_PACKAGE_CHANGED).apply { addDataScheme("package") },
            Context.RECEIVER_NOT_EXPORTED,
        )
        // Boot restore: if the screen is already off, stay fully off until
        // the first SCREEN_ON (spec). Otherwise restore the stored toggle.
        // Silent: Settings queries on page open, no need to blink it.
        val interactive =
            runCatching {
                getSystemService(PowerManager::class.java)?.isInteractive
            }.getOrNull() ?: true
        if (interactive) {
            applyReportRate(this, notify = false)
        } else {
            if (DEBUG) Log.d(TAG, "onCreate with screen off, staying off")
            applyScreenOff()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (DEBUG) Log.d(TAG, "onStartCommand")
        return START_STICKY
    }

    override fun onDestroy() {
        if (DEBUG) Log.d(TAG, "onDestroy")
        handler.removeCallbacksAndMessages(null)
        contentResolver.unregisterContentObserver(settingObserver)
        unregisterReceiver(screenStateReceiver)
        unregisterReceiver(packageReceiver)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "TouchReportRateService"
        private val DEBUG = Log.isLoggable(TAG, Log.DEBUG)

        const val SETTING_KEY = "touch_high_sampling_rate"
        private const val DEFAULT_VALUE = 1
        private const val PROC_PATH = "/proc/xm_htc_report_rate"
        private const val VALUE_NORMAL = "120"
        private const val VALUE_HIGH = "240"
        private const val RESUME_SETTLE_DELAY_MS = 2000L

        fun isReportRateWritable(): Boolean = FileUtils.isFileWritable(PROC_PATH)

        /**
         * Apply the current setting to both HTSR channels.
         *
         * Toggle ON forces everything to max (proc 240 + touchfeature
         * GAME/ACTIVE=1); proc alone is weaker than game mode. Toggle OFF
         * is games-only: baseline proc 120 and no touchfeature drive, the
         * power HAL owns the channels for focused games.
         *
         * While a game is focused the framework owns touchfeature — the TF
         * write is skipped entirely (both ON and OFF) so we never fight
         * the HAL mid-game. The proc write always lands: the HAL reads it
         * back for its proc-stick (240 keeps TF=1 across game exit, 120
         * lets game exit drop it), and the stored toggle is untouched so
         * game exit / screen-on restores correctly.
         *
         * @param notify true for user flips (switch + tile) so the
         * Settings row refreshes once and the QS tile re-listen; false
         * for boot/screen-on re-asserts of an unchanged value so an open
         * Settings page does not blink.
         */
        fun applyReportRate(context: Context, notify: Boolean = true) {
            val value =
                Settings.System.getIntForUser(
                    context.contentResolver,
                    SETTING_KEY,
                    DEFAULT_VALUE,
                    UserHandle.USER_CURRENT,
                )
            val gameActive = GameState.isGameActive(context)
            if (DEBUG) Log.d(TAG, "applyReportRate: $value notify=$notify game=$gameActive")
            FileUtils.writeLine(PROC_PATH, if (value == 1) VALUE_HIGH else VALUE_NORMAL)
            if (!gameActive) {
                TouchFeatureCompat.setGameMode(value == 1)
            }
            if (notify) {
                HtsrSwitchProvider.notifyChanged(context)
                TouchReportRateTileService.requestUpdate(context)
            }
        }

        /**
         * Full HTSR off for screen-off: proc to baseline plus touchfeature
         * reset, regardless of toggle or game state. The next SCREEN_ON
         * re-asserts per the toggle. Never notifies.
         */
        fun applyScreenOff() {
            FileUtils.writeLine(PROC_PATH, VALUE_NORMAL)
            TouchFeatureCompat.setGameMode(false)
        }

        fun startService(context: Context) {
            context.startServiceAsUser(
                Intent(context, TouchReportRateService::class.java),
                UserHandle.CURRENT,
            )
        }
    }
}
