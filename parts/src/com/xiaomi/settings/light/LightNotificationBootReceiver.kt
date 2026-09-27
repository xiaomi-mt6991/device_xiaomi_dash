/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.light

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

class LightNotificationBootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "LightNotificationBootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (Intent.ACTION_BOOT_COMPLETED == action || Intent.ACTION_LOCKED_BOOT_COMPLETED == action) {
            grantListenerAccess(context)
        }
    }

    private fun grantListenerAccess(context: Context) {
        val component = ComponentName(context, LightNotificationService::class.java)
        try {
            val nm = context.getSystemService(NotificationManager::class.java)
            val method = NotificationManager::class.java.getMethod(
                "setNotificationListenerAccessGranted",
                ComponentName::class.java,
                Boolean::class.javaPrimitiveType,
            )
            method.invoke(nm, component, true)
            Log.d(TAG, "Granted notification listener access for $component")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to grant notification listener access: ${e.message}")
        }
    }
}
