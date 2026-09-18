/*
 * SPDX-FileCopyrightText: The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.touch

import android.app.ActivityTaskManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.UserHandle
import android.provider.Settings
import android.util.Log

/**
 * Best-effort view of the framework game state, shared by the QS tile
 * and the report-rate service.
 *
 * Power HAL GAME mode is engaged whenever the focused app is a game —
 * either by manifest appCategory GAME or by user-added GameSpace entry
 * ([GAMESPACE_LIST_KEY], readable from the System table). Touchfeature
 * reads cannot answer this while HTSR is ON (forced GAME=1 looks
 * identical), so derive it from package state instead. Any failure
 * degrades to "no game".
 */
object GameState {

    private const val TAG = "GameState"
    private val DEBUG = Log.isLoggable(TAG, Log.DEBUG)

    /** System setting holding the user-added GameSpace game list. */
    private const val GAMESPACE_LIST_KEY = "gamespace_game_list"

    fun focusedPackage(context: Context): String? =
        runCatching {
            context.getSystemService(ActivityTaskManager::class.java)
                ?.getTasks(1)?.firstOrNull()?.topActivity?.packageName
        }.getOrNull()

    fun isGameActive(context: Context): Boolean {
        val pkg = focusedPackage(context) ?: return false
        val isGame = isManifestGame(context, pkg) || isGameSpaceGame(context, pkg)
        if (DEBUG) Log.d(TAG, "isGameActive($pkg)=$isGame")
        return isGame
    }

    private fun isManifestGame(context: Context, pkg: String): Boolean =
        runCatching {
            context.packageManager.getApplicationInfo(
                pkg,
                PackageManager.ApplicationInfoFlags.of(0),
            ).category == ApplicationInfo.CATEGORY_GAME
        }.getOrDefault(false)

    /**
     * User-added GameSpace entries ([GAMESPACE_LIST_KEY], e.g.
     * "com.foo.bar=1"): plain apps the user put into game mode carry no
     * GAME manifest category, so without this the tile never locks and
     * silent re-asserts would fight the HAL mid-game.
     */
    private fun isGameSpaceGame(context: Context, pkg: String): Boolean {
        val list =
            runCatching {
                Settings.System.getStringForUser(
                    context.contentResolver,
                    GAMESPACE_LIST_KEY,
                    UserHandle.USER_CURRENT,
                )
            }.getOrNull() ?: return false
        return list.split(',', ';').any { it.substringBefore('=').trim() == pkg }
    }
}
