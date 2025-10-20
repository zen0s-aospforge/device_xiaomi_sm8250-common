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
import android.view.MenuItem
import android.widget.CompoundButton
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.android.settingslib.widget.MainSwitchPreference
import org.lineageos.settings.R
import org.lineageos.settings.widget.SeekBarPreference

class TouchSettingsFragment : PreferenceFragmentCompat(), 
    SharedPreferences.OnSharedPreferenceChangeListener, 
    CompoundButton.OnCheckedChangeListener {

    private lateinit var sharedPrefs: SharedPreferences
    private lateinit var touchSensitivity: SeekBarPreference
    private lateinit var touchResponse: SeekBarPreference
    private lateinit var touchResistant: SeekBarPreference
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

        requireActivity().title = if (appName.isNotEmpty()) {
            resources.getString(R.string.touch_control_title_with_app, appName)
        } else {
            resources.getString(R.string.touch_control_title)
        }

        gameMode = findPreference<MainSwitchPreference>(Constants.PREF_TOUCH_GAME_MODE)!!.apply {
            addOnSwitchChangeListener(this@TouchSettingsFragment)
        }

        touchResistant = findPreference(Constants.PREF_TOUCH_RESISTANT)!!
        touchResponse = findPreference(Constants.PREF_TOUCH_RESPONSE)!!
        touchSensitivity = findPreference(Constants.PREF_TOUCH_SENSITIVITY)!!
        
        updateDefaults()
    }

    override fun onResume() {
        super.onResume()
        sharedPrefs.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onPause() {
        super.onPause()
        sharedPrefs.unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return if (item.itemId == android.R.id.home) {
            requireActivity().onBackPressed()
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

    override fun onCheckedChanged(buttonView: CompoundButton, isChecked: Boolean) {
        gameMode.isChecked = isChecked
        touchSensitivity.isEnabled = isChecked
        touchResponse.isEnabled = isChecked
        touchResistant.isEnabled = isChecked
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
            touchResponse.progress = it 
        }
        values.getOrNull(Constants.TOUCH_SENSITIVITY)?.toIntOrNull()?.let { 
            touchSensitivity.progress = it 
        }
        values.getOrNull(Constants.TOUCH_RESISTANT)?.toIntOrNull()?.let { 
            touchResistant.progress = it 
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