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

package org.lineageos.settings.thermal

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.RemoteException
import android.os.UserHandle
import android.view.Display
import android.view.Surface
import androidx.preference.PreferenceManager
import org.lineageos.settings.utils.FileUtils
import vendor.xiaomi.hardware.touchfeature.V1_0.ITouchFeature

class ThermalUtils(context: Context) {

    private val appContext: Context = context.applicationContext
    private val sharedPrefs: SharedPreferences
    private val display: Display?
    private val touchFeature: ITouchFeature?
    private val serviceIntent: Intent
    private var enabledCache: Boolean

    companion object {
        const val STATE_DEFAULT = 0
        const val STATE_ULTRACOOL = 1
        const val STATE_STREAMING = 2
        const val STATE_BROWSER = 3
        const val STATE_CAMERA = 4
        const val STATE_DIALER = 5
        const val STATE_GAMING = 6
        const val STATE_BENCHMARK = 7

        private const val THERMAL_CONTROL = "thermal_control"
        private const val THERMAL_ENABLED = "thermal_enabled"
        private const val THERMAL_STATE_DEFAULT = "0"
        private const val THERMAL_STATE_BENCHMARK = "10"
        private const val THERMAL_STATE_BROWSER = "11"
        private const val THERMAL_STATE_CAMERA = "12"
        private const val THERMAL_STATE_DIALER = "8"
        private const val THERMAL_STATE_GAMING = "9"
        private const val THERMAL_STATE_STREAMING = "14"
        private const val THERMAL_STATE_ULTRACOOL = "52"

        private const val THERMAL_BENCHMARK = "thermal.benchmark="
        private const val THERMAL_BROWSER = "thermal.browser="
        private const val THERMAL_CAMERA = "thermal.camera="
        private const val THERMAL_DIALER = "thermal.dialer="
        private const val THERMAL_GAMING = "thermal.gaming="
        private const val THERMAL_STREAMING = "thermal.streaming="
        private const val THERMAL_ULTRACOOL = "thermal.ultracool="

        private const val THERMAL_SCONFIG = "/sys/class/thermal/thermal_message/sconfig"

        @JvmStatic
        fun startService(context: Context) {
            ThermalUtils(context).startServiceIfEnabled()
        }
    }

    private var touchModeChanged = false

    init {
        sharedPrefs = PreferenceManager.getDefaultSharedPreferences(appContext)
        enabledCache = sharedPrefs.getBoolean(THERMAL_ENABLED, true)
        serviceIntent = Intent(appContext, ThermalService::class.java)

        display = context.display

        touchFeature = try {
            ITouchFeature.getService()
        } catch (e: Exception) {
            // RemoteException or NoSuchElementException
            null
        }
    }

    fun isEnabled(): Boolean = enabledCache

    fun setEnabled(enabled: Boolean) {
        if (enabledCache == enabled) return
        enabledCache = enabled
        sharedPrefs.edit().putBoolean(THERMAL_ENABLED, enabled).apply()
        if (enabled) {
            startServiceIfEnabled()
        } else {
            clearConfiguredPackages()
            setDefaultThermalProfile()
            resetTouchModes()
            stopServiceInternal()
        }
    }

    private fun clearConfiguredPackages() {
        val modes = listOf(
            THERMAL_BENCHMARK,
            THERMAL_BROWSER,
            THERMAL_CAMERA,
            THERMAL_DIALER,
            THERMAL_GAMING,
            THERMAL_STREAMING,
            THERMAL_ULTRACOOL
        )

        val clearedValue = modes.joinToString(":")
        writeValue(clearedValue)
    }

    fun startServiceIfEnabled() {
        if (!enabledCache) {
            stopServiceInternal()
            return
        }
        if (!FileUtils.fileExists(THERMAL_SCONFIG)) {
            stopServiceInternal()
            return
        }
        appContext.startServiceAsUser(serviceIntent, UserHandle.CURRENT)
    }

    private fun stopServiceInternal() {
        appContext.stopService(serviceIntent)
    }

