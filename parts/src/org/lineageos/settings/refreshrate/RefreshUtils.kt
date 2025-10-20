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
import androidx.preference.PreferenceManager

class RefreshUtils(context: Context) {

    private val mContext = context
    private val mSharedPrefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

    companion object {
        private const val REFRESH_CONTROL = "refresh_control"
        private const val KEY_PEAK_REFRESH_RATE = "peak_refresh_rate"
        private const val KEY_MIN_REFRESH_RATE = "min_refresh_rate"

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
        var isAppInList: Boolean = false
    }

    fun startService() {
        mContext.startServiceAsUser(
            Intent(mContext, RefreshService::class.java),
            UserHandle.CURRENT
        )
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

        if (modes[0].contains("$packageName,")) {
            state = STATE_STANDARD
        } else if (modes[1].contains("$packageName,")) {
            state = STATE_EXTREME
        }

        return state
    }

    fun setRefreshRate(packageName: String) {
        val value = getValue()
        var maxrate = defaultMaxRate
        var minrate = defaultMinRate
        isAppInList = false

        if (value.isNotEmpty()) {
            val modes = value.split(":")

            if (modes[0].contains("$packageName,")) {
                maxrate = REFRESH_STATE_STANDARD
                if (minrate > maxrate) {
                    minrate = maxrate
                }
                isAppInList = true
            } else if (modes[1].contains("$packageName,")) {
                maxrate = REFRESH_STATE_EXTREME
                if (minrate > maxrate) {
                    minrate = maxrate
                }
                isAppInList = true
            }
        }

        Settings.System.putFloat(mContext.contentResolver, KEY_MIN_REFRESH_RATE, minrate)
        Settings.System.putFloat(mContext.contentResolver, KEY_PEAK_REFRESH_RATE, maxrate)
    }
}
