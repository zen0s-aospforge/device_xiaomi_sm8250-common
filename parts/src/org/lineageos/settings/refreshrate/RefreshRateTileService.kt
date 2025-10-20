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

class RefreshRateTileService : TileService() {

    private lateinit var context: Context
    private lateinit var tile: Tile

    private val availableRates = ArrayList<Float>()
    @Volatile private var activeRateMin: Int = 0
    @Volatile private var activeRateMax: Int = 0
    private val tileExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val applyRunnable = Runnable { applyRefreshRateChanges() }

    override fun onCreate() {
        super.onCreate()
        context = applicationContext
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
        syncFromSettings()
    }

    private fun getSettingOf(key: String): Int {
        val rate = Settings.System.getFloat(context.contentResolver, key, 60f)
        return availableRates.indexOf(
            String.format(Locale.US, "%.02f", rate).toFloat()
        )
    }

    private fun syncFromSettings() {
        activeRateMin = getSettingOf(KEY_MIN_REFRESH_RATE)
        activeRateMax = getSettingOf(KEY_PEAK_REFRESH_RATE)
    }

    private fun cycleRefreshRateImmediate() {
        if (activeRateMin < availableRates.size - 1) {
            activeRateMin++
        } else {
            activeRateMin = 0
        }
    }

    private fun applyRefreshRateChanges() {
        tileExecutor.execute {
            val rate = availableRates[activeRateMin]
            Log.d(TAG, "Applying refresh rate: $rate Hz")
            Settings.System.putFloat(context.contentResolver, KEY_MIN_REFRESH_RATE, rate)
            Settings.System.putFloat(context.contentResolver, KEY_PREFERRED_REFRESH_RATE, rate)
            Settings.System.putFloat(context.contentResolver, KEY_PEAK_REFRESH_RATE, rate)
        }
    }

    private fun getFormatRate(rate: Float): String {
        return String.format("%.02f Hz", rate)
            .replace(Regex("[\\.,]00"), "")
    }

    private fun updateTileView() {
        val displayText: String
        val min = availableRates[activeRateMin]
        val max = availableRates[activeRateMax]

        displayText = if (min == max) {
            getFormatRate(min)
        } else {
            "${getFormatRate(min)} - ${getFormatRate(max)}"
        }

        tile.contentDescription = displayText
        tile.subtitle = displayText
        tile.state = if (min == max) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }

    override fun onStartListening() {
        super.onStartListening()
        tile = qsTile
        syncFromSettings()
        updateTileView()
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
        private const val DEBUG = false
        private const val APPLY_DELAY_MS = 1000L  // 1 second delay before applying changes
    }
}
