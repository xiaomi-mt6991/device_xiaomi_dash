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
 * Exposes forced HTSR as a pure inline switch (Settings Injection v2).
 *
 * This is a [ProviderTile], not an [android.app.Activity] tile, so the
 * dashboard renders a plain [androidx.preference.SwitchPreferenceCompat]
 * with no chevron or divider — like the neighboring toggles. The wire
 * protocol mirrors AOSP SettingsLib (getEntryData / isChecked /
 * onCheckedChanged) by hand so no SettingsLib drawer dependency is needed.
 *
 * Persistence across reboot and screen-off is always on (no toggle):
 * [TouchReportRateService] restores on boot and screen-on.
 */
class HtsrSwitchProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        // Settings' DashboardFeatureProviderImpl dereferences the
        // onCheckedChanged/isChecked result without a null check
        // (bundle.getBoolean on the return). A null return — e.g. a flip
        // landing while our persistent process restarts — crashes
        // com.android.settings and kicks the user back to the top level.
        // Those two methods therefore never return null; failure is
        // signaled via set_checked_error instead.
        if (method == METHOD_ON_CHECKED_CHANGED || method == METHOD_IS_CHECKED) {
            val ctx = context ?: return checkedError()
            val key = extras?.getString(KEY_HINT)
            if (!key.isNullOrEmpty() && key != ENTRY_KEY) return checkedError()
            return when (method) {
                METHOD_IS_CHECKED ->
                    Bundle().apply { putBoolean(EXTRA_CHECKED, isForced(ctx)) }
                else -> {
                    val ok =
                        runCatching {
                            applyFlip(ctx, extras?.getBoolean(EXTRA_CHECKED) ?: !isForced(ctx))
                        }.onFailure { e -> Log.w(TAG, "flip failed", e) }.isSuccess
                    Bundle().apply { putBoolean(EXTRA_ERROR, !ok) }
                }
            }
        }
        val ctx = context ?: return null
        // Settings omits the keyhint on the list call; keyed calls carry it.
        val key = extras?.getString(KEY_HINT)
        if (!key.isNullOrEmpty() && key != ENTRY_KEY) return null
        return when (method) {
            METHOD_GET_ENTRY_DATA ->
                if (key.isNullOrEmpty()) {
                    Bundle().apply {
                        putParcelableArrayList(EXTRA_ENTRY_DATA, arrayListOf(entryBundle(ctx)))
                    }
                } else {
                    entryBundle(ctx)
                }
            // Legacy fallback, kept since TileUtils queries it when
            // getEntryData returns null.
            METHOD_GET_SWITCH_DATA ->
                if (key.isNullOrEmpty()) {
                    Bundle().apply {
                        putParcelableArrayList(EXTRA_SWITCH_DATA, arrayListOf(entryBundle(ctx)))
                    }
                } else {
                    entryBundle(ctx)
                }
            else -> null
        }
    }

    private fun entryBundle(ctx: Context): Bundle =
        Bundle().apply {
            putString(EXTRA_CATEGORY, CATEGORY_DISPLAY)
            putInt(META_ORDER, ORDER)
            putString(KEY_HINT, ENTRY_KEY)
            putInt(META_TITLE, com.xiaomi.settings.R.string.touch_title)
            putInt(META_SUMMARY, com.xiaomi.settings.R.string.touch_row_summary)
            putString(META_SWITCH_URI, BASE_URI.toString())
        }

    private fun applyFlip(ctx: Context, checked: Boolean) {
        if (DEBUG) Log.d(TAG, "flip: $checked")
        Settings.System.putIntForUser(
            ctx.contentResolver,
            TouchReportRateService.SETTING_KEY,
            if (checked) 1 else 0,
            UserHandle.USER_CURRENT,
        )
        // User just flipped the switch — apply immediately.
        TouchReportRateService.applyReportRate(ctx)
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
        const val ENTRY_KEY = "htsr"
        val BASE_URI: Uri = Uri.parse("content://$AUTHORITY")

        private const val CATEGORY_DISPLAY = "com.android.settings.category.ia.display"
        private const val ORDER = -10

        // SettingsLib TileUtils / EntriesProvider wire strings.
        private const val METHOD_GET_ENTRY_DATA = "getEntryData"
        private const val METHOD_GET_SWITCH_DATA = "getSwitchData"
        private const val METHOD_IS_CHECKED = "isChecked"
        private const val METHOD_ON_CHECKED_CHANGED = "onCheckedChanged"
        private const val EXTRA_ENTRY_DATA = "entry_data"
        private const val EXTRA_SWITCH_DATA = "switch_data"
        private const val EXTRA_CHECKED = "checked_state"
        private const val EXTRA_ERROR = "set_checked_error"
        private const val KEY_HINT = "com.android.settings.keyhint"
        private const val EXTRA_CATEGORY = "com.android.settings.category"
        private const val META_ORDER = "com.android.settings.order"
        private const val META_TITLE = "com.android.settings.title"
        private const val META_SUMMARY = "com.android.settings.summary"
        private const val META_SWITCH_URI = "com.android.settings.switch_uri"

        private fun isForced(ctx: Context): Boolean =
            Settings.System.getIntForUser(
                ctx.contentResolver,
                TouchReportRateService.SETTING_KEY,
                1,
                UserHandle.USER_CURRENT,
            ) == 1

        private fun checkedError(): Bundle = Bundle().apply { putBoolean(EXTRA_ERROR, true) }

        @Volatile
        private var lastNotifiedChecked: Boolean? = null

        fun notifyChanged(ctx: Context) {
            // Only the isChecked URI has a registered observer in Settings
            // (refreshSwitch → setSwitchChecked, no row rebuild). Entry data
            // is static; notifying it would rebind the whole row and blink.
            // Dedupe: a flip fires putInt (service observer) + direct apply;
            // without this the row refreshes twice and flickers.
            val checked = isForced(ctx)
            if (lastNotifiedChecked == checked) return
            lastNotifiedChecked = checked
            ctx.contentResolver.notifyChange(
                BASE_URI.buildUpon()
                    .appendPath(METHOD_IS_CHECKED)
                    .appendPath(ENTRY_KEY)
                    .build(),
                null,
            )
        }
    }
}