    private fun writeValue(profiles: String) {
        sharedPrefs.edit().putString(THERMAL_CONTROL, profiles).apply()
    }

    private fun getValue(): String {
        var value = sharedPrefs.getString(THERMAL_CONTROL, null)

        if (value != null) {
            val modes = value.split(":")
            if (modes.size < 7) {
                if (modes.size == 6) {
                    // Migrate existing 6-mode data by appending the new ultracool bucket
                    val migrated = value + ":" + THERMAL_ULTRACOOL
                    writeValue(migrated)
                    return migrated
                } else {
                    value = null
                }
            }
        }

        if (value.isNullOrEmpty()) {
            value = "$THERMAL_BENCHMARK:$THERMAL_BROWSER:$THERMAL_CAMERA:$THERMAL_DIALER:$THERMAL_GAMING:$THERMAL_STREAMING:$THERMAL_ULTRACOOL"
            writeValue(value)
        }
        return value
    }

    fun writePackage(packageName: String, mode: Int) {
        var value = getValue()
        value = value.replace("$packageName,", "")
        val modes = value.split(":").toMutableList()

        when (mode) {
            STATE_BENCHMARK -> modes[0] = modes[0] + "$packageName,"
            STATE_BROWSER -> modes[1] = modes[1] + "$packageName,"
            STATE_CAMERA -> modes[2] = modes[2] + "$packageName,"
            STATE_DIALER -> modes[3] = modes[3] + "$packageName,"
            STATE_GAMING -> modes[4] = modes[4] + "$packageName,"
            STATE_STREAMING -> modes[5] = modes[5] + "$packageName,"
            STATE_ULTRACOOL -> modes[6] = modes[6] + "$packageName,"
        }

        val finalString = modes.joinToString(":")
        writeValue(finalString)
    }

    fun getStateForPackage(packageName: String): Int {
        val value = getValue()
        val modes = value.split(":")
        
        return when {
            modes.getOrNull(0)?.contains("$packageName,") == true -> STATE_BENCHMARK
            modes.getOrNull(1)?.contains("$packageName,") == true -> STATE_BROWSER
            modes.getOrNull(2)?.contains("$packageName,") == true -> STATE_CAMERA
            modes.getOrNull(3)?.contains("$packageName,") == true -> STATE_DIALER
            modes.getOrNull(4)?.contains("$packageName,") == true -> STATE_GAMING
            modes.getOrNull(5)?.contains("$packageName,") == true -> STATE_STREAMING
            modes.getOrNull(6)?.contains("$packageName,") == true -> STATE_ULTRACOOL
            else -> STATE_DEFAULT
        }
    }

    fun setDefaultThermalProfile() {
        if (FileUtils.fileExists(THERMAL_SCONFIG)) {
            FileUtils.writeLine(THERMAL_SCONFIG, THERMAL_STATE_DEFAULT)
        }
    }

    fun setThermalProfile(packageName: String) {
        if (!enabledCache || !FileUtils.fileExists(THERMAL_SCONFIG)) {
            setDefaultThermalProfile()
            return
        }
        val value = getValue()
        val modes = value.split(":")

        val state = when {
            modes.getOrNull(0)?.contains("$packageName,") == true -> THERMAL_STATE_BENCHMARK
            modes.getOrNull(1)?.contains("$packageName,") == true -> THERMAL_STATE_BROWSER
            modes.getOrNull(2)?.contains("$packageName,") == true -> THERMAL_STATE_CAMERA
            modes.getOrNull(3)?.contains("$packageName,") == true -> THERMAL_STATE_DIALER
            modes.getOrNull(4)?.contains("$packageName,") == true -> THERMAL_STATE_GAMING
            modes.getOrNull(5)?.contains("$packageName,") == true -> THERMAL_STATE_STREAMING
            modes.getOrNull(6)?.contains("$packageName,") == true -> THERMAL_STATE_ULTRACOOL
            else -> THERMAL_STATE_DEFAULT
        }

        FileUtils.writeLine(THERMAL_SCONFIG, state)

        if (state == THERMAL_STATE_BENCHMARK || state == THERMAL_STATE_GAMING) {
            updateTouchModes(packageName)
        } else if (touchModeChanged) {
            resetTouchModes()
        }
    }

