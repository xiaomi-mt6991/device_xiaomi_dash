/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.light

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import com.android.settingslib.widget.MainSwitchPreference
import com.android.settingslib.widget.SettingsBasePreferenceFragment
import com.xiaomi.settings.R

class LightSettingsFragment : SettingsBasePreferenceFragment(), Preference.OnPreferenceChangeListener {

    private var enablePref: MainSwitchPreference? = null
    private var standaloneEnablePref: SwitchPreferenceCompat? = null
    private var notificationsAppsPref: Preference? = null
    private var dynamicNotificationsAppsPref: Preference? = null
    private var gameModeAppsPref: Preference? = null
    private var musicAppsPref: Preference? = null
    private var notifAccessPref: Preference? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.light_settings, rootKey)

        enablePref = findPreference("light_enable")
        enablePref?.onPreferenceChangeListener = this

        standaloneEnablePref = findPreference("light_standalone_enable")
        standaloneEnablePref?.onPreferenceChangeListener = this

        // Color pickers + sliders apply live via LightService's own
        // listener, but also restart the service like the other state
        // keys so a fresh evaluation is guaranteed.
        listOf(
            "light_standalone_color",
            "light_charging_color",
            "light_charging_mode",
            "light_incoming_call_color",
            "light_camera_color",
            "light_notifications_color",
            "light_music_color",
            "light_game_mode_color",
            "light_brightness",
            "light_gradient_speed",
        ).forEach { key ->
            findPreference<Preference>(key)?.onPreferenceChangeListener = this
        }

        notifAccessPref = findPreference("light_notif_access")
        notifAccessPref?.setOnPreferenceClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            true
        }

        notificationsAppsPref = findPreference("light_notifications_apps")
        notificationsAppsPref?.setOnPreferenceClickListener {
            openAppSelector("Select Apps for Notifications", "light_notifications_apps")
        }

        dynamicNotificationsAppsPref = findPreference("light_dynamic_notifications_apps")
        dynamicNotificationsAppsPref?.setOnPreferenceClickListener {
            openAppSelector("Select Apps for Dynamic Notifications", "light_dynamic_notifications_apps")
        }

        musicAppsPref = findPreference("light_music_apps")
        musicAppsPref?.setOnPreferenceClickListener {
            openAppSelector("Select Music Apps", "light_music_apps")
        }

        gameModeAppsPref = findPreference("light_game_mode_apps")
        gameModeAppsPref?.setOnPreferenceClickListener {
            openAppSelector("Select Games for LED", "light_game_mode_apps")
        }
    }

    override fun onResume() {
        super.onResume()
        updateNotifAccessSummary()
    }

    private fun openAppSelector(title: String, prefKey: String): Boolean {
        val intent = Intent(context, AppSelectorActivity::class.java)
        intent.putExtra("title", title)
        intent.putExtra("prefKey", prefKey)
        startActivity(intent)
        return true
    }

    private fun updateNotifAccessSummary() {
        val ctx = context ?: return
        val component = ComponentName(ctx, LightNotificationService::class.java)
        val enabled = Settings.Secure.getString(
            ctx.contentResolver,
            "enabled_notification_listeners",
        )?.contains(component.flattenToString()) == true
        notifAccessPref?.summary = if (enabled) {
            getString(R.string.light_notif_access_summary)
        } else {
            getString(R.string.light_notif_access_summary) + " — tap to grant"
        }
    }

    override fun onPreferenceChange(preference: Preference, newValue: Any?): Boolean {
        when (preference.key) {
            "light_enable" -> {
                val enabled = newValue as Boolean
                if (enabled) {
                    context?.let { LightService.start(it) }
                } else {
                    context?.stopService(Intent(context, LightService::class.java))
                    LedManager.turnOff()
                }
            }
            "light_standalone_enable", "light_standalone_color",
            "light_brightness", "light_gradient_speed", "light_camera_enable", "light_camera_color",
            "light_charging_color", "light_charging_mode", "light_music_color", "light_game_mode_color",
            "light_incoming_call_color", "light_notifications_color" -> {
                // Restart service to apply the state change
                val enabled = enablePref?.isChecked == true
                if (enabled) {
                    context?.let { LightService.start(it) }
                }
            }
        }
        return true
    }
}
