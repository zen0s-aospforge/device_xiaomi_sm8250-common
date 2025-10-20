/*
 * Copyright (C) 2020 The LineageOS Project
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

package org.lineageos.settings.refreshrate

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import androidx.preference.PreferenceManager

class RefreshUtils(context: Context) {

    private val mContext = context
    private val mSharedPrefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

    companion object {
        private const val REFRESH_CONTROL = "refresh_control"
        private const val KEY_PEAK_REFRESH_RATE = "peak_refresh_rate"
        private const val KEY_MIN_REFRESH_RATE = "min_refresh_rate"
        private const val TAG = "RefreshUtils"

        const val STATE_DEFAULT = 0
        const val STATE_STANDARD = 1
        const val STATE_EXTREME = 2

        private const val REFRESH_STATE_DEFAULT = 120f
        private const val REFRESH_STATE_STANDARD = 60f
        private const val REFRESH_STATE_EXTREME = 120f

        private const val REFRESH_STANDARD = "refresh.standard="
        private const val REFRESH_EXTREME = "refresh.extreme="

        var defaultMaxRate: Float = 0f
        var defaultMinRate: Float = 0f

        @JvmStatic
        fun startService(context: Context) {
            context.startServiceAsUser(
                Intent(context, RefreshService::class.java),
                UserHandle.CURRENT
            )
        }
    }

    private fun writeValue(profiles: String) {
        mSharedPrefs.edit().putString(REFRESH_CONTROL, profiles).apply()
    }

    fun getOldRate() {
        defaultMaxRate = Settings.System.getFloat(
            mContext.contentResolver,
            KEY_PEAK_REFRESH_RATE,
            REFRESH_STATE_DEFAULT
        )
        defaultMinRate = Settings.System.getFloat(
            mContext.contentResolver,
            KEY_MIN_REFRESH_RATE,
            REFRESH_STATE_DEFAULT
        )
        Log.d(TAG, "getOldRate: defaultMaxRate=$defaultMaxRate, defaultMinRate=$defaultMinRate")
    }

    private fun getValue(): String {
        var value = mSharedPrefs.getString(REFRESH_CONTROL, null)

        if (value == null || value.isEmpty()) {
            value = "$REFRESH_STANDARD:$REFRESH_EXTREME"
            writeValue(value)
        }
        return value
    }

    fun writePackage(packageName: String, mode: Int) {
        var value = getValue()
        value = value.replace("$packageName,", "")
        val modes = value.split(":").toMutableList()
        val finalString: String

        when (mode) {
            STATE_STANDARD -> {
                modes[0] = modes[0] + packageName + ","
            }
            STATE_EXTREME -> {
                modes[1] = modes[1] + packageName + ","
            }
        }

        finalString = "${modes[0]}:${modes[1]}"
        writeValue(finalString)
    }

    fun getStateForPackage(packageName: String): Int {
        val value = getValue()
        val modes = value.split(":")
        var state = STATE_DEFAULT

        if (modes.size > 0 && modes[0].contains("$packageName,")) {
            state = STATE_STANDARD
            Log.d(TAG, "getStateForPackage: $packageName -> STATE_STANDARD (60Hz)")
        } else if (modes.size > 1 && modes[1].contains("$packageName,")) {
            state = STATE_EXTREME
            Log.d(TAG, "getStateForPackage: $packageName -> STATE_EXTREME (120Hz)")
        } else {
            Log.d(TAG, "getStateForPackage: $packageName -> STATE_DEFAULT (not configured)")
        }

        return state
    }

    fun setRefreshRate(packageName: String) {
        val appState = getStateForPackage(packageName)
        var maxrate: Float
        var minrate: Float

        when (appState) {
            STATE_STANDARD -> {
                maxrate = REFRESH_STATE_STANDARD  // 60 Hz
                minrate = REFRESH_STATE_STANDARD
                Log.d(TAG, "setRefreshRate: Applying STATE_STANDARD (60Hz) for $packageName")
            }
            STATE_EXTREME -> {
                maxrate = REFRESH_STATE_EXTREME   // 120 Hz
                minrate = REFRESH_STATE_EXTREME
                Log.d(TAG, "setRefreshRate: Applying STATE_EXTREME (120Hz) for $packageName")
            }
            else -> {
                // Not in any per-app list, shouldn't be called but handle gracefully
                getOldRate()
                maxrate = defaultMaxRate
                minrate = defaultMinRate
                Log.d(TAG, "setRefreshRate: App not in list, using defaults for $packageName")
            }
        }

        Log.d(TAG, "setRefreshRate: Setting refresh rate - min=$minrate, max=$maxrate for $packageName")
        // IMPORTANT: For per-app, we only want to set the values temporarily while app is in foreground
        // Don't persist to Settings.System as it will override global settings
        Settings.System.putFloat(mContext.contentResolver, KEY_MIN_REFRESH_RATE, minrate)
        Settings.System.putFloat(mContext.contentResolver, KEY_PEAK_REFRESH_RATE, maxrate)
        
        // Store the last applied app and rate for recovery
        mSharedPrefs.edit()
            .putString("last_app_package", packageName)
            .putFloat("last_app_rate", maxrate)
            .apply()
    }

    fun restoreGlobalRate() {
        Log.d(TAG, "restoreGlobalRate: Restoring global tile settings")
        val globalMinRate = mSharedPrefs.getFloat("global_min_rate", 60f)
        val globalMaxRate = mSharedPrefs.getFloat("global_max_rate", 120f)
        
        Log.d(TAG, "restoreGlobalRate: Setting min=$globalMinRate, max=$globalMaxRate")
        Settings.System.putFloat(mContext.contentResolver, KEY_MIN_REFRESH_RATE, globalMinRate)
        Settings.System.putFloat(mContext.contentResolver, KEY_PEAK_REFRESH_RATE, globalMaxRate)
    }

    fun saveGlobalRate(minRate: Float, maxRate: Float) {
        Log.d(TAG, "saveGlobalRate: Saving global settings min=$minRate, max=$maxRate")
        mSharedPrefs.edit()
            .putFloat("global_min_rate", minRate)
            .putFloat("global_max_rate", maxRate)
            .apply()
    }
}
