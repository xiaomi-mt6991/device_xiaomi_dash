/*
 * SPDX-FileCopyrightText: 2025 Paranoid Android
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.esimswitcher

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import com.android.settingslib.widget.FooterPreference
import com.android.settingslib.widget.MainSwitchPreference
import com.android.settingslib.widget.SettingsBasePreferenceFragment

class EsimSettingsFragment :
    SettingsBasePreferenceFragment(), Preference.OnPreferenceChangeListener {

    companion object {
        private const val TAG = "EsimSettingsFragment"
    }

    private val esimController by lazy { EsimController.getInstance(requireContext()) }

    private val switchBar by lazy { findPreference<MainSwitchPreference>("esim_enable")!! }
    private val footerPref by lazy { findPreference<FooterPreference>("esim_footer")!! }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        Log.d(TAG, "onCreatePreferences")
        setPreferencesFromResource(R.xml.settings_esim, rootKey)

        switchBar.onPreferenceChangeListener = this
        switchBar.isEnabled = false // disable until we know the state
        footerPref.title = getString(R.string.esim_footer_note)

        Thread {
            val enabled = runCatching { esimController.getEsimEnabled() }.getOrDefault(false)
            val activity = activity ?: return@Thread
            if (!isAdded) return@Thread
            activity.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                switchBar.isChecked = enabled
                switchBar.isEnabled = true
            }
        }.start()
    }

    override fun onPreferenceChange(preference: Preference, newValue: Any?): Boolean {
        if (preference == switchBar) {
            val isChecked = newValue as Boolean
            Log.d(TAG, "onPreferenceChange: $isChecked")
            if (esimController.getEsimActive()) {
                if (isChecked) return false
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.esim_warning_title)
                    .setMessage(R.string.esim_warning_message)
                    .setPositiveButton(android.R.string.ok, null)
                    .setCancelable(false)
                    .show()
                return false
            } else {
                esimController.setEsimEnabled(isChecked)
            }
        }
        return true
    }
}
