/*
 * SPDX-FileCopyrightText: The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.lights

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.ApplicationInfoFlags
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceViewHolder
import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity
import com.android.settingslib.widget.SettingsBasePreferenceFragment
import com.xiaomi.settings.R

/** Hosts [AppSelectFragment]. Extra [AppSelectActivity.EXTRA_MODE]. */
class AppSelectActivity : CollapsingToolbarBaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = intent.getStringExtra(EXTRA_MODE) ?: AppSelectFragment.MODE_NOTIF
        title =
            getString(
                when (mode) {
                    AppSelectFragment.MODE_GAME -> R.string.backlight_game_title
                    AppSelectFragment.MODE_MUSIC -> R.string.backlight_music_title
                    else -> R.string.backlight_notif_title
                },
            )
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(
                    com.android.settingslib.collapsingtoolbar.R.id.content_frame,
                    AppSelectFragment().apply {
                        arguments = Bundle().apply { putString(AppSelectFragment.EXTRA_MODE, mode) }
                    },
                    TAG,
                )
                .commit()
        }
    }

    companion object {
        private const val TAG = "BacklightApps"
        const val EXTRA_MODE = "mode"
    }
}

/** All installed games, used for the game lighting list. */
internal fun autoGames(pm: PackageManager): List<ApplicationInfo> =
    runCatching {
            pm.getInstalledApplications(ApplicationInfoFlags.of(0))
                .filter { it.category == ApplicationInfo.CATEGORY_GAME }
                .sortedBy { pm.getApplicationLabel(it).toString() }
        }
        .getOrDefault(emptyList())

/**
 * Per-app picker. NOTIF/MUSIC keep a manual set; GAME lists auto-detected
 * games with per-app switches plus a manual custom section.
 */
class AppSelectFragment : SettingsBasePreferenceFragment() {

