/*
 * Copyright (C) 2018 The LineageOS Project
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

package org.lineageos.settings.display

import android.content.Context
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import org.lineageos.settings.R
import org.lineageos.settings.utils.FileUtils

/**
 * Fragment for display settings including DC Dimming and High Brightness Mode controls.
 * Provides preference-based controls for display hardware features.
 */
class DisplaySettingsFragment : PreferenceFragmentCompat(), Preference.OnPreferenceChangeListener {

    companion object {
        private const val TAG = "DisplaySettingsFragment"
        private const val DEBUG = false

        // Display control constants
        private const val MAX_BACKLIGHT_VALUE = "2047"
        private const val MAX_SYSTEM_BRIGHTNESS = 255
    }

    // Preference references
    private var dcDimmingPreference: SwitchPreferenceCompat? = null
    private var hbmPreference: SwitchPreferenceCompat? = null

    // System node paths
    private lateinit var dcDimmingNode: String
    private lateinit var hbmNode: String
    private lateinit var backlightNode: String

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        try {
            // Initialize system node paths
            initializeSystemNodes()

            // Load preferences from XML
            addPreferencesFromResource(R.xml.display_settings)

            // Setup DC Dimming preference
            setupDcDimmingPreference()

            // Setup HBM preference
            setupHbmPreference()

            if (DEBUG) Log.d(TAG, "Display preferences initialized successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize display preferences", e)
        }
    }

    /**
     * Initialize system node paths from DisplayNodes.
     */
    private fun initializeSystemNodes() {
        dcDimmingNode = DisplayNodes.DC_DIMMING_NODE
        hbmNode = DisplayNodes.HBM_NODE
        backlightNode = DisplayNodes.BACKLIGHT

        if (DEBUG) {
            Log.d(TAG, "System nodes initialized: dc=$dcDimmingNode, hbm=$hbmNode, backlight=$backlightNode")
        }
    }

    /**
     * Setup DC Dimming preference with availability checking.
     */
    private fun setupDcDimmingPreference() {
        dcDimmingPreference = findPreference(DisplayNodes.DC_DIMMING_ENABLE_KEY)

        dcDimmingPreference?.let { preference ->
            if (FileUtils.fileExists(dcDimmingNode)) {
                preference.isEnabled = true
                preference.onPreferenceChangeListener = this
                if (DEBUG) Log.d(TAG, "DC Dimming preference enabled")
            } else {
                preference.setSummary(R.string.dc_dimming_enable_summary_not_supported)
                preference.isEnabled = false
                Log.w(TAG, "DC Dimming not supported - system node not found: $dcDimmingNode")
            }
        } ?: Log.e(TAG, "DC Dimming preference not found in XML")
    }

    /**
     * Setup HBM preference with availability checking.
     */
    private fun setupHbmPreference() {
        hbmPreference = findPreference(DisplayNodes.HBM_ENABLE_KEY)

        hbmPreference?.let { preference ->
            if (FileUtils.fileExists(hbmNode)) {
                preference.isEnabled = true
                preference.onPreferenceChangeListener = this
                if (DEBUG) Log.d(TAG, "HBM preference enabled")
            } else {
                preference.setSummary(R.string.hbm_enable_summary_not_supported)
                preference.isEnabled = false
                Log.w(TAG, "HBM not supported - system node not found: $hbmNode")
            }
        } ?: Log.e(TAG, "HBM preference not found in XML")
    }

    override fun onPreferenceChange(preference: Preference, newValue: Any?): Boolean {
        if (newValue !is Boolean) {
            Log.w(TAG, "Invalid preference value type: ${newValue?.javaClass?.simpleName}")
            return false
        }

        val enabled = newValue

        return when (preference.key) {
            DisplayNodes.DC_DIMMING_ENABLE_KEY -> {
                handleDcDimmingChange(enabled)
            }
            DisplayNodes.HBM_ENABLE_KEY -> {
                handleHbmChange(enabled)
            }
            else -> {
                Log.w(TAG, "Unknown preference key: ${preference.key}")
                false
            }
        }
    }

    /**
     * Handle DC Dimming preference change.
     */
    private fun handleDcDimmingChange(enabled: Boolean): Boolean {
        val value = if (enabled) "1" else "0"

        return if (FileUtils.writeLine(dcDimmingNode, value)) {
            if (DEBUG) Log.d(TAG, "DC Dimming set to $enabled ($value)")
            true
        } else {
            Log.e(TAG, "Failed to write DC Dimming value $value to $dcDimmingNode")
            false
        }
    }

    /**
     * Handle HBM preference change with additional brightness controls.
     */
    private fun handleHbmChange(enabled: Boolean): Boolean {
        val value = if (enabled) "1" else "0"

        // Write HBM setting
        val hbmSuccess = FileUtils.writeLine(hbmNode, value)

        if (!hbmSuccess) {
            Log.e(TAG, "Failed to write HBM value $value to $hbmNode")
            return false
        }

        // Additional brightness controls when enabling HBM
        if (enabled) {
            val backlightSuccess = setMaximumBacklight()
            val systemBrightnessSuccess = setMaximumSystemBrightness()

            if (backlightSuccess && systemBrightnessSuccess) {
                if (DEBUG) Log.d(TAG, "HBM enabled with maximum brightness settings")
            } else {
                Log.w(TAG, "HBM enabled but some brightness settings failed")
            }
        } else {
            if (DEBUG) Log.d(TAG, "HBM disabled")
        }

        return true
    }

    /**
     * Set backlight to maximum value for HBM.
     */
    private fun setMaximumBacklight(): Boolean {
        return if (FileUtils.writeLine(backlightNode, MAX_BACKLIGHT_VALUE)) {
            if (DEBUG) Log.d(TAG, "Backlight set to maximum: $MAX_BACKLIGHT_VALUE")
            true
        } else {
            Log.w(TAG, "Failed to set maximum backlight to $backlightNode")
            false
        }
    }

    /**
     * Set system screen brightness to maximum for HBM.
     */
    private fun setMaximumSystemBrightness(): Boolean {
        return try {
            val context = requireContext()
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                MAX_SYSTEM_BRIGHTNESS
            )
            if (DEBUG) Log.d(TAG, "System brightness set to maximum: $MAX_SYSTEM_BRIGHTNESS")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set system brightness", e)
            false
        }
    }
}