    fun setThermalProfileForce(mode: Int) {
        if (!FileUtils.fileExists(THERMAL_SCONFIG)) {
            return
        }
        val state = when (mode) {
            STATE_BENCHMARK -> THERMAL_STATE_BENCHMARK
            STATE_BROWSER -> THERMAL_STATE_BROWSER
            STATE_CAMERA -> THERMAL_STATE_CAMERA
            STATE_DIALER -> THERMAL_STATE_DIALER
            STATE_GAMING -> THERMAL_STATE_GAMING
            STATE_STREAMING -> THERMAL_STATE_STREAMING
            STATE_ULTRACOOL -> THERMAL_STATE_ULTRACOOL
            else -> THERMAL_STATE_DEFAULT
        }

        FileUtils.writeLine(THERMAL_SCONFIG, state)
    }

    private fun updateTouchModes(packageName: String) {
        val values = sharedPrefs.getString(packageName, null)
        resetTouchModes()

        if (values.isNullOrEmpty()) {
            return
        }

        val value = values.split(",")
        if (value.size < 4) return

        val gameMode = value.getOrNull(Constants.TOUCH_GAME_MODE)?.toIntOrNull() ?: 0
        val touchResponse = value.getOrNull(Constants.TOUCH_RESPONSE)?.toIntOrNull() ?: 0
        val touchSensitivity = value.getOrNull(Constants.TOUCH_SENSITIVITY)?.toIntOrNull() ?: 0
        val touchResistant = value.getOrNull(Constants.TOUCH_RESISTANT)?.toIntOrNull() ?: 0
        val touchActiveMode = if (touchResponse != 0 && touchSensitivity != 0 && touchResistant != 0) 1 else 0

        touchFeature?.let { feature ->
            try {
                feature.setTouchMode(Constants.MODE_TOUCH_TOLERANCE, touchSensitivity)
                feature.setTouchMode(Constants.MODE_TOUCH_UP_THRESHOLD, touchResponse)
                feature.setTouchMode(Constants.MODE_TOUCH_EDGE_FILTER, touchResistant)
                feature.setTouchMode(Constants.MODE_TOUCH_GAME_MODE, gameMode)
                feature.setTouchMode(Constants.MODE_TOUCH_ACTIVE_MODE, touchActiveMode)
            } catch (e: RemoteException) {
                // Do nothing
            }
        }

        touchModeChanged = true
        updateTouchRotation()
    }

    fun resetTouchModes() {
        if (!touchModeChanged) return

        touchFeature?.let { feature ->
            try {
                feature.resetTouchMode(Constants.MODE_TOUCH_GAME_MODE)
                feature.resetTouchMode(Constants.MODE_TOUCH_ACTIVE_MODE)
                feature.resetTouchMode(Constants.MODE_TOUCH_UP_THRESHOLD)
                feature.resetTouchMode(Constants.MODE_TOUCH_TOLERANCE)
                feature.resetTouchMode(Constants.MODE_TOUCH_EDGE_FILTER)
                feature.resetTouchMode(Constants.MODE_TOUCH_ROTATION)
            } catch (e: RemoteException) {
                // Do nothing
            }
        }

        touchModeChanged = false
    }

    fun updateTouchRotation() {
        if (!touchModeChanged) return

        val touchRotation = when (display?.rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 1
            Surface.ROTATION_180 -> 2
            Surface.ROTATION_270 -> 3
            else -> 0
        }

        touchFeature?.let { feature ->
            try {
                feature.setTouchMode(Constants.MODE_TOUCH_ROTATION, touchRotation)
            } catch (e: RemoteException) {
                // Do nothing
            }
        }
    }
}