/*
 * Copyright (C) 2015 The CyanogenMod Project
 *               2017-2020 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.lineageos.settings

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.preference.PreferenceManager
import org.lineageos.settings.refreshrate.RefreshUtils
import org.lineageos.settings.thermal.ThermalUtils
import org.lineageos.settings.touchsampling.TouchSamplingUtils
import org.lineageos.settings.utils.FileUtils

/**
 * BroadcastReceiver that handles device boot completion.
 * Initializes system services and applies saved user preferences.
 */
class BootCompletedReceiver : BroadcastReceiver() {

    companion object {
        private const val DEBUG = false
        private const val TAG = "XiaomiParts"

        // Shared preference keys
        private const val DC_DIMMING_ENABLE_KEY = "dc_dimming_enable"
        private const val HBM_ENABLE_KEY = "hbm_mode"

        // System file paths
        private const val DC_DIMMING_NODE = "/sys/devices/platform/soc/soc:qcom,dsi-display-primary/msm_fb_ea_enable"
        private const val HBM_NODE = "/sys/devices/platform/soc/soc:qcom,dsi-display-primary/hbm"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null) {
            Log.e(TAG, "Received boot completed intent with null context")
            return
        }

        if (DEBUG) Log.d(TAG, "Received boot completed intent")

        try {
            // Initialize system services
            initializeSystemServices(context)

            // Apply user preferences
            applyUserPreferences(context)

        } catch (e: Exception) {
            Log.e(TAG, "Error during boot completion initialization", e)
        }
    }

    /**
     * Initialize all system services that need to start on boot.
     */
    private fun initializeSystemServices(context: Context) {
        try {
            ThermalUtils.startService(context)
            if (DEBUG) Log.d(TAG, "Thermal service started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start thermal service", e)
        }

        try {
            RefreshUtils.startService(context)
            if (DEBUG) Log.d(TAG, "Refresh rate service started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start refresh rate service", e)
        }

        try {
            TouchSamplingUtils.restoreSamplingValue(context)
            if (DEBUG) Log.d(TAG, "Touch sampling value restored")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore touch sampling value", e)
        }
    }

    /**
     * Apply user preferences for display settings.
     */
    private fun applyUserPreferences(context: Context) {
        val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(context)

        // Apply DC Dimming setting
        val dcDimmingEnabled = sharedPrefs.getBoolean(DC_DIMMING_ENABLE_KEY, false)
        if (applyDisplaySetting(DC_DIMMING_NODE, dcDimmingEnabled, "DC Dimming")) {
            if (DEBUG) Log.d(TAG, "DC Dimming set to $dcDimmingEnabled")
        }

        // Apply HBM setting
        val hbmEnabled = sharedPrefs.getBoolean(HBM_ENABLE_KEY, false)
        if (applyDisplaySetting(HBM_NODE, hbmEnabled, "HBM")) {
            if (DEBUG) Log.d(TAG, "HBM set to $hbmEnabled")
        }
    }

    /**
     * Apply a display setting by writing to a system node.
     *
     * @param nodePath The system file path to write to
     * @param enabled Whether the setting should be enabled
     * @param settingName Human-readable name for logging
     * @return true if the setting was applied successfully
     */
    private fun applyDisplaySetting(nodePath: String, enabled: Boolean, settingName: String): Boolean {
        val value = if (enabled) "1" else "0"

        return if (FileUtils.writeLine(nodePath, value)) {
            if (DEBUG) Log.d(TAG, "$settingName setting applied: $value to $nodePath")
            true
        } else {
            Log.w(TAG, "Failed to apply $settingName setting: $value to $nodePath")
            false
        }
    }
}