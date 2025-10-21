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

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.preference.PreferenceManager
import org.lineageos.settings.utils.FileUtils

/**
 * Quick Settings Tile Service for DC Dimming control.
 * Allows users to toggle DC Dimming on/off from Quick Settings.
 */
class DcDimmingTileService : TileService() {

    companion object {
        private const val TAG = "DcDimmingTileService"
        private const val DEBUG = false
    }

    private lateinit var dcDimmingEnableKey: String
    private lateinit var dcDimmingNode: String

    override fun onCreate() {
        super.onCreate()
        initializeDisplayNodes()
    }

    /**
     * Initialize display node paths and keys.
     */
    private fun initializeDisplayNodes() {
        try {
            dcDimmingEnableKey = DisplayNodes.getDcDimmingEnableKey()
            dcDimmingNode = DisplayNodes.getDcDimmingNode()
            if (DEBUG) Log.d(TAG, "Display nodes initialized: key=$dcDimmingEnableKey, node=$dcDimmingNode")
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
            val isEnabled = sharedPrefs.getBoolean(dcDimmingEnableKey, false)
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
            val currentlyEnabled = sharedPrefs.getBoolean(dcDimmingEnableKey, false)
            val newState = !currentlyEnabled

            // Apply the setting to the system
            val success = applyDcDimmingSetting(newState)

            if (success) {
                // Save the new state to preferences
                sharedPrefs.edit()
                    .putBoolean(dcDimmingEnableKey, newState)
                    .apply() // Use apply() instead of commit() for async operation

                // Update the tile UI
                updateTileState(newState)

                if (DEBUG) Log.d(TAG, "DC Dimming toggled: $currentlyEnabled -> $newState")
            } else {
                Log.w(TAG, "Failed to apply DC Dimming setting: $newState")
                // Could show a toast or notification here if needed
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error toggling DC Dimming", e)
        }
    }

    /**
     * Apply the DC Dimming setting to the system.
     *
     * @param enabled Whether DC Dimming should be enabled
     * @return true if the setting was applied successfully
     */
    private fun applyDcDimmingSetting(enabled: Boolean): Boolean {
        val value = if (enabled) "1" else "0"

        return if (FileUtils.writeLine(dcDimmingNode, value)) {
            if (DEBUG) Log.d(TAG, "DC Dimming setting applied: $value to $dcDimmingNode")
            true
        } else {
            Log.w(TAG, "Failed to write DC Dimming value $value to $dcDimmingNode")
            false
        }
    }
}