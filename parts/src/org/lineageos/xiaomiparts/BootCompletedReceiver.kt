/*
 * SPDX-FileCopyrightText: 2018 The LineageOS Project
 * SPDX-FileCopyrightText: 2025 Paranoid Android
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.xiaomiparts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.lineageos.xiaomiparts.camera.NfcCameraService
import org.lineageos.xiaomiparts.display.ColorService
import org.lineageos.xiaomiparts.display.DcDimmingService
import org.lineageos.xiaomiparts.doze.PocketService
import org.lineageos.xiaomiparts.gestures.GestureUtils
import org.lineageos.xiaomiparts.thermal.ThermalUtils
import org.lineageos.xiaomiparts.touch.HighTouchPollingService
import org.lineageos.xiaomiparts.touch.TouchOrientationService

/** Everything begins at boot. */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Received intent: ${intent.action}")
        if (intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return

        Log.i(TAG, "Boot completed, starting services")
        ColorService.startService(context)
        DcDimmingService.startService(context)
        PocketService.startService(context)
        NfcCameraService.startService(context)
        TouchOrientationService.startService(context)
        HighTouchPollingService.startService(context)
        ThermalUtils.getInstance(context).startService()
        GestureUtils.onBootCompleted(context)
    }

    companion object {
        private const val TAG = "XiaomiParts-BCR"
    }
}
