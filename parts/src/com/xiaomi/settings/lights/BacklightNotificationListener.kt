/*
 * SPDX-FileCopyrightText: The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.lights

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/** Pokes [BacklightService] for a one-shot pulse on enabled apps' posts. */
class BacklightNotificationListener : NotificationListenerService() {

    private val prefs by lazy { BacklightPrefs(this) }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val pkg = sbn?.packageName ?: return
        if (sbn.isOngoing) return
        if (pkg == packageName) return
        if (!prefs.master || !prefs.notifApps.contains(pkg)) return
        if (DEBUG) Log.d(TAG, "posted: $pkg")
        BacklightService.notifyPosted(this, pkg)
    }

    companion object {
        private const val TAG = "BacklightNotif"
        private val DEBUG = Log.isLoggable(TAG, Log.DEBUG)
    }
}
