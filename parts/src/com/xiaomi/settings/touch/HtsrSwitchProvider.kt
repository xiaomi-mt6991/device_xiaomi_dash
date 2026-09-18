/*
 * SPDX-FileCopyrightText: The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.touch

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.UserHandle
import android.provider.Settings
import android.util.Log

/**
 * Backs the inline Settings switch for forced HTSR.
 *
 * The Settings dashboard binds the row's switch widget through the
 * "com.android.settings.switch_uri" tile metadata and drives it via
 * ContentProvider.call(): "isChecked" reads state, "onCheckedChanged"
 * applies flips. State changes from any surface (row switch, row tap,
 * QS tile) notify the URIs so every surface refreshes live.
 */
class HtsrSwitchProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context ?: return null
        return when (method) {
            METHOD_IS_CHECKED -> {
                Bundle().apply { putBoolean(KEY_CHECKED, isForced(ctx)) }
            }
            METHOD_ON_CHECKED_CHANGED -> {
                applyFlip(ctx, extras?.getBoolean(KEY_CHECKED) ?: !isForced(ctx))
                Bundle()
            }
            // Fallback: the dashboard parses the method from the FIRST path
            // segment, which is our own path ("htsr") when the metadata URI
            // carries no method. Disambiguate by extras: flips always carry
            // the checked_state extra, reads carry none.
            PATH -> {
                if (extras?.containsKey(KEY_CHECKED) == true) {
                    applyFlip(ctx, extras.getBoolean(KEY_CHECKED))
                    Bundle()
                } else {
                    Bundle().apply { putBoolean(KEY_CHECKED, isForced(ctx)) }
                }
            }
            else -> null
        }
    }

    private fun applyFlip(ctx: Context, checked: Boolean) {
        if (DEBUG) Log.d(TAG, "flip: $checked")
        Settings.System.putIntForUser(
            ctx.contentResolver,
            TouchReportRateService.SETTING_KEY,
            if (checked) 1 else 0,
            UserHandle.USER_CURRENT,
        )
        // force = true: user just flipped the inline switch — apply now even
        // when the persistent-across-reboot toggle is off, since this is an
        // explicit one-shot they asked for.
        TouchReportRateService.applyReportRate(ctx, force = true)
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?,
    ): Int = 0

    companion object {
        private const val TAG = "HtsrSwitch"
        private val DEBUG = Log.isLoggable(TAG, Log.DEBUG)

        const val AUTHORITY = "com.xiaomi.settings.touch"
        const val PATH = "htsr"
        val BASE_URI: Uri = Uri.parse("content://$AUTHORITY/$PATH")

        private const val METHOD_IS_CHECKED = "isChecked"
        private const val METHOD_ON_CHECKED_CHANGED = "onCheckedChanged"
        private const val KEY_CHECKED = "checked_state"

        private fun isForced(ctx: Context): Boolean =
            Settings.System.getIntForUser(
                ctx.contentResolver,
                TouchReportRateService.SETTING_KEY,
                1,
                UserHandle.USER_CURRENT,
            ) == 1

        fun notifyChanged(ctx: Context) {
            ctx.contentResolver.notifyChange(BASE_URI, null)
            ctx.contentResolver.notifyChange(
                Uri.withAppendedPath(BASE_URI, METHOD_IS_CHECKED),
                null,
            )
            ctx.contentResolver.notifyChange(
                Uri.withAppendedPath(BASE_URI, METHOD_ON_CHECKED_CHANGED),
                null,
            )
        }
    }
}
