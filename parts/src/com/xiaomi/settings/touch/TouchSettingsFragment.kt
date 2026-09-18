/*
 * SPDX-FileCopyrightText: The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.touch

import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import com.android.settingslib.widget.FooterPreference
import com.android.settingslib.widget.MainSwitchPreference
import com.android.settingslib.widget.SettingsBasePreferenceFragment
import com.xiaomi.settings.R

/**
 * Detail page for HTSR. Mirrors the inline forced-HTSR switch and adds the
 * persistent-across-reboot-and-screen-off toggle. All surfaces (inline
 * switch on the parent row, QS tile, this page) stay in sync via a
 * ContentObserver on the underlying Settings.System keys.
 */
class TouchSettingsFragment :
    SettingsBasePreferenceFragment(), Preference.OnPreferenceChangeListener {

    private val handler = Handler(Looper.getMainLooper())

    private val settingObserver =
        object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                refreshUi()
            }
        }

    private val forcedSwitch by lazy { findPreference<MainSwitchPreference>(KEY_FORCED)!! }
    private val persistentSwitch by lazy {
        findPreference<SwitchPreferenceCompat>(KEY_PERSISTENT)!!
    }
    private val saverSwitch by lazy {
        findPreference<SwitchPreferenceCompat>(KEY_SAVER)!!
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.touch_settings, rootKey)

        forcedSwitch.onPreferenceChangeListener = this
        persistentSwitch.onPreferenceChangeListener = this
        saverSwitch.onPreferenceChangeListener = this
        findPreference<FooterPreference>(KEY_FOOTER)?.let {
            it.title = getString(R.string.touch_footer)
        }
        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        val cr = requireContext().contentResolver
        cr.registerContentObserver(
            Settings.System.getUriFor(TouchReportRateService.SETTING_KEY),
            false,
            settingObserver,
        )
        cr.registerContentObserver(
            Settings.System.getUriFor(TouchReportRateService.PERSISTENT_SETTING_KEY),
            false,
            settingObserver,
        )
        refreshUi()
    }

    override fun onPause() {
        requireContext().contentResolver.unregisterContentObserver(settingObserver)
        super.onPause()
    }

    override fun onPreferenceChange(preference: Preference, newValue: Any?): Boolean {
        if (!TouchReportRateService.isReportRateWritable()) return false
        val checked = newValue as Boolean
        val cr = requireContext().contentResolver
        when (preference.key) {
            KEY_FORCED ->
                Settings.System.putIntForUser(
                    cr,
                    TouchReportRateService.SETTING_KEY,
                    if (checked) 1 else 0,
                    UserHandle.USER_CURRENT,
                )
            KEY_PERSISTENT ->
                Settings.System.putIntForUser(
                    cr,
                    TouchReportRateService.PERSISTENT_SETTING_KEY,
                    if (checked) 1 else 0,
                    UserHandle.USER_CURRENT,
                )
            KEY_SAVER ->
                Settings.System.putIntForUser(
                    cr,
                    TouchReportRateService.SAVER_SETTING_KEY,
                    if (checked) 1 else 0,
                    UserHandle.USER_CURRENT,
                )
            else -> return false
        }
        // User-driven write: apply now even when persistence is off. The
        // observer above only refreshes the UI, so this cannot loop.
        TouchReportRateService.applyReportRate(requireContext(), force = true)
        refreshUi()
        return true
    }

    private fun refreshUi() {
        if (!isAdded) return
        val ctx = context ?: return
        if (!TouchReportRateService.isReportRateWritable()) {
            forcedSwitch.isEnabled = false
            forcedSwitch.summary = getString(R.string.touch_unavailable_summary)
            persistentSwitch.isChecked = false
            persistentSwitch.isEnabled = false
            saverSwitch.isChecked = false
            saverSwitch.isEnabled = false
            return
        }
        forcedSwitch.isEnabled = true
        val forced =
            Settings.System.getIntForUser(
                ctx.contentResolver,
                TouchReportRateService.SETTING_KEY,
                1,
                UserHandle.USER_CURRENT,
            ) == 1
        val persistent =
            Settings.System.getIntForUser(
                ctx.contentResolver,
                TouchReportRateService.PERSISTENT_SETTING_KEY,
                1,
                UserHandle.USER_CURRENT,
            ) == 1
        if (forcedSwitch.isChecked != forced) forcedSwitch.isChecked = forced
        forcedSwitch.summary =
            getString(
                if (forced) R.string.touch_tile_forced else R.string.touch_tile_auto,
            )
        // Persistence only means something while forced mode is on: with the
        // main toggle off the row shows off and disabled, keeping the stored
        // value underneath for when forced mode comes back.
        val shownPersistent = forced && persistent
        if (persistentSwitch.isChecked != shownPersistent) {
            persistentSwitch.isChecked = shownPersistent
        }
        persistentSwitch.isEnabled = forced
        val saver =
            Settings.System.getIntForUser(
                ctx.contentResolver,
                TouchReportRateService.SAVER_SETTING_KEY,
                1,
                UserHandle.USER_CURRENT,
            ) == 1
        if (saverSwitch.isChecked != saver) saverSwitch.isChecked = saver
        saverSwitch.isEnabled = forced
    }

    companion object {
        private const val KEY_FORCED = "touch_high_sampling_rate"
        private const val KEY_PERSISTENT = "touch_high_sampling_rate_persistent"
        private const val KEY_SAVER = "touch_pause_on_saver"
        private const val KEY_FOOTER = "touch_footer"
    }
}
