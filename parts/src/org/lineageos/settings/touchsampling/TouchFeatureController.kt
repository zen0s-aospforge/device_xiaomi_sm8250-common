/*
 * Copyright (C) 2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.touchsampling

import android.os.IBinder
import android.os.RemoteException
import android.os.ServiceManager
import android.util.Log
import org.lineageos.settings.utils.FileUtils
import vendor.xiaomi.hw.touchfeature.ITouchFeature

object TouchFeatureController {
    private const val TAG = "TouchFeatureController"

    // Service name for Xiaomi TouchFeature AIDL
    private const val SERVICE_NAME = "vendor.xiaomi.hw.touchfeature.ITouchFeature/default"

    // Touch ID is usually 0 for primary panel
    private const val TOUCH_ID = 0

    // Mode for High Touch Sampling Rate. This may vary per device. Allow override via property.
    // If not provided, we skip AIDL path and rely on sysfs fallback to preserve behavior.
    private val MODE_HIGH_TOUCH_SAMPLING: Int? = runCatching {
        // Read from system properties if available via reflection to avoid platform SDK dependency
        val sysProps = Class.forName("android.os.SystemProperties")
        val getInt = sysProps.getMethod("getInt", String::class.java, Int::class.javaPrimitiveType)
        val value = getInt.invoke(null, "persist.vendor.touch.htpr_mode", -1) as Int
        if (value >= 0) value else null
    }.getOrNull()

    // Sysfs fallback path retained from existing implementation
    private const val SYSFS_HTSR = TouchSamplingUtils.HTSR_FILE

    private var service: ITouchFeature? = null

    @Synchronized
    private fun ensureService(): ITouchFeature? {
        if (service != null) return service
        return try {
            // Optimized: Use getService instead of waitForDeclaredService to prevent blocking
            val binder: IBinder? = ServiceManager.getService(SERVICE_NAME)
            if (binder == null) {
                Log.w(TAG, "TouchFeature service not found: $SERVICE_NAME")
                null
            } else {
                service = ITouchFeature.Stub.asInterface(binder)
                service
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to get TouchFeature service", t)
            null
        }
    }

    fun setHighTouchSamplingEnabled(enabled: Boolean) {
        var aidlSucceeded = false
        val mode = MODE_HIGH_TOUCH_SAMPLING
        if (mode != null) {
            try {
                ensureService()?.let { svc ->
                    svc.setTouchMode(TOUCH_ID, mode, if (enabled) 1 else 0)
                    aidlSucceeded = true
                    Log.d(TAG, "AIDL setTouchMode(mode=$mode) -> ${if (enabled) 1 else 0}")
                }
            } catch (e: RemoteException) {
                Log.w(TAG, "Remote exception setting high touch sampling via AIDL", e)
            } catch (t: Throwable) {
                Log.w(TAG, "Error setting high touch sampling via AIDL", t)
            }
        } else {
            Log.d(TAG, "No mode specified for AIDL; skipping ITouchFeature path")
        }

        // Always attempt sysfs fallback to preserve current behavior
        val desired = if (enabled) "1" else "0"
        val current = FileUtils.readOneLine(SYSFS_HTSR)
        if (current == null || current != desired) {
            Log.d(TAG, "Sysfs apply HTSR: $desired (AIDL ${if (aidlSucceeded) "ok" else "skipped/failed"})")
            FileUtils.writeLine(SYSFS_HTSR, desired)
        }
    }
}
