/*
 * Copyright (C) 2021 crDroid Android Project
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

import android.content.Context
import android.database.ContentObserver
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import android.view.Display
import java.util.ArrayList
import java.util.Locale
import java.util.concurrent.Executors

import org.lineageos.settings.refreshrate.RefreshUtils // <-- 1. ADD THIS IMPORT

class RefreshRateTileService : TileService() {

    private lateinit var context: Context
    private lateinit var tile: Tile
    private lateinit var mRefreshUtils: RefreshUtils // <-- 2. ADD THIS LINE

    private val availableRates = ArrayList<Float>()
    @Volatile private var currentMode: Int = MODE_DYNAMIC
    private val tileExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val applyRunnable = Runnable { applyRefreshRateChanges() }

    private val settingsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            super.onChange(selfChange, uri)
            Log.d(TAG, "Settings changed, syncing tile")
            syncFromSettings()
            updateTileView()

            // --- START FIX ---
            // When settings change (e.g., from the main Settings page),
            // we must also update the "global default" saved in SharedPreferences.
            // This keeps the tile, settings page, and per-app service in sync.
            tileExecutor.execute {
                val minRate = getSettingOf(KEY_MIN_REFRESH_RATE)
                val peakRate = getSettingOf(KEY_PEAK_REFRESH_RATE)
                Log.d(TAG, "SettingsObserver: saving new global rate min=$minRate, max=$peakRate")
                mRefreshUtils.saveGlobalRate(minRate, peakRate)
            }
            // --- END FIX ---
        }
    }

    override fun onCreate() {
        super.onCreate()
        context = applicationContext
        mRefreshUtils = RefreshUtils(context) // <-- 3. ADD THIS LINE
        val mode = context.display!!.mode
        val modes = context.display!!.supportedModes
        for (m in modes) {
            val rate = String.format(Locale.US, "%.02f", m.refreshRate).toFloat()
            if (m.physicalWidth == mode.physicalWidth &&
                m.physicalHeight == mode.physicalHeight
            ) {
                availableRates.add(rate)
            }
        }
        availableRates.sort()
        Log.d(TAG, "Available refresh rates: $availableRates")
        syncFromSettings()
    }

    private fun getSettingOf(key: String): Float {
        return Settings.System.getFloat(context.contentResolver, key, 60f)
    }

    private fun syncFromSettings() {
        val minRate = getSettingOf(KEY_MIN_REFRESH_RATE)
        val peakRate = getSettingOf(KEY_PEAK_REFRESH_RATE)
        Log.d(TAG, "syncFromSettings: minRate=$minRate, peakRate=$peakRate")
        
        currentMode = when {
            // DYNAMIC: has a range (min < max)
            minRate < 100f && peakRate >= 120f -> {
                Log.d(TAG, "Detected DYNAMIC mode (60-120Hz)")
                MODE_DYNAMIC
            }
            // 120HZ: locked to 120
            minRate >= 100f && peakRate >= 100f -> {
                Log.d(TAG, "Detected 120HZ mode")
                MODE_120HZ
            }
            // 60HZ: locked to 60
            minRate <= 60f && peakRate <= 60f -> {
                Log.d(TAG, "Detected 60HZ mode")
                MODE_60HZ
            }
            else -> {
                Log.d(TAG, "Unknown mode combination, defaulting to DYNAMIC")
                MODE_DYNAMIC
            }
        }
        Log.d(TAG, "Current mode: ${modeLabel(currentMode)}")
    }

    private fun cycleRefreshRateImmediate() {
        currentMode = when (currentMode) {
            MODE_DYNAMIC -> MODE_60HZ
            MODE_60HZ -> MODE_120HZ
            MODE_120HZ -> MODE_DYNAMIC
            else -> MODE_DYNAMIC
        }
        Log.d(TAG, "Cycled to mode: ${modeLabel(currentMode)}")
    }

    private fun applyRefreshRateChanges() {
        tileExecutor.execute {
            val minRate: Float
            val maxRate: Float
            
            when (currentMode) {
                MODE_DYNAMIC -> {
                    minRate = 60f
                    maxRate = 120f
                    Log.d(TAG, "Applying DYNAMIC mode: 60-120Hz")
                }
                MODE_60HZ -> {
                    minRate = 60f
                    maxRate = 60f
                    Log.d(TAG, "Applying 60HZ mode: locked to 60Hz")
                }
                MODE_120HZ -> {
                    minRate = 120f
                    maxRate = 120f
                    Log.d(TAG, "Applying 120HZ mode: locked to 120Hz")
                }
                else -> {
                    minRate = 60f
                    maxRate = 120f
                }
            }
            
            // Apply to system settings
            Settings.System.putFloat(context.contentResolver, KEY_MIN_REFRESH_RATE, minRate)
            Settings.System.putFloat(context.contentResolver, KEY_PREFERRED_REFRESH_RATE, maxRate)
            Settings.System.putFloat(context.contentResolver, KEY_PEAK_REFRESH_RATE, maxRate)
            
            // Save as global default for restoring when app is not in per-app list
            org.lineageos.settings.refreshrate.RefreshUtils(context).saveGlobalRate(minRate, maxRate)
            
            Log.d(TAG, "Applied: min=$minRate max=$maxRate and saved as global")
        }
    }

    private fun getFormatRate(rate: Float): String {
        return String.format("%.02f Hz", rate)
            .replace(Regex("[\\.,]00"), "")
    }

    private fun modeLabel(mode: Int): String {
        return when (mode) {
            MODE_DYNAMIC -> "DYNAMIC"
            MODE_60HZ -> "60HZ"
            MODE_120HZ -> "120HZ"
            else -> "UNKNOWN"
        }
    }

    private fun updateTileView() {
        val displayText: String
        
        when (currentMode) {
            MODE_DYNAMIC -> {
                displayText = "Dynamic"
                tile.icon = android.graphics.drawable.Icon.createWithResource(context, org.lineageos.settings.R.drawable.ic_qs_refresh_rate)
                Log.d(TAG, "Tile icon set to DYNAMIC (ic_refresh_default)")
            }
            MODE_60HZ -> {
                displayText = "60 Hz"
                tile.icon = android.graphics.drawable.Icon.createWithResource(context, org.lineageos.settings.R.drawable.ic_refresh_60)
                Log.d(TAG, "Tile icon set to 60HZ (ic_refresh_60)")
            }
            MODE_120HZ -> {
                displayText = "120 Hz"
                tile.icon = android.graphics.drawable.Icon.createWithResource(context, org.lineageos.settings.R.drawable.ic_refresh_120)
                Log.d(TAG, "Tile icon set to 120HZ (ic_refresh_120)")
            }
            else -> {
                displayText = "Unknown"
                tile.icon = android.graphics.drawable.Icon.createWithResource(context, org.lineageos.settings.R.drawable.ic_refresh_default)
            }
        }

        tile.label = getString(org.lineageos.settings.R.string.refresh_rate_tile_title)
        tile.contentDescription = displayText
        tile.subtitle = displayText
        tile.state = Tile.STATE_ACTIVE
        tile.updateTile()
        Log.d(TAG, "Tile updated: $displayText")
    }

    override fun onStartListening() {
        super.onStartListening()
        tile = qsTile
        syncFromSettings()
        updateTileView()
        
        // Register content observer to sync with settings changes
        context.contentResolver.registerContentObserver(
            Settings.System.getUriFor(KEY_MIN_REFRESH_RATE), false, settingsObserver)
        context.contentResolver.registerContentObserver(
            Settings.System.getUriFor(KEY_PEAK_REFRESH_RATE), false, settingsObserver)
    }

    override fun onStopListening() {
        super.onStopListening()
        // Unregister content observer
        context.contentResolver.unregisterContentObserver(settingsObserver)
    }

    override fun onClick() {
        super.onClick()
        Log.d(TAG, "Tile clicked - cycling refresh rate")
        
        // Update UI immediately for responsive feedback
        cycleRefreshRateImmediate()
        updateTileView()
        
        // Apply changes after 1 second delay on background thread
        mainHandler.removeCallbacks(applyRunnable)
        mainHandler.postDelayed(applyRunnable, APPLY_DELAY_MS)
    }

    companion object {
        private const val KEY_MIN_REFRESH_RATE = "min_refresh_rate"
        private const val KEY_PREFERRED_REFRESH_RATE = "preferred_refresh_rate"
        private const val KEY_PEAK_REFRESH_RATE = "peak_refresh_rate"
        private const val TAG = "RefreshRateTileService"
        private const val DEBUG = true
        private const val APPLY_DELAY_MS = 1000L  // 1 second delay before applying changes
        
        // Tile modes
        private const val MODE_DYNAMIC = 0
        private const val MODE_60HZ = 1
        private const val MODE_120HZ = 2
    }
}
