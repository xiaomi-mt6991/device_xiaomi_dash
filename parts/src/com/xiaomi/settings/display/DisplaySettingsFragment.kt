/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.display

import android.os.Bundle
import androidx.preference.Preference
import com.android.settingslib.widget.SettingsBasePreferenceFragment
import com.xiaomi.settings.R
import com.xiaomi.settings.widget.TickSeekBarPreference

class DisplaySettingsFragment :
    SettingsBasePreferenceFragment(),
    Preference.OnPreferenceChangeListener {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.display_settings, rootKey)

        findPreference<TickSeekBarPreference>(DisplaySaturation.KEY)?.onPreferenceChangeListener = this

        findPreference<Preference>("display_saturation_reset")?.setOnPreferenceClickListener {
            findPreference<TickSeekBarPreference>(DisplaySaturation.KEY)?.let { slider ->
                slider.value = DisplaySaturation.DEFAULT
                slider.refreshSummary()
                context?.let { ctx -> DisplaySaturation.apply(ctx, DisplaySaturation.DEFAULT) }
            }
            true
        }
    }

    override fun onPreferenceChange(preference: Preference, newValue: Any?): Boolean {
        if (preference.key == DisplaySaturation.KEY) {
            val level = (newValue as? Int) ?: return false
            // Always accept the drag: SeekBarPreference reverts the thumb
            // when we return false, which feels like a stuck slider. The
            // transform itself is best-effort (failures warn in logcat).
            val ctx = context ?: return false
            DisplaySaturation.apply(ctx, level)
            return true
        }
        return true
    }
}
