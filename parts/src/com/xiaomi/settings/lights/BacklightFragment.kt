/*
 * SPDX-FileCopyrightText: The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.lights

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import com.android.settingslib.widget.FooterPreference
import com.android.settingslib.widget.MainSwitchPreference
import com.android.settingslib.widget.SettingsBasePreferenceFragment
import com.android.settingslib.widget.SliderPreference
import com.xiaomi.settings.R

/**
 * Back light effects page. Master switch, per-effect rows (call mode inline,
 * app lists on sub-screens, camera toggle inline), global color + brightness.
 * All state lives in [BacklightPrefs]; the service is poked after each edit.
 */
class BacklightFragment :
    SettingsBasePreferenceFragment(), Preference.OnPreferenceChangeListener {

    private lateinit var prefs: BacklightPrefs

    private val master by lazy { findPreference<MainSwitchPreference>(KEY_MASTER)!! }
    private val callPref by lazy { findPreference<ListPreference>(KEY_CALL)!! }
    private val gameRow by lazy { findPreference<Preference>(KEY_GAME)!! }
    private val musicRow by lazy { findPreference<Preference>(KEY_MUSIC)!! }
    private val notifRow by lazy { findPreference<Preference>(KEY_NOTIF)!! }
    private val notifAccess by lazy { findPreference<Preference>(KEY_NOTIF_ACCESS)!! }
    private val cameraSwitch by lazy { findPreference<SwitchPreferenceCompat>(KEY_CAMERA)!! }
    private val colorPref by lazy { findPreference<ListPreference>(KEY_COLOR)!! }
    private val brightnessSlider by lazy { findPreference<SliderPreference>(KEY_BRIGHTNESS)!! }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        prefs = BacklightPrefs(requireContext())
        setPreferencesFromResource(R.xml.backlight_settings, rootKey)

        master.onPreferenceChangeListener = this
        callPref.onPreferenceChangeListener = this
        cameraSwitch.onPreferenceChangeListener = this
        colorPref.onPreferenceChangeListener = this
        brightnessSlider.min = 0
        brightnessSlider.max = 100
        brightnessSlider.sliderIncrement = 1
        brightnessSlider.setTickVisible(true)
        brightnessSlider.onPreferenceChangeListener = this

        gameRow.setOnPreferenceClickListener { openApps(AppSelectFragment.MODE_GAME); true }
        musicRow.setOnPreferenceClickListener { openApps(AppSelectFragment.MODE_MUSIC); true }
        notifRow.setOnPreferenceClickListener { openApps(AppSelectFragment.MODE_NOTIF); true }
        notifAccess.setOnPreferenceClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            true
        }

        findPreference<FooterPreference>(KEY_INTRO)?.title = getString(R.string.backlight_intro)
        findPreference<FooterPreference>(KEY_FOOTER)?.title = getString(R.string.backlight_footer)
        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    private fun openApps(mode: String) {
        startActivity(
            Intent(requireContext(), AppSelectActivity::class.java)
                .putExtra(AppSelectActivity.EXTRA_MODE, mode),
        )
    }

    private fun refreshUi() {
        val ctx = context ?: return
        master.isChecked = prefs.master

        val callValues = resources.getStringArray(R.array.backlight_call_values)
        val callNames = resources.getStringArray(R.array.backlight_call_names)
        val callIdx = callValues.indexOf(prefs.callMode.toString()).coerceAtLeast(0)
        callPref.value = callValues[callIdx]
        callPref.summary = callNames[callIdx]
        callPref.setDefaultValue(callValues[0])

        val pm = ctx.packageManager
        val auto = autoGames(pm).map { it.packageName }.toSet()
        val gameCount =
            (auto + prefs.gameCustom).count { prefs.isGameEnabled(it, it in auto) }
        gameRow.summary =
            if (gameCount == 0) getString(R.string.backlight_game_empty)
            else getString(R.string.backlight_game_summary, gameCount)

        musicRow.summary =
            if (prefs.musicApps.isEmpty()) getString(R.string.backlight_music_empty)
            else getString(R.string.backlight_music_summary, prefs.musicApps.size.toString())

        notifRow.summary =
            if (prefs.notifApps.isEmpty()) getString(R.string.backlight_notif_empty)
            else getString(R.string.backlight_game_summary, prefs.notifApps.size)

        cameraSwitch.isChecked = prefs.cameraEnabled

        val colorHex = "%06X".format(prefs.color and 0xFFFFFF)
        val colorValues = resources.getStringArray(R.array.backlight_color_values)
        val colorNames = resources.getStringArray(R.array.backlight_color_names)
        val colorIdx = colorValues.indexOf(colorHex).coerceAtLeast(0)
        colorPref.value = colorValues[colorIdx]
        colorPref.summary = colorNames[colorIdx]

        brightnessSlider.value = prefs.brightness
    }

    override fun onPreferenceChange(preference: Preference, newValue: Any?): Boolean {
        when (preference.key) {
            KEY_MASTER -> prefs.master = newValue as Boolean
            KEY_CALL -> prefs.callMode = (newValue as String).toIntOrNull() ?: 0
            KEY_CAMERA -> prefs.cameraEnabled = newValue as Boolean
            KEY_COLOR ->
                prefs.color =
                    (newValue as String).toLongOrNull(16)?.toInt()
                        ?: BacklightPrefs.DEFAULT_COLOR
            KEY_BRIGHTNESS -> prefs.brightness = newValue as Int
            else -> return false
        }
        BacklightService.start(requireContext())
        refreshUi()
        return true
    }

    companion object {
        private const val KEY_MASTER = "backlight_master"
        private const val KEY_INTRO = "backlight_intro"
        private const val KEY_CALL = "backlight_call"
        private const val KEY_GAME = "backlight_game"
        private const val KEY_MUSIC = "backlight_music"
        private const val KEY_NOTIF = "backlight_notif"
        private const val KEY_NOTIF_ACCESS = "backlight_notif_access"
        private const val KEY_CAMERA = "backlight_camera"
        private const val KEY_COLOR = "backlight_color"
        private const val KEY_BRIGHTNESS = "backlight_brightness"
        private const val KEY_FOOTER = "backlight_footer"
    }
}
