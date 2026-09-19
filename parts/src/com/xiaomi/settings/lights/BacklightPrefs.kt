/*
 * SPDX-FileCopyrightText: The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.lights

import android.content.Context

/**
 * Back light effects settings, device-protected so the boot-time service can
 * read them before unlock.
 */
class BacklightPrefs(context: Context) {

    private val prefs =
        context
            .createDeviceProtectedStorageContext()
            .getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var master: Boolean
        get() = prefs.getBoolean(KEY_MASTER, true)
        set(value) = prefs.edit().putBoolean(KEY_MASTER, value).apply()

    /** Packed 0xRRGGBB effect color. */
    var color: Int
        get() = prefs.getInt(KEY_COLOR, DEFAULT_COLOR)
        set(value) = prefs.edit().putInt(KEY_COLOR, value and 0xFFFFFF).apply()

    /** Effect brightness, 0-100. */
    var brightness: Int
        get() = prefs.getInt(KEY_BRIGHTNESS, 80).coerceIn(0, 100)
        set(value) = prefs.edit().putInt(KEY_BRIGHTNESS, value.coerceIn(0, 100)).apply()

    /** 0 = off, 1 = all calls. */
    var callMode: Int
        get() = prefs.getInt(KEY_CALL, CALL_OFF)
        set(value) = prefs.edit().putInt(KEY_CALL, value).apply()

    var cameraEnabled: Boolean
        get() = prefs.getBoolean(KEY_CAMERA, false)
        set(value) = prefs.edit().putBoolean(KEY_CAMERA, value).apply()

    var notifApps: Set<String>
        get() = prefs.getStringSet(KEY_NOTIF, emptySet())!!.toSet()
        private set(value) = prefs.edit().putStringSet(KEY_NOTIF, value).apply()

    var musicApps: Set<String>
        get() = prefs.getStringSet(KEY_MUSIC, emptySet())!!.toSet()
        private set(value) = prefs.edit().putStringSet(KEY_MUSIC, value).apply()

    var gameCustom: Set<String>
        get() = prefs.getStringSet(KEY_GAME_CUSTOM, emptySet())!!.toSet()
        private set(value) = prefs.edit().putStringSet(KEY_GAME_CUSTOM, value).apply()

    fun addApp(key: String, pkg: String) {
        val cur = prefs.getStringSet(key, emptySet())!!.toMutableSet().apply { add(pkg) }
        prefs.edit().putStringSet(key, cur).apply()
    }

    fun removeApp(key: String, pkg: String) {
        val cur = prefs.getStringSet(key, emptySet())!!.toMutableSet().apply { remove(pkg) }
        prefs.edit().putStringSet(key, cur).apply()
    }

    /** Auto-detected games default to on; explicit choice wins. */
    fun isGameEnabled(pkg: String, auto: Boolean): Boolean =
        if (prefs.contains(gameKey(pkg))) prefs.getBoolean(gameKey(pkg), true) else auto

    fun setGameEnabled(pkg: String, enabled: Boolean) {
        prefs.edit().putBoolean(gameKey(pkg), enabled).apply()
    }

    /** Per-game effect color; defaults to the global color. */
    fun gameColor(pkg: String, global: Int): Int =
        prefs.getInt(gameColorKey(pkg), global)

    fun setGameColor(pkg: String, color: Int) {
        prefs.edit().putInt(gameColorKey(pkg), color and 0xFFFFFF).apply()
    }

    /** Per-game effect; defaults to breath. */
    fun gameEffect(pkg: String): Int =
        prefs.getInt(gameEffectKey(pkg), EFFECT_BREATH)

    fun setGameEffect(pkg: String, effect: Int) {
        prefs.edit().putInt(gameEffectKey(pkg), effect).apply()
    }

    fun brightnessFrac(): Float = brightness / 100f

    companion object {
        const val NAME = "backlight_effects"

        const val KEY_NOTIF = "notif_apps"
        const val KEY_MUSIC = "music_apps"
        const val KEY_GAME_CUSTOM = "game_custom"

        const val CALL_OFF = 0
        const val CALL_ALL = 1

        const val DEFAULT_COLOR = 0xFFFFFF

        const val EFFECT_STEADY = 0
        const val EFFECT_BREATH = 1
        const val EFFECT_BLINK = 2

        private const val KEY_MASTER = "master"
        private const val KEY_COLOR = "color"
        private const val KEY_BRIGHTNESS = "brightness"
        private const val KEY_CALL = "call_mode"
        private const val KEY_CAMERA = "camera"

        fun gameKey(pkg: String) = "game_$pkg"

        private fun gameColorKey(pkg: String) = "gamecolor_$pkg"

        private fun gameEffectKey(pkg: String) = "gameeffect_$pkg"
    }
}
