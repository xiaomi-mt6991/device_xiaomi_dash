/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.light

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import com.android.settingslib.widget.SettingsBasePreferenceFragment

class AppSelectorFragment : SettingsBasePreferenceFragment() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val screen = preferenceManager.createPreferenceScreen(requireContext())
        preferenceScreen = screen
        loadApps(screen)
    }

    private fun loadApps(screen: PreferenceScreen) {
        val ctx = requireContext()
        val packageManager = ctx.packageManager
        val appContext = ctx.applicationContext
        val prefKey = arguments?.getString("prefKey") ?: PREF_AUTO_APPS
        val ownPackage = ctx.packageName

        // Label/icon resolution is binder IPC + resource loads for every
        // installed app: query and sort on a background thread, then
        // publish the rows on the UI thread so the page opens instantly.
        Thread {
            val entries = queryApps(appContext, packageManager, prefKey, ownPackage)
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                val uiCtx = requireContext()
                for (entry in entries) {
                    screen.addPreference(buildSwitch(uiCtx, entry))
                }
            }
        }.start()
    }

    private data class AppEntry(
        val packageName: String,
        val label: CharSequence,
        val icon: Drawable,
        val checked: Boolean,
    )

    private fun queryApps(
        appContext: Context,
        packageManager: PackageManager,
        prefKey: String,
        ownPackage: String,
    ): List<AppEntry> {
        val autoApps = PreferenceManager.getDefaultSharedPreferences(appContext)
            .getStringSet(prefKey, emptySet<String>()) ?: emptySet()

        return packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            .asSequence()
            .filter { appInfo ->
                appInfo.flags and ApplicationInfo.FLAG_SYSTEM == 0 &&
                    appInfo.packageName != ownPackage
            }
            .map { appInfo ->
                AppEntry(
                    packageName = appInfo.packageName,
                    label = appInfo.loadLabel(packageManager),
                    icon = appInfo.loadIcon(packageManager),
                    checked = appInfo.packageName in autoApps,
                )
            }
            // Selected first, then alphabetical — labels already resolved.
            .sortedWith(
                compareByDescending<AppEntry> { it.checked }
                    .thenBy { it.label.toString().lowercase() },
            ).toList()
    }

    private fun buildSwitch(ctx: Context, entry: AppEntry): SwitchPreferenceCompat =
        SwitchPreferenceCompat(ctx).apply {
            title = entry.label
            summary = entry.packageName
            icon = entry.icon
            isChecked = entry.checked
            isPersistent = false
            setOnPreferenceChangeListener { _, newValue ->
                updateAutoApp(entry.packageName, newValue as Boolean)
                true
            }
        }

    private fun updateAutoApp(packageName: String, add: Boolean) {
        val prefKey = arguments?.getString("prefKey") ?: PREF_AUTO_APPS
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val currentSet = prefs.getStringSet(prefKey, emptySet<String>()) ?: emptySet()

        val newSet = HashSet(currentSet)
        if (add) {
            newSet.add(packageName)
        } else {
            newSet.remove(packageName)
        }
        prefs.edit().putStringSet(prefKey, newSet).apply()
    }

    companion object {
        const val PREF_AUTO_APPS = "light_notifications_apps"
    }
}
