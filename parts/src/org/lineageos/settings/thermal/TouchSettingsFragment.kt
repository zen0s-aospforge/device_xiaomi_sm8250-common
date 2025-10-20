/**
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

import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.android.settingslib.widget.MainSwitchPreference
import org.lineageos.settings.R

class TouchSettingsFragment : PreferenceFragmentCompat(), 
    SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var sharedPrefs: SharedPreferences
    private lateinit var touchSensitivity: androidx.preference.SeekBarPreference
    private lateinit var touchResponse: androidx.preference.SeekBarPreference
    private lateinit var touchResistant: androidx.preference.SeekBarPreference
    private lateinit var gameMode: MainSwitchPreference

    private var packageName = ""

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.touch_settings)
        sharedPrefs = PreferenceManager.getDefaultSharedPreferences(requireContext())

        var appName = ""
        arguments?.let { bundle ->
            appName = bundle.getString("appName", "")
            packageName = bundle.getString("packageName", "")
        }

        // Set up action bar with back button
        val activity = requireActivity()
        if (activity.actionBar != null) {
            activity.actionBar!!.setDisplayHomeAsUpEnabled(true)
            activity.title = if (appName.isNotEmpty()) {
                resources.getString(R.string.touch_control_title_with_app, appName)
            } else {
                resources.getString(R.string.touch_control_title)
            }
        }

        gameMode = findPreference<MainSwitchPreference>(Constants.PREF_TOUCH_GAME_MODE)!!
        gameMode.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
            val isChecked = newValue as Boolean
            touchSensitivity.isEnabled = isChecked
            touchResponse.isEnabled = isChecked
            touchResistant.isEnabled = isChecked
            true
        }

        touchResistant = findPreference<androidx.preference.SeekBarPreference>(Constants.PREF_TOUCH_RESISTANT)!!
        touchResponse = findPreference<androidx.preference.SeekBarPreference>(Constants.PREF_TOUCH_RESPONSE)!!
        touchSensitivity = findPreference<androidx.preference.SeekBarPreference>(Constants.PREF_TOUCH_SENSITIVITY)!!
        
        updateDefaults()
        
        Log.d("TouchSettings", "Initialized touch settings for package: $packageName, app: $appName")
    }

    override fun onResume() {
        super.onResume()
        sharedPrefs.registerOnSharedPreferenceChangeListener(this)
        Log.d("TouchSettings", "onResume called")
    }

    override fun onPause() {
        super.onPause()
        sharedPrefs.unregisterOnSharedPreferenceChangeListener(this)
        Log.d("TouchSettings", "onPause called")
    }

    @Suppress("DEPRECATION")
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return if (item.itemId == android.R.id.home) {
            Log.d("TouchSettings", "Back button pressed")
            parentFragmentManager.popBackStack()
            true
        } else {
            super.onOptionsItemSelected(item)
        }
    }

    override fun onSharedPreferenceChanged(sharedPrefs: SharedPreferences, key: String?) {
        when (key) {
            Constants.PREF_TOUCH_GAME_MODE -> {
                val value = if (sharedPrefs.getBoolean(key, false)) 1 else 0
                updateTouchModes(value, Constants.TOUCH_GAME_MODE)
            }
            Constants.PREF_TOUCH_RESPONSE -> {
                updateTouchModes(sharedPrefs.getInt(key, 0), Constants.TOUCH_RESPONSE)
            }
            Constants.PREF_TOUCH_SENSITIVITY -> {
                updateTouchModes(sharedPrefs.getInt(key, 0), Constants.TOUCH_SENSITIVITY)
            }
            Constants.PREF_TOUCH_RESISTANT -> {
                updateTouchModes(sharedPrefs.getInt(key, 0), Constants.TOUCH_RESISTANT)
            }
        }
    }

    private fun updateDefaults() {
        val values = getTouchValues().split(",")
        if (values.size < 4) return

        val modeEnabled = values.getOrNull(Constants.TOUCH_GAME_MODE)?.toIntOrNull() == 1
        gameMode.isChecked = modeEnabled

        touchSensitivity.isEnabled = modeEnabled
        touchResponse.isEnabled = modeEnabled
        touchResistant.isEnabled = modeEnabled

        values.getOrNull(Constants.TOUCH_RESPONSE)?.toIntOrNull()?.let { 
            touchResponse.value = it 
        }
        values.getOrNull(Constants.TOUCH_SENSITIVITY)?.toIntOrNull()?.let { 
            touchSensitivity.value = it 
        }
        values.getOrNull(Constants.TOUCH_RESISTANT)?.toIntOrNull()?.let { 
            touchResistant.value = it 
        }
    }

    private fun writeTouchValues(modes: String) {
        sharedPrefs.edit().putString(packageName, modes).apply()
    }

    private fun getTouchValues(): String {
        val values = sharedPrefs.getString(packageName, null)
        return if (values.isNullOrEmpty()) {
            val defaultValues = "0,0,0,0"
            writeTouchValues(defaultValues)
            defaultValues
        } else {
            values
        }
    }

    private fun updateTouchModes(value: Int, mode: Int) {
        val values = getTouchValues().split(",").toMutableList()
        if (values.size > mode) {
            values[mode] = value.toString()
            val finalValues = values.joinToString(",")
            writeTouchValues(finalValues)
        }
    }
}