    private lateinit var prefs: BacklightPrefs
    private lateinit var mode: String

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        prefs = BacklightPrefs(requireContext())
        mode = arguments?.getString(EXTRA_MODE) ?: MODE_NOTIF
        rebuild()
    }

    override fun onResume() {
        super.onResume()
        rebuild()
    }

    private fun storeKey(): String =
        when (mode) {
            MODE_MUSIC -> BacklightPrefs.KEY_MUSIC
            MODE_GAME -> BacklightPrefs.KEY_GAME_CUSTOM
            else -> BacklightPrefs.KEY_NOTIF
        }

    private fun currentSet(): Set<String> =
        when (mode) {
            MODE_MUSIC -> prefs.musicApps
            MODE_GAME -> prefs.gameCustom
            else -> prefs.notifApps
        }

    private fun rebuild() {
        val ctx = requireContext()
        val pm = ctx.packageManager
        val screen = preferenceManager.createPreferenceScreen(ctx)

        if (mode == MODE_GAME) {
            val games = PreferenceCategory(ctx).apply { title = getString(R.string.backlight_game_title) }
            screen.addPreference(games)
            autoGames(pm).forEach { app ->
                games.addPreference(gameRow(app))
            }
        }

        val custom =
            PreferenceCategory(ctx).apply {
                title =
                    if (mode == MODE_GAME) getString(R.string.backlight_add_app_title)
                    else titleFor(ctx)
            }
        screen.addPreference(custom)
        val shown = currentSet()
        val autoPkgs = if (mode == MODE_GAME) autoGames(pm).map { it.packageName }.toSet() else emptySet()
        shown
            .mapNotNull { runCatching { pm.getApplicationInfo(it, 0) }.getOrNull() }
            .filter { it.packageName !in autoPkgs }
            .sortedBy { label(pm, it) }
            .forEach { app ->
                custom.addPreference(
                    object : Preference(ctx) {
                        override fun onBindViewHolder(holder: PreferenceViewHolder) {
                            super.onBindViewHolder(holder)
                            holder.itemView.setOnLongClickListener {
                                confirmRemove(app)
                                true
                            }
                        }
                    }.apply {
                        title = label(pm, app)
                        summary = gameSummary(app.packageName)
                        icon = pm.getApplicationIcon(app)
                        setOnPreferenceClickListener {
                            showGameConfig(app)
                            true
                        }
                    },
                )
            }
        custom.addPreference(
            Preference(ctx).apply {
                title = getString(R.string.backlight_add_app_title)
                setOnPreferenceClickListener {
                    showAppPicker()
                    true
                }
            },
        )

        preferenceScreen = screen
    }

    private fun gameRow(app: ApplicationInfo): Preference =
        Preference(requireContext()).apply {
            val pm = requireContext().packageManager
            title = label(pm, app)
            summary = gameSummary(app.packageName)
            icon = pm.getApplicationIcon(app)
            setOnPreferenceClickListener {
                showGameConfig(app)
                true
            }
        }

    private fun gameSummary(pkg: String): String {
        if (!prefs.isGameEnabled(pkg, true)) return getString(R.string.backlight_call_off)
        val effect =
            when (prefs.gameEffect(pkg)) {
                BacklightPrefs.EFFECT_STEADY -> getString(R.string.backlight_effect_steady)
                BacklightPrefs.EFFECT_BLINK -> getString(R.string.backlight_effect_blink)
                else -> getString(R.string.backlight_effect_breath)
            }
        val hex = "%06X".format(prefs.gameColor(pkg, prefs.color) and 0xFFFFFF)
        val idx = resources.getStringArray(R.array.backlight_color_values).indexOf(hex)
        val color =
            if (idx >= 0) resources.getStringArray(R.array.backlight_color_names)[idx] else "#$hex"
        return "$effect · $color"
    }

    private fun showGameConfig(app: ApplicationInfo) {
        val items =
            arrayOf(
                getString(R.string.backlight_call_off),
                getString(R.string.backlight_effect_steady),
                getString(R.string.backlight_effect_breath),
                getString(R.string.backlight_effect_blink),
            )
        val current =
            if (!prefs.isGameEnabled(app.packageName, true)) 0
            else
                when (prefs.gameEffect(app.packageName)) {
                    BacklightPrefs.EFFECT_STEADY -> 1
                    BacklightPrefs.EFFECT_BLINK -> 3
                    else -> 2
                }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.backlight_game_effect_title)
            .setSingleChoiceItems(items, current) { dialog, which ->
                dialog.dismiss()
                if (which == 0) {
                    prefs.setGameEnabled(app.packageName, false)
                    poke()
                    rebuild()
                } else {
                    prefs.setGameEnabled(app.packageName, true)
                    prefs.setGameEffect(
                        app.packageName,
                        when (which) {
                            1 -> BacklightPrefs.EFFECT_STEADY
                            3 -> BacklightPrefs.EFFECT_BLINK
                            else -> BacklightPrefs.EFFECT_BREATH
                        },
                    )
                    showGameColor(app)
                }
            }
            .show()
    }

    private fun showGameColor(app: ApplicationInfo) {
        val names = resources.getStringArray(R.array.backlight_color_names)
        val values = resources.getStringArray(R.array.backlight_color_values)
        val hex = "%06X".format(prefs.gameColor(app.packageName, prefs.color) and 0xFFFFFF)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.backlight_game_color_title)
            .setSingleChoiceItems(names, values.indexOf(hex).coerceAtLeast(0)) { dialog, which ->
                dialog.dismiss()
                prefs.setGameColor(
                    app.packageName,
                    values[which].toLongOrNull(16)?.toInt() ?: BacklightPrefs.DEFAULT_COLOR,
                )
                poke()
                rebuild()
            }
            .show()
    }

    private fun titleFor(ctx: android.content.Context): String =
        ctx.getString(
            when (mode) {
                MODE_MUSIC -> R.string.backlight_music_title
                else -> R.string.backlight_notif_title
            },
        )

    private fun confirmRemove(app: ApplicationInfo) {
        val pm = requireContext().packageManager
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.backlight_remove_app_title)
            .setMessage(getString(R.string.backlight_remove_app_message, label(pm, app)))
            .setPositiveButton(R.string.backlight_remove_app_confirm) { _, _ ->
                prefs.removeApp(storeKey(), app.packageName)
                poke()
                rebuild()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showAppPicker() {
        val ctx = requireContext()
        val pm = ctx.packageManager
        val shown = currentSet() + autoGames(pm).map { it.packageName }
        val candidates =
            pm.getInstalledApplications(ApplicationInfoFlags.of(0))
                .filter {
                    it.packageName !in shown && pm.getLaunchIntentForPackage(it.packageName) != null
                }
                .sortedBy { label(pm, it) }

        val iconSize = (32 * resources.displayMetrics.density).toInt()
        val iconPadding = (12 * resources.displayMetrics.density).toInt()
        val adapter =
            object : ArrayAdapter<ApplicationInfo>(
                ctx, android.R.layout.select_dialog_item, android.R.id.text1, candidates,
            ) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val view = super.getView(position, convertView, parent)
                    val app = candidates[position]
                    val text = view.findViewById<TextView>(android.R.id.text1)
                    text.text = label(pm, app)
                    pm.getApplicationIcon(app).apply { setBounds(0, 0, iconSize, iconSize) }
                        .let { text.setCompoundDrawablesRelative(it, null, null, null) }
                    text.compoundDrawablePadding = iconPadding
                    return view
                }
            }

        AlertDialog.Builder(ctx)
            .setTitle(R.string.backlight_add_app_dialog_title)
            .setAdapter(adapter) { _, which ->
                prefs.addApp(storeKey(), candidates[which].packageName)
                if (mode == MODE_GAME) {
                    prefs.setGameEnabled(candidates[which].packageName, true)
                }
                poke()
                rebuild()
            }
            .show()
    }

    private fun poke() {
        BacklightService.start(requireContext())
    }

    private fun label(pm: PackageManager, app: ApplicationInfo) =
        pm.getApplicationLabel(app).toString()

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_NOTIF = "notif"
        const val MODE_GAME = "game"
        const val MODE_MUSIC = "music"
    }
}
