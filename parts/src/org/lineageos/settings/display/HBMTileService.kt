/*
 * Copyright (C) 2018 The OmniROM Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.lineageos.settings.display

import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.preference.PreferenceManager
import org.lineageos.settings.utils.FileUtils

/**
 * Quick Settings Tile Service for High Brightness Mode (HBM) control.
 * Allows users to toggle HBM on/off from Quick Settings.
 * When enabled, also sets maximum backlight and system brightness.
 */
class HBMTileService : TileService() {

    companion object {
        private const val TAG = "HBMTileService"
        private const val DEBUG = false

        // Brightness control constants
        private const val MAX_BACKLIGHT_VALUE = "2047"
        private const val MAX_SYSTEM_BRIGHTNESS = 255
    }

    private lateinit var hbmEnableKey: String
    private lateinit var hbmNode: String
    private lateinit var backlightNode: String

    override fun onCreate() {
        super.onCreate()
        initializeDisplayNodes()
    }

    /**
     * Initialize display node paths and keys.
     */
    private fun initializeDisplayNodes() {
        try {
            hbmEnableKey = DisplayNodes.HBM_ENABLE_KEY
            hbmNode = DisplayNodes.HBM_NODE
            backlightNode = DisplayNodes.BACKLIGHT

            if (DEBUG) Log.d(TAG, "Display nodes initialized: key=$hbmEnableKey, node=$hbmNode, backlight=$backlightNode")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize display nodes", e)
        }
    }

    /**
     * Update the tile's UI state.
     */
    private fun updateTileState(enabled: Boolean) {
        try {
            val tile = qsTile ?: return
            tile.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.updateTile()

            if (DEBUG) Log.d(TAG, "Tile state updated: enabled=$enabled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update tile state", e)
        }
    }

    override fun onStartListening() {
        super.onStartListening()

        try {
            val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this)
            val isEnabled = sharedPrefs.getBoolean(hbmEnableKey, false)
            updateTileState(isEnabled)

            if (DEBUG) Log.d(TAG, "Started listening, current state: $isEnabled")
        } catch (e: Exception) {
            Log.e(TAG, "Error in onStartListening", e)
        }
    }

    override fun onStopListening() {
        super.onStopListening()
        if (DEBUG) Log.d(TAG, "Stopped listening")
    }

    override fun onClick() {
        super.onClick()

        try {
            val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this)

            // Toggle the current state
            val currentlyEnabled = sharedPrefs.getBoolean(hbmEnableKey, false)
            val newState = !currentlyEnabled

            // Apply the HBM setting and related brightness controls
            val success = applyHbmSetting(newState)

            if (success) {
                // Save the new state to preferences
                sharedPrefs.edit()
                    .putBoolean(hbmEnableKey, newState)
                    .apply() // Use apply() instead of commit() for async operation

                // Update the tile UI
                updateTileState(newState)

                if (DEBUG) Log.d(TAG, "HBM toggled: $currentlyEnabled -> $newState")
            } else {
                Log.w(TAG, "Failed to apply HBM setting: $newState")
                // Could show a toast or notification here if needed
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error toggling HBM", e)
        }
    }

    /**
     * Apply the HBM setting and related brightness controls.
     *
     * @param enabled Whether HBM should be enabled
     * @return true if the setting was applied successfully
     */
    private fun applyHbmSetting(enabled: Boolean): Boolean {
        val value = if (enabled) "1" else "0"

        // Write HBM setting
        val hbmSuccess = FileUtils.writeLine(hbmNode, value)

        if (!hbmSuccess) {
            Log.w(TAG, "Failed to write HBM value $value to $hbmNode")
            return false
        }

        // Additional brightness controls when enabling HBM
        if (enabled) {
            val backlightSuccess = setMaximumBacklight()
            val systemBrightnessSuccess = setMaximumSystemBrightness()

            if (backlightSuccess && systemBrightnessSuccess) {
                if (DEBUG) Log.d(TAG, "HBM enabled with maximum brightness settings")
            } else {
                Log.w(TAG, "HBM enabled but some brightness settings failed (backlight: $backlightSuccess, system: $systemBrightnessSuccess)")
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
            Settings.System.putInt(
                contentResolver,
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